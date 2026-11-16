package com.example.cloudmusic.model

/**
 * docs/01 §0（2026-11-16 用户拍板）：歌词本地优先（lrc / 内嵌），本地缺失允许在线抓取。
 * 本文件是纯 JVM 歌词模型与 LRC 解析器，**不引入任何第三方 / Android 依赖**（03-01 §14 #1）。
 *
 * 口径：
 * - [LyricLine.timeMs] 为该行显示的绝对时间戳（已计入文件级 [Lyrics.offsetMs]）；
 * - [Lyrics.activeIndex] 返回给定播放位置应高亮的行号，-1 表示尚未到第一行 / 无歌词；
 * - 解析容忍空行、ID3 标签残留、重复时间戳、毫秒 1~3 位，异常格式整行忽略而非中断。
 */

/** 单行歌词：时间戳（毫秒）+ 文本。 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 一首歌的逐行歌词。
 * @param lines 按 timeMs 升序排列的行（解析时保证）
 * @param offsetMs 文件级偏移；正值整体后移显示，负值提前。解析时已叠加到每行 timeMs，
 *                 此字段仅作溯源保留（反映原始 [offset:] 值）。
 * @param source 来源标识（"local:lrc" / "local:embedded" / "online:<provider>"），便于 UI 与审计。
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
    val source: String = "local:lrc",
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 返回 [positionMs] 应高亮的行下标：最后一条 timeMs <= positionMs 的行。
     * -1 = 播放位置在第一行之前或歌词为空。二分查找，O(log n)。
     */
    fun activeIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** [positionMs] 处的文本；无有效行返回 null。 */
    fun activeText(positionMs: Long): String? = lines.getOrNull(activeIndex(positionMs))?.text

    companion object {
        val EMPTY = Lyrics(emptyList())
    }
}

/**
 * LRC 文本解析器（纯函数）。
 *
 * 支持：
 * - `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]` 时间戳（毫秒 1~3 位自动归一到 3 位）；
 * - 一行多时间戳（如 `[00:12.00][00:24.00]歌词`）——拆成多行；
 * - `[offset:+500]` / `[offset:-300]` 文件级偏移（毫秒），叠加到每行；
 * - `[ar:...]` / `[ti:...]` / `[al:...]` 等元数据行忽略（非时间戳方括号开头）；
 * - 空文本行忽略（保留进度语义可选用空串，此处选择忽略以适配"暂无歌词"降级）。
 */
object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** 解析 LRC 文本；无任何有效行时返回 [Lyrics.EMPTY]。 */
    fun parse(text: String, source: String = "local:lrc"): Lyrics {
        var offsetMs = 0L
        OFFSET_TAG.find(text)?.let { runCatching { offsetMs = it.groupValues[1].toLong() } }

        val rows = mutableListOf<LyricLine>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().trim('\uFEFF')
            if (line.isEmpty()) continue
            // 纯元数据行（[ar:]/[ti:]/[by:] 等）不含时间戳，跳过
            if (!TIME_TAG.containsMatchIn(line)) continue

            val textPart = TIME_TAG.replace(line, "").trim()
            val stamps = TIME_TAG.findAll(line).map { it.toMillis() }.toList()
            if (textPart.isEmpty()) continue
            for (t in stamps) {
                val adj = (t + offsetMs).coerceAtLeast(0L)
                rows += LyricLine(adj, textPart)
            }
        }
        if (rows.isEmpty()) return Lyrics.EMPTY.copy(source = source)
        val sorted = rows.sortedBy { it.timeMs }
        return Lyrics(sorted, offsetMs, source)
    }

    private fun MatchResult.toMillis(): Long {
        val min = groupValues[1].toLong()
        val sec = groupValues[2].toLong()
        val fracRaw = groupValues[3]
        val frac = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 100L
            2 -> fracRaw.toLong() * 10L
            else -> fracRaw.take(3).toLong()
        }
        return min * 60_000L + sec * 1000L + frac
    }
}
