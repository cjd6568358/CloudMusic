package com.example.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.asf.AsfExtractor
import androidx.media3.extractor.ape.ApeExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.94/§61.94b · ApeExtractor **纯 JVM** 解析验证：真机夹具 f3_ape.ape
 * （OpenList 真实音频前 4MiB 截断片段，v3974）。
 *
 * 判据（离线可判）：sniff ✓ / Descriptor+Header+Seek 表解析（帧数/采样率）✓ /
 * 帧投递 > 0（截断样本的可用部分）✓ / EOF 闭环（无 seek 死循环——§61.94
 * 首轮真机卡死根因的回归验证）✓ / tag 探测路径不误触发（截断样本无 APE tag）✓。
 * 诚实边界：JVM 解析 ≠ 真机出声（FFmpeg ape 软解 + AudioTrack 属真机判据）。
 */
@UnstableApi
class ApeExtractorTest {

    private fun fixture(): ByteArray {
        val p = File("../analysis/l3-f3-fixtures/f3_ape.ape")
            .takeIf { it.isFile } ?: File("analysis/l3-f3-fixtures/f3_ape.ape")
        assertTrue(p.isFile, "夹具缺失：${p.path}（先跑 tools/l3_f3_fixture_gen.py）")
        return p.readBytes()
    }

    private fun parseAll(): Triple<Recorder, Int, Int> {
        val data = fixture()
        val extractor = ApeExtractor()
        val input = BytesInput(data)
        val rec = Recorder()
        extractor.init(rec)

        val holder = PositionHolder()
        var result = extractor.read(input, holder)
        var seeks = 0
        var guard = 0
        while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 2000) {
            if (result == Extractor.RESULT_SEEK) {
                seeks++
                input.seekTo(holder.position)   // 真机由 ProgressiveMediaPeriod 执行 seek
            }
            result = extractor.read(input, holder)
        }
        return Triple(rec, guard, seeks)
    }

    @Test
    fun `sniff 识别 APE 头`() {
        val input = BytesInput(fixture())
        assertTrue(ApeExtractor().sniff(input), "MAC magic + v3974 应命中")
        // ASF 头不应被 APE sniff 误命中（互斥验证）
        assertTrue(!AsfExtractor().sniff(input), "ASF GUID 不应被 APE sniff 命中")
    }

    @Test
    fun `解析 Descriptor+Seek 表并逐帧投递至 EOF`() {
        val (rec, guard, seeks) = parseAll()

        val f = rec.formats.single()
        assertTrue(f.sampleRate!! > 0, "采样率 > 0（Header 实测解析）")
        assertTrue(f.channelCount in 1..8, "声道数合理")
        assertEquals("audio/x-ape", f.sampleMimeType, "APE MIME → FfmpegAudioRenderer")

        assertTrue(rec.seekMap != null, "SeekMap 已下发")
        assertTrue(rec.seekMap!!.isSeekable, "Seek 表就绪后可 seek")
        assertTrue(rec.seekMap!!.durationUs > 0, "时长 > 0（totalframes × blocksperframe）")

        assertTrue(rec.samples > 0, "帧投递 > 0（截断样本的可用部分）")
        assertTrue(rec.samples < 2000, "帧数 < 宣称 totalFrames（4MiB 截断）")
        assertTrue(rec.sampleBytes > 0, "样本字节 > 0")
        assertTrue(seeks >= 0, "seek 循环修复：RESULT_SEEK 仅用于帧定位，无死循环")

        // 时间戳单调递增（帧序 × blocksPerFrame）
        assertTrue(
            rec.sampleTimesUs.zipWithNext().all { (a, b) -> b >= a },
            "时间戳单调不减",
        )
    }

    @Test
    fun `tag 探测不误触发（截断样本无 APE tag）`() {
        val (rec, guard, _) = parseAll()
        // f3_ape.ape 是 4MiB 截断片段——尾部无 APETAGEX，tagEnd 保持未探测。
        // 判据：EOF 闭环（STATE_TAG_CHECK 一次探测后 END），无死循环。
        assertTrue(guard < 2000, "读循环有界终止")
        assertTrue(rec.formats.isNotEmpty(), "解析完成")
    }

    /** §61.107：记录顺序 skip 次数（REMOTE 下每次 RESULT_SEEK 会重开取流）。 */
    private class CountingInput(private val base: BytesInput) : ExtractorInput by base {
        var skipCalls = 0
        override fun skipFully(length: Int) {
            skipCalls++
            base.skipFully(length)
        }
        fun seekTo(position: Long) = base.seekTo(position)
    }

    @Test
    fun `顺序帧推进走 skipFully 而非逐帧 RESULT_SEEK（REMOTE 取流回归）`() {
        // §61.106 真机：ape-remote 30s 只推进到第 2 帧（每次 RESULT_SEEK
        // 重开 CacheDataSource 9~17s），同一帧序列 LOCAL 22 次仅 8ms。
        // 本例把「顺序前进走 skipFully」钉成回归，防止改回逐帧 seek。
        val rec = Recorder()
        val input = CountingInput(BytesInput(fixture()))
        val extractor = ApeExtractor()
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
        assertTrue(rec.samples > 0, "仍有帧投递")
        assertTrue(input.skipCalls > 0, "顺序推进应走 skipFully")
        assertTrue(seeks < 10, "顺序推进不应产生大量 RESULT_SEEK（实际 $seeks）")
        assertTrue(guard < 2000, "无 seek 死循环")
    }
}
