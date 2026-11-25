package com.ccmsc.cloudmusic.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.221：CUE sheet 解析矩阵（纯 JVM，零依赖）。 */
class CueSheetTest {

    @Test
    fun `标准单 FILE 多轨 - 相邻 INDEX 差即分轨时长`() {
        val sheet = CueParser.parse(
            """
            REM COMMENT "exact audio copy"
            PERFORMER "窦唯"
            TITLE "黑梦"
            FILE "album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "明天"
                PERFORMER "窦唯"
                INDEX 00 00:00:00
                INDEX 01 00:33:00
              TRACK 02 AUDIO
                TITLE "孤单的人"
                INDEX 01 04:30:00
              TRACK 03 AUDIO
                TITLE "从命"
                INDEX 01 08:12:37
            """.trimIndent(),
        )
        assertEquals(3, sheet.tracks.size)
        assertEquals("黑梦", sheet.albumTitle)
        assertEquals("窦唯", sheet.albumPerformer)
        assertEquals("album.flac", sheet.tracks[0].file)
        assertEquals(33_000L, sheet.tracks[0].startMs)
        assertEquals("明天", sheet.tracks[0].title)
        assertEquals("窦唯", sheet.tracks[0].performer)
        assertEquals(270_000L, sheet.tracks[1].startMs)
        assertEquals(492_000L + 37L * 1000L / 75L, sheet.tracks[2].startMs)
        assertTrue(sheet.errors.isEmpty())
    }

    @Test
    fun `MM SS FF 换算 - 75 帧每秒向下取整`() {
        val sheet = CueParser.parse("FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 01:02:74")
        // (62s)*1000 + 74*1000/75 = 62000 + 986（floor(986.66)）
        assertEquals(62_986L, sheet.tracks[0].startMs)
    }

    @Test
    fun `CRLF 与行尾空白兼容`() {
        val sheet = CueParser.parse("FILE \"a.flac\" WAVE\r\nTRACK 01 AUDIO\r\nTITLE \"歌\" \r\nINDEX 01 01:00:00\r\n")
        assertEquals(1, sheet.tracks.size)
        assertEquals("歌", sheet.tracks[0].title)
        assertEquals(60_000L, sheet.tracks[0].startMs)
    }

    @Test
    fun `INDEX 00 pregap 不并入分轨起点`() {
        val sheet = CueParser.parse(
            "FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 00 00:00:00\nINDEX 01 02:00:00",
        )
        assertEquals(120_000L, sheet.tracks[0].startMs)
    }

    @Test
    fun `REM FLAGS ISRC PREGAP 等语句宽松跳过`() {
        val sheet = CueParser.parse(
            """
            REM GENRE "Rock"
            REM DATE 1994
            CATALOG 1234567890123
            FLAGS DCP
            ISRC ABC123
            PREGAP 00:01:00
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
            FLAGS 4CH
            ISRC XX-XXX
            INDEX 01 00:10:00
            """.trimIndent(),
        )
        assertEquals(1, sheet.tracks.size)
        assertEquals(10_000L, sheet.tracks[0].startMs)
        assertTrue(sheet.errors.isEmpty())
    }

    @Test
    fun `非 AUDIO 轨跳过并计错误`() {
        val sheet = CueParser.parse(
            """
            FILE "a.bin" BINARY
            TRACK 01 MODE1/2352
            INDEX 01 00:00:00
            FILE "a.flac" WAVE
            TRACK 02 AUDIO
            INDEX 01 01:00:00
            """.trimIndent(),
        )
        assertEquals(1, sheet.tracks.size)
        assertEquals("a.flac", sheet.tracks[0].file)
        assertTrue(sheet.errors.isNotEmpty(), "MODE1 轨应计入 errors")
    }

    @Test
    fun `缺 INDEX 01 的轨跳过并计错误`() {
        val sheet = CueParser.parse(
            "FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"无起点\"\nTRACK 02 AUDIO\nINDEX 01 00:03:00",
        )
        assertEquals(1, sheet.tracks.size)
        assertEquals(2, sheet.tracks[0].no)
        assertTrue(sheet.errors.isNotEmpty())
    }

    @Test
    fun `多 FILE 各轨归属与末轨无界`() {
        val sheet = CueParser.parse(
            """
            PERFORMER "艺人"
            TITLE "合辑"
            FILE "first.flac" WAVE
            TRACK 01 AUDIO
            TITLE "一"
            INDEX 01 00:00:00
            TRACK 02 AUDIO
            TITLE "二"
            INDEX 01 03:00:00
            FILE "second.flac" WAVE
            TRACK 03 AUDIO
            TITLE "三"
            INDEX 01 00:20:00
            """.trimIndent(),
        )
        assertEquals(3, sheet.tracks.size)
        assertEquals("first.flac", sheet.tracks[0].file)
        assertEquals("first.flac", sheet.tracks[1].file)
        assertEquals("second.flac", sheet.tracks[2].file)
        assertEquals(0L, sheet.tracks[0].startMs)
        assertEquals(180_000L, sheet.tracks[1].startMs)
        assertEquals(20_000L, sheet.tracks[2].startMs)
    }

    @Test
    fun `无引号 FILE 裸文件名与带空格引号名`() {
        val sheet = CueParser.parse("FILE myalbum.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertEquals("myalbum.flac", sheet.tracks[0].file)
        val quoted = CueParser.parse("FILE \"my album 01.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertEquals("my album 01.flac", quoted.tracks[0].file)
    }

    @Test
    fun `UTF-8 BOM 首行 FILE 仍可解析`() {
        val sheet = CueParser.parse("\uFEFFFILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertEquals(1, sheet.tracks.size)
        assertEquals("a.flac", sheet.tracks[0].file)
    }

    @Test
    fun `轨级 TITLE PERFORMER 不污染 sheet 级`() {
        val sheet = CueParser.parse(
            """
            TITLE "专辑"
            PERFORMER "专辑艺人"
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
            TITLE "轨一"
            PERFORMER "轨艺人"
            INDEX 01 00:00:00
            """.trimIndent(),
        )
        assertEquals("专辑", sheet.albumTitle)
        assertEquals("专辑艺人", sheet.albumPerformer)
        assertEquals("轨一", sheet.tracks[0].title)
        assertEquals("轨艺人", sheet.tracks[0].performer)
    }

    @Test
    fun `非法时间与序号宽松跳过不抛`() {
        val sheet = CueParser.parse(
            "GARBAGE LINE\nFILE \"a.flac\" WAVE\nTRACK XX AUDIO\nTRACK 02 AUDIO\nINDEX 01 99:99:99\nTRACK 03 AUDIO\nINDEX 01 05:00:00",
        )
        assertEquals(1, sheet.tracks.size)
        assertEquals(300_000L, sheet.tracks[0].startMs)
        assertTrue(sheet.errors.isNotEmpty())
    }

    @Test
    fun `空文本产空表`() {
        val sheet = CueParser.parse("")
        assertEquals(0, sheet.tracks.size)
        assertNull(sheet.albumTitle)
        assertTrue(!sheet.hasAudioTrack())
    }
}
