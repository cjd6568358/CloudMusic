package com.example.cloudmusic.engine.api

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.IDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

enum class EngineState { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

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
    val bufferMs: Int = 2000,
    /** PRD §6.1：解码失败自动跳下一首（可关）。 */
    val autoSkipOnError: Boolean = true,
)

interface IMusicEngine {
    fun prepare(source: EngineSource, config: EngineConfig): EngineHandle

    /** L2.5 注入统一字节流；自研 `IDataSource` 契约不变（03-01 §5）。 */
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
 * 纯 JVM 内存参考实现：供单测、UI 预览与组合层联调，**不替代舱内 EngineAdapter**。
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

    override fun release(handle: EngineHandle) {
        requireActive(handle)
        dataSource?.close()
        dataSource = null
        activeHandle = null
        source = null
        error = null
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
