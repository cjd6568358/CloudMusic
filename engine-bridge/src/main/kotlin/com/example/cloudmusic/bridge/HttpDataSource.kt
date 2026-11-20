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
        // §61.266：只读探测。旧实现 fetch(offset) 会把整个远端文件读进 ByteArray
        // （真机 259MB APE → OOM 闪退）；总长度 / MIME 用 HEAD 或 1 字节 Range 即可。
        val slice = http.probe(link.url, link.headers + args.headers, args.redirect)
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        // 每次只读调用方需要的那一小段（上限 64KiB），禁止 `bytes=offset-` 拉完整剩余文件。
        val slice = fetch(offset, (offset + len - 1L).coerceAtMost(offset + MAX_CHUNK - 1L))
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        if (slice.body.isEmpty()) return 0
        val count = minOf(len, slice.body.size)
        slice.body.copyInto(buf, destinationOffset = 0, startIndex = 0, endIndex = count)
        return count
    }

    override fun mimeHint(): String = MimeHint.of(link, overrideMime)

    override fun closeInternal() = Unit

    private fun fetch(offset: Long, rangeEnd: Long?) = http.fetch(
        url = link.url,
        headers = link.headers + args.headers,
        rangeStart = offset,
        redirect = args.redirect,
        rangeEnd = rangeEnd,
    )

    private companion object {
        const val MAX_CHUNK = 64 * 1024
    }
}
