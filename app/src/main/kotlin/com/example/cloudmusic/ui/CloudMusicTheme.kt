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
 */
@Composable
fun CloudMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(LegacyPalette.PrimaryRed),
            secondary = Color(LegacyPalette.AccentRed),
            background = Color(LegacyPalette.PageBackground),      // #ff262626
            surface = Color(LegacyPalette.PageBackground),        // #ff262626
            onPrimary = Color(LegacyPalette.White),
            onBackground = Color(LegacyPalette.TextPrimary),
            onSurface = Color(LegacyPalette.TextPrimary),
            // `legacy_are.xml` miContainer 的 #ff33333e，作为次级容器底色。
            secondaryContainer = Color(LegacyPalette.MiniPageBackground),
            onSecondaryContainer = Color(LegacyPalette.TextPrimary),
        ),
        content = content,
    )
}
