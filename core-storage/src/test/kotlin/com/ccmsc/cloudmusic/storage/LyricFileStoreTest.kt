package com.ccmsc.cloudmusic.storage

import com.ccmsc.cloudmusic.model.LyricLine
import com.ccmsc.cloudmusic.model.Lyrics
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.model.LrcParser
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/01 §0（2026-11-16 拍板）：歌词文件缓存纯 JVM 测试。 */
class LyricFileStoreTest {

    @TempDir
    lateinit var tmp: File

    @Test
    @DisplayName("LYRIC-F1 · save/read/exists/delete 往返")
    fun roundTrip() {
        val store = LyricFileStore(tmp)
        val id = TrackId.of("local", "/music/a.mp3")
        val raw = "[00:01.00]词A\n[00:02.50]词B"
        assertFalse(store.exists(id))
        assertNull(store.readRaw(id))

        assertTrue(store.saveRaw(id, raw))
        assertTrue(store.exists(id))
        assertEquals(raw, store.readRaw(id))

        assertTrue(store.delete(id))
        assertFalse(store.exists(id))
    }

    @Test
    @DisplayName("LYRIC-F2 · 不同 trackId 隔离")
    fun isolation() {
        val store = LyricFileStore(tmp)
        val a = TrackId.of("local", "/a.mp3")
        val b = TrackId.of("local", "/b.mp3")
        store.saveRaw(a, "[00:01.00]A")
        assertTrue(store.exists(a))
        assertFalse(store.exists(b))
    }

    @Test
    @DisplayName("LYRIC-F3 · serialize→parse 往返保留时间与文本（offset 归零边界）")
    fun serializeParseRoundTrip() {
        val original = LrcParser.parse("[00:01.00]词A\n[00:02.50]词B")
        val serialized = original.serializeToLrc()
        val reparsed = LrcParser.parse(serialized)
        assertEquals(original.lines.map { it.timeMs }, reparsed.lines.map { it.timeMs })
        assertEquals(original.lines.map { it.text }, reparsed.lines.map { it.text })
    }

    @Test
    @DisplayName("LYRIC-F4 · offset 已叠加的时间序列化往返不二次叠加")
    fun offsetRoundTrip() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(1_500L, "词")),  // 已含 offset 的最终时间
            offsetMs = 500L,                            // 仅溯源
        )
        val serialized = lyrics.serializeToLrc()
        // 行时间已是最终值，不应再写 [offset:] 行，否则解析时二次叠加
        assertFalse(serialized.contains("[offset:"))
        val reparsed = LrcParser.parse(serialized)
        assertEquals(1_500L, reparsed.lines[0].timeMs)
    }
}
