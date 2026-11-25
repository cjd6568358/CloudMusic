package com.ccmsc.cloudmusic.model

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
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** §61.242：词级时间轴（增强 LRC；无词级标签恒空，UI 回退整行高亮）。 */
    val words: List<LyricWord> = emptyList(),
)

/**
 * §61.242（V0.3 M3）：逐字歌词时间轴单元（增强 LRC `<mm:ss.xx>` 词级标签）。
 * [endMs] = 下一个词的起点；**行内最后一个词 endMs = -1（无界）**——语义为「唱到行尾」，
 * 仅在行本身为 active 行时被查询（粘滞到最后，不伪造提前结束）。
 * 无词级标签的行 [LyricLine.words] 恒空 → UI 回退整行高亮（零行为变化）。
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

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

    /**
     * §61.242：[positionMs] 处应高亮的词（仅 active 行内查询；行无词级时间轴返回 null）。
     * 词激活区间 = startMs <= pos < endMs；行尾无界词（endMs = -1）粘滞命中。
     */
    fun activeWord(positionMs: Long): LyricWord? {
        val line = lines.getOrNull(activeIndex(positionMs)) ?: return null
        if (line.words.isEmpty()) return null
        return line.words.firstOrNull { w ->
            w.startMs <= positionMs && (w.endMs < 0 || positionMs < w.endMs)
        }
    }

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

    /**
     * §61.242：增强 LRC 词级标签 `<mm:ss.xx>`（与行级同制式：毫秒 1~3 位归一、[.:] 容忍）。
     * 出现即视为逐字歌词行；标签间文本为词，空段跳过；无标签行 words 恒空（回退整行高亮）。
     */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

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
            // §61.242：词级时间轴解析（有标签才产出 words；空段跳过；末词无界 -1）。
            // 行首无标签但行中夹标签的混合形态按首标签前段处理（无时间归属 → 丢弃该前段）。
            val words = parseWords(textPart, offsetMs)
            val displayText = if (words.isEmpty()) textPart else words.joinToString("") { it.text }
            for (t in stamps) {
                val adj = (t + offsetMs).coerceAtLeast(0L)
                rows += LyricLine(adj, displayText, words.map { w ->
                    // 词时间同叠 offset；起点 0 钳制与行级一致。
                    w.copy(startMs = (w.startMs + offsetMs).coerceAtLeast(0L),
                           endMs = if (w.endMs < 0) -1L else (w.endMs + offsetMs).coerceAtLeast(0L))
                })
            }
        }
        if (rows.isEmpty()) return Lyrics.EMPTY.copy(source = source)
        val sorted = rows.sortedBy { it.timeMs }
        return Lyrics(sorted, offsetMs, source)
    }

    /**
     * §61.242：词级段解析——`<t1>词<t2>词…`；最后一段 endMs = -1（无界）。
     * 无词标签 → 空表（行级回退）。offset 由调用方统一叠加（此处产原始时间）。
     */
    private fun parseWords(textPart: String, offsetMs: Long): List<LyricWord> {
        val tags = WORD_TAG.findAll(textPart).toList()
        if (tags.isEmpty()) return emptyList()
        // 首标签前的无时间归属前段丢弃（增强 LRC 约定词全部挂在标签后）。
        val out = mutableListOf<LyricWord>()
        for (i in tags.indices) {
            val start = tags[i].range.last + 1
            val end = if (i + 1 < tags.size) tags[i + 1].range.first else textPart.length
            val word = textPart.substring(start, end)
            if (word.isBlank()) continue
            val startMs = tags[i].toMillis()
            val endMs = if (i + 1 < tags.size) tags[i + 1].toMillis() else -1L
            out += LyricWord(startMs, endMs, word)
        }
        return out
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
