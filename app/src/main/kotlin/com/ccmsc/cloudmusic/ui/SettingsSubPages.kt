package com.ccmsc.cloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §61.376（2026-11-26）设置页**两级化**：一级只留高频入口，低频维护动作下沉。
 *
 * ## 为什么改
 * 改之前一级页是 **14 行平铺 / 3 组**：
 * - 常用开关（移动网络播放、淡入淡出）与**低频维护动作**（刮削封面、检测重复、音量均衡分析）
 *   挤在同一层，全是 50dp 行 —— 用户「太多太乱」；
 * - 「点了立即执行」的动作（清理缓存 / 重新扫描 / 刮削 / 检测）与「打开面板」的行长得完全一样，
 *   右侧都画箭头 ⇒ 不知道哪行会立刻跑任务；
 * - 同类项被拆散：扫描相关占 3 行（重扫 / 忽略目录 / 最短时长），却没有一处说「这三个是一件事」。
 *
 * ## 现在
 * 一级 4 组 8 行（4×标题 + 8×行），低频项按**功能域**收进三个二级页：
 * - [SettingsSoundPage]：均衡器 / 淡入淡出 / 音量均衡分析（都改「听感」）
 * - [SettingsScanPage]：重新扫描 / 最短时长 / 忽略目录（都改「扫描行为」）
 * - [SettingsToolsPage]：刮削封面 / 检测重复（都是「一次性维护任务」，跑完即走）
 *
 * 二级页只做**入口重组**，弹窗与回调全部沿用 `SettingsScreen` 的既有实现，零行为改动。
 */
internal enum class SettingsSubPage { NONE, SOUND, SCAN, TOOLS }

/**
 * 二级页外壳：与一级页同一套背景/间距/页头（`FnPageHeader` 的返回语义是「回上一屏」）。
 * 页脚留 44dp，与一级页一致（§61.365 的胶囊让位口径不变）。
 */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader(title, onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 与一级页同一个顶部渐隐手法（§61.367）：滚到页头下沿不硬切。
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(FnComp.PageBg, Color.Transparent),
                            endY = 18.dp.toPx(),
                        ),
                    )
                }
                .padding(top = FnBaseline.HeaderToCardGapDp.dp),
        ) {
            content()
            Spacer(Modifier.height(44.dp))
        }
    }
}

