package com.ccmsc.cloudmusic.model

import java.util.Locale
import kotlin.math.abs

/**
 * V0.2 §61.220：智能歌单的**纯 JVM 规则引擎**。
 *
 * 口径（与 `docs/01 §3` V0.2「智能歌单」对齐）：
 * - 规则 = 若干 [Condition] 以 [Match] 方式组合，对全量曲库求值得到动态曲目集；
 * - **不新增 Room 表 / 不落库成员**：结果是实时计算的视图，曲库变化后下次打开即为最新；
 * - 只用 [Track] 已有字段（标题 / 艺人 / 专辑 / 年份 / 时长 / 采样率 / 位深 / 格式 /
 *   来源 / 标签完整度 / 可用性），不做网络、不读 URL（签名直链不入 UI / 日志）；
 * - 排序：[SortBy] + 上限 [limit]，确定性输出（同 key 用 id 兜底保证顺序稳定）。
 *
 * 纯 Kotlin + JDK、零 Android / 零第三方依赖，任何模块可直接引用。
 */
object SmartPlaylist {

    /** 可参与规则的字段；全部来自 [Track]，不引入收藏 / 播放历史（那需要额外数据源）。 */
    enum class Field {
        TITLE, ARTIST, ALBUM, YEAR, DURATION_MS, SAMPLE_RATE, BIT_DEPTH, FORMAT, SOURCE, TAG_COMPLETENESS
    }

    enum class Op { CONTAINS, EQUALS, NOT_EQUALS, GT, GTE, LT, LTE }

    enum class Match { ALL, ANY }

    enum class SortBy { TITLE, ARTIST, YEAR_DESC, DURATION_DESC, ADDED_DEFAULT }

    data class Condition(
        val field: Field,
        val op: Op,
        /** 数值字段填十进制字符串；FORMAT / SOURCE 填扩展名 / SourceType 名。 */
        val value: String,
    )

    data class Rule(
        val name: String,
        val conditions: List<Condition>,
        val match: Match = Match.ALL,
        val sortBy: SortBy = SortBy.ADDED_DEFAULT,
        /** ≤0 表示不限；>0 时按排序截断。 */
        val limit: Int = 0,
    )

    /** 内置预设：命名与 UI 展示一一对应，规则只依赖曲库字段，可离线求值。 */
    val PRESETS: List<Rule> = listOf(
        Rule("高解析 · ≥96kHz", listOf(Condition(Field.SAMPLE_RATE, Op.GTE, "96000")), sortBy = SortBy.TITLE),
        Rule("无损 · FLAC/APE/WAV", listOf(
            Condition(Field.FORMAT, Op.EQUALS, "flac"),
            Condition(Field.FORMAT, Op.EQUALS, "ape"),
            Condition(Field.FORMAT, Op.EQUALS, "wav"),
        ), match = Match.ANY, sortBy = SortBy.TITLE),
        Rule("长音频 · ≥10 分钟", listOf(Condition(Field.DURATION_MS, Op.GTE, "600000")), sortBy = SortBy.DURATION_DESC),
        Rule("标签不全 · <0.5", listOf(Condition(Field.TAG_COMPLETENESS, Op.LT, "0.5")), sortBy = SortBy.TITLE),
        Rule("最近年份 · ≥2020", listOf(Condition(Field.YEAR, Op.GTE, "2020")), sortBy = SortBy.YEAR_DESC),
        Rule("本地文件", listOf(Condition(Field.SOURCE, Op.EQUALS, "LOCAL")), sortBy = SortBy.TITLE, limit = 200),
    )

    /**
     * 求值：[tracks] 中满足规则的子集，按 [Rule.sortBy] 排序并按 [Rule.limit] 截断。
     * 空条件列表 = 全选（`ALL` 的空集为真），调用方不应构造无意义的空规则。
     */
    fun evaluate(tracks: List<Track>, rule: Rule): List<Track> {
        val matched = tracks.filter { t ->
            when (rule.match) {
                Match.ALL -> rule.conditions.all { test(t, it) }
                Match.ANY -> rule.conditions.any { test(t, it) }
            }
        }
        val byTitle = Comparator<Track> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
        val byArtist = Comparator<Track> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.artist, b.artist) }
        val sorted = when (rule.sortBy) {
            SortBy.TITLE -> matched.sortedWith(byTitle.thenBy { it.id.value })
            SortBy.ARTIST -> matched.sortedWith(byArtist.thenBy { it.id.value })
            SortBy.YEAR_DESC -> matched.sortedWith(compareByDescending<Track> { it.year }.thenBy { it.title })
            SortBy.DURATION_DESC -> matched.sortedWith(compareByDescending<Track> { it.durationMs }.thenBy { it.title })
            SortBy.ADDED_DEFAULT -> matched
        }
        return if (rule.limit > 0 && sorted.size > rule.limit) sorted.take(rule.limit) else sorted
    }

    /** 单条件判定。数值解析失败一律不命中（不猜测、不放行）。 */
    private fun test(track: Track, c: Condition): Boolean {
        val needle = c.value.trim()
        return when (c.field) {
            Field.TITLE -> text(track.title, c.op, needle)
            Field.ARTIST -> text(track.artist, c.op, needle)
            Field.ALBUM -> text(track.album, c.op, needle)
            Field.FORMAT -> text(AudioFormats.extOf(track.sourceRef.path), c.op, needle.lowercase(Locale.ROOT))
            Field.SOURCE -> text(track.sourceType.name, c.op, needle.uppercase(Locale.ROOT))
            Field.YEAR -> number(track.year.toLong(), c.op, needle)
            Field.DURATION_MS -> number(track.durationMs, c.op, needle)
            Field.SAMPLE_RATE -> number(track.sampleRate.toLong(), c.op, needle)
            Field.BIT_DEPTH -> number(track.bitDepth.toLong(), c.op, needle)
            Field.TAG_COMPLETENESS -> number(abs(track.tagCompleteness * 1000).toLong(), c.op,
                needle.toDoubleOrNull()?.let { (it * 1000).toLong().toString() } ?: needle)
        }
    }

    private fun text(actual: String, op: Op, needle: String): Boolean = when (op) {
        Op.CONTAINS -> actual.contains(needle, ignoreCase = true)
        Op.EQUALS -> actual.equals(needle, ignoreCase = true)
        Op.NOT_EQUALS -> !actual.equals(needle, ignoreCase = true)
        else -> false   // 文本字段用数值操作符 → 不命中（规则配置错误不制造误匹配）
    }

    private fun number(actual: Long, op: Op, needle: String): Boolean {
        val n = needle.toLongOrNull() ?: return false
        return when (op) {
            Op.GT -> actual > n
            Op.GTE -> actual >= n
            Op.LT -> actual < n
            Op.LTE -> actual <= n
            Op.EQUALS -> actual == n
            Op.NOT_EQUALS -> actual != n
            Op.CONTAINS -> false
        }
    }
}
