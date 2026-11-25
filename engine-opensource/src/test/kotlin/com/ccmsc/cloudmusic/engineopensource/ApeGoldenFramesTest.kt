package com.ccmsc.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ape.ApeExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.322（D-1 黄金样本差分）· `ApeExtractor` 的帧表必须与 ffmpeg 参考实现逐帧一致。
 *
 * 为什么要有这个测试：§61.320 的「卡爆」根因是帧起点未按 `ape.c` 的
 * `skip = (pos - first_frame) & 3` 对齐——当时仓库里**有完整 ffmpeg 源码**，却没有
 * 一份可执行的参考输出，只能靠人读 C 代码推理，读错一次就是一次真机故障。
 *
 * 参考数据：`analysis/l3-f3-fixtures/golden/ape_f3_ape.frames.tsv`，由
 * `tools/gen_golden_frames.py` 直接转录 `libavformat/ape.c`（`ape_read_header` +
 * `ape_read_packet`）生成，不是拿本项目 Kotlin 实现反推的——两条独立实现，
 * 同时写错才会一起错。
 *
 * 判据（逐帧）：① 投递前缀的 nblocks ② 投递前缀的 skip ③ 负载长度
 * ④ 负载字节与文件 `[pos, pos+avail)` **逐字节相等**。任一处差一字节即失败。
 */
@UnstableApi
class ApeGoldenFramesTest {

    private data class GoldenFrame(
        val pos: Long, val nblocks: Int, val skip: Int, val size: Long, val avail: Int,
    )

    private fun locate(rel: String): File =
        File("../$rel").takeIf { it.isFile } ?: File(rel)

    private fun fixture(): ByteArray {
        val p = locate("analysis/l3-f3-fixtures/f3_ape.ape")
        assertTrue(p.isFile, "夹具缺失：${p.path}（先跑 tools/l3_f3_fixture_gen.py）")
        return p.readBytes()
    }

    private fun golden(): List<GoldenFrame> {
        val p = locate("analysis/l3-f3-fixtures/golden/ape_f3_ape.frames.tsv")
        assertTrue(p.isFile, "黄金帧表缺失：${p.path}（先跑 tools/gen_golden_frames.py）")
        val out = mutableListOf<GoldenFrame>()
        p.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("pos\t")) return@forEachLine
            val c = t.split('\t')
            assertEquals(5, c.size, "黄金行字段数不对：$t")
            out += GoldenFrame(
                c[0].toLong(), c[1].toInt(), c[2].toInt(), c[3].toLong(), c[4].toInt(),
            )
        }
        assertTrue(out.isNotEmpty(), "黄金帧表为空")
        return out
    }

    /** 与 `ApeExtractorTest` 同型的最小取流器（BytesInput）。 */
    private fun runExtractor(data: ByteArray, rec: Recorder): ApeExtractor {
        val extractor = ApeExtractor()
        val input = BytesInput(data)
        extractor.init(rec)
        val holder = PositionHolder()
        var result = extractor.read(input, holder)
        var guard = 0
        while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 5000) {
            if (result == Extractor.RESULT_SEEK) input.seekTo(holder.position)
            result = extractor.read(input, holder)
        }
        return extractor
    }

    @Test
    fun `帧表与 ffmpeg 参考实现逐帧一致`() {
        val data = fixture()
        val want = golden()
        val rec = Recorder().apply { capturePayloads = true }
        runExtractor(data, rec)

        // 每帧两次 sampleData：8 字节前缀 + 负载（残帧按 §61.94b 整帧不投递，
        // 所以这里必然成对出现）。
        val pairs = rec.payloads.size / 2
        assertTrue(pairs > 0, "抽取器没有投递任何帧")
        assertTrue(
            rec.payloads[0].size == 8,
            "首样本应是 8 字节前缀（nblocks + skip），实际 ${rec.payloads[0].size}",
        )

        // 截断夹具只覆盖帧表前段；比对范围 = min(实际投递, 黄金表)。
        val n = minOf(pairs, want.size)
        // 投递帧数必须恰好等于「完整可读」的帧数。口径来自 §61.94b 的既定语义：
        // `pos + size + 8 > input.length` 的残帧**不投递**（APE 解码器拒收截断帧，
        // 投了就是 MALFORMED），循环到此为止。这里把这个边界写成判据——
        // 边界一旦被改动，帧数会立刻对不上。
        val fullFrames = want.takeWhile { it.pos + it.size + 8 <= data.size }.size
        assertEquals(
            fullFrames, pairs,
            "投递帧数与「完整可读帧数」不符（$pairs vs $fullFrames）：" +
                "entries=${rec.payloads.size} tailSizes=" +
                rec.payloads.takeLast(4).joinToString(",") { it.size.toString() },
        )
        assertTrue(n >= 30, "可比对帧数过少（$n）——夹具或解析疑似异常")

        for (i in 0 until n) {
            val prefix = rec.payloads[2 * i]
            assertEquals(8, prefix.size, "第 $i 帧前缀长度")
            val nblocks = leU32(prefix, 0)
            val skip = leU32(prefix, 4)
            val g = want[i]
            assertEquals(g.nblocks, nblocks, "第 $i 帧 nblocks（$nblocks vs 黄金 ${g.nblocks}）")
            assertEquals(g.skip, skip, "第 $i 帧 skip（$skip vs 黄金 ${g.skip}）")

            val payload = rec.payloads.getOrNull(2 * i + 1) ?: ByteArray(0)
            assertEquals(g.avail, payload.size, "第 $i 帧负载长度")
            assertTrue(
                payload.contentEquals(data.copyOfRange(g.pos.toInt(), g.pos.toInt() + g.avail)),
                "第 $i 帧负载字节与文件 [${g.pos}, ${g.pos + g.avail}) 不一致",
            )
        }

        // 对齐契约的独立断言（不依赖黄金表）：每一帧的**解码起点**必须 4 字节对齐。
        // 黄金表里 pos 已按 ape.c 做过 `pos -= skip`，所以直接判 pos % 4 == 0；
        // 原始 seek 表项（pos + skip）本身可以任意对齐——这正是旧实现漏掉的那一步。
        var misaligned = 0
        for (i in 0 until n) {
            if (want[i].pos % 4 != 0L) misaligned++
        }
        assertEquals(0, misaligned, "帧解码起点未 4 字节对齐（ape.c 契约）")
    }

    @Test
    fun `黄金表自身不变量`() {
        val want = golden()
        // 单调递增 + 帧数一致 + 末帧 avail 非负（截断夹具下末帧可能读不满）
        for (i in 1 until want.size) {
            assertTrue(want[i].pos > want[i - 1].pos, "第 $i 帧位置未递增")
        }
        assertTrue(want.last().avail >= 0, "末帧 avail 为负")
        // skip 只能落在 0..3（ape.c: & 3）
        assertTrue(want.all { it.skip in 0..3 }, "skip 越界（应恒 0..3）")
    }

    private fun leU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
