package com.example.cloudmusic.di.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 03-05 §5 / §6 / §8 的 DAO。集合查询一律返回 `Flow`（架构 §10 单向数据流），
 * 写入一律由 Repository 包在事务里；DAO 本身不做来源判断（§14 #8）。
 */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackRow>>

    /** §8：FTS4 外部内容表 MATCH；命中为空时 Repository 回落 LIKE。 */
    @Query(
        "SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.rowid = tracks_fts.docid " +
            "WHERE tracks_fts MATCH :match"
    )
    fun searchFts(match: String): Flow<List<TrackRow>>

    /** §8 / B05：短查询或 FTS 无结果的兜底；`ESCAPE` 配合 [LikePattern.escape]。 */
    @Query(
        "SELECT * FROM tracks WHERE title LIKE :like ESCAPE '\\' " +
            "OR artist LIKE :like ESCAPE '\\' OR album LIKE :like ESCAPE '\\'"
    )
    fun searchLike(like: String): Flow<List<TrackRow>>

    @Query("SELECT * FROM tracks WHERE album = :album")
    fun byAlbum(album: String): Flow<List<TrackRow>>

    @Query("SELECT * FROM tracks WHERE artist = :artist")
    fun byArtist(artist: String): Flow<List<TrackRow>>

    @Query(
        "SELECT * FROM tracks WHERE source_path = :exact " +
            "OR source_path LIKE :prefix ESCAPE '\\'"
    )
    fun byFolder(exact: String, prefix: String): Flow<List<TrackRow>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackRow?

    /** 供 upsert 合并旧状态；批大小 500 保证 `IN (:ids)` 不触 SQLite 变量上限。 */
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<TrackRow>

    @Upsert
    suspend fun upsertAll(rows: List<TrackRow>)

    @Query("UPDATE tracks SET availability = :value, updated_at = :now WHERE id = :id")
    suspend fun markAvailability(id: String, value: String, now: Long)

    /** §9 / B03：只标灰，绝不删行，歌单成员关系天然保留。 */
    @Query(
        "UPDATE tracks SET availability = 'UNREACHABLE', updated_at = :now " +
            "WHERE storage_id = :storageId AND availability != 'UNREACHABLE'"
    )
    suspend fun markOrphan(storageId: String, now: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** B06：启动时校验并重建 FTS 索引，参数固定，不接受外部输入。 */
    @Query("INSERT INTO tracks_fts(tracks_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY created_at, id")
    fun observePlaylists(): Flow<List<PlaylistRow>>

    /** §9：跨来源混合，按 position 返回，无来源字段。 */
    @Query(
        "SELECT t.* FROM playlist_tracks p INNER JOIN tracks t ON t.id = p.track_id " +
            "WHERE p.playlist_id = :playlistId ORDER BY p.position"
    )
    fun observeTracks(playlistId: String): Flow<List<TrackRow>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistRow?

    @Insert
    suspend fun insertPlaylist(row: PlaylistRow)

    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE playlists SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :id")
    suspend fun clearMembers(id: String)

    /** §7 硬规则：只删歌单与成员，绝不删 Track。 */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :id ORDER BY position")
    suspend fun members(id: String): List<PlaylistTrackRow>

    @Insert
    suspend fun insertMembers(rows: List<PlaylistTrackRow>)

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_tracks WHERE playlist_id = :id")
    suspend fun nextPosition(id: String): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT track_id FROM favorites")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM favorites WHERE track_id = :id")
    suspend fun count(id: String): Int

    @Insert
    suspend fun insert(row: FavoriteRow)

    @Query("DELETE FROM favorites WHERE track_id = :id")
    suspend fun remove(id: String)
}

@Dao
interface HistoryDao {
    @Query(
        "SELECT t.* FROM play_history h INNER JOIN tracks t ON t.id = h.track_id " +
            "ORDER BY h.last_played DESC, h.track_id"
    )
    fun observe(): Flow<List<TrackRow>>

    @Query("SELECT * FROM play_history WHERE track_id = :id")
    suspend fun get(id: String): PlayHistoryRow?

    @Upsert
    suspend fun upsert(row: PlayHistoryRow)
}

/** 03-06 §8.1 的队列与播放状态；冷启动恢复只走 [snapshot]。 */
@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items WHERE queue_id = :queueId ORDER BY position")
    fun observe(queueId: String): Flow<List<QueueItemRow>>

    @Query("SELECT * FROM queue_items WHERE queue_id = :queueId ORDER BY position")
    suspend fun items(queueId: String): List<QueueItemRow>

    @Query("DELETE FROM queue_items WHERE queue_id = :queueId")
    suspend fun clear(queueId: String)

    @Insert
    suspend fun insertAll(rows: List<QueueItemRow>)

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun snapshot(): PlaybackStateRow?

    @Upsert
    suspend fun saveSnapshot(row: PlaybackStateRow)

    @Query("DELETE FROM playback_state")
    suspend fun clearSnapshot()
}

/** 03-04 §8 增量快照：整轮扫描写完整快照，缺失项由扫描层标 UNREACHABLE。 */
@Dao
interface ScanSnapshotDao {
    @Query("SELECT * FROM scan_snapshot WHERE storage_id = :storageId")
    suspend fun load(storageId: String): List<ScanSnapshotRow>

    @Upsert
    suspend fun saveAll(rows: List<ScanSnapshotRow>)

    @Query("DELETE FROM scan_snapshot WHERE storage_id = :storageId")
    suspend fun clear(storageId: String)
}

/** B13 / T17：failure_log 只保留最近 1,000 条 / 30 天。 */
@Dao
interface FailureLogDao {
    @Insert
    suspend fun insert(row: FailureLogRow)

    @Query("SELECT * FROM failure_log ORDER BY happened_at DESC, id DESC")
    fun observe(): Flow<List<FailureLogRow>>

    @Query(
        "DELETE FROM failure_log WHERE happened_at < :cutoff OR id NOT IN " +
            "(SELECT id FROM failure_log ORDER BY happened_at DESC, id DESC LIMIT :max)"
    )
    suspend fun prune(cutoff: Long, max: Int)
}
