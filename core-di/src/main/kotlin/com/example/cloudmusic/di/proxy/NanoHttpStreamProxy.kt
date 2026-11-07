package com.example.cloudmusic.di.proxy

import com.example.cloudmusic.di.adapters.CastProxyBackend
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.output.localproxy.LocalHttpStreamProxy
import com.example.cloudmusic.output.localproxy.ProxyEntry
import com.example.cloudmusic.output.localproxy.ProxyMethod
import com.example.cloudmusic.output.localproxy.ProxyRequest
import com.example.cloudmusic.output.localproxy.ProxyResponse
import com.example.cloudmusic.output.localproxy.ProxyStatus
import com.example.cloudmusic.output.localproxy.RangeResponder
import com.example.cloudmusic.output.localproxy.TokenRegistry
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * C-13（`12 §31` / `03-08 §8`）· nanohttpd 本地流代理**本体**。
 *
 * 第十六批的 `InMemoryLocalHttpStreamProxy` 只承接「每请求独立 IDataSource」的纯 JVM 语义，
 * 生成的 URL 是 `http://127.0.0.1:0/t/{token}`，DMR 无法访问。本类补上：
 * - 绑定随机端口（`NanoHTTPD(0)`）+ **局域网 IP**（[LanAddressProvider]，不是回环地址）；
 * - 与 `publish()` 共享同一个 [TokenRegistry] 单例（此前内存实现自建实例）；
 * - `serve(session)` 复用 `RangeResponder` 的 206 / DLNA 头决策，并**真正按 Range 写字节**；
 * - `/cover/{token}` 供给 T-A4 落盘的 **WebP** 字节（MIME `image/webp`，不是 jpeg）；
 * - `CastProxyBackend.coverUrlFor()` 给 DIDL-Lite 的 `albumArtURI` 提供 URL。
 *
 * 硬约束不变：每个 HTTP 请求各自新建一个 [IDataSource]，响应结束由 [RangeInputStream.close]
 * 关闭，禁止跨请求共享（`10 §2 R8`）。
 */
fun interface LanAddressProvider {
    fun address(): String
}

