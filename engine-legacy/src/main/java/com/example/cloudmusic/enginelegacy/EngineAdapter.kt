package com.example.cloudmusic.enginelegacy

import android.content.Context
import android.util.Log
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.engine.api.EngineConfig
import com.example.cloudmusic.engine.api.EngineHandle
import com.example.cloudmusic.engine.api.EngineSource
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.model.IDataSource
import com.netease.cloudmusic.android.sdk.playerengine.DataProvider
import com.netease.cloudmusic.android.sdk.playerengine.DataRequestErrorCallback
import com.netease.cloudmusic.android.sdk.playerengine.PlaybackEngine
import com.netease.cloudmusic.android.sdk.playerengine.PlaybackEngineListener
import com.netease.cloudmusic.android.sdk.playerengine.PlayerDataSource
import com.netease.cloudmusic.android.sdk.playerengine.ResourceInfo
import com.netease.cloudmusic.android.sdk.playerengine.UrlResult
import com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-L L1（`12 §36` / `03-06 §4/§5`）· **照搬舱与自研侧唯一的缝**。
 *
 * 把 [IMusicEngine] 的 14 方法映射到舱内 `PlaybackEngine` 的真实 API
 * （`12 §34` jadx 实证：构造 `PlaybackEngine(J)` → `setDataSource(PlayerDataSource)`
 * → `prepareAsync()` → `play/pause/stop/seekTo/...`），数据源经 `PlayerDataSource`
 * 的 `DataProvider.getUrl() / getLocalPath()` 交给 native 自行拉流
 * （`16 §3.2` 决策表：不消费 Java 字节流）。
 *
 * **不变量与降级**：
 * - 「同一时刻只允许一个有效 handle」（`03-06 §4` / §15 T06）：新 `prepare` 前必须 `release`。
 * - `release` 同时关闭 `IDataSource`（§15 T09）。
 * - 任何 native 调用失败（`UnsatisfiedLinkError` / `JNI` 异常）都转成
 *   [CloudMusicError.DsUpstream] 并置 `ERROR`，**不把 native 崩溃泄漏到上层**。
 *
 * **边界（V0.1，必须真机 G01 收口）**：舱内 `PlayerDataSource` / `DataProvider` /
 * `UrlResult` 是 `§10.4` 登记簿里按编译报错补的最小实现，字段名与 JNI 回调形态
 * 尚未与 so 侧逐字段校准；`setQuality` / `attachAudioEffect` 按 `03-06 §5` V0.1 不启用。
 * 因此**本类能编译、能被 DI 注入，但不保证真机首次播放出声** —— 那是 G01 的判据。
 *
 * **12 §54（第四十五批）**：`createDataRequestHandler()` 已按 `12 §53` 的静态实证接通
 * [UrlDataRequest]（`SourceDataRequest` 子类 + 纯 JVM [RangeFetcher]），让 native 首次拿到
 * 取流对象并发起 HTTP Range 请求；这是阶段 A/B，**仍需真机验证**回传协议细节，
 * 不据此宣称「已出声」或回填 `ENGINE-FINGERPRINT`。
 */
/**
 * 12 §56（第四十六批 · 取流链路诊断）· `:engine-legacy` 内部的极轻量 logcat 探针。
 *
 * §55 真机阶段 B 失败后，无法确定 native 是否调用过 `DataProvider` 的取流方法；
 * 本对象让九个 `DataProvider` 回调与 `UrlDataRequest` 的取流链路都能在 logcat 里
 * 逐条对上（TAG `CM-DataReq`）。**只加日志、不改任何取流行为 / 分支判定**。
 * JVM 单测中 `android.util.Log` 是未 mock 的 stub，故吞掉异常，保证纯 JVM 逻辑可测。
 */
internal object EngineDiag {
    const val TAG = "CM-DataReq"

    fun i(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // JVM 单测环境：诊断日志不得影响纯逻辑测试。
        }
    }
}

class EngineAdapter : IMusicEngine {

    /**
     * 12 §57（第四十七批 · `cache=0x0` 校准实验）：由 DI 注入的 Application Context。
     * 真机日志 `PlaybackEngine ctor: cache=0x0` 说明 V0.1 把 `cacheManagerHandle` 传成 0；
     * 竞品 `NMVirtualFileManager.InitPtr()` 会分配真实 C++ 对象（`libNMCommonCache.so` 符号齐全）。
     * 本实验创建 VFM 并把 `native_ptr_` 传给 `PlaybackEngine(long)`，**假设待真机验证**。
     */
    @Volatile
    var appContext: Context? = null

