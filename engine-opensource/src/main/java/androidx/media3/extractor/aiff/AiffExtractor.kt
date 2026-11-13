package androidx.media3.extractor.aiff

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.pow

/**
 * `12 §61.102` · AIFF / AIFC 容器 Extractor（F3 容器缺口 ③）。
 *
 * 样本实测（`analysis/l3-f3-fixtures/f3_aiff.aiff`，1,146,934 B）：
 * `FORM`(1146926) + `AIFF` + `COMM`(18 @12) + `SSND`(1146888 @38)；
 * COMM = ch=1 / numFrames=573440 / bits=16 / 80-bit IEEE extended
 * `400ebb80000000000000` = **48000 Hz**（§61.101 曾误记为 44100，本批改正）。
 * SSND 头 8B（offset + blockSize）之后 1,146,880 B = 573440 × 1ch × 2B。
 *
 * 输出 `audio/x-aiff` → `FfmpegLibrary` 映射到 FFmpeg `pcm_s16be` decoder
 * （AIFF 是大端 PCM，交给软解做端序归一化到 s16le）。
 * **诚实边界**：MIME 映射存在 ≠ 静态库已启用 `pcm_s16be` ≠ 真机可播；
 * `build_ffmpeg.sh` 的 ENABLED_DECODERS 需含 `pcm_s16be`，且本 Extractor
 * 只覆盖未压缩 PCM / 16-bit / 定长帧，AIFC 压缩变体与 8/24/32-bit 未实现。
 */
