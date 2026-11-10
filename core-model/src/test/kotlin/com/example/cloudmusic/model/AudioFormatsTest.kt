package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `12 §61.45` / F1：单一格式真相源的纯 JVM 契约测试。
 * 断言「6 处引用同一来源」之外的集合口径本身，防止日后有人把表又拆散或悄悄扩收录集。
 */
class AudioFormatsTest {

    @Test
    @DisplayName("F1-1 · AUDIO_EXT 恰为 F1 前 ObjectType 的 14 项，不擅自扩收录集")
    fun audioExtIsCanonical() {
        assertEquals(
            setOf(
                "mp3", "flac", "ogg", "m4a", "wav", "opus", "wma", "aac",
                "ape", "dsf", "dff", "aiff", "wv", "tak",
            ),
            AudioFormats.AUDIO_EXT,
        )
        assertEquals(14, AudioFormats.AUDIO_EXT.size)
        assertTrue("wv" in AudioFormats.AUDIO_EXT)
        assertTrue("tak" in AudioFormats.AUDIO_EXT)
        assertFalse("alac" in AudioFormats.AUDIO_EXT, "alac 是否纳入收录集须由 F3 真机矩阵决定")
        assertFalse("m3u8" in AudioFormats.AUDIO_EXT)
        assertFalse("lrc" in AudioFormats.AUDIO_EXT)
    }

    @Test
    @DisplayName("F1-2 · EXTRA_AUDIO_EXT 仅 alac，且与收录集无交集")
    fun extraAudioExt() {
        assertEquals(setOf("alac"), AudioFormats.EXTRA_AUDIO_EXT)
        assertTrue(AudioFormats.AUDIO_EXT.intersect(AudioFormats.EXTRA_AUDIO_EXT).isEmpty())
    }

    @Test
    @DisplayName("F1-3 · STREAM_FILTER_EXT = 14 项 + alac，派生字符串可无损还原")
    fun streamFilterDerived() {
        assertEquals(15, AudioFormats.STREAM_FILTER_EXT.size)
        assertEquals(AudioFormats.AUDIO_EXT + "alac", AudioFormats.STREAM_FILTER_EXT)
        assertEquals(
            AudioFormats.STREAM_FILTER_EXT,
            AudioFormats.STREAM_FILTER_TYPES.split(',').toSet(),
        )
        assertFalse("ts" in AudioFormats.STREAM_FILTER_TYPES.split(',').toSet())
    }

