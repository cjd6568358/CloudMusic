package com.example.cloudmusic.model

import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * 旧文本乱码修复（§61.170 立项，§61.173 扩展）。
 *
 * 覆盖**两类**真实脏数据，都是真机 DB 一手取证得来：
 *
 * ① **百分号编码**：STRM 侧文件名是 `S.H.E%20-%20%E8%8C%B1%E7%BD%97%E8%AE%B0.wma`，
 *    直接展示就是天书。按 URL 规则解一次（`%XX` → UTF-8 字节），**只在解出 CJK 汉字时**采用，
 *    避免 `100%20Off` 之类正常文本被误解。
 * ② **GBK 被当 ISO-8859-1**：ID3v2 的 `enc=0x00`、ID3v1 定长单字节字段、部分 WebDAV
 *    displayname 都可能是 GBK 字节按规范解成 Latin-1 的形态（`´óÔ¼ÔÚ¶¬¼¾` = 大约在冬季）。
 *    只在「全部字符 ≤U+00FF 且含高位」时按 GB18030 重解，要求解出 CJK 且无替换符才采用。
 *
 * `fix` 的契约：**修不了就原样返回**（仅入参为 null 时返回 null）。因此调用方若要判断
 * 「是否有变化」，必须比较前后是否相等（见 `LegacyTextRepairRunner.plan`）。
 *
 * 纯 JVM、无 Android 依赖（`core-model` 契约层）。
 */
object LegacyTextRepair {

    private val GB18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

    /** 修复单个文本；`null` / 空串 / 正常文本原样返回。 */
    fun fix(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        // 顺序不可颠倒：先解百分号（得到真实字节），再修 GBK（真实字节才可能被判为高位单字节）。
        val percentDecoded = decodePercent(text) ?: text
        return repairGbk(percentDecoded)
    }

    /**
     * `%XX` 百分号解码（UTF-8）。[requireCjk] = 默认 `true`（通用文本通道，保守）；
     * 文件名通道可传 `false`（`NameParser`：文件名解出来就是给人看的，纯英文也该解）。
     *
     * 返回 null 表示「没解 / 解不动 / 不满足判据」，调用方保留原文。
     */
    fun decodePercent(text: String, requireCjk: Boolean = true): String? {
        if (!PERCENT_ESCAPE.containsMatchIn(text)) return null
        val decoded = runCatching {
            // URLDecoder 把 '+' 当空格，文件名里的字面 '+' 需先转义（同 PropfindParser 口径）
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return null
        if (decoded == text) return null
        if (decoded.indexOf('\uFFFD') >= 0) return null
        if (requireCjk && decoded.none(::isCjkIdeograph)) return null
        return decoded
    }

    /** 只在「疑似高位单字节乱码」时按 GB18030 重解，否则返回原值。 */
    private fun repairGbk(text: String): String {
        if (!looksLikeHighSingleByte(text)) return text
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return decodeCjk(bytes) ?: text
    }

    /**
     * 判据：全部字符落在 U+0000~U+00FF（即「每个字符都能还原成一个字节」）且至少有一个高位字符。
     * 只要出现一个 U+0100 以上的字符，就说明它已经是真正的 Unicode 文本，不再尝试修复。
     */
    private fun looksLikeHighSingleByte(text: String): Boolean {
        var high = false
        for (c in text) {
            if (c.code > 0xFF) return false
            if (c.code >= 0x80) high = true
        }
        return high
    }

    private fun decodeCjk(bytes: ByteArray): String? {
        val charset = GB18030 ?: return null
        val decoded = runCatching { String(bytes, charset) }.getOrNull() ?: return null
        if (decoded.indexOf('\uFFFD') >= 0) return null
        if (decoded.none(::isCjkIdeograph)) return null
        return decoded
    }

    /** 只认 CJK 统一汉字区间；不含标点/全角符号，避免随机字节对误判。 */
    private fun isCjkIdeograph(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF
}
