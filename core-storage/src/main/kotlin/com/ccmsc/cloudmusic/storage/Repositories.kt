package com.ccmsc.cloudmusic.storage

import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
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
    /**
     * §61.319（R2）：按 id 批量取，默认逐项委托 [get]（内存实现零改动兼容）；
     * Room 实现覆写为 `IN` 查询并按 500 分块（SQLite 变量上限），
     * 用于播放队列 setQueue 时一次取齐 CUE 标志，消除整队逐项点查。
     */
    suspend fun getMany(ids: List<TrackId>): List<Track?> = ids.map { get(it) }
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
    /**
     * §61.329（A-6）：CUE 分轨的封面继承——把「同一 (storageId, sourcePath) 的其它行」
     * 已解析出的封面回填给缺封面的锚点行（判定见 [CoverInheritance]）。返回**实际回填行数**。
     *
     * 幂等、只写空封面行，扫描后调用一次即可（旧库也被这一趟就地修好）。
     * 默认实现返回 0：测试替身（`ScanContractsTest` / `SettingsContractTest` 的
     * `FakeTrackRepository`）不承担曲库写入，生产两个实现均已覆写。
     */
    suspend fun inheritCueSplitCovers(): Int = 0
    suspend fun count(): Int
}

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeTracks(playlistId: String): Flow<List<Track>>
    suspend fun create(name: String): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun addTracks(id: String, trackIds: List<TrackId>)   // 跨来源混合
    /**
     * §61.254：扫描导入的命名歌单（m3u 展开）——按稳定 id 幂等 upsert：
     * 歌单行不存在则建（createdAt=now），存在则改名刷新；曲目**全量替换**（顺序 = m3u 条目序）。
     * 与用户手建歌单（create/addTracks）互不干扰（id 约定 pl_imp_*）。
     */
    suspend fun upsertImported(id: String, name: String, trackIds: List<TrackId>)
    suspend fun move(id: String, from: Int, to: Int)
    suspend fun remove(id: String, position: Int)
}

interface FavoriteRepository {
    fun observe(): Flow<Set<TrackId>>
    suspend fun toggle(id: TrackId)
}

interface HistoryRepository {
    suspend fun record(id: TrackId)

    /**
     * §61.328（B-1）：只推**最近** [limit] 条（默认 [LibraryQuery.HISTORY_WINDOW]）。
     * 列表是懒加载滚动区，几千行全量映射没有意义；总数见 [count]。
     */
    fun observe(limit: Int = LibraryQuery.HISTORY_WINDOW): Flow<List<Track>>

    /** §61.328（B-1）：历史总条数（去重后的曲目数，与 [observe] 的窗口无关）。 */
    fun count(): Flow<Int>
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
    /** 观察某曲目的歌词；无歌词时发 [com.ccmsc.cloudmusic.model.Lyrics.EMPTY]。 */
    fun observe(id: TrackId): Flow<com.ccmsc.cloudmusic.model.Lyrics>
    /** 写入本地歌词（同目录 lrc / 内嵌 USLT 扫描结果，或在线缓存）。 */
    suspend fun save(id: TrackId, lyrics: com.ccmsc.cloudmusic.model.Lyrics)
    /** 是否存在可用歌词（UI 决定显示占位还是歌词 View）。 */
    suspend fun has(id: TrackId): Boolean
}

/** 03-05 §6：批量 upsert 的事务大小；阶段 A / 阶段 B 共用。 */
const val UPSERT_BATCH_SIZE = 500
