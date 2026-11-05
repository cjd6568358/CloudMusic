package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** docs/03-05 §3：`play_history` 表在内存里的等价物。 */
data class HistoryEntry(val trackId: TrackId, val lastPlayed: Long, val playCount: Int)

/**
 * 所有内存 Repository 共享的单一状态源：任何写入都通过 `state.update` 原子替换，
 * 派生 Flow 会自动推送新值（§5「所有返回集合的方法用 Flow」、T11）。
 */
data class LibraryState(
    val tracks: Map<String, TrackEntity> = emptyMap(),
    val playlists: Map<String, Playlist> = emptyMap(),
    val members: Map<String, List<PlaylistMember>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val history: Map<String, HistoryEntry> = emptyMap(),
    val storages: Map<String, Storage> = emptyMap(),
    val failures: List<FailureRecord> = emptyList(),
    /** 便于 B02 / T06 断言「500 条一批事务」的计数，Android Room 实现不需要该字段。 */
    val batchCount: Int = 0,
)

class InMemoryLibraryStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    val state = MutableStateFlow(LibraryState())

    fun snapshot(): LibraryState = state.value
    fun mutate(block: (LibraryState) -> LibraryState) {
        state.update(block)
    }

    fun now(): Long = clock()
    fun newId(): String = idFactory()
}

/** docs/03-05 §5 / §6 / §7 / §8 的 TrackRepository 纯 JVM 实现。 */
class InMemoryTrackRepository(
    val store: InMemoryLibraryStore = InMemoryLibraryStore(),
) : TrackRepository {

    override fun observeAll(): Flow<List<Track>> = store.state.map { s ->
        LibraryQuery.sort(s.tracks.values.map { it.toTrack() })
    }

    override fun search(query: String): Flow<List<Track>> =
        observeAll().map { LibraryQuery.search(it, query) }

    override fun byAlbum(album: String): Flow<List<Track>> =
        observeAll().map { list -> list.filter { it.album == album } }

    override fun byArtist(artist: String): Flow<List<Track>> =
        observeAll().map { list -> list.filter { it.artist == artist } }

    override fun byFolder(prefix: String): Flow<List<Track>> =
        observeAll().map { LibraryQuery.byFolder(it, prefix) }

    override suspend fun get(id: TrackId): Track? = store.snapshot().tracks[id.value]?.toTrack()

    /**
     * §6：每 500 条一个事务；保留 `addedAt`；**不覆盖** `availability`（§14 #3）。
     * 收藏 / 播放次数存独立表，upsert 天然不会触及。
     * 恢复语义由 `ScanReconciler.upsertFromScan` 显式触发，见实现期澄清 C-04。
     */
    override suspend fun upsertBatch(tracks: List<Track>) {
        tracks.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            store.mutate { s ->
                val now = store.now()
                val next = LinkedHashMap(s.tracks)
                batch.forEach { t ->
                    val previous = next[t.id.value]
                    next[t.id.value] = TrackEntity.from(
                        track = t,
                        addedAt = previous?.addedAt ?: now,
                        updatedAt = now,
                        availability = previous?.availability ?: t.availability.name,
                    )
                }
                s.copy(tracks = next, batchCount = s.batchCount + 1)
            }
        }
    }

    override suspend fun markAvailability(id: TrackId, a: Availability) {
        store.mutate { s ->
            val entity = s.tracks[id.value] ?: return@mutate s
            s.copy(
                tracks = s.tracks + (id.value to entity.copy(
                    availability = a.name,
                    updatedAt = store.now(),
                )),
            )
        }
    }

    /** §9：删除 Storage 时把相关曲目标 UNREACHABLE，歌单成员关系保留。 */
    override suspend fun markOrphan(storageId: String) {
        store.mutate { s ->
            val next = s.tracks.mapValues { (_, entity) ->
                if (entity.storageId == storageId && entity.availability != Availability.UNREACHABLE.name) {
                    entity.copy(
                        availability = Availability.UNREACHABLE.name,
                        updatedAt = store.now(),
                    )
                } else entity
            }
            s.copy(tracks = next)
        }
    }

    override suspend fun count(): Int = store.snapshot().tracks.size
}

/**
 * 实现期澄清 C-04（docs/12 §13）：03-04 §8 / 03-05 T10 要求「文件恢复出现自动置回 OK」，
 * 而 03-05 §6 / §14 #3 又要求 upsert 不重置 availability，两者在 Room 的 `@Upsert`
 * 全列覆盖下会直接冲突。
 *
 * 处置：`upsertBatch` 严格保留既有状态；「重扫入库」走这里的显式通道，在 upsert 之后
 * 对本次确实扫到的曲目按扫描结果调用 `markAvailability`。播放失败标记的 LINK_EXPIRED
 * 不会被阶段 B 的元数据 upsert 抹掉，只有重扫成功才会恢复。
 * 本类不依赖 `:scanner`（避免 L1 反向依赖上层）；Android 侧组合时把 03-04 的
 * `TrackSink.upsertBatch / markUnavailable` 委托给本类即可。Room 实现可把逐条更新
 * 优化为单事务批量 UPDATE，语义不变。
 */
class ScanReconciler(private val tracks: TrackRepository) {
    suspend fun upsertFromScan(batch: List<Track>) {
        tracks.upsertBatch(batch)
        batch.forEach { tracks.markAvailability(it.id, it.availability) }
    }

    suspend fun markUnavailable(ids: List<TrackId>) {
        ids.forEach { tracks.markAvailability(it, Availability.UNREACHABLE) }
    }
}

