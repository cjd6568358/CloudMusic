package com.example.cloudmusic.output.localproxy

/**
 * docs/03-08 §8.2「响应头（DLNA 兼容关键）」的纯 JVM 实现。
 *
 * 熟轻库 nanohttpd 负责真正的 HTTP 传输；这里只做 **Range 解析 + 头部决策**，
 * 这样 T10 / T11 可以在无 Android 环境下精确断言。
 */
object RangeResponder {

    const val TOTAL_UNKNOWN = -1L
    private const val DLNA_CONTENT_FEATURES =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private val SINGLE_RANGE = Regex("""bytes=(\d+)-(\d*)""")

    /** `bytes=100-` / `bytes=0-99`；多段、后缀、非法一律返回 null → 整段 200。 */
    fun parse(header: String?): HttpRange? {
        if (header.isNullOrBlank() || header.contains(',')) return null
        val m = SINGLE_RANGE.matchEntire(header.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (end != null && end < start) return null
        return HttpRange(start, end)
    }

    fun respond(
        request: ProxyRequest,
        totalLength: Long,
        contentType: String = "application/octet-stream",
    ): ProxyResponse {
        val common = LinkedHashMap<String, String>()
        common["Content-Type"] = contentType
        common["Accept-Ranges"] = "bytes"
        common["transferMode.dlna.org"] = "Streaming"
        common["contentFeatures.dlna.org"] = DLNA_CONTENT_FEATURES
        if (request.getContentFeatures) {
            common["getcontentFeatures.dlna.org"] = "1"
        }

        val range = parse(request.rangeHeader)
        val partial = range != null && totalLength >= 0 && range.start < totalLength
        val head = request.method == ProxyMethod.HEAD

        if (partial) {
            val end = minOf(range!!.end ?: (totalLength - 1), totalLength - 1)
            val length = end - range.start + 1
            common["Content-Length"] = length.toString()
            common["Content-Range"] = "bytes ${range.start}-$end/$totalLength"
            return ProxyResponse(
                status = ProxyStatus.PARTIAL_CONTENT,
                headers = common,
                bodyLength = if (head) 0L else length,
            )
        }

        // 整段 200：未知长度不伪造 Content-Length（§8.2），交给 nanohttpd chunked
        if (totalLength >= 0) {
            common["Content-Length"] = totalLength.toString()
        } else {
            common["Transfer-Encoding"] = "chunked"
        }
        return ProxyResponse(
            status = ProxyStatus.OK,
            headers = common,
            bodyLength = if (head) 0L else if (totalLength >= 0) totalLength else TOTAL_UNKNOWN,
        )
    }

    fun notFound(): ProxyResponse = ProxyResponse(ProxyStatus.NOT_FOUND)

    fun methodNotAllowed(): ProxyResponse = ProxyResponse(ProxyStatus.METHOD_NOT_ALLOWED)
}
