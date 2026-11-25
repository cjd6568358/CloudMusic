package com.ccmsc.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** §61.239：分轨相对时间三口纯函数（core-model 唯一真值口径）——边界矩阵。 */
class CueEnvelopeTest {

    @Test
    @DisplayName("§61.239 · displayDuration：包络长 / 无界原样 / 异常包络原样")
    fun displayDuration() {
        // 分轨：end ≤ base → 包络长
        assertEquals(30_000L, CueEnvelope.displayDuration(120_000L, 30_000L, 60_000L))
        // 末轨无界（end=0）→ 原样
        assertEquals(120_000L, CueEnvelope.displayDuration(120_000L, 0L, 0L))
        // end > base（异常包络）→ 原样
        assertEquals(120_000L, CueEnvelope.displayDuration(120_000L, 0L, 999_999L))
        // base=0（引擎未解出）→ 原样 0
        assertEquals(0L, CueEnvelope.displayDuration(0L, 30_000L, 60_000L))
        // 整轨便捷重载（Track null）
        // §61.320：末轨无 cue 终点（end=0）→ 用文件尾兜底，不再把整文件时长当本轨
        assertEquals(257_351L, CueEnvelope.displayDuration(4_425_351L, 4_168_000L, 0L))
        assertEquals(120_000L, CueEnvelope.displayDuration(120_000L, null))
    }

    @Test
    @DisplayName("§61.239 · displayPosition：包络内相对化 / 包络前与整轨原样")
    fun displayPosition() {
        assertEquals(15_000L, CueEnvelope.displayPosition(45_000L, 30_000L))
        assertEquals(0L, CueEnvelope.displayPosition(30_000L, 30_000L))
        // 包络前（恢复钳制前的原始值）→ 原样（不产生负数）
        assertEquals(5_000L, CueEnvelope.displayPosition(5_000L, 30_000L))
        // 整轨原样
        assertEquals(45_000L, CueEnvelope.displayPosition(45_000L, 0L))
    }

    @Test
    @DisplayName("§61.239 · absoluteSeek：相对 → absolute（三口互逆）")
    fun absoluteSeek() {
        assertEquals(45_000L, CueEnvelope.absoluteSeek(15_000L, 30_000L))
        // 整轨原样
        assertEquals(15_000L, CueEnvelope.absoluteSeek(15_000L, 0L))
        // displayPosition ∘ absoluteSeek 恒等
        val cue = 30_000L
        val absolute = 47_777L
        assertEquals(absolute, CueEnvelope.absoluteSeek(CueEnvelope.displayPosition(absolute, cue), cue))
    }
}
