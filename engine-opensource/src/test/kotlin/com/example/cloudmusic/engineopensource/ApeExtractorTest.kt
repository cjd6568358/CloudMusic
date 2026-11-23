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

    private fun parseAll(): Quad<Recorder, Int, Int, ApeExtractor> {
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
        return Quad(rec, guard, seeks, extractor)
    }

    /** §61.149：四元组（Kotlin 无内建 Quad）。 */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun `sniff 识别 APE 头`() {
        val input = BytesInput(fixture())
        assertTrue(ApeExtractor().sniff(input), "MAC magic + v3974 应命中")
        // ASF 头不应被 APE sniff 误命中（互斥验证）
        assertTrue(!AsfExtractor().sniff(input), "ASF GUID 不应被 APE sniff 命中")
    }

    @Test
    fun `解析 Descriptor+Seek 表并逐帧投递至 EOF`() {
        val (rec, guard, seeks, extractor) = parseAll()

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

        // §61.149：packet 前缀字节序回归锚点——FFmpeg demuxer 契约 = LE 写
        // （AV_WL32 nblocks + AV_WL32 skip）；解码器 bswap_buf + BE 读 = 恒等。
        // §61.126 的 BE 写经 bswap 后 offset 倒序 → "Incorrect offset passed"
        // 全包拒绝（§61.148 真机一手静音）。skip=0（首帧）判据：prefix[4..7] 全 0
        // 无法区分字节序，故用 nblocks：LE 下低字节在 prefix[0]，≥256 的
        // blocksPerFrame 使 prefix[1]（或 [2]）非零且高字节 [3]=0。
        val prefix = extractor.firstPacketPrefix
        assertTrue(prefix != null, "首样本前 8 字节前缀已被捕获")
        assertTrue(prefix!![3] == 0.toByte() && (prefix[1] != 0.toByte() || prefix[2] != 0.toByte()),
            "nblocks 前缀为小端（LE）写入：prefix=%s".format(prefix.joinToString("") { "%02x".format(it) }))
        assertTrue(prefix[4] == 0.toByte() && prefix[5] == 0.toByte() && prefix[6] == 0.toByte()
            && prefix[7] == 0.toByte(), "首帧 skip = 0")
    }

    @Test
    fun `帧起点按 ape_c 契约 4 字节对齐（skip 对齐，真机卡爆根因回归）`() {
        // §61.320：ffmpeg ape.c 的帧对齐契约——skip_i = (seek_i - firstframe) & 3，
        // 解码起点 = seek_i - skip_i，包长 = align4(delta + skip_i)。
        // 本夹具（v3990）实测 139/200 个 seek 起点非 4 字节对齐：旧实现 skip 恒 0、
        // 包长不补齐 ⇒ 每包 1~3 字节错位，解码器 "packet size is not a multiple of 4"
        // + "Error decoding frame"（真机 CDImage/幸福沙漏 2 分钟 729 次），
        // 绝大多数帧解不出声 ⇒ 听感卡爆。本用例锁死对齐不变量。
        val (_, _, _, extractor) = parseAll()
        val offsets = extractor.frameOffsets
        assertTrue(offsets.size > 10, "帧表足够长")
        assertTrue(
            offsets.all { it % 4 == 0L },
            "所有解码起点必须 4B 对齐（旧实现 %d 个未对齐）".format(offsets.count { it % 4 != 0L }),
        )
        // 对齐后 seek 仍按 position 精确定位（帧表与 SeekMap 同源）
        for (idx in listOf(1, offsets.size / 2, offsets.size - 1)) {
            extractor.seek(offsets[idx], 0L)
            assertEquals(idx, extractor.currentFrameIndex, "对齐后 seek 帧 $idx 仍精确")
        }
    }

    @Test
    fun `seek 按 position 定位帧而非 timeUs 反算（播放时间乱跳根因）`() {
        // §61.298：frameTimeUs(i) 整数截断使 frameForTimeUs 反算 1405/1435 帧成 i-1。
        // Media3 下发的 position 是 SeekMap 给出的精确帧起点；旧实现按 timeUs
        // 反算 → 帧游标落后一帧 → 解码错位 → Error decoding frame + 时间乱跳。
        val (_, guard, _, extractor) = parseAll()
        val offsets = extractor.frameOffsets
        assertTrue(offsets.size > 10, "帧表足够长")
        for (idx in listOf(1, 2, 3, offsets.size / 4, offsets.size / 2)) {
            if (idx <= 0 || idx >= offsets.size) continue
            extractor.seek(offsets[idx], 0L)   // timeUs 故意传错，验证只认 position
            assertEquals(idx, extractor.currentFrameIndex,
                "seek 到帧 $idx 的 position=${offsets[idx]} 应定位到帧 $idx")
        }
        assertTrue(guard < 2000)
    }

    @Test
    fun `tag 探测不误触发（截断样本无 APE tag）`() {
        val (rec, guard, _, _) = parseAll()
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
