package com.example.cloudmusic.player

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.TrackId

/** docs/03-06 §8.2：业务层唯一播放模式（与 `08` 的 `playback.default_mode` 字符串取值一致）。 */
enum class PlayMode { SEQUENCE, SINGLE_LOOP, LIST_LOOP, SHUFFLE }

/** §8.1 `queue_items` 行。position 从 0 连续，无空洞（§15 T08）。 */
data class QueueItem(
    val position: Int,
    val trackId: TrackId,
    val sourceRef: SourceRef,
    val addedAt: Long,
    val queueId: String = "default",
)

/** §8.1 `playback_state` 行；冷启动恢复的唯一业务输入（§15 T11）。 */
data class PlaybackSnapshot(
    val currentTrackId: String?,
    val positionMs: Long,
    val playMode: PlayMode,
    val updatedAt: Long,
)

/** §9 状态机相位。UI 只观察 [PlayerState]，不得直接调 IMusicEngine。 */
enum class PlayerPhase { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val currentIndex: Int = -1,
    val currentTrackId: TrackId? = null,
    val positionMs: Long = 0L,
    val mode: PlayMode = PlayMode.SEQUENCE,
    val lastError: CloudMusicError? = null,
    /** 切 DLNA 时手机转遥控器：本机内核静音 + 暂停（§9）。 */
    val remoteOutput: Boolean = false,
)

/** `08` 的播放设置运行时投影；持久值由 `AppSettings.playback` 注入。 */
data class PlayerConfig(
    val autoSkipOnError: Boolean = true,
    val restoreLastQueue: Boolean = true,
    val shuffleSeed: Long = 0L,
)
