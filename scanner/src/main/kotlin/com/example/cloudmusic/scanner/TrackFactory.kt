package com.example.cloudmusic.scanner

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId

/**
 * docs/03-04 §7 · Candidate → Track。
 * 实现期调整：标签读取不放在 Factory 内部，而由 ScanCoordinator 按阶段传入
 * （阶段 A 传 EMPTY 生成骨架，阶段 B 传真实 RawTags），这样两阶段分离可测（T16）。
 */
class TrackFactory(
    private val coverResolver: CoverResolver = PriorityCoverResolver(),
) {
    fun build(candidate: Candidate, raw: RawTags = RawTags.EMPTY): Track {
        val fallback = NameParser.parse(candidate.obj.name, candidate.parentDirName, candidate.hint)
        return Track(
            // 03-01 §7：不含 host / 凭据，地址变更不丢收藏
            id = TrackId.of(candidate.storage.id, candidate.obj.path),
            title = raw.title?.takeIf { it.isNotBlank() } ?: fallback.title,
            artist = raw.artist?.takeIf { it.isNotBlank() } ?: fallback.artist,
            album = raw.album?.takeIf { it.isNotBlank() } ?: fallback.album,
            durationMs = raw.durationMs ?: candidate.hint?.durationMs ?: 0L,
            trackNo = raw.trackNo ?: 0,
            year = raw.year ?: 0,
            sampleRate = raw.sampleRate ?: 0,
            bitDepth = raw.bitDepth ?: 0,
            sourceRef = candidate.sourceRef(),
            sourceType = candidate.sourceType(),
            coverRef = coverResolver.resolve(candidate, raw.embeddedCover),
            tagCompleteness = maxOf(raw.completeness, fallback.completeness),
            availability = Availability.OK,
        )
    }
}
