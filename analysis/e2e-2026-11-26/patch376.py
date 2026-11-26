# -*- coding: utf-8 -*-
"""§61.376：设置页两级化 —— 一级页重排为 4 组 8 行 + 二级页切换。

按行号操作（heredoc/转义在本项目反复失效，行号最稳）。
"""
import io

P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SettingsScreen.kt'

BLOCK = '''            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            // 卡1：本地曲库统计（替代飞牛 admin 账号卡，spec §2 #2）——不造用户信息
            FnStatCard(
                icon = FnIcon.Songs,
                title = "本地曲库",
                primary = "$trackCount 首",
                secondary = "$sourceCount 个数据源",
            )
            Spacer(Modifier.height(FnBaseline.StatToNextGroupDp.dp))

            // ── §61.376：一级页 4 组 8 行（改前 3 组 14 行）──
            // 常用开关与「打开面板/二级页」留在一级；低频维护动作收进「音效 / 扫描 / 曲库工具」。
            FnSectionTitle("播放")
            FnGroupCard {
                FnRow(
                    icon = FnIcon.Network,
                    title = "移动网络播放",
                    onClick = { onWifiOnlyChanged(!wifiOnly) },
                    value = if (wifiOnly) "仅 WiFi 下播放" else "允许",
                    showChevron = false,
                    trailing = { FnSwitch(checked = !wifiOnly, onChange = { onWifiOnlyChanged(!it) }) },
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Output,
                    title = "播放到",
                    onClick = onOpenOutput,
                    value = "本机 / 投屏",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Sliders,
                    title = "音效",
                    onClick = { subPage = SettingsSubPage.SOUND },
                    // 摘要要写「点进去能改什么」，不写「已调整」这种没有信息量的状态。
                    value = "均衡器 · 淡入淡出 · 音量均衡",
                )
                // §61.211（G09 拍板 A / 预研报告 §4）：仅 MIUI 系设备显示——
                // 进程重建受 MIUI 拉活限制，应用侧唯一可行动点 = 引导手动开自启动。
                if (com.ccmsc.cloudmusic.MiuiAutostartGuide
                    .shouldShow(android.os.Build.MANUFACTURER)
                ) {
                    FnRow(
                        dividerAbove = true,
                        icon = FnIcon.Power,
                        title = "后台播放不中断（MIUI）",
                        onClick = onOpenAutostartSettings,
                        value = "去开启自启动",
                    )
                }
            }
            if (com.ccmsc.cloudmusic.MiuiAutostartGuide
                .shouldShow(android.os.Build.MANUFACTURER)
            ) {
                // §61.275（V0.4-02）：说明块加边界样式——与卡片行区分，长文不再像「行内小字」
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FnBaseline.PagePaddingDp.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(FnComp.CardBg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        com.ccmsc.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))

            FnSectionTitle("音乐库")
            FnGroupCard {
                FnRow(
                    icon = FnIcon.Gauge,
                    title = "扫描",
                    onClick = { subPage = SettingsSubPage.SCAN },
                    value = "最短 ${scanMinDurationMs / 1000} 秒" +
                        if (scanIgnoreDirs.isEmpty()) "" else " · 忽略 ${scanIgnoreDirs.size} 项",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Sources,
                    title = "音乐来源",
                    onClick = onOpenSources,
                    value = "$sourceCount 个",
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))

            FnSectionTitle("外观与维护")
            FnGroupCard {
                FnRow(
                    icon = FnIcon.Contrast,
                    title = "主题",
                    onClick = { showTheme = true },
                    value = when (themeMode) {
                        "LIGHT" -> "浅色"
                        "DARK" -> "深色"
                        "FOLLOW_SYSTEM" -> "跟随系统"
                        else -> "深色优先 · 跟随系统"
                    },
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Trash,
                    title = "清理播放缓存",
                    onClick = {
                        onClearCache()
                        cacheMsg = if (runtime.proxyRunning) "已执行" else "已执行；播放中会被拒绝"
                    },
                    // 动作行：点了立刻执行、不跳页 ⇒ 不画箭头（§61.376 全页统一这条规则）。
                    value = "释放空间",
                    showChevron = false,
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Duplicate,
                    title = "曲库工具",
                    onClick = { subPage = SettingsSubPage.TOOLS },
                    value = "刮削封面 · 检测重复",
                )
            }
            if (cacheMsg.isNotBlank()) {
                Text(
                    cacheMsg,
                    color = FnComp.SwitchGreen,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }'''.split('\n')

ELSE = '''    } else {
        when (subPage) {
            SettingsSubPage.SOUND -> SettingsSoundPage(
                eqEnabled = eqEnabled,
                eqEffectLevel = eqEffectLevel,
                fadeEnabled = fadeEnabled,
                onOpenEq = { showEq = true },
                onFadeEnabledChanged = onFadeEnabledChanged,
                onAnalyzeReplayGain = onAnalyzeReplayGain,
                onBack = { subPage = SettingsSubPage.NONE },
            )
            SettingsSubPage.SCAN -> SettingsScanPage(
                scanMinDurationMs = scanMinDurationMs,
                scanIgnoreDirs = scanIgnoreDirs,
                onRescan = onRescan,
                onOpenMinDuration = { showScanDuration = true },
                onOpenIgnoreDirs = { showIgnoreDirs = true },
                onBack = { subPage = SettingsSubPage.NONE },
            )
            SettingsSubPage.TOOLS -> SettingsToolsPage(
                onScrapeCovers = onScrapeCovers,
                onFindDuplicates = {
                    onFindDuplicates()
                    showDupReport = true
                },
                onBack = { subPage = SettingsSubPage.NONE },
            )
            SettingsSubPage.NONE -> Unit
        }
    }'''.split('\n')

with io.open(P, encoding='utf-8', newline='') as f:
    lines = f.read().split('\n')

assert lines[169].startswith('            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))'), lines[169]
assert lines[339].strip() == '}', repr(lines[339])
lines[169:340] = BLOCK

anchor = '    var showIgnoreDirs by remember { mutableStateOf(false) }'
i = lines.index(anchor)
lines.insert(i + 1, '    // §61.376：一级页 / 二级页切换；二级页时返回键回一级（见下一行 BackHandler）。')
lines.insert(i + 2, '    var subPage by remember { mutableStateOf(SettingsSubPage.NONE) }')
lines.insert(i + 3, '    BackHandler(enabled = subPage != SettingsSubPage.NONE) { subPage = SettingsSubPage.NONE }')

col = lines.index('    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {')
lines.insert(col, '    if (subPage == SettingsSubPage.NONE) {')

end = lines.index('    if (showTheme) {')
lines[end:end] = ELSE

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write('\n'.join(lines))
print('一级页重排完成，总行数:', len(lines))
