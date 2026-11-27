package com.ccmsc.cloudmusic.engineopensource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * §61.378（U1-C）：预热守卫语义修正的 JVM 判据。
 *
 * A/B 反证锚点（guard 同口径钉源码）：
 * - A（旧语义 FAIL 样本）：空缓存 + 3.4MB 文件 → 旧判据 `3453364 > 0` 会 skip，
 *   新判据必须 warm；
 * - B（白暖样本）：空缓存 + 292,225,852B（>256MiB cap）→ 旧判据（长度未知放行）硬暖
 *   后仅余 11MB，新判据必须 skip。
 */
class WarmPolicyTest {

    private val cap = 256L * 1024 * 1024

    @Test
    @DisplayName("空缓存 + 小文件（3.4MB MP3）→ 预热（旧判据在此恒 skip，回归保护）")
    fun coldCacheSmallFileWarms() {
        val d = WarmPolicy.decide(upstreamLen = 3_453_364L, storedBytes = 0L, capBytes = cap)
        assertTrue(d.warm, "冷装首播小文件必须可预热：$d")
        assertEquals("fit", d.reason)
    }

    @Test
    @DisplayName("空缓存 + 292MB FLAC（实测白暖样本）→ skip（LRU 边写边淘汰，暖了白暖）")
    fun oversizeFileSkipsOnColdCache() {
        val d = WarmPolicy.decide(upstreamLen = 292_225_852L, storedBytes = 0L, capBytes = cap)
        assertFalse(d.warm, "超容文件必须放弃预热：$d")
        assertEquals("over-remaining", d.reason)
    }

    @Test
    @DisplayName("304MB APE（§61.320 原始动机）→ skip，且不论缓存已存多少都 skip")
    fun oversizeApeSkips() {
        assertFalse(
            WarmPolicy.decide(304_219_309L, 0L, cap).warm,
        )
        assertFalse(
            WarmPolicy.decide(304_219_309L, 100L * 1024 * 1024, cap).warm,
        )
    }

    @Test
    @DisplayName("长度未知（chunked / 未探测）→ 一律不暖（容量无从判定）")
    fun unknownLengthNeverWarms() {
        assertFalse(WarmPolicy.decide(-1L, 0L, cap).warm)
        assertFalse(WarmPolicy.decide(0L, 1_000_000L, cap).warm)
        assertEquals("len-unknown", WarmPolicy.decide(-1L, 0L, cap).reason)
    }

    @Test
    @DisplayName("剩余容量边界：文件恰好等于剩余 → 预热；超一字节 → skip")
    fun remainingBoundary() {
        val stored = 100L * 1024 * 1024
        val remaining = cap - stored
        assertTrue(WarmPolicy.decide(remaining, stored, cap).warm)
        assertFalse(WarmPolicy.decide(remaining + 1, stored, cap).warm)
    }

    @Test
    @DisplayName("已存量超过 cap（异常态防御）→ 剩余钳 0，除零长外一律 skip")
    fun storedOverCapDefends() {
        assertFalse(WarmPolicy.decide(1L, cap + 1, cap).warm)
    }
}
