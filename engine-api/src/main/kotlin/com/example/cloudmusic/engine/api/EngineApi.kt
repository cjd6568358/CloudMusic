package com.example.cloudmusic.engine.api

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.IDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/03-06 §4「稳定门面」——**签名逐字对齐**，改动需先改契约文档。
 *
 * 这是自研侧与照搬舱之间唯一的缝：`:engine-legacy/EngineAdapter` 实现它，
 * `PlaybackService` 只依赖它。按 `13 §7` 实证，舱内对接的是
 * `PlaybackEngine.nativeEngine*`、`DataProvider`/`PlayerDataSource` 与三套回调，
 * 但这些细节**不得泄漏到本接口**。
 */
@JvmInline
value class EngineHandle(val raw: Long)

/**
 * §61.301（H0-1）：新增 BUFFERING——Media3 `STATE_BUFFERING` 此前被吞成日志，
 * 缓冲期业务层照报 PLAYING、系统会话按 speed=1f 墙钟外推（锁屏/通知/车机
 * 「缓冲中计时照走」的直接根因）。枚举加列不属门面方法签名改动（14+4 不变）；
 * 既有消费者全部为等值比对，无序数/序列化依赖（全仓扫描一手）。
 */
enum class EngineState { IDLE, PREPARING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

data class EngineSource(
    val trackId: String,
    val mimeHint: String,
    /** -1 = 未知（chunked / 直链无 Content-Length）。 */
    val expectedLength: Long = -1L,
    /**
     * C-05（`16 §4`）：`URL` 态直链，或 `PROXIED` 态由组合层 `proxy.publish` 拿到的**回环代理 URL**。
     * 引擎舱 `DataProvider.getUrl()` 从这里取值、native 自行拉流，**不消费 Java 字节流**
     * （`13 §3` 实证 / `16 §3.2` 权威决策表）。
     * 默认 null 向后兼容；`IMusicEngine` 的 14 方法签名不变。
     */
    val url: String? = null,
    /** §61.162：Link.headers 透传（WebDAV Authorization 等），供引擎 HTTP 栈附加。 */
    val headers: List<Pair<String, String>> = emptyList(),
    /** C-05：`LOCAL` 态的本机路径，供 `DataProvider.getLocalPath()` / `openLocalFd()` 直接读取。 */
    val localPath: String? = null,
) {
    init {
        require(!(url != null && localPath != null)) {
            "url 与 localPath 互斥（16 §3.2 本机取流决策表）"
        }
    }
}

data class EngineConfig(
    /** 0 = 由内核探测。 */
    val sampleRateHz: Int = 0,
    val preferHardwareDecoder: Boolean = true,
    /** §61.317：默认 15000（15s 起播缓冲）——网络 CUE/APE 大文件码率 ~1000kbps，
     *  2s 缓冲仅 ~250KB，网络抖动即断流。15s ≈ 1.875MB，抗短时网络波动。
     *  本地源不受影响（秒 READY）。 */
    val bufferMs: Int = 15000,
    /** PRD §6.1：解码失败自动跳下一首（可关）。 */
    val autoSkipOnError: Boolean = true,
    /**
     * §61.120（T5-2 前置）：曲库落库的曲目增益（dB）——容器标签缺失时的兜底
     * 增益源（分析管线后续批）。null = 无库值。
     */
    val trackGainDb: Float? = null,
)

interface IMusicEngine {
    fun prepare(source: EngineSource, config: EngineConfig): EngineHandle

    /**
     * L2.5 通用取流注入端口；自研 `IDataSource` 契约不变（03-01 §5）。
     * §61.108 语义定稿（03-06 §4）：这是**预留注入端口**——B2 主路径的字节流
     * 由 `EngineSource`（url/localPath，C-05）与回环代理解析供给，B2 适配器
     * 对本方法只保存引用并在 `release` 时关闭（不参与播放主路径）；
     * A 路线 `EngineAdapter` 语义为 proxy 态回源句柄替换。
     */
    fun attachDataSource(handle: EngineHandle, ds: IDataSource)

