package com.ccmsc.cloudmusic.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ccmsc.cloudmusic.ui.player.loadLegacyTypeface
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccmsc.cloudmusic.SurfaceNav
import com.ccmsc.cloudmusic.di.playback.RuntimeState
import com.ccmsc.cloudmusic.model.SmartPlaylist
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.ErrorText
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.model.TrackInfo
import com.ccmsc.cloudmusic.output.dlna.DmrDevice
import com.ccmsc.cloudmusic.output.dlna.DeviceSource
import com.ccmsc.cloudmusic.output.dlna.ManualDmrSpec
import com.ccmsc.cloudmusic.output.router.ActiveOutput
import com.ccmsc.cloudmusic.output.router.OutputDeviceState
import com.ccmsc.cloudmusic.ui.player.LegacyPlayerPage
import com.ccmsc.cloudmusic.ui.player.PlayerStateBridge
import com.ccmsc.cloudmusic.player.PlayerPhase
import com.ccmsc.cloudmusic.player.PlayerState
import com.ccmsc.cloudmusic.player.QueueItem
import com.ccmsc.cloudmusic.storage.AppSettings
import com.ccmsc.cloudmusic.storage.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * §61.137 设置页（`main_account`/cky + `item_account_*` 行家族）：52dip 页头 +
 * 40dip 分组标题（b19）+ 50dip 行（bzv 左区 20dip 图标 + 16sp 标题 + 右侧 11sp 提示
 * + 箭头；b1a 开关行）。交互契约不变（SettingsStore effects）。
 */
