package com.example.cloudmusic.media

import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * docs/03-07 §3「稳定门面」——**签名逐字对齐**，包名 `com.example.cloudmusic.media` 永不变。
 *
 * 自研侧只依赖这个接口；`:media-legacy/MediaLegacyAdapter` 实现它，
 * `PlaybackService` 以 `@Singleton` 持有。按 `13 §8` 实证，照搬层的
 * `MediaButtonEventReceiver` / `AudioFocusInfo` 等精确类名属于舱内细节，
 * **不得泄漏到本门面**。
 */
interface IMediaControl {
    /** 外部事件入口：由照搬层裁决后回调 PlayerController。 */
    fun onFocusChange(change: FocusChange)
    fun onMediaButton(key: MediaKey, action: MediaAction)
    fun bind(controller: PlayerControllerHandle)

    /** PlayerController 反向推送状态。 */
    fun publishMetadata(track: Track, coverPath: String?)
    fun setPlaybackState(state: EngineState, positionMs: Long, durationMs: Long)

    /** 切 DLNA 输出时禁用本机媒体会话（03-07 §12 T09）。 */
    fun setEnabled(enabled: Boolean)

    /**
     * C-15（`12 §44`）：上层把「用户主动操作」回传给焦点状态机。
     * **带默认空实现**，照搬层实现与既有测试替身无需改动即可编译（零回归）；
     * 参考实现覆写后驱动 `FocusPolicy` 的「是否因焦点而暂停」标记（§12 T01~T03）。
     * `PlaybackService` 的 `MediaSession.Callback` 在 `onPlay` / `onPause` 调用，
     * 否则手动暂停后收到 `GAIN` 会被误判为「来电恢复」而自动续播。
     */
    fun onPlaying() {}
    fun onManualPause() {}

    fun release()
}

enum class FocusChange {
    GAIN, GAIN_TRANSIENT, GAIN_TRANSIENT_MAY_DUCK,
    LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, OFF,
}

enum class MediaKey { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREV, STOP, FAST_FORWARD, REWIND }

enum class MediaAction { DOWN, UP, LONG_PRESS, DOUBLE_CLICK }

/**
 * PlayerController 暴露给照搬层的最小句柄；与 `IMediaControl` 同域，
 * 避免舱依赖 `:feature-player`（`10 §2 R18`）。
 */
interface PlayerControllerHandle {
    fun pauseFromFocus()
    fun resumeFromFocus()
    fun duck(volume: Float)
    fun unduck()
    fun play()
    fun pause()
    fun next()
    fun prev()
    fun seekRelative(deltaMs: Long)
}

/** 通知 / 锁屏 / AVRCP 元数据（缺标题时走兜底，03-07 §12 T07）。 */
data class MediaMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val coverPath: String?,
    val durationMs: Long,
) {
    companion object {
        const val FALLBACK_TITLE = "未知曲目"
    }
}

data class MediaPlaybackSnapshot(
    val state: EngineState,
    val positionMs: Long,
    val durationMs: Long,
)

/** 内存参考实现的可观测状态（仅供测试 / 预览，不属契约）。 */
data class MediaControlSnapshot(
    val enabled: Boolean,
    val bound: Boolean,
    val focus: FocusState,
    val volume: Float,
    val metadata: MediaMetadata?,
    val playback: MediaPlaybackSnapshot?,
)

/** 便于上层订阅的只读观察口（非契约方法，仅内存实现）。 */
interface ObservableMediaControl {
    fun metadata(): Flow<MediaMetadata?>
    fun playback(): Flow<MediaPlaybackSnapshot?>
    fun snapshot(): MediaControlSnapshot
}
