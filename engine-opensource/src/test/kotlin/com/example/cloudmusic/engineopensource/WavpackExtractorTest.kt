package com.example.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ape.ApeExtractor
import androidx.media3.extractor.asf.AsfExtractor
import androidx.media3.extractor.wavpack.WavpackExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `12 §61.102` · WavpackExtractor 纯 JVM 解析验证（真机夹具 f3_wv.wv）。
 *
 * 判据：sniff / 32B 块头解析（采样率索引、total_samples）/ 563 个变长块全投递 /
 * MIME / Unseekable 时长 / 尾部非 wvpk 正常 EOF。诚实边界：JVM 解析 ≠ 真机可播
 * （竞品 so 无 wavpack decoder，需自编译静态库显式启用，见类 KDoc）。
 */
@UnstableApi
class WavpackExtractorTest {

    private fun fixture(): ByteArray {
        val p = File("../analysis/l3-f3-fixtures/f3_wv.wv")
            .takeIf { it.isFile } ?: File("analysis/l3-f3-fixtures/f3_wv.wv")
        assertTrue(p.isFile, "夹具缺失：${p.path}")
        return p.readBytes()
    }

    private fun parseAll(): Triple<Recorder, Int, Int> {
        val extractor = WavpackExtractor()
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
    fun `sniff 识别 wvpk 且不与 APE ASF 混淆`() {
        assertTrue(WavpackExtractor().sniff(BytesInput(fixture())))
        assertFalse(ApeExtractor().sniff(BytesInput(fixture())))
        assertFalse(AsfExtractor().sniff(BytesInput(fixture())))
    }

    @Test
    fun `解析块头并逐块投递 563 块至 EOF`() {
        val (rec, guard, seeks) = parseAll()
        val f = rec.formats.single()
        assertEquals("audio/x-wavpack", f.sampleMimeType)
        assertEquals("wavpack", f.codecs)
        assertEquals(48000, f.sampleRate, "flags SRATE idx=10 → 48000Hz")
        assertEquals(12_000_000L, rec.seekMap!!.durationUs, "576000 帧 / 48000Hz")
        assertFalse(rec.seekMap!!.isSeekable, "变长块无索引表，不伪造可 seek")
        assertEquals(563, rec.samples, "563 个 wvpk 块")
        assertEquals(313_366L, rec.sampleBytes, "块总长之和（尾部 92B 元数据不投递）")
        assertTrue(rec.sampleTimesUs.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0, seeks)
        assertTrue(guard < 2000, "读循环有界终止")
    }
}
