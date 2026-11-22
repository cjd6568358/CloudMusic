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
    /**
     * §61.181（A 案）：引擎解出的**当前曲真实时长**（>0 有效）。0 = 引擎尚未解出
     * （B2 在 READY 时才解出，prepare 返回时可能是未知值），UI 按曲库元数据兜底。
     * 切曲即清零（[PlayerController] PREPARING 置位），不残留上一曲真值。
     */
    val durationMs: Long = 0L,
    val mode: PlayMode = PlayMode.SEQUENCE,
    val lastError: CloudMusicError? = null,
    /**
     * §61.301（H0-1）：引擎 BUFFERING 透传标志。PlayerPhase 不加列（UI 的 exhaustive
     * when 零强制改动）：引擎进缓冲时 phase 保持 PLAYING 而本标志置位——节拍器
     * 缓冲期停发 PLAYING 会话快照（防系统会话 speed=1f 外推重锚），UI 可选渲染
     * 缓冲态。PAUSED/ENDED 等非播放相位恒 false。
     */
    val isBuffering: Boolean = false,
    /** 切 DLNA 时手机转遥控器：本机内核静音 + 暂停（§9）。 */
    val remoteOutput: Boolean = false,
)

/** `08` 的播放设置运行时投影；持久值由 `AppSettings.playback` 注入。 */
data class PlayerConfig(
    val autoSkipOnError: Boolean = true,
    val restoreLastQueue: Boolean = true,
    val shuffleSeed: Long = 0L,
)
