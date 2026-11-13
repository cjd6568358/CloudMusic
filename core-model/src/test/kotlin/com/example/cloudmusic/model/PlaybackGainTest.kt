package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `12 §61.101` / C-18 · ReplayGain 纯 JVM 值语义回归。
 *
 * 诚实边界：本测试只验证「增益值 → 目标音量」的换算与钳制，
 * **不验证 B2 引擎侧接线**（当前无生产消费方，见 PlaybackGain KDoc）。
 */
class PlaybackGainTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(
            abs(expected - actual) <= eps,
            "expected=$expected actual=$actual (eps=$eps)",
        )
    }

    @Test
    @DisplayName("C18-1 · OFF 恒返回 1f，忽略增益与峰值")
    fun offIgnoresEverything() {
        val g = ReplayGain(trackGainDb = -12f, albumGainDb = -9f,
            trackPeak = 4f, albumPeak = 8f)
        assertEquals(0f, g.gainDb(GainMode.OFF))
        assertEquals(1f, g.peak(GainMode.OFF))
        assertEquals(1f, g.targetVolume(GainMode.OFF, preampDb = 12f))
    }

    @Test
    @DisplayName("C18-2 · TRACK -6dB → 10^(-6/20)")
    fun trackGain() {
        val g = ReplayGain(trackGainDb = -6f, albumGainDb = 99f)
        near(0.501187f, g.targetVolume(GainMode.TRACK))
        assertEquals(-6f, g.gainDb(GainMode.TRACK))
    }

    @Test
    @DisplayName("C18-3 · ALBUM -3dB 走专辑增益，不看曲目增益")
    fun albumGain() {
        val g = ReplayGain(trackGainDb = -20f, albumGainDb = -3f)
        near(0.707946f, g.targetVolume(GainMode.ALBUM))
        assertEquals(-3f, g.gainDb(GainMode.ALBUM))
    }

    @Test
    @DisplayName("C18-4 · preamp 与增益相加后换算")
    fun preampAdds() {
        val g = ReplayGain(trackGainDb = -6f)
        near(0.707946f, g.targetVolume(GainMode.TRACK, preampDb = 3f))
        near(0.501187f, g.targetVolume(GainMode.TRACK, preampDb = 0f))
    }

    @Test
    @DisplayName("C18-5 · 峰值保护：peak>1 时按 1/peak 收紧")
    fun peakCeiling() {
        val g = ReplayGain(trackGainDb = 0f, trackPeak = 2f)
        near(0.5f, g.targetVolume(GainMode.TRACK))
        val g2 = ReplayGain(trackGainDb = 6f, trackPeak = 0.5f)
        near(1f, g2.targetVolume(GainMode.TRACK), 1e-3f)
    }

    @Test
    @DisplayName("C18-6 · 音量恒钳制在 IMusicEngine.setVolume 契约的 0f..1f")
    fun clampedToContract() {
        val loud = ReplayGain(trackGainDb = 40f, albumGainDb = 40f)
        assertEquals(1f, loud.targetVolume(GainMode.TRACK))
        assertEquals(1f, loud.targetVolume(GainMode.ALBUM, preampDb = 60f))
        val quiet = ReplayGain(trackGainDb = -200f)
        val v = quiet.targetVolume(GainMode.TRACK)
        assertTrue(v >= 0f && v <= 1f, "targetVolume=$v")
        assertTrue(v < 0.001f)
    }

    @Test
    @DisplayName("C18-7 · hasGain 只反映元数据，不表示已接回生效")
    fun hasGainIsMetadataOnly() {
        assertFalse(ReplayGain().hasGain())
        assertTrue(ReplayGain(trackGainDb = -0.1f).hasGain())
        assertTrue(ReplayGain(albumGainDb = 3f).hasGain())
        assertEquals(1f, ReplayGain().targetVolume(GainMode.TRACK))
    }

    @Test
    @DisplayName("C18-8 · 非法值一律拒绝，不静默产生 NaN 音量")
    fun rejectsInvalid() {
        assertFailsWith<IllegalArgumentException> {
            ReplayGain(trackGainDb = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayGain(albumGainDb = Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { ReplayGain(trackPeak = 0f) }
        assertFailsWith<IllegalArgumentException> { ReplayGain(albumPeak = -1f) }
        assertFailsWith<IllegalArgumentException> {
            ReplayGain().targetVolume(GainMode.TRACK, Float.NaN)
        }
    }

    @Test
    @DisplayName("C18-9 · ALBUM 使用专辑峰值而非曲目峰值")
    fun albumPeakIsSeparate() {
        val g = ReplayGain(albumGainDb = 0f, trackPeak = 4f, albumPeak = 1f)
        near(1f, g.targetVolume(GainMode.ALBUM))
        near(0.25f, g.targetVolume(GainMode.TRACK))
    }
}
