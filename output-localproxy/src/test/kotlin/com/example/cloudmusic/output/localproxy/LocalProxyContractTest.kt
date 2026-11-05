package com.example.cloudmusic.output.localproxy

import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-08 §8.2 / §8.3 + §13 T10 / T11 / T12 的纯 JVM 判定部分。 */
class LocalProxyContractTest {

    private class FakeDataSource(private val total: Long = 1_000L) : IDataSource {
        var openCount = 0
        var closeCount = 0
        override fun open(offset: Long): Long { openCount++; return total }
        override fun readAt(offset: Long, buf: ByteArray, len: Int): Int = 0
        override fun close() { closeCount++ }
        override fun mimeHint(): String = "audio/flac"
    }

    private class FakeClock(var t: Long = 0L)

    private fun fixture(
        clock: FakeClock = FakeClock(),
        total: Long = 1_000L,
        cover: ByteArray? = null,
    ): Triple<InMemoryLocalHttpStreamProxy, MutableList<FakeDataSource>, String> {
        val created = mutableListOf<FakeDataSource>()
        val registry = TokenRegistry(
            clock = { clock.t },
            random = { n -> ByteArray(n) { it.toByte() } },
        )
        val proxy = InMemoryLocalHttpStreamProxy(
            host = "192.168.1.2", port = 8080, registry = registry,
            dataSourceFactory = { FakeDataSource(total).also { created.add(it) } },
            coverProvider = { cover },
        )
        val url = proxy.publish("track-1", Link(localPath = "/music/a.flac", localSize = total))
        return Triple(proxy, created, url)
    }

    @Test
    @DisplayName("03-08 §13 T10 Range: bytes=100- → 206 + 正确 Content-Range")
    fun t10() {
        val (proxy, _, url) = fixture()
        val path = "/" + url.substringAfter("8080/")
        val res = proxy.serve(
            ProxyRequest(ProxyMethod.GET, path, rangeHeader = "bytes=100-"),
        )
        assertEquals(ProxyStatus.PARTIAL_CONTENT, res.status)
        assertEquals("bytes 100-999/1000", res.headers["Content-Range"])
        assertEquals("900", res.headers["Content-Length"])
        assertEquals(900L, res.bodyLength)
        assertEquals("audio/flac", res.headers["Content-Type"])
        assertEquals("Streaming", res.headers["transferMode.dlna.org"])
    }

    @Test
    @DisplayName("03-08 §13 T11 HEAD /t/{token} 头部与 GET 一致、无 body")
    fun t11() {
        val (proxy, _, url) = fixture()
        val path = "/" + url.substringAfter("8080/")
        val get = proxy.serve(ProxyRequest(ProxyMethod.GET, path, "bytes=0-99"))
        val head = proxy.serve(ProxyRequest(ProxyMethod.HEAD, path, "bytes=0-99"))
        assertEquals(get.status, head.status)
        assertEquals(get.headers, head.headers)
        assertEquals(0L, head.bodyLength)
        assertEquals(100L, get.bodyLength)
        assertEquals("bytes 0-99/1000", head.headers["Content-Range"])
    }

    @Test
    @DisplayName("03-08 §13 T12 token 不存在 / 过期 → 404，且不泄露路径")
    fun t12() {
        val clock = FakeClock()
        val (proxy, created, url) = fixture(clock)
        val missing = proxy.serve(ProxyRequest(ProxyMethod.GET, "/t/deadbeef"))
        assertEquals(ProxyStatus.NOT_FOUND, missing.status)
        assertTrue(missing.headers.isEmpty())
        assertFalse(missing.toString().contains("/music/a.flac"))
        assertEquals(0, created.size) // 未解析到 token，绝不开流

        clock.t = 10 * 60 * 1000L // 默认 TTL 到期
        val path = "/" + url.substringAfter("8080/")
        assertEquals(ProxyStatus.NOT_FOUND, proxy.serve(ProxyRequest(ProxyMethod.GET, path)).status)
    }

    @Test
    @DisplayName("EA01 多段 / 非法 Range 回落整段 200")
    fun ea01() {
        assertNull(RangeResponder.parse("bytes=0-100,200-300"))
        assertNull(RangeResponder.parse("bytes=-500"))
        assertNull(RangeResponder.parse("bytes=abc-"))
        assertNull(RangeResponder.parse("bytes=200-100"))
        val res = RangeResponder.respond(
            ProxyRequest(ProxyMethod.GET, "/t/x", "bytes=0-100,200-300"), 1_000L,
        )
        assertEquals(ProxyStatus.OK, res.status)
        assertEquals("1000", res.headers["Content-Length"])
        assertNull(res.headers["Content-Range"])
    }

    @Test
    @DisplayName("EA02 未知长度不伪造 Content-Length，走 chunked")
    fun ea02() {
        val res = RangeResponder.respond(ProxyRequest(ProxyMethod.GET, "/t/x"), -1L)
        assertEquals(ProxyStatus.OK, res.status)
        assertNull(res.headers["Content-Length"])
        assertEquals("chunked", res.headers["Transfer-Encoding"])
        assertEquals(-1L, res.bodyLength)
    }