/** 二级页共用的操作反馈行（与一级页「已开始…」同一视觉：绿色小字）。 */
@Composable
internal fun SubPageFeedback(msg: String) {
    if (msg.isBlank()) return
    Text(
        msg,
        color = FnComp.SwitchGreen,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

/**
 * 音效页：三件事都只影响「听起来怎么样」。
 *
 * 分级理由：均衡器是**面板**（改参数后立即生效）、淡入淡出是**开关**（影响切歌手感）、
 * 音量均衡分析是**一次性任务**（跑完写库）。三者在原页面排在一起时各自为政，
 * 现在明确成「一个域」，并给一句话说明它们不冲突。
 */
@Composable
internal fun SettingsSoundPage(
    eqEnabled: Boolean,
    eqEffectLevel: Int,
    fadeEnabled: Boolean,
    onOpenEq: () -> Unit,
    onFadeEnabledChanged: (Boolean) -> Unit,
    onAnalyzeReplayGain: () -> Unit,
    onBack: () -> Unit,
) {
    var msg by remember { mutableStateOf("") }
    SubPage("音效", onBack) {
        FnGroupCard {
            FnRow(
                icon = FnIcon.Sliders,
                title = "均衡器",
                onClick = onOpenEq,
                value = if (eqEnabled) "开 · 档位 $eqEffectLevel" else "关",
            )
            FnRow(
                dividerAbove = true,
                icon = FnIcon.Wave,
                title = "淡入淡出",
                onClick = { onFadeEnabledChanged(!fadeEnabled) },
                value = if (fadeEnabled) "开（起播渐入 / 分轨渐出）" else "关",
                showChevron = false,
                trailing = { FnSwitch(checked = fadeEnabled, onChange = onFadeEnabledChanged) },
            )
            FnRow(
                dividerAbove = true,
                icon = FnIcon.Level,
                title = "音量均衡分析",
                onClick = {
                    onAnalyzeReplayGain()
                    msg = "已开始分析（每次最多 50 首，播放时音量更均匀）"
                },
                value = "让每首歌音量接近",
                // 动作行：点了立刻跑任务，不跳页面 ⇒ 不画箭头（一级页同理）。
                showChevron = false,
            )
        }
        Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
        Text(
            "三处互不影响：均衡器改频段、淡入淡出改切歌手感、音量均衡只写曲目增益。",
            color = FnComp.TextHint,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        SubPageFeedback(msg)
    }
}

/**
 * 扫描页：三件事都只影响「怎么扫」，且都是**下次扫描生效**（重扫是立即触发）。
 *
 * 原页把这三点拆成三行且彼此不相邻的语义没人解释；现在同页 + 一句「下次扫描生效」总说明。
 */
@Composable
internal fun SettingsScanPage(
    scanMinDurationMs: Long,
    scanIgnoreDirs: List<String>,
    onRescan: () -> Unit,
    onOpenMinDuration: () -> Unit,
    onOpenIgnoreDirs: () -> Unit,
    onBack: () -> Unit,
) {
    var msg by remember { mutableStateOf("") }
    SubPage("扫描", onBack) {
        FnGroupCard {
            // §61.387：**「重新扫描音乐库」已删** —— 来源页「维护 · 全量重扫」是同一动作，
            // 同一功能在两个二级页各有一个入口，用户不知道该点哪个。重扫归来源页。
            FnRow(
                icon = FnIcon.Gauge,
                title = "最短时长",
                onClick = onOpenMinDuration,
                value = "跳过短于 ${scanMinDurationMs / 1000} 秒的文件",
            )
            FnRow(
                dividerAbove = true,
                icon = FnIcon.FolderOff,
                title = "忽略目录",
                onClick = onOpenIgnoreDirs,
                value = if (scanIgnoreDirs.isEmpty()) "未设置" else "${scanIgnoreDirs.size} 项",
            )
        }
        Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
        Text(
            "两项都在下次扫描生效；要立刻重扫全库，去「音乐来源 → 维护 → 全量重扫」。",
            color = FnComp.TextHint,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        SubPageFeedback(msg)
    }
}

/**
 * 曲库工具页：两个**一次性维护任务**，共同点是「跑完即走、只提示不自动改」。
 *
 * 原页把它们和「主题」「淡入淡出」排在一起，动作与设置混淆；独立成页后
 * 一级页不再出现「点了会跑很久」的行。
 */
@Composable
internal fun SettingsToolsPage(
    onClearCache: () -> Unit,
    onScrapeCovers: () -> Unit,
    onFindDuplicates: () -> Unit,
    onBack: () -> Unit,
) {
    var msg by remember { mutableStateOf("") }
    SubPage("维护工具", onBack) {
        FnGroupCard {
            // §61.377：从一级页移下来——它和刮削/去重同类（一次性维护动作），
            // 留在一级只是为了腾出「外观与维护」组的可见高度。
            FnRow(
                icon = FnIcon.Trash,
                title = "清理播放缓存",
                onClick = {
                    onClearCache()
                    msg = "已执行（正在播放时会被拒绝）"
                },
                value = "释放空间",
                showChevron = false,
            )
            FnRow(
                dividerAbove = true,
                icon = FnIcon.Image,
                title = "刮削缺失封面",
                onClick = {
                    onScrapeCovers()
                    msg = "已开始刮削（每次最多 50 首，联网匹配专辑封面）"
                },
                value = "联网补齐缺失封面",
                showChevron = false,
            )
            FnRow(
                dividerAbove = true,
                icon = FnIcon.Duplicate,
                title = "检测重复曲目",
                onClick = {
                    onFindDuplicates()
                    msg = "已开始检测（按标题/艺人 + 时长 ±1s 分组，仅提示不自动删除）"
                },
                value = "找出疑似重复的曲目",
                showChevron = false,
            )
        }
        Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
        Text(
            "三个动作都在后台执行，不会改动你的文件；检测结果只作提示。",
            color = FnComp.TextHint,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        SubPageFeedback(msg)
    }
}
