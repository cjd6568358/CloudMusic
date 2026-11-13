package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * docs/03-05 §5 的 Repository 契约，**签名逐字对齐**。
 *
 * 所有返回集合的方法用 Flow：Room / DataStore 变更自动推给 UI（架构 §10 单向数据流）。
 * 本模块给出纯 JVM 内存实现；Room / FTS4 / DataStore 适配在 Android 侧按同一契约落地。
 * Repository 层不做来源判断，跨来源必须透明（§9 / §14 #8）。
 */
interface TrackRepository {
    fun observeAll(): Flow<List<Track>>
    fun search(query: String): Flow<List<Track>>          // FTS + LIKE 兜底
    fun byAlbum(album: String): Flow<List<Track>>
    fun byArtist(artist: String): Flow<List<Track>>
    fun byFolder(prefix: String): Flow<List<Track>>
    suspend fun get(id: TrackId): Track?
    suspend fun upsertBatch(tracks: List<Track>)          // ★每 500 条一个事务
    suspend fun markAvailability(id: TrackId, a: Availability)
    suspend fun markOrphan(storageId: String)             // 删除 Storage 时调用
    /** §61.122（T5-2）：分析管线落库曲目增益（dB，负=衰减）；仅 LOCAL opt-in。 */
    suspend fun markReplayGain(id: TrackId, gainDb: Float)
    suspend fun count(): Int
}

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeTracks(playlistId: String): Flow<List<Track>>
    suspend fun create(name: String): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun addTracks(id: String, trackIds: List<TrackId>)   // 跨来源混合
    suspend fun move(id: String, from: Int, to: Int)
    suspend fun remove(id: String, position: Int)
}

interface FavoriteRepository {
    fun observe(): Flow<Set<TrackId>>
    suspend fun toggle(id: TrackId)
}

interface HistoryRepository {
    suspend fun record(id: TrackId)
    fun observe(): Flow<List<Track>>
}

interface StorageRepository {
    fun observe(): Flow<List<Storage>>
    suspend fun add(type: String, additionJson: String): Storage   // 生成稳定 UUID
    suspend fun update(s: Storage)
    suspend fun remove(id: String)                                 // 同时 markOrphan
    suspend fun setError(id: String, msg: String)
}

/** 03-05 §6：批量 upsert 的事务大小；阶段 A / 阶段 B 共用。 */
const val UPSERT_BATCH_SIZE = 500