/** docs/03-05 §5 / §3.2：跨来源歌单，成员按 position 排序，不做来源判断。 */
class InMemoryPlaylistRepository(
    private val store: InMemoryLibraryStore,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        store.state.map { s -> s.playlists.values.sortedWith(compareBy<Playlist> { it.createdAt }.thenBy { it.id }) }

    override fun observeTracks(playlistId: String): Flow<List<Track>> = store.state.map { s ->
        s.members[playlistId].orEmpty()
            .sortedBy { it.position }
            .mapNotNull { m -> s.tracks[m.trackId.value]?.toTrack() }
    }

    override suspend fun create(name: String): String {
        val id = store.newId()
        val now = store.now()
        store.mutate { s ->
            s.copy(playlists = s.playlists + (id to Playlist(id, name, now, now)))
        }
        return id
    }

    override suspend fun rename(id: String, name: String) {
        store.mutate { s ->
            val p = s.playlists[id] ?: return@mutate s
            s.copy(playlists = s.playlists + (id to p.copy(name = name, updatedAt = store.now())))
        }
    }

    /** 只删歌单与成员关系，绝不删 Track（§7 硬规则 / §14 #5）。 */
    override suspend fun delete(id: String) {
        store.mutate { s ->
            s.copy(playlists = s.playlists - id, members = s.members - id)
        }
    }

    override suspend fun addTracks(id: String, trackIds: List<TrackId>) {
        store.mutate { s ->
            val playlist = s.playlists[id] ?: return@mutate s
            val now = store.now()
            val list = s.members[id].orEmpty().toMutableList()
            trackIds.forEach { tid -> list.add(PlaylistMember(list.size, tid, now)) }
            s.copy(
                members = s.members + (id to list),
                playlists = s.playlists + (id to playlist.copy(updatedAt = now)),
            )
        }
    }

    override suspend fun move(id: String, from: Int, to: Int) {
        store.mutate { s ->
            val list = s.members[id].orEmpty().toMutableList()
            if (from !in list.indices || to !in list.indices) return@mutate s
            val item = list.removeAt(from)
            list.add(to, item)
            s.copy(members = s.members + (id to renumber(list)))
        }
    }

    override suspend fun remove(id: String, position: Int) {
        store.mutate { s ->
            val list = s.members[id].orEmpty().toMutableList()
            val index = list.indexOfFirst { it.position == position }
            if (index < 0) return@mutate s
            list.removeAt(index)
            s.copy(members = s.members + (id to renumber(list)))
        }
    }

    private fun renumber(list: List<PlaylistMember>): List<PlaylistMember> =
        list.mapIndexed { i, m -> m.copy(position = i) }
}

class InMemoryFavoriteRepository(
    private val store: InMemoryLibraryStore,
) : FavoriteRepository {
    override fun observe(): Flow<Set<TrackId>> =
        store.state.map { s -> s.favorites.map { TrackId(it) }.toSet() }

    /** T11：Flow 立即推送新集合。 */
    override suspend fun toggle(id: TrackId) {
        store.mutate { s ->
            s.copy(favorites = if (id.value in s.favorites) s.favorites - id.value else s.favorites + id.value)
        }
    }
}

class InMemoryHistoryRepository(
    private val store: InMemoryLibraryStore,
) : HistoryRepository {
    override suspend fun record(id: TrackId) {
        store.mutate { s ->
            val old = s.history[id.value]
            val now = store.now()
            val entry = HistoryEntry(id, now, (old?.playCount ?: 0) + 1)
            s.copy(history = s.history + (id.value to entry))
        }
    }

    override fun observe(): Flow<List<Track>> = store.state.map { s ->
        s.history.values
            .sortedWith(compareByDescending<HistoryEntry> { it.lastPlayed }.thenBy { it.trackId.value })
            .mapNotNull { e -> s.tracks[e.trackId.value]?.toTrack() }
    }
}

/** docs/03-05 §2 / §5：Storage 存 DataStore；删除时显式 markOrphan（无物理外键）。 */
class InMemoryStorageRepository(
    private val store: InMemoryLibraryStore,
    private val tracks: TrackRepository,
) : StorageRepository {
    override fun observe(): Flow<List<Storage>> =
        store.state.map { s -> s.storages.values.sortedBy { it.modified } }

    override suspend fun add(type: String, additionJson: String): Storage {
        val storage = Storage(
            id = store.newId(),
            mountPath = "/$type",
            driverType = type,
            addition = Addition(additionJson),
            modified = store.now(),
        )
        store.mutate { s -> s.copy(storages = s.storages + (storage.id to storage)) }
        return storage
    }

    override suspend fun update(s: Storage) {
        store.mutate { state -> state.copy(storages = state.storages + (s.id to s)) }
    }

    override suspend fun remove(id: String) {
        store.mutate { s -> s.copy(storages = s.storages - id) }
        tracks.markOrphan(id)
    }

    override suspend fun setError(id: String, msg: String) {
        store.mutate { s ->
            val storage = s.storages[id] ?: return@mutate s
            s.copy(
                storages = s.storages + (id to storage.copy(errorMsg = msg, modified = store.now())),
            )
        }
    }
}

/** docs/03-05 §3.4 / B13：失败记录，启动时按「最近 1,000 条 / 30 天」清理。 */
class InMemoryFailureLog(private val store: InMemoryLibraryStore) {
    private val nextId = AtomicLong(1)

    fun observe(): Flow<List<FailureRecord>> = store.state.map { it.failures }

    suspend fun record(trackId: TrackId, code: String, detail: String? = null) {
        val entry = FailureRecord(nextId.getAndIncrement(), trackId, code, detail, store.now())
        store.mutate { s -> s.copy(failures = s.failures + entry) }
    }

    suspend fun prune(now: Long = store.now()) {
        store.mutate { s -> s.copy(failures = LibraryQuery.pruneFailures(s.failures, now)) }
    }
}