    @Test
    @DisplayName("EA03 请求 getcontentFeatures 时回 DLNA 特征头")
    fun ea03() {
        val without = RangeResponder.respond(ProxyRequest(ProxyMethod.GET, "/t/x"), 1_000L)
        assertNull(without.headers["getcontentFeatures.dlna.org"])
        val withHeader = RangeResponder.respond(
            ProxyRequest(ProxyMethod.GET, "/t/x", getContentFeatures = true), 1_000L,
        )
        assertEquals("1", withHeader.headers["getcontentFeatures.dlna.org"])
        assertTrue(withHeader.headers["contentFeatures.dlna.org"]!!.startsWith("DLNA.ORG_OP=01"))
    }

    @Test
    @DisplayName("EA04 token 为 16 字节 hex（32 字符）且不暴露路径")
    fun ea04() {
        val (_, _, url) = fixture()
        val token = url.substringAfterLast("/")
        assertEquals(32, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(url.contains("/music/a.flac"))
        assertEquals("http://192.168.1.2:8080/t/$token", url)
    }

    @Test
    @DisplayName("EA05 stop() 停服并让全部 token 失效")
    fun ea05() {
        val (proxy, _, url) = fixture()
        assertTrue(proxy.isRunning())
        val path = "/" + url.substringAfter("8080/")
        proxy.stop()
        assertFalse(proxy.isRunning())
        assertEquals(ProxyStatus.NOT_FOUND, proxy.serve(ProxyRequest(ProxyMethod.GET, path)).status)
    }

    @Test
    @DisplayName("EA06 每个 HTTP 请求各自新建并关闭 IDataSource（10 §2 R8）")
    fun ea06() {
        val (proxy, created, url) = fixture()
        val path = "/" + url.substringAfter("8080/")
        proxy.serve(ProxyRequest(ProxyMethod.GET, path))
        proxy.serve(ProxyRequest(ProxyMethod.GET, path, "bytes=10-"))
        assertEquals(2, created.size)
        assertTrue(created.all { it.openCount == 1 && it.closeCount == 1 })
        assertEquals(2, proxy.openedCount)
        assertEquals(2, proxy.closedCount)
    }

    @Test
    @DisplayName("EA07 GET /cover/{token} 走封面提供者，HEAD 无 body")
    fun ea07() {
        val (proxy, _, url) = fixture(cover = ByteArray(128))
        val path = "/cover/" + url.substringAfterLast("/")
        val get = proxy.serve(ProxyRequest(ProxyMethod.GET, path))
        assertEquals(ProxyStatus.OK, get.status)
        assertEquals("128", get.headers["Content-Length"])
        assertEquals(128L, get.bodyLength)
        val head = proxy.serve(ProxyRequest(ProxyMethod.HEAD, path))
        assertEquals(get.headers, head.headers)
        assertEquals(0L, head.bodyLength)
    }

    @Test
    @DisplayName("EA08 §8.4 启动条件：纯直链队列不启代理")
    fun ea08() {
        assertFalse(
            ProxyStartPolicy.shouldStart(listOf(Link.Kind.URL), anyViaProxy = false, fallbackNeeded = false),
        )
        assertTrue(
            ProxyStartPolicy.shouldStart(listOf(Link.Kind.LOCAL), anyViaProxy = false, fallbackNeeded = false),
        )
        assertTrue(
            ProxyStartPolicy.shouldStart(listOf(Link.Kind.URL), anyViaProxy = false, fallbackNeeded = true),
        )
        assertTrue(
            ProxyStartPolicy.shouldStart(listOf(Link.Kind.URL), anyViaProxy = true, fallbackNeeded = false),
        )
        assertTrue(
            ProxyStartPolicy.shouldStart(
                listOf(Link.Kind.URL), anyViaProxy = false, fallbackNeeded = false,
                settings = ProxySettings(preferDirectLink = false),
            ),
        )
        assertTrue(
            ProxyStartPolicy.shouldStart(
                listOf(Link.Kind.URL), anyViaProxy = false, fallbackNeeded = false,
                settings = ProxySettings(forceWebdavProxy = true),
            ),
        )
    }

    @Test
    @DisplayName("EA09 Range 起点越界回落整段 200；未知路径 404")
    fun ea09() {
        val res = RangeResponder.respond(
            ProxyRequest(ProxyMethod.GET, "/t/x", "bytes=2000-"), 1_000L,
        )
        assertEquals(ProxyStatus.OK, res.status)
        val (proxy, _, _) = fixture()
        assertEquals(
            ProxyStatus.NOT_FOUND,
            proxy.serve(ProxyRequest(ProxyMethod.GET, "/unknown")).status,
        )
    }

    @Test
    @DisplayName("EA10 registry purgeExpired / invalidateAll 清理过期条目")
    fun ea10() {
        val clock = FakeClock()
        val reg = TokenRegistry(clock = { clock.t }, random = { n -> ByteArray(n) })
        val token = reg.register("t", Link(localPath = "/a", localSize = 1))
        assertEquals(1, reg.size())
        clock.t = 10 * 60 * 1000L
        reg.purgeExpired()
        assertEquals(0, reg.size())
        assertNull(reg.resolve(token))
        reg.register("t2", Link(url = "https://example.com/a.mp3"))
        reg.invalidateAll()
        assertEquals(0, reg.size())
    }
}
