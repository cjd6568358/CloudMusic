package com.ccmsc.cloudmusic

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.211（G09 拍板 A）：MIUI 自启动引导判定核（预研报告 §4 / §61.32 一手口径）。 */
class MiuiAutostartGuideTest {

    @Test
    @DisplayName("Xiaomi（真机一手 ro.product.manufacturer）→ 显示引导行")
    fun xiaomiShows() {
        assertTrue(MiuiAutostartGuide.shouldShow("Xiaomi"))
    }

    @Test
    @DisplayName("大小写不敏感；Redmi/POCO 设备 manufacturer 同为 Xiaomi 口径覆盖")
    fun caseInsensitive() {
        assertTrue(MiuiAutostartGuide.shouldShow("xiaomi"))
        assertTrue(MiuiAutostartGuide.shouldShow(" XIAOMI "))
    }

    @Test
    @DisplayName("非 MIUI 厂商 / null / 空串 → 隐藏（判定失败按非 MIUI 处理）")
    fun nonMiuiHidden() {
        assertFalse(MiuiAutostartGuide.shouldShow("HUAWEI"))
        assertFalse(MiuiAutostartGuide.shouldShow("samsung"))
        assertFalse(MiuiAutostartGuide.shouldShow(""))
        assertFalse(MiuiAutostartGuide.shouldShow(null))
    }

    @Test
    @DisplayName("§61.275 引导文案要点在位（自启动路径 + 不申请省电白名单口径），术语已人话化")
    fun guideTextCoversKeyPoints() {
        val text = MiuiAutostartGuide.GUIDE_TEXT
        assertTrue("自启动" in text)
        assertTrue("应用管理" in text)
        assertTrue("不会申请省电白名单" in text)
        // 判据强度不变（不申请白名单的口径必须可见），只换了表述：
        // 用户可见文案不得再出现「电池优化豁免 / 保活 / 重新拉起」这类系统术语。
        for (term in listOf("电池优化", "保活", "拉起")) {
            assertFalse(term in text, "引导文案残留术语：" + term)
        }
    }
}
