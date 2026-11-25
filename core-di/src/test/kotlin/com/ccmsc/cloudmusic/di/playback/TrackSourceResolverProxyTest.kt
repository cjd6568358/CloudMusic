package com.ccmsc.cloudmusic.di.playback

import com.ccmsc.cloudmusic.di.adapters.CastProxyBackend
import com.ccmsc.cloudmusic.di.adapters.PrePublishedCastProxy
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.output.dlna.LinkProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.268：本地 WebDAV 播放无声——签名直链（`?sign=`）必须转本机回环代理，
 * 普通 URL（STRM / 歌单、无 sign）保持 §61.252 原样直推。
 *
 * 真机一手（2026-11-21，192.168.1.10:37385）：`DefaultHttpDataSource` 直连
 * `…mp3?sign=…` → `EOFException: unexpected end of stream` → preparation 失败无声。
 */
class TrackSourceResolverProxyTest {

    private class FakeBackend : CastProxyBackend {
        val published = mutableMapOf<String, Link>()
        override fun publish(trackId: String, link: Link): String {
            published[trackId] = link
            return "http://127.0.0.1:41271/t/$trackId"
        }
        override fun coverUrlFor(trackId: String): String? = null
        override fun ensureStarted() {}
        override fun urlFor(trackId: String) = "http://127.0.0.1:41271/t/$trackId"
        override fun stop() {}
        override fun isRunning() = true
    }

    private fun resolver(link: Link, backend: FakeBackend) = ProductionTrackSourceResolver(
        links = LinkProvider { link },
        proxy = PrePublishedCastProxy(backend),
    )

    private fun track() = Track(
        id = com.ccmsc.cloudmusic.model.TrackId("t-1"), title = "逝年", artist = "夏小虎",
        album = "x", durationMs = 196_520,
        sourceRef = com.ccmsc.cloudmusic.model.SourceRef(
            "ecloud-music", "/夏小虎 - 逝年.mp3", null),
        sourceType = com.ccmsc.cloudmusic.model.SourceType.WEBDAV,
    )

    @Test
    @DisplayName("§61.268 · 判定：只有 ?sign= 签名直链转代理")
    fun signDetection() {
        assertTrue(ProductionTrackSourceResolver.needsLoopbackProxy(
            "http://192.168.1.1:15244/d/a.mp3?sign=abc:0"))
        assertTrue(ProductionTrackSourceResolver.needsLoopbackProxy(
            "http://host/f?x=1&SIGN=xyz"))
        assertFalse(ProductionTrackSourceResolver.needsLoopbackProxy(
            "http://host/strm/live.m3u8?token=1")) // 非 sign 直链不碰
        assertFalse(ProductionTrackSourceResolver.needsLoopbackProxy(
            "http://host/a.mp3"))
        assertFalse(ProductionTrackSourceResolver.needsLoopbackProxy(null))
        assertFalse(ProductionTrackSourceResolver.needsLoopbackProxy(""))
    }

    @Test
    @DisplayName("§61.268 · 签名直链 resolve 后变成回环代理 URL")
    fun signedUrlGoesProxy() = runTest {
        val backend = FakeBackend()
        val signed = Link(url = "http://192.168.1.1:15244/d/a.mp3?sign=abc:0")
        val out = resolver(signed, backend).resolve(track())
        assertEquals("http://127.0.0.1:41271/t/t-1", out.url)
        assertFalse(out.proxy)
        assertEquals(signed, backend.published["t-1"], "代理回源须拿到原始签名直链")
    }

    @Test
    @DisplayName("§61.252 零回归 · 普通 URL 直链原样直推")
    fun plainUrlUnchanged() = runTest {
        val backend = FakeBackend()
        val plain = Link(url = "http://host/strm/a.m3u8")
        val out = resolver(plain, backend).resolve(track())
        assertEquals(plain, out)
        assertTrue(backend.published.isEmpty(), "无 sign 不得触发代理")
    }

    @Test
    @DisplayName("零回归 · PROXIED 仍走回环代理")
    fun proxiedStillProxy() = runTest {
        val backend = FakeBackend()
        val proxied = Link(proxy = true, proxySourceUrl = "http://host/dav/a.ape")
        val out = resolver(proxied, backend).resolve(track())
        assertEquals("http://127.0.0.1:41271/t/t-1", out.url)
        assertFalse(out.proxy)
    }
}
