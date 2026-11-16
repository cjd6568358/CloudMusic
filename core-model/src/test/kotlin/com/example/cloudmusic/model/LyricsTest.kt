package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/01 §0（2026-11-16 拍板）：歌词本地闭环的纯 JVM 契约测试。 */
class LyricsTest {

    @Test
    @DisplayName("LRC-1 · 基础解析：mm:ss.xx / 毫秒归一 / 升序")
    fun parseBasic() {
        val lrc = """
            [ti:测试曲]
            [ar:某艺人]
            [00:01.00]第一行
            [00:02.50]第二行
            [01:03.456]第三行
        """.trimIndent()
        val lyrics = LrcParser.parse(lrc)
        assertEquals(3, lyrics.lines.size)
        assertEquals(LyricLine(1_000L, "第一行"), lyrics.lines[0])
        assertEquals(LyricLine(2_500L, "第二行"), lyrics.lines[1])
        assertEquals(LyricLine(63_456L, "第三行"), lyrics.lines[2])
        assertTrue(lyrics.lines.none { "测试曲" in it.text || "某艺人" in it.text }, "ti/ar 元数据行被忽略")
    }

    @Test
    @DisplayName("LRC-2 · 毫秒 1~2 位归一到 3 位")
    fun parseFractionDigits() {
        val lyrics = LrcParser.parse("[00:01.5]A\n[00:02.05]B")
        assertEquals(1_500L, lyrics.lines[0].timeMs)
        assertEquals(2_050L, lyrics.lines[1].timeMs)
    }

    @Test
    @DisplayName("LRC-3 · 冒号分隔毫秒 [mm:ss:xx] 亦支持")
    fun parseColonFraction() {
        val lyrics = LrcParser.parse("[00:03:20]C")
        assertEquals(3_200L, lyrics.lines[0].timeMs)
    }

    @Test
    @DisplayName("LRC-4 · 一行多时间戳拆成多行，文本相同")
    fun parseMultiStamp() {
        val lyrics = LrcParser.parse("[00:12.00][00:24.00]重复句")
        assertEquals(2, lyrics.lines.size)
        assertEquals(12_000L, lyrics.lines[0].timeMs)
        assertEquals(24_000L, lyrics.lines[1].timeMs)
        assertEquals("重复句", lyrics.lines[1].text)
    }

    @Test
    @DisplayName("LRC-5 · offset 叠加到每行且整体不小于 0")
    fun parseOffset() {
        val lyrics = LrcParser.parse("[offset:500]\n[00:01.00]A\n[00:02.00]B")
        assertEquals(500L, lyrics.offsetMs)
        assertEquals(1_500L, lyrics.lines[0].timeMs)
        assertEquals(2_500L, lyrics.lines[1].timeMs)

        val negative = LrcParser.parse("[offset:-200]\n[00:01.00]A")
        assertEquals(-200L, negative.offsetMs)
        assertEquals(800L, negative.lines[0].timeMs)
    }

    @Test
    @DisplayName("LRC-6 · activeIndex 二分：首行前 -1、命中行、末行后保持末行")
    fun activeIndex() {
        val lyrics = LrcParser.parse("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(-1, lyrics.activeIndex(0L))
        assertEquals(-1, lyrics.activeIndex(999L))
        assertEquals(0, lyrics.activeIndex(1_000L))
        assertEquals(0, lyrics.activeIndex(1_999L))
        assertEquals(1, lyrics.activeIndex(2_000L))
        assertEquals(2, lyrics.activeIndex(9_999L))
        assertEquals("B", lyrics.activeText(2_500L))
    }

    @Test
    @DisplayName("LRC-7 · 空歌词 / 无有效行 → 降级 EMPTY，activeText 为 null")
    fun parseEmpty() {
        val a = LrcParser.parse("")
        assertTrue(a.isEmpty)
        assertEquals(-1, a.activeIndex(1_000L))
        assertNull(a.activeText(1_000L))

        val b = LrcParser.parse("[ti:只有元数据]\n[ar:无时间轴]")
        assertTrue(b.isEmpty)
    }

    @Test
    @DisplayName("LRC-8 · source 溯源标识透传（local / online provider）")
    fun sourceLabel() {
        assertEquals("local:lrc", LrcParser.parse("[00:01.00]A").source)
        assertEquals("local:embedded", LrcParser.parse("[00:01.00]A", "local:embedded").source)
        assertEquals("online:netease", LrcParser.parse("[00:01.00]A", "online:netease").source)
    }
}
