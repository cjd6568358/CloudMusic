package com.example.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ape.ApeExtractor
import androidx.media3.extractor.asf.AsfExtractor
import androidx.media3.extractor.aiff.AiffExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `12 §61.102` · AiffExtractor 纯 JVM 解析验证（真机夹具 f3_aiff.aiff）。
 *
 * 判据：sniff / FORM·COMM·SSND 解析 / 80-bit 采样率 / MIME / SeekMap 时长 /
 * 逐块投递字节量 / EOF 闭环。诚实边界：JVM 解析 ≠ 真机可播（还需 FFmpeg
 * 静态库启用 pcm_s16be decoder 并做真机回归）。
 */
@UnstableApi
class AiffExtractorTest {

    private fun fixture(): ByteArray {
        val p = File("../analysis/l3-f3-fixtures/f3_aiff.aiff")
            .takeIf { it.isFile } ?: File("analysis/l3-f3-fixtures/f3_aiff.aiff")
        assertTrue(p.isFile, "夹具缺失：${p.path}")
        return p.readBytes()
    }

    private fun parseAll(): Triple<Recorder, Int, Int> {
        val extractor = AiffExtractor()
        val input = BytesInput(fixture())
        val rec = Recorder()
        extractor.init(rec)
        val holder = PositionHolder()
        var result = extractor.read(input, holder)
        var seeks = 0
        var guard = 0
        while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 2000) {
            if (result == Extractor.RESULT_SEEK) {
                seeks++
                input.seekTo(holder.position)
            }
            result = extractor.read(input, holder)
        }
        return Triple(rec, guard, seeks)
    }

    @Test
    fun `sniff 识别 FORM AIFF 且不与 APE ASF 混淆`() {
        val input = BytesInput(fixture())
        assertTrue(AiffExtractor().sniff(input), "FORM/AIFF 应命中")
        assertFalse(ApeExtractor().sniff(BytesInput(fixture())))
        assertFalse(AsfExtractor().sniff(BytesInput(fixture())))
    }

    @Test
    fun `解析 COMM 与 SSND 并逐块投递至 EOF`() {
        val (rec, guard, seeks) = parseAll()
        val f = rec.formats.single()
        assertEquals("audio/x-aiff", f.sampleMimeType)
        assertEquals("pcm_s16be", f.codecs)
        assertEquals(48000, f.sampleRate, "80-bit extended 400ebb80... = 48000Hz")
        assertEquals(1, f.channelCount)
        assertEquals(573440L * 1_000_000L / 48000L, rec.seekMap!!.durationUs)
        assertTrue(rec.seekMap!!.isSeekable)
        assertEquals(1_146_880L, rec.sampleBytes, "SSND 数据 = 573440 帧 × 1ch × 2B")
        assertTrue(rec.samples > 0)
        assertTrue(rec.sampleTimesUs.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0, seeks, "顺序读不需 RESULT_SEEK")
        assertTrue(guard < 2000, "读循环有界终止")
    }
}