@UnstableApi
class AiffExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var sampleRate = 0
    private var channels = 0
    private var bitsPerSample = 0
    private var numFrames = 0L
    private var dataStart = -1L
    private var dataBytes = 0L
    private var bytesFed = 0L
    private var state = STATE_HEADER

    override fun sniff(input: ExtractorInput): Boolean {
        val h = ByteArray(12)
        if (!input.peekFully(h, 0, 12, true)) return false
        val form = h[0] == 'F'.code.toByte() && h[1] == 'O'.code.toByte() &&
            h[2] == 'R'.code.toByte() && h[3] == 'M'.code.toByte()
        val aiff = (h[8] == 'A'.code.toByte() && h[9] == 'I'.code.toByte() &&
            h[10] == 'F'.code.toByte() && (h[11] == 'F'.code.toByte() ||
            h[11] == 'C'.code.toByte()))
        return form && aiff
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (dataStart < 0 || sampleRate <= 0) {
            state = STATE_HEADER
            return
        }
        val frame = (timeUs * sampleRate / 1_000_000L).coerceIn(0L, numFrames)
        bytesFed = frame * bytesPerFrame()
        state = STATE_SEEK
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = when (state) {
        STATE_HEADER -> readHeader(input)
        STATE_SEEK -> {
            val target = dataStart + bytesFed
            if (input.position != target) {
                seekPosition.position = target
                Extractor.RESULT_SEEK
            } else {
                state = STATE_SAMPLES
                readSamples(input)
            }
        }
        else -> readSamples(input)
    }

    /** 逐 chunk 走 FORM 容器；统一用 read()，避免与 sniff 的 peek 缓冲交错。 */
    private fun readHeader(input: ExtractorInput): Int {
        val form = ByteArray(12)
        if (!readExact(input, form, 12)) return Extractor.RESULT_END_OF_INPUT
        val formSize = readBeU32(form, 4).toLong()

        var pos = 12L
        var sawComm = false
        var sawSsnd = false
        val chunkHead = ByteArray(8)
        while (pos + 8 <= formSize + 8) {
            if (!readExact(input, chunkHead, 8)) return Extractor.RESULT_END_OF_INPUT
            val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
            val size = readBeU32(chunkHead, 4).toLong()
            pos += 8
            if (size < 0 || pos + size > formSize + 8) return Extractor.RESULT_END_OF_INPUT
            when (id) {
                "COMM" -> {
                    val comm = ByteArray(18)
                    if (!readExact(input, comm, 18)) return Extractor.RESULT_END_OF_INPUT
                    channels = readBeU16(comm, 0)
                    numFrames = readBeU32(comm, 2).toLong()
                    bitsPerSample = readBeU16(comm, 6)
                    sampleRate = extended80ToInt(comm, 8)
                    if (!skipExact(input, size - 18)) return Extractor.RESULT_END_OF_INPUT
                    sawComm = true
                }
                "SSND" -> {
                    val ssnd = ByteArray(8)
                    if (!readExact(input, ssnd, 8)) return Extractor.RESULT_END_OF_INPUT
                    dataStart = input.position
                    dataBytes = (size - 8).coerceAtLeast(0)
                    if (!skipExact(input, 0)) return Extractor.RESULT_END_OF_INPUT
                    sawSsnd = true
                }
                else -> if (!skipExact(input, size)) return Extractor.RESULT_END_OF_INPUT
            }
            // AIFF chunk 按偶数字节对齐
            if (size % 2L == 1L && !skipExact(input, 1)) return Extractor.RESULT_END_OF_INPUT
            pos += size + (size % 2L)
            if (sawSsnd) break
        }
        if (!sawComm || !sawSsnd || sampleRate <= 0 || channels <= 0 ||
            bitsPerSample != 16
        ) {
            return Extractor.RESULT_END_OF_INPUT
        }

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType("audio/x-aiff")
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                .setCodecs("pcm_s16be")
                .build(),
        )
        val out = output ?: return Extractor.RESULT_END_OF_INPUT
        out.endTracks()
        out.seekMap(AiffSeekMap())
        state = STATE_SAMPLES
        return Extractor.RESULT_CONTINUE
    }

    private fun readSamples(input: ExtractorInput): Int {
        val remaining = dataBytes - bytesFed
        if (remaining <= 0) return Extractor.RESULT_END_OF_INPUT
        val frame = bytesPerFrame()
        val want = minOf(remaining, SAMPLE_CHUNK_BYTES.toLong())
            .let { (it / frame) * frame }
            .toInt()
        if (want <= 0) return Extractor.RESULT_END_OF_INPUT
        val fed = trackOutput?.sampleData(input, want, true) ?: 0
        if (fed <= 0) return Extractor.RESULT_END_OF_INPUT
        val timeUs = (bytesFed / frame) * 1_000_000L / sampleRate
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, fed, 0, null)
        bytesFed += fed
        return Extractor.RESULT_CONTINUE
    }

    private fun bytesPerFrame(): Long = (channels * (bitsPerSample / 8)).toLong().coerceAtLeast(1)

    private inner class AiffSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long =
            if (sampleRate <= 0) C.TIME_UNSET else numFrames * 1_000_000L / sampleRate
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val frame = (timeUs * sampleRate / 1_000_000L).coerceIn(0L, numFrames)
            val p = SeekPoint(frame * 1_000_000L / sampleRate, dataStart + frame * bytesPerFrame())
            return SeekMap.SeekPoints(p)
        }
    }

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

    private fun skipExact(input: ExtractorInput, length: Long): Boolean {
        var left = length
        val scratch = ByteArray(4096)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n <= 0) return false
            left -= n
        }
        return true
    }

    private companion object {
        const val STATE_HEADER = 0
        const val STATE_SAMPLES = 1
        const val STATE_SEEK = 2
        const val SAMPLE_CHUNK_BYTES = 8192

        fun readBeU16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

        fun readBeU32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xff) shl 24) or
                ((b[off + 1].toLong() and 0xff) shl 16) or
                ((b[off + 2].toLong() and 0xff) shl 8) or
                (b[off + 3].toLong() and 0xff)

        /** IEEE 754 80-bit extended（AIFF COMM 采样率）→ Int；不支持非正规/非有限值。 */
        fun extended80ToInt(b: ByteArray, off: Int): Int {
            val e = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
            val exp = e and 0x7fff
            // 尾数 64 位最高位可能为 1（如 48000Hz 的 0xbb80...），用 Long 会变负数。
            // 直接以 Double 逐字节累加，保留无符号语义。
            var mant = 0.0
            for (i in 2..9) mant = mant * 256.0 + (b[off + i].toInt() and 0xff)
            if (exp == 0 || exp == 0x7fff || mant == 0.0) return 0
            val value = mant / 2.0.pow(63) * 2.0.pow(exp - 16383)
            return if (value in 1.0..1_000_000.0) value.toInt() else 0
        }
    }
}
