package androidx.media3.extractor.ape

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * §61.94（B2 M4.1 · 容器缺口补齐 ①）：Monkey's Audio（APE）容器 Extractor。
 *
 * Media3 1.11.1 的 DefaultExtractorsFactory 无 APE 支持（§61.92 实证清单），
 * 而解码器侧 `ff_ape_decoder` 已随 FFmpeg 扩展编入（§61.83 符号实证）——缺的
 * 只是容器解析。本类按 `libavformat/ape.c`（FFmpeg 6.0）的格式事实手写：
 *
 * - Descriptor（v≥3960）：`MAC ` magic + version + descriptor/header/seektable/
 *   wavheader/wavtaillength 五长度 + MD5（52B 含保留区）；
 * - Header：compression/format-flags/channels/samplerate/…/totalframes/
 *   finalframeblocks（flags 位含 peak/seek-elements/8·24bit/wav-header 变体）；
 * - Seek 表：totalframes × u32 **绝对文件偏移**，帧 i 大小 = pos[i+1] − pos[i]，
 *   末帧 = 文件尾 − APE 标签；帧对齐残数 skip 随帧数据投递（解码器自处理）。
 *
 * 真机样本：F3 的 `f3_ape.ape`（OpenList 真实音频前 4MiB 截断片段）——帧表
 * 完整（靠前），帧数据到 EOF 截断：读侧到 EOF 即 `RESULT_END_OF_INPUT`，
 * 只验证可用部分（F3 判据：demuxer 识别 + codec 打开 + 播放链）。
 */
