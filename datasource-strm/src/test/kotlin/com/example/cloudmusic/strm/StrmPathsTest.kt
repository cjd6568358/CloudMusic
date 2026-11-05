package com.example.cloudmusic.strm

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/03-03 §7 T05~T07 与 §2.3 后缀归一化。 */
class StrmPathsTest {

    @Test
    @DisplayName("T05 同一 key 多源合并 → 单 key autoFlatten=true")
    fun t05() {
        val paths = parsePaths("music:/strm\nmusic:/music/strm")
        assertEquals(listOf("music"), paths.rootKeys)
        assertEquals(listOf("/strm", "/music/strm"), paths.groups["music"])
        assertTrue(paths.autoFlatten)
    }

    @Test
    @DisplayName("T06 两个 key → 根目录列出两个 key，不 autoFlatten")
    fun t06() {
        val paths = parsePaths("nas:/mnt/nas/strm\nlocal:/sdcard/strm")
        assertEquals(listOf("local", "nas"), paths.rootKeys)
        assertFalse(paths.autoFlatten)
    }

    @Test
    @DisplayName("T07 后缀清单含空格 / 大写 / 前导点")
    fun t07() {
        assertEquals(
            setOf("mp3", "flac", "m4a"),
            normalizeExtList(" MP3, .FLAC ; m4a\n"),
        )
        assertTrue("strm" in normalizeExtList(defaultDownloadTypes))
        assertTrue("ts" !in normalizeExtList(defaultFilterTypes))
    }

    @Test
    @DisplayName("裸 dst 的 key 取 base 名；Windows 盘符不被误判为 key:dst")
    fun barePathAndDriveLetter() {
        assertEquals(listOf("strm"), parsePaths("/music/strm/").rootKeys)
        val win = parsePaths("C:/Music/strm")
        assertEquals(1, win.groups.size)
        assertTrue(win.allDestinations.single().startsWith("C:/"))
    }

    @Test
    @DisplayName("空 paths 不产生虚拟目录")
    fun emptyPaths() {
        assertTrue(parsePaths("").isEmpty)
        assertFalse(parsePaths("").autoFlatten)
    }
}
