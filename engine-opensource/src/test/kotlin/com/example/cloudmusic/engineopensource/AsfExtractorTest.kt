package com.example.cloudmusic.engineopensource

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.asf.AsfExtractor
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.97 / M4.2 · AsfExtractor **纯 JVM** 解析验证：真机夹具 f3_wma.wma
 * （ASF/WMA v2，722,095B，120 包 / 45.4s / WAVEFORMATEX 实测布局）。
 *
 * 判据（离线可判）：sniff ✓ / StreamProps 解析（wftag=0x161 ch=2 rate=44100
 * align=5945）✓ / 120 包全投递 ✓ / 样本字节量 ≈ 数据区 ✓。
 * 诚实边界：JVM 解析 ≠ 真机出声（FFmpeg wmav2 软解 + AudioTrack 属真机判据）。
 */
@UnstableApi
class AsfExtractorTest {

    /** ByteArray 之上的最小 ExtractorInput 实现（§61.97 专用，不引 test-utils）。 */
    private class BytesInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0L
        private val peek = ArrayDeque<Byte>()
        private var peekPos = 0

        override fun getPosition(): Long = pos
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

        override fun skipFully(length: Int) {
            if (!skipFully(length, false)) throw IOException("EOF")
        }

        override fun skip(length: Int): Int {
            if (!skipFully(length, false)) throw IOException("EOF")
            return length
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

        override fun advancePeekPosition(length: Int) {
            if (!advancePeekPosition(length, false)) throw IOException("EOF")
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
    }

    private fun fixture(): ByteArray {
        // Gradle 测试工作目录 = 模块目录（engine-opensource/），夹具在仓库根 analysis/ 下。
        val p = File("../analysis/l3-f3-fixtures/f3_wma.wma")
            .takeIf { it.isFile } ?: File("analysis/l3-f3-fixtures/f3_wma.wma")
        assertTrue(p.isFile, "夹具缺失：${p.path}（先跑 tools/l3_f3_fixture_gen.py）")
        return p.readBytes()
    }

    @Test
    fun `sniff 识别 ASF 头`() {
        val input = BytesInput(fixture())
        assertTrue(AsfExtractor().sniff(input), "ASF Header GUID 应命中")
    }

    @Test
    fun `解析 StreamProps 并逐包投递 120 帧`() {
        val data = fixture()
        val extractor = AsfExtractor()
        val input = BytesInput(data)
        val rec = Recorder()
        extractor.init(rec)

        val holder = PositionHolder()
        var result = extractor.read(input, holder)
        var guard = 0
        while (result == Extractor.RESULT_CONTINUE && guard++ < 500) {
            result = extractor.read(input, holder)
        }
        assertEquals(Extractor.RESULT_END_OF_INPUT, result, "应走到 EOF")

        val f = rec.formats.single()
        assertEquals(44100, f.sampleRate, "采样率（WAVEFORMATEX 实测布局）")
        assertEquals(2, f.channelCount, "声道数")
        assertEquals("audio/x-wma", f.sampleMimeType, "WMA v1/v2 MIME")

        assertTrue(rec.seekMap != null)
        assertTrue(rec.seekMap!!.isSeekable, "定长包可 seek")
        assertTrue(rec.seekMap!!.durationUs > 0, "时长 > 0（play_duration）")
        assertEquals(120, rec.samples, "120 包全投递（FileProps.data_packets_count）")
        assertTrue(rec.sampleBytes > 700_000, "样本字节量 ≈ 数据区")
        assertTrue(rec.sampleTimesUs.last() > rec.sampleTimesUs.first(), "时间戳单调递增")
    }
}
