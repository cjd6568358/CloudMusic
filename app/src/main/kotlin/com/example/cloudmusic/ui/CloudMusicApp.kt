package com.example.cloudmusic.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cloudmusic.ui.player.loadLegacyTypeface
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
import com.example.cloudmusic.SurfaceNav
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.model.SmartPlaylist
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.ErrorText
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.model.TrackInfo
import com.example.cloudmusic.output.dlna.DmrDevice
import com.example.cloudmusic.output.dlna.DeviceSource
import com.example.cloudmusic.output.dlna.ManualDmrSpec
import com.example.cloudmusic.output.router.ActiveOutput
import com.example.cloudmusic.output.router.OutputDeviceState
import com.example.cloudmusic.ui.player.LegacyPlayerPage
import com.example.cloudmusic.ui.player.PlayerStateBridge
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * T-A6（`12 §33`）/ 第三十一批（`12 §40`）：V0.1 最小自研 UI。
 *
 * 页面（03-10 §5 的 V0.1 子集）：曲库 / 歌单 / 搜索 / 队列 / 设置 五页 + 数据源管理入口
 * + 输出设备弹窗。
 *
 * `12 §61.40`（用户「UI 交互严格复刻竞品」）：点击迷你播放条进入 `LegacyPlayerPage`。
 * `12 §61.137`（用户「像素级对齐竞品 UI，包括设置、交互 UI，不只是播放页」）：
 * 非播放页全部按 `analysis/ui-replica/page-spec-61-137.md` 重建——色值/尺寸/字号取自
 * 竞品 `music_library_*` / `main_account` / `item_account_*` / 搜索家族 layout 一手证据，
 * 共享组件在 [CompeteUi.kt]（[Comp] 设计系统）。接线（回调/Repository/bridge）零改动，
 * 仅视觉与组件结构替换；图标用字形占位不导出竞品素材（C4）。
 *
 * 边界（`03-10 §11`）：只经 Repository / StateFlow 取数，不碰 Driver / Room / OkHttp；
 * 蓝牙只作状态提示、**不列为与本机/DLNA 并列的可选输出**（禁止事项 8）。
 * V0.1 用状态切页，未引 navigation-compose / coil-compose。
 */

/**
 * §61.168（飞牛导航）：功能页口径标签——胶囊「更多」页的入口单一来源。
 *
 * §61.274（V0.4-01 入口合并，拍板 §61.272 #4）：**「曲库」行已删**——原 tab7 曲库管理
 * 的全部功能（视图组 / ＋入歌单 / 不可用重试 / 重扫）并入底栏「音乐库」，全 App 只保留
 * 一个曲库入口；本表随之由五页收缩为四页（歌单 / 搜索 / 队列 / 设置），功能一个不丢。
 */
private val FUNCTION_PAGE_LABELS = listOf(
    "歌单" to FnIcon.Playlist, "搜索" to FnIcon.Search,
    "队列" to FnIcon.Queue, "设置" to FnIcon.Settings,
)

/**
 * 飞牛搜索页「搜索发现」热词 —— 实现迁到 [SearchDiscovery.hotWords]（§61.326 / A-3）。
 *
 * 旧实现直接拿原始 artist / album 字符串分组，`刘若英` / `劉若英` / `"刘若英` 会并列成
 * 三个胶囊（评审 §六-5、真机 01:43 截图）。归一化与去重逻辑连同字表现在都在
 * [SearchDiscovery] 里，可被 JVM 单测直接断言——本文件只负责调用。
 */

