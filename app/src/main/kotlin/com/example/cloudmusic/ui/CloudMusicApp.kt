package com.example.cloudmusic.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cloudmusic.ui.player.LegacyAsset
import com.example.cloudmusic.ui.player.LegacyMetrics
import com.example.cloudmusic.ui.player.LegacyPalette
import com.example.cloudmusic.ui.player.loadLegacyBitmap
import com.example.cloudmusic.ui.player.loadLegacyTypeface
import com.example.cloudmusic.ui.player.rememberLegacyImageBitmap
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudmusic.SurfaceNav
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
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
 * §61.168（飞牛导航）：功能页五页口径标签——胶囊「更多」页的入口单一来源。
 * 保留 03-10 §5 五页口径（曲库/歌单/搜索/队列/设置）不丢，只是从平铺底栏收进「更多」。
 */
private val FUNCTION_PAGE_LABELS = listOf(
    "曲库" to "♫", "歌单" to "☰", "搜索" to "⌕",
    "队列" to "☷", "设置" to "⚙",
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
    // 次级页是**过度隐藏**——飞牛基准只有**搜索页**全屏无底栏（161534），设置页（161550）
    // 明确显示底栏且「更多」高亮。修正：仅搜索页隐藏，其余页面（含设置）恢复显示。
    val capsuleHidden = tab == 3
    // §61.119（M3）：加入歌单弹窗目标曲目。
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    // §61.183：验收期导航请求兑现（粘性流，启动页结束后同样生效；恒 null = 无动作）。
    LaunchedEffect(surfaceRequests) {
        surfaceRequests.collect { req ->
            when (req) {
                SurfaceNav.Surface.PLAYER -> showPlayer = true
                SurfaceNav.Surface.HOME -> showPlayer = false
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

    // §61.187（W7 全屏沉浸）：播放页打开时隐藏系统栏（竞品播放页顶到状态栏，
    // 行内滑出临时呼出），关闭时恢复——DisposableEffect 成对，退出重组即还原。
    val view = LocalView.current
    if (showPlayer) {
        DisposableEffect(Unit) {
            val window = (view.context as? android.app.Activity)?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, view) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    // §61.137：去掉全局 TopAppBar（竞品无全局标题栏，页头归各页：设置 52dip 行 /
    // 歌单·我的音乐 44dip 标题栏）；底栏竞品化（h7 底 + 10sp label + 品牌红选中）。
    // §61.138：迷你条旋转封面接 `Track.coverRef` 真实封面（回退竞品 g9f 占位盘）。
    val miniCoverRef = player.currentTrackId?.let { id ->
        trackList.firstOrNull { it.id.value == id.value }?.coverRef
    }
    // §61.150：迷你条标题接 Track.title 真曲名（§61.141 观察项——此前恒显示
    // raw currentTrackId.value，竞品显示曲名）；曲库缺失时回退 trackId（行为不变）。
    val miniTitle = player.currentTrackId?.let { id ->
        trackList.firstOrNull { it.id.value == id.value }?.title
    }
    Scaffold(
        containerColor = Comp.PageBg,
        bottomBar = {
            Column {
                // §61.196（W13）：设置页不显示播放条（基准 161550 无播放条，仅底部胶囊）；
                // 其余页面播放条恒显（全局播放控制）。
                if (tab != 6) {
                    MiniPlayer(
                        player, onPlay, onPause, miniCoverRef, miniTitle,
                        onOpen = { showPlayer = true },
                        onOpenQueue = { showMore = false; tab = 4 },
                    )
                }
                // §61.187：搜索页隐藏胶囊栏（§61.195 勘误后仅搜索页——设置页底栏在）。
                if (!capsuleHidden) {
                // §61.168（飞牛导航）：胶囊三 tab（首页/音乐库/更多），对齐飞牛真机底栏。
                // 内部 tab 索引 0~6 保持不变；「更多」弹出入口面板承载歌单/搜索/队列/我的/设置，
                // 功能不丢。无返回箭头/无账号入口（辨别铁律）。
                // §61.191（W4）：仅图标化——白色图标 + 选中红圆底白图标，去文字标签。
                FnCapsuleBar(
                    icons = listOf(
                        FnCapsuleIcon.Home, FnCapsuleIcon.Music, FnCapsuleIcon.More,
                    ),
                    selected = when {
                        showSources || showMore -> -1
                        tab == 0 -> 0
                        tab == 1 -> 1
                        else -> 2
                    },
                    onSelect = { i ->
                        showSources = false
                        showMore = false
                        when (i) {
                            0, 1 -> tab = i
                            2 -> showMore = true
                        }
                    },
                )
                } // §61.187：次级页隐藏胶囊栏
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
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
                    onLibraryManager = { showMore = false; tab = 7 },
                    onDismiss = { showMore = false },
                    entryLabels = FUNCTION_PAGE_LABELS,
                )
                tab == 0 -> HomeScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
                    onOpenSearch = { searchReturnTo = 0; tab = 3 },
                    onOpenSources = { showSources = true },
                    onOpenMine = { tab = 5 },
                    favoriteCount = homeFavorites.size,
                    recentCount = homeHistory.size,
                )
                tab == 1 -> FnLibraryScreen(
                    tracks = trackList,
                    onPlayTracks = onPlayTracks,
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
                // §61.168：原曲库完整版（含加入歌单）从「更多 → 曲库管理」进入，功能不丢。
                tab == 7 -> LibraryScreen(
                    trackList, onRescanAll, onRetryUnavailable, onPlayTracks,
                ) { track -> addToPlaylistTrack = track }
                else -> SettingsScreen(
                    runtime, appSettings.network.wifiOnly, onRescanAll,
                    { showSources = true },
                    // §61.29 / 03-11 §2：打开输出弹窗时按动作申请蓝牙权限显示设备名；
                    // 弹窗本身不等待授权结果（B01 拒绝不阻断）。
                    { showOutput = true; onOpenOutput() },
                    onWifiOnlyChanged,
                    onClearCache,
                    onAnalyzeReplayGain,
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
        OutputDialog(
            state = output,
            devices = dmrDevices,
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
 * `12 §61.42` / `legacy_e0g.xml`（view_vinyl_records）+ `legacy_bnx.xml`：
 * 迷你条由「37dip 圆环 → 36dip 旋转封面 → 9dip 红点 → 14sp 跑马灯(音符 drawableStart)
 * → 播放 / 队列入口」组成，背景是 18dip 圆角的
 * `gradient dz(#33000000) → du(#00000000)`。色值 / 尺寸 / 位图全部取自竞品导出产物。
 *
 * 不伪造：`CircleProgressView` 的进度弧无生产数据源，只还原静态圆环；
 * 暂停位图与唱针不在导出闭包内，按竞品形态自绘；旋转周期 12s 是自定值（挂账）。
 */
@Composable
private fun MiniPlayer(
    player: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    coverRef: String?,
    title: String?,
    onOpen: () -> Unit,
    /** §61.187（W9）：队列入口——竞品 MiniPlayer 右端 ☰（播放列表），不铺前后切换。 */
    onOpenQueue: () -> Unit,
) {
    val playing = player.phase == PlayerPhase.PLAYING
    // §61.138：真实封面（缓存 + 降采样，与列表行同源）。
    val coverBitmap = rememberCompCover(coverRef)
    val barShape = RoundedCornerShape(LegacyMetrics.MiniBarCornerDp.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LegacyMetrics.PagePaddingDp.dp, vertical = 4.dp)
            .clip(barShape)
            // 竞品 `legacy_are.xml` miContainer 深色底 #ff33333e：白色图标在浅色
            // 系统主题下会不可见，先铺竞品深色底，再叠 `legacy_bnx.xml` 的横向渐变。
            .background(Color(LegacyPalette.MiniPageBackground))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(LegacyPalette.ScrimBottom),
                        Color(LegacyPalette.Transparent),
                    ),
                ),
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniCoverCluster(playing, coverBitmap)

        // `legacy_e0g.xml` slogan：14sp / b1e / 38dip 高 / maxWidth 125dip /
        // drawableStart=@drawable/f6m / MARQUEE 无限循环。
        val note = rememberLegacyImageBitmap(LegacyAsset.NoteGlyph)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(LegacyMetrics.MarqueeHeightDp.dp)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (note != null) {
                Image(
                    bitmap = note,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.MARQUEE
                        marqueeRepeatLimit = -1
                        isSelected = true
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(LegacyPalette.TextPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, LegacyMetrics.MarqueeTextSp)
                        loadLegacyTypeface(ctx)?.let { typeface = it }
                    }
                },
                update = {
                    // §61.150：真曲名优先（§61.141 观察项收口）；曲库缺失回退 raw
                    // trackId（原行为），未在播放回退占位文案。
                    it.text = title ?: player.currentTrackId?.value ?: "未在播放"
                },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = LegacyMetrics.MarqueeMaxWidthDp.dp),
            )
        }

        // §61.187（W9）：竞品 MiniPlayer 按钮组 = 单个播放/暂停 + 右端 ☰ 队列入口；
        // 前后切换留给播放页横滑/控制区（mini bar 上铺三个键是自研偏差）。
        MiniPlayPauseButton(playing, onPlay, onPause)
        MiniQueueButton(onOpenQueue)
    }
}

/** §61.187（W9）：竞品 MiniPlayer 右端 ☰ 播放列表入口（三横条绘制，无对应素材）。 */
@Composable
private fun MiniQueueButton(onOpenQueue: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onOpenQueue),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            listOf(0, 1, 2).forEach { row ->
                Box(
                    Modifier
                        .padding(vertical = 2.dp)
                        .size(width = if (row == 2) 10.dp else 14.dp, height = 2.dp)
                        .background(Color(LegacyPalette.TextPrimary)),
                )
            }
        }
    }
}

