package com.example.cloudmusic.engineopensource

import com.example.cloudmusic.model.GainMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `12 §61.105` · ReplayGain / gapless 接线的**纯 JVM 契约单测**。
 *
 * 只验证「标签 → 值对象 → 有效音量」的换算与 gapless 可达性判定；
 * 不验证真机音量效果、不验证 Media3 metadata Entry 的装箱（需 Android 制品），
 * 更不写成「ReplayGain 真机已生效」。真机部分见 §61.105 的 NOT RUN 登记。
 */
class ReplayGainControllerTest {

    private class Probe {
        val applied = mutableListOf<Float>()
        val controller = ReplayGainController { applied += it }
    }

    private val trackTags = listOf(
        "REPLAYGAIN_TRACK_GAIN=-6.54 dB",
        "REPLAYGAIN_ALBUM_GAIN=-3.20 dB",
        "REPLAYGAIN_TRACK_PEAK=0.98765",
        "REPLAYGAIN_ALBUM_PEAK=0.91234",
    )

    @Test
    fun `无标签时保持原始用户音量`() {
        val p = Probe()
        p.controller.onMetadataLines(listOf("TITLE=demo", "ARTIST=x"))
        assertNull(p.controller.parsedGain())
        assertEquals(1f, p.controller.effectiveVolume())
        assertEquals(1f, p.applied.last())
    }

    @Test
    fun `TRACK 标签按 dB 换算并下发到播放器音量`() {
        val p = Probe()
        p.controller.onMetadataLines(trackTags)
        assertNotNull(p.controller.parsedGain())
        // -6.54 dB → 10^(-6.54/20) ≈ 0.4710，峰值 0.98765 不触发削波保护。
        assertEquals(0.4710f, p.controller.effectiveVolume(), 0.002f)
        assertEquals(p.controller.effectiveVolume(), p.applied.last())
    }

    @Test
    fun `ALBUM 模式取专辑增益与专辑峰值`() {
        val p = Probe()
        p.controller.mode = GainMode.ALBUM
        p.controller.onMetadataLines(trackTags)
        assertEquals(0.6918f, p.controller.effectiveVolume(), 0.002f)
    }

    @Test
    fun `OFF 模式不改变用户音量`() {
        val p = Probe()
        p.controller.mode = GainMode.OFF
        p.controller.onUserVolume(0.6f)
        p.controller.onMetadataLines(trackTags)
        assertEquals(0.6f, p.controller.effectiveVolume())
        assertEquals(0.6f, p.applied.last())
    }

    @Test
    fun `用户音量与增益相乘且钳制在 0 到 1`() {
        val p = Probe()
        p.controller.onUserVolume(0.5f)
        p.controller.onMetadataLines(trackTags)
        assertEquals(0.2355f, p.controller.effectiveVolume(), 0.002f)
        assertTrue(p.controller.effectiveVolume() in 0f..1f)
    }

    @Test
    fun `正增益超过峰值上限时按削波保护收敛`() {
        val p = Probe()
        p.controller.onMetadataLines(
            listOf("REPLAYGAIN_TRACK_GAIN=+6.02 dB", "REPLAYGAIN_TRACK_PEAK=2.0"),
        )
        // 10^(6.02/20) ≈ 2.0，但 1/peak = 0.5 → 削波保护取 0.5。
        assertEquals(0.5f, p.controller.effectiveVolume(), 0.002f)
    }

    @Test
    fun `用户预放大与标签增益相加`() {
        val p = Probe()
        p.controller.preampDb = 6.54f
        p.controller.onMetadataLines(trackTags)
        assertEquals(1f, p.controller.effectiveVolume(), 0.002f)
    }

    @Test
    fun `非法数值整行忽略且不产生增益`() {
        val p = Probe()
        p.controller.onMetadataLines(
            listOf("REPLAYGAIN_TRACK_GAIN=not-a-number dB", "REPLAYGAIN_TRACK_PEAK=0"),
        )
        assertNull(p.controller.parsedGain())
        assertEquals(1f, p.controller.effectiveVolume())
    }

    @Test
    fun `大小写与分隔符不敏感且支持 ID3 形式`() {
        val p = Probe()
        p.controller.onMetadataLines(
            listOf("replaygain_track_gain: -6.54 dB", "REPLAYGAIN-TRACK-PEAK: 0.9"),
        )
        val g = p.controller.parsedGain()
        assertNotNull(g)
        assertEquals(-6.54f, g.trackGainDb)
        assertEquals(0.9f, g.trackPeak)
    }

    @Test
    fun `reset 清掉上一曲增益避免串曲`() {
        val p = Probe()
        p.controller.onUserVolume(0.8f)
        p.controller.onMetadataLines(trackTags)
        assertTrue(p.controller.effectiveVolume() < 0.8f)
        p.controller.reset()
        assertNull(p.controller.parsedGain())
        assertEquals(1f, p.controller.effectiveVolume())
        assertEquals(1f, p.applied.last())
    }

    @Test
    fun `当前 single-handle 架构的跨曲 gapless 判 BLOCKED`() {
        val a = GaplessPolicy.assess(
            playerReused = false,
            queuedItemCount = 1,
            pauseAtEndOfMediaItems = false,
        )
        assertTrue(a.inFileTrimmingAvailable, "文件内 trimming 由官方 Extractor 具备")
        assertFalse(a.crossTrackAvailable, "player 重建 + 单曲队列 → 跨曲不可达")
        assertNotNull(a.blocker)
        assertTrue(a.blocker!!.contains("single-handle"))
    }

    @Test
    fun `复用 player 且连续队列时跨曲 gapless 才可达`() {
        val ok = GaplessPolicy.assess(
            playerReused = true,
            queuedItemCount = 3,
            pauseAtEndOfMediaItems = false,
        )
        assertTrue(ok.crossTrackAvailable)
        assertNull(ok.blocker)
        val paused = GaplessPolicy.assess(true, 3, true)
        assertFalse(paused.crossTrackAvailable)
    }

    @Test
    fun `setVolume 拒绝越界值保持门面契约`() {
        val p = Probe()
        val thrown = runCatching { p.controller.onUserVolume(1.5f) }
        assertTrue(thrown.isFailure)
        assertEquals(1f, p.controller.effectiveVolume())
    }
}