    /**
     * §61.26（G05 真机实证）：prepare（含 native 引擎 / VFM 构造）与完成 / 错误触发的
     * [releaseAllInternal] 必须互斥——自动进曲在 msg=2 后 ~40ms 就发起下一首 prepare，
     * 与旧引擎的 native release 并发会踩出
     * `libncmplayerengine AudioPlayerUnit::SetAudioListener` SEGV（tombstone 实证）。
     * VFM 不再有共享字段：每次 prepare 的实例随 [Slot] 生命周期释放。
     */
    private val lifecycleLock = Any()

    /**
     * 12 §37：native 事件码常量表尚无实证（13 §7.1 要求「事件码 + 轮询」建状态机），
     * 因此这里**不猜** ENDED / ERROR，只把 `onEngineEvent` 原样透传给 G01 抓日志。
     * 真机拿到 msg 枚举后再补 `EngineState` 映射。
     */
    data class NativeEngineEvent(
        val msg: Int,
        val songId: String,
        val arg1: Int,
        val arg2: Int,
    )

    private class Slot(
        val engine: PlaybackEngine,
        val source: EngineSource,
        val provider: UrlDataProvider,
        /** §61.26：缓存管理器归 slot 所有——G05 真机实证共享字段 `virtualFileManager`
         *  会在「msg=2 释放线程 vs 自动进曲 prepare 线程」竞态下被另一方 release，
         *  旧引擎 release 途中缓存句柄悬空 → libncmplayerengine `SetAudioListener` SEGV。 */
        val vfm: NMVirtualFileManager?,
    )

    /** 13 §3 的 9 方法音源接口：只喂 URL / localPath，不消费 Java 字节流。 */
    private class UrlDataProvider(private val source: EngineSource) : DataProvider {
        @Volatile
        var attached: IDataSource? = null

        /** 12 §54：native 取流句柄单例，仅 URL 源创建；[close] 时 `release()` 释放 C++ 对象。 */
        @Volatile
        private var request: UrlDataRequest? = null

        @Volatile
        private var probedLength: Long? = null

        /**
         * 12 §57（第四十七批）：Room `tracks` 表没有文件长度列，夹具的
         * `expectedLength` 恒为 0；真机日志显示
         * `CreateBufferSource resource config:{"size":0}` → `NMBufferSourceImp Start Use Memory Mode`
         * → 不向 Java 发起 `Start` 取流 → `av_probe_input_buffer read error`。
         * 长度未知时用一次真实 `HEAD` 探测 Content-Length（不硬编码、不伪造）；
         * 失败返回 -1（未知），行为退回上一轮，可安全回退。
         */
        fun resolveLength(): Long {
            if (source.expectedLength > 0) return source.expectedLength
            probedLength?.takeIf { it > 0 }?.let { return it }
            val target = source.url
            val value = if (target.isNullOrBlank()) {
                -1L
            } else {
                // 放到后台线程：prepare() 可能在主线程，Java 网络请求会触发
                // NetworkOnMainThreadException；用 latch 同步等结果（最多 8s）。
                val probe = java.util.concurrent.atomic.AtomicLong(-1L)
                val done = java.util.concurrent.CountDownLatch(1)
                Thread({
                    try {
                        val connection = URL(target).openConnection() as HttpURLConnection
                        // 12 §58：恢复 §57 E4 已实证成功的 HEAD 形式。GET/Range 变体在后续
                        // 现场出现 App→127.0.0.1:8765 的 TCP 连接超时（设备 curl 仍 200，
                        // 属现场网络环境问题，与请求方法无关），故不保留该变体。
                        // 保留 responseCode 显式触发连接与 8s 等待，只增强健壮性、不改语义。
                        connection.requestMethod = "HEAD"
                        connection.connectTimeout = 5_000
                        connection.readTimeout = 5_000
                        try {
                            // 显式触发连接（部分 Android 实现里 getHeaderField 不会自己 connect）。
                            val status = connection.responseCode
                            if (status in 200..299) {
                                val fromRange = RangeFetcher.parseTotal(
                                    connection.getHeaderField("Content-Range"),
                                )
                                val fromHeader = connection.getHeaderField("Content-Length")
                                    ?.trim()?.toLongOrNull() ?: -1L
                                probe.set(
                                    listOf(fromRange, fromHeader, connection.contentLengthLong)
                                        .firstOrNull { it > 0 } ?: -1L,
                                )
                            }
                        } finally {
                            connection.disconnect()
                        }
                    } catch (t: Throwable) {
                        EngineDiag.i("probeLength FAILED ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        done.countDown()
                    }
                }, "cloudmusic-probe").apply { isDaemon = true }.start()
                if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    EngineDiag.i("probeLength TIMEOUT url=$target")
                }
                probe.get()
            }
            probedLength = value
            EngineDiag.i("probeLength=$value url=$target")
            return value
        }

