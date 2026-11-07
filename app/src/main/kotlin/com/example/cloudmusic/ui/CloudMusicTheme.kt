package com.example.cloudmusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * T-A6（`12 §33` / `03-10 §2`）：深色优先 + 跟随系统，主色 `#FF3A3A` / `#FF2C55`
 * （PRD §13 #9）。播放页照搬主题属 T-L L3，本文件只管自研 Compose 页面。
 */
@Composable
fun CloudMusicTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFFF3A3A.toInt()),
            secondary = Color(0xFFFF2C55.toInt()),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFFF2C55.toInt()),
            secondary = Color(0xFFFF3A3A.toInt()),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
