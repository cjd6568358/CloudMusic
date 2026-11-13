package androidx.media3.extractor.wavpack

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * `12 §61.102` · WavPack 容器 Extractor（F3 容器缺口 ④）。
 *
 * 样本实测（`analysis/l3-f3-fixtures/f3_wv.wv`，313,458 B）：
 * 块头 `wvpk` + ckSize u32(LE) + version u16 + track/index u8 + total_samples u32
 * + block_index u32 + block_samples u32 + flags u32 + crc u32 = 32B；
 * 首块 ckSize=548（总长 556）/ version=0x410 / total_samples=576000 /
 * block_samples=1024 / flags=0x053c1805（SRATE idx=10 → 48000Hz）；
 * 全文件 **563 个变长块**，尾部 92B 非 `wvpk`（尾随元数据，正常 EOF）。
 *
 * 输出 `audio/x-wavpack` → `FfmpegLibrary` 映射到 FFmpeg `wavpack` decoder。
 * **诚实边界**：竞品 `libffmpeg.so` 的 `.dynsym` 里 `wavpack` 为 `NO_SYMBOL`
 * （§61.83 / `l3-b2-m2-decoder-symbols-report.md`），本 Extractor 依赖**自编译
 * FFmpeg 6.0 静态库显式启用 `--enable-decoder=wavpack`**；映射存在 ≠ 解码器已编入
 * ≠ 真机可播。变长块无索引表 → 提供 Unseekable SeekMap；DSD / 多声道矩阵 /
 * 修正文件（.wvc）与多轨索引未覆盖。
 */
@UnstableApi
class WavpackExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var formatSent = false
    private var sampleRate = 0
    private var channels = 0
    private var totalSamples = 0L
    private var blocksFed = 0

    override fun sniff(input: ExtractorInput): Boolean {
        val h = ByteArray(4)
        if (!input.peekFully(h, 0, 4, true)) return false
        return isWvpk(h, 0)
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        // 变长块无索引表：回到容器起点重新解析（不伪造可 seek 能力）。
        formatSent = false
        blocksFed = 0
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val head = ByteArray(32)
        if (!readExact(input, head, 32)) return Extractor.RESULT_END_OF_INPUT
        if (!isWvpk(head, 0)) return Extractor.RESULT_END_OF_INPUT

        val blockSize = readLeU32(head, 4)
        if (blockSize < 24 || blockSize > MAX_BLOCK_BYTES) {
            return Extractor.RESULT_END_OF_INPUT
        }
        val blockTotal = blockSize + 8
        if (!formatSent) {
            totalSamples = readLeU32(head, 12)
            val flags = readLeU32(head, 24)
            val srateIdx = ((flags shr 23) and 0xF).toInt()
            sampleRate = SAMPLE_RATES.getOrElse(srateIdx) { 0 }
            channels = if (flags and MONO_FLAG != 0L) 1 else 2
            if (sampleRate <= 0 || channels <= 0) return Extractor.RESULT_END_OF_INPUT
            trackOutput?.format(
                Format.Builder()
                    .setSampleMimeType("audio/x-wavpack")
                    .setSampleRate(sampleRate)
                    .setChannelCount(channels)
                    .setCodecs("wavpack")
                    .build(),
            )
            val out = output ?: return Extractor.RESULT_END_OF_INPUT
            out.endTracks()
            out.seekMap(SeekMap.Unseekable(durationUs()))
            formatSent = true
        }

        val startSample = readLeU32(head, 16)   // block_index
        return deliverBlock(input, head, (blockTotal - 32).toInt(), startSample)
    }

    /** 整块（含 32B 头）作为一个解码单元投递：头走 ParsableByteArray，体走 input。 */
    private fun deliverBlock(
        input: ExtractorInput,
        head: ByteArray,
        bodyBytes: Int,
        startSample: Long,
    ): Int {
        trackOutput?.sampleData(ParsableByteArray(head), 32)
        var fed = 0
        if (bodyBytes > 0) {
            fed = trackOutput?.sampleData(input, bodyBytes, true) ?: 0
            if (fed <= 0) return Extractor.RESULT_END_OF_INPUT
        }
        val timeUs = startSample * 1_000_000L / sampleRate
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, 32 + fed, 0, null)
        blocksFed++
        return Extractor.RESULT_CONTINUE
    }

    private fun durationUs(): Long =
        if (sampleRate <= 0) C.TIME_UNSET else totalSamples * 1_000_000L / sampleRate

    override fun release() {
        output = null
        trackOutput = null
    }

    private fun readExact(input: ExtractorInput, target: ByteArray, length: Int): Boolean {
        var off = 0
        while (off < length) {
            val n = input.read(target, off, length - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private companion object {
        const val MAX_BLOCK_BYTES = 1 shl 20
        const val MONO_FLAG = 0x00000004L
        // WavPack 5 flags bits 23..26（wavpack.c sample_rates[]）。
        val SAMPLE_RATES = intArrayOf(
            6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000,
            32000, 44100, 48000, 64000, 88200, 96000, 192000,
        )

        fun isWvpk(b: ByteArray, off: Int): Boolean =
            b[off] == 'w'.code.toByte() && b[off + 1] == 'v'.code.toByte() &&
                b[off + 2] == 'p'.code.toByte() && b[off + 3] == 'k'.code.toByte()

        fun readLeU32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xff) or
                ((b[off + 1].toLong() and 0xff) shl 8) or
                ((b[off + 2].toLong() and 0xff) shl 16) or
                ((b[off + 3].toLong() and 0xff) shl 24)
    }
}
