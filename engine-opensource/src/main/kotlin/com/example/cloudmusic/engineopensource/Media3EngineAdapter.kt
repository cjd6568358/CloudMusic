package com.example.cloudmusic.engineopensource

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.engine.api.EngineConfig
import com.example.cloudmusic.engine.api.EngineHandle
import com.example.cloudmusic.engine.api.EngineSource
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.model.IDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * docs/12 §61.81 / B2 M1 · `IMusicEngine` 的 Media3/ExoPlayer 实现骨架。
 *
 * 边界（**M1 骨架 ≠ B2 完成**）：
 * - 门面 [IMusicEngine] 14 方法签名逐字不变（03-06 §4），只新增实现；
 *   单点换绑在 `EngineModule.provideMusicEngine`，`:engine-legacy` /
 *   `EngineAdapter` 与 18 个私有 so 保留为回退（C5 本批不移除）。
 * - 本批只接 `url` / `localPath` 的官方 `MediaItem` + `ExoPlayer` 基础播放；
 *   `RangeCacheDataSource`（自研缓存 / Range / 代理回落）属 M3，此处只保存
 *   [IDataSource] 引用，**不伪造接线**。
 * - `setQuality` 与 `attachAudioEffect` 按 C7 默认假设留空实现并挂账
 *   （自研 DSP / 103 JNI 音效链不在 B2 复刻范围，基础 EQ 属 M4 / P4）。
 * - [EngineConfig] 的 `sampleRateHz` / `preferHardwareDecoder` / `bufferMs` /
 *   `autoSkipOnError` 本批不消费：平台 MediaCodec 硬解优先、缓冲由 ExoPlayer
 *   管理；精确语义属 M2 / M4，不写成「已支持」。
 * - 媒体会话 / 音频焦点 / 媒体按键**不**由 Media3 主导（C1），仍走
 *   `:core-media` + `:media-legacy` + 平台 API；本模块不引 `media3-session`。
 *
 * 状态机：PREPARING / PLAYING / PAUSED / IDLE 由门面调用即时驱动（与
 * `InMemoryMusicEngine` / `EngineAdapter` 一致），ENDED / ERROR 由
 * [Player.Listener] 驱动；M4 再按 §61.74 / §61.75 多信号口径收紧。
 */
class Media3EngineAdapter : IMusicEngine {

    /** 由 `EngineModule.provideMusicEngine` 用 `.also { it.appContext = context }` 注入。 */
    var appContext: Context? = null

    private val _state = MutableStateFlow(EngineState.IDLE)
    private var player: ExoPlayer? = null
    private var activeHandle: EngineHandle? = null
    private var dataSource: IDataSource? = null

