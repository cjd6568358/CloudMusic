package com.example.cloudmusic.di.scan

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V0.2 §61.217：外置 lrc 候选顺序的纯 JVM 契约（零 IO、零网络）。 */
class LyricsPathCandidatesTest {

    @Test
    @DisplayName("§61.217 C1：同名优先，其次 lyrics/ 子目录，再大写扩展名")
    fun order() {
        val list = LyricsPathCandidates.forTrack("晴天.flac", null, null)
        assertEquals(
            listOf("晴天.lrc", "lyrics/晴天.lrc", "晴天.LRC", "lyrics/晴天.LRC"),
            list,
        )
    }

    @Test
    @DisplayName("§61.217 C2：标题 / 艺人名候选排在同名之后且去重")
    fun titleAndArtistCandidates() {
        val list = LyricsPathCandidates.forTrack("track01.flac", "晴天", "周杰伦")
        assertEquals("track01.lrc", list.first())
        assertTrue("晴天.lrc" in list)
        assertTrue("周杰伦 - 晴天.lrc" in list)
        assertEquals(list.size, list.toSet().size)
    }

    @Test
    @DisplayName("§61.217 C3：标题与文件名相同时不重复；候选上限 8")
    fun dedupeAndCap() {
        val list = LyricsPathCandidates.forTrack("晴天.flac", "晴天", "周杰伦")
        assertTrue(list.size <= LyricsPathCandidates.MAX_CANDIDATES)
        assertEquals(1, list.count { it == "晴天.lrc" })
    }

    @Test
    @DisplayName("§61.217 C4：标签里的路径分隔符 / Windows 非法字符被清洗，不越界")
    fun sanitize() {
        val list = LyricsPathCandidates.forTrack("a.mp3", "../evil:title", "artist|name")
        assertTrue(list.none { it.contains("..") || it.contains(":") || it.contains("|") })
    }
}
