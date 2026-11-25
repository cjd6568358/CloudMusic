package com.ccmsc.cloudmusic.di.scan

import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.storage.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.233：封面在线刮削——Deezer 响应解析纯函数 + 会话流程（fake http / fake writer）。 */
class OnlineCoverScraperTest {

    private fun t2s(t: Track): String = "id=" + t.id.value + " src=" + t.sourceType + " cover=" + t.coverRef

    @Test
    @DisplayName("§61.233 · parseDeezerCover：cover_big / medium / cover 三级兜底")
    fun parseThreeTier() {
        val big = """{"data":[{"album":{"id":1,"cover_big":"https://e/cov/big.jpg"}}]}"""
        assertEquals("https://e/cov/big.jpg", parseDeezerCover(big))
        val medium = """{"data":[{"album":{"cover_medium":"https://e/cov/md.jpg"}}]}"""
        assertEquals("https://e/cov/md.jpg", parseDeezerCover(medium))
        val base = """{"data":[{"album":{"cover":"https://e/cov/base.jpg"}}]}"""
        assertEquals("https://e/cov/base.jpg", parseDeezerCover(base))
    }

    @Test
    @DisplayName("§61.233 · parseDeezerCover：空 data / 无 album / 非 http / 空文本 → null")
    fun parseGraceful() {
        assertNull(parseDeezerCover(""))
        assertNull(parseDeezerCover("""{"total":0,"data":[]}"""))
        assertNull(parseDeezerCover("""{"data":[{"title":"x"}]}"""))
        assertNull(parseDeezerCover("""{"data":[{"album":{"cover_big":"not-a-url"}}]}"""))
        assertNull(parseDeezerCover("not json"))
    }

    @Test
    @DisplayName("§61.233 · deezerSearchUrl：album+artist 组合且 URL encode")
    fun searchUrl() {
        val url = deezerSearchUrl("黑梦", "窦唯")
        assertTrue(url.startsWith("https://api.deezer.com/search?limit=1&q="))
        assertTrue(":" in url.substringAfter("q=") || "%3A" in url, "album:\"x\" 语法 encode 后在位")
        assertTrue("%22" in url, "引号被 encode")
    }

    private fun track(id: Int, album: String, artist: String, source: SourceType, cover: String? = null) = Track(
        id = TrackId("id-$id"), title = "t$id", artist = artist, album = album,
        durationMs = 100_000L, sourceRef = SourceRef("s", "/p$id.flac"), sourceType = source,
        coverRef = cover,
    )

    @Test
    @DisplayName("§61.233 · 会话流程：缺封面可刮源命中 → writer 落盘 + updateCover；已有封面跳过")
    fun sessionFlow(@TempDir dir: File) = runTest {
        // 可刮目标（无封面）×2 + 已有封面 1 + STRM 1（不刮）
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "黑梦", "窦唯", SourceType.LOCAL),
            track(2, "黑梦", "窦唯", SourceType.SFTP),
            track(3, "黑梦", "窦唯", SourceType.LOCAL, cover = "existing.jpg"),
            track(4, "黑梦", "窦唯", SourceType.STRM),
        ))
        val fakeBytes = ByteArray(64) { 0x42 }
        val http = CoverHttp { url ->
            if (url.contains("api.deezer.com"))
                """{"data":[{"album":{"cover_big":"https://img/x.jpg"}}]}""".toByteArray()
            else fakeBytes
        }
        val written = mutableListOf<Pair<String, ByteArray>>()
        val writer = object : CoverWriterPort {
            override fun write(trackId: String, bytes: ByteArray): String =
                run { written.add(trackId to bytes); "cache/covers/$trackId.webp" }
            override fun writeFromRef(trackId: String, ref: String): String? = null
        }
        val session = CoverScrapeSession(repo, writer, http)
        val report = session.scrapeMissing(50)
        assertEquals(2, report.attempted, "已封面 + STRM 被过滤")
        assertEquals(2, report.hit, "两轨均命中落盘")
        assertEquals(2, written.size)
        // 落库：只写缺封面行
        val after = repo.observeAll().first()
        after.forEach { t ->
            when {
                t.sourceType == SourceType.STRM -> assertNull(t.coverRef, "STRM 不刮")
                t.id.value == "id-3" -> assertEquals("existing.jpg", t.coverRef, "已有封面不覆盖")
                else -> assertTrue(t.coverRef?.contains("covers") == true, "新刮封面已落库")
            }
        }
    }

    @Test
    @DisplayName("§61.233 · 会话流程：网络失败 / 无命中 → attempted 计数、零 hit，不抛")
    fun sessionGraceful(@TempDir dir: File) = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(track(1, "黑梦", "窦唯", SourceType.LOCAL)))
        val http = CoverHttp { url -> if (url.contains("api.deezer.com")) "{}".toByteArray() else null }
        val writer = object : CoverWriterPort {
            override fun write(trackId: String, bytes: ByteArray): String = "x"
            override fun writeFromRef(trackId: String, ref: String): String? = null
        }
        val report = CoverScrapeSession(repo, writer, http).scrapeMissing(50)
        assertEquals(1, report.attempted)
        assertEquals(0, report.hit)
        assertNull(repo.observeAll().first()[0].coverRef)
        File(dir, "unused").let { assertTrue(!it.exists()) }
    }
}
