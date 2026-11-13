package androidx.media3.extractor.ape

import android.util.Log
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
    private var finalFrameBlocks = 0
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
        // §61.94c：SeekMap 延后到 readHeader（帧表/时长就绪后）下发——Mp4Extractor
        // 同型：Media3 的 PreparedState 在 seekMap() 时一次性捕获 duration，
        // init 期时长未知的 SeekMap 会让 period 永不 prepared。
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
        // §61.94b（ffmpeg ape.c v3980+ 分支）：version 后有 padding1 u16 @6，
        // 后续五长度 @8/12/16/20/24 + audiodata/audiodata_high/wavtail @28/32/36
        // —— 初版实现漏 padding1 导致全部字段错位 2B（合法 APE 被误拒）。
        val padding1 = readLeU16(desc, 6)
        val descriptorLength = readLeU32(desc, 8).toInt()
        val headerLength = readLeU32(desc, 12).toInt()
        val seekTableLength = readLeU32(desc, 16).toInt()
        val wavHeaderLength = readLeU32(desc, 20).toInt()
        val audioDataLength = readLeU32(desc, 24).toInt()
        val audioDataLengthHigh = readLeU32(desc, 28).toInt()
        val wavTailLength = readLeU32(desc, 32).toInt()
        if (version < 3960) {
            // v<3960 老头变体（无 descriptor）：真机样本为 3974，本轮如实不支持。
            return Extractor.RESULT_END_OF_INPUT
        }
        if (descriptorLength > 52) {
            input.skipFully(descriptorLength - 52)
        }
        // §61.94b（ffmpeg ape.c v3980+ header）：24B = compression u16 + flags u16 +
        // blocksperframe u32 + finalframeblocks u32 + totalframes u32 + bps u16 +
        // channels u16 + samplerate u32（字段序与 v<3960 完全不同！）
        val hdr = ByteArray(24)
        if (!input.readFully(hdr, 0, 24, true)) return Extractor.RESULT_END_OF_INPUT
        val compressionType = readLeU16(hdr, 0)
        val formatFlags = readLeU16(hdr, 2)
        blocksPerFrame = readLeU32(hdr, 4).toInt()
        val finalFrameBlocks = readLeU32(hdr, 8).toInt()
        totalFrames = readLeU32(hdr, 12).toInt()
        channels = readLeU16(hdr, 18).toInt()
        sampleRate = readLeU32(hdr, 20).toInt()
        val headerLen = headerLength
        val seekLen = seekTableLength
        if (totalFrames <= 0 || seekLen < totalFrames * 4) {
            return Extractor.RESULT_END_OF_INPUT   // 无帧 / seek 表截断（ape.c 同判）
        }
        this.finalFrameBlocks = finalFrameBlocks

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
        // §61.94b（Media3 一手栈：Loading finished before preparation is complete）：
        // 截断文件——SeekMap 声明时长必须与实际可投递数据一致，否则 Media3 在
        // EOS 时判 MALFORMED。totalFrames 收敛到可用数据内的最后完整帧。
        if (inputLength >= 0) {
            val lastValid = frames.indices.lastOrNull { frames[it] < inputLength } ?: -1
            if (lastValid < 0) return Extractor.RESULT_END_OF_INPUT
            totalFrames = lastValid + 1
            frames = frames.copyOf(totalFrames)
        }

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType("audio/x-ape")
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                .setCodecs("ape")
                .build(),
        )
        // §61.94c：时长就绪（totalFrames 已按可用数据收敛）→ 下发 SeekMap。
        val out = output ?: return Extractor.RESULT_END_OF_INPUT
        out.endTracks()   // §61.94c：单轨也要 endTracks——Media3 的
        // sampleQueuesBuilt 只在 endTracks 置位，缺它则 prepared 永不完成
        //（Loader finished before preparation is complete）。
        out.seekMap(
            object : SeekMap {
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
        state = STATE_READ_FRAMES
        currentFrame = 0
        return Extractor.RESULT_CONTINUE
    }

    /** 逐帧投递：pos 不符用 RESULT_SEEK，帧数据整块作为一帧样本（APE 帧内含 3B 对齐残数）。 */
    private fun readFrames(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (currentFrame < totalFrames) {
            if (inputLength < 0) inputLength = input.length
            val pos = frames[currentFrame]
            // §61.94b：截断/边界防护——帧起点超出可用数据长度 = 可用音频已投递完毕。
            if (input.length >= 0 && pos >= input.length) {
                logI("CM-Ape", "frames beyond data end @%d (frame %d/%d) — EOF".format(
                    pos, currentFrame, totalFrames))
                return Extractor.RESULT_END_OF_INPUT
            }
            val next = frames.getOrNull(currentFrame + 1) ?: C.LENGTH_UNSET.toLong()
            val size = frameSize(currentFrame, next)
            if (size <= 0) {
                currentFrame++
                continue
            }
            // §61.94b：残帧不投递——APE 解码器拒收截断帧（FrameParsing → MALFORMED）。
            // 完整帧判定：帧数据（含 skip 前缀 8B）必须完全落在可用数据长度内。
            if (input.length >= 0 && pos + size + 8 > input.length) {
                logI("CM-Ape", "末帧不完整（pos=%d size=%d avail=%d）— EOF".format(
                    pos, size, input.length - input.position))
                return Extractor.RESULT_END_OF_INPUT
            }
            if (input.position != pos) {
                val delta = pos - input.position
                // §61.107：REMOTE 真机一手证据 —— 每次 RESULT_SEEK 会让
                // ProgressiveMediaPeriod 重开 CacheDataSource（ape-remote 30s
                // 只推进到第 2 帧、每次 9~17s；同一帧序列 LOCAL 22 次仅 8ms）。
                // 顺序前进改为 skipFully 顺序读，避免取流请求反复重开；
                // 回退 / 超大跨度仍用 RESULT_SEEK（保持 seek 语义）。
                if (delta in 1..MAX_SEQUENTIAL_SKIP &&
                    (input.length < 0 || pos < input.length)) {
                    if (!runCatching { input.skipFully(delta.toInt()) }.isSuccess) {
                        return Extractor.RESULT_END_OF_INPUT
                    }
                } else {
                    logI("CM-Ape", "seek to pos=%d frame=%d/%d len=%d".format(
                        pos, currentFrame, totalFrames, input.length))
                    seekPosition.position = pos
                    return Extractor.RESULT_SEEK
                }
            }
            // §61.94b（ffmpeg ape_read_packet 逐字对齐）：APE 解码器的帧输入 =
            // [nblocks u32][skip u32][帧数据（含对齐残数）] —— 8B 前缀是解码契约！
            val nblocks = if (currentFrame == totalFrames - 1) {
                finalFrameBlocks
            } else {
                blocksPerFrame
            }
            val skip = ((pos - firstFrameOffset) and 3).toInt()
            val prefix = ByteArray(8)
            writeLeU32(prefix, 0, nblocks)
            writeLeU32(prefix, 4, skip)
            trackOutput?.sampleData(
                androidx.media3.common.util.ParsableByteArray(prefix), 8,
            )
            // §61.94b：截断文件——喂入长度按可用数据截取（DataSource 越界读会抛
            // position out of range → Media3 包成 MALFORMED；截断的末帧解码器自行处理）。
            val avail = if (input.length >= 0) (input.length - input.position).toInt() else size.toInt()
            val feedLen = minOf(size.toInt(), avail)
            // §61.107：REMOTE 下 DataSource 可能一次只给部分数据；旧实现单次
            // sampleData(..., true) 会把截断帧投给解码器（真机
            // `ApeDecWrapper: Bad input arguments to ape_dec_process`）。
            // 循环读满 feedLen（前缀 8B 已单独写入），仍以 fed 为实际投递长度。
            var fed = 0
            var remaining = feedLen
            while (remaining > 0) {
                val n = runCatching {
                    trackOutput?.sampleData(input, remaining, false) ?: -1
                }.getOrDefault(-1)
                if (n <= 0) break
                fed += n
                remaining -= n
            }
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
                C.BUFFER_FLAG_KEY_FRAME, 8 + fed, 0, null,
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

    private fun durationUs(): Long {
        if (sampleRate <= 0 || blocksPerFrame <= 0) return C.TIME_UNSET
        // §61.94b：截断文件——时长按「可用数据内的最后帧」计算（声明时长若超过
        // 实际投递，Media3 在 EOS 时报 MALFORMED）。
        if (inputLength >= 0 && frames.isNotEmpty()) {
            val lastValid = frames.indices.lastOrNull { frames[it] < inputLength } ?: return 0L
            val endFrame = (lastValid + 1).coerceAtMost(totalFrames)
            return frameTimeUs(endFrame)
        }
        return blocksPerFrame.toLong() * totalFrames * 1_000_000L / sampleRate
    }

    private var inputLength = -1L

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

    private fun writeLeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

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
        /** §61.107：顺序跳过的上限（2MiB）；超过则退回 RESULT_SEEK。 */
        private const val MAX_SEQUENTIAL_SKIP = 2L * 1024 * 1024
        /**
         * `12 §61.101`：纯 JVM 单测里 `android.util.Log` 是未 mock 的 stub，
         * 直接调 `Log.i` 会抛 `RuntimeException: Method i ... not mocked`
         * （1485ce0f 引入的排障日志导致 ApeExtractorTest 2 例回归）。
         * 与 `AsfExtractor.logW` 同型：生产仍走 Log，无 Android 运行时退化为 println。
         */
        private fun logI(tag: String, msg: String) {
            try {
                Log.i(tag, msg)
            } catch (t: Throwable) {
                println("[$tag] $msg")
            }
        }

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
            logI("CM-Factory", "createExtractors called")
            val default = androidx.media3.extractor.DefaultExtractorsFactory().createExtractors()
            val arr = arrayOf<Extractor>(
                ApeExtractor(),
                androidx.media3.extractor.asf.AsfExtractor(),   // §61.97：WMA/ASF 容器
                androidx.media3.extractor.aiff.AiffExtractor(),       // §61.102：AIFF PCM
                androidx.media3.extractor.wavpack.WavpackExtractor(), // §61.102：WavPack
            ) + default
            logI("CM-Factory", "extractors: ${arr.size} (ape/asf + ${default.size} default)")
            arr
        }
    }
}