/** `legacy_e0g.xml`：37dip `CircleProgressView` 包住 36dip `RotateFrameLayout` + 9dip 红点。 */
@Composable
private fun MiniCoverCluster(playing: Boolean, cover: Bitmap?) {
    Box(
        modifier = Modifier.size(LegacyMetrics.MiniProgressDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 进度弧无生产数据源，只还原静态圆环，不伪造进度（12 §61.42 挂账）。
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(1.5.dp, Color(LegacyPalette.TrackBuffer), CircleShape),
        )
        RotatingMiniCover(playing, Modifier.size(LegacyMetrics.MiniCoverDp.dp), cover)
        Box(
            Modifier
                .size(LegacyMetrics.RedDotDp.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(Color(LegacyPalette.AccentRed)),
        )
    }
}

/**
 * 平台属性动画驱动的旋转封面（`RotateFrameLayout` 等价），不加载任何 `res/anim/` XML。
 * §61.138：`cover` 为当前曲真实封面（null 回退竞品 `g9f` 占位盘）；位图替换按
 * 身份比对，避免每秒位置 recomposition 重复 setImageBitmap。
 */
@Composable
private fun RotatingMiniCover(
    playing: Boolean,
    modifier: Modifier = Modifier,
    cover: Bitmap? = null,
) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val placeholder = loadLegacyBitmap(ctx, LegacyAsset.CoverPlaceholder)
                setImageBitmap(cover ?: placeholder)
                val animator = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                    duration = LegacyMetrics.MiniCoverSpinMs
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
                tag = SpinState(animator, placeholder, cover)
            }
        },
        update = { view ->
            val state = view.tag as? SpinState ?: return@AndroidView
            if (playing) {
                when {
                    !state.animator.isStarted -> state.animator.start()
                    state.animator.isPaused -> state.animator.resume()
                }
            } else if (state.animator.isStarted && !state.animator.isPaused) {
                state.animator.pause()
            }
            if (state.lastCover !== cover) {
                state.lastCover = cover
                view.setImageBitmap(cover ?: state.placeholder)
            }
        },
        modifier = modifier.clip(CircleShape),
    )
}

