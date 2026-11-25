package com.ccmsc.cloudmusic.di.scan

import com.ccmsc.cloudmusic.model.DuplicateGrouper
import com.ccmsc.cloudmusic.model.DuplicateReport
import com.ccmsc.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * §61.247（V0.3 M5 / A1 分层去重）：重复曲目检测会话（挂 [PlaybackRuntime]：设置页触发，
 * §61.233 CoverScrapeSession 同型——手动 + 零 IO 读曲库，不挂扫描期）。
 *
 * 当前生产面 = **Tier2 疑似重复**（标题/艺人归一化 × 时长 ±1s，纯内存分组——零网络/磁盘读）。
 * 处置完全由用户手动（PRD 原则 6 读优先：不自动删除/合并）。
 */
class DuplicateScanSession(private val tracks: TrackRepository) {

    suspend fun detect(): DuplicateReport {
        val all = tracks.observeAll().first()
        return DuplicateReport(
            scanned = all.size,
            groups = DuplicateGrouper.suspectedDuplicates(all),
        )
    }
}

/**
 * §61.248（A1 Tier1）：内容哈希确认趟——对 Tier2 疑似组的**唯一候选文件**（去重后
 * [maxHashCount] 限数防流量失控，§61.233 先例）逐个全读 SHA-256 →
 * [DuplicateGrouper.groupByHash] 升级出**精确确认组**。候选数 = 疑似组文件去重计数；
 * 超限文件不尝试（如实计数）；哈希 null（驱动缺失/读取失败）= hashFailed 不伪造指纹。
 *
 * §61.250（累进口径）：[memo] 为调用方持有的跨次指纹记忆（key = "storageId|path"）——
 * **缺陷修复**：§61.248 限数固定取前 N，重跑永远只算同一批、candidates>50 时永远到不了
 * 后面的候选。现口径 = 只对 **memo 未覆盖**的候选逐次限数推进；命中 memo 的直接复用
 * （零 IO）；失败**不记忆**（下轮自动重试）。确认分组消费 memo∪本轮全部指纹。
 */
class DuplicateHashSession(
    private val tracks: TrackRepository,
    private val hasher: com.ccmsc.cloudmusic.di.scan.TrackContentHasher,
) {

    suspend fun detect(
        maxHashCount: Int = 50,
        memo: MutableMap<String, String> = HashMap(),
    ): DuplicateReport {
        val all = tracks.observeAll().first()
        val suspected = DuplicateGrouper.suspectedDuplicates(all)
        val candidates = suspected.flatMap { it.items }
            .distinctBy { it.sourceRef.storageId to it.sourceRef.path }
        val memoKey: (com.ccmsc.cloudmusic.model.Track) -> String = {
            "${it.sourceRef.storageId}|${it.sourceRef.path}"
        }
        val pending = candidates.filter { memoKey(it) !in memo }
        val toHash = pending.take(maxHashCount)
        var failed = 0
        for (track in toHash) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val h = runCatching { hasher.hash(track) }.getOrNull()
            if (h.isNullOrBlank()) {
                failed++   // 失败不记忆：下轮重试（驱动可能已恢复）
            } else {
                memo[memoKey(track)] = h
            }
        }
        val hashedTracks = candidates.filter { memo.containsKey(memoKey(it)) }
        val confirmed = DuplicateGrouper.groupByHash(hashedTracks) { memo[memoKey(it)] }
        return DuplicateReport(
            scanned = all.size,
            groups = suspected,
            confirmed = confirmed,
            candidates = candidates.size,
            hashedCount = toHash.size - failed,
            hashFailed = failed,
            memoHits = candidates.size - pending.size,
            memoSize = memo.size,
        )
    }
}
