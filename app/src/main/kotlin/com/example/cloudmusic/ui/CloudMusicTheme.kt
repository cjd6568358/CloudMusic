package com.example.cloudmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.cloudmusic.ui.player.LegacyPalette

/**
 * T-A6（`12 §33` / `03-10 §2`）：主色 `#FF3A3A` / `#FF2C55`（PRD §13 #9）。
 *
 * `12 §61.42`：用户要求 1:1 复刻竞品视觉 → **固定深色主题**，色值全部取自
 * [LegacyPalette]（竞品反编译一手证据），不再跟随系统亮/暗模式。
 * 原先跟随系统亮色会让竞品白色图标落在浅紫背景上、对比度不足，也不符合竞品风格。
 *
 * §61.136：新增竞品全局层级色（di/gm/h7/qx/rc —— 深色背景三级层次），
 * 供非播放页（曲库/歌单/搜索/设置/我的音乐）共享同一设计系统。
 */
object CloudPageColors {
    /** 最深背景（rc = #ff121212）—— 页面主背景。 */
    val BgDeep = Color(0xFF121212)
    /** 次级背景（di/gm = #ff1c1c1c）—— 卡片/列表行。 */
    val BgCard = Color(0xFF1C1C1C)
    /** 三级背景（h7/qx = #ff262626）—— 底栏/Tab 背景。 */
    val BgTab = Color(0xFF262626)
}

@Composable
fun CloudMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(LegacyPalette.PrimaryRed),
            secondary = Color(LegacyPalette.AccentRed),
            background = Color(LegacyPalette.PageBackground),      // #ff262626
            surface = Color(LegacyPalette.PageBackground),
            onPrimary = Color(LegacyPalette.White),
            onBackground = Color(LegacyPalette.TextPrimary),
            onSurface = Color(LegacyPalette.TextPrimary),
            secondaryContainer = Color(LegacyPalette.MiniPageBackground),
            onSecondaryContainer = Color(LegacyPalette.TextPrimary),
            // §61.136：竞品三级深色背景（供各页面直接引用）
            surfaceContainerLowest = CloudPageColors.BgDeep,       // rc
            surfaceContainerLow = CloudPageColors.BgCard,          // di
            surfaceContainer = CloudPageColors.BgTab,              // h7
        ),
        content = content,
    )
}
