package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V0.2 §61.218：歌曲信息展示口径的纯 JVM 契约（零 UI、零网络）。 */
class TrackInfoTest {

    private fun track(
        path: String = "/music/a.flac",
        rate: Int = 96_000,
        bits: Int = 24,
        type: SourceType = SourceType.LOCAL,
        duration: Long = 244_933L,
        artist: String = "周杰伦",
        album: String = "第二天堂",
        availability: Availability = Availability.OK,
    ) = Track(
        id = TrackId("t1"), title = "晴天", artist = artist, album = album,
        durationMs = duration, sampleRate = rate, bitDepth = bits,
        sourceRef = SourceRef("s", path, url = "https://cdn.example/signed?token=SECRET"),
        sourceType = type,
        availability = availability,
    )

    @Test
    @DisplayName("§61.218 F1：格式标签 = 扩展名 + 采样率 + 位深")
    fun formatLabel() {
        assertEquals("FLAC · 96kHz · 24bit", TrackInfo.formatLabel(track()))
        assertEquals("MP3 · 44.1kHz", TrackInfo.formatLabel(track(path = "/a.mp3", rate = 44_100, bits = 0)))
    }

    @Test
    @DisplayName("§61.275 F2：未知后缀 / 未知采样率不伪造（占位 — ，不再写「未知」字样）")
    fun unknownValues() {
        assertEquals(
            TrackInfo.UNKNOWN,
            TrackInfo.formatLabel(track(path = "/a.xyz", rate = 0, bits = 0)),
        )
    }

    @Test
    @DisplayName("§61.218 F3：来源与时长如实显示，未知时长不写 00:00")
    fun sourceAndDuration() {
        assertEquals("STRM 直链", TrackInfo.sourceLabel(track(type = SourceType.STRM)))
        assertEquals("SFTP", TrackInfo.sourceLabel(track(type = SourceType.SFTP)))
        assertEquals("04:04", TrackInfo.durationLabel(track()))
        assertEquals(TrackInfo.UNKNOWN, TrackInfo.durationLabel(track(duration = 0L)))
    }

    @Test
    @DisplayName("§61.276 F5：不可用原因人话化（枚举名不进 UI，OK 无角标）")
    fun availabilityLabel() {
        assertEquals("", TrackInfo.availabilityLabel(track()))
        for (a in listOf(
            Availability.UNREACHABLE,
            Availability.LINK_EXPIRED,
            Availability.UNSUPPORTED_SCHEME,
        )) {
            val label = TrackInfo.availabilityLabel(track(availability = a))
            assertTrue(label.isNotBlank(), "不可用原因不得为空：" + a)
            assertFalse(label.contains(a.name), "原因文案泄漏枚举名：" + a.name)
        }
    }

    @Test
    @DisplayName("§61.275 F4：列表副题「已知字段才显示」——无空段、无「未知」噪声")
    fun listSubtitleKnownFieldsOnly() {
        assertEquals("周杰伦 · 第二天堂", TrackInfo.listSubtitle(track()))
        // 扫描器文件名兜底 / ID3 空值写法一律当缺失
        assertEquals("周杰伦", TrackInfo.listSubtitle(track(album = "未知")))
        assertEquals("第二天堂", TrackInfo.listSubtitle(track(artist = "")))
        assertEquals("", TrackInfo.listSubtitle(track(artist = "unknown", album = "<unknown>")))
        assertEquals("周杰伦", TrackInfo.listSubtitle(track(album = "   ")))
    }
}
