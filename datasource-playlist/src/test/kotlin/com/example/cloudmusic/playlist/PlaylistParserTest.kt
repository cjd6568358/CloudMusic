package com.example.cloudmusic.playlist

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-03 §7 T08~T11、T14 与 §6 边界。 */
class PlaylistParserTest {

    @Test
    @DisplayName("T08 #EXTINF:213,艺人 - 标题 → 时长与标题提示正确")
    fun t08() {
        val result = parsePlaylist(
            "x.m3u",
            "#EXTM3U\n#EXTINF:213,周杰伦 - 晴天\nsongs/晴天.mp3",
        ) as PlaylistParseResult.Ok
        val entry = result.entries.single() as PlaylistEntry.Relative
        assertEquals(213L, entry.durationSec)
        assertEquals("周杰伦 - 晴天", entry.titleHint)
        assertEquals("songs/晴天.mp3", entry.path)
    }

    @Test
    @DisplayName("T09 .pls 三个 FileN 但 NumberOfEntries=2 → 按实际 3 条")
    fun t09() {
        val text = """
            [playlist]
            NumberOfEntries=2
            File1=/music/a.mp3
            Title1=A
            Length1=100
            File2=/music/b.mp3
            File3=/music/c.mp3
            Version=2
        """.trimIndent()
        val result = parsePlaylist("x.pls", text) as PlaylistParseResult.Ok
        assertEquals(3, result.entries.size)
        assertEquals("/music/a.mp3", (result.entries[0] as PlaylistEntry.Absolute).path)
        assertEquals("A", result.entries[0].titleHint)
        assertEquals("/music/c.mp3", (result.entries[2] as PlaylistEntry.Absolute).path)
    }

    @Test
    @DisplayName("T10 相对路径以播放列表父目录为基准")
    fun t10() {
        val target = resolveEntry(PlaylistEntry.Relative("../其他/歌.flac"), "/music/list", "/")
        assertEquals(ResolvedTarget.File("/music/其他/歌.flac"), target)
    }

    @Test
    @DisplayName("T11 归一化后逃出 Storage 根 → Rejected")
    fun t11() {
        val target = resolveEntry(PlaylistEntry.Relative("../../etc/passwd"), "/music/list", "/music")
        assertTrue(target is ResolvedTarget.Rejected)
        assertTrue("越界" in (target as ResolvedTarget.Rejected).reason)
    }

    @Test
    @DisplayName("T14 .m3u8 含 #EXT-X-* → 整份 Rejected")
    fun t14() {
        val result = parsePlaylist(
            "x.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nseg.ts",
        )
        assertTrue(result is PlaylistParseResult.Rejected)
        assertTrue("HLS" in (result as PlaylistParseResult.Rejected).reason)
    }

    @Test
    @DisplayName("B08 无 #EXTM3U 头仍解析；B09 EXTINF:-1 时长未知")
    fun noHeaderAndUnknownDuration() {
        val result = parsePlaylist("x.m3u", "#EXTINF:-1,未知\na.mp3\nb.flac") as PlaylistParseResult.Ok
        assertEquals(2, result.entries.size)
        assertNull(result.entries[0].durationSec)
        assertEquals("未知", result.entries[0].titleHint)
        assertNull(result.entries[1].titleHint)
    }

    @Test
    @DisplayName("HLS 分片与非 http scheme 标 Unsupported，不入库")
    fun rejectFragments() {
        val result = parsePlaylist("x.m3u", "seg.ts\nftp://nas/a.flac\nhttps://x/ok.flac") as PlaylistParseResult.Ok
        assertTrue(result.entries[0] is PlaylistEntry.Unsupported)
        assertTrue(result.entries[1] is PlaylistEntry.Unsupported)
        assertEquals("https://x/ok.flac", (result.entries[2] as PlaylistEntry.Url).url)
    }

    @Test
    @DisplayName("B16 非 UTF-8 回落 GBK 解码成功")
    fun gbkFallback() {
        val gbk = "周杰伦 - 晴天.mp3".toByteArray(Charset.forName("GBK"))
        assertTrue("周杰伦" in decodePlaylistText(gbk))
        assertEquals("https://x/a.flac", decodePlaylistText("https://x/a.flac".toByteArray()))
    }
}
