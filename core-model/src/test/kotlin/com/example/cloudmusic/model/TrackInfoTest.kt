package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** V0.2 §61.218：歌曲信息展示口径的纯 JVM 契约（零 UI、零网络）。 */
class TrackInfoTest {

    private fun track(
        path: String = "/music/a.flac",
        rate: Int = 96_000,
        bits: Int = 24,
        type: SourceType = SourceType.LOCAL,
        duration: Long = 244_933L,
    ) = Track(
        id = TrackId("t1"), title = "晴天", artist = "周杰伦", album = "第二天堂",
        durationMs = duration, sampleRate = rate, bitDepth = bits,
        sourceRef = SourceRef("s", path, url = "https://cdn.example/signed?token=SECRET"),
        sourceType = type,
    )

    @Test
    @DisplayName("§61.218 F1：格式标签 = 扩展名 + 采样率 + 位深")
    fun formatLabel() {
        assertEquals("FLAC · 96kHz · 24bit", TrackInfo.formatLabel(track()))
        assertEquals("MP3 · 44.1kHz", TrackInfo.formatLabel(track(path = "/a.mp3", rate = 44_100, bits = 0)))
    }

    @Test
    @DisplayName("§61.218 F2：未知后缀 / 未知采样率不伪造")
    fun unknownValues() {
        assertEquals("未知", TrackInfo.formatLabel(track(path = "/a.xyz", rate = 0, bits = 0)))
    }

    @Test
    @DisplayName("§61.218 F3：来源与时长如实显示，未知时长不写 00:00")
    fun sourceAndDuration() {
        assertEquals("STRM 直链", TrackInfo.sourceLabel(track(type = SourceType.STRM)))
        assertEquals("SFTP", TrackInfo.sourceLabel(track(type = SourceType.SFTP)))
        assertEquals("04:04", TrackInfo.durationLabel(track()))
        assertEquals("未知", TrackInfo.durationLabel(track(duration = 0L)))
    }
}