@UnstableApi
class ApeExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var sampleRate = 0
    private var channels = 0
    private var blocksPerFrame = 0
    private var totalFrames = 0
    private var firstFrameOffset = 0L
    private var frames = LongArray(0)          // 每帧绝对偏移

    private var state = STATE_READ_HEADER
    private var currentFrame = 0
    private var tagEnd = -1L   // APE tag 起点探测结果（-1 = 未探测/无 tag）
    private var tagChecked = false

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(6)
        if (!input.peekFully(header, 0, 6, true)) return false
        return isApeMagic(header) && readLeU16(header, 4) in 3930..4000
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.seekMap(
            object : SeekMap {
                // 帧表在 read 阶段解析完成后才可用——委托到本类字段（Mp4Extractor 同型）。
                override fun isSeekable(): Boolean = frames.isNotEmpty()
                override fun getDurationUs(): Long = durationUs()
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val idx = frameForTimeUs(timeUs)
                    return SeekMap.SeekPoints(
                        SeekPoint(frameTimeUs(idx), frames.getOrElse(idx) { firstFrameOffset }),
                    )
                }
            },
        )
    }

    override fun seek(position: Long, timeUs: Long) {
        // 外部 seek：按 timeUs 定位帧游标；帧表未就绪时回到头解析状态重走。
        if (frames.isEmpty()) {
            state = STATE_READ_HEADER
            currentFrame = 0
            return
        }
        currentFrame = frameForTimeUs(timeUs)
        state = STATE_READ_FRAMES
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        when (state) {
            STATE_READ_HEADER -> readHeader(input, seekPosition)
            STATE_READ_FRAMES -> readFrames(input, seekPosition)
            STATE_TAG_CHECK -> tagCheck(input, seekPosition)
            else -> Extractor.RESULT_END_OF_INPUT
        }

    /** §61.94b：尾部 APE tag（APETAGEX）探测——排除 tag 字节污染末帧。 */
    private fun tagCheck(input: ExtractorInput, seekPosition: PositionHolder): Int {
        tagChecked = true
        val window = ByteArray(128)
        if (input.readFully(window, 0, window.size, true)) {
            val marker = "APETAGEX".toByteArray()
            val idx = indexOf(window, marker)
            if (idx >= 0) {
                // APE tag v2 footer：marker(8) + version(4) + tagSize(4，不含 footer 32B)
                val tagSize = readLeU32(window, idx + 12).toInt() + 32
                tagEnd = input.length - window.size + idx + tagSize
            }
        }
        // 回到当前帧位置续投（tagEnd 会收紧 frameSize 的 EOF 兜底）。
        val pos = frames.getOrElse(currentFrame) { firstFrameOffset }
        seekPosition.position = pos
        state = STATE_READ_FRAMES
        return Extractor.RESULT_SEEK
    }

    private fun indexOf(window: ByteArray, pattern: ByteArray): Int {
        for (i in 0..window.size - pattern.size) {
            var matched = true
            for (j in pattern.indices) {
                if (window[i + j] != pattern[j]) { matched = false; break }
            }
            if (matched) return i
        }
        return -1
    }

    /** 解析 Descriptor + Header + Seek 表，就绪帧表并下发 Format。 */
    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val desc = ByteArray(52)
        if (!input.readFully(desc, 0, 52, true)) return Extractor.RESULT_END_OF_INPUT
        if (!isApeMagic(desc)) return Extractor.RESULT_END_OF_INPUT
        val version = readLeU16(desc, 4)
        val descriptorLength = readLeU32(desc, 6).toInt()
        val headerLength = readLeU32(desc, 10).toInt()
        val seekTableLength = readLeU32(desc, 14).toInt()
        val wavHeaderLength = readLeU32(desc, 18).toInt()
        if (version < 3960) {
            // v<3960 老头变体（无 descriptor）：真机样本为 3974，本轮如实不支持。
            return Extractor.RESULT_END_OF_INPUT
        }
        if (descriptorLength > 52) {
            input.skipFully(descriptorLength - 52)
        }
        val hdr = ByteArray(24)
        if (!input.readFully(hdr, 0, 24, true)) return Extractor.RESULT_END_OF_INPUT
        val compressionType = readLeU16(hdr, 0)
        val formatFlags = readLeU16(hdr, 2)
        channels = readLeU16(hdr, 4).toInt()
        sampleRate = readLeU32(hdr, 6).toInt()
        var headerLen = headerLength
        var seekLen = seekTableLength
        var extra = 0
        if (formatFlags and FLAG_HAS_PEAK_LEVEL != 0) extra += 4
        if (formatFlags and FLAG_HAS_SEEK_ELEMENTS != 0) {
            seekLen = readLeU16(hdr, 20).toInt() * 4
            extra += 4
        }
        headerLen += extra
        blocksPerFrame = when {
            version >= 3950 -> 73728 * 4
            version >= 3900 || (version >= 3800 && compressionType >= 4000) -> 73728
            else -> 9216
        }
        val totalFrames = readLeU32(hdr, 16).toInt()
        if (totalFrames <= 0 || seekLen < totalFrames * 4) {
            return Extractor.RESULT_END_OF_INPUT   // 无帧 / seek 表截断（ape.c 同判）
        }
        this.totalFrames = totalFrames

        val skipWav = formatFlags and FLAG_CREATE_WAV_HEADER == 0
        if (skipWav && wavHeaderLength in 1..0x100000) {
            input.skipFully(wavHeaderLength.toInt())
        }
        // firstframe = descriptor + header + seektable + wavheader（+v<3810 的逐帧计数）。
        val wavSkip = if (skipWav) wavHeaderLength.toLong() else 0L
        firstFrameOffset = descriptorLength.toLong() + headerLen.toLong() +
            seekTableLength.toLong() + wavSkip
        if (version < 3810) firstFrameOffset += totalFrames

        // Seek 表：entry[0] 冗余（= firstframe），entry[i] = 帧 i 绝对偏移。
        frames = LongArray(totalFrames)
        frames[0] = firstFrameOffset
        input.skipFully(4)
        val entry = ByteArray(4)
        for (i in 1 until totalFrames) {
            if (!input.readFully(entry, 0, 4, true)) return Extractor.RESULT_END_OF_INPUT
            frames[i] = readLeU32(entry, 0) and 0xFFFFFFFFL
        }

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType("audio/x-ape")
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                .setCodecs("ape")
                .build(),
        )
        state = STATE_READ_FRAMES
        currentFrame = 0
        return Extractor.RESULT_CONTINUE
    }

    /** 逐帧投递：pos 不符用 RESULT_SEEK，帧数据整块作为一帧样本（APE 帧内含 3B 对齐残数）。 */
    private fun readFrames(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (currentFrame < totalFrames) {
            val pos = frames[currentFrame]
            val next = frames.getOrNull(currentFrame + 1) ?: C.LENGTH_UNSET.toLong()
            val size = frameSize(currentFrame, next)
            if (size <= 0) {
                currentFrame++
                continue
            }
            if (input.position != pos) {
                // §61.94：seek 到当前帧的绝对偏移；游标不推进——下一次 read 时
                // input.position == pos，直接投递本帧（推进会造成逐帧空转跳过）。
                seekPosition.position = pos
                return Extractor.RESULT_SEEK
            }
            val fed = trackOutput?.sampleData(input, size.toInt(), true) ?: 0
            if (fed <= 0) {
                // EOF：①真实文件 = 最后帧读完或到 tag；②截断样本 = 可用部分投递完毕。
                if (!tagChecked && input.length >= 0) {
                    // APE tag 尾部探测：回 seek 到尾窗（128B）读 APETAGEX 标记。
                    state = STATE_TAG_CHECK
                    seekPosition.position = (input.length - 128).coerceAtLeast(0)
                    return Extractor.RESULT_SEEK
                }
                return Extractor.RESULT_END_OF_INPUT
            }
            trackOutput?.sampleMetadata(
                frameTimeUs(currentFrame),
                C.BUFFER_FLAG_KEY_FRAME, fed, 0, null,
            )
            currentFrame++
            return Extractor.RESULT_CONTINUE
        }
        return Extractor.RESULT_END_OF_INPUT
    }

    private fun frameSize(index: Int, nextPos: Long): Long {
        val pos = frames[index]
        val end = if (tagEnd >= 0) tagEnd else -1L
        return when {
            nextPos > pos -> if (end >= 0) minOf(nextPos, end) - pos else nextPos - pos
            end >= 0 && end > pos -> end - pos
            else -> blocksPerFrame.toLong()   // 末帧/截断：标称块数上界，EOF/tag 兜底
        }
    }

    private fun durationUs(): Long =
        if (sampleRate <= 0 || blocksPerFrame <= 0) {
            C.TIME_UNSET
        } else {
            blocksPerFrame.toLong() * totalFrames * 1_000_000L / sampleRate
        }

    private fun frameTimeUs(idx: Int): Long =
        if (sampleRate <= 0 || blocksPerFrame <= 0) {
            0L
        } else {
            blocksPerFrame.toLong() * idx * 1_000_000L / sampleRate
        }

    private fun frameForTimeUs(timeUs: Long): Int {
        if (sampleRate <= 0 || blocksPerFrame <= 0) return 0
        val frame = (timeUs * sampleRate / (blocksPerFrame.toLong() * 1_000_000L)).toInt()
        return frame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
    }

    private fun isApeMagic(b: ByteArray): Boolean =
        b[0] == 'M'.code.toByte() && b[1] == 'A'.code.toByte() &&
            b[2] == 'C'.code.toByte() && b[3] == ' '.code.toByte()

    private fun readLeU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLeU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    override fun release() {
        output = null
        trackOutput = null
    }

    companion object {
        private const val STATE_READ_HEADER = 0
        private const val STATE_READ_FRAMES = 1
        private const val STATE_TAG_CHECK = 2
        private const val FLAG_HAS_PEAK_LEVEL = 0x01
        private const val FLAG_HAS_SEEK_ELEMENTS = 0x02
        private const val FLAG_CREATE_WAV_HEADER = 0x8000

        /**
         * §61.94：默认清单 + Ape（APE 置首——`MAC ` sniff 唯一性强，
         * 避免被 Mp3Extractor 的 MPEG 同步误命中）。
         */
        @UnstableApi
        fun factoryWithApe(): ExtractorsFactory = ExtractorsFactory {
            val default = androidx.media3.extractor.DefaultExtractorsFactory().createExtractors()
            arrayOf<Extractor>(
                ApeExtractor(),
                androidx.media3.extractor.asf.AsfExtractor(),   // §61.97：WMA/ASF 容器
            ) + default
        }
    }
}
