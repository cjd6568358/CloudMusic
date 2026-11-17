package com.example.cloudmusic.di.lyrics

import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.storage.LyricFileStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.198：在线歌词 provider 的纯 JVM 判定与降级契约（零网络、零 Android 依赖）。 */
class OnlineLyricsFetcherTest {

    @TempDir
    lateinit var cacheDir: File

    private fun track(title: String = "江南", artist: String = "林俊杰") = Track(
        id = TrackId("online-track-1"),
        title = title,
        artist = artist,
        album = "第二天堂",
        durationMs = 267_946L,
        sourceRef = SourceRef("webdav-strm-probe", "a.mp3"),
        sourceType = SourceType.STRM,
    )

    @Test
    fun `search url encodes query and keeps fixed api params`() {
        val url = NeteaseLyricsApi.searchUrl("江南 林俊杰")
        assertTrue(url.startsWith("https://music.163.com/api/search/get?s="))
        assertTrue(url.contains("%E6%B1%9F%E5%8D%97"))
        assertTrue(url.endsWith("&type=1&offset=0&limit=5"))
        assertEquals(
            "https://music.163.com/api/song/lyric?id=108914&lv=1&kv=1&tv=-1",
            NeteaseLyricsApi.lyricUrl(108914L),
        )
    }

    @Test
    fun `pick song id requires title and artist match`() {
        assertEquals(108914L, NeteaseLyricsApi.pickSongId(SEARCH_JSON, "江南", "林俊杰"))
        // 括号副标题 / 大小写 / 标点不阻断匹配（首个同分候选取原版）
        assertEquals(108914L, NeteaseLyricsApi.pickSongId(SEARCH_JSON, "江南（Live）", "林俊杰"))
        // 艺人不对 → 不猜歌词
        assertNull(NeteaseLyricsApi.pickSongId(SEARCH_JSON, "江南", "周杰伦"))
        assertNull(NeteaseLyricsApi.pickSongId("""{"code":503}""", "江南", "林俊杰"))
    }

    @Test
    fun `valid lrc rejects pure music empty and error responses`() {
        assertNotNull(NeteaseLyricsApi.validLrc(LYRIC_JSON))
        assertNull(NeteaseLyricsApi.validLrc("""{"code":200,"pureMusic":true,"lrc":{"lyric":""}}"""))
        assertNull(NeteaseLyricsApi.validLrc("""{"code":404}"""))
        assertNull(NeteaseLyricsApi.validLrc("""{"code":200,"lrc":{"lyric":"纯音乐 请欣赏"}}"""))
    }

    @Test
    fun `fetch hits search then lyric saves raw cache and returns parsed lyrics`() {
        val urls = mutableListOf<String>()
        val store = LyricFileStore(cacheDir)
        val fetcher = OnlineLyricsFetcher(
            httpGet = { url ->
                urls += url
                if ("/search/" in url) SEARCH_JSON else LYRIC_JSON
            },
            store = store,
        )
        val t = track()
        val lyrics = fetcher.fetch(t)

        assertNotNull(lyrics)
        assertEquals(OnlineLyricsFetcher.SOURCE, lyrics.source)
        assertEquals("风到这里就是粘", lyrics.lines.first().text)
        assertEquals(2, urls.size)
        assertEquals(NeteaseLyricsApi.searchUrl("江南 林俊杰"), urls[0])
        assertEquals(NeteaseLyricsApi.lyricUrl(108914L), urls[1])
        assertTrue(store.exists(t.id))
        assertTrue(store.readRaw(t.id)!!.contains("[00:12.00]"))
    }

    @Test
    fun `fetch does not guess lyric when artist mismatches`() {
        val urls = mutableListOf<String>()
        val store = LyricFileStore(cacheDir)
        val fetcher = OnlineLyricsFetcher(
            httpGet = { url -> urls += url; SEARCH_JSON },
            store = store,
        )
        assertNull(fetcher.fetch(track(artist = "周杰伦")))
        assertEquals(1, urls.size) // 搜索未命中即止，不发第二次请求
        assertFalse(store.exists(track().id))
    }

    @Test
    fun `network failure is memoized and never cached`() {
        var calls = 0
        val store = LyricFileStore(cacheDir)
        val fetcher = OnlineLyricsFetcher(
            httpGet = { calls++; throw IllegalStateException("offline") },
            store = store,
        )
        val t = track()
        assertNull(fetcher.fetchRaw(t))
        assertNull(fetcher.fetchRaw(t))
        assertEquals(1, calls) // 失败记忆：UI 重入不反复联网
        assertFalse(store.exists(t.id))
    }

    @Test
    fun `pure music response is rejected and memoized`() {
        var calls = 0
        val fetcher = OnlineLyricsFetcher(
            httpGet = {
                calls++
                if ("/search/" in it) SEARCH_JSON
                else """{"code":200,"pureMusic":true,"lrc":{"lyric":""}}"""
            },
        )
        assertNull(fetcher.fetchRaw(track()))
        assertNull(fetcher.fetchRaw(track()))
        assertEquals(2, calls)
    }

    private companion object {
        const val SEARCH_JSON = """
            {"code":200,"result":{"songs":[
              {"id":108914,"name":"江南","artists":[{"name":"林俊杰"}]},
              {"id":108779,"name":"江南 (Live)","artists":[{"name":"林俊杰"}]}
            ]}}
        """
        const val LYRIC_JSON = """
            {"code":200,"lrc":{"version":3,"lyric":"[00:12.00]风到这里就是粘\n[00:16.00]粘住过客的思念"}}
        """
    }
}
