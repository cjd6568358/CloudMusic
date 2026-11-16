package com.example.cloudmusic.di.db

import com.example.cloudmusic.model.LegacyTextRepair

/**
 * §61.173（2026-11-16 真机一手）：**存量**乱码标题的一次性修复通道。
 *
 * 为什么必须单独做一条通道：§61.170 只修了「扫描入库路径」，而 `ScanCoordinator` 对
 * size/mtime 未变的文件直接 `addSkipped` 跳过 —— **重扫永远不会重读这些文件的标签**，
 * 已落库的脏行光靠「重扫」是修不掉的（本批真机实证：7,838 行里仍有 52 条 GBK 误读标题，
 * 且它们的 `source_path` 早已是正确的中文）。
 *
 * 语义：
 * 1. 读全库 `(id, title, artist, album, sort_title)`；
 * 2. 逐行过 `LegacyTextRepair.fix`（只在「疑似乱码且能解出 CJK」时返回非 null）；
 * 3. **至少一个字段可修**才 UPDATE，并按新 title 重算 `sort_title`；
 * 4. 无脏行时零写入 ⇒ 天然幂等，重复调用安全。
 *
 * 调用方（`MainActivity`）用 SharedPreferences 标记只跑一次，避免每次冷启动白读全库文本。
 */
class LegacyTextRepairRunner(private val dao: TrackDao) {

    /** [scanned] = 全库行数，[repaired] = 实际改写的行数。 */
    data class Result(val scanned: Int, val repaired: Int)

    suspend fun run(now: Long = System.currentTimeMillis()): Result {
        val rows = dao.allTexts()
        var repaired = 0
        for (row in rows) {
            val plan = plan(row) ?: continue
            dao.updateTexts(
                id = plan.id,
                title = plan.title,
                artist = plan.artist,
                album = plan.album,
                sortTitle = SortKeys.of(plan.title),
                now = now,
            )
            repaired++
        }
        return Result(scanned = rows.size, repaired = repaired)
    }

    companion object {
        /**
         * 纯函数：给出「修复后的一行」，三字段都没变化时返回 `null`。
         * 抽成纯函数是为了让 JVM 单测无需 Room/Context 即可断言逐行决策
         * （`LibraryRoomContractTest` 同一风格，见 `12 §26.2`）。
         *
         * 注意 `LegacyTextRepair.fix` 的契约是「修不了就原样返回」，**不是**「修不了返回 null」；
         * 所以这里必须用「前后是否相等」判定是否需要写库，否则会把全库行都无意义地 UPDATE 一遍。
         */
        fun plan(row: TrackTextRow): TrackTextRow? {
            val title = LegacyTextRepair.fix(row.title) ?: row.title
            val artist = LegacyTextRepair.fix(row.artist) ?: row.artist
            val album = LegacyTextRepair.fix(row.album) ?: row.album
            if (title == row.title && artist == row.artist && album == row.album) return null
            return row.copy(title = title, artist = artist, album = album)
        }
    }
}
