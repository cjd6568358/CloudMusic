package com.example.cloudmusic.enginelegacy

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 12 §54（第四十五批 · `12 §50.7 #1` 阶段 A/B）· native 取流的**纯 JVM 逻辑**。
 *
 * 实证依据（`analysis/data-request-handler-evidence-2026-11-07.md`）：
 * `createDataRequestHandler()` 返回 `SourceDataRequest` 子类，native 回调
 * `Start(requestId, offset, length)` —— 其中 `offset` 是 `k()` 的 `p4`、
 * `end = offset + length` 是 `p6`，`requestId` 是 `p12` 并被原样带回所有
 * `OnDataRequest*` 的首参。本对象把这层协议做成不继承 native 类、不碰 Android
 * 框架的纯函数，因此能在 `:engine-legacy` 的 JVM 单测里完整覆盖。
 *
 * **边界（12 §59 已校准）**：本类只负责纯 JVM 取流与分块，`requestId` 仅用于
 * 日志对账；native 侧各回调长参的真实语义（首参 = kind/status 槽，`OnDataRequestData`
 * 的 j2/j3 = 文件绝对 offset / 长度）由 [UrlDataRequest] 的 sink 映射层统一处理，
 * 实证依据见该类注释与 `analysis/a3-phase-ab-evidence-2026-11-07.md §8`。
 */
fun interface RangeOpener {
    /** 打开一次 Range 读取；调用方负责关闭 [RangeResponse.body]。 */
    fun open(url: String, offset: Long, length: Long): RangeResponse
}

data class RangeResponse(
    val body: InputStream,
    /** `Content-Range` 的资源总长；未知为 -1。 */
    val totalLength: Long = -1L,
    /** 本次响应体长度；未知为 -1。 */
    val contentLength: Long = -1L,
)

/** 与 `SourceDataRequest.OnDataRequestBegin/Data/End` 一一对应的回传槽。 */
interface RangeSink {
    fun onBegin(requestId: Long, totalLength: Long)
    fun onData(requestId: Long, bytes: ByteArray, offset: Long, count: Long)
    fun onEnd(requestId: Long, error: String?)
}

object RangeFetcher {

    const val CHUNK = 16 * 1024

    /** `Range: bytes=<offset>-<offset+length-1>`；length ≤ 0 时退化为开放式区间。 */
    fun rangeHeader(offset: Long, length: Long): String =
        if (length > 0) "bytes=$offset-${offset + length - 1}" else "bytes=$offset-"

    /** `Content-Range: bytes 0-999/37824` → 37824；无起点形式（星号 + 斜杠 + 总长）同样解析；无法解析 → -1。 */
    fun parseTotal(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        val total = contentRange.substringAfterLast('/', "").trim()
        return total.toLongOrNull()?.takeIf { it >= 0L } ?: -1L
    }

    /**
     * 同步执行一次 Range 取流。**任何异常都转成 [RangeSink.onEnd] 的 error，不向上冒泡**
     * （native 回调线程不能被 Java 异常穿透，§50 的三道 CheckJNI abort 教训）。
     * 返回实际回传的字节数。
     */
    fun fetch(
        url: String,
        requestId: Long,
        offset: Long,
        length: Long,
        opener: RangeOpener,
        sink: RangeSink,
        cancelled: () -> Boolean = { false },
    ): Long {
        var delivered = 0L
        try {
            val response = opener.open(url, offset, length)
            sink.onBegin(requestId, response.totalLength)
            response.body.use { input ->
                val buffer = ByteArray(CHUNK)
                while (!cancelled()) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    // HTTP 服务器可能忽略 Range 一次返回更多字节；按请求区间截断，
                    // 否则会多回传数据导致 native 侧 OnWriteNetData SKIP / 缓存错位。
                    val allowed = if (length > 0) {
                        minOf(n.toLong(), length - delivered).toInt()
                    } else {
                        n
                    }
                    if (allowed <= 0) break
                    // copyOf：native 可能异步持有 byte[]，复用缓冲区会造成数据错乱。
                    sink.onData(requestId, buffer.copyOf(allowed), offset + delivered, allowed.toLong())
                    delivered += allowed
                    if (length > 0 && delivered >= length) break
                }
            }
            sink.onEnd(requestId, null)
        } catch (t: Throwable) {
            sink.onEnd(requestId, t.message ?: t.javaClass.simpleName)
        }
        return delivered
    }
}

/** V0.1 生产实现：JDK / Android 均有的 `HttpURLConnection`，不引 OkHttp 到照搬舱。 */
class HttpUrlRangeOpener(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : RangeOpener {

    override fun open(url: String, offset: Long, length: Long): RangeResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Range", RangeFetcher.rangeHeader(offset, length))
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            error("HTTP $status for $url (Range ${RangeFetcher.rangeHeader(offset, length)})")
        }
        val total = RangeFetcher.parseTotal(connection.getHeaderField("Content-Range"))
            .takeIf { it >= 0L } ?: connection.contentLengthLong
        return RangeResponse(connection.inputStream, total, connection.contentLengthLong)
    }
}
