package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link

/**
 * docs/03-06 §7 / 03-01 §5 · PROXIED 态：包装驱动提供的带鉴权流。
 *
 * **本类不创建 nanohttpd**（§15 T03）：`16 §3.2` 口径下，本机播放由组合层
 * `proxy.publish` 生成回环 URL 供引擎自取，本地代理**每请求**再经本适配器回源。
 * `requireReference=true` 的底层句柄在 [closeInternal] 中随 delegate 一起释放（§7.2）。
 */
class DriverDataSource(
    link: Link,
    private val factory: DriverStreamFactory,
) : BaseDataSource(link) {

    private var delegate: IDataSource? = null

    override fun open(offset: Long): Long {
        val opened = factory.open(link)
        delegate = opened
        total = opened.open(offset)
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        if (delegate == null) open(offset)
        return requireNotNull(delegate).readAt(offset, buf, len)
    }

    override fun mimeHint(): String = delegate?.mimeHint() ?: super.mimeHint()

    override fun closeInternal() {
        delegate?.close()
        delegate = null
    }
}
