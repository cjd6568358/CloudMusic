package com.ccmsc.cloudmusic.engineopensource

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.asf.AsfExtractor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.347（D-1 黄金样本差分）· `AsfExtractor` 的包表必须与参考实现逐包一致。
 *
 * 参考数据：`analysis/l3-f3-fixtures/golden/asf_f3_wma.frames.tsv`，由
 * `tools/gen_golden_frames.py asf` 转录 `libavformat/asfdec_f.c:asf_read_file_properties`
 * 的 FileProps 字段（packets/play_duration/max_packet_size）+ 项目 §61.94/§61.107
 * 实测钉死的包布局（27B 头 + block_align 载荷 + padding）独立生成。
 *
 * 这个测试直接防护 §61.107 的真机根因：旧实现不跳包尾 4B padding ⇒ 每包漂移 4B，
 * 逐包 payload 与黄金字节流立刻对不上。
 *
 * 判据：① Format/SeekMap 头字段（rate/channels/duration）与黄金元数据一致；
 * ② 投递包数 == 黄金包数；③ 每包时间戳 == sendMs（§61.112 口径）×1000；
 * ④ 每包 payload 字节 == 文件 [pos+27, pos+27+avail) 逐字节。
 */
@UnstableApi
class AsfGoldenFramesTest {

    private data class GoldenPkt(
        val pos: Int, val sendMs: Long, val deltaMs: Long,
        val avail: Int, val padding: Int,
    )

    private data class GoldenMeta(
        val packetCount: Long, val playDuration100ns: Long, val maxPacketSize: Int,
        val blockAlign: Int, val sampleRate: Int, val channels: Int,
        val dataStart: Int, val padding: Int,
    )

    private fun locate(rel: String): File =
        File("../$rel").takeIf { it.isFile } ?: File(rel)

    private fun fixture(): ByteArray {
        val p = locate("analysis/l3-f3-fixtures/f3_wma.wma")
        assertTrue(p.isFile, "夹具缺失：${p.path}")
        return p.readBytes()
    }

    private fun golden(): Pair<GoldenMeta, List<GoldenPkt>> {
        val p = locate("analysis/l3-f3-fixtures/golden/asf_f3_wma.frames.tsv")
        assertTrue(p.isFile, "黄金包表缺失：${p.path}（先跑 tools/gen_golden_frames.py）")
        var meta: GoldenMeta? = null
        val pkts = mutableListOf<GoldenPkt>()
        fun m0() = meta ?: GoldenMeta(0, 0, 0, 0, 0, 0, 0, 0)
        p.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            if (t.startsWith("#")) {
                val kv = t.removePrefix("# ").split("=", limit = 2)
                if (kv.size == 2) {
                    meta = when (kv[0]) {
                        "packet_count" -> m0().copy(packetCount = kv[1].toLong())
                        "play_duration_100ns" -> m0().copy(playDuration100ns = kv[1].toLong())
                        "max_packet_size" -> m0().copy(maxPacketSize = kv[1].toInt())
                        "block_align" -> m0().copy(blockAlign = kv[1].toInt())
                        "sample_rate" -> m0().copy(sampleRate = kv[1].toInt())
                        "channels" -> m0().copy(channels = kv[1].toInt())
                        "data_start" -> m0().copy(dataStart = kv[1].toInt())
                        "padding" -> m0().copy(padding = kv[1].toInt())
                        else -> meta
                    }
                }
                return@forEachLine
            }
            if (t.startsWith("pos\t")) return@forEachLine
            val c = t.split('\t')
            assertEquals(5, c.size, "黄金行字段数不对：$t")
            pkts += GoldenPkt(
                c[0].toInt(), c[1].toLong(), c[2].toLong(), c[3].toInt(), c[4].toInt(),
            )
        }
        assertTrue(meta != null && pkts.isNotEmpty(), "黄金表元数据/包为空")
        return meta!! to pkts
    }

    private fun runExtractor(data: ByteArray, rec: Recorder) {
        val extractor = AsfExtractor()
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
    fun `包表与参考实现逐包一致`() {
        val data = fixture()
        val (meta, want) = golden()
        val rec = Recorder().apply { capturePayloads = true }
        runExtractor(data, rec)

        // ① 头字段
        val f = rec.formats.single()
        assertEquals(meta.sampleRate, f.sampleRate, "采样率")
        assertEquals(meta.channels, f.channelCount, "声道数")
        assertEquals(meta.playDuration100ns / 10, rec.seekMap!!.durationUs,
            "play_duration(100ns)/10 = 微秒")
        // ②③④ 逐包
        assertEquals(want.size, rec.payloads.size, "投递包数")
        assertEquals(want.size, rec.samples, "sampleMetadata 次数")
        for (i in want.indices) {
            val g = want[i]
            assertEquals(g.sendMs * 1000, rec.sampleTimesUs[i], "第 $i 包时间戳（sendMs 口径 §61.112）")
            val payload = rec.payloads[i]
            assertEquals(g.avail, payload.size, "第 $i 包 payload 长度（block_align）")
            assertTrue(
                payload.contentEquals(data.copyOfRange(g.pos + 27, g.pos + 27 + g.avail)),
                "第 $i 包 payload 字节与文件不一致（padding 漂移？）",
            )
        }
        assertTrue(rec.samples >= 100, "可比对包数过少（${rec.samples}）")
    }

    @Test
    fun `黄金表自身不变量`() {
        val (meta, want) = golden()
        assertEquals(120L, meta.packetCount)
        assertEquals(5976, meta.maxPacketSize)
        assertEquals(5945, meta.blockAlign)
        assertEquals(4, meta.padding, "§61.107 包尾 padding")
        assertEquals(meta.maxPacketSize, 27 + meta.blockAlign + meta.padding, "包长收口")
        for (i in 1 until want.size) {
            assertEquals(want[i - 1].pos + meta.maxPacketSize, want[i].pos, "第 $i 包应紧贴前包（无漂移）")
        }
        assertTrue(want.all { it.avail == meta.blockAlign }, "所有包 payload 应为 block_align 满长")
    }
}