    fun play(handle: EngineHandle)
    fun pause(handle: EngineHandle)
    fun stop(handle: EngineHandle)
    fun seek(handle: EngineHandle, positionMs: Long)
    fun setVolume(handle: EngineHandle, volume: Float)
    fun positionMs(handle: EngineHandle): Long
    fun durationMs(handle: EngineHandle): Long
    fun state(): Flow<EngineState>
    fun setQuality(handle: EngineHandle, quality: Int)
    fun attachAudioEffect(handle: EngineHandle, effectId: Int)

    /**
     * §61.118（T5-1 capstone）：分频段均衡器电平（millibel，按 band 序）——
     * **增量方法**（14 方法门面不动，M1 守护加法兼容）。实现侧自行处理
     * band 数不匹配（取 min）；未挂载 audiofx 时静默忽略。
     */
    fun setEqualizerBands(handle: EngineHandle, levelsMillibel: List<Int>)

    /**
     * §61.131（gapless 队列化施工单）：多曲目 gapless 播放——B2 侧走 ExoPlayer
     * 原生 playlist（setMediaItems），同格式相邻曲目无缝衔接。
     * **增量方法**（门面 prepare 不动，单曲路径零回归）。
     */
    fun prepareQueue(sources: List<EngineSource>, startIndex: Int, config: EngineConfig): EngineHandle

    /**
     * §61.139（gapless 队列接线）：队列模式下当前 MediaItem 索引；非队列路径恒 -1。
     * B2 由 Player.Listener.onMediaItemTransition 驱动，业务层据此同步 UI 游标。
     * **增量方法**（14 方法门面不动，M1 守护加法兼容）。
     */
    fun queueIndex(): StateFlow<Int>

    /** §61.139：队列内跳播（不重建 player，保持 gapless）；非队列实现退化。 */
    fun seekToQueueIndex(handle: EngineHandle, index: Int)
    fun release(handle: EngineHandle)
    fun lastError(): CloudMusicError?
}

/** 内存参考实现的可观测快照（仅供测试 / 预览断言，不属契约）。 */
data class EngineSnapshot(
    val state: EngineState,
    val handleRaw: Long?,
    val source: EngineSource?,
    val config: EngineConfig,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val dataSourceAttached: Boolean,
    val lastError: CloudMusicError?,
)

/**
 * 纯 JVM 内存参考实现：供单测、UI 预览与组合层联调，**不替代引擎舱生产适配器**
 * （当前为 B2 `Media3EngineAdapter`，`12 §61.65~61.66`；A 路线 `EngineAdapter`
 * 保留为回退实现）。
 *
 * 落实 03-06 §4 的不变量：`EngineHandle` 同一时刻只允许存在一个有效值，
 * `prepare()` 新曲目必须先 `release()` 旧 handle（§15 T06）；
 * `seek` 钳制到 `[0, duration]`（§15 T09）；`release` 同时关闭 `IDataSource`。
 */
class InMemoryMusicEngine : IMusicEngine {

    private val _state = MutableStateFlow(EngineState.IDLE)
    private var activeHandle: EngineHandle? = null
    private var source: EngineSource? = null
    private var config = EngineConfig()
    private var dataSource: IDataSource? = null
    private var position = 0L
    private var duration = -1L
    private var volume = 1f
    private var error: CloudMusicError? = null
    private var nextRaw = 1L
    private val _queueIndex = MutableStateFlow(-1)

    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        check(activeHandle == null) {
            "single handle invariant: release() before a new prepare() (03-06 §4 / §15 T06)"
        }
        val handle = EngineHandle(nextRaw++)
        activeHandle = handle
        this.source = source
        this.config = config
        this.duration = source.expectedLength
        this.position = 0L
        this.volume = 1f
        this.error = null
        this.dataSource = null
        _queueIndex.value = -1
        _state.value = EngineState.PREPARING
        return handle
    }

