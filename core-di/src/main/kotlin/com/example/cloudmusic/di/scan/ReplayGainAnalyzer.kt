package com.example.cloudmusic.di.scan

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * §61.122（T5-2 分析管线第一版）：**近似响度分析**——平台 MediaExtractor +
 * MediaCodec 解码（LOCAL 可解格式：mp3/aac/flac/wav/ogg/m4a；ape/wma/wv 平台
 * 不可解 → 返回 null = NO_SAMPLE，如实登记不伪造）。
 *
 * 算法：解码前 [maxSeconds] 秒 PCM → 均值混音 → 全程 RMS → dBFS →
 * `gain = -14 - dBFS`（对齐 ReplayGain 的 -14 dB 参考响度）。
 * **诚实边界：未用 RG 规范等响 Yule 滤波器，为近似值**（仅作无标签曲目的
 * 兜底增益；规范实现随 T5-2 后续批评估）。
 *
 * 纯解码 + 数值计算，不触碰播放链（§61.109 边界同型：Analysis 不持引擎）。
 */
object ReplayGainAnalyzer {

    /** 返回增益 dB（负值=衰减）；不可解 / 无音频轨 / 解码失败 → null。 */
    fun analyze(path: String, maxSeconds: Int = 90): Float? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val trackIdx = (0 until extractor.trackCount)
                .firstOrNull {
                    extractor.getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val maxSamples = sampleRate.toLong() * channels * maxSeconds
                var sumSquares = 0.0
                var samples = 0L
                var sawInputEos = false
                var sawOutputEos = false
                val bufferInfo = MediaCodec.BufferInfo()
                var guard = 0
                while (!sawOutputEos && samples < maxSamples && guard++ < 100_000) {
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx) ?: continue
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            val out = codec.getOutputBuffer(outIdx)
                            if (out != null && bufferInfo.size > 0) {
                                out.position(bufferInfo.offset)
                                out.limit(bufferInfo.offset + bufferInfo.size)
                                // 16-bit PCM 短整型逐样本平方和（混音 = 多声道求均值）
                                while (out.remaining() >= 2 && samples < maxSamples) {
                                    val v = out.short.toFloat() / 32768f
                                    sumSquares += (v * v).toDouble()
                                    samples++
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> Unit
                    }
                }
                if (samples < sampleRate.toLong() * channels) return null   // <1s 内容不足
                val rms = sqrt(sumSquares / samples)
                if (rms <= 0.0) return null
                val dbfs = 20.0 * log10(rms)
                // RG 参考：校准到 -14 dB 目标响度 → 增益 = 目标 - 实际
                (-14.0 - dbfs).toFloat().coerceIn(-30f, 30f)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }.getOrNull()
}
