package com.example.cloudmusic.di.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlaybackSnapshot
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.scanner.CandidateKind
import com.example.cloudmusic.scanner.ScanSnapshot
import com.example.cloudmusic.storage.TrackEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * docs/03-05 §3 / 03-04 §8 / 03-06 §8.1 的 Room 实体：**9 张物理表 + 1 张 FTS4 虚拟表**。
 *
 * 第十七批（`12 §26`，T-A1）。列名逐字对齐 DDL（snake_case），枚举一律存**字符串名**
 * （§10 / §14 #7），凭据不入库（§14 #2，Storage 仍属 DataStore / T-A2）。
 * 类一律加 `Row` 后缀，避免与 `:core-storage` 的 `TrackEntity` 等纯 JVM 模型混淆。
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"], name = "idx_tracks_title"),
        Index(value = ["artist"], name = "idx_tracks_artist"),
        Index(value = ["album"], name = "idx_tracks_album"),
        Index(value = ["storage_id"], name = "idx_tracks_storage"),
        Index(value = ["availability"], name = "idx_tracks_avail"),
        Index(value = ["sort_title"], name = "idx_tracks_sort_title"),
    ],
)
data class TrackRow(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "track_no") val trackNo: Int,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int,
    @ColumnInfo(name = "storage_id") val storageId: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "cover_ref") val coverRef: String?,
    /** §61.120（T5-2 前置）：分析落库增益（dB）；null = 未分析。 */
    @ColumnInfo(name = "replay_gain_db") val replayGainDb: Float?,
    @ColumnInfo(name = "tag_completeness") val tagCompleteness: Float,
    @ColumnInfo(name = "availability") val availability: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sort_title", defaultValue = "''") val sortTitle: String,
) {
    /** §13 T01：Room 行 → `:core-storage` 的实体，复用其 `toTrack()` 保证往返一致。 */
    fun toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNo = trackNo,
        year = year,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        storageId = storageId,
        sourcePath = sourcePath,
        sourceUrl = sourceUrl,
        sourceType = sourceType,
        coverRef = coverRef,
        replayGainDb = replayGainDb,
        tagCompleteness = tagCompleteness,
        availability = availability,
        addedAt = addedAt,
        updatedAt = updatedAt,
    )

    fun toTrack(): Track = toEntity().toTrack()

    companion object {
        /**
         * §6 / §14 #3 / 实现期澄清 C-04：upsert **不得**重置 `availability`、`added_at`，
         * 也不得触碰收藏 / 播放次数（那些在独立表）。恢复语义只走 `ScanReconciler.upsertFromScan`。
         * 纯函数，便于在无 Android 运行时的 JVM 单测里断言。
         */
        fun from(track: Track, existing: TrackRow?, now: Long): TrackRow = TrackRow(
            id = track.id.value,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            trackNo = track.trackNo,
            year = track.year,
            sampleRate = track.sampleRate,
            bitDepth = track.bitDepth,
            storageId = track.sourceRef.storageId,
            sourcePath = track.sourceRef.path,
            sourceUrl = track.sourceRef.url,
            sourceType = track.sourceType.name,
            coverRef = track.coverRef,
            // §61.120：分析增益只由 T5-2 分析管线写入，扫描 upsert 保留旧值。
            replayGainDb = existing?.replayGainDb ?: track.replayGainDb,
            tagCompleteness = track.tagCompleteness,
            availability = existing?.availability ?: track.availability.name,
            addedAt = existing?.addedAt ?: now,
            updatedAt = now,
            sortTitle = SortKeys.of(track.title),
        )
    }
}

/** §3.1 FTS4 外部内容表；Room 自动生成同步触发器，B06 由 `onOpen` 的 rebuild 兜底。 */
@Fts4(contentEntity = TrackRow::class)
@Entity(tableName = "tracks_fts")
data class TrackFtsRow(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
)

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "position"],
    indices = [Index(value = ["track_id"], name = "idx_pt_track")],
)
data class PlaylistTrackRow(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteRow(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "play_history")
data class PlayHistoryRow(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "last_played") val lastPlayed: Long,
    @ColumnInfo(name = "play_count") val playCount: Int,
)

@Entity(
    tableName = "failure_log",
    indices = [Index(value = ["happened_at"], name = "idx_failure_time")],
)
data class FailureLogRow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "detail") val detail: String?,
    @ColumnInfo(name = "happened_at") val happenedAt: Long,
)

/** 03-04 §8 `(storage_id, path, size, modified, kind, scanned_at)`。 */
@Entity(tableName = "scan_snapshot", primaryKeys = ["storage_id", "path"])
data class ScanSnapshotRow(
    @ColumnInfo(name = "storage_id") val storageId: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "modified") val modified: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
) {
    fun toModel(): ScanSnapshot = ScanSnapshot(
        storageId = storageId,
        path = path,
        size = size,
        modified = modified,
        kind = CandidateKind.valueOf(kind),
        scannedAt = scannedAt,
    )

    companion object {
        fun from(model: ScanSnapshot): ScanSnapshotRow = ScanSnapshotRow(
            storageId = model.storageId,
            path = model.path,
            size = model.size,
            modified = model.modified,
            kind = model.kind.name,
            scannedAt = model.scannedAt,
        )
    }
}

/** 03-06 §8.1：业务队列唯一真相源；`source_ref` 存 SourceRef 的 JSON（不缓存 Link）。 */
@Entity(
    tableName = "queue_items",
    primaryKeys = ["queue_id", "position"],
    indices = [Index(value = ["track_id"], name = "idx_queue_track")],
)
data class QueueItemRow(
    @ColumnInfo(name = "queue_id") val queueId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
) {
    fun toModel(): QueueItem = QueueItem(
        position = position,
        trackId = TrackId(trackId),
        sourceRef = sourceRef.toSourceRef(),
        addedAt = addedAt,
        queueId = queueId,
    )
}

/** 03-06 §8.1：单行 `id = 1`，冷启动恢复的唯一业务输入。 */
@Entity(tableName = "playback_state")
data class PlaybackStateRow(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "current_track_id") val currentTrackId: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "play_mode") val playMode: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toModel(): PlaybackSnapshot = PlaybackSnapshot(
        currentTrackId = currentTrackId,
        positionMs = positionMs,
        playMode = PlayMode.valueOf(playMode),
        updatedAt = updatedAt,
    )
}

private val sourceRefJson = Json { ignoreUnknownKeys = true }

/** SourceRef → JSON；`url` 缺失用 JsonNull，不用空串以免与合法空 URL 混淆。 */
fun SourceRef.toJson(): String = buildJsonObject {
    put("storageId", storageId)
    put("path", path)
    if (url == null) put("url", JsonNull) else put("url", url)
}.toString()

fun String.toSourceRef(): SourceRef {
    val obj = sourceRefJson.parseToJsonElement(this).jsonObject
    val urlNode = obj["url"]
    val url = if (urlNode == null || urlNode is JsonNull) null
    else urlNode.jsonPrimitive.contentOrNull
    return SourceRef(
        storageId = obj.getValue("storageId").jsonPrimitive.content,
        path = obj.getValue("path").jsonPrimitive.content,
        url = url,
    )
}
