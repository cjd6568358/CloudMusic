package com.example.cloudmusic.model

/**
 * 跨层契约（docs/03-01 §5），实现在 :engine-bridge（03-06 §7）。
 *
 * Link.kind → IDataSource 分派：
 *  URL → HttpDataSource / LOCAL → FileDataSource /
 *  PROXIED → DriverDataSource / TEXT → MemoryDataSource
 * 本机播放的 PROXIED 由驱动直接提供带鉴权流，不经 nanohttpd（架构 §5）。
 */
interface IDataSource {
    /** 打开并返回总长度；-1 表示未知长度。offset 为起始偏移（Range）。 */
    fun open(offset: Long): Long

    /** 从 offset 读最多 len 字节到 buf，返回实际字节数；EOF 返回 0，-1 表示错误。 */
    fun readAt(offset: Long, buf: ByteArray, len: Int): Int

    fun close()

    fun mimeHint(): String
}