    @Test
    @DisplayName("F1-4 · MIME_BY_EXT 与 03-06 §7 的 11 项口径逐字一致")
    fun mimeTableIsContract() {
        assertEquals(11, AudioFormats.MIME_BY_EXT.size)
        val expected = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "aac" to "audio/aac",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "wma" to "audio/x-ms-wma",
            "alac" to "audio/x-alac",
            "opus" to "audio/ogg",
            "m3u8" to "application/vnd.apple.mpegurl",
            "lrc" to "text/plain",
        )
        assertEquals(expected, AudioFormats.MIME_BY_EXT)
        // 6 种新收录格式没有已确认 MIME，F1 不编造
        listOf("ape", "dsf", "dff", "aiff", "wv", "tak").forEach {
            assertEquals(AudioFormats.OCTET_STREAM, AudioFormats.mimeOf(it))
        }
    }

    @Test
    @DisplayName("F1-5 · extOf 去 query / 取路径末段 / 大小写归一")
    fun extOfPaths() {
        assertEquals("mp3", AudioFormats.extOf("https://x.y.com/a.MP3?token=1"))
        assertEquals("flac", AudioFormats.extOf("/music/a.flac"))
        assertEquals("m4a", AudioFormats.extOf("C:/music/a.M4A"))
        assertEquals("", AudioFormats.extOf("https://x.y.com/a"))
        assertEquals("", AudioFormats.extOf("x"))
        assertEquals("gitignore", AudioFormats.extOf(".gitignore"))
    }

    @Test
    @DisplayName("F1-6 · mimeOf / mimeOfName 端到端口径与未知兜底")
    fun mimeEndToEnd() {
        assertEquals("audio/mpeg", AudioFormats.mimeOf("MP3"))
        assertEquals("audio/mp4", AudioFormats.mimeOfName("https://x/a.m4a?sig=1"))
        assertEquals("audio/aac", AudioFormats.mimeOfName("/a.aac"))
        assertEquals("audio/x-ms-wma", AudioFormats.mimeOfName("/a.wma"))
        assertEquals("audio/x-alac", AudioFormats.mimeOfName("/a.alac"))
        assertEquals("text/plain", AudioFormats.mimeOfName("/a.lrc"))
        assertEquals("application/vnd.apple.mpegurl", AudioFormats.mimeOfName("/list.m3u8"))
        assertEquals(AudioFormats.OCTET_STREAM, AudioFormats.mimeOfName("/a.bin"))
        assertEquals(AudioFormats.OCTET_STREAM, AudioFormats.mimeOf("ape"))
    }

    @Test
    @DisplayName("F1-7 · isAudio 大小写不敏感，文本/额外项不属曲库音频")
    fun isAudioBoundary() {
        assertTrue(AudioFormats.isAudio("FLAC"))
        assertTrue(AudioFormats.isAudio("wv"))
        assertFalse(AudioFormats.isAudio("alac"))
        assertFalse(AudioFormats.isAudio("m3u8"))
        assertFalse(AudioFormats.isAudio("exe"))
    }

    @Test
    @DisplayName("F2-b-1 · LOCAL 源取 localPath 真实后缀，不再把 MIME 当扩展名")
    fun extOfSourceLocalPath() {
        // §61.47 真机原状：url=null + mimeHint=audio/mpeg → extension=audio/mpeg
        assertEquals(
            "mp3",
            AudioFormats.extOfSource(null, "/data/data/com.example.cloudmusic/files/music/fixture.mp3", "audio/mpeg"),
        )
        assertEquals("flac", AudioFormats.extOfSource(null, "/music/a.FLAC", "audio/flac"))
        assertEquals("m4a", AudioFormats.extOfSource(null, "C:/music/a.m4a", "audio/mp4"))
    }

    @Test
    @DisplayName("F2-b-2 · URL 后缀优先且去 query / 大小写归一")
    fun extOfSourceUrlPriority() {
        assertEquals("mp3", AudioFormats.extOfSource("https://x.y.com/a.MP3?token=1", null, "audio/mpeg"))
        assertEquals("ogg", AudioFormats.extOfSource("http://10.0.0.2:8000/t/a.ogg", null, "audio/ogg"))
        // URL 优先于 localPath（URL 源不应误用本地夹具后缀）
        assertEquals("aac", AudioFormats.extOfSource("https://x/a.aac?sig=1", "/local/fixture.mp3", "audio/aac"))
    }

    @Test
    @DisplayName("F2-b-3 · 无后缀回环直链由 mimeHint 反查已知 MIME，不回传 MIME 字符串")
    fun extOfSourceMimeFallback() {
        assertEquals("mp3", AudioFormats.extOfSource("http://10.0.0.2:8000/t/tok", null, "audio/mpeg"))
        assertEquals("flac", AudioFormats.extOfSource("http://10.0.0.2:8000/t/tok", null, "audio/flac"))
        assertEquals("ogg", AudioFormats.extOfSource(null, null, "audio/ogg"))
        assertEquals("m3u8", AudioFormats.extOfSource(null, null, "application/vnd.apple.mpegurl"))
        // 逆表由 MIME_BY_EXT 派生，audio/ogg 多扩展名时取首个登记项 ogg（非 opus）
        assertEquals("ogg", AudioFormats.extOfSource(null, null, "audio/ogg"))
    }

    @Test
    @DisplayName("F2-b-4 · 未知 MIME / 长 token / 点文件一律空串，不编造")
    fun extOfSourceNeverInvents() {
        assertEquals("", AudioFormats.extOfSource(null, null, "application/octet-stream"))
        assertEquals("", AudioFormats.extOfSource(null, null, "audio/x-unknown"))
        assertEquals("", AudioFormats.extOfSource(null, null, null))
        assertEquals("", AudioFormats.extOfSource(null, "/music/.gitignore", null))
        assertEquals("", AudioFormats.extOfSource("https://x.y.com/a", null, null))
        assertEquals("", AudioFormats.extOfSource("https://x.y.com/longtoken123456", null, null))
    }
}
