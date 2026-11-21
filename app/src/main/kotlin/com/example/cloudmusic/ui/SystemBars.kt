package com.example.cloudmusic.ui

import android.view.Window
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat

/**
 * §61.291（V0.4 系统栏明暗跟随）：状态栏 / 导航栏的底色与图标明暗必须跟着主题走。
 *
 * 缺陷来历：`themes.xml` 把 `statusBarColor` / `navigationBarColor` 写死
 * `@android:color/black`、`windowLightStatusBar=false`；运行时只有播放页临时改成透明。
 * 于是**浅色主题下**页面底是 `#FAFAFA`，屏幕顶部却是一条纯黑栏 + 白图标——
 * 与 §61.288 刚补完的浅色可读性是同一类「浅色没做完」的账，U-12 一眼能看到。
 *
 * 口径：
 * - 栏底色 = 页面底 [FnComp.PageBg]（单一真相源，禁再写死黑/白）；
 * - 深色主题配浅色图标、浅色主题配深色图标；
 * - 播放页固定深色沉浸 → 状态栏透明 + 图标恒浅色，导航栏压黑保留三键（用户明确要求不隐藏）。
 *
 * 为什么是「声明式目标态」而不是「进入时改、退出时还原」：原实现保存窗口原值再还原，
 * 一旦目标态有两个来源（主题 / 播放页）就会漏配对。改为每次按键重算 [State]，
 * 退出播放页得到的必然是**当前主题**的栏色，而不是「上次进去之前的颜色」。
 */
internal object SystemBars {

    /** 目标态：一次算清两条栏该怎么画。 */
    data class State(val immersive: Boolean, val lightIcons: Boolean)

    /**
     * @param isDark 当前主题是否为深色（MainActivity 的 `applyThemeFromSettings` 计算结果）
     * @param showPlayer 是否处于播放页（播放页恒深色沉浸，与主题无关）
     */
    fun stateFor(isDark: Boolean, showPlayer: Boolean): State =
        if (showPlayer) {
            // 播放页：透明状态栏 + 浅色图标 + 压黑导航栏
            State(immersive = true, lightIcons = true)
        } else {
            State(immersive = false, lightIcons = isDark)
        }

    /** 按目标态上色。`isAppearanceLight*` 语义是「栏底为浅色 ⇒ 图标用深色」，故取反。 */
    fun apply(window: Window, isDark: Boolean, showPlayer: Boolean) {
        val state = stateFor(isDark, showPlayer)
        if (state.immersive) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.BLACK
        } else {
            val barArgb = FnComp.PageBg.toArgb()
            window.statusBarColor = barArgb
            window.navigationBarColor = barArgb
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !state.lightIcons
        controller.isAppearanceLightNavigationBars = !state.lightIcons
    }
}
