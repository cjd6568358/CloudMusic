package com.example.cloudmusic.media

import com.example.cloudmusic.engine.api.EngineState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.240：系统会话桥接——发布链快照/元数据 → 会话写口的纯 JVM 判定。 */
class SessionBridgeTest {

    private class RecordingPlayback {
        var calls = 0
        var playing = false
        var positionMs = -1L
        var durationMs = -1L
    }

    private class RecordingMetadata {
        var calls = 0
        var title = ""
        var artist = ""
        var album = ""
        var durationMs = -1L
        var coverPath: String? = null
    }

    private fun bridge(playback: RecordingPlayback, metadata: RecordingMetadata) = SessionBridge(
        playbackWriter = SessionBridge.SessionPlaybackWriter { playing, pos, dur ->
            playback.calls += 1
            playback.playing = playing
            playback.positionMs = pos
            playback.durationMs = dur
        },
        metadataWriter = SessionBridge.SessionMetadataWriter { title, artist, album, dur, cover ->
            metadata.calls += 1
            metadata.title = title
            metadata.artist = artist
            metadata.album = album
            metadata.durationMs = dur
            metadata.coverPath = cover
        },
    )

    @Test
    @DisplayName("§61.240 · PLAYING 快照 → 播放态写（位置/时长透传）")
    fun playingSnapshotWrites() {
        val pb = RecordingPlayback()
        val md = RecordingMetadata()
        bridge(pb, md).onPlayback(MediaPlaybackSnapshot(EngineState.PLAYING, 15_000L, 30_000L))
        assertEquals(1, pb.calls)
        assertTrue(pb.playing)
        assertEquals(15_000L, pb.positionMs)
        assertEquals(30_000L, pb.durationMs)
        assertEquals(0, md.calls, "元数据写口不被播放快照触发")
    }

    @Test
    @DisplayName("§61.240 · 非播放态（PAUSED/ENDED/IDLE/ERROR）一律回退态，不伪造播放")
    fun nonPlayingFallsBack() {
        val pb = RecordingPlayback()
        val md = RecordingMetadata()
        val b = bridge(pb, md)
        for (state in listOf(EngineState.PAUSED, EngineState.ENDED, EngineState.IDLE, EngineState.ERROR, EngineState.PREPARING)) {
            b.onPlayback(MediaPlaybackSnapshot(state, 1_000L, 10_000L))
            assertFalse(pb.playing, "$state 不应被写为播放中")
        }
        assertEquals(5, pb.calls)
    }

    @Test
    @DisplayName("§61.240 · null 快照不写；负位置钳 0（一手防御）")
    fun nullSkippedAndNegativeClamped() {
        val pb = RecordingPlayback()
        val md = RecordingMetadata()
        val b = bridge(pb, md)
        b.onPlayback(null)
        assertEquals(0, pb.calls, "null 快照不写（尚无发布）")
        b.onPlayback(MediaPlaybackSnapshot(EngineState.PLAYING, -5_000L, 0L))
        assertEquals(1, pb.calls)
        assertEquals(0L, pb.positionMs, "负位置钳 0")
        assertEquals(0L, pb.durationMs, "未知时长原样透传")
    }

    @Test
    @DisplayName("§61.240 · 元数据逐字段透传；null 不写（T07 兜底在上游）")
    fun metadataPassthrough() {
        val pb = RecordingPlayback()
        val md = RecordingMetadata()
        val b = bridge(pb, md)
        b.onMetadata(null)
        assertEquals(0, md.calls, "null 元数据不写")
        b.onMetadata(MediaMetadata(title = "分轨甲", artist = "某人", album = "专辑", coverPath = "/c.webp", durationMs = 30_000L))
        assertEquals(1, md.calls)
        assertEquals("分轨甲", md.title)
        assertEquals("某人", md.artist)
        assertEquals("专辑", md.album)
        assertEquals(30_000L, md.durationMs)
        assertEquals("/c.webp", md.coverPath)
        assertEquals(0, pb.calls, "播放写口不被元数据触发")
    }
}
