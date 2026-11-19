package com.example.cloudmusic.ui.player

import com.example.cloudmusic.di.lyrics.OnlineLyricsFetcher
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.model.CueEnvelope
import com.example.cloudmusic.model.LrcParser
import com.example.cloudmusic.model.Lyrics
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.LyricFileStore
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.launch
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
 * §61.202 起 UI 进度改读 [positionMs]（运行时裁决：DLNA 用 DMR 真值、本机用引擎进度），
 * 不再直接拿 [PlayerState.positionMs]，投屏时才不会显示被静音的本机引擎冻结位置。
 *
 * 时长契约：契约 `03-10 §6` 的 `positionMs` 来自 [PlayerState.positionMs]；
 * `durationMs` 自 §61.181 起为「引擎真值优先、曲库元数据兜底」：
 * [PlayerState.durationMs] > 0（引擎已解出，含 STRM 直链在 READY 时解出的真值）
 * 即用引擎值，否则回退 [Track.durationMs]（本地有标签曲目在 PREPARING 期即可显示）。
 * 原口径「无引擎实时字段故不接」（`12 §61.180` 定性）正是 STRM 播放页恒 00:00 的
 * 直接成分——「不伪造」的正确解法是补真值通道，不是退回占位值。
 */
class PlayerStateBridge(
    private val runtime: PlaybackRuntime,
    tracks: TrackRepository,
    queue: QueueRepository,
    /** §61.198：歌词文件缓存（内嵌 USLT / 同目录 lrc 由扫描链落盘）。 */
    private val lyricStore: LyricFileStore? = null,
    /** §61.198：本地缺失时的在线 provider（docs/01 §0 用户拍板允许）。 */
    private val onlineLyrics: OnlineLyricsFetcher? = null,
) {
    /** [PlaybackRuntime.playerState] 本身即 `StateFlow`，直接透传，不复制状态。 */
    val state: StateFlow<PlayerState> = runtime.playerState()

    /** 当前曲目 → 标题 / 艺人 / 封面；经 `TrackRepository.observeAll()` 按 `currentTrackId` 查。 */
    val track: Flow<Track?> = combine(state, tracks.observeAll()) { s, all ->
        val id = s.currentTrackId?.value
        all.firstOrNull { it.id.value == id }
    }

    /** §61.226：[track] 最新值缓存（onSeek 非 suspend 的分轨换算输入）。 */
    @Volatile
    private var currentTrackCached: Track? = null

    /** §61.226：持有 track 的 collect 生命周期（bridge 初始化期挂 scope，见 init/构造区）。 */
    fun attachTrackCache(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch { track.collect { currentTrackCached = it } }
    }

    val queueCount: Flow<Int> = queue.observe().map { it.size }

    /**
     * §61.181（A 案）：引擎真值优先（[PlayerState.durationMs]，STRM 直链解出的
     * 真值含在内），曲库元数据兜底（本地有标签曲目 / 引擎未解出时）；无曲目为 0。
     * §61.226：CUE 分轨显示时长 = 包络长（end - start）——UI 进度条按分轨内相对时间，
     * 引擎 absolute 时长不再直接暴露（§61.224 的播放在包络内截断，超出无意义）。
     */
    val durationMs: Flow<Long> = combine(state, track) { s, t ->
        val base = if (s.durationMs > 0) s.durationMs else t?.durationMs ?: 0L
        displayDuration(base, t)
    }

    /**
     * §61.198 歌词取数四级：本地文件缓存（内嵌 / 同目录 lrc，扫描期已落盘）
     * → 在线 provider（标题+艺人一致才取，命中写回同一缓存）→ 空（不造数）。
     * 阻塞 IO 由调用方在 Dispatchers.IO 执行；两个可选依赖缺省时行为与 §61.186 一致。
     */
    suspend fun lyricsFor(track: Track?): Lyrics {
        val id = track?.id ?: return Lyrics.EMPTY
        lyricStore?.readRaw(id)?.takeIf { it.isNotBlank() }?.let {
            return LrcParser.parse(it, "local:file")
        }
        return onlineLyrics?.fetch(track) ?: Lyrics.EMPTY
    }

    /**
     * §61.202 播放页进度显示的单一入口：运行时在 DLNA 活跃时给 DMR 真值，
     * 否则给引擎实时进度（见 [PlaybackRuntime.displayPositionMs]）。
     * §61.226：CUE 分轨显示位置 = absolute - cueStart（分轨内相对时间，0 起点）；
     * 绝对时间只存在内部链（引擎 / 游标 / MediaSession），UI 一律相对。
     */
    val positionMs: Flow<Long> = combine(runtime.displayPositionMs(), track) { pos, t ->
        displayPosition(pos, t)
    }

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

    /**
     * `PlaybackRuntime.seekTo`（`12 §61.26` G03 验收同一裁决入口）。
     * §61.226：UI 传分轨内相对时间 → 换算 absolute（+cueStart）再下发；
     * 引擎侧 `PlayerController.seek` 另有 clampToCue 兜底（双向限界）。
     */
    fun onSeek(ms: Long) {
        runtime.seekTo(absoluteSeek(ms, currentTrackCached))
    }

    /**
     * §61.109（复核报告 P2 ③）：音效链路的**第一个生产 UI 入口**——此前只能
     * adb intent（`ACTION_EFFECT`，§61.93）触发。`level=0` 释放基础音效链，
     * `>0` 启用 audiofx 档位（语义同 `PlaybackRuntime.attachEffect`）；
     * 档位枚举 / 音效反馈 UI 仍属 capstone 专项，本入口只透传档位，不伪造反馈。
     */
    fun onEffect(level: Int) {
        runtime.attachEffect(level)
    }

    /** §61.118（T5-1）：均衡器弹窗入口——即时下发 + 持久化（AudiofxSettings）。 */
    fun onEqualizer(enabled: Boolean, effectLevel: Int, bands: List<Int>) {
        runtime.applyEqualizer(enabled, effectLevel, bands)
    }

    /** §61.119（M8）：队列点击跳播。 */
    fun onQueueJump(index: Int) {
        runtime.jumpTo(index)
    }

    /** §61.119（M3）：歌单创建（id 经回调交付，供「加入歌单」级联）。 */
    fun onCreatePlaylist(name: String, onCreated: (String) -> Unit = {}) {
        runtime.createPlaylist(name, onCreated)
    }

    /** §61.119（M3）：加入歌单。 */
    fun onAddToPlaylist(playlistId: String, trackId: com.example.cloudmusic.model.TrackId) {
        runtime.addToPlaylist(playlistId, trackId)
    }

    /** §61.119（M3）：删除歌单。 */
    fun onDeletePlaylist(id: String) {
        runtime.deletePlaylist(id)
    }

    /** §61.120（M3 小账）：重命名歌单。 */
    fun onRenamePlaylist(id: String, name: String) {
        runtime.renamePlaylist(id, name)
    }

    /** §61.121（M3 小账）：歌单内曲目移动。 */
    fun onMovePlaylistItem(id: String, from: Int, to: Int) {
        runtime.moveInPlaylist(id, from, to)
    }

    /** §61.121（M3 小账）：歌单内移除单曲。 */
    fun onRemovePlaylistItem(id: String, position: Int) {
        runtime.removeFromPlaylist(id, position)
    }

    /** §61.121：歌单曲目流（展开视图取数；runtime 透传 Repository）。 */
    fun observePlaylistTracks(playlistId: String): Flow<List<Track>> =
        runtime.observePlaylistTracks(playlistId)

    /** §61.119（M3）：播放歌单。 */
    fun onPlayPlaylist(id: String) {
        runtime.playPlaylist(id)
    }

    /** §61.130（M5）：收藏集合流。 */
    fun observeFavorites(): Flow<Set<com.example.cloudmusic.model.TrackId>> =
        runtime.observeFavorites()

    /** §61.130（M5）：最近播放流。 */
    fun observeHistory(): Flow<List<Track>> = runtime.observeHistory()

    /** §61.130（M5）：收藏切换（播放页 ♡ 唯一生产入口）。 */
    fun onToggleFavorite(trackId: com.example.cloudmusic.model.TrackId) {
        runtime.toggleFavorite(trackId)
    }

    companion object {
        /**
         * §61.226 三口 → §61.239 起委托 [CueEnvelope]（core-model 唯一真值口径，
         * 会话快照侧同源）；签名与判据零变化。
         */
        /** §61.226：分轨显示时长 = 包络长（end - start）；整轨 / 无界原样。 */
        internal fun displayDuration(base: Long, t: Track?): Long =
            CueEnvelope.displayDuration(base, t)

        /** §61.226：分轨显示位置 = absolute - cueStart（0 起点）；整轨原样。 */
        internal fun displayPosition(pos: Long, t: Track?): Long =
            CueEnvelope.displayPosition(pos, t)

        /** §61.226：显示相对时间 → absolute（+cueStart）；引擎侧 clampToCue 双向兜底。 */
        internal fun absoluteSeek(displayMs: Long, t: Track?): Long =
            CueEnvelope.absoluteSeek(displayMs, t)
    }
}