@Composable
fun CloudMusicApp(
    tracks: Flow<List<Track>>,
    storages: Flow<List<Storage>>,
    playlists: Flow<List<Playlist>>,
    queue: Flow<List<QueueItem>>,
    outputState: Flow<OutputDeviceState>,
    settings: Flow<AppSettings>,
    search: (String) -> Flow<List<Track>>,
    runtimeState: StateFlow<RuntimeState>,
    playerState: StateFlow<PlayerState>,
    onRescanAll: () -> Unit,
    onRetryUnavailable: () -> Unit,
    /** §61.207（A2-③）：单源刷新（数据源管理页「刷新此源」行内动作）——定向增量扫。 */
    onRefreshStorage: (String) -> Unit = { _ -> },
    /** §61.208（A4）：合并重复项开关（scan.mergeDuplicates 的 UI 消费口径）。 */
    mergeDuplicates: Boolean = false,
    onMergeDuplicatesChanged: (Boolean) -> Unit = { _ -> },
    /** §61.211（G09 拍板 A）：MIUI 自启动引导跳转（仅 MIUI 系设备显示行）。 */
    onOpenAutostartSettings: () -> Unit = {},
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
    /** §61.132：添加 WebDAV 源（url / username / password / rootPath）。 */
    onAddWebDav: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** §61.151：添加 STRM 独立源（delegateStorageId + paths 多行，空=委托根目录）。 */
    onAddStrm: (String, String) -> Unit = { _, _ -> },
    /** V0.2 §61.216：添加 SFTP 源（host / port / username / password / rootPath）。 */
    onAddSftp: (String, Int, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    /** V0.2 §61.228：添加 FTP 源（host / port / username / password / rootPath / ssl）。 */
    onAddFtp: (String, Int, String, String, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    /** §61.152：移除数据源（markOrphan → 曲目标记不可用，文件不删）。 */
    onRemoveStorage: (String) -> Unit = { _ -> },
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onOpenOutput: () -> Unit,
    /** §61.110：输出弹窗选择（本机 / DLNA）——DLNA 走发现 → 推流 → setActive 闭环。 */
    onOutputSelected: (ActiveOutput) -> Unit = {},
    /** §61.200：输出弹窗的 DMR 设备列表（MainActivity 打开弹窗时发现并排序去重）。 */
    dlnaDevices: StateFlow<List<DmrDevice>> = MutableStateFlow(emptyList()),
    /** §61.241（V3-M2）：上次投屏设备 USN——弹窗里给该设备标「上次投屏」。 */
    lastDmrUsn: StateFlow<String?> = MutableStateFlow(null),
    /** §61.229：投屏态 DMR 音量（输出弹窗滑杆）；回调转发 runtime.setCastVolume。 */
    castVolume: StateFlow<Int> = MutableStateFlow(50),
    onCastVolumeChanged: (Int) -> Unit = { _ -> },
    /** §61.200：点具体设备直接投屏（跳过自动选择）。 */
    onPickDmr: (DmrDevice) -> Unit = {},
    /** §61.200：弹窗内「重新扫描」触发重新发现。 */
    onScanDlna: () -> Unit = {},
    /** §61.201：手动指定 DMR 的探测结果（null = 未探测）。 */
    manualDmrResult: StateFlow<Result<DmrDevice>?> = MutableStateFlow(null),
    /** §61.201：提交手动探测（IP / 端口 / 描述地址）。 */
    onProbeManualDmr: (ManualDmrSpec) -> Unit = {},
    /** §61.201：打开手动面板时清掉上次结果，避免旧状态误导。 */
    onClearManualDmrResult: () -> Unit = {},
    /** §61.113（M2-1）：曲库分组视角播放入口——分组曲目替换业务队列并从 index 起播。 */
    onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    /** §61.116（M6-3）：清理 B2 播放缓存（播放中由 runtime 侧拒绝）。 */
    onClearCache: () -> Unit = {},
    /** §61.122（T5-2）：ReplayGain 近似分析（LOCAL 未分析曲目，cap 50）。 */
    onAnalyzeReplayGain: () -> Unit = {},
    /** §61.233：封面在线刮削批处理（限数 50，toast 汇报）。 */
    onScrapeCovers: () -> Unit = {},
    /** §61.247（V0.3 M5 / A1 Tier2）：重复曲目检测（设置页触发 → 结果面板）。 */
    onFindDuplicates: () -> Unit = {},
    /** §61.247：检测结果流（null = 未检测）。 */
    duplicateReport: StateFlow<com.example.cloudmusic.model.DuplicateReport?> = MutableStateFlow(null),
    /** §61.247：关闭结果面板时清空报告（下次触发重算）。 */
    onClearDuplicates: () -> Unit = {},
    /** §61.230：淡入淡出开关（AudiofxSettings.fadeEnabled 热更新）。 */
    fadeEnabled: Boolean = false,
    onFadeEnabledChanged: (Boolean) -> Unit = { _ -> },
    /** §61.231：均衡器面板（AudiofxSettings + applyEqualizer 全链已接）。 */
    eqEnabled: Boolean = false,
    eqEffectLevel: Int = 0,
    eqBandsMillibel: List<Int> = emptyList(),
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit = { _, _, _ -> },
    /** §61.234：主题模式（设置页四选项；改动即 recreate）。 */
    themeMode: String = "DARK_FOLLOW_SYSTEM",
    /**
     * §61.291：主题计算结果（MainActivity `applyThemeFromSettings()` 的返回值）。
     * 系统栏明暗靠它决定——UI 侧不重算 `uiMode`，避免两处判断漂移。
     */
    darkTheme: Boolean = true,
    onThemeChanged: (String) -> Unit = { _ -> },
    /** §61.116（M2-2）：扫描最短时长下限（ms），下次扫描生效。 */
    scanMinDurationMs: Long = 10_000L,
    onScanMinDurationChanged: (Long) -> Unit = {},
    /** §61.117（M2-2）：扫描忽略目录（小写比对），下次扫描生效。 */
    scanIgnoreDirs: List<String> = emptyList(),
    onScanIgnoreDirsChanged: (List<String>) -> Unit = {},
    /** §61.119（M3/M8）：歌单与队列交互（bridge 直达）。 */
    bridge: PlayerStateBridge,
    /**
     * §61.183（三层守护网之③）：验收期画面证据通道 —— intent 导航 surface 请求流
     * （MainActivity 从 `SurfaceNav.parse` 粘性下发；默认空流 = 常规启动零行为变化）。
     * MIUI 禁 `input tap`，runbook 由此免注入直达播放页等 surface 截图取证。
     */
    surfaceRequests: StateFlow<SurfaceNav.Surface?> = MutableStateFlow(null),
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    // §61.201：手动 DMR 面板与输出面板互斥（避免两个 ModalBottomSheet 叠加）。
    var showManualDmr by rememberSaveable { mutableStateOf(false) }
    // §61.168（飞牛导航）：胶囊三 tab 的「更多」入口面板；内部 tab 索引保持不变。
    var showMore by rememberSaveable { mutableStateOf(false) }
    // 12 §61.42：用户要求「1:1 像素级复刻竞品播放页」→ 启动即全屏播放页，
    // V0.1 六页 tab 导航栏（曲库/歌单/搜索/队列/我的/设置）不再出现在首屏；
    // 播放页返回才回到功能页，避免把自研导航误当成竞品播放页组件。
    // §61.169（2026-11-16）：该口径作废——首页/音乐库骨架与启动页已就位，竞品冷启动落在**首页**
    // 而非播放页；继续「启动即播放页」会让首页/音乐库在冷启动时完全不可达，与用户
    // 「从启动页起 1:1 复刻」的目标冲突。改为冷启动落首页，播放页由迷你条/点歌进入。
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    // §61.320（真机 2026-11-24 一手）：播放页是覆盖在 Scaffold 之上的整页（不是独立路由），
    // 搜索框仍留在组合里且**保持焦点**——从搜索结果点歌进播放页时，输入法会一路跟过来，
    // 把暂停/上一首/下一首/进度条整块盖住（截图实证：播放页下半屏被键盘占据）。
    // 键盘只属于输入页：进入整页播放页时主动清焦点并收起 IME。
    val imeController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(showPlayer) {
        if (showPlayer) {
            focusManager.clearFocus()
            imeController?.hide()
        }
    }
    // §61.280（V0.4-07 ①，03-10 §9 B16）：次级页「从哪进来」——返回键与页头「←」都回**进入方**，
    // 不再写死回「更多」面板。-1 = 更多面板；≥0 = 该 tab 序号。
    var subOrigin by rememberSaveable { mutableIntStateOf(-1) }
    // §61.280（V0.4-07 ③）：我的音乐入口语义——false = 收藏段（默认），true = 最近播放段。
    var mineFocusRecent by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // §61.187：搜索页返回目标（-1 = 从「更多」进入 → 返回更多；≥0 = 从该 tab 进入）。
    // 旧实现「取消」只清关键字不退出页面 ⇒ 用户「返回也返回不了」。
    var searchReturnTo by rememberSaveable { mutableIntStateOf(-1) }
    // §61.192 勘误（用户「进了设置页为什么底部TAB没了」）：§61.187 把底栏隐藏扩大到全部
    // 次级页是**过度隐藏**——修正后设置页恢复显示底栏。
    // §61.222（用户指示）：搜索升为一等 tab（底栏四胶囊），「仅搜索页隐藏底栏」口径
    // 随之作废（capsuleHidden 判定删除）——搜索页保持底栏可见，选中态可见性优先。
    // §61.292（V0.4 页面状态保持）：一级页面各自的 saveable 槽——tab 切走再切回，
    // LazyColumn 滚动位置与 `rememberSaveable` 的视图选择原样恢复（此前整体离组合即丢弃）。
    val pageStateHolder = rememberSaveableStateHolder()
    // §61.119（M3）：加入歌单弹窗目标曲目。
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    // §61.294：动作反馈通道（Toast）——面板点完即关，此前不留任何痕迹。
    val feedbackContext = LocalContext.current

    // §61.183：验收期导航请求兑现（粘性流，启动页结束后同样生效；恒 null = 无动作）。
    LaunchedEffect(surfaceRequests) {
        surfaceRequests.collect { req ->
            when (req) {
                SurfaceNav.Surface.PLAYER -> showPlayer = true
                SurfaceNav.Surface.HOME -> showPlayer = false
                // §61.212（B2 取证导航扩展）：MIUI 禁 input tap，次级页经 intent 直达。
                // §61.238：三页直达必须关播放页覆盖层——否则从播放页 intent 直达被覆盖层吞掉。
                SurfaceNav.Surface.LIBRARY -> { showPlayer = false; showMore = false; tab = 1 }
                SurfaceNav.Surface.SEARCH -> { showPlayer = false; showMore = false; searchReturnTo = 0; tab = 3 }
                SurfaceNav.Surface.SETTINGS -> { showPlayer = false; showMore = false; tab = 6 }
                null -> Unit
            }
        }
    }

    val trackList by tracks.collectAsState(initial = emptyList())
    val storageList by storages.collectAsState(initial = emptyList())
    val playlistList by playlists.collectAsState(initial = emptyList())
    val queueList by queue.collectAsState(initial = emptyList())
    val output by outputState.collectAsState(
        initial = OutputDeviceState(ActiveOutput.LOCAL, null, ""),
    )
    val runtime by runtimeState.collectAsState()
    val player by playerState.collectAsState()
    val appSettings by settings.collectAsState(initial = AppSettings())
    // §61.167（飞牛首页）：收藏 / 最近播放数供首页卡片流显示（本地真实数据，不造）。
    val homeFavorites by bridge.observeFavorites().collectAsState(initial = emptySet())
    // §61.328（B-1）：首页卡片只要**数量**——原实现为此订阅整个历史列表（几千行全量映射）。
    val homeHistoryCount by bridge.observeHistoryCount().collectAsState(initial = 0)

    // §61.328（B-1）搜索防抖：原写法 `remember(query) { search(query) }` 每击一键就换 Flow，
    // Room 立刻跑一次 FTS + 一次全表 LIKE（`%q%` 走不了索引）。改为一轮输入静默后再查。
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        val wait = searchDebounceMs(query)
        if (wait > 0) delay(wait)
        debouncedQuery = query
    }
    val resultsFlow = remember(debouncedQuery) { search(debouncedQuery) }
    // 不能用 collectAsState(initial = emptyList())：换 Flow 时它会退回初值，打字过程中
    // 结果区会闪成空白。这里保留上一轮结果，等新结果到达再整体替换（只换不空）。
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    // §61.328（B-1）：只在「搜索页在前台 + 有关键字」时订阅查询流。
    // 空关键字下 search("") 返回 observeAll()——7,500 首全量 Collator 排序，而此刻搜索页
    // 显示的是热词、根本不渲染结果；旧实现让这份全库排序在 App 整个生命周期里跟着每次
    // 曲库写入重跑一遍。切走 tab 时停订阅，结果保留上一轮（回来立刻重放）。
    LaunchedEffect(resultsFlow, tab, debouncedQuery) {
        if (tab != 3 || debouncedQuery.isBlank()) return@LaunchedEffect
        resultsFlow.collect { searchResults = it }
    }

    // §61.187（W7 全屏沉浸）/ §61.270（用户 2026-11-21 口径）：
    // 顶部全面屏——状态栏透明 + 内容延伸到状态栏后面（竞品真机：时间/信号叠在深灰页面上，
    // 无黑块）。实测 MIUI 下 hide(statusBars()) 不生效（状态栏仍在），故改用透明状态栏方案：
    //   setDecorFitsSystemWindows(false) + statusBarColor 透明 → 页面渐变背景顶到屏幕上沿。
    // 底部保留系统操作栏——导航栏不清透明、不隐藏（用户明确要求，竞品亦保留三键导航）。
    // 关闭播放页时按当前主题重新上色（§61.291：目标态重算，不再保存/还原窗口原值）。
    // §61.291（V0.4 系统栏明暗跟随）：系统栏改为「声明式目标态」——
    // 由 (showPlayer, darkTheme) 一次算出该是什么颜色，退出播放页按**当前主题**重算，
    // 不再保存窗口原值再还原（原实现只改状态栏，浅色主题下普通页仍是 themes.xml 的纯黑条）。
    val view = LocalView.current
    val activityWindow = (view.context as? android.app.Activity)?.window
    // theme optical：windowOptOutEdgeToEdgeEnforcement=true 时 decorView fits=true。
    // 在进入播放页之前读一次基线（播放页会把它改成 false），退出时还原到这个基线。
    val themeDecorFits: Boolean = remember(activityWindow) {
        activityWindow?.decorView?.let {
            androidx.core.view.ViewCompat.getFitsSystemWindows(it)
        } ?: true
    }
    DisposableEffect(showPlayer, darkTheme) {
        if (activityWindow != null) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(
                activityWindow,
                if (showPlayer) false else themeDecorFits,
            )
            SystemBars.apply(activityWindow, darkTheme, showPlayer)
            if (showPlayer) {
                // 内容延伸到状态栏后，透明状态栏让页面渐变顶上去（消除黑块）；
                // 导航栏保留三键、压黑不隐藏（用户明确要求）。
                val controller = WindowInsetsControllerCompat(activityWindow, view)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose { }
    }

    // §61.137：去掉全局 TopAppBar（竞品无全局标题栏，页头归各页：设置 52dip 行 /
    // 歌单·我的音乐 44dip 标题栏）；底栏竞品化（h7 底 + 10sp label + 品牌红选中）。
    // §61.321（R3）：曲库索引一次成型。此前迷你条三行各自 `firstOrNull { ... }`
    // 全表扫一遍（N 首 × 3 次 / 每次根重组），队列页 titles 也是每次重组重建
    // 一个 O(N) 的 Map —— 都随重组次数放大成 GC 压力。
    val tracksById = remember(trackList) { trackList.associateBy { it.id } }
    // 队列页 `QueueScreen` 的既有契约是 `Map<String, Track>`（键 = trackId.value）。
    val queueTitles = remember(trackList) { trackList.associate { it.id.value to it } }
    // §61.279（V0.4-06 飞牛化）：迷你条封面接 `Track.coverRef` 真实封面，
    // 缺失时 `FnCover` 落占位底 + 音符字形（不再用竞品旋转盘位图）。
    val miniCoverRef = player.currentTrackId?.let { id -> tracksById[id]?.coverRef }
    // §61.150：迷你条标题接 Track.title 真曲名（§61.141 观察项——此前恒显示
    // raw currentTrackId.value，竞品显示曲名）；曲库缺失时回退 trackId（行为不变）。
    val miniTitle = player.currentTrackId?.let { id -> tracksById[id]?.title }
    // §61.279：副行接 `TrackInfo.listSubtitle`（艺人 · 专辑，已知字段才拼；空则不渲染）。
    val miniSubtitle = player.currentTrackId?.let { id -> tracksById[id] }
        ?.let { TrackInfo.listSubtitle(it) }
    // §61.280（V0.4-07 ①）：次级页统一出口——有来源就回来源，没有（深链/冷启）才回「更多」。
    fun exitSubPage() {
        if (subOrigin >= 0) tab = subOrigin else showMore = true
    }

    // §61.280（V0.4-07 ①，PRD §7.2 返回栈契约 / 03-10 §9 B16）：
    // 逐层退出——播放页覆盖层 → 「更多」面板 → 次级页（回进入方）→ 一级 tab（首页/音乐库）。
    // 一级 tab 上**不拦截**，交给系统退出 App；弹层（ModalBottomSheet）自带返回关闭，无需在此处理。
    BackHandler(
        enabled = showPlayer || showSources || showMore || tab in 2..6,
    ) {
        when {
            showPlayer -> showPlayer = false
            showSources -> showSources = false
            showMore -> showMore = false
            // 搜索页的「返回」= 清词 + 回进入方（与页内「取消」同一语义，§61.187）。
            tab == 3 -> {
                query = ""
                if (searchReturnTo >= 0) tab = searchReturnTo else showMore = true
            }
            else -> exitSubPage()
        }
    }

    Scaffold(
        // §61.277（V0.4-04 ④）：全局底色切飞牛页底；Comp 令牌自此收敛为播放页专用
        // （播放页覆盖层自带 LegacyPalette 背景，不受本行影响）。
        containerColor = FnComp.PageBg,
        bottomBar = {
            Column(
                // §61.325（A-1）：键盘弹起时**整条底栏上移到键盘上沿**。
                // 病根：Scaffold 的 bottomBar 贴着窗口底边排版，而 IME 在本工程的窗口模式下
                // 只以 inset 下发、窗口高度不变（SearchScreen 的 imePadding 能生效即证据）——
                // 于是「内容被抬到键盘上方、底栏却原地不动被整块盖住」，迷你条与胶囊栏
                // 只剩半截甚至全无，用户看到的是「输入时导航消失」。
                // 修法：把 IME 让位从各页面收口到底栏一处 —— 底栏抬多少，Scaffold 就按
                // 实际高度喂给内容 padding，两者永远同步，不会出现「内容抬了、栏没抬」。
                // 减掉 navigationBars 是因为 FnCapsuleBar 自己带 navigationBarsPadding()：
                // ime inset 已含导航栏高度，不减会多留一条导航栏高的空档；键盘弹起时
                // 三键导航若被 IME 顶掉，navigationBars 归零，排除项自然失效（自适应）。
                // 键盘收起时 ime=0 ⇒ 本行零高度，正常态像素分毫不动。
                Modifier.windowInsetsPadding(
                    WindowInsets.ime.exclude(WindowInsets.navigationBars),
                ),
            ) {
                // §61.196（W13）：设置页不显示播放条（基准 161550 无播放条，仅底部胶囊）。
                // §61.279（V0.4-06）：**无当前曲目时不显示空条**——冷启动只有一条
                // 「未在播放」+ 一个按了没反应的播放键，是死控件；对应 03-10 §9 B03。
                if (tab != 6 && player.currentTrackId != null) {
                    MiniPlayer(
                        player = player,
                        onPlay = onPlay,
                        onPause = onPause,
                        coverRef = miniCoverRef,
                        title = miniTitle,
                        subtitle = miniSubtitle,
                        onOpen = { showPlayer = true },
                        onOpenQueue = { showMore = false; tab = 4 },
                    )
                }
                // §61.222（用户指示）：底栏加搜索 tab——四胶囊（首页/音乐库/搜索/更多）。
                // §61.187/§61.195 的「搜索页隐藏胶囊栏」口径随本次调整作废：搜索成为
                // 一等 tab 后保持底栏可见（选中态可见性优先）。
                // §61.168 内部 tab 索引 0~7 保持不变；「更多」面板承载歌单/队列/我的/设置，
                // 功能不丢。无返回箭头/无账号入口（辨别铁律）。
                // §61.191（W4）：仅图标化——白色图标 + 选中红圆底白图标，去文字标签。
                // §61.330（A-4）：图标集合与画法都收口了——`CapsuleTabs` 是唯一列表，
                // 四枚字形全部来自 `FnIcon`（线性、同视框、同笔画），此处不再自绘。
                FnCapsuleBar(
                    icons = CapsuleTabs,
                    selected = when {
                        showSources -> -1
                        showMore -> 3
                        tab == 0 -> 0
                        tab == 1 -> 1
                        tab == 3 -> 2
                        else -> 3
                    },
                    onSelect = { i ->
                        showSources = false
                        showMore = false
                        when (i) {
                            0, 1 -> tab = i
                            2 -> {
                                if (tab != 3) searchReturnTo = tab
                                tab = 3
                            }
                            3 -> showMore = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                // §61.262：竖屏挖孔在状态栏内，普通页只补左右安全区，避免与 statusBarsPadding 叠加顶移。
                .displayCutoutPadding(),
        ) {
            // §61.188：移除自造的「曲库就绪/扫描中」横幅——飞牛基准首页无此物；
            // 扫描失败可见性已由数据源错误态承载（§61.184 setError 接线）。
            // §61.292：按页 key 建立独立 saveable 槽。下方 when 保持原缩进——整块重排会让本批
            // diff 失去可审性，语义与缩进无关。
            pageStateHolder.SaveableStateProvider(pageStateKey(showSources, showMore, tab)) {
            when {
                showSources -> SourcesScreen(
                    storageList, onRescanAll, onRetryUnavailable, onRefreshStorage,
                    mergeDuplicates, onMergeDuplicatesChanged,
                    onAddSaf, onAddMediaStore,
                    onAddWebDav,
                    onAddStrm,
                    onAddSftp,
                    onAddFtp,
                    onRemoveStorage,
                    onBack = { showSources = false },
                )
                showMore -> MoreScreen(
                    onPlaylists = { showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 2 },
                    onSearch = { showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 3 },
                    onQueue = { showMore = false; subOrigin = -1; tab = 4 },
                    onMine = { showMore = false; mineFocusRecent = false; subOrigin = -1; tab = 5 },
                    onSettings = { showMore = false; subOrigin = -1; tab = 6 },
                    onSources = { showMore = false; subOrigin = -1; showSources = true },
                    onDismiss = { showMore = false },
                    entryLabels = FUNCTION_PAGE_LABELS,
                )
                tab == 0 -> HomeScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onOpenSources = { subOrigin = 0; showSources = true },
                    onOpenMine = { recent ->
                        mineFocusRecent = recent
                        subOrigin = 0
                        tab = 5
                    },
                    favoriteCount = homeFavorites.size,
                    recentCount = homeHistoryCount,
                )
                // §61.274（V0.4-01）：曲库唯一入口——原 tab7 全功能（视图组 / ＋入歌单 /
                // 不可用重试 / 重扫）已并入本页，不再有第二个曲库入口。
                tab == 1 -> FnLibraryScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onAddToPlaylist = { addToPlaylistTrack = it },
                    onRescan = onRescanAll,
                    // §61.285（U-13 ②）：扫描进行中 → 重扫钮进度环 + 禁用。
                    scanning = runtime.scanning,
                    onRetryUnavailable = onRetryUnavailable,
                    onOpenSearch = { searchReturnTo = 1; subOrigin = 1; tab = 3 },
                    onOpenPlaylists = { subOrigin = 1; tab = 2 },
                    onOpenFavorites = { mineFocusRecent = false; subOrigin = 1; tab = 5 },
                    onOpenHistory = { mineFocusRecent = true; subOrigin = 1; tab = 5 },
                    onOpenSources = { subOrigin = 1; showSources = true },
                    // §61.327（A-7）：当前播放曲目 —— 曲库行的「正在播放」标识。
                    nowPlayingId = player.currentTrackId,
                )
                tab == 2 -> PlaylistsScreen(
                    playlistList,
                    onCreate = bridge::onCreatePlaylist,
                    onPlay = bridge::onPlayPlaylist,
                    onDelete = bridge::onDeletePlaylist,
                    onRename = bridge::onRenamePlaylist,
                    onMoveItem = bridge::onMovePlaylistItem,
                    onRemoveItem = bridge::onRemovePlaylistItem,
                    observeTracks = bridge::observePlaylistTracks,
                    // V0.2 §61.220：智能歌单——全量曲库实时求值 + 命中曲目直接播放
                    allTracks = trackList,
                    onPlaySmart = { hits -> onPlayTracks(hits, 0) },
                    nowPlayingId = player.currentTrackId,
                    // §61.192：次级页返回（飞牛「←」口径），回「更多」
                    onBack = { exitSubPage() },
                )
                tab == 3 -> SearchScreen(
                    query = query,
                    onQuery = { query = it },
                    // §61.328（B-1）：热词是「点一下就要出结果」的动作，绕开防抖直接落值。
                    onPickHotWord = { word -> query = word; debouncedQuery = word },
                    results = searchResults,
                    // 飞牛热词 = 本地曲库高频艺人 / 专辑（spec §3.3：不造在线热搜）
                    hotWords = remember(trackList) { SearchDiscovery.hotWords(trackList) },
                    onPlayTracks = onPlayTracks,
                    nowPlayingId = player.currentTrackId,
                    // §61.187：「取消」= 退出搜索页回进入方（旧实现只清关键字不退出）
                    onClose = {
                        query = ""
                        if (searchReturnTo >= 0) tab = searchReturnTo else showMore = true
                    },
                )
                tab == 4 -> QueueScreen(
                    queueList,
                    // §61.321：与迷你条共用一次性索引，不再每次重组重建 Map。
                    titles = queueTitles,
                    onJump = bridge::onQueueJump,
                    nowPlayingId = player.currentTrackId,
                    // §61.192：次级页返回
                    onBack = { exitSubPage() },
                )
                tab == 5 -> MyMusicScreen(
                    bridge = bridge,
                    allTracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onBack = { exitSubPage() },
                    focusRecent = mineFocusRecent,
                    nowPlayingId = player.currentTrackId,
                )
                else -> SettingsScreen(
                    runtime, appSettings.network.wifiOnly, onRescanAll,
                    { subOrigin = 6; showSources = true },
                    // §61.29 / 03-11 §2：打开输出弹窗时按动作申请蓝牙权限显示设备名；
                    // 弹窗本身不等待授权结果（B01 拒绝不阻断）。
                    { showOutput = true; onOpenOutput() },
                    onWifiOnlyChanged,
                    onClearCache,
                    onAnalyzeReplayGain,
                    onScrapeCovers,
                    onFindDuplicates,
                    duplicateReport,
                    onClearDuplicates,
                    fadeEnabled,
                    onFadeEnabledChanged,
                    eqEnabled,
                    eqEffectLevel,
                    eqBandsMillibel,
                    onApplyEqualizer,
                    themeMode,
                    onThemeChanged,
                    appSettings.scan.minDurationMs,
                    onScanMinDurationChanged,
                    appSettings.scan.ignoreDirs,
                    onScanIgnoreDirsChanged,
                    trackCount = trackList.size,
                    sourceCount = storageList.size,
                    // §61.211（G09 拍板 A）：MIUI 自启动引导。
                    onOpenAutostartSettings = onOpenAutostartSettings,
                    onBack = { exitSubPage() },
                )
            }
            }
        }
    }

    if (showOutput) {
        val dmrDevices by dlnaDevices.collectAsState()
        val castVolumeState by castVolume.collectAsState()
        val lastUsn by lastDmrUsn.collectAsState()
        OutputDialog(
            state = output,
            devices = dmrDevices,
            lastDmrUsn = lastUsn,
            castVolume = castVolumeState,
            onCastVolumeChanged = onCastVolumeChanged,
            onOutputSelected = onOutputSelected,
            onPickDmr = { device -> onPickDmr(device); showOutput = false },
            onScanDlna = onScanDlna,
            onOpenManualDmr = {
                onClearManualDmrResult()
                showOutput = false
                showManualDmr = true
            },
        ) { showOutput = false }
    }
    if (showManualDmr) {
        val probeResult by manualDmrResult.collectAsState()
        ManualDmrDialog(
            result = probeResult,
            onProbe = onProbeManualDmr,
            onPick = { device -> onPickDmr(device); showManualDmr = false },
        ) { showManualDmr = false }
    }

    // §61.119（M3）：加入歌单弹窗——曲库行 "+" 触发；支持新建内联（b5y 面板样式）。
    addToPlaylistTrack?.let { target ->
        AddToPlaylistSheet(
            trackTitle = target.title,
            playlists = playlistList,
            onPick = { playlistId ->
                // §61.294：面板立即关闭，补一句「已加入」；名字查不到时用中性词兜底。
                val playlistName = playlistList.firstOrNull { it.id == playlistId }?.name.orEmpty()
                bridge.onAddToPlaylist(playlistId, target.id)
                addToPlaylistTrack = null
                feedbackContext.showFeedback(FeedbackText.addedToPlaylist(playlistName))
            },
            onCreateAndAdd = { name ->
                bridge.onCreatePlaylist(name) { id -> bridge.onAddToPlaylist(id, target.id) }
                addToPlaylistTrack = null
                // §61.294：新建与加入是两次动作，合成一句，避免两条 Toast 打架。
                feedbackContext.showFeedback(FeedbackText.createdWithTrack(name))
            },
            onDismiss = { addToPlaylistTrack = null },
        )
    }

    // 12 §61.40：整页播放页覆盖在 Scaffold 之上（局部混用两套 UI 框架，限制在播放页一屏）。
    if (showPlayer) {
        LegacyPlayerPage(
            bridge = bridge,
            onClose = { showPlayer = false },
            // §61.192（W2）：副控制行「更多」位 = 输出/DLNA 入口（权限口径同设置页：
            // SHOW_BLUETOOTH_NAME 按动作申请，拒绝不阻断——设备名不显示仍可投屏）。
            onOpenOutput = {
                showOutput = true
                onOpenOutput()
            },
        )
    }
}

/**
 * 迷你播放条（**§61.279 / V0.4-06 飞牛化改版，拍板 §61.273 #1A**）。
 *
 * 改版口径：竞品 `legacy_e0g.xml` 的「#33333e 深底 + 37dip 进度圆环 + 旋转封面 + 红点 +
 * 14sp 原生 MARQUEE 跑马灯（drawableStart 双音符）+ ☰」整组退役，换成飞牛卡片条——
 * `FnComp.CardBg` 16dp 圆角条 + 40dp 圆角封面 + 曲名/艺人两行 + 播放键 + 队列入口，
 * 与音乐库/设置页同一套卡片语言（页面风格连续，不留「播放器贴纸」感）。
 *
 * 三处旧实现是**自研偏差或垫脚石**，一并删除：
 * 1. **跑马灯**：`TextView` + `MARQUEE` + `isSelected=true` 会在每秒 position tick 的
 *    recomposition 里持续滚动，既耗电又和飞牛「静态两行」不一致；改 `Text` 单行省略。
 * 2. **双音符 drawableStart**：`f6m` 音符位图与标题之间没有语义关联（竞品是装饰），
 *    改由封面 + 两行文字承担信息层级。
 * 3. **旋转封面 + 进度圆环 + 红点**：圆环无生产数据源（`12 §61.42` 挂账），红点是竞品
 *    「正在播放」标记；飞牛口径用「播放键形态」表达播放态（▶ / ⏸ 切换），不再画假进度。
 *
 * **不伪造**：进度弧仍未接数据源，本版直接不画（比画一条恒 0 的假弧诚实）。
 */
@Composable
private fun MiniPlayer(
    player: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    coverRef: String?,
    title: String?,
    /** 副行：`TrackInfo.listSubtitle`（艺人 · 专辑，已知字段才拼）；空则整行不渲染。 */
    subtitle: String?,
    onOpen: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val playing = player.phase == PlayerPhase.PLAYING
    val shownTitle = title ?: player.currentTrackId?.value ?: "未在播放"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 16dp = 全站卡片页边距（`FnBaseline.PagePaddingDp`），与上方曲库卡片对齐；
            // 上下 6dp 让条与胶囊底栏之间留出一条呼吸缝，不糊成一块。
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(FnComp.CardBg)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FnCover(coverRef, Modifier.size(40.dp), glyph = "♪")
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 4.dp),
        ) {
            Text(
                shownTitle,
                color = FnComp.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // §61.275 显示契约：副题为空（无艺人也无专辑）时整行不渲染，不留空段。
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = FnComp.TextHint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MiniBarButton(
            icon = if (playing) FnIcon.Pause else FnIcon.Play,
            description = if (playing) "暂停" else "播放",
            onClick = { if (playing) onPause() else onPlay() },
        )
        MiniBarButton(FnIcon.Queue, "播放列表", onOpenQueue)
    }
}

/** 迷你条圆钮：40dp 触达区 + 20dp 线性图标（图标取自 `FnIcons` 唯一真相源）。 */
@Composable
private fun MiniBarButton(icon: FnIcon, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            // §61.290：onClickLabel 只是「双击以…」的动作提示，不是控件名；
            // 图标画在 Canvas 上不产生语义节点，所以必须显式挂描述。
            .semantics { contentDescription = description }
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FnLineIcon(icon, FnComp.TextPrimary, size = 20.dp)
    }
}

/**
 * §61.276（V0.4-03 灰显契约，拍板 #3A / 03-10 §9 B09）：
 * `availability != OK` 的曲目行 = 整行降透明 + 「暂不可用」角标，**仍可见 / 可搜索 /
 * 可保留在歌单与队列**；点击只给原因（原生 Toast），不发起播放、不弹网络错误。
 */
internal fun unavailableBadge(track: Track): String? =
    if (track.availability != Availability.OK) "暂不可用" else null

/** 可用则执行动作；不可用只提示原因（`V0.4-03`）。 */
internal fun playOrExplain(
    context: android.content.Context,
    track: Track,
    action: () -> Unit,
) {
    if (track.availability == Availability.OK) {
        action()
    } else {
        context.showFeedback(TrackInfo.availabilityLabel(track))
    }
}

/** 带不可用拦截的飞牛曲目行（搜索结果等单行场景复用）。 */
@Composable
private fun TrackRowGuarded(track: Track, playing: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    FnTrackRow(
        title = track.title,
        subtitle = TrackInfo.listSubtitle(track),
        coverRef = track.coverRef,
        dimmed = track.availability != Availability.OK,
        badge = unavailableBadge(track),
        playing = playing,
        onClick = { playOrExplain(context, track, onClick) },
    )
}

/** §61.137 搜索页（asc 搜索框 + asa 结果 + cvq chip 样式预留）：取消清词、框内 ✕。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Track>,
    hotWords: List<String>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    /**
     * §61.328（B-1）：热词点击的落值通道——**绕开 300ms 防抖**。热词是「点一下就要结果」，
     * 走防抖会先卡半拍；输入框打字才需要静默合并。默认回落 [onQuery]（不接线也不炸）。
     */
    onPickHotWord: (String) -> Unit = onQuery,
    /** §61.327（A-7）：当前播放曲目 id。 */
    nowPlayingId: TrackId? = null,
    /** §61.187：「取消」= 退出搜索页（回进入方）；旧实现只清关键字 ⇒ 无法返回。 */
    onClose: () -> Unit = {},
) {
    // §61.325（A-1）：本页**不再**自己吃 ime inset。键盘让位已收口到 Scaffold 的 bottomBar
    // （整条底栏上移，Scaffold 用「底栏实际高度」喂内容 padding）。两处都加会叠出
    // 一个键盘高的空白 —— 那正是上一版「结果列表悬在键盘上方一大截」的成因。
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // ── 顶栏：搜索框 + 取消（飞牛 161534 逐像素；**无返回箭头**——辨别铁律） ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = FnBaseline.SearchBoxLeftDp.dp,
                    end = FnBaseline.CancelRightDp.dp,
                    top = FnBaseline.SearchTopGapDp.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FnSearchField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                // §61.293：只在「进来那一刻关键字为空」时抢焦点——搜完点结果去播放再返回，
                // 不该又弹一次键盘盖住半屏结果（判定是纯函数，见 InputPolicy）。
                autoFocus = remember { shouldAutoFocusSearch(query) },
            )
            Text(
                "取消",
                color = FnComp.TextSecondary,
                fontSize = FnBaseline.CancelSp.sp,
                modifier = Modifier
                    .padding(start = FnBaseline.SearchToCancelDp.dp)
                    // §61.187：取消 = 退出搜索页（清关键字由调用方处理）——
                    // 旧实现只清关键字，用户被困在搜索页（「返回也返回不了」）。
                    .clickable { onClose() },
            )
        }
        when {
            // 空曲库：不造热词，直接给空态
            query.isBlank() && hotWords.isEmpty() -> FnEmpty("输入关键字搜索本地曲库", Modifier.weight(1f))
            // 飞牛热词区接**本地**艺人 / 专辑名，不造在线热搜（spec §3.3 本地化口径）
            query.isBlank() -> Column(Modifier.fillMaxSize()) {
                FnSectionTitle("搜索发现 · 本地曲库")
                FlowRow(
                    Modifier.padding(horizontal = FnBaseline.PagePaddingDp.dp + 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    hotWords.forEach { word ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(FnComp.CardBg)
                                // §61.328：热词立刻查，不等防抖窗口
                                .clickable { onPickHotWord(word) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(word, color = FnComp.TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            results.isEmpty() -> FnEmpty("无匹配结果", Modifier.weight(1f))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, t -> t.id.value }) { index, track ->
                    // §61.175：修旧实现的死链——原 `onClick = {}` 点了没反应；
                    // 现在点结果即从该首起播（替换业务队列，口径同 §61.113 分组播放）。
                    // §61.276（V0.4-03）：不可用曲目灰显 + 点击原因（不报网络错误）
                    TrackRowGuarded(
                        track,
                        playing = track.id == nowPlayingId,
                    ) { onPlayTracks(results, index) }
                }
            }
        }
    }
}

/** §61.137 队列页（dialog_music_list/a6h 风格基准）：标题栏 + 序号行，点击跳播。 */
@Composable
private fun QueueScreen(
    queue: List<QueueItem>,
    titles: Map<String, Track>,
    onJump: (Int) -> Unit,
    /** §61.327（A-7）：当前播放曲目 id。 */
    nowPlayingId: TrackId? = null,
    /** §61.192：次级页返回（回「更多」）。 */
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("播放列表 · ${queue.size}", onBack = onBack)
        if (queue.isEmpty()) {
            FnEmpty("队列为空，播放后会列出接下来的曲目", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(queue, key = { "${it.queueId}-${it.position}" }) { item ->
                    val track = titles[item.trackId.value]
                    FnTrackRow(
                        title = track?.title ?: item.trackId.value,
                        // §61.275：已知字段才显示（含占位值过滤），此处只保留来源兜底。
                        subtitle = track?.let { TrackInfo.listSubtitle(it) }
                            .orEmpty().ifBlank { "来源：${item.sourceRef}" },
                        coverRef = track?.coverRef,
                        glyph = "${item.position + 1}",
                        dimmed = track != null && track.availability != Availability.OK,
                        badge = track?.let { unavailableBadge(it) },
                        // §61.327：曲库里查不到的行（track == null）不可能是在播曲目。
                        playing = track != null && track.id == nowPlayingId,
                        onClick = {
                            // §61.276：队列里的不可用曲目可保留，跳转前给原因
                            if (track != null) playOrExplain(context, track) { onJump(item.position) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * §61.130（M5 我的音乐）：收藏 + 最近播放两段（FavoriteRepo/HistoryRepo 全收口）。
 * §61.137 视觉：44dip 页头 + cvn 分组标题 + cvg 曲目行。
 * §61.277（V0.4-04）：页头/标题/行改飞牛骨架。
 */
@Composable
private fun MyMusicScreen(
    bridge: PlayerStateBridge,
    allTracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onBack: () -> Unit,
    /**
     * §61.280（V0.4-07 ③）：入口语义分流——从「最近」卡 / 音乐库「最近」进来时
     * 自动滚到最近播放段；从「收藏」卡 / 更多页「我的音乐」进来则停在顶部（收藏段）。
     */
    focusRecent: Boolean = false,
    /** §61.327（A-7）：当前播放曲目 id（收藏 / 最近播放两段共用）。 */
    nowPlayingId: TrackId? = null,
) {
    val context = LocalContext.current
    val favorites by bridge.observeFavorites().collectAsState(initial = emptySet())
    // §61.328（B-1）：列表只取最近窗口（LazyColumn 一屏十几行），总数单独订阅。
    val history by bridge.observeHistory().collectAsState(initial = emptyList())
    val historyCount by bridge.observeHistoryCount().collectAsState(initial = 0)
    // §61.289：收藏过滤记忆化——原写法在 composable 体内，**每次重组都全量扫一遍曲库**
    // （7,500 首 × 每次播放状态刷新）。只在曲库或收藏变化时重算。
    val favTracks = remember(allTracks, favorites) {
        allTracks.filter { it.id in favorites }
    }
    // §61.284（B07 收口）：收藏/历史此前是 Column + verticalScroll + forEach，**离屏行也
    // 全部组合**；而播放历史在库里没有上限（HistoryDao.observe 无 LIMIT、也无淘汰），
    // 用过几个月就是几千行常驻节点。改 LazyColumn：行序与卡片外观不变，只把 item 发懒。
    val listState = rememberLazyListState()
    // 「最近」入口进来要滚到最近播放段。懒列表下无法量未组合项的 y 坐标，
    // 只能按 item 序号定位；序号算法抽在 [MyMusicLayout]，单测与 UI 同源。
    val historyHeaderIndex = MyMusicLayout.historyHeaderIndex(
        favoriteCount = favTracks.size,
        hasHistory = history.isNotEmpty(),
    )
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("我的音乐", onBack = onBack)
        // §61.280（V0.4-07 ③）语义不变：从「最近」卡 / 音乐库「最近」进来才滚，
        // 从「收藏」卡 / 更多页进来停在顶部（收藏段）。
        LaunchedEffect(focusRecent, historyHeaderIndex) {
            if (focusRecent && historyHeaderIndex > 0) {
                listState.animateScrollToItem(historyHeaderIndex)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "mm-stat") {
                Column {
                    Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
                    FnStatCard(
                        icon = FnIcon.Favorite,
                        title = "我的音乐",
                        primary = "${favTracks.size} 收藏",
                        secondary = "最近播放 $historyCount 首 · 全部来自本地曲库",
                        accent = FnComp.IconRed,
                    )
                    Spacer(Modifier.height(FnBaseline.StatToNextGroupDp.dp))
                }
            }
            if (favTracks.isEmpty() && history.isEmpty()) {
                item(key = "mm-empty") {
                    FnEmpty("收藏曲目或播放曲目后在此显示", Modifier.height(220.dp))
                }
            }
            if (favTracks.isNotEmpty()) {
                item(key = "mm-fav-title") { FnSectionTitle("收藏 · ${favTracks.size}") }
                itemsIndexed(favTracks, key = { _, t -> "fav-" + t.id.value }) { index, track ->
                    FnTrackRow(
                        title = track.title,
                        subtitle = TrackInfo.listSubtitle(track),
                        coverRef = track.coverRef,
                        glyph = "♥",
                        dimmed = track.availability != Availability.OK,
                        badge = unavailableBadge(track),
                        playing = track.id == nowPlayingId,
                        modifier = Modifier.fnGroupCardRow(index, favTracks.size),
                        onClick = { playOrExplain(context, track) { onPlayTracks(favTracks, index) } },
                    )
                }
                item(key = "mm-fav-gap") { Spacer(Modifier.height(FnBaseline.GroupGapDp.dp)) }
            }
            if (history.isNotEmpty()) {
                // §61.328：小节标题用**总数**，与上方统计卡同口径（窗口截断不谎报）。
                item(key = "mm-hist-title") { FnSectionTitle("最近播放 · $historyCount") }
                itemsIndexed(history, key = { _, t -> "hist-" + t.id.value }) { index, track ->
                    FnTrackRow(
                        title = track.title,
                        subtitle = TrackInfo.listSubtitle(track),
                        coverRef = track.coverRef,
                        glyph = "↺",
                        dimmed = track.availability != Availability.OK,
                        badge = unavailableBadge(track),
                        playing = track.id == nowPlayingId,
                        modifier = Modifier.fnGroupCardRow(index, history.size),
                        onClick = { playOrExplain(context, track) { onPlayTracks(history, index) } },
                    )
                }
            }
            item(key = "mm-tail") { Spacer(Modifier.height(28.dp)) }
        }
    }
}
