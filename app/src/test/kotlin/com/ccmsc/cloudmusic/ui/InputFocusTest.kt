package com.ccmsc.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/**
 * §61.293 / §61.344（G-7）：搜索页「进页是否抢焦点」的真值表。
 *
 * 用户 2026-11-25 一手改口径：切到搜索页**不弹键盘**，主动点输入框才弹。
 * BasicTextField 点击即聚焦，故 [shouldAutoFocusSearch] 恒 false；
 * 本测试用三种关键字形态全覆盖钉死「任何入口都不抢焦点」，防回潮到 §61.293 旧口径。
 */
class InputFocusTest {

    @Test
    @DisplayName("空关键字进页不抢焦点（G-7：不弹键盘，点输入框才弹）")
    fun blankQueryDoesNotStealFocus() {
        assertFalse(shouldAutoFocusSearch(""))
    }

    @Test
    @DisplayName("纯空白进页不抢焦点")
    fun whitespaceDoesNotStealFocus() {
        assertFalse(shouldAutoFocusSearch("   "))
        assertFalse(shouldAutoFocusSearch("	"))
    }

    @Test
    @DisplayName("带关键字返回不抢焦点（搜完去播放再回来，不该弹键盘盖结果）")
    fun existingQueryKeepsFocusAway() {
        assertFalse(shouldAutoFocusSearch("周杰伦"))
        assertFalse(shouldAutoFocusSearch("a"))
    }

    @Test
    @DisplayName("首尾空白也不抢焦点")
    fun paddedQueryDoesNotStealFocus() {
        assertFalse(shouldAutoFocusSearch(" 晴天 "))
    }
}
