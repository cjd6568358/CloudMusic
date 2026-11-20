package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.bridge.HttpSlice
import com.example.cloudmusic.bridge.RangeHttpStack
import com.example.cloudmusic.model.Header
import com.example.cloudmusic.output.dlna.DescriptionFetcher
import com.example.cloudmusic.output.dlna.HeadProbe
import com.example.cloudmusic.output.dlna.SoapRequest
import com.example.cloudmusic.output.dlna.SoapResponse
import com.example.cloudmusic.output.dlna.SoapTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * docs/16 §5.3 T-P2 / T-P3 端口的 **OkHttp 生产实现**（第十六批，`12 §25`）。
 *
 * 纯 JVM 可编译可测：本文件不引用任何 Android 框架类，网络全部经 OkHttp 注入，
 * 与 `12 §11.3` 的 `OkHttpStack` / `12 §22` 的端口隔离同型。
 * 日志与异常信息一律不含 URL 查询串、鉴权头与凭据（`07 §8` / `03-01 §14 #6`）。
 */

/** 03-06 §7.2：每次读取发 `Range: bytes={start}-`；302 预解析与总长度推断都在此收口。 */
class OkHttpRangeHttpStack(private val client: OkHttpClient) : RangeHttpStack {

    override fun fetch(
        url: String,
        headers: List<Header>,
        rangeStart: Long,
        redirect: Boolean,
        rangeEnd: Long?,
    ): HttpSlice {
        val target = if (redirect) resolveRedirect(url, headers) else url
        val rangeValue = if (rangeEnd == null) "bytes=$rangeStart-" else "bytes=$rangeStart-$rangeEnd"
        val builder = Request.Builder().url(target).header("Range", rangeValue)
        headers.forEach { builder.header(it.name, it.value) }
        client.newCall(builder.get().build()).execute().use { resp ->
            if (resp.code != 206 && !resp.isSuccessful) {
                throw IOException("音源 HTTP ${resp.code}") // 不回显 URL / headers
            }
            // §61.266：一律流式读，最多读到请求的那一段。服务端忽略 Range 返回 200 整段时，
            // 先流式跳过 rangeStart 再截断，绝不 `body.bytes()` 把 259MB 整文件装进堆。
            val stream = resp.body?.byteStream()
            val skip = if (rangeStart > 0 && resp.code != 206) rangeStart else 0L
            if (stream != null && skip > 0) skipFully(stream, skip)
            val limit = when {
                rangeEnd == null -> Long.MAX_VALUE // 历史口径：读到尾部，但受 MAX_BODY_BYTES 兜底
                rangeEnd < rangeStart -> 0L
                else -> rangeEnd - rangeStart + 1
            }
            val body = if (stream == null || limit <= 0L) ByteArray(0) else readAtMost(stream, limit)
            val total = totalLength(
                resp.header("Content-Range"),
                resp.header("Content-Length"),
                rangeStart,
                resp.code,
            )
            return HttpSlice(body, total, resp.header("Content-Type"))
        }
    }

    /**
     * §61.266：探测**只要元数据**。HEAD 优先；服务器不支持 HEAD（405/501）时退化为
     * `Range: bytes=0-0`（1 字节），绝不整文件 GET。
     */
    override fun probe(url: String, headers: List<Header>, redirect: Boolean): HttpSlice {
        val target = if (redirect) resolveRedirect(url, headers) else url
        val builder = Request.Builder().url(target).head()
        headers.forEach { builder.header(it.name, it.value) }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful || resp.code == 206) {
                val total = totalLength(
                    resp.header("Content-Range"), resp.header("Content-Length"), 0L, resp.code,
                )
                return HttpSlice(ByteArray(0), total, resp.header("Content-Type"))
            }
        }
        return fetch(target, headers, 0L, false, 0L)
    }

    /** 流式跳过 [count] 字节（服务端忽略 Range 时的兜底），不占用等量堆内存。 */
    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    /** 至多读 [limit] 字节；先收敛到 [MAX_BODY_BYTES] 再转 Int，避免 `toInt()` 溢出。 */
    private fun readAtMost(stream: InputStream, limit: Long): ByteArray {
        val cap = minOf(limit, MAX_BODY_BYTES).toInt()
        val out = ByteArrayOutputStream(minOf(cap, 16 * 1024))
        val scratch = ByteArray(16 * 1024)
        var remaining = cap.toLong()
        while (remaining > 0) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(scratch, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** `LinkArgs.redirect=true`：先取 302 Location，再用最终 URL 发 Range 请求（03-02 WebDAV）。 */
    private fun resolveRedirect(url: String, headers: List<Header>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { builder.header(it.name, it.value) }
        client.newBuilder().followRedirects(false).build()
            .newCall(builder.get().build()).execute().use { resp ->
                if (resp.code !in 300..399) return url
                val location = resp.header("Location") ?: return url
                return runCatching { URI(url).resolve(location).toString() }.getOrDefault(location)
            }
    }

    companion object {
        /**
         * §61.266 兜底：`fetch` 未带 `rangeEnd`（历史口径）时单次最多读 8 MiB。
         * 正常播放路径由 `HttpDataSource` 显式传 `rangeEnd`（≤64 KiB），不会触顶。
         */
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024L

        /**
         * 总长度口径（03-08 §8.2）：`Content-Range: bytes a-b/total` 优先；
         * 其余只在起始偏移为 0 且服务端给了 `Content-Length` 时采用，**绝不伪造长度**。
         */
        fun totalLength(
            contentRange: String?,
            contentLength: String?,
            rangeStart: Long,
            statusCode: Int = 200,
        ): Long {
            contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?.takeIf { it > 0 }?.let { return it }
            if (rangeStart > 0 || statusCode == 206) return -1L
            return contentLength?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
        }
    }
}

/** 03-08 §3.2 `preProbe`：不带任何鉴权头的匿名 HEAD，2s 超时，2xx/3xx 才算通过。 */
class OkHttpHeadProbe(private val client: OkHttpClient) : HeadProbe {
    override suspend fun preProbe(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val probe = client.newBuilder()
                .callTimeout(2, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            probe.newCall(Request.Builder().url(url).head().build()).execute().use {
                it.code in 200..399
            }
        }.getOrDefault(false)
    }
}

/** 03-08 §4.2 / §5：GET 设备描述，只有 200 才返回 body，失败返回 null 让探测继续下一个候选。 */
class OkHttpDescriptionFetcher(private val client: OkHttpClient) : DescriptionFetcher {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.code == 200) resp.body?.string() else null
            }
        }.getOrNull()
    }
}

/** 03-08 §6.2：SOAPACTION 必须原样带引号，Content-Type 为 text/xml; charset="utf-8"。 */
class OkHttpSoapTransport(private val client: OkHttpClient) : SoapTransport {
    override suspend fun post(request: SoapRequest): SoapResponse = withContext(Dispatchers.IO) {
        val contentType = "text/xml; charset=\"utf-8\""
        val http = Request.Builder()
            .url(request.url)
            .header("SOAPACTION", request.soapAction)
            .header("CONTENT-TYPE", contentType)
            .post(request.body.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(http).execute().use { resp ->
            SoapResponse(resp.code, resp.body?.string().orEmpty())
        }
    }
}
