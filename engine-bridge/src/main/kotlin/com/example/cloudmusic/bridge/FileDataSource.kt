package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Link

/**
 * docs/03-06 §7 · LOCAL 态：经 [FdOpener] 端口随机读。
 * JVM 用 [JvmFileFdOpener]；Android 侧换成 `ContentResolver.openFileDescriptor` 即可，
 * 本类不依赖 Android 框架（16 §5.2 T-P2 纯 JVM 可先行）。
 */
class FileDataSource(
    link: Link,
    private val fdOpener: FdOpener,
) : BaseDataSource(link) {

    private var source: SeekableSource? = null

    override fun open(offset: Long): Long {
        val path = requireNotNull(link.localPath) { "LOCAL 态必须有 localPath" }
        val opened = fdOpener.open(path)
        source = opened
        total = if (link.localSize > 0) link.localSize else opened.size()
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        if (source == null) open(offset)
        return requireNotNull(source).readAt(offset, buf, len)
    }

    override fun closeInternal() {
        source?.close()
        source = null
    }
}
