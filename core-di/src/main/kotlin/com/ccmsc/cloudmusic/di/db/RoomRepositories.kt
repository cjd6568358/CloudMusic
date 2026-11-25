package com.ccmsc.cloudmusic.di.db

import androidx.room.withTransaction
import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.SearchText
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.player.PlayMode
import com.ccmsc.cloudmusic.player.PlaybackSnapshot
import com.ccmsc.cloudmusic.player.QueueItem
import com.ccmsc.cloudmusic.player.QueueRepository
import com.ccmsc.cloudmusic.scanner.ScanSnapshot
import com.ccmsc.cloudmusic.scanner.ScanSnapshotStore
import com.ccmsc.cloudmusic.storage.CoverInheritance
import com.ccmsc.cloudmusic.storage.FailureRecord
import com.ccmsc.cloudmusic.storage.FavoriteRepository
import com.ccmsc.cloudmusic.storage.HistoryRepository
import com.ccmsc.cloudmusic.storage.LibraryQuery
import com.ccmsc.cloudmusic.storage.LibraryQuery.FAILURE_LOG_MAX
import com.ccmsc.cloudmusic.storage.LibraryQuery.FAILURE_LOG_MAX_AGE_MS
import com.ccmsc.cloudmusic.storage.Playlist
import com.ccmsc.cloudmusic.storage.PlaylistRepository
import com.ccmsc.cloudmusic.storage.TrackRepository
import com.ccmsc.cloudmusic.storage.UPSERT_BATCH_SIZE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/** §6 / B02 / T06：upsert 固定按 500 条切事务，纯函数便于 JVM 单测断言批数。 */
object UpsertPlanner {
    fun chunks(tracks: List<Track>, size: Int = UPSERT_BATCH_SIZE): List<List<Track>> {
        require(size > 0) { "batch size must be positive" }
        return tracks.chunked(size)
    }
}

private fun List<TrackRow>.toSortedTracks(): List<Track> =
    LibraryQuery.sort(map { it.toTrack() })

/** docs/03-05 §5 的 TrackRepository Room 实现（替换 `InMemoryTrackRepository`）。 */
class RoomTrackRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TrackRepository {
    private val dao = db.trackDao()

    override fun observeAll(): Flow<List<Track>> = dao.observeAll().map { it.toSortedTracks() }