class NanoHttpStreamProxy(
    private val registry: TokenRegistry,
    private val dataSourceFactory: (Link) -> IDataSource,
    private val coverProvider: (String) -> ByteArray?,
    private val lanAddress: LanAddressProvider,
) : NanoHTTPD(0), LocalHttpStreamProxy, CastProxyBackend {

    @Volatile
    private var running = false
    private val urls = ConcurrentHashMap<String, String>()
    private val tokens = ConcurrentHashMap<String, String>()

    // ---- 生命周期 ------------------------------------------------------------

    override fun ensureStarted() {
        if (running) return
        synchronized(this) {
            if (running) return
            start()
            running = true
        }
    }

    override fun stop() {
        super.stop()
        running = false
        registry.invalidateAll()
        urls.clear()
        tokens.clear()
    }

    override fun isRunning(): Boolean = running

    // ---- 发布与 URL ----------------------------------------------------------

    override fun publish(trackId: String, link: Link): String {
        ensureStarted()
        val token = registry.register(trackId, link)
        tokens[trackId] = token
        val url = "http://${lanAddress.address()}:$listeningPort/t/$token"
        urls[trackId] = url
        return url
    }

    override fun urlFor(trackId: String): String =
        urls[trackId] ?: error("track not published: $trackId")

    override fun coverUrlFor(trackId: String): String? {
        val token = tokens[trackId] ?: return null
        return "http://${lanAddress.address()}:$listeningPort/cover/$token"
    }

    // ---- 纯 JVM 语义入口（契约单测用） ---------------------------------------

    override fun serve(request: ProxyRequest): ProxyResponse {
        if (!running) return RangeResponder.notFound()
        val token = parseToken(request.path) ?: return RangeResponder.notFound()
        if (request.method != ProxyMethod.GET && request.method != ProxyMethod.HEAD) {
            return RangeResponder.methodNotAllowed()
        }
        val entry = registry.resolve(token) ?: return RangeResponder.notFound()
        if (request.path.startsWith(COVER_PREFIX)) return coverResponse(request, entry)
        val source = dataSourceFactory(entry.link)
        return try {
            val total = source.open(0)
            val mime = source.mimeHint().ifBlank { DEFAULT_MIME }
            RangeResponder.respond(request, total, mime)
        } finally {
            source.close()
        }
    }

    // ---- nanohttpd 入口（真实 HTTP 传输） ------------------------------------

    override fun serve(session: IHTTPSession): Response {
        val method = when (session.method) {
            Method.GET -> ProxyMethod.GET
            Method.HEAD -> ProxyMethod.HEAD
            else -> return newResponse(RangeResponder.methodNotAllowed(), null)
        }
        val headers = session.headers
        val request = ProxyRequest(
            method = method,
            path = session.uri,
            rangeHeader = headers.entries.firstOrNull {
                it.key.equals("range", ignoreCase = true)
            }?.value,
            getContentFeatures = headers.keys.any {
                it.equals("getcontentFeatures.dlna.org", ignoreCase = true)
            },
        )
        val token = parseToken(request.path) ?: return newResponse(RangeResponder.notFound(), null)
        val entry = registry.resolve(token) ?: return newResponse(RangeResponder.notFound(), null)

        if (request.path.startsWith(COVER_PREFIX)) {
            val decision = coverResponse(request, entry)
            val bytes = if (method == ProxyMethod.HEAD) ByteArray(0)
            else coverProvider(entry.trackId) ?: ByteArray(0)
            return newResponse(decision, ByteArrayInputStream(bytes))
        }

        // 每个请求独立开流；RangeInputStream.close() 在响应结束时关闭它。
        val source = dataSourceFactory(entry.link)
        return try {
            val total = source.open(0)
            val mime = source.mimeHint().ifBlank { DEFAULT_MIME }
            val decision = RangeResponder.respond(request, total, mime)
            val start = if (decision.status == ProxyStatus.PARTIAL_CONTENT) {
                RangeResponder.parse(request.rangeHeader)?.start ?: 0L
            } else 0L
            val bodyLength = if (method == ProxyMethod.HEAD) 0L else decision.bodyLength
            val stream = if (bodyLength == 0L) {
                source.close()
                ByteArrayInputStream(ByteArray(0))
            } else {
                RangeInputStream(source, start, if (bodyLength < 0) Long.MAX_VALUE else bodyLength)
            }
            newResponse(decision, stream)
        } catch (e: Exception) {
            source.close()
            newResponse(RangeResponder.notFound(), null)
        }
    }

    // ---- 内部 ---------------------------------------------------------------

    private fun parseToken(path: String): String? = when {
        path.startsWith(TRACK_PREFIX) -> path.removePrefix(TRACK_PREFIX)
        path.startsWith(COVER_PREFIX) -> path.removePrefix(COVER_PREFIX)
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** 03-08 §8.1：封面统一 WebP（03-04 §6.4 落盘格式）；HEAD 与 GET 头部一致。 */
    private fun coverResponse(request: ProxyRequest, entry: ProxyEntry): ProxyResponse {
        val bytes = coverProvider(entry.trackId)
            ?: return RangeResponder.notFound()
        return ProxyResponse(
            status = ProxyStatus.OK,
            headers = mapOf(
                "Content-Type" to "image/webp",
                "Content-Length" to bytes.size.toString(),
            ),
            bodyLength = if (request.method == ProxyMethod.HEAD) 0L else bytes.size.toLong(),
        )
    }

    private fun newResponse(decision: ProxyResponse, stream: InputStream?): Response {
        val status = when (decision.status) {
            ProxyStatus.OK -> Response.Status.OK
            ProxyStatus.PARTIAL_CONTENT -> Response.Status.PARTIAL_CONTENT
            ProxyStatus.NOT_FOUND -> Response.Status.NOT_FOUND
            ProxyStatus.METHOD_NOT_ALLOWED -> Response.Status.METHOD_NOT_ALLOWED
        }
        val contentLength = decision.headers["Content-Length"]?.toLongOrNull()
        val response = if (decision.headers.containsKey("Transfer-Encoding") && stream != null) {
            // 未知长度（strm 直链）：不伪造 Content-Length，交给 chunked。
            newChunkedResponse(status, decision.headers["Content-Type"] ?: DEFAULT_MIME, stream)
        } else {
            newFixedLengthResponse(
                status,
                decision.headers["Content-Type"] ?: DEFAULT_MIME,
                stream ?: ByteArrayInputStream(ByteArray(0)),
                contentLength ?: 0L,
            )
        }
        decision.headers.forEach { (key, value) -> response.addHeader(key, value) }
        return response
    }

    /** 按 Range 起点从 [IDataSource] 读字节；响应结束时连带关闭数据源。 */
    private class RangeInputStream(
        private val source: IDataSource,
        private val start: Long,
        private val length: Long,
    ) : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= length || len <= 0) return -1
            val want = minOf(len.toLong(), length - pos).toInt()
            val buf = ByteArray(want)
            val read = source.readAt(start + pos, buf, want)
            if (read <= 0) return -1
            val count = minOf(read, want)
            System.arraycopy(buf, 0, b, off, count)
            pos += count
            return count
        }

        override fun close() = source.close()
    }

    private companion object {
        const val TRACK_PREFIX = "/t/"
        const val COVER_PREFIX = "/cover/"
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
