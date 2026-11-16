package com.example.cloudmusic.scanner

import com.example.cloudmusic.model.LegacyTextRepair

/** docs/03-04 §6.5 · 标签缺失时的文件名 / 目录名兜底。 */
object NameParser {

    data class Fallback(
        val title: String,
        val artist: String,
        val album: String,
        val completeness: Float,
    )

    fun parse(fileName: String, parentDirName: String, hint: TrackHint? = null): Fallback {
        // §61.170（2026-11-16 真机一手，DB 一手取证）：兜底名有**两类**脏数据，
        // 都在这里统一清洗（这是所有「无标签」标题的唯一入口）：
        // ① 百分号编码：STRM 侧文件名是 `S.H.E%20-%20%E8%8C%B1%E7%BD%97%E8%AE%B0.wma`，
        //    直接展示就是天书 → 先按 URL 规则解一次（只解 `%XX`，解不动就保留原文）；
        // ② GBK 字节被当 ISO-8859-1 解出来的乱码（WebDAV/旧 ID3 同型）→ LegacyTextRepair。
        val cleanName = normalize(fileName) ?: fileName
        val cleanDir = normalize(parentDirName) ?: parentDirName

        val base = cleanName.substringBeforeLast('.', cleanName).trim()
        val parts = base.split(" - ", limit = 2)
        val hasSplit = parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()

        val title = LegacyTextRepair.fix(hint?.title)?.takeIf { it.isNotBlank() }
            ?: if (hasSplit) parts[1].trim() else base.ifBlank { cleanName }
        val artist = LegacyTextRepair.fix(hint?.artist)?.takeIf { it.isNotBlank() }
            ?: if (hasSplit) parts[0].trim() else artistFromParent(cleanDir)
        val album = albumFromParent(cleanDir)

        // 仅文件名级信息 → 0.2（03-04 §6.5）
        return Fallback(title, artist, album, 0.2f)
    }

    /**
     * 先解百分号编码（仅当确实含 `%XX`），再做 GBK 乱码修复。
     *
     * §61.173：实现统一收敛到 `LegacyTextRepair`（原先这里有一份重复的 URLDecoder 逻辑），
     * 区别只在 `requireCjk = false` —— 文件名通道解出来就是给人看的，纯英文（`My%20Song`）也该解；
     * 而通用文本通道（入库 / 存量修复）要求解出汉字才动手，避免误解正常文本。
     */
    internal fun normalize(raw: String): String? {
        if (raw.isEmpty()) return raw
        val percentDecoded = LegacyTextRepair.decodePercent(raw, requireCjk = false) ?: raw
        return LegacyTextRepair.fix(percentDecoded)
    }

    private fun artistFromParent(parent: String): String {
        val parts = parent.split(" - ", limit = 2)
        return if (parts.size == 2) parts[0].trim().ifBlank { "未知" } else "未知"
    }

    private fun albumFromParent(parent: String): String {
        val parts = parent.split(" - ", limit = 2)
        return (if (parts.size == 2) parts[1] else parent).trim().ifBlank { "未知" }
    }
}