    private fun requireActive(handle: EngineHandle) {
        check(activeHandle == handle) {
            "invalid or released EngineHandle(${handle.raw}); active=${activeHandle?.raw}"
        }
    }

    override fun attachDataSource(handle: EngineHandle, ds: IDataSource) {
        requireActive(handle)
        dataSource = ds
    }

    override fun play(handle: EngineHandle) {
        requireActive(handle)
        _state.value = EngineState.PLAYING
    }

    override fun pause(handle: EngineHandle) {
        requireActive(handle)
        _state.value = EngineState.PAUSED
    }

    override fun stop(handle: EngineHandle) {
        requireActive(handle)
        position = 0L
        _state.value = EngineState.IDLE
    }

    override fun seek(handle: EngineHandle, positionMs: Long) {
        requireActive(handle)
        val upper = if (duration >= 0) duration else Long.MAX_VALUE
        position = positionMs.coerceIn(0L, upper)
    }

    override fun setVolume(handle: EngineHandle, volume: Float) {
        requireActive(handle)
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        this.volume = volume
    }

    override fun positionMs(handle: EngineHandle): Long {
        requireActive(handle)
        return position
    }

    override fun durationMs(handle: EngineHandle): Long {
        requireActive(handle)
        return duration
    }

    override fun state(): Flow<EngineState> = _state.asStateFlow()

    override fun setQuality(handle: EngineHandle, quality: Int) {
        requireActive(handle)
    }

    override fun attachAudioEffect(handle: EngineHandle, effectId: Int) {
        requireActive(handle)
    }

    /** §61.118：内存参考实现——记录最近一次下发（单测断言用），无真实 DSP。 */
    override fun setEqualizerBands(handle: EngineHandle, levelsMillibel: List<Int>) {
        requireActive(handle)
    }

    /** §61.131 / §61.139：内存参考实现——记录队列快照并模拟 playlist 内跳播。 */
    override fun prepareQueue(sources: List<EngineSource>, startIndex: Int, config: EngineConfig): EngineHandle {
        val handle = prepare(sources.first(), config)
        queuedSources = sources.toList()
        queuedStartIndex = startIndex
        _queueIndex.value = startIndex.coerceIn(0, sources.lastIndex)
        return handle
    }

    override fun queueIndex(): StateFlow<Int> = _queueIndex.asStateFlow()

    /** 队列内跳播不重建 handle；同步 source/duration 让 snapshot 反映当前曲目。 */
    override fun seekToQueueIndex(handle: EngineHandle, index: Int) {
        requireActive(handle)
        val src = queuedSources?.getOrNull(index) ?: return
        source = src
        duration = src.expectedLength
        position = 0L
        _queueIndex.value = index
    }

    /** §61.131：最近一次 prepareQueue 的队列快照。 */
    var queuedSources: List<EngineSource>? = null
        private set
    var queuedStartIndex: Int = 0
        private set

    override fun release(handle: EngineHandle) {
        requireActive(handle)
        dataSource?.close()
        dataSource = null
        activeHandle = null
        source = null
        error = null
        _queueIndex.value = -1
        _state.value = EngineState.IDLE
    }

    override fun lastError(): CloudMusicError? = error

    /** 供上层注入引擎错误（非契约方法，仅参考实现）。 */
    fun reportError(e: CloudMusicError) {
        error = e
        _state.value = EngineState.ERROR
    }

    /** 供上层标记自然播放结束（非契约方法，仅参考实现）。 */
    fun reportEnded() {
        _state.value = EngineState.ENDED
    }

    /** §61.301：供上层注入缓冲事件（非契约方法，仅参考实现；BUFFERING 透传用例）。 */
    fun reportBuffering() {
        _state.value = EngineState.BUFFERING
    }

    fun snapshot(): EngineSnapshot = EngineSnapshot(
        state = _state.value,
        handleRaw = activeHandle?.raw,
        source = source,
        config = config,
        positionMs = position,
        durationMs = duration,
        volume = volume,
        dataSourceAttached = dataSource != null,
        lastError = error,
    )
}
