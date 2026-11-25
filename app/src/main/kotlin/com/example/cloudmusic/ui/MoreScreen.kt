package com.example.cloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 飞牛胶囊导航「更多」页（docs/01 §0；飞牛风格骨架 + 我们的功能，非飞牛账号壳层）。
 *
 * 承载胶囊三 tab 之外的全部功能入口：歌单 / 搜索 / 队列 / 我的音乐 / 设置 / 数据源，
 * 一个都不丢。**曲库行已于 §61.274（V0.4-01 入口合并）删除**——曲库管理全功能并入底栏
 * 「音乐库」，全 App 只保留一个曲库入口（拍板 §61.272 #4）。**不含**飞牛的 admin 卡 / 修改密码 / 用户管理 / 退出登录 / 服务器设置
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
    /** 功能页入口标签（由 CloudMusicApp 提供，保持 03-10 §5 页面口径单一来源）。 */
    entryLabels: List<Pair<String, FnIcon>> = listOf(
        "歌单" to FnIcon.Playlist, "搜索" to FnIcon.Search,
        "队列" to FnIcon.Queue, "设置" to FnIcon.Settings,
    ),
) {
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // §61.353（G-10）：一级 tab 无返回键（§61.280 五层 BackHandler 不拦截一级 tab）。
        FnPageHeader("更多")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            FnGroupCard {
                // 行由 CloudMusicApp 提供的口径标签驱动（守护单一来源），
                // §61.274 起顺序：歌单(entryLabels[0]) / 搜索[1] / 队列[2] / 设置[3]。
                FnRow(entryLabels[0].second, entryLabels[0].first, onPlaylists, iconGlyphDp = 21f, titleSp = 16f)
                FnRow(
                    entryLabels[1].second, entryLabels[1].first, onSearch,
                    dividerAbove = true, iconGlyphDp = 21f, titleSp = 16f,
                )
                FnRow(
                    entryLabels[2].second, entryLabels[2].first, onQueue,
                    dividerAbove = true, iconGlyphDp = 21f, titleSp = 16f,
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            FnGroupCard {
                FnRow(FnIcon.Favorite, "我的音乐", onMine, iconGlyphDp = 21f, titleSp = 16f)
                FnRow(
                    entryLabels[3].second, entryLabels[3].first, onSettings,
                    dividerAbove = true, iconGlyphDp = 21f, titleSp = 16f,
                )
                FnRow(FnIcon.Sources, "音乐来源", onSources, dividerAbove = true, iconGlyphDp = 21f, titleSp = 16f)
            }
        }
    }
}
