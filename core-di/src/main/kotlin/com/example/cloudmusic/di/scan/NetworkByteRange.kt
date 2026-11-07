package com.example.cloudmusic.di.scan

import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.Obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * C-12（`12 §31` / `03-04 §3`）· 网络音源 ByteRange 的纯 JVM 策略与 URL 态生产实现。
 *
 * `HeadTagReader` 的 `rangeFor` 只需要一个 [ByteRangeCapability]，并不要求它就是 Driver：
 * - WebDAV / 本地挂载 → 由 Driver 自身实现（[com.example.cloudmusic.webdav.WebDavDriver]，
 *   复用 BASIC/DIGEST 协商与 `HttpStack`）；
 * - strm / 播放列表展开出的 **URL 条目没有可用 Driver 实例** → 由本文件的
 *   [UrlByteRangeSource] 按 [com.example.cloudmusic.scanner.Candidate.directUrl] 发 Range GET。
 *
 * 全部只读头部若干 KB，绝不下载整文件（03-04 §15 #1）。
 */
object ByteRangePolicy {
    /** 同目录封面也属于 Range 读取，复用 `CoverPolicy.MAX_EMBEDDED` 的 5MB 上限。 */
    const val MAX_COVER_BYTES = 5 * 1024 * 1024

    fun header(offset: Long, length: Int): String = "bytes=$offset-${offset + length - 1}"

    /**
     * 206 = 服务端已按区间返回；200 = 忽略 Range，只能本地切片。
     * 任一切片失败返回 null，交给扫描器降级（B01 / B16）。
     */
    fun slice(bytes: ByteArray, offset: Long, length: Int, partial: Boolean): ByteArray? {
        if (bytes.isEmpty()) return null
        if (partial) return bytes
        val from = offset.coerceAtMost(bytes.size.toLong()).toInt()
        val to = (offset + length).coerceAtMost(bytes.size.toLong()).toInt()
        return if (from >= to) null else bytes.copyOfRange(from, to)
    }
}

data class RangeHttpResult(val status: Int, val bytes: ByteArray?)

/** 把 HTTP 执行隔离成端口，纯 JVM 单测用 fake 即可覆盖 200/206/失败三条分支。 */
fun interface RangeRequester {
    suspend fun get(url: String, headers: Map<String, String>): RangeHttpResult
}

class OkHttpRangeRequester(private val client: OkHttpClient) : RangeRequester {
    override suspend fun get(url: String, headers: Map<String, String>): RangeHttpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).get()
                headers.forEach { (key, value) -> builder.header(key, value) }
                client.newCall(builder.build()).execute().use { response ->
                    RangeHttpResult(response.code, response.body?.bytes())
                }
            }.getOrDefault(RangeHttpResult(-1, null))
        }
}

/** C-12：URL 态音源的 [ByteRangeCapability]；失败返回 null 而不是抛异常。 */
class UrlByteRangeSource(
    private val url: String,
    private val headers: List<Header> = emptyList(),
    private val requester: RangeRequester,
) : ByteRangeCapability {
    override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        val requestHeaders = LinkedHashMap<String, String>()
        headers.forEach { requestHeaders[it.name] = it.value }
        requestHeaders["Range"] = ByteRangePolicy.header(offset, length)
        val result = runCatching { requester.get(url, requestHeaders) }
            .getOrNull() ?: return null
        if (result.status !in 200..299) return null
        val bytes = result.bytes ?: return null
        return ByteRangePolicy.slice(bytes, offset, length, partial = result.status == 206)
    }
}