    /** §8：FTS MATCH 优先；短查询 / FTS 无结果回落 LIKE；B07 不按 availability 过滤。
     *  `§61.296`：查询先经 `SearchText` 归一（全角空格折叠为半角），否则 LIKE 与 FTS
     *  都会拿到「一个带 U+3000 的整串」，两个词都命中却零结果。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun search(query: String): Flow<List<Track>> {
        val q = SearchText.normalize(query)
        if (q.isEmpty()) return observeAll()
        val likeFlow = dao.searchLike("%${LikePattern.escape(q)}%").map { it.toSortedTracks() }
        if (FtsQuery.isShortQuery(q)) return likeFlow
        // §61.328（B-1）LIKE 收敛：只有 FTS 打空时才订阅 LIKE。
        // 旧写法用 `combine` —— 两条查询**每轮都跑**，FTS 已命中时那次 LIKE 是纯浪费
        // （`%q%` 走不了索引 = 全表扫 7,500 行），且任一曲库变更都会把两条一起重跑。
        // 语义仍是「FTS 优先、LIKE 兜底」，只是把兜底从「并行必跑」改成「按需二次查询」。
        return dao.searchFts(FtsQuery.match(q))
            .map { it.toSortedTracks() }
            .flatMapLatest { hits -> if (hits.isEmpty()) likeFlow else flowOf(hits) }
    }

    override fun byAlbum(album: String): Flow<List<Track>> =
        dao.byAlbum(album).map { it.toSortedTracks() }

    override fun byArtist(artist: String): Flow<List<Track>> =
        dao.byArtist(artist).map { it.toSortedTracks() }

    override fun byFolder(prefix: String): Flow<List<Track>> {
        val normalized = prefix.trimEnd('/')
        if (normalized.isEmpty()) return observeAll()
        return dao.byFolder(normalized, LikePattern.escape(normalized) + "/%")
            .map { it.toSortedTracks() }
    }

    override suspend fun get(id: TrackId): Track? = dao.get(id.value)?.toTrack()

    /**
     * §61.319（R2）：Room 批量点查——`IN (:ids)` 每条 SQL 最多 500 个变量
     * （[UPSERT_BATCH_SIZE] 同口径），按输入顺序返回、缺项 null。
     */
    override suspend fun getMany(ids: List<TrackId>): List<Track?> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<String, Track>()
        for (chunk in ids.distinct().chunked(UPSERT_BATCH_SIZE)) {
            dao.findByIds(chunk.map { it.value }).forEach { row ->
                found[row.id] = row.toTrack()
            }
        }
        return ids.map { found[it.value] }
    }

    /** §6 / §14 #3 / C-04：单事务合并旧状态，严格保留 availability 与 addedAt。 */
    override suspend fun upsertBatch(tracks: List<Track>) {
        UpsertPlanner.chunks(tracks).forEach { batch ->
            db.withTransaction {
                val existing = dao.findByIds(batch.map { it.id.value }).associateBy { it.id }
                val now = clock()
                dao.upsertAll(batch.map { TrackRow.from(it, existing[it.id.value], now) })
            }
        }
    }

    override suspend fun markAvailability(id: TrackId, a: Availability) =
        dao.markAvailability(id.value, a.name, clock())

    /** §61.145：批量置位（500 一批防 SQLite 变量上限，单事务多行）。 */
    override suspend fun markAvailabilityBatch(ids: List<TrackId>, a: Availability) {
        ids.map { it.value }.chunked(500).forEach { chunk ->
            dao.markAvailabilityBatch(chunk, a.name, clock())
        }
    }

    override suspend fun markOrphan(storageId: String) = dao.markOrphan(storageId, clock())

    /** §61.122（T5-2）：分析管线落库增益。 */
    override suspend fun markReplayGain(id: TrackId, gainDb: Float) =
        dao.markReplayGain(id.value, gainDb)

    /** §61.181（B 案）：引擎真值时长回填（DAO 侧只写缺值行，已有元数据不覆盖）。 */
    override suspend fun updateDuration(id: TrackId, durationMs: Long) =
        dao.updateDuration(id.value, durationMs)

    /** §61.233：刮削封面落库（DAO 侧只写缺封面行，不覆盖已有封面）。 */
    override suspend fun updateCover(id: TrackId, coverRef: String) =
        dao.updateCover(id.value, coverRef, System.currentTimeMillis())

    /**
     * §61.329（A-6）：CUE 分轨封面继承。
     *
     * 三步、全部按亲缘键 `(storage_id, source_path)` 走，**不碰哈希**：
     * ① 取全部缺封面行（空串也算缺）；
     * ② 只为这些行出现过的 storage / path 查 donor（同 storage 同路径且有封面的行）；
     * ③ [CoverInheritance] 出计划 → 按 coverRef 分组批量回填（`IN` 500 分块）。
     *
     * 幂等：写语句自带 `cover_ref IS NULL OR = ''` 前置；扫描后跑一次，旧库就地修好。
     * 返回实际受影响行数（不是计划条数——并发下可能被别人先填）。
     */
    override suspend fun inheritCueSplitCovers(): Int {
        val gaps = dao.coverGapRows()
        if (gaps.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var filled = 0
        gaps.groupBy { it.storageId }.forEach { (storageId, rows) ->
            val donors = rows.map { it.sourcePath }.distinct()
                .chunked(500)
                .flatMap { dao.coverDonorRows(storageId, it) }
            val plan = CoverInheritance.plan(
                (rows + donors).map {
                    CoverInheritance.Row(it.id, it.storageId, it.sourcePath, it.coverRef)
                },
            )
            plan.entries.groupBy({ it.value }, { it.key }).forEach { (cover, ids) ->
                ids.chunked(500).forEach { chunk ->
                    filled += dao.fillCoverIfEmpty(chunk, cover, now)
                }
            }
        }
        return filled
    }

    override suspend fun count(): Int = dao.count()

    /** B06：除启动回调外，也可由扫描完成后显式重建。 */
    suspend fun rebuildFts() = dao.rebuildFts()
}

/** §5 歌单；move/remove 在同一事务内重排 position，保证连续无空洞、不删 Track。 */
class RoomPlaylistRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : PlaylistRepository {
    private val dao = db.playlistDao()

    override fun observePlaylists(): Flow<List<Playlist>> =
        dao.observePlaylists().map { rows ->
            rows.map { Playlist(it.id, it.name, it.createdAt, it.updatedAt) }
        }

    override fun observeTracks(playlistId: String): Flow<List<Track>> =
        dao.observeTracks(playlistId).map { rows -> rows.map { it.toTrack() } }

