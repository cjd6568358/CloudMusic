package com.example.cloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 飞牛胶囊导航「更多」页（docs/01 §0；飞牛风格骨架 + 我们的功能，非飞牛账号壳层）。
 *
 * 承载胶囊三 tab 之外的全部功能入口：曲库 / 歌单 / 搜索 / 队列 / 我的音乐 / 设置 / 数据源，
 * 一个都不丢。**不含**飞牛的 admin 卡 / 修改密码 / 用户管理 / 退出登录 / 服务器设置
 * （辨别清单 spec §2：我们没有账号体系）。
 *
 * `12 §61.175`：卡片与行改用 [FnGroupCard] / [FnRow]（行高 50.4dp、圆角 16、无行分隔线），
 * 与设置页同一套骨架；7 行按飞牛「4 行 + 3 行」的分组节奏拆成两张卡。
 */
@Composable
fun MoreScreen(
    onPlaylists: () -> Unit,
    onSearch: () -> Unit,
    onQueue: () -> Unit,
    onMine: () -> Unit,
    onSettings: () -> Unit,
    onSources: () -> Unit,
    onLibraryManager: () -> Unit = {},
    onDismiss: () -> Unit,
    /** 功能页入口标签（由 CloudMusicApp 提供，保持 03-10 §5 五页口径单一来源）。 */
    entryLabels: List<Pair<String, String>> = listOf(
        "曲库" to "♫", "歌单" to "☰", "搜索" to "⌕", "队列" to "☷", "设置" to "⚙",
    ),
) {
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("更多", onBack = onDismiss)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            FnGroupCard {
                // 前 5 行由 CloudMusicApp 提供的五页口径标签驱动（守护单一来源），
                // 顺序：曲库(entryLabels[0]) / 歌单[1] / 搜索[2] / 队列[3] / 设置[4]。
                FnRow(entryLabels[0].second, entryLabels[0].first, onLibraryManager)
                FnRow(
                    entryLabels[1].second, entryLabels[1].first, onPlaylists,
                    dividerAbove = true,
                )
                FnRow(
                    entryLabels[2].second, entryLabels[2].first, onSearch,
                    dividerAbove = true,
                )
                FnRow(
                    entryLabels[3].second, entryLabels[3].first, onQueue,
                    dividerAbove = true,
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            FnGroupCard {
                FnRow("♥", "我的音乐", onMine)
                FnRow(
                    entryLabels[4].second, entryLabels[4].first, onSettings,
                    dividerAbove = true,
                )
                FnRow("⊕", "数据源", onSources, dividerAbove = true)
            }
            Text(
                "CloudMusic · 统一虚拟曲库 · 本地 / WebDAV / STRM 平等对待 · AGPL-3.0",
                color = FnComp.TextHint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
