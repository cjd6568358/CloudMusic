package com.example.cloudmusic.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.sin

/**
 * §61.256（V0.3 M5 / A1 Tier3）：自研纯 Kotlin 音频指纹——**零新依赖**（规避 A2 Scala /
 * A3 NDK 拍板闸门；算法为 Shazam 风格频谱峰值地标哈希的保守简化）。
 *
 * 管线：PCM 单声道 float → 重采样 8kHz → 分帧 FFT（radix-2）→ 每帧取能量 Top 峰（邻域
 * 抑制）→ 相邻帧峰值配对 → 量化哈希（64bit long）。相似度 = 哈希多重集交集 / min 大小。
 * 哈希只编码峰值频率对与[PAIR_DELTA_FRAMES]固定帧距，**不编码绝对帧号**——前导静音 /
 * 头部截断造成整体时间偏移时哈希集不变（§61.258 偏移哈希挂账收口）。
 *
 * 口径（如实登记）：
 * - 自研算法精度无第三方基准——只用于「转码重复**疑似**升级确认」（与 Tier1 内容哈希、
 *   Tier2 元数据同层叠），匹配阈值保守（[MATCH_THRESHOLD]），处置完全用户手动（PRD 原则 6）。
 * - PCM 由 [「组合层解码端口」] 提供（MediaCodec 解码 + 单声道混缩 + 限秒），本对象纯 JVM。
 */
object AudioFingerprint {

    /** 目标重采样率（Hz）——频谱分辨率与计算量的平衡点。 */
    const val TARGET_RATE: Int = 8_000

    /** FFT 窗长（8kHz 下 ≈512ms 窗）。 */
    const val FRAME_SIZE: Int = 4096

    /** 帧移（1/4 窗）。 */
    const val HOP: Int = FRAME_SIZE / 4

    /** 每帧保留的峰值数（邻域抑制后）。 */
    const val PEAKS_PER_FRAME: Int = 5

    /** 匹配阈值：交集/min ≥ 此值判「疑似同源」。 */
    const val MATCH_THRESHOLD: Double = 0.35

    /**
     * 峰值对的固定帧距（相邻帧）。只编码「频率对 + 相对距离」，不编码绝对时间位置：
     * 时间轴上整体平移（前导静音 / 截掉片头）不会改变哈希集（§61.258）。
     */
    const val PAIR_DELTA_FRAMES: Int = 1

    /** 重采样（线性插值降采样；升采样不支持——解码侧先保证 ≤TARGET_RATE 或就近取）。 */
    internal fun resample(pcm: FloatArray, srcRate: Int): FloatArray {
        if (srcRate <= TARGET_RATE) return pcm
        val ratio = srcRate.toDouble() / TARGET_RATE
        val outLen = (pcm.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = pos - i0
            out[i] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toFloat()
        }
        return out
    }

    /** radix-2 FFT（迭代式，实数输入按复数处理——规模 4096 下性能足够）。 */
    internal fun fftMagnitude(frame: FloatArray): DoubleArray {
        val n = frame.size
        val re = DoubleArray(n) { frame[it].toDouble() }
        val im = DoubleArray(n)
        // 位反转重排
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var start = 0
            while (start < n) {
                var pr = 1.0; var pi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[start + k]; val ui = im[start + k]
                    val vr = re[start + k + len / 2] * pr - im[start + k + len / 2] * pi
                    val vi = re[start + k + len / 2] * pi + im[start + k + len / 2] * pr
                    re[start + k] = ur + vr; im[start + k] = ui + vi
                    re[start + k + len / 2] = ur - vr; im[start + k + len / 2] = ui - vi
                    val npr = pr * wr - pi * wi
                    pi = pr * wi + pi * wr
                    pr = npr
                }
                start += len
            }
            len = len shl 1
        }
        val half = n / 2
        val mag = DoubleArray(half)
        for (i in 0 until half) mag[i] = hypot(re[i], im[i])
        return mag
    }

    /** 单帧峰值：Top-[PEAKS_PER_FRAME] 能量 bin（±3 bin 邻域抑制，避免谐波连片）。 */
    internal fun framePeaks(mag: DoubleArray): IntArray {
        val used = BooleanArray(mag.size)
        val peaks = mutableListOf<Int>()
        val order = (1 until mag.size).sortedByDescending { mag[it] }
        for (bin in order) {
            if (peaks.size >= PEAKS_PER_FRAME) break
            if (used[bin]) continue
            peaks += bin
            for (d in -3..3) {
                val nb = bin + d
                if (nb in used.indices) used[nb] = true
            }
        }
        return peaks.toIntArray()
    }

    /**
     * PCM → 指纹哈希集（Long 集合）。[pcm] 必须单声道；[sampleRate] 为其采样率。
     * 哈希 = 相邻帧峰值对（bin1 量化到 8 bin 粒度、bin2 同、固定帧距[PAIR_DELTA_FRAMES]），
     * 符号位防负；绝对帧号不入哈希，故整体时间偏移鲁棒（§61.258）。
     */
    fun fingerprint(pcm: FloatArray, sampleRate: Int): Set<Long> {
        if (pcm.size < FRAME_SIZE) return emptySet()
        val resampled = resample(pcm, sampleRate)
        val hashes = mutableSetOf<Long>()
        var prevPeaks: IntArray? = null
        var pos = 0
        while (pos + FRAME_SIZE <= resampled.size) {
            val frame = resampled.copyOfRange(pos, pos + FRAME_SIZE)
            val peaks = framePeaks(fftMagnitude(frame))
            prevPeaks?.let { prev ->
                for (p1 in prev) for (p2 in peaks) {
                    // 量化：bin 换算对数音高（8 bin/八度近似），帧距直接入哈希
                    val q1 = (log2((p1 + 1.0).coerceAtLeast(1.0)) * 8).toInt().coerceAtLeast(0)
                    val q2 = (log2((p2 + 1.0).coerceAtLeast(1.0)) * 8).toInt().coerceAtLeast(0)
                    // 只入固定帧距（相对时间），绝不入绝对帧号——否则平移片段即失配。
                    var h = (q1.toLong() shl 20) or (q2.toLong() shl 8) or
                        (PAIR_DELTA_FRAMES.toLong() and 0xFF)
                    h = h and Long.MAX_VALUE
                    hashes += h
                }
            }
            prevPeaks = peaks
            pos += HOP
        }
        return hashes
    }

    /** 相似度 = 交集 / min(两侧大小)；任一空集 = 0。范围 [0, 1]。 */
    fun similarity(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var inter = 0
        for (k in small) if (k in large) inter++
        return inter.toDouble() / small.size
    }

    /** 便捷：两段 PCM 直接判「疑似同源」。 */
    fun likelySameSource(pcmA: FloatArray, rateA: Int, pcmB: FloatArray, rateB: Int): Boolean {
        val sim = similarity(fingerprint(pcmA, rateA), fingerprint(pcmB, rateB))
        return sim >= MATCH_THRESHOLD
    }
}