        override fun getUrl(): UrlResult {
            val result = UrlResult(
                source.url.orEmpty(),
                resolveLength(),
                source.mimeHint,
                0L, 0, "",
            )
            EngineDiag.i("getUrl url=${result.url} fileSize=${result.fileSize} format=${result.format}")
            return result
        }

        override fun getLocalPath(): String {
            val path = source.localPath.orEmpty()
            EngineDiag.i("getLocalPath path=$path")
            return path
        }

        override fun openLocalFd(path: String): Int {
            EngineDiag.i("openLocalFd path=$path -> -1 (V0.1 未实现)")
            return -1
        }

        override fun closeLocalFd(fd: Int) {
            EngineDiag.i("closeLocalFd fd=$fd")
        }

        override fun onExpire(url: String): UrlResult {
            EngineDiag.i("onExpire url=$url")
            return getUrl()
        }

        override fun setDataRequestErrorCallback(
            callback: DataRequestErrorCallback?,
            enabled: Boolean,
        ) {
            EngineDiag.i(
                "setDataRequestErrorCallback callback=${callback?.javaClass?.name} enabled=$enabled",
            )
        }

        /**
         * 12 §54（第四十五批）· 按 `12 §53` 的实证结论接通取流句柄。
         *
         * native 经 `NMBufferSource.SetDataRequest` 绑定 [UrlDataRequest] 后回调
         * `Start(requestId, offset, length)`（立即返回 0、后台 HTTP Range 取流），
         * 数据经 `OnDataRequestBegin/Data/End` 回传。**仅 URL 源创建**，LOCAL 源仍 null。
         * 缓存单例：竞品的 `createDataRequestHandler()` 同样返回字段而非每次 new，
         * 避免重复 `InitPtr()` 泄漏 native C++ 对象。
         *
         * 类型 / 参数语义 / 回传首参均有 smali 实证
         * （`analysis/data-request-handler-evidence-2026-11-07.md §2.5`）；**仍待真机校准**的是
         * `OnDataRequestData` 第 2/3 个 long 与 `OnDataRequestEnd` 成功时 String 的取值
         * （报告 §5 阶段 C）。**编译通过 ≠ 出声**，A3 仍 `DEEP RUN`、舱不冻结。
         */
        override fun createDataRequestHandler(): Any? {
            EngineDiag.i("createDataRequestHandler url=${source.url} cached=${request != null}")
            val url = source.url ?: return null
            return request ?: synchronized(this) {
                request ?: UrlDataRequest(url).also {
                    request = it
                    EngineDiag.i("createDataRequestHandler -> NEW UrlDataRequest")
                }
            }
        }

        override fun cancelRequest() {
            EngineDiag.i("cancelRequest hasHandler=${request != null}")
            request?.Stop()
        }

        override fun close() {
            EngineDiag.i("close hasHandler=${request != null}")
            request?.release()
            request = null
            attached?.close()
            attached = null
        }
    }

    private val slots = ConcurrentHashMap<Long, Slot>()
    private val _state = MutableStateFlow(EngineState.IDLE)
    private val _nativeEvent = MutableStateFlow<NativeEngineEvent?>(null)
    private var nextRaw = 1L

    /**
     * §61.25（E25 真机暴露 · 重播竞态）：`CNCMAudioPlayer::PrepareAsync` 是异步的，
     * play 若在 prepare 完成通知（msg=200）之前到达，native 侧
     * `CNCMAudioPlayer::Play status 2`（preparing）直接丢弃 → 引擎永不出声。
     * 每次 prepare 重建门闩，msg=200（成功）/ msg=100（失败）放行；
     * play() 在下发 native 前等待（10s 超时兜底）。
     */
    @Volatile
    private var prepareGate: CountDownLatch? = null

    /** 诊断用（非 IMusicEngine 契约）：G01 抓 native 事件码校准状态机。 */
    fun nativeEvents(): Flow<NativeEngineEvent?> = _nativeEvent.asStateFlow()

