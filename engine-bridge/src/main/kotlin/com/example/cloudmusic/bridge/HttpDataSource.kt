package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import java.io.IOException
import java.io.InputStream

/**
 * docs/03-06 §7 · URL 态：透传 `Link.headers` + `LinkArgs.headers`，
 * 每次读取发 `Range: bytes={offset}-`（§7.2 允许重连，但复用同一 [Link]）。
 * 生产实现由 OkHttp 4.x 注入 [RangeHttpStack]（302 跟随、10s 连接 / 30s 读超时）；
 * 本模块不触网（T-P2 纯 JVM 先行）。
 *
 * §61.302（H0-3）读模型改版：**顺序读续用已开上游流**（[openStream] 单条 TCP 流
 * 连续泵），只有非顺序（seek 回跳 / 头尾读）才重新开流。旧实现每次 readAt 物化
 * 一个 ≤64KiB ByteArray（= 每 64KiB 一个全新 HTTP GET + 握手/重定向往返），起播
 * 吞吐被钳在 64KiB/RTT（§61.269 实测 0.27~0.68s/64KiB ≈ 94~237KB/s，播 3s 卡 3.5s
 * 的结构根因）。OOM 防线不变：流式泵不物化（§61.266 的「有界物化」只保留在
 * [RangeHttpStack.fetch] 的随机读路径）。
 */
class HttpDataSource(
    link: Link,
    private val args: LinkArgs,
    private val http: RangeHttpStack,
) : BaseDataSource(link) {

    private var overrideMime: String? = null

    /** §61.302：已开上游顺序流；[streamNextOffset] = 该流下一字节的全局偏移（-1 = 无流）。 */
    private var streamBody: InputStream? = null
    private var streamNextOffset: Long = -1L

    override fun open(offset: Long): Long {
        // §61.266：只读探测。旧实现 fetch(offset) 会把整个远端文件读进 ByteArray
        // （真机 259MB APE → OOM 闪退）；总长度 / MIME 用 HEAD 或 1 字节 Range 即可。
        val slice = http.probe(link.url, link.headers + args.headers, args.redirect)
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        // §61.302：顺序命中 → 从已开流续泵（readAt 0 = EOF / -1 = 错误，§15 T05）。
        val body = streamBody
        if (body != null && streamNextOffset == offset) {
            val n: Int? = try {
                body.read(buf, 0, len)
            } catch (e: IOException) {
                null // 陈旧流（服务端空闲断开）→ 关流后走下方重新开流重试
            }
            if (n != null && n > 0) {
                streamNextOffset += n
                return n
            }
            closeStream()
            if (n != null) return 0 // 清洁 EOF：顺序流耗尽 = readAt 0 口径
        } else {
            closeStream()
        }
        // 非顺序 / 无流 / 陈旧流：重新开流（开尾 Range，顺序泵，不物化进堆）。
        val slice = http.openStream(link.url, link.headers + args.headers, offset, args.redirect)
        total = slice.totalLength
        overrideMime = slice.mimeHint ?: overrideMime
        streamBody = slice.body
        streamNextOffset = offset
        val n = slice.body.read(buf, 0, len)
        if (n > 0) streamNextOffset += n
        return if (n < 0) 0 else n
    }

    override fun mimeHint(): String = MimeHint.of(link, overrideMime)

    override fun closeInternal() = closeStream()

    /** §61.302：关流即释放上游响应连接（幂等）。 */
    private fun closeStream() {
        runCatching { streamBody?.close() }
        streamBody = null
        streamNextOffset = -1L
    }
}
