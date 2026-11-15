package com.example.cloudmusic.engineopensource

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * §61.160（T5-3 路径 B 第一效果）：声场展宽处理器（mid/side 立体声展宽）。
 *
 * 挂入 Media3 官方 PCM 链（`DefaultAudioSink.Builder.setAudioProcessors`，
 * E6 插入点）——**仅处理 16-bit 立体声**，其余格式/声道数原样透传（B01 降级）。
 * 展宽算法 = L/R → mid/side 变换 + side 通道按 widenAmount 放大 → 反变换；
 * widenAmount ∈ [0,1]，0 = 恒等（直通）。
 *
 * 状态经 [AudioEffectsBus]（进程级单例，DriverRegistry 同型）——
 * PlaybackRuntime 按音效档位写入，处理器逐包读取（无锁 volatile）。
 * 默认 0（直通），零行为变化基线。
 */
@UnstableApi
class SpatialWidenProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var pcmEncoding = C.ENCODING_PCM_16BIT



    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = inputAudioFormat.channelCount
        pcmEncoding = inputAudioFormat.encoding
        // 仅 16-bit PCM（2/1 声道）参与处理；其余原样（输出 = 输入格式）
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val out = replaceOutputBuffer(remaining)
        if (!isActiveProcessing()) {
            // 直通：B01 降级（非立体声 / 非 16-bit / amount=0）
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bytes = ByteArray(remaining)
        inputBuffer.get(bytes)
        widen16Stereo(bytes, AudioEffectsBus.widenAmount)
        out.put(bytes)
        out.flip()
    }

    private fun isActiveProcessing(): Boolean =
        AudioEffectsBus.widenAmount > 0f && channels == 2 && pcmEncoding == C.ENCODING_PCM_16BIT

    /**
     * 16-bit 交错立体声 mid/side 展宽：mid=(L+R)/2, side=(L-R)/2 →
     * L'=mid+side*k, R'=mid-side*k（k = 1 + widenAmount，1 = 恒等）。
     * 逐 4 字节帧处理；奇数尾字节原样（帧对齐由 AudioSink 保证）。
     */
    private fun widen16Stereo(bytes: ByteArray, amount: Float) {
        val k = 1f + amount
        var i = 0
        val n = bytes.size - (bytes.size % 4)
        while (i < n) {
            val l = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
            val r = ((bytes[i + 2].toInt() and 0xFF) or (bytes[i + 3].toInt() shl 8)).toShort()
            val mid = (l + r) / 2f
            val side = (l - r) / 2f
            var nl = mid + side * k
            var nr = mid - side * k
            // 钳制防削波
            nl = nl.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            nr = nr.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            bytes[i] = (nl.toInt() and 0xFF).toByte()
            bytes[i + 1] = ((nl.toInt() shr 8) and 0xFF).toByte()
            bytes[i + 2] = (nr.toInt() and 0xFF).toByte()
            bytes[i + 3] = ((nr.toInt() shr 8) and 0xFF).toByte()
            i += 4
        }
    }
}

/** §61.160：进程级音效状态总线（DriverRegistry 同型单例；volatile 无锁）。 */
object AudioEffectsBus {
    /** 声场展宽量 [0,1]；0 = 直通。PlaybackRuntime 按音效档位写入。 */
    @Volatile
    var widenAmount: Float = 0f
}
