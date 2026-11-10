package com.example.cloudmusic.bridge

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import java.util.Locale

/**
 * docs/03-06 §7.1 统一实现骨架，逐字对齐并补足线程安全：
 * `readAt` 可能被内核解码线程调用，而 `close()` 来自 EngineThread，故两者都加 [Synchronized]。
 *
 * - `readAt` 返回 `0` = EOF、`-1` = 错误；**上层不得把 -1 当 EOF**（§15 T05）。
 * - 异常统一转成 [CloudMusicError]（07 §8：不把 IOException 原样抛给 UI），记录在 [lastError]。
 * - `close()` 幂等；`requireReference=true` 的句柄由各实现的 `closeInternal()` 释放。
 */
abstract class BaseDataSource(protected val link: Link) : IDataSource {

    protected var total: Long = -1L

    @Volatile
    protected var closed = false

    /** 最近一次取流错误，供上层决定「自动跳下一首」还是提示（T05 错误分支）。 */
    @Volatile
    var lastError: CloudMusicError? = null
        private set

    @Synchronized
    final override fun readAt(offset: Long, buf: ByteArray, len: Int): Int {
        check(!closed) { "DataSource 已关闭" }
        require(offset >= 0) { "offset 不得为负" }
        require(len >= 0 && len <= buf.size) { "len 超出 buf 容量" }
        if (len == 0) return 0
        return try {
            readAtInternal(offset, buf, len)
        } catch (e: Exception) {
            lastError = e.toCloudMusicError()
            -1
        }
    }

    protected abstract fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int

    @Synchronized
    final override fun close() {
        if (!closed) {
            closed = true
            closeInternal()
        }
    }

    protected abstract fun closeInternal()

    override fun mimeHint(): String = MimeHint.of(link)

    private fun Throwable.toCloudMusicError(): CloudMusicError = when (this) {
        is CloudMusicError -> this
        else -> CloudMusicError.DsUpstream(
            "IO", message?.take(120) ?: this::class.java.simpleName,
        )
    }
}

/** 03-06 §7：mimeHint 按扩展名推断；`Link.headers` / 端口返回的 Content-Type 优先。 */
object MimeHint {
    // 12 §61.45 / F1：扩展名 → MIME 唯一映射表，统一取自 AudioFormats（03-06 §7 口径）。
    private val MAP: Map<String, String> = com.example.cloudmusic.model.AudioFormats.MIME_BY_EXT

    fun extOf(link: Link): String {
        val raw = link.localPath?.takeIf { it.isNotBlank() }
            ?: link.url.substringBefore('?').substringAfterLast('/', "")
        return raw.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    fun of(link: Link, override: String? = null): String =
        override?.takeIf { it.isNotBlank() }
            ?: MAP[extOf(link)]
            ?: com.example.cloudmusic.model.AudioFormats.OCTET_STREAM
}
