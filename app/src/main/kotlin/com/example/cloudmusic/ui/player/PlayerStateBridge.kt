package com.example.cloudmusic.ui.player

import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * `03-10 §6`：播放页（照搬 / 自研 View）与业务 `StateFlow` 之间的**唯一通道**。
 *
 * 边界（`03-10 §11 #4` / `03-06 §9`）：只经 [PlaybackRuntime] 与 Repository 的 Flow 取数，
 * 播放页**不持有** `PlayerController` / `IMusicEngine` / `Driver` / Room / OkHttp。
 * 本类落在 `:app`，不新建 `:ui-legacy` 模块、不改 Hilt 注册图（零 DI 变更）。
 *
 * 与契约 `03-10 §6` 的差异（**诚实挂账，见 `12 §61.40`**）：契约列出的
 * `onModeToggle()` / `onFavorite()` / `onOpenQueue()` / `onOpenOutput()` 四项，当前
 * 生产侧没有真实后端：
 * - 模式：`PlayerController` 只有 `setQueue(items, mode, startAt)`，切换会 `sync.push()`
 *   全量下发 native 队列，不擅自改变真机运行时行为；模式图标改为只读展示；
 * - 收藏：`Track` 无收藏字段、无收藏 Repository；
 * - 输出切换：`OutputRouter.setActive` 生产调用方为 0（`12 §48`），不伪造投屏状态；
 * - 队列弹窗：属 `03-10 §5` 的 V0.2 完善项。
 * 这四项**不实现、不伪造完成**，仅在 UI 上以只读 / 占位形态呈现。
 *
 * 时长契约：契约 `03-10 §6` 的 `positionMs` / `durationMs` 在这里分别来自
 * [PlayerState.positionMs] 与 [Track.durationMs]（曲库元数据）；
 * `PlayerState` 中并无引擎实时 `durationMs` 字段，故不伪造一个引擎时长流。
 */
class PlayerStateBridge(
    private val runtime: PlaybackRuntime,
    tracks: TrackRepository,
    queue: QueueRepository,
) {
    /** [PlaybackRuntime.playerState] 本身即 `StateFlow`，直接透传，不复制状态。 */
    val state: StateFlow<PlayerState> = runtime.playerState()

    /** 当前曲目 → 标题 / 艺人 / 封面；经 `TrackRepository.observeAll()` 按 `currentTrackId` 查。 */
    val track: Flow<Track?> = combine(state, tracks.observeAll()) { s, all ->
        val id = s.currentTrackId?.value
        all.firstOrNull { it.id.value == id }
    }

    val queueCount: Flow<Int> = queue.observe().map { it.size }

    /** `Track.durationMs`；无曲目时为 0。 */
    val durationMs: Flow<Long> = track.map { it?.durationMs ?: 0L }

    /** `03-10 §6` 的 `playMode`；当前只读（见类注释）。 */
    val playMode: Flow<PlayMode> = state.map { it.mode }

    /** 读 `state` 写播放 / 暂停，与 `PlaybackService.performPlay/performPause` 同源。 */
    fun onPlayPause() {
        if (state.value.phase == PlayerPhase.PLAYING) runtime.pause() else runtime.play()
    }

    fun onNext() {
        runtime.next()
    }

    fun onPrev() {
        runtime.prev()
    }

    /** `PlaybackRuntime.seekTo`（`12 §61.26` G03 验收同一裁决入口）。 */
    fun onSeek(ms: Long) {
        runtime.seekTo(ms)
    }
}
