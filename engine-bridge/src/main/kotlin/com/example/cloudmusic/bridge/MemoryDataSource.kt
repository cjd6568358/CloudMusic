package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Link

/** docs/03-06 §7 · TEXT 态：`text.encodeToByteArray()`，按 offset 用 `copyInto` 切片。 */
class MemoryDataSource(link: Link) : BaseDataSource(link) {

    private val bytes = (link.text ?: "").encodeToByteArray()

    override fun open(offset: Long): Long {
        total = bytes.size.toLong()
        return total
    }

    override fun readAtInternal(offset: Long, buf: ByteArray, len: Int): Int {
        if (total < 0) open(offset)
        if (offset >= total) return 0
        val count = minOf(len.toLong(), total - offset).toInt()
        bytes.copyInto(buf, destinationOffset = 0, startIndex = offset.toInt(), endIndex = offset.toInt() + count)
        return count
    }

    override fun closeInternal() = Unit
}