    override suspend fun create(name: String): String {
        val id = idFactory()
        val now = clock()
        db.withTransaction { dao.insertPlaylist(PlaylistRow(id, name, now, now)) }
        return id
    }

    override suspend fun rename(id: String, name: String) =
        db.withTransaction { dao.rename(id, name, clock()) }

    override suspend fun delete(id: String) = db.withTransaction {
        dao.clearMembers(id)
        dao.deletePlaylist(id)
    }

    override suspend fun addTracks(id: String, trackIds: List<TrackId>) = db.withTransaction {
        if (dao.get(id) == null) return@withTransaction
        var position = dao.nextPosition(id)
        val now = clock()
        dao.insertMembers(trackIds.map { PlaylistTrackRow(id, position++, it.value, now) })
        dao.touch(id, now)
    }

    /** §61.254：扫描导入幂等 upsert——行不存在则建；曲目全量替换（顺序 = m3u 条目序）。 */
    override suspend fun upsertImported(id: String, name: String, trackIds: List<TrackId>) =
        db.withTransaction {
            val now = clock()
            if (dao.get(id) == null) {
                dao.insertPlaylist(PlaylistRow(id, name, now, now))
            } else {
                dao.rename(id, name, now)
            }
            dao.clearMembers(id)
            dao.insertMembers(trackIds.mapIndexed { position, trackId ->
                PlaylistTrackRow(id, position, trackId.value, now)
            })
        }

    override suspend fun move(id: String, from: Int, to: Int) = rewrite(id) { list ->
        if (from !in list.indices || to !in list.indices || from == to) false
        else {
            list.add(to, list.removeAt(from))
            true
        }
    }

    override suspend fun remove(id: String, position: Int) = rewrite(id) { list ->
        val index = list.indexOfFirst { it.position == position }
        if (index < 0) false
        else {
            list.removeAt(index)
            true
        }
    }

    private suspend fun rewrite(id: String, block: (MutableList<PlaylistTrackRow>) -> Boolean) =
        db.withTransaction {
            val list = dao.members(id).toMutableList()
            if (!block(list)) return@withTransaction
            val now = clock()
            dao.clearMembers(id)
            dao.insertMembers(list.mapIndexed { index, row -> row.copy(position = index) })
            dao.touch(id, now)
        }
}

/** T11：收藏变更后 Room 的 Flow 自动推送，无需手动刷新。 */
class RoomFavoriteRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FavoriteRepository {
    private val dao = db.favoriteDao()

    override fun observe(): Flow<Set<TrackId>> =
        dao.observeIds().map { ids -> ids.map { TrackId(it) }.toSet() }

    override suspend fun toggle(id: TrackId) = db.withTransaction {
        if (dao.count(id.value) > 0) dao.remove(id.value)
        else dao.insert(FavoriteRow(id.value, clock()))
    }
}

class RoomHistoryRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : HistoryRepository {
    private val dao = db.historyDao()

    override suspend fun record(id: TrackId) = db.withTransaction {
        val previous = dao.get(id.value)
        dao.upsert(
            PlayHistoryRow(
                trackId = id.value,
                lastPlayed = clock(),
                playCount = (previous?.playCount ?: 0) + 1,
            )
        )
    }

    override fun observe(limit: Int): Flow<List<Track>> = dao.observe(limit).map { rows ->
        rows.map { it.toTrack() }
    }

    override fun count(): Flow<Int> = dao.count()
}

/**
 * 03-06 §8.1：业务队列唯一真相源落 Room；`playback_state` 单行存冷启动恢复输入。
 * Native 队列只读镜像、单向下发约束由 `NativeQueueSandbox` 守护，本类不触碰。
 */
class RoomQueueRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : QueueRepository {
    companion object {
        const val DEFAULT_QUEUE_ID = "default"
    }

    private val dao = db.queueDao()

    @Volatile
    private var cursorIndex = 0

    @Volatile
    private var mode = PlayMode.SEQUENCE

    /** 与内存实现同型的同步读取入口，便于测试与组合层对齐。 */
    fun currentMode(): PlayMode = mode
    fun setMode(value: PlayMode) {
        mode = value
    }

    override fun observe(): Flow<List<QueueItem>> =
        dao.observe(DEFAULT_QUEUE_ID).map { rows -> rows.map { it.toModel() } }

    override suspend fun setAll(tracks: List<Track>) {
        android.util.Log.w("CM-Queue", "setAll size=%d caller=%s".format(
            tracks.size, Throwable().stackTrace.getOrNull(2)?.methodName ?: "?"))
        db.withTransaction {
            dao.clear(DEFAULT_QUEUE_ID)
            dao.insertAll(tracks.mapIndexed { index, track -> row(index, track) })
        }
        cursorIndex = 0
    }

