package com.example.cloudmusic.storage

import com.example.cloudmusic.model.LyricLine
import com.example.cloudmusic.model.Lyrics
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/01 §0（2026-11-16 拍板）：歌词仓库纯 JVM 契约测试。 */
class LyricRepositoryTest {

    @Test
    @DisplayName("LYRIC-R1 · 无歌词时 observe 发 EMPTY、has=false")
    fun emptyBeforeSave() = runTest {
        val repo = InMemoryLyricRepository()
        val id = TrackId.of("local", "/music/a.mp3")
        assertTrue(repo.observe(id).first().isEmpty)
        assertFalse(repo.has(id))
    }

    @Test
    @DisplayName("LYRIC-R2 · save 后 observe 推送歌词、has=true")
    fun saveAndObserve() = runTest {
        val repo = InMemoryLyricRepository()
        val id = TrackId.of("local", "/music/a.mp3")
        val lyrics = Lyrics(listOf(LyricLine(1_000L, "词A"), LyricLine(2_000L, "词B")), source = "local:lrc")
        repo.save(id, lyrics)
        assertEquals(lyrics, repo.observe(id).first())
        assertTrue(repo.has(id))
    }

    @Test
    @DisplayName("LYRIC-R3 · 不同 trackId 隔离；空歌词视为无歌词")
    fun isolation() = runTest {
        val repo = InMemoryLyricRepository()
        val a = TrackId.of("local", "/music/a.mp3")
        val b = TrackId.of("local", "/music/b.mp3")
        repo.save(a, Lyrics(listOf(LyricLine(0L, "A词"))))
        assertTrue(repo.has(a))
        assertFalse(repo.has(b))
        assertTrue(repo.observe(b).first().isEmpty)
    }
}
