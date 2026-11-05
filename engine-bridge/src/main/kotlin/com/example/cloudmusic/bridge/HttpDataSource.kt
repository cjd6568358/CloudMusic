package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs

/**
 * docs/03-06 §7 · URL 态：透传 `Link.headers` + `LinkArgs.headers`，
 * 每次读取发 `Range: bytes={offset}-`（§7.2 允许重连，但复用同一 [Link]）。
 * 生产实现由 OkHttp 4.x 注入 [RangeHttpStack]（302 跟随、10s 连接 / 30s 读超时）；
 * 本模块不触网（T-P2 纯 JVM 先行）。
 */
class HttpDataSource(
    link: Link,
    private val args: LinkArgs,
    private val http: RangeHttpStack,
) : BaseDataSource(link) {

    private var overrideMime: String? = null

    override fun open(offset: Long): Long {
        // 探测总长度；-1 表示 chunked / 无 Content-Range，UI 进度条应显示不可拖动。
        val slice = fetch(offset)
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        val slice = fetch(offset)
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        if (slice.body.isEmpty()) return 0
        val count = minOf(len, slice.body.size)
        slice.body.copyInto(buf, destinationOffset = 0, startIndex = 0, endIndex = count)
        return count
    }

    override fun mimeHint(): String = MimeHint.of(link, overrideMime)

    override fun closeInternal() = Unit

    private fun fetch(offset: Long) = http.fetch(
        url = link.url,
        headers = link.headers + args.headers,
        rangeStart = offset,
        redirect = args.redirect,
    )
}
