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
    /** §61.145：批量可用性置位（重扫恢复 OK / markUnavailable 批路径）——默认逐行委托，
     *  生产实现必须覆写为单事务批量（逐行 suspend + 每行独立 WAL 事务是 7,500 重扫
     *  阶段 A 更新路径分钟级卡点的直接成分，§61.144 真机一手）。 */
    suspend fun markAvailabilityBatch(ids: List<TrackId>, a: Availability) {
        ids.forEach { markAvailability(it, a) }
    }
    suspend fun markOrphan(storageId: String)             // 删除 Storage 时调用
    /** §61.122（T5-2）：分析管线落库曲目增益（dB，负=衰减）；仅 LOCAL opt-in。 */
    suspend fun markReplayGain(id: TrackId, gainDb: Float)
    /**
     * §61.181（B 案）：引擎真值时长回填——只写**缺值行**（durationMs<=0，
     * STRM / 无标签），已有元数据不覆盖；调用方须传引擎解出的真实时长（>0）。
     */
    suspend fun updateDuration(id: TrackId, durationMs: Long)
    /** §61.233（V0.2 M9 刮削）：封面刮削落库——只写**缺封面行**（cover_ref IS NULL），不覆盖已有封面。 */
    suspend fun updateCover(id: TrackId, coverRef: String)
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

/**
 * docs/01 §0（2026-11-16 拍板）：歌词仓库。本地优先（同目录 .lrc / 内嵌 USLT），
 * 本地缺失时允许在线 provider 兜底（个人自用，不考虑合规）。
 * 以 [TrackId] 为键，与 Track 结构解耦——不新增 Track 字段、不动 Room tracks 表。
 */
interface LyricRepository {
    /** 观察某曲目的歌词；无歌词时发 [com.example.cloudmusic.model.Lyrics.EMPTY]。 */
    fun observe(id: TrackId): Flow<com.example.cloudmusic.model.Lyrics>
    /** 写入本地歌词（同目录 lrc / 内嵌 USLT 扫描结果，或在线缓存）。 */
    suspend fun save(id: TrackId, lyrics: com.example.cloudmusic.model.Lyrics)
    /** 是否存在可用歌词（UI 决定显示占位还是歌词 View）。 */
    suspend fun has(id: TrackId): Boolean
}

/** 03-05 §6：批量 upsert 的事务大小；阶段 A / 阶段 B 共用。 */
const val UPSERT_BATCH_SIZE = 500