/** RotatingMiniCover 的 View 级持态（旋转动画 + 占位盘 + 上次封面，随 View 存活）。 */
private class SpinState(
    val animator: ObjectAnimator,
    val placeholder: Bitmap?,
    var lastCover: Bitmap?,
)

@Composable
private fun MiniControlButton(
    assetPath: String,
    description: String,
    sizeDp: Float,
    onClick: () -> Unit,
) {
    val bitmap = rememberLegacyImageBitmap(assetPath)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(LegacyMetrics.ControlSizeDp.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                modifier = Modifier.size(sizeDp.dp),
            )
        }
    }
}

@Composable
private fun MiniPlayPauseButton(
    playing: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(LegacyMetrics.ControlSizeDp.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (playing) onPause() else onPlay() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniPauseBar()
                MiniPauseBar()
            }
        } else {
            val bitmap = rememberLegacyImageBitmap(LegacyAsset.Play)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "播放",
                    modifier = Modifier.size(LegacyMetrics.ControlPlayDp.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPauseBar() {
    Box(
        Modifier
            .width(4.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(LegacyPalette.White)),
    )
}

/** §61.113（M2-1）：曲库分组视角——全部 / 专辑 / 艺人 / 不可用（analysis/m2-sublist）。 */
private enum class LibraryView { ALL, ALBUM, ARTIST, UNAVAILABLE }

private fun LibraryView.label(): String = when (this) {
    LibraryView.ALL -> "全部"
    LibraryView.ALBUM -> "专辑"
    LibraryView.ARTIST -> "艺人"
    LibraryView.UNAVAILABLE -> "不可用"
}

/**
 * §61.137 曲库页（`fragment_music_library`/as5 + `music_library_music_item`/cvi +
 * `music_library_music_info_layout`/cvg）：45dip 顶 tab（17sp，白色 1dip 指示器）+
 * 0.5dip 分割线 + 行（48dip 封面 / 16sp 歌名 / 12sp 歌手 · 时长，aer 行底）。
 * 原 FilterChip 计数改为分组副标题承载（竞品 tab 无计数）。
 */
@Composable
private fun LibraryScreen(
    tracks: List<Track>,
    onRescan: () -> Unit,
    onRetry: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    CompPage {
        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            CompTabRow(
                tabs = LibraryView.entries.map { it.label() },
                selected = view,
                onSelect = { view = it },
                trailing = {
                    Box(Modifier.fillMaxSize().clickable(onClick = onRescan), contentAlignment = Alignment.Center) {
                        Text("↻", color = Comp.TextSecondary, fontSize = 17.sp)
                    }
                },
            )
            if (tracks.isEmpty()) {
                CompEmpty("曲库为空：添加数据源后执行扫描")
                return@Column
            }
            when (LibraryView.entries[view]) {
                LibraryView.ALL -> LazyColumn(Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id.value }) { track ->
                        CompTrackRow(
                            title = track.title,
                            subtitle = "${track.artist} · ${track.album}",
                            coverRef = track.coverRef,
                            onClick = {
                                val idx = tracks.indexOfFirst { it.id == track.id }
                                onPlayTracks(tracks, idx)
                            },
                            trailing = {
                                Text(
                                    "＋",
                                    color = Comp.TextSecondary,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .padding(start = 4.dp, end = 2.dp)
                                        .clickable { onAddToPlaylist(track) },
                                )
                            },
                        )
                    }
                }
                LibraryView.ALBUM, LibraryView.ARTIST -> {
                    val groups = when (LibraryView.entries[view]) {
                        LibraryView.ALBUM -> tracks.groupBy { it.album.ifBlank { "未知专辑" } }
                        else -> tracks.groupBy { it.artist.ifBlank { "未知艺人" } }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEach { (key, groupTracks) ->
                            item(key = "group-" + key) {
                                val open = key in expanded
                                CompGroupTitle("$key · ${groupTracks.size}")
                                Text(
                                    if (open) "收起 ▾" else "展开 ▸",
                                    color = Comp.TextHint,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = if (open) expanded - key else expanded + key
                                        }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            if (key in expanded) {
                                items(groupTracks, key = { it.id.value + "-" + key }) { track ->
                                    CompTrackRow(
                                        title = track.title,
                                        subtitle = track.artist,
                                        coverRef = track.coverRef,
                                        onClick = {
                                            val idx = groupTracks.indexOfFirst { it.id == track.id }
                                            onPlayTracks(groupTracks, idx)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                LibraryView.UNAVAILABLE -> {
                    val bad = tracks.filter { it.availability != Availability.OK }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (bad.isEmpty()) {
                            item { CompEmpty("没有不可用曲目") }
                        } else {
                            item {
                                CompActionButton(
                                    text = "重试不可用曲目",
                                    onClick = onRetry,
                                    modifier = Modifier.padding(16.dp),
                                    heightDp = 32,
                                )
                            }
                            items(bad, key = { it.id.value }) { track ->
                                CompTrackRow(
                                    title = track.title,
                                    subtitle = "${track.availability} · "
                                        + (track.sourceRef.path ?: track.sourceRef.url ?: ""),
                                    coverRef = track.coverRef,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
    onRemoveStorage: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showWebDav by remember { mutableStateOf(false) }
    var showStrm by remember { mutableStateOf(false) }
    // §61.207：行点击 → 源操作面板（刷新此源 / 移除）——§61.152 的移除确认扩为双动作，
    // 「刷新此源」复用定向增量扫（§61.206/§61.204 同链）。
    var actionTarget by remember { mutableStateOf<Storage?>(null) }
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(title = "数据源管理", onBack = onBack)
            CompGroupTitle("添加来源")
            CompSettingsRow("⊕", "本地目录（SAF）", onClick = onAddSaf, rightHint = "无需读取权限")
            CompSettingsRow("⊕", "从媒体库导入", onClick = onAddMediaStore, rightHint = "按动作申请权限")
            CompSettingsRow("⊕", "添加 WebDAV 源", onClick = { showWebDav = true }, rightHint = "地址 + 认证")
            // §61.151：STRM 独立源（委托已添加的数据源挂载 .strm 挂载点）。
            CompSettingsRow("⊕", "添加 STRM 源", onClick = { showStrm = true }, rightHint = "委托数据源 + .strm")
            CompGroupTitle("维护")
            CompSettingsRow("↻", "全量重扫", onClick = onRescan)
            CompSettingsRow("↻", "重试失败项", onClick = onRetry)
            // §61.208（A4）：同 URL 双源折叠开关（scan.mergeDuplicates 消费方）——
            // 点行切换，rightHint 反映状态（与本页 CompSettingsRow 体系一致）。
            CompSettingsRow(
                icon = "⧉",
                title = "合并重复项",
                onClick = { onMergeDuplicatesChanged(!mergeDuplicates) },
                rightHint = if (mergeDuplicates) "同 URL 只显示一行" else "关闭",
            )
            CompGroupTitle("已添加 · ${storages.size}")
            if (storages.isEmpty()) {
                Text(
                    "还没有数据源，点上方按钮添加本地目录或导入媒体库",
                    color = Comp.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(storages, key = { it.id }) { storage ->
                        val status = if (storage.isWork) "正常" else "不可用 ${storage.errorMsg}"
                        CompSettingsRow(
                            icon = "☁",
                            title = storage.remark.ifBlank { storage.mountPath },
                            // §61.207：点行 → 源操作面板（刷新此源 / 移除）。
                            onClick = { actionTarget = storage },
                            rightHint = "${storage.driverType} · $status",
                        )
                    }
                }
            }
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
    // §61.207：源操作面板（§61.152 移除确认扩为双动作）——「刷新此源」走定向增量扫。
    actionTarget?.let { target ->
        CompSheetDialog(
            onDismiss = { actionTarget = null },
            title = target.remark.ifBlank { target.mountPath },
            content = {
                Text(
                    "${target.driverType} · ${if (target.isWork) "正常" else "不可用 ${target.errorMsg}"}\n" +
                        "刷新只重扫该源（有变更的文件才重新处理）；移除标记曲目不可用，文件不删。",
                    color = Comp.TextSecondary,
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "添加 WebDAV 源",
        content = {
            Column {
                CompDialogInput(url, { url = it }, hint = "服务器地址（含端口），如 https://nas:5006")
                CompDialogInput(username, { username = it }, hint = "用户名（可空=匿名）")
                CompDialogInput(password, { password = it }, hint = "密码（可空）", password = true)
                CompDialogInput(rootPath, { rootPath = it }, hint = "远程路径（默认 /music）")
            }
        },
        actions = listOf("添加" to {
            if (url.isNotBlank()) {
                onAdd(url.trim().trimEnd('/'), username.trim(), password, rootPath.trim())
            }
        }),
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "添加 STRM 源",
        content = {
            Column {
                if (delegates.isEmpty()) {
                    Text(
                        "还没有可委托的数据源——先添加本地目录 / WebDAV，再挂载其 .strm 挂载点",
                        color = Comp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        "委托数据源",
                        color = Comp.TextSecondary,
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
                                color = if (selected) Comp.AccentRed else Comp.TextSecondary,
                                fontSize = 14.sp,
                            )
                            Text(
                                " ${storage.remark.ifBlank { storage.mountPath }}（${storage.driverType}）",
                                color = if (selected) Comp.TextPrimary else Comp.TextSecondary,
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
                        color = Comp.TextPrimary,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Comp.RowBg)
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (paths.isBlank()) {
                            Text(
                                "挂载路径（可空=委托根目录；每行 目录 或 名:目录）",
                                color = Comp.TextHint,
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
    /** §61.192：次级页返回（回「更多」）。 */
    onBack: () -> Unit = {},
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(
                title = "歌单",
                onBack = onBack,
                right = {
                    Text(
                        "＋",
                        color = Comp.TextPrimary,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { showCreate = true },
                    )
                },
            )
            if (playlists.isEmpty()) {
                CompEmpty("还没有歌单；在曲库行点「＋」加入曲目时会自动创建")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { CompGroupTitle("创建的歌单 · ${playlists.size}") }
                    items(playlists, key = { it.id }) { playlist ->
                        // §61.140 首曲封面：每行一条 observeTracks 流取 firstOrNull() 的
                        // coverRef（复用 §61.138 LruCache；行离开组合即取消收集）。
                        val firstCoverRef by remember(playlist.id) {
                            observeTracks(playlist.id).map { tracks ->
                                tracks.firstOrNull()?.coverRef
                            }
                        }.collectAsState(initial = null)
                        CompPlaylistRow(
                            name = playlist.name,
                            subtitle = "展开查看曲目",
                            coverRef = firstCoverRef,
                            onClick = {
                                expandedId = if (expandedId == playlist.id) null else playlist.id
                            },
                            trailing = {
                                Text(
                                    "⋮",
                                    color = Comp.TextSecondary,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .clickable { onPlay(playlist.id) },
                                )
                            },
                        )
                        if (expandedId == playlist.id) {
                            val tracks by observeTracks(playlist.id)
                                .collectAsState(initial = emptyList())
                            if (tracks.isEmpty()) {
                                Text(
                                    "（空歌单——在曲库行点「＋」加入）",
                                    color = Comp.TextHint,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                                )
                            }
                            tracks.forEachIndexed { index, track ->
                                CompTrackRow(
                                    title = "${index + 1}. ${track.title}",
                                    subtitle = track.artist,
                                    coverRef = track.coverRef,
                                    onClick = {},
                                    trailing = {
                                        Row {
                                            Text(
                                                "↑",
                                                color = Comp.TextSecondary,
                                                fontSize = 14.sp,
                                                modifier = Modifier
                                                    .padding(4.dp)
                                                    .clickable {
                                                        if (index > 0) {
                                                            onMoveItem(playlist.id, index, index - 1)
                                                        }
                                                    },
                                            )
                                            Text(
                                                "↓",
                                                color = Comp.TextSecondary,
                                                fontSize = 14.sp,
                                                modifier = Modifier
                                                    .padding(4.dp)
                                                    .clickable {
                                                        if (index < tracks.size - 1) {
                                                            onMoveItem(playlist.id, index, index + 1)
                                                        }
                                                    },
                                            )
                                            Text(
                                                "✕",
                                                color = Comp.TextSecondary,
                                                fontSize = 14.sp,
                                                modifier = Modifier
                                                    .padding(4.dp)
                                                    .clickable { onRemoveItem(playlist.id, index) },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // 歌单 ⋮ → more-ops 面板（播放全部 / 重命名 / 删除）。
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
        CompSheetDialog(
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

/** 建单/重命名共用输入面板（b5y 底板 + CompDialogInput）。 */
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = title,
        content = { CompDialogInput(text, { text = it }, hint = hint) },
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "加入歌单：$trackTitle",
        content = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        "还没有歌单，先新建一个",
                        color = Comp.TextSecondary,
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
                        Text(pl.name, color = Comp.TextPrimary, fontSize = 16.sp, maxLines = 1)
                    }
                }
                CompDialogInput(newName, { newName = it }, hint = "新歌单名")
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
                    FnTrackRow(
                        title = track.title,
                        subtitle = "${track.artist} · ${track.album}",
                        coverRef = track.coverRef,
                        onClick = { onPlayTracks(results, index) },
                    )
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
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(title = "播放列表 · ${queue.size}", onBack = onBack)
            if (queue.isEmpty()) {
                CompEmpty("队列为空（播放后由 QueueRepository.observe() 提供）")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(queue, key = { "${it.queueId}-${it.position}" }) { item ->
                        val track = titles[item.trackId.value]
                        CompTrackRow(
                            title = track?.title ?: item.trackId.value,
                            subtitle = listOfNotNull(track?.artist, track?.album)
                                .joinToString(" · ").ifBlank { "来源：${item.sourceRef}" },
                            coverRef = track?.coverRef,
                            onClick = { onJump(item.position) },
                            coverGlyph = "${item.position + 1}",
                        )
                    }
                }
            }
        }
    }
}

/**
 * §61.130（M5 我的音乐）：收藏 + 最近播放两段（FavoriteRepo/HistoryRepo 全收口）。
 * §61.137 视觉：44dip 页头 + cvn 分组标题 + cvg 曲目行。
 */
@Composable
private fun MyMusicScreen(
    bridge: PlayerStateBridge,
    allTracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onBack: () -> Unit,
) {
    val favorites by bridge.observeFavorites().collectAsState(initial = emptySet())
    val history by bridge.observeHistory().collectAsState(initial = emptyList())
    val favTracks = allTracks.filter { it.id in favorites }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("我的音乐", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            FnStatCard(
                glyph = "♥",
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
                            subtitle = track.artist,
                            coverRef = track.coverRef,
                            glyph = "♥",
                            onClick = { onPlayTracks(favTracks, index) },
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
                            subtitle = track.artist,
                            coverRef = track.coverRef,
                            glyph = "↺",
                            onClick = { onPlayTracks(history, index) },
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
    var showIgnoreDirs by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // 顶栏：从「更多」进入的**页内二级页**，返回钮语义是「回上一屏」，
        // 与飞牛「回 NAS 主屏」的宿主壳层返回箭头不同（spec §2 #1 / §61.175）。
        FnPageHeader("设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(FnBaseline.HeaderToCardGapDp.dp))
            // 卡1：本地曲库统计（替代飞牛 admin 账号卡，spec §2 #2）——不造用户信息
            FnStatCard(
                glyph = "♫",
                title = "本地曲库",
                primary = "$trackCount 首",
                secondary = "$sourceCount 个数据源",
            )
            Spacer(Modifier.height(FnBaseline.StatToNextGroupDp.dp))
            // 卡2 播放（人话标签；§61.188 用户「罗里吧嗦」——砍技术值与内部行话）
            FnGroupCard {
                FnRow(
                    glyph = "⇅",
                    title = "移动网络播放",
                    onClick = { onWifiOnlyChanged(!wifiOnly) },
                    value = if (wifiOnly) "仅 WiFi 下播放" else "允许",
                    showChevron = false,
                    trailing = { FnSwitch(checked = !wifiOnly, onChange = { onWifiOnlyChanged(!it) }) },
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "⌾",
                    title = "输出设备",
                    onClick = onOpenOutput,
                    value = "本机 / DLNA 投屏",
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "✕",
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
                        glyph = "⚑",
                        title = "后台播放保活（MIUI）",
                        onClick = onOpenAutostartSettings,
                        value = "去开启自启动",
                    )
                }
            }
            if (com.example.cloudmusic.MiuiAutostartGuide
                .shouldShow(android.os.Build.MANUFACTURER)
            ) {
                Text(
                    com.example.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                    color = FnComp.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            // 卡3 曲库（飞牛「音乐库管理」映射：spec §2 #7）
            FnGroupCard {
                FnRow(
                    glyph = "↻",
                    title = "重新扫描曲库",
                    onClick = onRescan,
                    value = "找出新增与恢复的文件",
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "☁",
                    title = "数据源管理",
                    onClick = onOpenSources,
                    value = "添加或移除音乐来源",
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "◎",
                    title = "忽略目录",
                    onClick = { showIgnoreDirs = true },
                    value = if (scanIgnoreDirs.isEmpty()) "未设置" else "${scanIgnoreDirs.size} 项",
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "◎",
                    title = "扫描速度限制",
                    onClick = { showScanDuration = true },
                    value = "每次至少 ${scanMinDurationMs / 1000} 秒",
                )
            }
            Spacer(Modifier.height(FnBaseline.GroupGapDp.dp))
            // 卡4 音质（ReplayGain 人话化）
            FnGroupCard {
                FnRow(
                    glyph = "⚖",
                    title = "音量均衡分析",
                    onClick = {
                        onAnalyzeReplayGain()
                        cacheMsg = "已开始分析（每次最多 50 首，播放时音量更均匀）"
                    },
                    value = "让每首歌音量接近",
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
    if (showScanDuration) {
        CompSheetDialog(
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "忽略目录（逗号分隔，下次扫描生效）",
        content = { CompDialogInput(text, { text = it }, hint = "如：System Volume Information, .Trash") },
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
    onOutputSelected: (ActiveOutput) -> Unit,
    onPickDmr: (DmrDevice) -> Unit = {},
    onScanDlna: () -> Unit = {},
    onOpenManualDmr: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "输出设备 · 当前：${if (state.activeOutput == ActiveOutput.LOCAL) "本机" else "DLNA"}",
        content = {
            Column {
                Text(
                    "切换 / 推流已接线（03-08 §10.1），本机静音随切换联动（§14 #9 成对）；" +
                        "切歌保持与断连回落已由会话引擎接管。" +
                        "蓝牙仅显示连接状态、不作为并列输出（03-10 §11 #8）。",
                    color = Comp.TextHint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (state.routeHint.isNotBlank()) {
                    Text(
                        "状态提示：${state.routeHint}",
                        color = Comp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (devices.isNotEmpty()) {
                    Text(
                        "发现 ${devices.size} 台 DLNA 设备（点选直接投屏）：",
                        color = Comp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        // §61.200：设备列表直接映射为面板动作——手动设备 / 最近发现已由 DmrDeviceSelector 排序。
        actions = buildList {
            add("本机（手机扬声器 / 有线输出）" to { onOutputSelected(ActiveOutput.LOCAL) })
            devices.forEach { device ->
                val kind = if (device.source == DeviceSource.MANUAL) "手动" else "发现"
                add("${device.friendlyName} · $kind" to { onPickDmr(device) })
            }
            add("DLNA · 自动选择（记住设备优先）" to { onOutputSelected(ActiveOutput.DLNA) })
            add("重新扫描 DLNA 设备" to onScanDlna)
            add("手动指定 DMR（IP / 描述地址）" to onOpenManualDmr)
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
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "手动指定 DMR（03-08 §4.2）",
        content = {
            Column {
                CompDialogInput(host, { host = it }, hint = "设备 IP / 主机名，如 192.168.1.5")
                CompDialogInput(port, { port = it }, hint = "端口（可空，默认试 8200 / 49152）")
                CompDialogInput(descUrl, { descUrl = it }, hint = "描述地址（可空，填则仅试它）")
                CompDialogInput(label, { label = it }, hint = "备注名（可空）")
                if (localError != null || probeError != null) {
                    Text(
                        localError?.let { "请检查输入：$it" } ?: "探测失败：$probeError",
                        color = Comp.AccentRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (found != null) {
                    Text(
                        "已找到：${found.friendlyName}（${found.baseUrl}）",
                        color = Comp.TextSecondary,
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
