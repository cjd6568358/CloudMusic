package com.ccmsc.cloudmusic.di.adapters

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ccmsc.cloudmusic.di.scan.PcmData
import com.ccmsc.cloudmusic.di.scan.PcmDecoder
import com.ccmsc.cloudmusic.model.Track

/**
 * §61.256（V0.3 M5 / A1 Tier3）：生产 PCM 解码器——MediaExtractor + MediaCodec 标准解码环。
 *
 * - 源 = [Track.sourceRef]：缓存直链（strm/playlist 的 http URL，自包含签名无需头）或
 *   本地路径；两者皆无（如孤儿 SFTP 源）→ null 如实跳过；
 * - [maxSeconds] 限秒：解码到上限即停（指纹只需代表性片段，防整曲驻留内存）；
 * - 混缩：多声道取均值 → 单声道；返回原始采样率（指纹核内部重采样）；
 * - 异常 / 无音轨 / 解码器未就绪 → null（会话计跳过，不伪造）。
 * JVM 不可测（Android 框架类）——判据 = 真机声纹会话（pending-device 登记）。
 */
class MediaPcmDecoder : PcmDecoder {

    override suspend fun decodeMono(track: Track, maxSeconds: Int): PcmData? {
        val source = track.sourceRef.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: track.sourceRef.path.takeIf { path ->
                !path.startsWith("/") || path.startsWith("/storage") || path.startsWith("/data") ||
                    java.io.File(path).exists()
            }
            ?: return null
        return runCatching { decodeInternal(source, maxSeconds) }.getOrNull()
    }

    private fun decodeInternal(source: String, maxSeconds: Int): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return null
            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val maxSamples = (sampleRate.toLong() * maxSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val out = ArrayList<Float>(maxSamples)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var timeoutUs = 10_000L
            try {
                while (!outputDone && out.size < maxSamples) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex) ?: break
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            format = codec.outputFormat
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            val buf = codec.getOutputBuffer(outIndex) ?: break
                            val shorts = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            var f = 0
                            while (f < frames && out.size < maxSamples) {
                                var acc = 0f
                                for (c in 0 until channels) acc += shorts.get(f * channels + c) / 32768f
                                out += acc / channels
                                f++
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            if (out.isEmpty()) return null
            return PcmData(out.toFloatArray(), sampleRate)
        } finally {
            extractor.release()
        }
    }
}
