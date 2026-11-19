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

    // ------------------------------------------------------------------
    // §61.242（V0.3 M3）：增强 LRC 逐字时间轴（词级标签 <mm:ss.xx>）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("KARAOKE-1 · 词级标签解析：词时间轴 + 末词无界 + 显示文本剥标签")
    fun wordTimeline() {
        val lyrics = LrcParser.parse("[00:12.50]<00:12.50>你<00:13.20>好<00:14.00>吗")
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals("你好吗", line.text, "显示文本剥词级标签")
        assertEquals(3, line.words.size)
        assertEquals(LyricWord(12_500L, 13_200L, "你"), line.words[0])
        assertEquals(LyricWord(13_200L, 14_000L, "好"), line.words[1])
        assertEquals(LyricWord(14_000L, -1L, "吗"), line.words[2], "末词无界")
    }

    @Test
    @DisplayName("KARAOKE-2 · offset 叠加词时间；毫秒 3 位与 [.:] 容忍；空段跳过")
    fun wordOffsetAndTolerance() {
        val lyrics = LrcParser.parse(
            "[offset:-500]\n[01:00:50]<01:00.500>甲<01:01.000><01:01.200>乙",
        )
        val line = lyrics.lines[0]
        assertEquals(2, line.words.size, "连续标签空段跳过")
        assertEquals(60_000L, line.words[0].startMs, "60_500-500 offset 叠加")
        assertEquals(60_500L, line.words[0].endMs, "下一标签 61_000-500")
        assertEquals(60_700L, line.words[1].startMs, "第三标签 61_200-500（空段仅跳词不跳时间）")
        assertEquals(-1L, line.words[1].endMs, "乙为末段无界")
    }

    @Test
    @DisplayName("KARAOKE-3 · 无词级标签行 words 恒空（整行高亮回退零变化）")
    fun plainLineNoWords() {
        val lyrics = LrcParser.parse("[00:12.50]普通行")
        assertTrue(lyrics.lines[0].words.isEmpty())
        assertNull(lyrics.activeWord(13_000L), "无词级时间轴 activeWord 返回 null")
    }

    @Test
    @DisplayName("KARAOKE-4 · activeWord 边界：行前 null / 词内命中 / 末词粘滞 / 跨行")
    fun activeWordBoundaries() {
        val lyrics = LrcParser.parse(
            "[00:10.00]<00:10.00>一二<00:11.00>三四\n[00:20.00]第二行",
        )
        assertNull(lyrics.activeWord(5_000L), "active 行之前（activeIndex=-1）返回 null")
        assertEquals("一二", lyrics.activeWord(10_000L)?.text, "起点命中")
        assertEquals("一二", lyrics.activeWord(10_999L)?.text, "词区间内")
        assertEquals("三四", lyrics.activeWord(11_000L)?.text, "第二词起点切词")
        assertEquals("三四", lyrics.activeWord(15_000L)?.text, "末词粘滞到行尾")
        assertNull(lyrics.activeWord(20_000L), "第二行为普通行（无词轴）→ null")
    }
}
