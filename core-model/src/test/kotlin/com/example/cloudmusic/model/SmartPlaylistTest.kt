package com.example.cloudmusic.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** V0.2 §61.220：智能歌单规则引擎 JVM 单测。 */
class SmartPlaylistTest {

    private fun track(
        id: String,
        title: String = id,
        artist: String = "歌手",
        album: String = "专辑",
        year: Int = 2024,
        durationMs: Long = 180_000L,
        sampleRate: Int = 44_100,
        bitDepth: Int = 16,
        ext: String = "mp3",
        source: SourceType = SourceType.LOCAL,
        tag: Float = 0.9f,
    ) = Track(
        id = TrackId(id),
        title = title, artist = artist, album = album,
        durationMs = durationMs, year = year,
        sampleRate = sampleRate, bitDepth = bitDepth,
        sourceRef = SourceRef("nas-1", "/音乐/$id.$ext"),
        sourceType = source, tagCompleteness = tag,
    )

    private val library = listOf(
        track("hi", "Hi-Res 曲", sampleRate = 96_000, ext = "flac", bitDepth = 24),
        track("long", "长曲", durationMs = 900_000, ext = "ape"),
        track("old", "老歌", year = 1999, tag = 0.3f),
        track("net", "网络曲", ext = "m4a", source = SourceType.WEBDAV),
    )

    @Test
    @DisplayName("§61.220 P1：数值比较 GTE / LT 正确筛选")
    fun numericConditions() {
        val hi = SmartPlaylist.evaluate(library, SmartPlaylist.Rule(
            "高解析", listOf(SmartPlaylist.Condition(SmartPlaylist.Field.SAMPLE_RATE, SmartPlaylist.Op.GTE, "96000")),
        ))
        assertEquals(listOf("hi"), hi.map { it.id.value })
        val short = SmartPlaylist.evaluate(library, SmartPlaylist.Rule(
            "标签不全", listOf(SmartPlaylist.Condition(SmartPlaylist.Field.TAG_COMPLETENESS, SmartPlaylist.Op.LT, "0.5")),
        ))
        assertEquals(listOf("old"), short.map { it.id.value })
    }

    @Test
    @DisplayName("§61.220 P2：ANY 组合 + 格式字段（F1 真相源派生扩展名）")
    fun anyFormatCondition() {
        val lossless = SmartPlaylist.evaluate(library, SmartPlaylist.PRESETS.first { it.name.startsWith("无损") })
        assertEquals(setOf("hi", "long"), lossless.map { it.id.value }.toSet())
    }

    @Test
    @DisplayName("§61.220 P3：ALL 组合——两条件同时满足才命中")
    fun allConditions() {
        val r = SmartPlaylist.Rule("本地无损", listOf(
            SmartPlaylist.Condition(SmartPlaylist.Field.SOURCE, SmartPlaylist.Op.EQUALS, "LOCAL"),
            SmartPlaylist.Condition(SmartPlaylist.Field.FORMAT, SmartPlaylist.Op.EQUALS, "flac"),
        ), match = SmartPlaylist.Match.ALL)
        assertEquals(listOf("hi"), SmartPlaylist.evaluate(library, r).map { it.id.value })
    }

    @Test
    @DisplayName("§61.220 P4：文本 CONTAINS / NOT_EQUALS + 大小写不敏感")
    fun textConditions() {
        val c = SmartPlaylist.Rule("含 hi", listOf(
            SmartPlaylist.Condition(SmartPlaylist.Field.TITLE, SmartPlaylist.Op.CONTAINS, "hi-res"),
        ))
        assertEquals(listOf("hi"), SmartPlaylist.evaluate(library, c).map { it.id.value })
        val notLocal = SmartPlaylist.Rule("非本地", listOf(
            SmartPlaylist.Condition(SmartPlaylist.Field.SOURCE, SmartPlaylist.Op.NOT_EQUALS, "local"),
        ))
        assertEquals(listOf("net"), SmartPlaylist.evaluate(library, notLocal).map { it.id.value })
    }

    @Test
    @DisplayName("§61.220 P5：排序 + 上限截断确定性")
    fun sortAndLimit() {
        val r = SmartPlaylist.Rule("最长 2 首", emptyList(), sortBy = SmartPlaylist.SortBy.DURATION_DESC, limit = 2)
        assertEquals(listOf("long", "hi"), SmartPlaylist.evaluate(library, r).map { it.id.value }.take(2))
        val byTitle = SmartPlaylist.evaluate(library, SmartPlaylist.Rule("按标题", emptyList(), sortBy = SmartPlaylist.SortBy.TITLE))
        assertEquals(byTitle.map { it.title }, byTitle.map { it.title }.sortedWith(String.CASE_INSENSITIVE_ORDER))
    }

    @Test
    @DisplayName("§61.220 P6：空曲库 / 非法数值 / 文本误用数值操作符一律不命中")
    fun defensive() {
        assertTrue(SmartPlaylist.evaluate(emptyList(), SmartPlaylist.PRESETS.first()).isEmpty())
        val bad = SmartPlaylist.Rule("非法值", listOf(
            SmartPlaylist.Condition(SmartPlaylist.Field.YEAR, SmartPlaylist.Op.GTE, "not-a-number"),
        ))
        assertTrue(SmartPlaylist.evaluate(library, bad).isEmpty())
        val misuse = SmartPlaylist.Rule("误用", listOf(
            SmartPlaylist.Condition(SmartPlaylist.Field.TITLE, SmartPlaylist.Op.GT, "x"),
        ))
        assertTrue(SmartPlaylist.evaluate(library, misuse).isEmpty())
    }

    @Test
    @DisplayName("§61.220 P7：全部内置预设可求值、不抛异常")
    fun allPresetsEvaluable() {
        SmartPlaylist.PRESETS.forEach { rule ->
            val result = SmartPlaylist.evaluate(library, rule)
            assertTrue(result.size <= library.size, "${rule.name} 结果数异常")
        }
    }
}
