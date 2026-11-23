package com.example.cloudmusic.di.playback

import android.util.Log
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerController
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.FavoriteRepository
import com.example.cloudmusic.storage.HistoryRepository
import com.example.cloudmusic.storage.LibraryQuery
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * §61.324（B-4 拆域）：曲库域（歌单 / 收藏 / 历史 / 批处理）。
 *
 * 从 `PlaybackRuntime`（原 1301 行、12 类职责）切出的三域之一，边界是
 * **「曲库数据的读写与批处理，不含播放控制」**：
 * - 歌单 CRUD 与曲目流（§61.119 / §61.121 / §61.120）
 * - 收藏与播放历史（§61.130）
 * - ReplayGain 批分析（§61.122）、封面刮削（§61.233）、重复检测（§61.247~§61.256）
 *
 * 唯一的播放类调用是 [playPlaylist]（歌单替换业务队列并起播）——它属于
 * **「曲库数据 → 播放队列」的转换口**，不是播放控制策略，留在本域可让
 * 「歌单」与「队列」的耦合只出现在这一处。无 `RuntimeState` 依赖，可独立测试。
 */
class LibraryFacade(
    private val controller: PlayerController,
    private val queueRepository: QueueRepository,
    private val tracks: TrackRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoriteRepository,
    private val history: HistoryRepository,
    private val coverWriter: com.example.cloudmusic.di.scan.CoverWriterPort,
    private val scrapeHttp: com.example.cloudmusic.di.scan.CoverHttp,
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
) {

    /** §61.130（M5）：收藏集合流（播放页 ♡ 状态 + 我的音乐页取数）。 */
    fun observeFavorites(): Flow<Set<TrackId>> = favorites.observe()

    /** §61.130（M5）：最近播放流（HistoryDao JOIN tracks，按 last_played DESC）。
     *  §61.328（B-1）：只推最近 [limit] 条；总数另走 [observeHistoryCount]。 */
    fun observeHistory(limit: Int = LibraryQuery.HISTORY_WINDOW): Flow<List<Track>> =
        history.observe(limit)

    /** §61.328（B-1）：历史总数（不受列表窗口截断影响）。 */
    fun observeHistoryCount(): Flow<Int> = history.count()

    /** §61.130（M5）：收藏切换（播放页 ♡ 按钮的唯一生产入口）。 */
    fun toggleFavorite(trackId: TrackId) {
        scope.launch {
            runCatching { favorites.toggle(trackId) }
                .onFailure { Log.w("CloudMusicRuntime", "toggleFavorite 失败", it) }
        }
    }

    /** §61.130（M5）：播放历史记录（PLAYING 时每曲一次，HistoryDao playCount 递增）。 */
    fun recordHistory(trackId: TrackId) {
        scope.launch {
            runCatching { history.record(trackId) }
                .onFailure { Log.w("CloudMusicRuntime", "recordHistory 失败", it) }
        }
    }

    /** §61.119（M3）：创建歌单，返回 id（经回调交付 UI）。 */
    fun createPlaylist(name: String, onCreated: (String) -> Unit = {}) {
        scope.launch {
            runCatching { playlists.create(name) }
                .onSuccess { id -> onCreated(id) }
                .onFailure { Log.w("CloudMusicRuntime", "createPlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：加入歌单（跨来源混合，契约不变）。 */
    fun addToPlaylist(playlistId: String, trackId: TrackId) {
        scope.launch {
            runCatching { playlists.addTracks(playlistId, listOf(trackId)) }
                .onFailure { Log.w("CloudMusicRuntime", "addToPlaylist 失败", it) }
        }
    }

    /** §61.121（M3 小账）：歌单内曲目移动（契约 move 收口，removeAt/add 语义）。 */
    fun moveInPlaylist(id: String, from: Int, to: Int) {
        scope.launch {
            runCatching { playlists.move(id, from, to) }
                .onFailure { Log.w("CloudMusicRuntime", "moveInPlaylist 失败", it) }
        }
    }

    /** §61.121（M3 小账）：歌单内移除单曲（契约 remove 收口）。 */
    fun removeFromPlaylist(id: String, position: Int) {
        scope.launch {
            runCatching { playlists.remove(id, position) }
                .onFailure { Log.w("CloudMusicRuntime", "removeFromPlaylist 失败", it) }
        }
    }

    /** §61.121：歌单曲目流透传（UI 展开视图取数；03-10 §11 #4 经 runtime 通道）。 */
    fun observePlaylistTracks(id: String): Flow<List<Track>> =
        playlists.observeTracks(id)

    /**
     * §61.122（T5-2 分析管线第一版）：曲库 ReplayGain 近似分析——仅 LOCAL、
     * 未分析（replayGainDb==null）、上限 [maxCount] 首；逐曲解码（IO 线程）、
     * 落库 + logcat 进度（CM-RG tag，真机可观测）。耗时随曲库线性增长，
     * 由设置页显式触发（opt-in）。
     */
    fun analyzeLibraryReplayGain(maxCount: Int = 50, force: Boolean = false) {
        scope.launch {
            runCatching {
                val all = tracks.observeAll().first()
                    .filter {
                        it.sourceType == com.example.cloudmusic.model.SourceType.LOCAL &&
                            (force || it.replayGainDb == null)
                    }
                    .take(maxCount)
                if (all.isEmpty()) {
                    Log.i("CM-RG", "analyze: nothing to analyze")
                    return@runCatching
                }
                Log.i("CM-RG", "analyze start: %d tracks".format(all.size))
                var done = 0
                var gained = 0
                for (track in all) {
                    ensureActive()
                    // §61.122 真机一手：playLocal 注入曲目的 sourceRef.path 是文件名
                    // （非绝对路径）→ 候选 = 原值 + filesDir/music/<name>，取存在的。
                    val candidates = listOfNotNull(
                        track.sourceRef.path,
                        appContext.filesDir.resolve("music/${track.sourceRef.path}")
                            .absolutePath,
                    )
                    val path = candidates.firstOrNull {
                        java.io.File(it).isFile
                    }
                    if (path == null) {
                        Log.i("CM-RG", "analyze %s → 文件不存在（%s）".format(
                            track.title.take(20), track.sourceRef.path))
                        done++
                        continue
                    }
                    val result = com.example.cloudmusic.di.scan.ReplayGainAnalyzer
                        .analyze(path)
                    if (result != null) {
                        tracks.markReplayGain(track.id, result.gainDb)
                        gained++
                        Log.i("CM-RG", "analyze %s → %.2f dB (%s)".format(
                            track.title.take(20), result.gainDb, result.path))
                    } else {
                        Log.i("CM-RG", "analyze %s → NO_SAMPLE（平台不可解或过短）".format(
                            track.title.take(20)))
                    }
                    done++
                }
                Log.i("CM-RG", "analyze done: %d/%d gained".format(gained, done))
            }.onFailure { Log.w("CM-RG", "analyze 失败", it) }
        }
    }

    /** §61.120（M3 小账）：重命名歌单（契约 rename 收口）。 */
    fun renamePlaylist(id: String, name: String) {
        scope.launch {
            runCatching { playlists.rename(id, name) }
                .onFailure { Log.w("CloudMusicRuntime", "renamePlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：删除歌单。 */
    fun deletePlaylist(id: String) {
        scope.launch {
            runCatching { playlists.delete(id) }
                .onFailure { Log.w("CloudMusicRuntime", "deletePlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：播放歌单——歌单曲目替换业务队列并从头起播。 */
    fun playPlaylist(id: String) {
        scope.launch {
            runCatching {
                val list = playlists.observeTracks(id).first()
                if (list.isEmpty()) return@runCatching
                queueRepository.setAll(list)
                val queueItems = queueRepository.observe().first()
                controller.setQueue(queueItems, PlayMode.SEQUENCE, 0)
                controller.playAtDebounced(0)
            }.onFailure { Log.w("CloudMusicRuntime", "playPlaylist 失败", it) }
        }
    }

    /**
     * §61.233（V0.2 刮削）：封面在线刮削批处理（设置页触发，同 §61.130 RG 分析模式；
     * 手动 + 限数 50，不挂扫描期）。结果 toast 汇报尝试/命中数。
     */
    fun scrapeMissingCovers(maxCount: Int = 50) = scope.launch {
        runCatching {
            val session = com.example.cloudmusic.di.scan.CoverScrapeSession(
                tracks = tracks,
                writer = coverWriter,
                http = scrapeHttp,
            )
            val report = session.scrapeMissing(maxCount)
            Log.i("CM-SCRAPER", "scrape done: attempted=%d hit=%d".format(report.attempted, report.hit))
        }.onFailure { Log.w("CM-SCRAPER", "scrape 失败", it) }
    }

    /** §61.250：跨次指纹记忆（key="storageId|path"）——限数逐次推进 + 命中零 IO 复用。 */
    private val duplicateHashMemo =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    /** §61.247：重复曲目检测报告（null = 未检测；设置页触发后由 UI 消费展示）。 */
    private val _duplicateReport = MutableStateFlow<
        com.example.cloudmusic.model.DuplicateReport?>(null)
    fun duplicateReport(): StateFlow<
        com.example.cloudmusic.model.DuplicateReport?> = _duplicateReport.asStateFlow()

    /** §61.247：关闭报告面板时清空（下次触发重算）。 */
    fun clearDuplicateReport() {
        _duplicateReport.value = null
        duplicateHashMemo.clear()   // §61.250：面板关闭清记忆（下次触发从头推进）
    }

    /**
     * §61.247（V0.3 M5 / A1 Tier2）：重复曲目检测（设置页触发；零 IO 读曲库内存分组，
     * 不挂扫描期）。结果进 [duplicateReport] 由设置页结果面板展示；处置用户手动。
     */
    fun findDuplicates() = scope.launch {
        runCatching {
            // §61.248：Tier2 分组 + Tier1 哈希确认趟（候选限数 50 文件/次）。
            val session = com.example.cloudmusic.di.scan.DuplicateHashSession(
                tracks = tracks,
                hasher = com.example.cloudmusic.di.scan.DriverContentHasher(),
            )
            // §61.250：跨次指纹记忆（域内持有，重跑推进剩余候选；换库全量重扫时
            // 由调用方 clearDuplicateReport 清——记忆按 storageId|path 键，路径变化自然失效）。
            var report = session.detect(maxHashCount = 50, memo = duplicateHashMemo)
            // §61.256：Tier3 声纹趟——对哈希未确认的疑似组解码匹配（转码/异格式拷贝升级）。
            if (report.groups.isNotEmpty()) {
                val fpSession = com.example.cloudmusic.di.scan.FingerprintSession(
                    tracks = tracks,
                    decoder = com.example.cloudmusic.di.adapters.MediaPcmDecoder(),
                )
                report = fpSession.detect(report, maxCount = 20)
            }
            _duplicateReport.value = report
            Log.i("CM-DUP", "duplicate scan done: scanned=%d suspected=%d confirmed=%d fpConfirmed=%d hashed=%d failed=%d"
                .format(report.scanned, report.groups.size, report.confirmed.size,
                    report.fingerprintConfirmed.size, report.hashedCount, report.hashFailed))
        }.onFailure { Log.w("CM-DUP", "duplicate scan 失败", it) }
    }
}
