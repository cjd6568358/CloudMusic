package com.example.cloudmusic.di.scan

import com.example.cloudmusic.model.AudioFingerprint
import com.example.cloudmusic.model.DuplicateGroup
import com.example.cloudmusic.model.DuplicateReport
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.flow.first

/**
 * §61.256（V0.3 M5 / A1 Tier3）：声纹确认趟——对 Tier2 疑似组中**哈希未确认**的候选
 * （转码/不同格式拷贝）解码 → 频谱指纹 → 组内两两匹配 → 升级「声纹疑似同源」组。
 *
 * 口径（如实登记）：
 * - 自研算法（`AudioFingerprint`，纯 Kotlin 零依赖）——精度无第三方基准，阈值保守，
 *   结果 = **疑似**（处置用户手动，PRD 原则 6）；
 * - 不可解码（远端取流失败/编解码器不支持/孤儿源）→ 跳过计数，不伪造；
 * - 限数 [maxCount] 防解码流量/时长失控（§61.233 先例）；
 * - 输入 = [DuplicateReport]（Tier1 哈希趟产物）——已确认组不重复声纹。
 */
class FingerprintSession(
    private val tracks: TrackRepository,
    private val decoder: PcmDecoder,
) {

    suspend fun detect(base: DuplicateReport, maxCount: Int = 20): DuplicateReport {
        // 已被 Tier1 确认组的成员不再声纹（同源已实锤）
        val confirmedIds = base.confirmed.flatMap { it.items }.map { it.id }.toSet()
        val candidates = base.groups.flatMap { it.items }
            .filter { it.id !in confirmedIds }
            .distinctBy { it.sourceRef.storageId to it.sourceRef.path }
            .take(maxCount)
        val fingerprints = HashMap<TrackId, Set<Long>>()
        var failed = 0
        for (track in candidates) {
            currentCoroutineContext().ensureActive()
            val pcm = runCatching { decoder.decodeMono(track, DECODE_MAX_SECONDS) }.getOrNull()
            val fp = pcm?.let { AudioFingerprint.fingerprint(it.pcm, it.sampleRate) }
            if (fp.isNullOrEmpty()) failed++ else fingerprints[track.id] = fp
        }
        // 组内两两匹配（候选按组聚合：组内 ≥2 个声纹命中对 → 组升级）
        val fpConfirmed = mutableListOf<DuplicateGroup>()
        for (group in base.groups) {
            val members = group.items.filter { it.id in fingerprints.keys }
            if (members.size < 2) continue
            // 并查集式聚合：任一成员与组内另一成员命中 → 同簇
            val clusters = mutableListOf<MutableList<Track>>()
            for (t in members) {
                var merged = false
                for (cluster in clusters) {
                    if (cluster.any { c ->
                            AudioFingerprint.similarity(
                                fingerprints.getValue(t.id), fingerprints.getValue(c.id),
                            ) >= AudioFingerprint.MATCH_THRESHOLD
                        }
                    ) {
                        cluster += t; merged = true; break
                    }
                }
                if (!merged) clusters += mutableListOf(t)
            }
            clusters.filter { it.size >= 2 }.forEach { c ->
                fpConfirmed += DuplicateGroup(
                    title = c.first().title, artist = c.first().artist,
                    items = c.sortedBy { it.sourceRef.path },
                )
            }
        }
        return base.copy(
            fingerprintConfirmed = fpConfirmed,
            fingerprintedCount = fingerprints.size,
            fingerprintFailed = failed,
        )
    }

    companion object {
        /** 单曲解码限秒（指纹代表性片段）。 */
        const val DECODE_MAX_SECONDS: Int = 30

        /** 声纹趟候选限数。 */
        const val DEFAULT_MAX_COUNT: Int = 20
    }
}
