package com.example.cloudmusic.engineopensource

import androidx.media3.extractor.ExtractorInput
import java.io.IOException

/**
 * §61.94/§61.97 测试基建：ByteArray 之上的最小 [ExtractorInput] 实现
 * （不引 media3-test-utils；抽象成员签名逐一对齐 1.11.1）。
 */
internal class BytesInput(private val data: ByteArray) : ExtractorInput {
    private var pos = 0L
    private val peek = ArrayDeque<Byte>()
    private var peekPos = 0

    override fun getPosition(): Long = pos

    /** 测试用：应用 RESULT_SEEK 的目标位置（真机由 ProgressiveMediaPeriod 执行）。 */
    fun seekTo(position: Long) {
        pos = position
        resetPeekPosition()
    }
    override fun getLength(): Long = data.size.toLong()

    private fun ensurePeeked(count: Int) {
        while (peek.size < count && peekPos < data.size) {
            peek.addLast(data[peekPos])
            peekPos++
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= data.size) return -1
        val n = minOf(length, (data.size - pos).toInt())
        for (i in 0 until n) buffer[offset + i] = data[pos.toInt() + i]
        pos += n
        return n
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        ensurePeeked(length)
        if (peek.size < length) {
            if (allowEndOfInput) return false
            throw IOException("EOF")
        }
        for (i in 0 until length) target[offset + i] = peek.removeFirst()
        pos += length
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        if (!readFully(target, offset, length, false)) throw IOException("EOF")
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        ensurePeeked(length)
        if (peek.size < length) {
            if (allowEndOfInput) return false
            throw IOException("EOF")
        }
        repeat(length) { peek.removeFirst() }
        pos += length
        return true
    }

    override fun skip(length: Int): Int {
        if (!skipFully(length, false)) throw IOException("EOF")
        return length
    }

    override fun skipFully(length: Int) {
        if (!skipFully(length, false)) throw IOException("EOF")
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        ensurePeeked(length)
        val n = minOf(length, peek.size)
        for (i in 0 until n) target[offset + i] = peek.elementAt(i)
        return n
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        ensurePeeked(length)
        if (peek.size < length) {
            if (allowEndOfInput) return false
            throw IOException("EOF")
        }
        for (i in 0 until length) target[offset + i] = peek.elementAt(i)
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        if (!peekFully(target, offset, length, false)) throw IOException("EOF")
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        ensurePeeked(length)
        if (peek.size < length) {
            if (allowEndOfInput) return false
            throw IOException("EOF")
        }
        repeat(length) { peek.removeFirst() }
        peekPos += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        if (!advancePeekPosition(length, false)) throw IOException("EOF")
    }

    override fun resetPeekPosition() {
        peek.clear()
        peekPos = pos.toInt()
    }

    override fun getPeekPosition(): Long = peekPos.toLong()

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        pos = position
        throw e
    }
}
