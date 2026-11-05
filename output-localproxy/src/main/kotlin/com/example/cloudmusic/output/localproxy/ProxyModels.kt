package com.example.cloudmusic.output.localproxy

import com.example.cloudmusic.model.Link

/** docs/03-08 §8.1 路由：DMR 常先发 HEAD，再发带 Range 的 GET。 */
enum class ProxyMethod { GET, HEAD }

enum class ProxyStatus(val code: Int, val reason: String) {
    OK(200, "OK"),
    PARTIAL_CONTENT(206, "Partial Content"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
}

data class ProxyRequest(
    val method: ProxyMethod,
    val path: String,
    val rangeHeader: String? = null,
    /** DMR 请求 `getcontentFeatures.dlna.org: 1` 时响应头里要回 DLNA 特征。 */
    val getContentFeatures: Boolean = false,
)

/**
 * 与 nanohttpd 无关的响应模型（便于纯 JVM 单测）。
 * [bodyLength] 是实际要写入的字节数；HEAD 恒为 0，未知长度时为 -1（chunked）。
 */
data class ProxyResponse(
    val status: ProxyStatus,
    val headers: Map<String, String> = emptyMap(),
    val bodyLength: Long = 0,
) {
    val reason: String get() = status.reason
}

/** §8.3：token 只映射队列内曲目，不暴露真实路径 / 凭据。 */
data class ProxyEntry(
    val trackId: String,
    val link: Link,
    val expiresAt: Long,
)

/** 单段 Range；多段与非法 Range 在 V0.1 回落整段 200。 */
data class HttpRange(val start: Long, val end: Long? = null)

/** §3.1 / §8.4 的决策开关。 */
data class ProxySettings(
    val preferDirectLink: Boolean = true,
    val forceWebdavProxy: Boolean = false,
)
