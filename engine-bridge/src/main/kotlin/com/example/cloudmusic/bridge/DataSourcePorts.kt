package com.example.cloudmusic.bridge

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import java.io.RandomAccessFile

/**
 * docs/03-06 §7 的**端口隔离**：三种外部能力都不在本模块实现，便于纯 JVM 单测注入 fake。
 * 16 §5.2 已确认这条路线（OkHttp / DatagramSocket / JDK DOM 均可纯 JVM；`FdOpener` 同 `TagReader` 端口）。
 */

/** 一次带 `Range: bytes={rangeStart}-{rangeEnd?}` 的 GET 结果。`totalLength = -1` 表示未知（chunked）。 */
data class HttpSlice(
    val body: ByteArray,
    val totalLength: Long,
    val mimeHint: String? = null,
)

/**
 * §61.302（H0-3）：顺序流式开流结果——[body] 是**未消费的响应体流**（连续泵，不物化进堆）。
 * 与 [HttpSlice]（一次性物化 ≤ 上限的 ByteArray）互补：随机读走物化、顺序读走流。
 */
data class HttpStreamSlice(
    val body: java.io.InputStream,
    val totalLength: Long,
    val mimeHint: String? = null,
)

/** HTTP 端口。生产由 OkHttp 4.x 实现（透传 headers、302 跟随、10s 连接 / 30s 读超时）。 */
fun interface RangeHttpStack {
    /**
     * [rangeEnd] 为 null 时保持历史口径（`bytes=start-`）；非 null 时**必须**只读这一小段，
     * 禁止把整段响应体读进 ByteArray（§61.266 真机 OOM 根因）。
     *
     * `fun interface` 的抽象方法不允许带默认值，调用方须显式传参。
     */
    fun fetch(
        url: String,
        headers: List<Header>,
        rangeStart: Long,
        redirect: Boolean,
        rangeEnd: Long?,
    ): HttpSlice

    /**
     * §61.302（H0-3）：顺序流式开流——`bytes={rangeStart}-` 的**未消费响应体**，
     * 供顺序读消费者（回环代理回源 / 扫描头尾读）单条 TCP 流连续泵，消除
     * 「每 64KiB 一个全新 HTTP GET」结构税（§61.269 实测 0.27~0.68s/64KiB）。
     * 实现体注意：服务端忽略 Range 回 200 时须**流式**跳过 rangeStart（一次 O(n)，
     * 不物化）；关闭返回的 [HttpStreamSlice.body] 即释放响应连接（OkHttp body 流语义）。
     * 默认实现退化为一次性 [fetch] 物化（旧实现 / 纯 JVM fake 零改动可用）。
     */
    fun openStream(
        url: String,
        headers: List<Header>,
        rangeStart: Long,
        redirect: Boolean,
    ): HttpStreamSlice =
        fetch(url, headers, rangeStart, redirect, null).let {
            HttpStreamSlice(
                java.io.ByteArrayInputStream(it.body),
                it.totalLength,
                it.mimeHint,
            )
        }

    /** 只探测总长度 / MIME；默认退化为 1 字节 Range，生产实现优先 HEAD。 */
    fun probe(url: String, headers: List<Header>, redirect: Boolean): HttpSlice =
        fetch(url, headers, 0L, redirect, 0L)
}

/** 可随机读的句柄。Android 侧换成 `contentResolver.openFileDescriptor(uri, "r")`。 */
interface SeekableSource {
    fun size(): Long
    fun readAt(offset: Long, buf: ByteArray, len: Int): Int
    fun close()
}

/** 端口：按路径打开句柄。JVM 用 [java.io.RandomAccessFile] 适配。 */
interface FdOpener {
    fun open(path: String): SeekableSource
}

class JvmFileFdOpener : FdOpener {
    override fun open(path: String): SeekableSource {
        val file = RandomAccessFile(path, "r")
        return object : SeekableSource {
            override fun size(): Long = file.length()
            @Synchronized
            override fun readAt(offset: Long, buf: ByteArray, len: Int): Int {
                file.seek(offset)
                return file.read(buf, 0, len).let { if (it < 0) 0 else it }
            }

            override fun close() = file.close()
        }
    }
}

/**
 * 端口：PROXIED 由驱动提供带鉴权的流（03-01 §5）。
 * **不创建 nanohttpd**（§15 T03）：本机播放的回环 URL 由组合层 `proxy.publish` 生成，
 * 代理每请求再经本端口回源（`16 §3.2`）。
 */
fun interface DriverStreamFactory {
    fun open(link: Link): IDataSource
}

/** 未注入端口时的显式占位：分派仍可进行（T01~T03 只验类型），实际取流会给出可判定的错误。 */
internal fun unavailableHttpStack(): RangeHttpStack = RangeHttpStack { _, _, _, _, _ ->
    throw CloudMusicError.DsUnreachable("未配置 RangeHttpStack（生产由 OkHttp 注入）")
}

internal fun unavailableDriverStreams(): DriverStreamFactory = DriverStreamFactory {
    throw CloudMusicError.DsUnreachable("未配置 DriverStreamFactory（PROXIED 需驱动提供流）")
}
