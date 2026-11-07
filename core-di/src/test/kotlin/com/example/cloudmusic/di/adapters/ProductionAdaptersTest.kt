package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.bridge.DataSourceAdapter
import com.example.cloudmusic.bridge.DriverStreamFactory
import com.example.cloudmusic.bridge.FdOpener
import com.example.cloudmusic.bridge.JvmFileFdOpener
import com.example.cloudmusic.bridge.RangeHttpStack
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.output.dlna.MulticastLockPort
import com.example.cloudmusic.output.dlna.MulticastTransport
import com.example.cloudmusic.output.dlna.SsdpDiscovery
import com.example.cloudmusic.output.dlna.SsdpScanner
import com.example.cloudmusic.output.localproxy.InMemoryLocalHttpStreamProxy
import com.example.cloudmusic.output.localproxy.ProxyMethod
import com.example.cloudmusic.output.localproxy.ProxyRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 第十六批（`12 §25`）生产端口适配的 JVM 契约测试。
 * 用纯 `java.net.ServerSocket` 手写的极简本地回环 HTTP 服务（`jdk.httpserver` 不在 AGP
 * 的 JVM 单测 classpath 里），不依赖 Android 运行时 / MockWebServer / 真机；
 * 不实例化 `AndroidSourceAdapters` 与 `BridgeModule` 中的 Android 框架类。
 */
class ProductionAdaptersTest {

    private val client = OkHttpClient()

    // ---- OkHttp 端口 ---------------------------------------------------------

    @Test
    fun `T01 Range 请求透传头并解析总长度`() = withServer { server ->
        server.handle("/track") { ex ->
            val range = ex.header("Range")
            if (range == "bytes=4-") {
                ex.respond(206, "5678", "Content-Range" to "bytes 4-7/8")
            } else {
                ex.respond(200, "12345678")
            }
        }
        val slice = OkHttpRangeHttpStack(client).fetch(
            url = server.url("/track"),
            headers = listOf(Header("Authorization", "Basic secret")),
            rangeStart = 4,
            redirect = false,
        )
        assertEquals("5678", slice.body.decodeToString())
        assertEquals(8L, slice.totalLength)
        val sent = server.requests.single()
        assertEquals("bytes=4-", sent.header("Range"))
        assertEquals("Basic secret", sent.header("Authorization"))
    }

    @Test
    fun `T02 未知长度不伪造 Content-Length`() {
        assertEquals(8L, OkHttpRangeHttpStack.totalLength("bytes 4-7/8", null, 4, 206))
        assertEquals(-1L, OkHttpRangeHttpStack.totalLength(null, null, 4, 200))
        assertEquals(12L, OkHttpRangeHttpStack.totalLength(null, "12", 0, 200))
        assertEquals(-1L, OkHttpRangeHttpStack.totalLength(null, "-1", 0, 200))
    }

    @Test
    fun `T03 redirect 先取 302 再对最终地址发 Range`() = withServer { server ->
        server.handle("/origin") { it.respond(302, null, "Location" to "/final") }
        server.handle("/final") { it.respond(200, "abcdef") }
        val slice = OkHttpRangeHttpStack(client).fetch(server.url("/origin"), emptyList(), 0, true)
        assertEquals("abcdef", slice.body.decodeToString())
        assertEquals(6L, slice.totalLength)
        val final = server.requests.single { it.path == "/final" }
        assertEquals("bytes=0-", final.header("Range"))
    }

    @Test
    fun `T04 HEAD 探测 2xx 通过 404 拒绝 且不携带鉴权头`() = withServer { server ->
        server.handle("/ok") { it.respond(200, null) }
        server.handle("/missing") { it.respond(404, null) }
        val probe = OkHttpHeadProbe(client)
        runBlocking {
            assertTrue(probe.preProbe(server.url("/ok")))
            assertFalse(probe.preProbe(server.url("/missing")))
            assertFalse(probe.preProbe("not-a-url"))
        }
        assertTrue(server.requests.none { it.header("Authorization") != null })
    }

    @Test
    fun `T05 描述抓取只有 200 才返回 body`() = withServer { server ->
        server.handle("/desc.xml") { it.respond(200, "<root><device/></root>") }
        server.handle("/404") { it.respond(404, "nope") }
        val fetcher = OkHttpDescriptionFetcher(client)
        runBlocking {
            assertEquals("<root><device/></root>", fetcher.get(server.url("/desc.xml")))
            assertEquals(null, fetcher.get(server.url("/404")))
        }
    }

