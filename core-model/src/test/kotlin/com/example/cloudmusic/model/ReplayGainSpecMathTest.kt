package com.example.cloudmusic.model

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.123：RG 1.0 规范测量的纯 JVM 判定（系数表 / 线性 / 基准锚点）。 */
class ReplayGainSpecMathTest {

    private fun sine44100(freqHz: Int, amplitudePct: Double, seconds: Double = 2.0): ShortArray {
        val n = (44_100 * seconds).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = amplitudePct * sin(2.0 * PI * freqHz * i / 44_100.0)
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    @Test
    fun `系数表逐字对齐规范 44100 butter yule 首尾值`() {
        // 抽检规范表首尾值（§61.123 取自 HydrogenAudio RG 1.0 规范表 1b/2b）
        assertEquals(0.05418656406430, ReplayGainSpecMathTestAccess.yule44100B0(), 1e-14)
        assertEquals(-0.00187763777362, ReplayGainSpecMathTestAccess.yule44100B10(), 1e-14)
        assertEquals(3.47845948550071, ReplayGainSpecMathTestAccess.yule44100A1(), 1e-14)
        assertEquals(-0.13149317958808, ReplayGainSpecMathTestAccess.yule44100A10(), 1e-14)
        assertEquals(0.98500175787242, ReplayGainSpecMathTestAccess.butter44100B0(), 1e-14)
        assertEquals(-0.97022847566350, ReplayGainSpecMathTestAccess.butter44100A2(), 1e-14)
    }

    @Test
    fun `线性 振幅加倍增益精确负 6 点 02 dB`() {
        val quiet = ReplayGainSpecMath.calculateGainDb(sine44100(1000, 0.10), 44_100)
        val loud = ReplayGainSpecMath.calculateGainDb(sine44100(1000, 0.20), 44_100)
        assertNotNull(quiet)
        assertNotNull(loud)
        // 振幅 ×2 → 响度 +6.02dB → 增益（PINK_REF − L）减 6.02dB。
        val delta = quiet - loud
        assertEquals(6.02f, delta, 0.05f)
    }

    @Test
    fun `全幅 1kHz 正弦的规范锚点 mp3gain 公认 -14 dB`() {
        // §61.123 校准锚点：全幅 1kHz 正弦 → ≈ -14.17 dB（Python 对照实现与
        // mp3gain 实现族公认输出一致）；锁住符号约定与 PINK_REF 口径。
        val gain = ReplayGainSpecMath.calculateGainDb(sine44100(1000, 1.0), 44_100)
        assertNotNull(gain)
        assertEquals(-14.17f, gain, 0.5f)
    }

    @Test
    fun `不支持采样率返回 null 回退近似路径`() {
        assertNull(ReplayGainSpecMath.calculateGainDb(sine44100(1000, 0.5), 22_050))
        assertNull(ReplayGainSpecMath.calculateGainDb(ShortArray(10), 44_100))
    }
}

/** 测试取数口：内部系数表仅供系数对齐断言。 */
object ReplayGainSpecMathTestAccess {
    fun butter44100B0(): Double = ReplayGainSpecMath.butter44100ForTest().b0
    fun butter44100A2(): Double = ReplayGainSpecMath.butter44100ForTest().a2
    fun yule44100B0(): Double = ReplayGainSpecMath.yule44100ForTest().b[0]
    fun yule44100B10(): Double = ReplayGainSpecMath.yule44100ForTest().b[10]
    fun yule44100A1(): Double = ReplayGainSpecMath.yule44100ForTest().a[1]
    fun yule44100A10(): Double = ReplayGainSpecMath.yule44100ForTest().a[10]
}
