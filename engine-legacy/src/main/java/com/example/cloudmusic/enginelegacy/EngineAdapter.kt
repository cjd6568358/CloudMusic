package com.example.cloudmusic.enginelegacy

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
import com.netease.cloudmusic.android.sdk.playerengine.UrlResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

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
 */
class EngineAdapter : IMusicEngine {

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
    )

    /** 13 §3 的 9 方法音源接口：只喂 URL / localPath，不消费 Java 字节流。 */
    private class UrlDataProvider(private val source: EngineSource) : DataProvider {
        @Volatile
        var attached: IDataSource? = null

        override fun getUrl(): UrlResult = UrlResult(
            source.url.orEmpty(),
            source.expectedLength,
            source.mimeHint,
            0L, 0, "",
        )

        override fun getLocalPath(): String = source.localPath.orEmpty()
        override fun openLocalFd(path: String): Int = -1
        override fun closeLocalFd(fd: Int) = Unit
        override fun onExpire(url: String): UrlResult = getUrl()
        override fun setDataRequestErrorCallback(
            callback: DataRequestErrorCallback?,
            enabled: Boolean,
        ) = Unit

        override fun createDataRequestHandler(): Any? = null
        override fun cancelRequest() = Unit
        override fun close() {
            attached?.close()
            attached = null
        }
    }

    private val slots = ConcurrentHashMap<Long, Slot>()
    private val _state = MutableStateFlow(EngineState.IDLE)
    private val _nativeEvent = MutableStateFlow<NativeEngineEvent?>(null)
    private var nextRaw = 1L

    /** 诊断用（非 IMusicEngine 契约）：G01 抓 native 事件码校准状态机。 */
    fun nativeEvents(): Flow<NativeEngineEvent?> = _nativeEvent.asStateFlow()

    /** 只设置 Java 字段，不触发 native（PlaybackEngine.setListener 实证），故安全。 */
    private inner class StateListener : PlaybackEngineListener() {
        override fun onEngineEvent(msg: Int, songId: String, arg1: Int, arg2: Int) {
            _nativeEvent.value = NativeEngineEvent(msg, songId, arg1, arg2)
        }
    }

    @Volatile
    private var error: CloudMusicError? = null

    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        check(slots.isEmpty()) {
            "single handle invariant: release() before a new prepare() (03-06 §4 / §15 T06)"
        }
        error = null
        _state.value = EngineState.PREPARING
        return try {
            // 12 §34 D-104：按类实证顺序加载 6 个依赖库（去重、失败显式抛错）。
            EngineNativeLoader.loadChain(*EngineNativeLoader.PLAYBACK_ENGINE_CHAIN)
            val engine = PlaybackEngine(0L)
            engine.setListener(StateListener())   // 12 §37：接通回调链，不猜事件码
            val provider = UrlDataProvider(source)
            // 12 §50 真机 A3：URL / 回环代理源必须让 native 走 REMOTE 分支取流，
            // 否则 BufferSource 只查空的 localPath，Play 有焦点但没有音频数据。
            val pds = PlayerDataSource(provider, source.trackId, source.url != null)
            engine.setDataSource(pds)
            engine.prepareAsync()
            val raw = nextRaw++
            slots[raw] = Slot(engine, source, provider)
            _state.value = EngineState.PAUSED
            EngineHandle(raw)
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_PREPARE", t.message ?: "native 引擎准备失败"))
            throw CloudMusicError.DsUpstream("ENGINE_PREPARE", t.message ?: "native 引擎准备失败")
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

    override fun release(handle: EngineHandle) {
        val s = slots.remove(handle.raw) ?: return
        try {
            s.engine.release()
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_RELEASE", t.message ?: "释放失败"))
        } finally {
            s.provider.close()   // §15 T09：release 同时关闭 IDataSource
            error = null
            _state.value = EngineState.IDLE
        }
    }

    override fun lastError(): CloudMusicError? = error
}
