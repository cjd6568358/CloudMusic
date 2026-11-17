package com.example.cloudmusic.output.dlna

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.199 / 03-08 §10.2：断连判定与回落一次的纯 JVM 契约（零网络、零设备）。 */
class DlnaSessionMonitorTest {

    @Test
    @DisplayName("§61.199 S1：成功读取刷新进度并清零失败计数")
    fun successRefreshesPosition() {
        val m = DlnaSessionMonitor()
        assertEquals(12_000L, m.observe(12_000L).lastPositionMs)
        m.observe(null)
        val ok = m.observe(34_000L)
        assertEquals(DlnaSessionMonitor.State.HEALTHY, ok.state)
        assertEquals(34_000L, ok.lastPositionMs)
        assertFalse(ok.shouldFallBack)
    }

    @Test
    @DisplayName("§61.199 S2：连续 3 次失败才判定断连（一次抖动不抢占）")
    fun threeFailuresThenDisconnect() {
        val m = DlnaSessionMonitor()
        val first = m.observe(null)
        assertEquals(DlnaSessionMonitor.State.DEGRADED, first.state)
        assertFalse(first.shouldFallBack)
        assertEquals(DlnaSessionMonitor.State.DEGRADED, m.observe(null).state)
        val third = m.observe(null)
        assertEquals(DlnaSessionMonitor.State.DISCONNECTED, third.state)
        assertTrue(third.shouldFallBack)
    }

    @Test
    @DisplayName("§61.199 S3：断连回落只发一次，重复轮询不再抢占本机播放")
    fun fallbackFiresOnce() {
        val m = DlnaSessionMonitor()
        repeat(3) { m.observe(null) }
        repeat(5) {
            val v = m.observe(null)
            assertEquals(DlnaSessionMonitor.State.DISCONNECTED, v.state)
            assertFalse(v.shouldFallBack)
        }
    }

    @Test
    @DisplayName("§61.199 S4：失败时保留最后有效进度（不能从头播放）")
    fun keepsLastKnownPosition() {
        val m = DlnaSessionMonitor()
        m.observe(271_810L)
        m.observe(-1L)   // 非法值按失败处理，不覆盖进度
        m.observe(null)
        val verdict = m.observe(null)
        assertEquals(271_810L, verdict.lastPositionMs)
        assertTrue(verdict.shouldFallBack)
    }

    @Test
    @DisplayName("§61.199 S5：新会话 reset 后断连标记与旧进度清空")
    fun resetForNewSession() {
        val m = DlnaSessionMonitor()
        m.observe(9_000L)
        repeat(3) { m.observe(null) }
        m.reset()
        val fresh = m.observe(null)
        assertEquals(DlnaSessionMonitor.State.DEGRADED, fresh.state)
        assertEquals(0L, fresh.lastPositionMs)
        m.observe(null)
        assertTrue(m.observe(null).shouldFallBack, "reset 后应能再次触发回落")
    }
}
