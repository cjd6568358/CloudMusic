package com.example.cloudmusic.model

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * §61.123（T5-2 第二级）：ReplayGain 1.0 规范响度测量（纯 JVM，可单测）。
 *
 * 系数表逐字取自 HydrogenAudio「ReplayGain 1.0 specification」——
 * Butterworth 2 阶高通（150Hz）级联 MATLAB yulewalk 10 阶等响 IIR；
 * RMS 50ms 分块（双声道平方均值合并）；每块 dB 以**文件峰值峰峰**归一；
 * **95 分位**为响度 L；`RG = PINK_REF - L`。
 * PINK_REF = 64.82 dB（Glen Sawyer 参考实现常数，ffmpeg / libreplaygain 同源；
 * 规范正文描述流程，常数出自参考实现）。
 *
 * 只支持 [SUPPORTED_RATES]（规范只给这两组系数；其它采样率由调用方回退近似法）。
 */
object ReplayGainSpecMath {

    val SUPPORTED_RATES = setOf(44_100, 48_000)

    /** 参考实现常数（见类 KDoc）。 */
    const val PINK_REF = 64.82

    // §61.123：系数表取数口（测试对齐断言用，生产不用）。
    internal fun butter44100ForTest() = BUTTER_44100
    internal fun yule44100ForTest() = YULE_44100

    /** 50ms 分块。 */
    const val BLOCK_MS = 50

    // —— Table 1a/1b: Butterworth（b0,b1,b2 / a1,a2）——
    private val BUTTER_48000 = Iir2(
        b0 = 0.98621192462708, b1 = -1.97242384925416, b2 = 0.98621192462708,
        a1 = 1.97223372919527, a2 = -0.97261396931306,
    )
    private val BUTTER_44100 = Iir2(
        b0 = 0.98500175787242, b1 = -1.97000351574484, b2 = 0.98500175787242,
        a1 = 1.96977855582618, a2 = -0.97022847566350,
    )

    // —— Table 2a/2b: Yulewalk（b0..b10 / a1..a10）——
    private val YULE_48000 = Iir10(
        b = doubleArrayOf(
            0.03857599435200, -0.02160367184185, -0.00123395316851,
            -0.00009291677959, -0.01655260341619, 0.02161526843274,
            -0.02074045215285, 0.00594298065125, 0.00306428023191,
            0.00012025322027, 0.00288463683916,
        ),
        a = doubleArrayOf(
            1.0, 3.84664617118067, -7.81501653005538, 11.34170355132042,
            -13.05504219327545, 12.28759895145294, -9.48293806319790,
            5.87257861775999, -2.75465861874613, 0.86984376593551,
            -0.13919314567432,
        ),
    )
    private val YULE_44100 = Iir10(
        b = doubleArrayOf(
            0.05418656406430, -0.02911007808948, -0.00848709379851,
            -0.00851165645469, -0.00834990904936, 0.02245293253339,
            -0.02596338512915, 0.01624864962975, -0.00240879051584,
            0.00674613682247, -0.00187763777362,
        ),
        a = doubleArrayOf(
            1.0, 3.47845948550071, -6.36317777566148, 8.54751527471874,
            -9.47693607801280, 8.81498681370155, -6.85401540936998,
            4.39470996079559, -2.19611684890774, 0.75104302451432,
            -0.13149317958808,
        ),
    )

    /** 二阶 IIR（Direct Form I；a0 归一为 1）。 */
    internal class Iir2(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    ) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun process(x: Double): Double {
            // §61.123 校准：规范表 a 值已含符号 → 反馈取**加法**（减法发散，
            // Python 对照实现钉死；全幅 1kHz 正弦 -14.17 dB 与 mp3gain 一致）。
            val y = b0 * x + b1 * x1 + b2 * x2 + a1 * y1 + a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** 十阶 IIR（Direct Form I）。 */
    internal class Iir10(val b: DoubleArray, val a: DoubleArray) {
        private val x = DoubleArray(b.size)
        private val y = DoubleArray(a.size)
        fun process(input: Double): Double {
            // 移位历史（数组长度小，直接移位可读性优先）
            for (i in x.size - 1 downTo 1) x[i] = x[i - 1]
            for (i in y.size - 1 downTo 1) y[i] = y[i - 1]
            x[0] = input
            var acc = 0.0
            for (k in b.indices) acc += b[k] * x[k]
            for (k in 1 until a.size) acc += a[k] * y[k]
            y[0] = acc
            return acc
        }
    }

    /**
     * 对**已下混为单声道**的 PCM（16-bit 短整型，±32768 满量程）执行规范测量，
     * 返回 RG 增益 dB；采样率不支持返回 null。
     *
     * 注：双声道的平方均值合并语义由调用方保证（解码侧把多声道样本交错传入时，
     * 本函数按「单声道平方序列」处理——等价于先混音后测量，见 §61.122 一手口径）。
     */
    fun calculateGainDb(mono: ShortArray, sampleRate: Int): Float? {
        if (sampleRate !in SUPPORTED_RATES) return null
        if (mono.size < sampleRate / 2) return null   // <0.5s 内容不足
        val butter = if (sampleRate == 48_000) BUTTER_48000 else BUTTER_44100
        val yule = if (sampleRate == 48_000) YULE_48000 else YULE_44100
        // 流式过滤：每 50ms 块 RMS 的绝对 dB（参考实现无峰值归一——
        // PINK_REF=64.82 已内嵌参考响度；全幅 1kHz 正弦 → -14.17 dB 校准锚点）。
        val blockSize = sampleRate / (1000 / BLOCK_MS)
        val blockDb = ArrayList<Double>()
        var blockSum = 0.0
        var blockCount = 0
        for (i in mono.indices) {
            val s = mono[i].toDouble()
            val filtered = butter.process(yule.process(s))
            blockSum += filtered * filtered
            blockCount++
            if (blockCount >= blockSize) {
                val rms = sqrt(blockSum / blockCount)
                blockDb.add(20.0 * log10(max(rms, 1e-12)))
                blockSum = 0.0
                blockCount = 0
            }
        }
        if (blockCount > 0) {
            val rms = sqrt(blockSum / blockCount)
            blockDb.add(20.0 * log10(max(rms, 1e-12)))
        }
        if (blockDb.isEmpty()) return null
        blockDb.sort()
        // 95 分位：升序取 95% 位置的值
        val idx = min(blockDb.size - 1, ((blockDb.size * 95) / 100).coerceAtLeast(0))
        val loudness = blockDb[idx]
        return (PINK_REF - loudness).toFloat().coerceIn(-51f, 51f)
    }
}
