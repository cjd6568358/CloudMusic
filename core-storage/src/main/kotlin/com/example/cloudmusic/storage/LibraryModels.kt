package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId

/** docs/03-05 §3.2 `playlists` 表；:core-model 未定义该业务模型，故在此就近定义（实现期补充 C-03）。 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** docs/03-05 §3.2 `playlist_tracks` 表；trackId 只是逻辑引用，无来源字段（§9 跨来源透明）。 */
data class PlaylistMember(
    val position: Int,
    val trackId: TrackId,
    val addedAt: Long,
)

/** docs/03-05 §3.4 `failure_log` 表（03-06 §13 引用）。 */
data class FailureRecord(
    val id: Long,
    val trackId: TrackId,
    val code: String,
    val detail: String? = null,
    val happenedAt: Long,
)

/**
 * docs/03-05 §3.1 `tracks` 表的内存等价物。
 *
 * 枚举一律按**字符串名**存取（`Availability` / `SourceType`），新增枚举值时不需要 Migration
 * （§10 / §14 #7）。`addedAt` / `updatedAt` 是 DB 侧字段，不属于业务层 `Track`。
 */
data class TrackEntity(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int,
    val year: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val storageId: String,
    val sourcePath: String,
    val sourceUrl: String?,
    val sourceType: String,
    val coverRef: String?,
    /** §61.120（T5-2 前置）：分析落库增益（dB）；null = 未分析。 */
    val replayGainDb: Float?,
    val tagCompleteness: Float,
    val availability: String,
    val addedAt: Long,
    val updatedAt: Long,
) {
    /** §13 T01：实体 → 业务模型，所有业务字段必须往返一致。 */
    fun toTrack(): Track = Track(
        id = TrackId(id),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNo = trackNo,
        year = year,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        sourceRef = SourceRef(storageId, sourcePath, sourceUrl),
        sourceType = SourceType.valueOf(sourceType),
        coverRef = coverRef,
        replayGainDb = replayGainDb,
        tagCompleteness = tagCompleteness,
        availability = Availability.valueOf(availability),
    )

    companion object {
        /**
         * §6 / §14 #3：upsert 不得重置 `addedAt`，也不得覆盖由其它 Repository 维护的
         * `availability`。调用方必须显式传入旧的 `availability`；新增行用传入 Track 的状态。
         */
        fun from(
            track: Track,
            addedAt: Long,
            updatedAt: Long,
            availability: String = track.availability.name,
        ): TrackEntity = TrackEntity(
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
            replayGainDb = track.replayGainDb,
            tagCompleteness = track.tagCompleteness,
            availability = availability,
            addedAt = addedAt,
            updatedAt = updatedAt,
        )
    }
}
