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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
 * §61.343（B-5 收尾）：薄门面——60 参数聚合到 [UiActions]，实现体 [CloudMusicAppContent]
 * 用 `with(actions)` 直接消费字段，函数体逐字未动。
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

@Composable
fun CloudMusicApp(actions: UiActions) {
    CloudMusicAppContent(actions)
}

@OptIn(ExperimentalLayoutApi::class) // §61.357：WindowInsets.isImeVisible（底栏按键盘可见性让位）
@Composable
private fun CloudMusicAppContent(actions: UiActions) = with(actions) {
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
            // §61.357（G-6 二次修正，2026-11-25 用户一手）：键盘弹起时**整条底栏不参与排版**。
            // §61.356 让底栏跟着 IME 上移到键盘上沿——真机观感是「tab 和播放条被键盘推高、
            // 浮在半空」，用户当场质疑，该方案作废。改回主流口径（网易云/QQ音乐同款）：
            // 输入时键盘贴底、底栏让位消失；内容区自己吃 ime inset 滚到键盘上沿。
            // 此时 bottomBar 高度为 0，Scaffold 不再为底栏留白，键盘上方也就不会出现
            // 「底栏高度那么厚」的黑带——那正是用户最初反馈的「键盘上方黑色遮罩」
            // （§61.325 把让位收口到底栏时，误判成「输入时导航消失」）。
            if (!WindowInsets.isImeVisible) {
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
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // §61.357（G-6 二次修正）：Scaffold 的 padding 是普通 padding、不消费 window
                // insets；先把它登记为「已消费」，imePadding 才不会把里面的 navigationBars
                // 再补一遍（真机 MIUI 三键导航 165px，不 consume 会多空一条导航栏的黑带）。
                // §61.352（G-5）：此处不再叠 statusBarsPadding——Scaffold padding 已含
                // statusBars，加两次会顶部多出一整条；只保留 displayCutoutPadding（竖屏
                // 挖孔只补左右安全区，不补顶部）。
                .consumeWindowInsets(padding)
                .displayCutoutPadding()
                .imePadding(),
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
                    // §61.353（G-10）：一级 tab 无返回键，onDismiss 入参已删除。
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
