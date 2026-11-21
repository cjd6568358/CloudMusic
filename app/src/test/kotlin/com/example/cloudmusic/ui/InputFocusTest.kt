package com.example.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.293：搜索页「进页是否抢焦点」的真值表。
 *
 * 为什么值得钉死：这条判定决定键盘会不会自动弹出。改成恒 `true` 的代价很具体——
 * 用户搜完点结果去播放、返回搜索页时，键盘立刻盖住半屏结果，且每次返回都弹一次；
 * 改成恒 `false` 则是另一头：点搜索图标进页后还得再点一下输入框才能打字。
 * 两种退化的表现都只在真机上才看得见，所以用纯 JVM 断言把口径固定住。
 */
class InputFocusTest {

    @Test
    @DisplayName("空关键字进入搜索页要抢焦点（否则还要多点一下输入框）")
    fun blankQueryStealsFocus() {
        assertTrue(shouldAutoFocusSearch(""))
    }

    @Test
    @DisplayName("纯空白等同于空：它不是一次真实查询")
    fun whitespaceCountsAsBlank() {
        assertTrue(shouldAutoFocusSearch("   "))
        assertTrue(shouldAutoFocusSearch("\t"))
    }

    @Test
    @DisplayName("带着关键字回来不抢焦点（搜完去播放再返回，不该再弹键盘）")
    fun existingQueryKeepsFocusAway() {
        assertFalse(shouldAutoFocusSearch("周杰伦"))
        assertFalse(shouldAutoFocusSearch("a"))
    }

    @Test
    @DisplayName("首尾空白不算空：用户确实敲过内容")
    fun paddedQueryIsNotBlank() {
        assertFalse(shouldAutoFocusSearch(" 晴天 "))
    }
}
