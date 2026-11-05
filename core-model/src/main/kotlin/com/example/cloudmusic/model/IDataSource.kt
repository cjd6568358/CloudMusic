package com.example.cloudmusic.model

/**
 * 跨层契约（docs/03-01 §5），实现在 :engine-bridge（03-06 §7）。
 *
 * Link.kind → IDataSource 分派：
 *  URL → HttpDataSource / LOCAL → FileDataSource /
 *  PROXIED → DriverDataSource / TEXT → MemoryDataSource
 *
 * 2026-11-06 口径修正（`16 §3.2` / `13 §3`）：引擎舱**不消费 Java 字节流**，
 * `DataProvider.getUrl()` / `getLocalPath()` 由 `EngineSource`（C-05）直传，native 自取。
 * 本接口是「本地代理每请求回源」与「扫描期 Range 读」的原语：
 * PROXIED 本机播放由组合层 `proxy.publish` 转成回环 URL 供引擎自取，代理每请求再经本接口回源。
 */
interface IDataSource {
    /** 打开并返回总长度；-1 表示未知长度。offset 为起始偏移（Range）。 */
    fun open(offset: Long): Long

    /** 从 offset 读最多 len 字节到 buf，返回实际字节数；EOF 返回 0，-1 表示错误。 */
    fun readAt(offset: Long, buf: ByteArray, len: Int): Int

    fun close()

    fun mimeHint(): String
}
