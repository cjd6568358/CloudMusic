package com.example.cloudmusic.di.scan

import com.example.cloudmusic.model.DuplicateGrouper
import com.example.cloudmusic.model.DuplicateReport
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.flow.first

/**
 * §61.247（V0.3 M5 / A1 分层去重）：重复曲目检测会话（挂 [PlaybackRuntime]：设置页触发，
 * §61.233 CoverScrapeSession 同型——手动 + 零 IO 读曲库，不挂扫描期）。
 *
 * 当前生产面 = **Tier2 疑似重复**（标题/艺人归一化 × 时长 ±1s，纯内存分组——零网络/磁盘读）。
 * Tier1 内容哈希确认趟挂账（需 TrackContentHasher 端口接入驱动链，另批）；
 * [DuplicateGrouper.groupByHash] 消费位已就绪。
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
