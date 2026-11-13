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
            // §61.107：read / readFully / skipFully 必须共用同一游标。
            // 旧夹具的 readFully 走 peek 队列、read 直接按 pos 取，两者混用会让
            // 包对齐修复后的逐包投递错位（真机 DefaultExtractorInput 不会）。
            // peek 队列仅保留给 sniff 用的 peekFully（独立实例，不影响主读取）。
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw IOException("EOF")
            }
            for (i in 0 until length) target[offset + i] = data[pos.toInt() + i]
            pos += length
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            if (!readFully(target, offset, length, false)) throw IOException("EOF")
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            // §61.107：与 read / readFully 共用 pos（见上）。
            if (pos + length > data.size) {
                if (allowEndOfInput) return false
                throw IOException("EOF")
            }
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

        // §61.105 回归：单轨也必须 endTracks（缺它 → sampleQueuesBuilt 不置位 →
        // 真机 "Loading finished before preparation is complete"），且
        // SeekMap 只能在 header 就绪后下发一次。
        assertEquals(1, rec.endTracksCalls, "readHeader 就绪后 endTracks 恰好一次")
        assertEquals(1, rec.seekMapCalls, "SeekMap 恰好下发一次")
    }

    @Test
    fun `init 期不下发 SeekMap 与 endTracks（Ape 同型回归）`() {
        val rec = Recorder()
        val extractor = AsfExtractor()
        extractor.init(rec)
        assertEquals(0, rec.seekMapCalls, "init 期参数未就绪，不得下发 SeekMap")
        assertEquals(0, rec.endTracksCalls, "init 期不得 endTracks")
        assertEquals(null, rec.seekMap)

        val input = BytesInput(fixture())
        val holder = PositionHolder()
        val result = extractor.read(input, holder)   // 只跑 readHeader
        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertEquals(1, rec.endTracksCalls, "readHeader 就绪后立刻 endTracks")
        assertEquals(1, rec.seekMapCalls, "readHeader 就绪后立刻下发 SeekMap")
        assertTrue(rec.seekMap!!.isSeekable)
        assertTrue(rec.seekMap!!.durationUs > 0)
    }

    @Test
    fun `Media3 init 后先 seek(0,0) 仍重走 readHeader 并下发 endTracks`() {
        // §61.106 真机一手根因（`diag read enter state=1 pos=0`）：Media3 在
        // init() 之后、首次 read() 之前调用 seek(0, 0)。旧实现无条件把 state
        // 推到 STATE_READ_PACKETS，header 永不解析 → endTracks/seekMap 永不下发
        // → "Loading finished before preparation is complete"（wma ×2）。
        // 本例把该调用序列钉死为 JVM 回归，防止再次只测「init → read」路径。
        val rec = Recorder()
        val extractor = AsfExtractor()
        assertTrue(extractor.sniff(BytesInput(fixture())), "sniff 应命中 ASF 头")
        extractor.init(rec)
        extractor.seek(0L, 0L)
        val result = extractor.read(BytesInput(fixture()), PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertEquals(1, rec.endTracksCalls, "seek 抢跑后仍须重走 header 并 endTracks")
        assertEquals(1, rec.seekMapCalls, "SeekMap 仍恰好下发一次")
        assertTrue(rec.seekMap!!.isSeekable)
        assertTrue(rec.seekMap!!.durationUs > 0)
        assertTrue(rec.formats.isNotEmpty(), "Format 已下发")
    }

    @Test
    fun `WAVEFORMATEX 作为 initializationData 下发（wmav2 解码器参数前置）`() {
        // §61.107：wmadec.c 要求 WAVEFORMATEX 尾部 codec-specific 字节作
        // extradata，且 wma.c ff_wma_init 要求 block_align / bit_rate /
        // sample_rate / ch_layout 全部有效。AsfExtractor 必须把完整
        // WAVEFORMATEX 交给 ffmpeg_jni.cc 适配，否则真机 wma 初始化失败。
        val rec = Recorder()
        val extractor = AsfExtractor()
        extractor.init(rec)
        extractor.read(BytesInput(fixture()), PositionHolder())
        val init = rec.formats.single().initializationData
        assertTrue(init.isNotEmpty(), "WAVEFORMATEX 必须作为 initializationData 下发")
        val wfx = init[0]
        assertTrue(wfx.size > 18, "含 18B WAVEFORMATEX 头 + cbSize 尾部字节")
        val le16 = { i: Int -> (wfx[i].toInt() and 0xFF) or ((wfx[i + 1].toInt() and 0xFF) shl 8) }
        val le32 = { i: Int -> (0..3).fold(0L) { acc, k ->
            acc or ((wfx[i + k].toLong() and 0xFF) shl (8 * k)) } }
        assertEquals(0x161, le16(0), "wFormatTag = WMAv2")
        assertEquals(2, le16(2), "nChannels = 2")
        assertEquals(44100L, le32(4), "nSamplesPerSec = 44100")
        assertEquals(5945, le16(12), "nBlockAlign = 5945")
        assertEquals(10, le16(16), "cbSize = 10（尾部 codec-specific 字节）")
    }
}
