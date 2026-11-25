package com.ccmsc.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.wavpack.WavpackExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.347（D-1 黄金样本差分）· `WavpackExtractor` 的块表必须与参考实现逐块一致。
 *
 * 参考数据：`analysis/l3-f3-fixtures/golden/wavpack_f3_wv.frames.tsv`，由
 * `tools/gen_golden_frames.py wavpack` 转录 `libavformat/wv.c:ff_wv_parse_header`
 * 的字段偏移 + `wvdec.c:wv_read_packet` 的块长公式独立生成——不是拿 Kotlin 反推。
 *
 * 判据（逐块）：每块两次 sampleData = 32B 头（ParsableByteArray）+ body。
 * ① 头的 9 个字段（cksize/version/total_samples/block_index/samples/flags/crc）
 *    与黄金表逐项一致；② 头 32B 字节 == 文件 [pos, pos+32)；③ body 长度 == 表 body_size；
 * ④ body 字节 == 文件 [pos+32, pos+32+body_size) 逐字节。任一处差一字节即失败。
 */
@UnstableApi
class WavpackGoldenFramesTest {

    private data class GoldenBlock(
        val pos: Int, val ckSize: Int, val version: Int, val totalSamples: Long,
        val blockIndex: Long, val samples: Long, val flags: Long, val crc: Long,
        val bodySize: Int,
    )

    private fun locate(rel: String): File =
        File("../$rel").takeIf { it.isFile } ?: File(rel)

    private fun fixture(): ByteArray {
        val p = locate("analysis/l3-f3-fixtures/f3_wv.wv")
        assertTrue(p.isFile, "夹具缺失：${p.path}")
        return p.readBytes()
    }

    private fun golden(): List<GoldenBlock> {
        val p = locate("analysis/l3-f3-fixtures/golden/wavpack_f3_wv.frames.tsv")
        assertTrue(p.isFile, "黄金块表缺失：${p.path}（先跑 tools/gen_golden_frames.py）")
        val out = mutableListOf<GoldenBlock>()
        p.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("pos\t")) return@forEachLine
            val c = t.split('\t')
            assertEquals(9, c.size, "黄金行字段数不对：$t")
            out += GoldenBlock(
                c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toLong(),
                c[4].toLong(), c[5].toLong(), c[6].toLong(), c[7].toLong(), c[8].toInt(),
            )
        }
        assertTrue(out.isNotEmpty(), "黄金块表为空")
        return out
    }

    private fun runExtractor(data: ByteArray, rec: Recorder) {
        val extractor = WavpackExtractor()
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
    fun `块表与参考实现逐块一致`() {
        val data = fixture()
        val want = golden()
        val rec = Recorder().apply { capturePayloads = true }
        runExtractor(data, rec)

        // 每块两次 sampleData：32B 头 + body
        assertEquals(want.size * 2, rec.payloads.size, "payload 次数应为块数×2")
        assertEquals(want.size, rec.samples, "投递块数")

        for (i in want.indices) {
            val g = want[i]
            val head = rec.payloads[2 * i]
            assertEquals(32, head.size, "第 $i 块头长")
            // ② 头 32B 与文件逐字节
            assertTrue(
                head.contentEquals(data.copyOfRange(g.pos, g.pos + 32)),
                "第 $i 块头字节与文件 [${g.pos}, ${g.pos + 32}) 不一致",
            )
            // ① 头字段逐项
            assertEquals(g.ckSize.toLong(), leU32(head, 4), "第 $i 块 cksize")
            assertEquals(g.version, leU16(head, 8), "第 $i 块 version")
            assertEquals(g.totalSamples, leU32(head, 12), "第 $i 块 total_samples")
            assertEquals(g.blockIndex, leU32(head, 16), "第 $i 块 block_index")
            assertEquals(g.samples, leU32(head, 20), "第 $i 块 samples")
            assertEquals(g.flags, leU32(head, 24), "第 $i 块 flags")
            assertEquals(g.crc, leU32(head, 28), "第 $i 块 crc")

            // ③④ body
            val body = rec.payloads[2 * i + 1]
            assertEquals(g.bodySize, body.size, "第 $i 块 body 长度")
            assertTrue(
                body.contentEquals(data.copyOfRange(g.pos + 32, g.pos + 32 + g.bodySize)),
                "第 $i 块 body 字节与文件不一致",
            )
        }
        assertTrue(rec.samples >= 100, "可比对块数过少（${rec.samples}）")
    }

    @Test
    fun `黄金表自身不变量`() {
        val want = golden()
        for (i in 1 until want.size) {
            assertTrue(want[i].pos > want[i - 1].pos, "第 $i 块位置未递增")
        }
        assertTrue(want.all { it.ckSize >= 24 && it.bodySize == it.ckSize - 24 }, "ckSize/body 关系不符 wv.c")
        assertTrue(want.all { it.pos + 32 + it.bodySize <= it.pos + it.ckSize + 8 }, "块长越界")
    }

    private fun leU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
}
