package com.example.cloudmusic.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cloudmusic.ui.player.loadLegacyTypeface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.flow.Flow
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
 * 飞牛搜索页「搜索发现」热词 —— 取**本地曲库**的高频艺人 / 专辑（spec §3.3 本地化口径）。
 *
 * 不造在线热搜：这是离线优先 App，热词必须来自用户自己的曲库；空曲库返回空 →
 * 搜索页直接给空态，不虚构任何词条。
 */
private fun localHotWords(tracks: List<Track>, limit: Int = 10): List<String> {
    if (tracks.isEmpty()) return emptyList()
    // 占位符不算热词：「未知」/「<unknown>」/「unknown」都是标签缺失时的兜底值，
    // 拿来当搜索发现会把噪声推给用户（§61.175 真机截图踩到）。
    val placeholders = setOf("未知", "未知艺人", "未知专辑", "未知歌手", "unknown", "<unknown>")
    fun top(counts: Map<String, Int>, n: Int): List<String> =
        counts.entries
            .filter {
                it.key.isNotBlank() &&
                    !it.key.startsWith("<") &&
                    it.key.lowercase() !in placeholders
            }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }

    val half = limit / 2
    return (top(tracks.groupingBy { it.artist }.eachCount(), half) +
        top(tracks.groupingBy { it.album }.eachCount(), half)).distinct()
}

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
    var query by rememberSaveable { mutableStateOf("") }
    // §61.187：搜索页返回目标（-1 = 从「更多」进入 → 返回更多；≥0 = 从该 tab 进入）。
    // 旧实现「取消」只清关键字不退出页面 ⇒ 用户「返回也返回不了」。
    var searchReturnTo by rememberSaveable { mutableIntStateOf(-1) }
    // §61.192 勘误（用户「进了设置页为什么底部TAB没了」）：§61.187 把底栏隐藏扩大到全部
    // 次级页是**过度隐藏**——修正后设置页恢复显示底栏。
    // §61.222（用户指示）：搜索升为一等 tab（底栏四胶囊），「仅搜索页隐藏底栏」口径
    // 随之作废（capsuleHidden 判定删除）——搜索页保持底栏可见，选中态可见性优先。
    // §61.119（M3）：加入歌单弹窗目标曲目。
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

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
    val homeHistory by bridge.observeHistory().collectAsState(initial = emptyList())
    val resultsFlow = remember(query) { search(query) }
    val searchResults by resultsFlow.collectAsState(initial = emptyList())

    // §61.187（W7 全屏沉浸）/ §61.270（用户 2026-11-21 口径）：
    // 顶部全面屏——状态栏透明 + 内容延伸到状态栏后面（竞品真机：时间/信号叠在深灰页面上，
    // 无黑块）。实测 MIUI 下 hide(statusBars()) 不生效（状态栏仍在），故改用透明状态栏方案：
    //   setDecorFitsSystemWindows(false) + statusBarColor 透明 → 页面渐变背景顶到屏幕上沿。
    // 底部保留系统操作栏——导航栏不清透明、不隐藏（用户明确要求，竞品亦保留三键导航）。
    // 关闭播放页时成对还原——DisposableEffect 退出重组即恢复，其它飞牛页面零影响。
    val view = LocalView.current
    if (showPlayer) {
        DisposableEffect(Unit) {
            val activity = view.context as? android.app.Activity
            val window = activity?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, view) }
            val origStatusBarColor = window?.statusBarColor
            // theme optical：windowOptOutEdgeToEdgeEnforcement=true 时 decorView fits=true。
            // 用 ViewCompat 读原值，退出播放页时精确还原（避免硬编码假设）。
            val origDecorFits: Boolean = window?.decorView?.let {
                androidx.core.view.ViewCompat.getFitsSystemWindows(it)
            } ?: true
            if (window != null) {
                // 内容延伸到状态栏后，透明状态栏让页面渐变顶上去（消除黑块）
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                // 仅状态栏图标按页面深色背景反相；导航栏保持系统默认（保留三键）
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.show(WindowInsetsCompat.Type.navigationBars())
            }
            onDispose {
                if (window != null) {
                    origStatusBarColor?.let { window.statusBarColor = it }
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, origDecorFits)
                }
            }
        }
    }

    // §61.137：去掉全局 TopAppBar（竞品无全局标题栏，页头归各页：设置 52dip 行 /
    // 歌单·我的音乐 44dip 标题栏）；底栏竞品化（h7 底 + 10sp label + 品牌红选中）。
    // §61.279（V0.4-06 飞牛化）：迷你条封面接 `Track.coverRef` 真实封面，
    // 缺失时 `FnCover` 落占位底 + 音符字形（不再用竞品旋转盘位图）。
    val miniCoverRef = player.currentTrackId?.let { id ->
        trackList.firstOrNull { it.id.value == id.value }?.coverRef
    }
    // §61.150：迷你条标题接 Track.title 真曲名（§61.141 观察项——此前恒显示
    // raw currentTrackId.value，竞品显示曲名）；曲库缺失时回退 trackId（行为不变）。
    val miniTitle = player.currentTrackId?.let { id ->
        trackList.firstOrNull { it.id.value == id.value }?.title
    }
    // §61.279：副行接 `TrackInfo.listSubtitle`（艺人 · 专辑，已知字段才拼；空则不渲染）。
    val miniSubtitle = player.currentTrackId?.let { id ->
        trackList.firstOrNull { it.id.value == id.value }
    }?.let { TrackInfo.listSubtitle(it) }
    Scaffold(
        // §61.277（V0.4-04 ④）：全局底色切飞牛页底；Comp 令牌自此收敛为播放页专用
        // （播放页覆盖层自带 LegacyPalette 背景，不受本行影响）。
        containerColor = FnComp.PageBg,
        bottomBar = {
            Column {
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
                FnCapsuleBar(
                    icons = listOf(
                        FnCapsuleIcon.Home, FnCapsuleIcon.Music, FnCapsuleIcon.Search, FnCapsuleIcon.More,
                    ),
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
                    onPlaylists = { showMore = false; searchReturnTo = -1; tab = 2 },
                    onSearch = { showMore = false; searchReturnTo = -1; tab = 3 },
                    onQueue = { showMore = false; tab = 4 },
                    onMine = { showMore = false; tab = 5 },
                    onSettings = { showMore = false; tab = 6 },
                    onSources = { showMore = false; showSources = true },
                    onDismiss = { showMore = false },
                    entryLabels = FUNCTION_PAGE_LABELS,
                )
                tab == 0 -> HomeScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onOpenSources = { showSources = true },
                    onOpenMine = { tab = 5 },
                    favoriteCount = homeFavorites.size,
                    recentCount = homeHistory.size,
                )
                // §61.274（V0.4-01）：曲库唯一入口——原 tab7 全功能（视图组 / ＋入歌单 /
                // 不可用重试 / 重扫）已并入本页，不再有第二个曲库入口。
                tab == 1 -> FnLibraryScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onAddToPlaylist = { addToPlaylistTrack = it },
                    onRescan = onRescanAll,
                    onRetryUnavailable = onRetryUnavailable,
                    onOpenSearch = { searchReturnTo = 1; tab = 3 },
                    onOpenPlaylists = { tab = 2 },
                    onOpenFavorites = { tab = 5 },
                    onOpenHistory = { tab = 5 },
                    onOpenSources = { showSources = true },
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
                    // §61.192：次级页返回（飞牛「←」口径），回「更多」
                    onBack = { showMore = true },
                )
                tab == 3 -> SearchScreen(
                    query = query,
                    onQuery = { query = it },
                    results = searchResults,
                    // 飞牛热词 = 本地曲库高频艺人 / 专辑（spec §3.3：不造在线热搜）
                    hotWords = remember(trackList) { localHotWords(trackList) },
                    onPlayTracks = onPlayTracks,
                    // §61.187：「取消」= 退出搜索页回进入方（旧实现只清关键字不退出）
                    onClose = {
                        query = ""
                        if (searchReturnTo >= 0) tab = searchReturnTo else showMore = true
                    },
                )
                tab == 4 -> QueueScreen(
                    queueList,
                    titles = trackList.associate { it.id.value to it },
                    onJump = bridge::onQueueJump,
                    // §61.192：次级页返回
                    onBack = { showMore = true },
                )
                tab == 5 -> MyMusicScreen(
                    bridge = bridge,
                    allTracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onBack = { showMore = true },
                )
                else -> SettingsScreen(
                    runtime, appSettings.network.wifiOnly, onRescanAll,
                    { showSources = true },
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
                    onBack = { showMore = true },
                )
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
                bridge.onAddToPlaylist(playlistId, target.id)
                addToPlaylistTrack = null
            },
            onCreateAndAdd = { name ->
                bridge.onCreatePlaylist(name) { id -> bridge.onAddToPlaylist(id, target.id) }
                addToPlaylistTrack = null
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
private fun unavailableBadge(track: Track): String? =
    if (track.availability != Availability.OK) "暂不可用" else null

/** 可用则执行动作；不可用只提示原因（`V0.4-03`）。 */
private fun playOrExplain(
    context: android.content.Context,
    track: Track,
    action: () -> Unit,
) {
    if (track.availability == Availability.OK) {
        action()
    } else {
        Toast.makeText(context, TrackInfo.availabilityLabel(track), Toast.LENGTH_SHORT).show()
    }
}

/** 带不可用拦截的飞牛曲目行（搜索结果等单行场景复用）。 */
@Composable
private fun TrackRowGuarded(track: Track, onClick: () -> Unit) {
    val context = LocalContext.current
    FnTrackRow(
        title = track.title,
        subtitle = TrackInfo.listSubtitle(track),
        coverRef = track.coverRef,
        dimmed = track.availability != Availability.OK,
        badge = unavailableBadge(track),
        onClick = { playOrExplain(context, track, onClick) },
    )
}

/** §61.137 数据源管理（`main_account` 行样式）：添加来源 / 维护 / 已添加 三组。 */
@Composable
private fun SourcesScreen(
    storages: List<Storage>,
    onRescan: () -> Unit,
    onRetry: () -> Unit,
    onRefreshStorage: (String) -> Unit,
    mergeDuplicates: Boolean,
    onMergeDuplicatesChanged: (Boolean) -> Unit,
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
    onAddWebDav: (String, String, String, String) -> Unit,
    onAddStrm: (String, String) -> Unit,
    onAddSftp: (String, Int, String, String, String, String, String) -> Unit,
    onAddFtp: (String, Int, String, String, String, Boolean) -> Unit,
    onRemoveStorage: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showWebDav by remember { mutableStateOf(false) }
    var showStrm by remember { mutableStateOf(false) }
    var showSftp by remember { mutableStateOf(false) }
    var showFtp by remember { mutableStateOf(false) }
    // §61.207：行点击 → 源操作面板（刷新此源 / 移除）——§61.152 的移除确认扩为双动作，
    // 「刷新此源」复用定向增量扫（§61.206/§61.204 同链）。
    var actionTarget by remember { mutableStateOf<Storage?>(null) }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("音乐来源", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item { FnSectionTitle("添加音乐来源") }
            item {
                FnGroupCard {
                    FnRow(FnIcon.Add, "本地目录（SAF）", onAddSaf, value = "无需读取权限")
                    FnRow(FnIcon.Add, "从媒体库导入", onAddMediaStore, value = "按动作申请权限", dividerAbove = true)
                    FnRow(FnIcon.Add, "添加 WebDAV 源", { showWebDav = true }, value = "地址 + 认证", dividerAbove = true)
                    // §61.151：STRM 独立源（复用已添加的来源挂载 .strm 挂载点）。
                    FnRow(FnIcon.Add, "添加 STRM 源", { showStrm = true }, value = "复用已有来源 + .strm", dividerAbove = true)
                    // V0.2 §61.216：SFTP 数据源（凭据只在内存 Addition，不写日志）。
                    FnRow(FnIcon.Add, "添加 SFTP 源", { showSftp = true }, value = "SSH 文件传输", dividerAbove = true)
                    FnRow(FnIcon.Add, "添加 FTP 源", { showFtp = true }, value = "文件传输协议", dividerAbove = true)
                }
            }
            item { FnSectionTitle("维护") }
            item {
                FnGroupCard {
                    FnRow(FnIcon.Refresh, "全量重扫", onRescan, value = "找出新增与恢复的文件")
                    FnRow(FnIcon.Refresh, "重试失败项", onRetry, value = "只处理上次失败的", dividerAbove = true)
                    // §61.208（A4）：同 URL 双源折叠开关（scan.mergeDuplicates 消费方）。
                    FnRow(
                        FnIcon.Duplicate, "合并重复项",
                        { onMergeDuplicatesChanged(!mergeDuplicates) },
                        value = if (mergeDuplicates) "同 URL 只显示一行" else "关闭",
                        showChevron = false,
                        dividerAbove = true,
                        trailing = {
                            FnSwitch(checked = mergeDuplicates, onChange = onMergeDuplicatesChanged)
                        },
                    )
                }
            }
            item { FnSectionTitle("已添加 · ${storages.size}") }
            if (storages.isEmpty()) {
                item {
                    Text(
                        "还没有音乐来源，点上方按钮添加本地目录或导入媒体库",
                        color = FnComp.TextHint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(storages, key = { it.id }) { storage ->
                    // §61.275：错误码不进用户文案（07 码表 → 人话映射）
                    val status = if (storage.isWork) {
                        "正常"
                    } else {
                        "不可用 · " + ErrorText.human(storage.errorMsg)
                    }
                    FnGroupCard(Modifier.padding(bottom = 10.dp)) {
                        FnRow(
                            FnIcon.Sources,
                            storage.remark.ifBlank { storage.mountPath },
                            // §61.207：点行 → 源操作面板（刷新此源 / 移除）。
                            { actionTarget = storage },
                            value = "${storage.driverType} · $status",
                            // §61.275（B06）：失败源在列表里也必须一眼可辨（灰显 + 角标）
                            trailing = if (storage.isWork) null else {
                                { FnUnavailableBadge("不可用") }
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showWebDav) {
        WebDavDialog(
            onDismiss = { showWebDav = false },
            onAdd = { url, user, pass, path ->
                onAddWebDav(url, user, pass, path)
                showWebDav = false
            },
        )
    }
    // §61.151：STRM 独立源对话框——委托源候选 = 已添加且非 strm 的存储
    // （strm 委托 strm 不禁止但无意义，不列）。
    if (showStrm) {
        StrmDialog(
            delegates = storages.filter { it.driverType != "strm" },
            onDismiss = { showStrm = false },
            onAdd = { delegateId, paths ->
                onAddStrm(delegateId, paths)
                showStrm = false
            },
        )
    }
    // V0.2 §61.216：SFTP 源输入面板（端口非法时本地拦截，不发起连接）。
    if (showSftp) {
        SftpDialog(
            onDismiss = { showSftp = false },
            onAdd = { host, port, user, pass, root, key, passphrase ->
                onAddSftp(host, port, user, pass, root, key, passphrase)
                showSftp = false
            },
        )
    }
    if (showFtp) {
        FtpDialog(
            onDismiss = { showFtp = false },
            onAdd = { host, port, user, pass, root, ssl ->
                onAddFtp(host, port, user, pass, root, ssl)
                showFtp = false
            },
        )
    }
    // §61.207：源操作面板（§61.152 移除确认扩为双动作）——「刷新此源」走定向增量扫。
    actionTarget?.let { target ->
        FnSheetDialog(
            onDismiss = { actionTarget = null },
            title = target.remark.ifBlank { target.mountPath },
            content = {
                Text(
                    "${target.driverType} · " + (
                        if (target.isWork) "正常" else "不可用 · " + ErrorText.human(target.errorMsg)
                        ) + "\n" +
                        "刷新只重扫该源（有变更的文件才重新处理）；移除标记曲目不可用，文件不删。",
                    color = FnComp.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            },
            actions = listOf(
                "刷新此源" to {
                    onRefreshStorage(target.id)
                    actionTarget = null
                },
                "移除" to {
                    onRemoveStorage(target.id)
                    actionTarget = null
                },
            ),
            destructiveIndexOf = 1,
        )
    }
}

/** §61.132/§61.137：WebDAV 源添加（b5y 面板 + 表单；字段与 WebDavDriver strCompat 对齐）。 */
@Composable
private fun WebDavDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, username: String, password: String, rootPath: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("/music") }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 WebDAV 源",
        content = {
            Column {
                FnDialogInput(url, { url = it }, hint = "服务器地址（含端口），如 https://nas:5006")
                FnDialogInput(username, { username = it }, hint = "用户名（可空=匿名）")
                FnDialogInput(password, { password = it }, hint = "密码（可空）", password = true)
                FnDialogInput(rootPath, { rootPath = it }, hint = "远程路径（默认 /music）")
            }
        },
        actions = listOf("添加" to {
            if (url.isNotBlank()) {
                onAdd(url.trim().trimEnd('/'), username.trim(), password, rootPath.trim())
            }
        }),
    )
}

/** V0.2 §61.216：SFTP 源输入面板（字段与 SftpConfig / 08 配置项对齐；凭据只进 Addition.raw）。 */
@Composable
private fun SftpDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, String, String, String, String, String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 SFTP 源",
        content = {
            Column {
                FnDialogInput(host, { host = it }, hint = "主机 IP / 域名，如 nas.local")
                FnDialogInput(port, { port = it }, hint = "SSH 端口（默认 22）")
                FnDialogInput(user, { user = it }, hint = "用户名")
                FnDialogInput(pass, { pass = it }, hint = "密码 / 口令", password = true)
                FnDialogInput(root, { root = it }, hint = "远程根目录（默认 /）")
                // §61.239：Ed25519 边界一手文案（§61.223 判读登记收口）——Android 无
                // Ed25519 JCE provider，JSch 本地 Auth fail，推荐 RSA/EC 私钥口径。
                FnDialogInput(privateKey, { privateKey = it }, hint = "PEM 私钥（可与密码二选一，换行用 \\n；推荐 RSA/EC）", password = true)
                FnDialogInput(passphrase, { passphrase = it }, hint = "私钥口令（可空）", password = true)
                if (error != null) {
                    Text(
                        error!!,
                        color = FnComp.IconRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        actions = listOf(
            "添加" to {
                val parsedPort = port.trim().toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写主机地址"
                    user.isBlank() -> error = "请填写用户名"
                    parsedPort == null || parsedPort !in 1..65535 -> error = "端口必须是 1~65535"
                    pass.isBlank() && privateKey.isBlank() -> error = "请填写密码或 PEM 私钥之一"
                    else -> {
                        error = null
                        onAdd(host.trim(), parsedPort, user.trim(), pass, root.trim(), privateKey, passphrase)
                    }
                }
            },
            "关闭" to onDismiss,
        ),
    )
}

/** §61.228：FTP 源添加（§61.216 SftpDialog 同型；username 空 = anonymous，驱动侧默认）。 */
@Composable
private fun FtpDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, String, String, String, Boolean) -> Unit,
) {
    var ssl by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var error by remember { mutableStateOf<String?>(null) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 FTP 源",
        content = {
            Column {
                FnDialogInput(host, { host = it }, hint = "主机 IP / 域名，如 nas.local")
                FnDialogInput(port, { port = it }, hint = "FTP 端口（默认 21）")
                FnDialogInput(user, { user = it }, hint = "用户名（空 = 匿名）")
                FnDialogInput(pass, { pass = it }, hint = "密码（匿名可空）", password = true)
                FnDialogInput(root, { root = it }, hint = "远程根目录（默认 /）")
                FnRow(
                    icon = FnIcon.Lock,
                    title = "FTPS（显式 TLS）",
                    onClick = { ssl = !ssl },
                    showChevron = false,
                    trailing = { FnSwitch(checked = ssl, onChange = { ssl = it }) },
                )
                if (error != null) {
                    Text(
                        error!!,
                        color = FnComp.IconRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        actions = listOf(
            "添加" to {
                val parsedPort = port.trim().toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写主机地址"
                    parsedPort == null || parsedPort !in 1..65535 -> error = "端口必须是 1~65535"
                    else -> {
                        error = null
                        onAdd(host.trim(), parsedPort, user.trim(), pass, root.trim(), ssl)
                    }
                }
            },
            "关闭" to onDismiss,
        ),
    )
}

/** §61.231：均衡器面板——5 段滑杆（millibel，±15dB 口径）+ 档位 + 开关；
 *  应用走 applyEqualizer 全链（engine setEqualizerBands band 数不匹配取 min）。 */
@Composable
private fun EqDialog(
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
@Composable
private fun StrmDialog(
    delegates: List<Storage>,
    onDismiss: () -> Unit,
    onAdd: (delegateStorageId: String, paths: String) -> Unit,
) {
    var delegateId by remember { mutableStateOf("") }
    var paths by remember { mutableStateOf("") }
    val canAdd = delegateId.isNotBlank() && delegates.any { it.id == delegateId }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 STRM 源",
        content = {
            Column {
                if (delegates.isEmpty()) {
                    Text(
                        "还没有可复用的来源——先添加本地目录 / WebDAV，再挂载其 .strm 挂载点",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        "复用来源",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    delegates.forEach { storage ->
                        val selected = storage.id == delegateId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { delegateId = storage.id }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selected) "●" else "○",
                                color = if (selected) FnComp.IconRed else FnComp.TextSecondary,
                                fontSize = 14.sp,
                            )
                            Text(
                                " ${storage.remark.ifBlank { storage.mountPath }}（${storage.driverType}）",
                                color = if (selected) FnComp.TextPrimary else FnComp.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                // §61.161：多行路径编辑（parsePaths 每行 `目录` 或 `名:目录`）。
                androidx.compose.foundation.text.BasicTextField(
                    value = paths,
                    onValueChange = { paths = it },
                    minLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = FnComp.TextPrimary,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(FnComp.InputBg)
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (paths.isBlank()) {
                            Text(
                                "挂载路径（可空=委托根目录；每行 目录 或 名:目录）",
                                color = FnComp.TextHint,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        },
        actions = listOf("添加" to {
            if (canAdd) onAdd(delegateId, paths.trim())
        }),
    )
}

/** §61.137 歌单页（cvn 分组标题 + cvm 60dip 行 + ⋮ more-ops 面板）。 */
@Composable
private fun PlaylistsScreen(
    playlists: List<Playlist>,
    onCreate: (String) -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onMoveItem: (String, Int, Int) -> Unit,
    onRemoveItem: (String, Int) -> Unit,
    observeTracks: (String) -> Flow<List<Track>>,
    /** V0.2 §61.220：智能歌单——全量曲库 + 命中曲目播放回调。 */
    allTracks: List<Track> = emptyList(),
    onPlaySmart: (List<Track>) -> Unit = {},
    /** §61.192：次级页返回（回「更多」）。 */
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    // §61.277（V0.4-04）：飞牛骨架重排——页头 + 分组标题 + 飞牛行；逻辑/回调零改动。
    var opsTarget by remember { mutableStateOf<Playlist?>(null) }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader(
            "歌单",
            onBack = onBack,
            trailing = {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { showCreate = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", color = FnComp.TextPrimary, fontSize = 20.sp)
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            // V0.2 §61.220：智能歌单（实时规则，不落库、曲库变化即时生效）。
            item { FnSectionTitle("智能歌单 · ${SmartPlaylist.PRESETS.size}（实时规则）") }
            items(SmartPlaylist.PRESETS, key = { it.name }) { rule ->
                val hits = SmartPlaylist.evaluate(allTracks, rule)
                FnPlaylistRow(
                    name = "✧ ${rule.name}",
                    subtitle = "命中 ${hits.size} 首 · 点击播放",
                    onClick = { if (hits.isNotEmpty()) onPlaySmart(hits) },
                )
            }
            item { FnSectionTitle("创建的歌单 · ${playlists.size}") }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "还没有自建歌单；在音乐库曲目行点「＋」即可创建并加入",
                        color = FnComp.TextHint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                // §61.140 首曲封面：每行一条 observeTracks 流取 firstOrNull() 的
                // coverRef（复用 §61.138 LruCache；行离开组合即取消收集）。
                val firstCoverRef by remember(playlist.id) {
                    observeTracks(playlist.id).map { tracks ->
                        tracks.firstOrNull()?.coverRef
                    }
                }.collectAsState(initial = null)
                FnPlaylistRow(
                    name = playlist.name,
                    subtitle = if (expandedId == playlist.id) "收起曲目" else "展开查看曲目",
                    coverRef = firstCoverRef,
                    onClick = {
                        expandedId = if (expandedId == playlist.id) null else playlist.id
                    },
                    trailing = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { opsTarget = playlist },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("⋮", color = FnComp.TextSecondary, fontSize = 18.sp)
                        }
                    },
                )
                if (expandedId == playlist.id) {
                    val tracks by observeTracks(playlist.id)
                        .collectAsState(initial = emptyList())
                    if (tracks.isEmpty()) {
                        Text(
                            "（空歌单——在音乐库曲目行点「＋」加入）",
                            color = FnComp.TextHint,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 78.dp, bottom = 6.dp),
                        )
                    }
                    tracks.forEachIndexed { index, track ->
                        FnTrackRow(
                            title = "${index + 1}. ${track.title}",
                            // §61.275：艺人/专辑缺失不渲染空段
                            subtitle = TrackInfo.listSubtitle(track),
                            coverRef = track.coverRef,
                            dimmed = track.availability != Availability.OK,
                            badge = unavailableBadge(track),
                            onClick = { playOrExplain(context, track) {} },
                            trailing = {
                                Row {
                                    Text(
                                        "↑",
                                        color = FnComp.TextSecondary,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .clickable {
                                                if (index > 0) {
                                                    onMoveItem(playlist.id, index, index - 1)
                                                }
                                            },
                                    )
                                    Text(
                                        "↓",
                                        color = FnComp.TextSecondary,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .clickable {
                                                if (index < tracks.size - 1) {
                                                    onMoveItem(playlist.id, index, index + 1)
                                                }
                                            },
                                    )
                                    Text(
                                        "✕",
                                        color = FnComp.TextSecondary,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .clickable { onRemoveItem(playlist.id, index) },
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    // 歌单 ⋮ → 操作面板（播放全部 / 重命名 / 删除）。
    opsTarget?.let { target ->
        FnSheetDialog(
            onDismiss = { opsTarget = null },
            title = target.name,
            actions = listOf(
                "播放全部" to { opsTarget = null; onPlay(target.id) },
                "重命名" to { opsTarget = null; renameTarget = target.id },
                "删除歌单" to { opsTarget = null; deleteTarget = target },
                "取消" to { opsTarget = null },
            ),
            destructiveIndexOf = 2,
        )
    }
    if (showCreate) {
        CreatePlaylistSheet(
            title = "新建歌单",
            initial = "",
            hint = "歌单名",
            confirmLabel = "创建",
            onConfirm = { name ->
                if (name.isNotBlank()) onCreate(name.trim())
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { target ->
        val current = playlists.firstOrNull { it.id == target }
        CreatePlaylistSheet(
            title = "重命名歌单",
            initial = current?.name ?: "",
            hint = "新名称",
            confirmLabel = "保存",
            onConfirm = { name ->
                if (name.isNotBlank()) onRename(target, name.trim())
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    // §61.121：删除确认（此前误删无确认，M3 挂账 UX 收口）。
    deleteTarget?.let { target ->
        FnSheetDialog(
            onDismiss = { deleteTarget = null },
            title = "删除歌单「${target.name}」？歌单曲目引用将一并移除；该操作不可撤销。",
            actions = listOf(
                "删除" to {
                    onDelete(target.id)
                    deleteTarget = null
                },
                "取消" to { deleteTarget = null },
            ),
            destructiveIndexOf = 0,
        )
    }
}

/** 建单/重命名共用输入面板（飞牛卡片弹层 + FnDialogInput，§61.277）。 */
@Composable
private fun CreatePlaylistSheet(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(title) { mutableStateOf(initial) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = title,
        content = { FnDialogInput(text, { text = it }, hint = hint) },
        actions = listOf(confirmLabel to { onConfirm(text) }),
    )
}

/** §61.119 加入歌单面板：已有歌单行 + 新建内联。 */
@Composable
private fun AddToPlaylistSheet(
    trackTitle: String,
    playlists: List<Playlist>,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "加入歌单：$trackTitle",
        content = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        "还没有歌单，先新建一个",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                playlists.forEach { pl ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clickable { onPick(pl.id) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(pl.name, color = FnComp.TextPrimary, fontSize = 16.sp, maxLines = 1)
                    }
                }
                FnDialogInput(newName, { newName = it }, hint = "新歌单名")
            }
        },
        actions = listOf("建并加入" to {
            if (newName.isNotBlank()) onCreateAndAdd(newName.trim())
        }),
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
    /** §61.187：「取消」= 退出搜索页（回进入方）；旧实现只清关键字 ⇒ 无法返回。 */
    onClose: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(FnComp.PageBg).imePadding()) {
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
                                .clickable { onQuery(word) }
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
                    TrackRowGuarded(track) { onPlayTracks(results, index) }
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
) {
    val context = LocalContext.current
    val favorites by bridge.observeFavorites().collectAsState(initial = emptySet())
    val history by bridge.observeHistory().collectAsState(initial = emptyList())
    val favTracks = allTracks.filter { it.id in favorites }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("我的音乐", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            FnStatCard(
                icon = FnIcon.Favorite,
                title = "我的音乐",
                primary = "${favTracks.size} 收藏",
                secondary = "最近播放 ${history.size} 首 · 全部来自本地曲库",
                accent = FnComp.IconRed,
            )
            Spacer(Modifier.height(FnBaseline.StatToNextGroupDp.dp))
            if (favTracks.isEmpty() && history.isEmpty()) {
                FnEmpty("收藏曲目或播放曲目后在此显示", Modifier.height(220.dp))
            }
            if (favTracks.isNotEmpty()) {
                FnSectionTitle("收藏 · ${favTracks.size}")
                FnGroupCard {
                    favTracks.forEachIndexed { index, track ->
                        FnTrackRow(
                            title = track.title,
                            subtitle = TrackInfo.listSubtitle(track),
                            coverRef = track.coverRef,
                            glyph = "♥",
                            dimmed = track.availability != Availability.OK,
                            badge = unavailableBadge(track),
                            onClick = { playOrExplain(context, track) { onPlayTracks(favTracks, index) } },
                        )
                    }
                }
                Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            }
            if (history.isNotEmpty()) {
                FnSectionTitle("最近播放 · ${history.size}")
                FnGroupCard {
                    history.forEachIndexed { index, track ->
                        FnTrackRow(
                            title = track.title,
                            subtitle = TrackInfo.listSubtitle(track),
                            coverRef = track.coverRef,
                            glyph = "↺",
                            dimmed = track.availability != Availability.OK,
                            badge = unavailableBadge(track),
                            onClick = { playOrExplain(context, track) { onPlayTracks(history, index) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * §61.137 设置页（`main_account`/cky + `item_account_*` 行家族）：52dip 页头 +
 * 40dip 分组标题（b19）+ 50dip 行（bzv 左区 20dip 图标 + 16sp 标题 + 右侧 11sp 提示
 * + 箭头；b1a 开关行）。交互契约不变（SettingsStore effects）。
 */
@Composable
private fun SettingsScreen(
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
    duplicateReport: StateFlow<com.example.cloudmusic.model.DuplicateReport?> = MutableStateFlow(null),
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
    var cacheMsg by remember { mutableStateOf("") }
    var showScanDuration by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showDupReport by remember { mutableStateOf(false) }
    var showIgnoreDirs by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // 顶栏：从「更多」进入的**页内二级页**，返回钮语义是「回上一屏」，
        // 与飞牛「回 NAS 主屏」的宿主壳层返回箭头不同（spec §2 #1 / §61.175）。
        FnPageHeader("设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            // 卡1：本地曲库统计（替代飞牛 admin 账号卡，spec §2 #2）——不造用户信息
            FnStatCard(
                icon = FnIcon.Songs,
                title = "本地曲库",
                primary = "$trackCount 首",
                secondary = "$sourceCount 个数据源",
            )
            Spacer(Modifier.height(FnBaseline.StatToNextGroupDp.dp))
            // 卡2 播放（人话标签；§61.188 用户「罗里吧嗦」——砍技术值与内部行话）
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
                    icon = FnIcon.Trash,
                    title = "清理播放缓存",
                    onClick = {
                        onClearCache()
                        cacheMsg = if (runtime.proxyRunning) "已执行" else "已执行；播放中会被拒绝"
                    },
                    value = "释放空间",
                )
                // §61.211（G09 拍板 A / 预研报告 §4）：仅 MIUI 系设备显示——
                // 进程重建受 MIUI 拉活限制，应用侧唯一可行动点 = 引导手动开自启动。
                if (com.example.cloudmusic.MiuiAutostartGuide
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
            if (com.example.cloudmusic.MiuiAutostartGuide
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
                        com.example.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            // 卡3 曲库（飞牛「音乐库管理」映射：spec §2 #7）
            FnGroupCard {
                FnRow(
                    icon = FnIcon.Refresh,
                    title = "重新扫描音乐库",
                    onClick = onRescan,
                    value = "找出新增与恢复的文件",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Sources,
                    title = "音乐来源",
                    onClick = onOpenSources,
                    value = "添加或移除音乐来源",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.FolderOff,
                    title = "忽略目录",
                    onClick = { showIgnoreDirs = true },
                    value = if (scanIgnoreDirs.isEmpty()) "未设置" else "${scanIgnoreDirs.size} 项",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Gauge,
                    title = "扫描速度限制",
                    onClick = { showScanDuration = true },
                    value = "每次至少 ${scanMinDurationMs / 1000} 秒",
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            // 卡4 音质（ReplayGain 人话化）
            FnGroupCard {
                FnRow(
                    icon = FnIcon.Level,
                    title = "音量均衡分析",
                    onClick = {
                        onAnalyzeReplayGain()
                        cacheMsg = "已开始分析（每次最多 50 首，播放时音量更均匀）"
                    },
                    value = "让每首歌音量接近",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Image,
                    title = "刮削缺失封面",
                    onClick = {
                        onScrapeCovers()
                        cacheMsg = "已开始刮削（每次最多 50 首，联网匹配专辑封面）"
                    },
                    value = "联网补齐缺失封面",
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Duplicate,
                    title = "检测重复曲目",
                    onClick = {
                        onFindDuplicates()
                        showDupReport = true
                        cacheMsg = "已开始检测（按标题/艺人 + 时长 ±1s 分组，仅提示不自动删除）"
                    },
                    value = "找出疑似重复的曲目",
                )
                FnRow(
                    dividerAbove = true,
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
                    icon = FnIcon.Wave,
                    title = "淡入淡出",
                    onClick = { onFadeEnabledChanged(!fadeEnabled) },
                    value = if (fadeEnabled) "开（起播渐入 / 分轨渐出）" else "关",
                    showChevron = false,
                    trailing = { FnSwitch(checked = fadeEnabled, onChange = onFadeEnabledChanged) },
                )
                FnRow(
                    dividerAbove = true,
                    icon = FnIcon.Sliders,
                    title = "均衡器",
                    onClick = { showEq = true },
                    value = if (eqEnabled) "开 · 档位 " + eqEffectLevel else "关",
                )
            }
            if (cacheMsg.isNotBlank()) {
                Text(
                    cacheMsg,
                    color = FnComp.SwitchGreen,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
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
private fun IgnoreDirsSheet(
    current: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.joinToString(", ")) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "忽略目录（逗号分隔，下次扫描生效）",
        content = { FnDialogInput(text, { text = it }, hint = "如：System Volume Information, .Trash") },
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
private fun OutputDialog(
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
private fun ManualDmrDialog(
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
        content = {
            Column {
                FnDialogInput(host, { host = it }, hint = "设备 IP / 主机名，如 192.168.1.5")
                FnDialogInput(port, { port = it }, hint = "端口（可空，默认试 8200 / 49152）")
                FnDialogInput(descUrl, { descUrl = it }, hint = "设备说明地址（可空，填了只试它）")
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