    override suspend fun insertNext(track: Track) = rewrite { list ->
        val at = (cursorIndex + 1).coerceIn(0, list.size)
        list.add(at, row(at, track))
    }

    override suspend fun move(from: Int, to: Int) = rewrite { list ->
        if (list.isEmpty() || from !in list.indices || to !in list.indices || from == to) return@rewrite
        list.add(to, list.removeAt(from))
    }

    override suspend fun remove(position: Int) = rewrite { list ->
        if (position !in list.indices) return@rewrite
        list.removeAt(position)
        if (cursorIndex >= list.size) cursorIndex = (list.size - 1).coerceAtLeast(0)
    }

    override suspend fun clear() {
        android.util.Log.w("CM-Queue", "clear caller=%s".format(
            Throwable().stackTrace.getOrNull(2)?.methodName ?: "?"))
        db.withTransaction {
            dao.clear(DEFAULT_QUEUE_ID)
            dao.clearSnapshot()
        }
        cursorIndex = 0
    }

    override suspend fun saveCursor(trackId: String, positionMs: Long) {
        // §61.328（B-1）：点查 position（单行），不再整队拉回内存比对。
        dao.positionOf(DEFAULT_QUEUE_ID, trackId)?.let { cursorIndex = it }
        db.withTransaction {
            dao.saveSnapshot(
                PlaybackStateRow(
                    id = 1,
                    currentTrackId = trackId,
                    positionMs = positionMs.coerceAtLeast(0),
                    playMode = mode.name,
                    updatedAt = clock(),
                )
            )
        }
    }

    override suspend fun restore(): PlaybackSnapshot? {
        val snapshot = dao.snapshot()?.toModel() ?: return null
        mode = snapshot.playMode
        return snapshot
    }

    private suspend fun rewrite(block: (MutableList<QueueItemRow>) -> Unit) {
        android.util.Log.w("CM-Queue", "rewrite caller=%s".format(
            Throwable().stackTrace.getOrNull(2)?.methodName ?: "?"))
        db.withTransaction {
            val list = dao.items(DEFAULT_QUEUE_ID).toMutableList()
            block(list)
            dao.clear(DEFAULT_QUEUE_ID)
            dao.insertAll(list.mapIndexed { index, row -> row.copy(position = index) })
        }
    }

    private fun row(position: Int, track: Track) = QueueItemRow(
        queueId = DEFAULT_QUEUE_ID,
        position = position,
        trackId = track.id.value,
        sourceRef = track.sourceRef.toJson(),
        addedAt = clock(),
    )
}

/** 03-04 §8：整轮扫描写完整快照；本次没扫到的条目由扫描层标 UNREACHABLE，不删 Track。 */
class RoomScanSnapshotStore(private val db: LibraryDatabase) : ScanSnapshotStore {
    private val dao = db.scanSnapshotDao()

    override suspend fun load(storageId: String): Map<String, ScanSnapshot> =
        dao.load(storageId).associate { it.path to it.toModel() }

    override suspend fun save(storageId: String, entries: List<ScanSnapshot>) = db.withTransaction {
        dao.clear(storageId)
        dao.saveAll(entries.map { ScanSnapshotRow.from(it) })
    }

    /** §61.204：metadata 块增量回写；500 一批防 SQLite 变量上限，幂等可重入。 */
    override suspend fun markMetadataDone(storageId: String, paths: Collection<String>) {
        paths.chunked(500).forEach { dao.markMetadataDone(storageId, it) }
    }

    override suspend fun clear(storageId: String) = dao.clear(storageId)
}

/** B13 / T17：失败记录落库并按「最近 1,000 条 / 30 天」清理。 */
class RoomFailureLog(
    private val db: LibraryDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dao = db.failureLogDao()

    fun observe(): Flow<List<FailureRecord>> = dao.observe().map { rows ->
        rows.map {
            FailureRecord(it.id, TrackId(it.trackId), it.code, it.detail, it.happenedAt)
        }
    }

    suspend fun record(trackId: TrackId, code: String, detail: String? = null) =
        dao.insert(
            FailureLogRow(
                trackId = trackId.value,
                code = code,
                detail = detail,
                happenedAt = clock(),
            )
        )

    suspend fun prune(now: Long = clock()) =
        dao.prune(now - FAILURE_LOG_MAX_AGE_MS, FAILURE_LOG_MAX)
}