    @Test
    fun `T06 SOAPACTION 原样带引号且返回错误体`() = withServer { server ->
        server.handle("/control") { it.respond(500, "<UPnPError><errorCode>714</errorCode></UPnPError>") }
        val transport = OkHttpSoapTransport(client)
        val response = runBlocking {
            transport.post(
                com.example.cloudmusic.output.dlna.SoapRequest(
                    url = server.url("/control"),
                    soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
                    body = "<s:Envelope/>",
                ),
            )
        }
        assertEquals(500, response.status)
        assertTrue(response.body.contains("714"))
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            server.requests.single().header("SOAPACTION"),
        )
    }

    // ---- C-07：PROXIED 回源链路 ----------------------------------------------

    @Test
    fun `T07 PROXIED 经 proxySourceUrl 回源并支持 Range`() = withServer { server ->
        server.handle("/dav/a.flac") { ex ->
            assertEquals("Basic secret", ex.header("Authorization"))
            if (ex.header("Range") == "bytes=4-") {
                ex.respond(206, "5678", "Content-Range" to "bytes 4-7/8")
            } else {
                ex.respond(200, "12345678")
            }
        }
        val link = Link(
            proxy = true,
            headers = listOf(Header("Authorization", "Basic secret")),
            requireReference = true,
            proxySourceUrl = server.url("/dav/a.flac"),
        )
        assertEquals(Link.Kind.PROXIED, link.kind)

        val source = OkHttpProxiedStreamFactory(client).open(link)
        assertEquals(8L, source.open(0))
        val buf = ByteArray(4)
        assertEquals(4, source.readAt(4, buf, 4))
        assertEquals("5678", buf.decodeToString())
        source.close()
    }

    @Test
    fun `T08 缺 proxySourceUrl 的 PROXIED 显式失败`() {
        val factory = OkHttpProxiedStreamFactory(client)
        val error = assertFailsWith<CloudMusicError> { factory.open(Link(proxy = true)) }
        assertEquals("E_DS_UNREACHABLE", error.code)
        assertTrue(error.message!!.contains("C-07"))
    }

    @Test
    fun `T09 生产 DataSourceFactory 四态分派`() {
        val factory = DataSourceFactory.production(
            http = OkHttpRangeHttpStack(client),
            fd = JvmFileFdOpener(),
            streams = OkHttpProxiedStreamFactory(client),
        )
        assertNotNull(factory.create(Link(text = "x", proxy = true), LinkArgs()))
        assertNotNull(factory.create(Link(url = "https://example.com/a.mp3"), LinkArgs()))
        // LOCAL 与 PROXIED 只验类型分派，不触发真实 IO（open 才发请求）
        assertNotNull(factory.create(Link(localPath = "/tmp/a.mp3"), LinkArgs()))
        assertNotNull(factory.create(Link(proxy = true), LinkArgs()))
    }

    @Test
    fun `T10 本地代理每请求独立 IDataSource`() {
        var opens = 0
        var closes = 0
        val proxy = InMemoryLocalHttpStreamProxy(
            dataSourceFactory = { _ ->
                opens++
                object : com.example.cloudmusic.model.IDataSource {
                    override fun open(offset: Long): Long = 4
                    override fun readAt(offset: Long, buf: ByteArray, len: Int): Int = 0
                    override fun close() { closes++ }
                    override fun mimeHint(): String = "audio/mpeg"
                }
            },
        )
        val url = proxy.publish("track-1", Link(url = "https://example.com/a.mp3"))
        val token = url.substringAfterLast('/')
        repeat(2) { proxy.serve(ProxyRequest(ProxyMethod.GET, "/t/$token")) }
        assertEquals(2, opens)
        assertEquals(2, closes)
    }

    // ---- 组合端口 ------------------------------------------------------------

    @Test
    fun `T11 CastProxyPort 未 publish 时显式报错`() {
        val delegate = InMemoryLocalHttpStreamProxy(dataSourceFactory = { TODO("不应触发 IO") })
        val castProxy = PrePublishedCastProxy(InMemoryCastProxyBackend(delegate))
        assertFailsWith<CloudMusicError> { castProxy.urlFor("missing") }
        castProxy.publish("t1", Link(url = "https://example.com/a.mp3"))
        assertTrue(castProxy.urlFor("t1").startsWith("http://127.0.0.1:0/t/"))
        assertTrue(castProxy.isRunning())
        castProxy.stop()
        assertFalse(castProxy.isRunning())
    }

    @Test
    fun `T12 LinkProvider 无驱动时按 SourceRef url 重建`() = runBlocking {
        val track = Track(
            id = TrackId("t1"), title = "晴天", artist = "周杰伦", album = "叶惠美",
            durationMs = 0, sourceRef = SourceRef("nas", "/a.strm", "https://example.com/a.mp3"),
            sourceType = SourceType.STRM,
        )
        val link = RegistryLinkProvider().link(track)
        assertEquals(Link.Kind.URL, link.kind)
        assertEquals("https://example.com/a.mp3", link.url)
        val failed = runCatching {
            RegistryLinkProvider().link(track.copy(sourceRef = SourceRef("nas", "/b")))
        }
        assertTrue(failed.exceptionOrNull() is CloudMusicError)
    }

    @Test
    fun `T13 保守直链：URL 直推 PROXIED 走代理`() = runBlocking {
        val provider = ConservativeDirectLinkProvider()
        assertEquals("https://example.com/a.mp3", provider.directUrl(Link(url = "https://example.com/a.mp3")))
        assertEquals(null, provider.directUrl(Link(proxy = true)))
    }

    @Test
    fun `T14 SSDP 端口按 USN 去重且 MulticastLock 成对释放`() {
        var acquired = 0
        var released = 0
        val transport = object : MulticastTransport {
            val payloads = mutableListOf<String>()
            override fun send(payload: ByteArray) { payloads += payload.decodeToString() }
            override fun receive(timeoutMs: Int): List<String> = listOf(
                "HTTP/1.1 200 OK\r\nUSN: uuid:1\r\nLOCATION: http://127.0.0.1/desc.xml\r\n",
                "HTTP/1.1 200 OK\r\nUSN: uuid:1\r\nLOCATION: http://127.0.0.1/desc.xml\r\n",
            )
        }
        val lock = object : MulticastLockPort {
            override fun acquire() { acquired++ }
            override fun release() { released++ }
        }
        val devices = SsdpScanner(transport, lock).discover(50)
        assertEquals(1, devices.size)
        assertEquals("uuid:1", devices.single().usn)
        assertEquals(2, SsdpDiscovery.SEARCH_TARGETS.size)
        assertEquals(1, acquired)
        assertEquals(1, released)
    }

    // ---- 本地回环测试服务（纯 java.net，AGP JVM 单测无 jdk.httpserver） ----------

    /** 一次请求的快照 + 可回写响应的 socket；测试 lambda 直接用它 header() / respond()。 */
    private class Req(
        private val socket: Socket,
        private val method: String,
        val path: String,
        private val headers: Map<String, String>,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /** HEAD 恒无 body；302 / 404 用 null body；Connection: close 让 OkHttp 不复用连接。 */
        fun respond(code: Int, body: String?, vararg extra: Pair<String, String>) {
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder("HTTP/1.1 $code ${reasonOf(code)}\r\n")
            extra.forEach { head.append(it.first).append(": ").append(it.second).append("\r\n") }
            head.append("Content-Length: ${if (method == "HEAD" || bytes == null) 0 else bytes.size}\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD" && bytes != null) out.write(bytes)
            out.flush()
            socket.close()
        }

        private fun reasonOf(code: Int): String = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            302 -> "Found"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }

    private class TestServer : AutoCloseable {
        private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        private val handlers = ConcurrentHashMap<String, (Req) -> Unit>()
        val requests = Collections.synchronizedList(mutableListOf<Req>())

        init {
            Thread({ acceptLoop() }, "cm-test-http").apply { isDaemon = true; start() }
        }

        fun handle(path: String, block: (Req) -> Unit) {
            handlers[path] = block
        }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
                Thread({ serve(socket) }, "cm-test-http-conn").apply { isDaemon = true; start() }
            }
        }

        private fun serve(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val line = reader.readLine() ?: return
                val parts = line.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    val idx = header.indexOf(':')
                    if (idx > 0) headers[header.substring(0, idx).trim()] = header.substring(idx + 1).trim()
                }
                val bodyLength = headers.entries
                    .firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                repeat(bodyLength) { reader.read() } // 丢弃请求体（SOAP POST），避免粘到下次读
                val req = Req(socket, method, path, headers)
                requests += req
                handlers[path]?.invoke(req) ?: req.respond(404, null)
            } catch (_: IOException) {
                runCatching { socket.close() }
            }
        }

        override fun close() = server.close()
    }

    private fun withServer(block: (TestServer) -> Unit) {
        TestServer().use { block(it) }
    }
}
