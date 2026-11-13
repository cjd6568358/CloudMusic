package com.example.cloudmusic.di.scan

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.cloudmusic.model.ReplayGainSpecMath
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * §61.122/§61.123（T5-2 分析管线）：**近似 → 规范**两级响度分析——平台
 * `MediaExtractor + MediaCodec` 解码（LOCAL 可解格式：mp3/aac/flac/wav/ogg/m4a；
 * ape/wma/wv 视平台扩展而定；平台不可解 → null = NO_SAMPLE，如实登记）。
 *
 * - **规范路径**：采样率 ∈ {44.1k, 48k} 时走 [ReplayGainSpecMath]
 *   （RG 1.0 等响滤波 + 50ms 分块 + 95 分位 + PINK_REF=64.82，系数逐字取自
 *   HydrogenAudio 规范表）；
 * - **近似回退**：其它采样率用全程 RMS → `-14 - dBFS`（§61.122 首版，
 *   无规范滤波器，边界如实）。
 *
 * 纯解码 + 数值计算，不触碰播放链（Analysis 不持引擎）。
 */
object ReplayGainAnalyzer {

    /** 返回增益 dB（负值=衰减）与所用路径；不可解 / 无音频轨 / 解码失败 → null。 */
    fun analyze(path: String, maxSeconds: Int = 90): AnalyzedGain? = runCatching {
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
                val maxFrames = sampleRate.toLong() * maxSeconds
                // 下混后的单声道 PCM（多声道交错求均值；容量按帧数预留）
                val pcm = ShortArray((maxFrames * channels).toInt().coerceAtLeast(1))
                var monoCount = 0L
                var sawInputEos = false
                var sawOutputEos = false
                val bufferInfo = MediaCodec.BufferInfo()
                var guard = 0
                while (!sawOutputEos && monoCount < maxFrames && guard++ < 100_000) {
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
                                while (out.remaining() >= 2 * channels &&
                                    monoCount < maxFrames
                                ) {
                                    var acc = 0.0
                                    repeat(channels) {
                                        acc += out.short.toDouble()
                                    }
                                    out.position(out.position() + 2 * channels)
                                    pcm[monoCount.toInt()] =
                                        (acc / channels).coerceIn(-32768.0, 32767.0)
                                            .toInt().toShort()
                                    monoCount++
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
                if (monoCount < sampleRate.toLong()) return null   // <1s 内容不足
                val mono = pcm.copyOf(monoCount.toInt())
                // §61.123：规范路径优先（44.1k/48k），近似回退（其它采样率）。
                val spec = ReplayGainSpecMath.calculateGainDb(mono, sampleRate)
                if (spec != null) {
                    return AnalyzedGain(spec, "spec")
                }
                var sumSquares = 0.0
                for (s in mono) {
                    val v = s.toFloat() / 32768f
                    sumSquares += (v * v).toDouble()
                }
                val rms = sqrt(sumSquares / monoCount)
                if (rms <= 0.0) return null
                val dbfs = 20.0 * log10(rms)
                AnalyzedGain((-14.0 - dbfs).toFloat().coerceIn(-30f, 30f), "approx")
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /** 分析结果：增益 dB + 所用路径（spec / approx，供日志与诚实边界）。 */
    data class AnalyzedGain(val gainDb: Float, val path: String)
}
