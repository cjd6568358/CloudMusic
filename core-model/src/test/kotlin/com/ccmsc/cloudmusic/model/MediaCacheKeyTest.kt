package com.ccmsc.cloudmusic.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §61.318（R1）：CUE 分轨缓存 key 换轨为物理文件锚的纯 JVM 判据。
 *
 * 钉死四件事：
 * 1. 同一物理音频的多个分轨（不同 TrackId / 不同 cue 起止）得到**同一个** key；
 * 2. 不同物理文件 / 不同 storage 的 key 互不相同；
 * 3. key 不含签名 URL / host / 空格 / 中文路径明文（sha256 固定 32 hex）；
 * 4. 历史 trackId 锚仅由兼容入口产生，生产入口永不产出 `b2track:`。
 */
class MediaCacheKeyTest {

    private fun cueTrack(trackNo: Int, startMs: Long, endMs: Long) = Track(
        id = TrackId.of("webdav-1", "/music/album.ape#album#$trackNo"),
        title = "分轨 $trackNo",
        artist = "歌手",
        album = "专辑",
        durationMs = 180_000,
        trackNo = trackNo,
        sourceRef = SourceRef("webdav-1", "/music/album.ape", "https://host/sign?token=SECRET"),
        sourceType = SourceType.WEBDAV,
        cueStartMs = startMs,
        cueEndMs = endMs,
    )

    @Test
    fun `同一物理音频的分轨共享缓存 key`() {
        val t1 = cueTrack(1, 0L, 180_000L)
        val t2 = cueTrack(2, 180_000L, 360_000L)
        val t3 = cueTrack(3, 360_000L, 0L)
        assertNotEquals(t1.id.value, t2.id.value, "分轨 TrackId 必须互不相同")
        assertEquals(t1.sourceRef.path, t2.sourceRef.path, "前提：分轨 sourceRef 指向父音频")
        assertEquals(MediaCacheKey.of(t1), MediaCacheKey.of(t2))
        assertEquals(MediaCacheKey.of(t2), MediaCacheKey.of(t3))
    }

    @Test
    fun `不同物理文件或不同 storage 的 key 互不相同`() {
        val t1 = cueTrack(1, 0L, 180_000L)
        val otherFile = t1.copy(
            id = TrackId.of("webdav-1", "/music/other.ape#album#1"),
            sourceRef = t1.sourceRef.copy(path = "/music/other.ape"),
        )
        val otherStorage = t1.copy(
            id = TrackId.of("webdav-2", "/music/album.ape#album#1"),
            sourceRef = t1.sourceRef.copy(storageId = "webdav-2"),
        )
        assertNotEquals(MediaCacheKey.of(t1), MediaCacheKey.of(otherFile))
        assertNotEquals(MediaCacheKey.of(t1), MediaCacheKey.of(otherStorage))
    }

    @Test
    fun `key 形态是 b2file 加 32 位 hex 且不含签名与路径明文`() {
        val key = MediaCacheKey.of(cueTrack(1, 0L, 180_000L))
        assertTrue(key.startsWith(MediaCacheKey.FILE_PREFIX), "应为新物理锚前缀：$key")
        val hex = key.removePrefix(MediaCacheKey.FILE_PREFIX)
        assertEquals(32, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "hex 形态：$hex")
        assertFalse(key.contains("SECRET"))
        assertFalse(key.contains("host"))
        assertFalse(key.contains("album.ape"))
    }

    @Test
    fun `路径规范化等价且生产入口不产出旧前缀`() {
        assertEquals(
            MediaCacheKey.ofPath("s", "/music/./album.ape"),
            MediaCacheKey.ofPath("s", "/music/album.ape"),
        )
        val produced = MediaCacheKey.ofPath("s", "/music/album.ape")
        assertTrue(MediaCacheKey.isFileKey(produced))
        assertFalse(MediaCacheKey.isFileKey(MediaCacheKey.ofFallback("t-1")))
        assertEquals("b2track:t-1", MediaCacheKey.ofFallback("t-1"))
    }
}
