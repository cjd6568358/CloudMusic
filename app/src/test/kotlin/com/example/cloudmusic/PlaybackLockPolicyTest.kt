package com.example.cloudmusic

import com.example.cloudmusic.engine.api.EngineState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.306（K3b）：保活锁持有判定与持有者幂等语义（lx 组合拳对齐，纯 JVM）。 */
class PlaybackLockPolicyTest {

    @Test
    @DisplayName("K3b · 持有判定矩阵：网络 × PLAYING|BUFFERING 才持有")
    fun shouldHoldMatrix() {
        assertTrue(PlaybackLockPolicy.shouldHold(EngineState.PLAYING, isNetworkSource = true))
        assertTrue(PlaybackLockPolicy.shouldHold(EngineState.BUFFERING, isNetworkSource = true))
        assertFalse(PlaybackLockPolicy.shouldHold(EngineState.PLAYING, isNetworkSource = false), "本地曲无需网络锁")
        assertFalse(PlaybackLockPolicy.shouldHold(EngineState.PAUSED, isNetworkSource = true), "暂停即释（lx 口径）")
        assertFalse(PlaybackLockPolicy.shouldHold(EngineState.IDLE, isNetworkSource = true))
        assertFalse(PlaybackLockPolicy.shouldHold(EngineState.ENDED, isNetworkSource = true))
        assertFalse(PlaybackLockPolicy.shouldHold(EngineState.ERROR, isNetworkSource = true))
    }

    @Test
    @DisplayName("K3b · 持有者幂等：判定变化才触碰锁，快照链重复调用零副作用")
    fun holderIdempotentTransitions() {
        val wake = RecordingPort()
        val wifi = RecordingPort()
        val holder = PlaybackLockHolder(wake, wifi)

        holder.update(EngineState.PLAYING, isNetworkSource = true)
        assertEquals(listOf(1), wake.acquires, "首次持有 acquire×1")
        assertEquals(listOf(1), wifi.acquires)

        holder.update(EngineState.PLAYING, isNetworkSource = true) // 2s 快照链重复
        assertEquals(1, wake.acquires.size, "同判定重复调用不重复触碰")

        holder.update(EngineState.PAUSED, isNetworkSource = true)
        assertEquals(listOf(1), wake.releases, "暂停即释")
        assertEquals(listOf(1), wifi.releases)

        holder.update(EngineState.BUFFERING, isNetworkSource = true)
        assertEquals(2, wake.acquires.size, "缓冲期重新持有（取流在途）")

        holder.releaseAll()
        assertEquals(2, wake.releases.size, "销毁兜底释放")
        holder.releaseAll()
        assertEquals(2, wake.releases.size, "未持有态 releaseAll 幂等 no-op")
    }

    private class RecordingPort : LockPort {
        val acquires = mutableListOf<Int>()
        val releases = mutableListOf<Int>()
        override fun acquire() {
            acquires.add(acquires.size + 1)
        }

        override fun release() {
            releases.add(releases.size + 1)
        }
    }
}
