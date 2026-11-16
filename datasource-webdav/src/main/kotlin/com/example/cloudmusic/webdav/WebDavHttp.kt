package com.example.cloudmusic.webdav

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WebDavRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class WebDavResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    /**
     * C-12（12 §31）：二进制 Range 读的原始字节。`body` 是 String，会把 MP3/FLAC 头部
     * 的非 UTF-8 字节替换掉，因此 `ByteRangeCapability.readBytes` 必须走本字段。
     * PROPFIND 也顺带填充，避免为两种响应写两套读取路径。
     */
    val bodyBytes: ByteArray? = null,
)

fun WebDavResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/**
 * 可注入的 HTTP 抽象：生产用 [OkHttpStack]，单测用 fake，
 * 因此不需要真实网络、MockWebServer 或 Android 运行时。
 */
interface HttpStack {
    suspend fun execute(request: WebDavRequest): WebDavResponse
}

class OkHttpStack(private val client: OkHttpClient) : HttpStack {
    override suspend fun execute(request: WebDavRequest): WebDavResponse =
        suspendCancellableCoroutine { cont ->
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (key, value) -> builder.header(key, value) }
            val body = request.body?.toRequestBody("application/xml; charset=utf-8".toMediaType())
            builder.method(request.method, body)
            val call = client.newCall(builder.build())
            // B17：协程取消时立即 cancel OkHttp call，不留悬挂连接
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // §61.178（真机一手悬死根因）：整段必须 try-catch。
                    // `resp.body.bytes()` 抛 IOException / 协程已被取消 / 任何运行时异常
                    // 一旦逃出本回调，OkHttp 的 AsyncCall 只会把它吞掉（它假设 call 已处理完），
                    // 协程**永远不会 resume**——扫描表现为「正在扫描曲库 0/0」永久不动，
                    // 无异常、无超时、无日志（`binder sync` 只打过一次）。
                    try {
                        response.use { resp ->
                            val headers = mutableMapOf<String, String>()
                            for (i in 0 until resp.headers.size) {
                                headers[resp.headers.name(i)] = resp.headers.value(i)
                            }
                            if (cont.isActive) {
                                // C-12：只读一次 body（OkHttp 的响应体只能消费一次），
                                // 二进制与文本视图共用同一份字节。
                                val bytes = resp.body?.bytes()
                                cont.resume(
                                    WebDavResponse(
                                        status = resp.code,
                                        headers = headers,
                                        body = bytes?.toString(Charsets.UTF_8).orEmpty(),
                                        bodyBytes = bytes,
                                    ),
                                )
                            }
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
}
