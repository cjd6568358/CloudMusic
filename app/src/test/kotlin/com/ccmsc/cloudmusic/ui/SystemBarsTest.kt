package com.ccmsc.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.291：系统栏目标态的真值表。
 *
 * 为什么值得纯 JVM 断言：[SystemBars.apply] 是框架调用，单元测试跑不了；但「该用浅色图标还是
 * 深色图标」这一层是纯函数 [SystemBars.stateFor]。浅色主题下图标一旦判反，状态栏就是
 * 白底白字（视觉上等于消失）——这种错误静态看代码最容易被 `!state.lightIcons` 的取反绕过去。
 * 真机观感（浅色主题状态栏是否与页面无缝、播放页透明栏是否压住渐变）归 V0.4-09 U-12。
 */
class SystemBarsTest {

    @Test
    @DisplayName("深色主题普通页：不沉浸、图标浅色")
    fun darkThemeNormalPage() {
        val state = SystemBars.stateFor(isDark = true, showPlayer = false)
        assertFalse(state.immersive, "普通页不应把内容顶到状态栏后面")
        assertTrue(state.lightIcons, "深色页面底必须配浅色图标")
    }

    @Test
    @DisplayName("浅色主题普通页：不沉浸、图标深色")
    fun lightThemeNormalPage() {
        val state = SystemBars.stateFor(isDark = false, showPlayer = false)
        assertFalse(state.immersive)
        assertFalse(state.lightIcons, "浅色页面底配白图标＝图标消失")
    }

    @Test
    @DisplayName("播放页恒沉浸：深色主题下图标仍是浅色")
    fun playerPageInDarkTheme() {
        val state = SystemBars.stateFor(isDark = true, showPlayer = true)
        assertTrue(state.immersive)
        assertTrue(state.lightIcons)
    }

    @Test
    @DisplayName("播放页与主题无关：浅色主题下也必须反相成浅色图标")
    fun playerPageInLightTheme() {
        val state = SystemBars.stateFor(isDark = false, showPlayer = true)
        assertTrue(state.immersive)
        assertTrue(state.lightIcons, "播放页固定深色沉浸，浅色主题下也必须用浅色图标")
    }
}
