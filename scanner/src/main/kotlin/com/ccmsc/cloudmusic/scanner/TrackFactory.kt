package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId

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
        // §61.221：CUE 分轨的元数据来自 cue 文本（候选展开时已带 cueRaw），优先于音频 tag——
        // 整轨文件的 tag 是专辑级信息，轨级标题/艺人只存在于 cue；阶段 B 因此对分轨跳过 tag 读取。
        val effective = candidate.cueRaw ?: raw
        return Track(
            // 03-01 §7：不含 host / 凭据，地址变更不丢收藏
            // §61.221：分轨 id 用锚点路径（<音频>#<cue>#<轨号>），同一音频的多分轨各自成行；
            // sourceRef.path 保持真实音频路径（播放取流 / driver.get 语义不变）。
            id = TrackId.of(candidate.storage.id, candidate.effectiveIdPath()),
            title = effective.title?.takeIf { it.isNotBlank() } ?: fallback.title,
            artist = effective.artist?.takeIf { it.isNotBlank() } ?: fallback.artist,
            album = effective.album?.takeIf { it.isNotBlank() } ?: fallback.album,
            durationMs = effective.durationMs ?: candidate.hint?.durationMs ?: 0L,
            trackNo = effective.trackNo ?: 0,
            year = effective.year ?: 0,
            sampleRate = effective.sampleRate ?: 0,
            bitDepth = effective.bitDepth ?: 0,
            sourceRef = candidate.sourceRef(),
            sourceType = candidate.sourceType(),
            coverRef = coverResolver.resolve(candidate, effective.embeddedCover),
            tagCompleteness = maxOf(effective.completeness, fallback.completeness),
            availability = Availability.OK,
            cueStartMs = candidate.cueStartMs,
            cueEndMs = candidate.cueEndMs,
        )
    }
}
