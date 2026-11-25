package com.example.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.aiff.AiffExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.347（D-1 黄金样本差分）· `AiffExtractor` 的采样字节流必须与参考实现逐字节一致。
 *
 * 参考数据：`analysis/l3-f3-fixtures/golden/aiff_f3_aiff.frames.tsv`，由
 * `tools/gen_golden_frames.py aiff` 转录 `libavformat/aiffdec.c:aiff_read_header`
 * 的 COMM/SSND 字段（含 80-bit extended 采样率）+ 项目 `SAMPLE_CHUNK_BYTES=8192`
 * 分段契约独立生成。诚实边界：分段大小是项目常量，不是 ffmpeg 的 packet 策略——
 * 本测试校验「数据区字节流 + 我方分段边界」，能抓 Kotlin 侧差一错误。
 *
 * 判据：① Format 的声道/采样率与黄金元数据一致；② 投递段数 == 黄金段数；
 * ③ 每段长度 == 表 length；④ 每段字节 == 文件 [pos, pos+length) 逐字节。
 */
@UnstableApi
class AiffGoldenFramesTest {

    private data class GoldenSeg(val pos: Int, val length: Int)

    private data class GoldenMeta(
        val channels: Int, val numFrames: Long, val bits: Int,
        val sampleRate: Int, val dataStart: Int, val dataBytes: Int,
    )

    private fun locate(rel: String): File =
        File("../$rel").takeIf { it.isFile } ?: File(rel)

    private fun fixture(): ByteArray {
        val p = locate("analysis/l3-f3-fixtures/f3_aiff.aiff")
        assertTrue(p.isFile, "夹具缺失：${p.path}")
        return p.readBytes()
    }

    private fun golden(): Pair<GoldenMeta, List<GoldenSeg>> {
        val p = locate("analysis/l3-f3-fixtures/golden/aiff_f3_aiff.frames.tsv")
        assertTrue(p.isFile, "黄金段表缺失：${p.path}（先跑 tools/gen_golden_frames.py）")
        var meta: GoldenMeta? = null
        val segs = mutableListOf<GoldenSeg>()
        p.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            if (t.startsWith("#")) {
                val kv = t.removePrefix("# ").split("=", limit = 2)
                if (kv.size == 2) {
                    when (kv[0]) {
                        "channels" -> meta = meta?.copy(channels = kv[1].toInt())
                            ?: GoldenMeta(kv[1].toInt(), 0, 0, 0, 0, 0)
                        "num_frames" -> meta = (meta ?: GoldenMeta(0, 0, 0, 0, 0, 0))
                            .copy(numFrames = kv[1].toLong())
                        "bits_per_sample" -> meta = (meta ?: GoldenMeta(0, 0, 0, 0, 0, 0))
                            .copy(bits = kv[1].toInt())
                        "sample_rate" -> meta = (meta ?: GoldenMeta(0, 0, 0, 0, 0, 0))
                            .copy(sampleRate = kv[1].toInt())
                        "data_start" -> meta = (meta ?: GoldenMeta(0, 0, 0, 0, 0, 0))
                            .copy(dataStart = kv[1].toInt())
                        "data_bytes" -> meta = (meta ?: GoldenMeta(0, 0, 0, 0, 0, 0))
                            .copy(dataBytes = kv[1].toInt())
                    }
                }
                return@forEachLine
            }
            if (t.startsWith("pos\t")) return@forEachLine
            val c = t.split('\t')
            assertEquals(2, c.size, "黄金行字段数不对：$t")
            segs += GoldenSeg(c[0].toInt(), c[1].toInt())
        }
        assertTrue(meta != null && segs.isNotEmpty(), "黄金表元数据/段为空")
        return meta!! to segs
    }

    private fun runExtractor(data: ByteArray, rec: Recorder) {
        val extractor = AiffExtractor()
        val input = BytesInput(data)
        extractor.init(rec)
        val holder = PositionHolder()
        var result = extractor.read(input, holder)
        var guard = 0
        while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 2000) {
            if (result == Extractor.RESULT_SEEK) input.seekTo(holder.position)
            result = extractor.read(input, holder)
        }
    }

    @Test
    fun `采样字节流与参考实现逐字节一致`() {
        val data = fixture()
        val (meta, want) = golden()
        val rec = Recorder().apply { capturePayloads = true }
        runExtractor(data, rec)

        // ① Format 头字段
        val f = rec.formats.single()
        assertEquals(meta.channels, f.channelCount, "声道数")
        assertEquals(meta.sampleRate, f.sampleRate, "采样率（80-bit extended 解码）")
        // ②③④ 逐段
        assertEquals(want.size, rec.payloads.size, "投递段数")
        assertEquals(want.size, rec.samples, "sampleMetadata 次数")
        for (i in want.indices) {
            val g = want[i]
            val seg = rec.payloads[i]
            assertEquals(g.length, seg.size, "第 $i 段长度")
            assertTrue(
                seg.contentEquals(data.copyOfRange(g.pos, g.pos + g.length)),
                "第 $i 段字节与文件 [${g.pos}, ${g.pos + g.length}) 不一致",
            )
        }
        // 字节流整体覆盖数据区（无缺口、无重叠）
        val total = want.sumOf { it.length }
        assertEquals(meta.dataBytes, total, "分段总长应覆盖整个数据区")
        assertEquals(meta.dataStart, want.first().pos, "首段起点 = dataStart")
        assertTrue(rec.samples >= 100, "可比对段数过少（${rec.samples}）")
    }

    @Test
    fun `黄金表自身不变量`() {
        val (meta, want) = golden()
        assertEquals(48000, meta.sampleRate)
        assertEquals(1, meta.channels)
        assertEquals(16, meta.bits)
        assertEquals(573440L, meta.numFrames)
        assertEquals(8192, meta.dataBytes / want.size, "段长应为项目 SAMPLE_CHUNK_BYTES")
        var cursor = meta.dataStart
        for ((i, g) in want.withIndex()) {
            assertEquals(cursor, g.pos, "第 $i 段应紧邻前段（无缺口/重叠）")
            cursor += g.length
        }
        assertEquals(meta.dataStart + meta.dataBytes, cursor, "末段应收口到数据区末尾")
    }
}