    /** 只设置 Java 字段，不触发 native（PlaybackEngine.setListener 实证），故安全。 */
    private inner class StateListener : PlaybackEngineListener() {
        override fun onEngineEvent(msg: Int, songId: String, arg1: Int, arg2: Int) {
            _nativeEvent.value = NativeEngineEvent(msg, songId, arg1, arg2)
            // 12 §61.8（第五十一批续）：msg=100 = native 播放器错误通知（E13b 实证：
            // prepare 中止时 msg=100 arg1=1 arg2=-103，随后 native 自行 Abort/关闭取流
            // 句柄）。此时 handle 已死，必须释放 slot：
            // ① imp Close → vfile Close → NMSlicesFile 索引提交（磁盘缓存命中的前提，
            //    E11b/E12c 实证 force-stop 跳过 Close 导致索引不提交、命中读 0 字节）；
            // ② 否则 slots 非空，下一次 prepare 会触发 single-handle invariant 崩溃。
            // 事件来自 native 回调线程：释放动作投到独立线程执行，避免与 player 内部
            // 锁潜在死锁；期间到达的 play/pause 命中已清空的 slots → 显式 DsNotFound。
            if (msg == 200 && arg1 == 702) {
                // §61.25：prepare 完成通知。真机 E25 实证 msg=200 发两次：
                // arg1=701（openSource 后、doPrepareAsync 中段）与 arg1=702
                // （mAudioStream start / notifyTrackInfoReady 后，真正的完成）。
                // 701 放行会让 play 抢在音频流管线建成前到达，解码器 Read
                // Finish Sync:0 饿死。只在 702 放行。
                prepareGate?.countDown()
            } else if (msg == 100) {
                // §61.26（G07）：native 错误通知须落入 lastError()（验收判据
                // "ERROR + lastError() 非空"）；arg1/arg2 原样入串便于对账。
                // countDown 放行 play（失败路径不得卡 10s 门闩）。
                prepareGate?.countDown()
                fail(
                    CloudMusicError.DsUpstream(
                        "ENGINE_MSG100",
                        "native 错误通知 msg=100 arg1=$arg1 arg2=$arg2",
                    ),
                )
                _state.value = EngineState.ERROR
                Thread {
                    releaseAllInternal()
                }.apply { isDaemon = true }.start()
            } else if (msg == 2) {
                // 12 §61.23（第五十一批续）：msg=2 = 播放完成通知（E23 实证：
                // doPlayCompleted kPlayerPlaybackCompeted 后紧随 msg=2）。
                // 释放 slot → imp Close → NMSlicesFile 索引提交（缓存命中前提）。
                // §61.26（G05）：状态置 **ENDED**（契约语义：播完等消费方裁决——
                // PlayerController 据此自动进下一首；releaseAllInternal 不再降级 IDLE，
                // ENDED 保持到下一次 prepare / stop）。
                prepareGate?.countDown()
                _state.value = EngineState.ENDED
                Thread {
                    releaseAllInternal()
                }.apply { isDaemon = true }.start()
            }
        }
    }

    /** §61.8：错误后全量释放（镜像 [release] 的清理序），native 回调线程外执行。 */
    private fun releaseAllInternal() {
        // §61.26：与 prepare 互斥（G05 native SEGV 实证），且 VFM 归 slot 各自释放。
        synchronized(lifecycleLock) {
            try {
                for ((raw, slot) in slots) {
                    runCatching { slot.engine.release() }
                    runCatching { slot.provider.close() }
                    runCatching { slot.vfm?.release() }
                    slots.remove(raw)
                }
                // §61.26：不再降级状态——msg=2 置 ENDED / msg=100 置 ERROR 语义保留
                // 到下一次 prepare（PREPARING）或 stop（IDLE），供消费方裁决。
            } catch (_: Throwable) {
                // 释放失败不阻塞回调线程；下一次 prepare 会因残留 slot 显式报错。
            }
        }
    }