@Composable
internal fun SettingsScreen(
    runtime: RuntimeState,
    wifiOnly: Boolean,
    onRescan: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenOutput: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onAnalyzeReplayGain: () -> Unit,
    onScrapeCovers: () -> Unit,
    /** §61.247（V0.3 M5 / A1 Tier2）：重复曲目检测触发与结果流。 */
    onFindDuplicates: () -> Unit = {},
    duplicateReport: StateFlow<com.ccmsc.cloudmusic.model.DuplicateReport?> = MutableStateFlow(null),
    onClearDuplicates: () -> Unit = {},
    fadeEnabled: Boolean,
    onFadeEnabledChanged: (Boolean) -> Unit,
    eqEnabled: Boolean,
    eqEffectLevel: Int,
    eqBandsMillibel: List<Int>,
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
    scanMinDurationMs: Long,
    onScanMinDurationChanged: (Long) -> Unit,
    scanIgnoreDirs: List<String>,
    onScanIgnoreDirsChanged: (List<String>) -> Unit,
    trackCount: Int,
    sourceCount: Int,
    /** §61.211（G09 拍板 A）：MIUI 自启动引导（仅 MIUI 系设备显示）。 */
    onOpenAutostartSettings: () -> Unit = {},
    onBack: () -> Unit,
) {
    // 操作结果反馈走页底提示位（飞牛行内没有提示位，不硬塞进 50.4dp 行框）。
    var showScanDuration by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showDupReport by remember { mutableStateOf(false) }
    var showIgnoreDirs by remember { mutableStateOf(false) }
    // §61.376：一级页 / 二级页切换；二级页时返回键回一级（见下一行 BackHandler）。
    var subPage by remember { mutableStateOf(SettingsSubPage.NONE) }
    var showMiuiGuide by remember { mutableStateOf(false) }
    BackHandler(enabled = subPage != SettingsSubPage.NONE) { subPage = SettingsSubPage.NONE }
    if (subPage == SettingsSubPage.NONE) {
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // 顶栏：从「更多」进入的**页内二级页**，返回钮语义是「回上一屏」，
        // 与飞牛「回 NAS 主屏」的宿主壳层返回箭头不同（spec §2 #1 / §61.175）。
        FnPageHeader("设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // §61.367：内容滚到页头下沿时是**硬切**（一行文字被齐齐削掉半截，读作裁剪 bug）。
                // 在滚动层自身上画 18dp `PageBg → 透明`渐变（不额外包 Box：外层容器的块体会
                // 圈进整页文字，§61.287 会把任意 `.height()` 误判成「含文字的定高容器」）。
                // 滚到顶时渐变盖的是 HeaderToCardGapDp=20.3dp 那段空档，不压第一张卡。
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(FnComp.PageBg, Color.Transparent),
                            endY = 18.dp.toPx(),
                        ),
                    )
                },
        ) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            // §61.377：统计卡并入「音乐库」组首行——真机实测「卡 + 间距」占 140dp，
            // 正好把「外观与维护」整组顶出屏幕。数字一个没少，只是不再单独吃一张大卡。

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
                        // §61.377：先开说明面板（含「去开启自启动」动作）——原实现点行直接跳系统设置，
                        // 但那段 4 行说明常驻在页面上，把「外观与维护」整组挤出了屏幕。
                        onClick = { showMiuiGuide = true },
                        value = "需手动开自启动",
                    )
                }
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))

            FnSectionTitle("音乐库")
            FnGroupCard {
                // §61.381：「本地曲库」行删除——它和下一行「音乐来源」**跳同一个页面**，
                // 是纯重复入口；曲目数与源数并进「音乐来源」的 value，信息一个没少。
                FnRow(
                    icon = FnIcon.Sources,
                    title = "音乐来源",
                    onClick = onOpenSources,
                    value = "$trackCount 首 · $sourceCount 个来源",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Gauge,
                    title = "扫描",
                    onClick = { subPage = SettingsSubPage.SCAN },
                    value = "最短 ${scanMinDurationMs / 1000} 秒" +
                        if (scanIgnoreDirs.isEmpty()) "" else " · 忽略 ${scanIgnoreDirs.size} 项",
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
                    icon = FnIcon.Duplicate,
                    title = "曲库工具",
                    onClick = { subPage = SettingsSubPage.TOOLS },
                    value = "刮削封面 · 检测重复",
                )
            }
            // §61.365：28dp 太贴底，滚动到底时最后一项（音量均衡分析）正好压在胶囊
            // 圆角上。加到 44dp，留出与分组间距同级的呼吸缝。
            Spacer(Modifier.height(44.dp))
        }
    }
    } else {
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
                onClearCache = onClearCache,
                onScrapeCovers = onScrapeCovers,
                onFindDuplicates = {
                    onFindDuplicates()
                    showDupReport = true
                },
                onBack = { subPage = SettingsSubPage.NONE },
            )
            SettingsSubPage.NONE -> Unit
        }
    }
    if (showMiuiGuide) {
        // §61.377：MIUI 专属说明——内容给全，但不常驻占屏。
        FnSheetDialog(
            onDismiss = { showMiuiGuide = false },
            title = "后台播放不中断（MIUI）",
            content = {
                Text(
                    com.ccmsc.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                    color = FnComp.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            },
            actions = listOf(
                "去开启自启动" to {
                    showMiuiGuide = false
                    onOpenAutostartSettings()
                },
                "知道了" to { showMiuiGuide = false },
            ),
        )
    }
    if (showTheme) {
        FnSheetDialog(
            onDismiss = { showTheme = false },
            title = "主题",
            actions = listOf(
                "深色优先 · 跟随系统（默认）" to {
                    onThemeChanged("DARK_FOLLOW_SYSTEM"); showTheme = false
                },
                "跟随系统" to { onThemeChanged("FOLLOW_SYSTEM"); showTheme = false },
                "深色" to { onThemeChanged("DARK"); showTheme = false },
                "浅色" to { onThemeChanged("LIGHT"); showTheme = false },
                "关闭" to { showTheme = false },
            ),
        )
    }
    if (showDupReport) {
        val report by duplicateReport.collectAsState()
        FnSheetDialog(
            onDismiss = {
                showDupReport = false
                onClearDuplicates()
            },
            title = "疑似重复曲目",
            content = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // 委托属性不能 smart cast —— 先落本地快照。
                    val current = report
                    when {
                        current == null -> Text(
                            "尚未检测或检测中…（重新点「检测重复曲目」刷新）",
                            color = FnComp.TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        current.groups.isEmpty() -> Text(
                            "未发现疑似重复（曲库 ${current.scanned} 首）",
                            color = FnComp.TextSecondary, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        else -> {
                            if (current.confirmed.isNotEmpty()) {
                                Text(
                                    "✓ 精确确认 ${current.confirmed.size} 组（内容 SHA-256 一致；指纹缓存 ${current.memoSize}/${current.candidates}" +
                                        if (current.candidates > current.memoSize) "，再次检测继续推进剩余候选）" else "）",
                                    color = FnComp.TextPrimary, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                current.confirmed.forEach { group ->
                                    Text(
                                        "${group.title} · ${group.artist}（${group.items.size} 份）",
                                        color = FnComp.TextPrimary, fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    group.items.forEach { t ->
                                        Text(
                                            "  · ${t.sourceRef.path}（${t.sourceType.name}）",
                                            color = FnComp.TextHint, fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                            if (current.fingerprintConfirmed.isNotEmpty()) {
                                Text(
                                    "≈ 声纹疑似同源 ${current.fingerprintConfirmed.size} 组（频谱指纹匹配，可能为转码拷贝；已解码 ${current.fingerprintedCount} 个候选）",
                                    color = FnComp.TextPrimary, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                current.fingerprintConfirmed.forEach { group ->
                                    Text(
                                        "${group.title} · ${group.artist}（${group.items.size} 份）",
                                        color = FnComp.TextPrimary, fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    group.items.forEach { t ->
                                        Text(
                                            "  · ${t.sourceRef.path}（${t.sourceType.name}）",
                                            color = FnComp.TextHint, fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                "疑似重复 ${current.groups.size} 组（时长 ±1s 同键聚合；是否重复由你判断，本应用只提示不删除）：",
                                color = FnComp.TextSecondary, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            current.groups.forEach { group ->
                                Text(
                                    "${group.title} · ${group.artist}（${group.items.size} 份）",
                                    color = FnComp.TextPrimary, fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                group.items.forEach { t ->
                                    Text(
                                        "  · ${t.sourceRef.path}（${t.sourceType.name}，${t.durationMs / 1000}s）",
                                        color = FnComp.TextHint, fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = listOf("关闭" to { showDupReport = false; onClearDuplicates() }),
        )
    }
    if (showEq) {
        EqDialog(
            enabled = eqEnabled,
            effectLevel = eqEffectLevel,
            bandsMillibel = eqBandsMillibel,
            onApply = { en, lvl, bands -> onApplyEqualizer(en, lvl, bands) },
            onDismiss = { showEq = false },
        )
    }
    if (showScanDuration) {
        FnSheetDialog(
            onDismiss = { showScanDuration = false },
            title = "扫描最短时长（下次扫描生效）",
            actions = listOf(5_000L to "5 秒", 10_000L to "10 秒", 30_000L to "30 秒", 60_000L to "60 秒")
                .map { (ms, label) ->
                    label to {
                        onScanMinDurationChanged(ms)
                        showScanDuration = false
                    }
                },
        )
    }
    if (showIgnoreDirs) {
        IgnoreDirsSheet(
            current = scanIgnoreDirs,
            onApply = {
                onScanIgnoreDirsChanged(it)
                showIgnoreDirs = false
            },
            onDismiss = { showIgnoreDirs = false },
        )
    }
}

/** 忽略目录编辑面板（逗号分隔 → 小写比对，契约不变）。 */
@Composable
internal fun IgnoreDirsSheet(
    current: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.joinToString(", ")) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "忽略目录（逗号分隔，下次扫描生效）",
        content = {
            FnDialogInput(
                text, { text = it },
                hint = "如：System Volume Information, .Trash",
                autoFocus = true,
            )
        },
        actions = listOf(
            "应用" to {
                onApply(
                    text.split(',').map { it.trim() }.filter { it.isNotBlank() },
                )
            },
        ),
    )
}

/** 03-10 §11 #8：蓝牙只作状态提示，不列为与本机/DLNA 并列的可选输出（b5y 面板）。 */
@Composable
internal fun OutputDialog(
    state: OutputDeviceState,
    devices: List<DmrDevice> = emptyList(),
    lastDmrUsn: String? = null,
    castVolume: Int = 50,
    onCastVolumeChanged: (Int) -> Unit = { _ -> },
    onOutputSelected: (ActiveOutput) -> Unit,
    onPickDmr: (DmrDevice) -> Unit = {},
    onScanDlna: () -> Unit = {},
    onOpenManualDmr: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "播放到 · 当前：${if (state.activeOutput == ActiveOutput.LOCAL) "本机" else "投屏"}",
        content = {
            Column {
                // §61.275（V0.4-02 文案人话化）：原段落是开发者口径（§ 编号 + 「推流 /
                // 会话引擎 / 并列输出」），用户读不懂；改为白话说明，信息量不减。
                Text(
                    "选择声音从哪里播放。投屏后手机本机静音，断开或切歌时会自动回到本机。" +
                        // §61.383（真机 2026-11-27）：投屏是**原始文件直传**（代理不转码）——
                        // 曲库 82% 是 APE/FLAC，实测天猫精灵对 APE 连拉流都不发起（能连上、
                        // 无数据），MP3 正常。静默失败最劝退，这里如实说明。
                        "无损格式（APE/FLAC）部分音箱不支持，没声音时请换 MP3 版本。" +
                        "蓝牙设备请在系统设置里连接。",
                    color = FnComp.TextHint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (state.routeHint.isNotBlank()) {
                    Text(
                        "状态提示：${state.routeHint}",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (devices.isNotEmpty()) {
                    Text(
                        "发现 ${devices.size} 台可投屏设备（点选直接投屏）：",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // §61.229：投屏态 DMR 音量滑杆（RenderingControl Master channel）。
                if (state.activeOutput == ActiveOutput.DLNA && devices.isNotEmpty()) {
                    Text(
                        "投屏音量：$castVolume",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Slider(
                        value = castVolume.toFloat(),
                        onValueChange = { onCastVolumeChanged(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        },
        // §61.200：设备列表直接映射为面板动作——手动设备 / 最近发现已由 DmrDeviceSelector 排序。
        actions = buildList {
            add("本机（手机扬声器 / 有线输出）" to { onOutputSelected(ActiveOutput.LOCAL) })
            devices.forEach { device ->
                // §61.241：上次成功投屏的设备标记（发现列表里在线才显示，不伪造）。
                val kind = if (device.source == DeviceSource.MANUAL) "手动" else "发现"
                val badge = if (device.usn == lastDmrUsn) " · 上次投屏" else ""
                add("${device.friendlyName} · $kind$badge" to { onPickDmr(device) })
            }
            add("自动选择投屏设备（记住上次）" to { onOutputSelected(ActiveOutput.DLNA) })
            add("重新搜索投屏设备" to onScanDlna)
            add("手动添加投屏设备（IP / 端口）" to onOpenManualDmr)
            add("关闭" to onDismiss)
        },
    )
}

/**
 * §61.201 / 03-08 §4.2：手动指定 DMR 的输入面板。
 *
 * 复用既有 [ManualDmrProbe]（候选描述路径 + 明确失败原因）与生产 DescriptionFetcher，
 * UI 只收 IP / 端口 / 可选描述地址 / 备注，不自己解析 XML。探测结果由 Activity 以
 * [Result] 回传：成功显示设备并允许直接投屏，失败显示探测器给出的原因（不静默）。
 */
@Composable
internal fun ManualDmrDialog(
    result: Result<DmrDevice>?,
    onProbe: (ManualDmrSpec) -> Unit,
    onPick: (DmrDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var descUrl by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val found = result?.getOrNull()
    val probeError = result?.exceptionOrNull()?.message
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "手动添加投屏设备",
        // §61.295：4 格 + 探测结果区（成功时还会多出「添加」入口），同样需要滚动。
        scrollable = true,
        content = {
            Column {
                // §61.293：首格自动聚焦，回车逐格下移，末格（备注名）回车收键盘。
                FnDialogInput(host, { host = it }, hint = "设备 IP / 主机名，如 192.168.1.5", autoFocus = true, imeAction = ImeAction.Next)
                FnDialogInput(port, { port = it }, hint = "端口（可空，默认试 8200 / 49152）", imeAction = ImeAction.Next)
                FnDialogInput(descUrl, { descUrl = it }, hint = "设备说明地址（可空，填了只试它）", imeAction = ImeAction.Next)
                FnDialogInput(label, { label = it }, hint = "备注名（可空）")
                if (localError != null || probeError != null) {
                    Text(
                        localError?.let { "请检查输入：$it" } ?: "探测失败：$probeError",
                        color = FnComp.IconRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (found != null) {
                    Text(
                        "已找到：${found.friendlyName}（${found.baseUrl}）",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        actions = buildList {
            add("探测设备" to probe@{
                val spec = ManualDmrSpec(
                    host = host.trim(),
                    port = port.trim().toIntOrNull(),
                    descriptionUrl = descUrl.trim().takeIf { it.isNotBlank() },
                    label = label.trim(),
                )
                if (spec.host.isBlank() && spec.descriptionUrl == null) {
                    localError = "请填写 IP 或描述地址"
                    return@probe
                }
                localError = null
                onProbe(spec)
            })
            if (found != null) add("投屏到 ${found.friendlyName}" to { onPick(found) })
            add("返回" to onDismiss)
        },
    )
}

/** §61.231：均衡器面板——5 段滑杆（millibel，±15dB 口径）+ 档位 + 开关；
 *  应用走 applyEqualizer 全链（engine setEqualizerBands band 数不匹配取 min）。 */
@Composable
internal fun EqDialog(
    enabled: Boolean,
    effectLevel: Int,
    bandsMillibel: List<Int>,
    onApply: (Boolean, Int, List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    // §61.236：段数自适应——引擎 setEqualizerBands band 数不匹配取 min（§61.118），
    // 面板段数 = max(5, 已存自定义长度)：5 段用固定频段名，更多段用通用 Band N 标签。
    val count = kotlin.math.max(5, bandsMillibel.size)
    val bands = remember(bandsMillibel, count) {
        List(count) { bandsMillibel.getOrNull(it) ?: 0 }
    }
    var on by remember { mutableStateOf(enabled) }
    var level by remember { mutableStateOf(effectLevel.coerceIn(0, 5)) }
    // §61.236：滑杆状态数组化（段数自适应，不再固定 b0~b4）
    var bandValues by remember { mutableStateOf(bands.map { it.toFloat() }) }
    val labels = if (count == 5) listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    else (1..count).map { "Band $it" }
    val values = bandValues
    val db = { v: Float -> "%.1f dB".format(v / 100f) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "均衡器",
        content = {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                FnRow(
                    icon = FnIcon.Sliders,
                    title = "启用均衡器",
                    onClick = { on = !on },
                    showChevron = false,
                    trailing = { FnSwitch(checked = on, onChange = { on = it }) },
                )
                if (on) {
                    Text(
                        "效果档位：$level",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 0f..5f,
                        steps = 4,
                    )
                    labels.forEachIndexed { i, label ->
                        Text(
                            label + " · " + db(values[i]),
                            color = FnComp.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Slider(
                            value = values[i],
                            onValueChange = { v ->
                                bandValues = bandValues.toMutableList().also { it[i] = v }
                            },
                            valueRange = -1500f..1500f,
                        )
                    }
                }
            }
        },
        actions = listOf(
            "应用" to {
                onApply(
                    on,
                    level,
                    bandValues.map { it.toInt() },
                )
                onDismiss()
            },
            "关闭" to onDismiss,
        ),
    )
}

/** §61.151：STRM 独立源添加（b5y 面板；字段与 StrmDriver str() 对齐——
 *  delegate_storage_id + paths 多行 `key:dst` / 裸 `dst`，空 = 委托根目录）。
 *  委托候选 = 已添加且非 strm 的存储；选中行高亮品牌红。 */