    @Volatile
    private var error: CloudMusicError? = null
    private var nextRaw = 1L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.value = EngineState.ENDED
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            fail(CloudMusicError.DsUpstream(
                "MEDIA3_PLAYBACK", e.message ?: "Media3 播放错误"))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        check(activeHandle == null) {
            "single handle invariant: release() before a new prepare() (03-06 §4 / §15 T06)"
        }
        val ctx = appContext
            ?: throw CloudMusicError.DsUpstream(
                "MEDIA3_CONTEXT", "appContext 未注入（EngineModule 单点换绑需 .also）")
        // 局部副本：跨模块 val 无法 smart-cast（M1 编译 warning 的根因）。
        val url = source.url
        val localPath = source.localPath
        val uri: Uri = when {
            url != null -> Uri.parse(url)
            localPath != null -> Uri.fromFile(File(localPath))
            else -> throw CloudMusicError.DsUpstream(
                "MEDIA3_SOURCE", "EngineSource 既无 url 也无 localPath，无法构造 MediaItem")
        }
        error = null
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(source.trackId)
            .apply {
                // mimeHint 可能是 MIME（audio/mpeg）或真实扩展名（mp3，F2-b）。
                // 只在确认为 MIME 时透传，其余交给 Media3 内容探测，不编造。
                if (source.mimeHint.contains('/')) setMimeType(source.mimeHint)
            }
            .build()
        // §61.85（B2 M2）：EXTENSION_RENDERER_MODE_ON = 平台 MediaCodec 硬解优先，
        // FFmpeg 软解（FfmpegAudioRenderer，libffmpegJNI）仅补位平台不支持的
        // 格式——对齐施工单 §4「硬解优先」与 B2 的「删掉缺陷层、不换解码器」。
        val renderersFactory = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val p = ExoPlayer.Builder(ctx, renderersFactory).build()
        p.addListener(listener)
        p.setMediaItem(item)
        p.prepare()
        player = p
        val handle = EngineHandle(nextRaw++)
        activeHandle = handle
        // 与 EngineAdapter / InMemoryMusicEngine 一致：prepare 后等 play() 才进入 PLAYING。
        _state.value = EngineState.PAUSED
        return handle
    }

    private fun requireActive(handle: EngineHandle) {
        check(activeHandle == handle) {
            "invalid or released EngineHandle(${handle.raw}); active=${activeHandle?.raw}"
        }
    }

    private fun fail(e: CloudMusicError) {
        error = e
        _state.value = EngineState.ERROR
    }

    private inline fun guard(handle: EngineHandle, block: (ExoPlayer) -> Unit) {
        requireActive(handle)
        val p = player ?: return
        try {
            block(p)
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_CALL", t.message ?: "Media3 调用失败"))
        }
    }

    override fun attachDataSource(handle: EngineHandle, ds: IDataSource) {
        requireActive(handle)
        // M3 才把 IDataSource 桥接成 RangeCacheDataSource（官方 DataSource 语义）。
        // 本批只保存引用并在 release 时关闭，不伪造缓存 / Range 接线。
        dataSource = ds
    }

    override fun play(handle: EngineHandle) = guard(handle) {
        it.play()
        _state.value = EngineState.PLAYING
    }

    override fun pause(handle: EngineHandle) = guard(handle) {
        it.pause()
        _state.value = EngineState.PAUSED
    }

    override fun stop(handle: EngineHandle) = guard(handle) {
        it.seekTo(0L)
        it.stop()
        it.clearMediaItems()
        _state.value = EngineState.IDLE
    }

    override fun seek(handle: EngineHandle, positionMs: Long) = guard(handle) {
        val d = it.duration
        val target = if (d != C.TIME_UNSET && d >= 0) {
            positionMs.coerceIn(0L, d)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        it.seekTo(target)
    }

    override fun setVolume(handle: EngineHandle, volume: Float) {
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        guard(handle) { it.volume = volume }
    }

    override fun positionMs(handle: EngineHandle): Long {
        requireActive(handle)
        val pos = player?.currentPosition ?: return 0L
        return if (pos == C.TIME_UNSET) 0L else pos
    }

    override fun durationMs(handle: EngineHandle): Long {
        requireActive(handle)
        val d = player?.duration ?: return -1L
        return if (d == C.TIME_UNSET || d < 0) -1L else d
    }

    override fun state(): Flow<EngineState> = _state.asStateFlow()

    /** 03-06 §5：切质量 V0.1 不启用；A 路线 `EngineAdapter` 亦为空实现。 */
    override fun setQuality(handle: EngineHandle, quality: Int) {
        requireActive(handle)
    }

    /**
     * C7 音效降级：自研 PCM DSP 链（103 JNI / EQ / NCAE / MNN）不在 B2 复刻范围，
     * 本批留空实现并挂账 P4；基础 EQ / `android.media.audiofx.Equalizer` 属 M4。
     */
    override fun attachAudioEffect(handle: EngineHandle, effectId: Int) {
        requireActive(handle)
    }

    override fun release(handle: EngineHandle) {
        requireActive(handle)
        try {
            player?.removeListener(listener)
            player?.release()
        } catch (t: Throwable) {
            fail(CloudMusicError.DsUpstream("ENGINE_RELEASE", t.message ?: "释放失败"))
        } finally {
            player = null
            // §15 T09：release 同时关闭 IDataSource。
            dataSource?.close()
            dataSource = null
            activeHandle = null
            error = null
            _state.value = EngineState.IDLE
        }
    }

    override fun lastError(): CloudMusicError? = error
}