    @Volatile
    private var error: CloudMusicError? = null

    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        synchronized(lifecycleLock) {
            check(slots.isEmpty()) {
                "single handle invariant: release() before a new prepare() (03-06 §4 / §15 T06)"
            }
            error = null
            prepareGate = CountDownLatch(1)
            _state.value = EngineState.PREPARING
            return try {
            // 12 §34 D-104：按类实证顺序加载 6 个依赖库（去重、失败显式抛错）。
            EngineNativeLoader.loadChain(*EngineNativeLoader.PLAYBACK_ENGINE_CHAIN)
            // 12 §57：不再把 cacheManagerHandle 写死为 0。VFM 的 InitPtr() 由
            // EngineNativeLoader 已加载的 NMCommonCache 提供；失败时 native_ptr_ 仍为 0，
            // 行为退回 §56 基线，故该实验可安全回退。
            val vfm = appContext?.let { NMVirtualFileManager(it) }
            // 12 §61（第五十一批 · 用户 2026-11-08 拍板批准路线 2）：初始化缓存根目录，
            // 配合 SourceConfig.cacheLocal=true 走 normal mode（见 SourceConfig 注释）。
            // SetConfig 的 JSON 键名经反汇编钉死（0xe3e4）："dir" / "max_size" /
            // "slice_size"（int）/"cache_clean_range_min|max"（float 比例，缺省 0.8/1.0）。
            // 根目录用 App 内部缓存子目录（卸载即清、不外置）；上限 512MiB。
            vfm?.let { manager ->
                appContext?.let { context ->
                    runCatching {
                        // 12 §61.79（第一百零六批，用户 2026-11-11 拍板「接线回滚一起做」）：
                        // 回滚 §61.69 的 level-5 诊断接线。§61.70 勘误已证 native
                        // g_level 进程启动恒为 0、I/D/W/E 日志默认全开，level=5 反而
                        // 抑制 I/D；且 INMLogger 空类缺 BIInfo 会令 GetMethodID 失败，
                        // 接线零增益且有风险。§61.73/§61.75 真机取证用的 APK 本就
                        // 不含接线（基线 APK），回滚后源码与现场一致。
                        // 本回滚只删诊断接线，不动 SetConfig / VFM / LOCAL·REMOTE
                        // 分叉等任何功能代码；不是 F4 修复（绕行 ≠ 修复）。
                        val dir = java.io.File(context.cacheDir, "legacy_engine_cache")
                        dir.mkdirs()
                        val json = "{\"dir\":\"${dir.absolutePath}\"," +
                            "\"max_size\":536870912,\"slice_size\":1048576," +
                            "\"cache_clean_range_min\":0.8,\"cache_clean_range_max\":1.0}"
                        manager.SetConfig(json)
                        EngineDiag.i("SetConfig ok dir=${dir.absolutePath} max=512MiB")
                    }.onFailure {
                        EngineDiag.i("SetConfig FAILED ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
            }
            val cacheHandle = vfm?.getNativePtr() ?: 0L
            EngineDiag.i("cacheManagerHandle=$cacheHandle hasContext=${appContext != null}")
            val engine = PlaybackEngine(cacheHandle)
            engine.setListener(StateListener())   // 12 §37：接通回调链，不猜事件码
            val provider = UrlDataProvider(source)
            // 12 §50 真机 A3：URL / 回环代理源必须让 native 走 REMOTE 分支取流，
            // 否则 BufferSource 只查空的 localPath，Play 有焦点但没有音频数据。
            EngineDiag.i(
                "prepare trackId=${source.trackId} remote=${source.url != null} " +
                    "url=${source.url} expectedLength=${source.expectedLength}",
            )
            // 12 §61.48 / F2-b：extension 必须是真实扩展名，不能是 MIME 字符串。
            // §61.47 真机取证已证实：LOCAL 源（url=null）旧链落到 source.mimeHint，
            // 日志为 resourceInfo extension=audio/mpeg fileSize=-1；native 虽经
            // XorLocalSource 内容探测仍可播 mp3，但语义错配真实存在，且其它格式
            // 可能依赖 extension 选择 demuxer（待 F3 逐格式矩阵证实，不预断）。
            // 现统一由 :core-model 的 AudioFormats 单一真相源推断：URL 后缀 →
            // LOCAL 路径真实后缀 → mimeHint 反查已登记 MIME → 空串（不编造）。
            // 历史 A3 已有 extension=mp3 成功日志（a3-phase-ab-evidence §E4），
            // 传真实扩展名不是新猜测；零新增 Gradle 依赖、P0 native 舱零改动。
            val extension = com.example.cloudmusic.model.AudioFormats.extOfSource(
                source.url, source.localPath, source.mimeHint,
            )
            // 12 §57：`CreateBufferSource` 的 resource config "size" 取自 ResourceInfo.fileSize，
            // 0 会让 NMBufferSourceImp 走 Memory Mode 而不向 Java 发起 Start 取流。
            val length = provider.resolveLength()
            val info = ResourceInfo(extension, length, source.trackId, "")
            EngineDiag.i("resourceInfo extension=$extension fileSize=$length")
            val pds = PlayerDataSource(provider, source.trackId, source.url != null, info)
            engine.setDataSource(pds)
            engine.prepareAsync()
            val raw = nextRaw++
            slots[raw] = Slot(engine, source, provider, vfm)
            _state.value = EngineState.PAUSED
            EngineHandle(raw)
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_PREPARE", t.message ?: "native 引擎准备失败"))
            throw CloudMusicError.DsUpstream("ENGINE_PREPARE", t.message ?: "native 引擎准备失败")
        }
        }
    }

    private fun slot(handle: EngineHandle): Slot =
        slots[handle.raw] ?: throw CloudMusicError.DsNotFound("无效或已释放的 EngineHandle(${handle.raw})")

    /** native 异常 → 统一错误态，避免 JNI 崩溃穿透到 UI 层。 */
    private fun fail(e: CloudMusicError) {
        error = e
        _state.value = EngineState.ERROR
    }

    private inline fun guard(handle: EngineHandle, block: (Slot) -> Unit) {
        val s = slot(handle)
        try {
            block(s)
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_CALL", t.message ?: "native 调用失败"))
        }
    }

    override fun attachDataSource(handle: EngineHandle, ds: IDataSource) {
        slot(handle).provider.attached = ds
    }

    override fun play(handle: EngineHandle) = guard(handle) {
        // §61.25（E25 真机暴露 · 重播竞态）：play 早于 prepare 完成会被 native
        // 丢弃（Play status 2）。等 msg=200 放行；10s 超时兜底（异常路径 msg=100
        // 亦放行，不会卡死）。
        prepareGate?.await(10, TimeUnit.SECONDS)
        it.engine.play(false)
        _state.value = EngineState.PLAYING
    }

    override fun pause(handle: EngineHandle) = guard(handle) {
        it.engine.pause(false)
        _state.value = EngineState.PAUSED
    }

    override fun stop(handle: EngineHandle) = guard(handle) {
        it.engine.stop()
        _state.value = EngineState.IDLE
    }

    override fun seek(handle: EngineHandle, positionMs: Long) = guard(handle) {
        it.engine.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setVolume(handle: EngineHandle, volume: Float) {
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        guard(handle) { it.engine.setVolume(volume) }
    }

    override fun positionMs(handle: EngineHandle): Long {
        val s = slot(handle)
        return try {
            s.engine.getCurrentPosition()
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_POSITION", t.message ?: "读取进度失败"))
            0L
        }
    }

    override fun durationMs(handle: EngineHandle): Long {
        val s = slot(handle)
        return try {
            s.engine.getDuration()
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_DURATION", t.message ?: "读取时长失败"))
            -1L
        }
    }

    override fun state(): Flow<EngineState> = _state.asStateFlow()

    /** 03-06 §5：换质量属 `Playback.nativeChangeQuality`，V0.1 不启用。 */
    override fun setQuality(handle: EngineHandle, quality: Int) {
        slot(handle)
    }

    /** 03-06 §5：音效句柄需 native effectHandle，V0.1 不启用。 */
    override fun attachAudioEffect(handle: EngineHandle, effectId: Int) {
        slot(handle)
    }

    /** §61.118（T5-1）：A 路线无 audiofx 接线，no-op（仅句柄校验）。 */
    override fun setEqualizerBands(handle: EngineHandle, levelsMillibel: List<Int>) {
        slot(handle)
    }

    override fun release(handle: EngineHandle) {
        // §61.26：与 prepare / releaseAllInternal 互斥；VFM 随 slot 释放（G05 实证
        // 共享字段在自动进曲竞态下会释放到别人刚创建的实例上）。
        synchronized(lifecycleLock) {
            val s = slots.remove(handle.raw) ?: return
            try {
                s.engine.release()
            } catch (t: Throwable) {
                fail(CloudMusicError.DsUpstream("ENGINE_RELEASE", t.message ?: "释放失败"))
            } finally {
                s.provider.close()   // §15 T09：release 同时关闭 IDataSource
                runCatching { s.vfm?.release() }
                error = null
                _state.value = EngineState.IDLE
            }
        }
    }

    override fun lastError(): CloudMusicError? = error
}
