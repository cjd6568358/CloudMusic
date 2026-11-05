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

/** 一次带 `Range: bytes={rangeStart}-` 的 GET 结果。`totalLength = -1` 表示未知（chunked）。 */
data class HttpSlice(
    val body: ByteArray,
    val totalLength: Long,
    val mimeHint: String? = null,
)

/** HTTP 端口。生产由 OkHttp 4.x 实现（透传 headers、302 跟随、10s 连接 / 30s 读超时）。 */
fun interface RangeHttpStack {
    fun fetch(url: String, headers: List<Header>, rangeStart: Long, redirect: Boolean): HttpSlice
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
internal fun unavailableHttpStack(): RangeHttpStack = RangeHttpStack { _, _, _, _ ->
    throw CloudMusicError.DsUnreachable("未配置 RangeHttpStack（生产由 OkHttp 注入）")
}

internal fun unavailableDriverStreams(): DriverStreamFactory = DriverStreamFactory {
    throw CloudMusicError.DsUnreachable("未配置 DriverStreamFactory（PROXIED 需驱动提供流）")
}
