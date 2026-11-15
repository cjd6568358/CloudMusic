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
import com.example.cloudmusic.ui.player.LegacyAsset
import com.example.cloudmusic.ui.player.LegacyMetrics
import com.example.cloudmusic.ui.player.LegacyPalette
import com.example.cloudmusic.ui.player.loadLegacyBitmap
import com.example.cloudmusic.ui.player.loadLegacyTypeface
import com.example.cloudmusic.ui.player.rememberLegacyImageBitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
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
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
    /** §61.132：添加 WebDAV 源（url / username / password / rootPath）。 */
    onAddWebDav: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** §61.151：添加 STRM 独立源（delegateStorageId + paths 多行，空=委托根目录）。 */
    onAddStrm: (String, String) -> Unit = { _, _ -> },
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onOpenOutput: () -> Unit,
    /** §61.110：输出弹窗选择（本机 / DLNA）——DLNA 走发现 → 推流 → setActive 闭环。 */
    onOutputSelected: (ActiveOutput) -> Unit = {},
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
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    // 12 §61.42：用户要求「1:1 像素级复刻竞品播放页」→ 启动即全屏播放页，
    // V0.1 六页 tab 导航栏（曲库/歌单/搜索/队列/我的/设置）不再出现在首屏；
    // 播放页返回才回到功能页，避免把自研导航误当成竞品播放页组件。
    var showPlayer by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    // §61.119（M3）：加入歌单弹窗目标曲目。
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

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
    val resultsFlow = remember(query) { search(query) }
    val searchResults by resultsFlow.collectAsState(initial = emptyList())

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
                MiniPlayer(player, onPlay, onPause, onNext, onPrev, miniCoverRef, miniTitle) { showPlayer = true }
                CompBottomBar(
                    tabs = listOf(
                        "曲库" to "♫", "歌单" to "☰", "搜索" to "⌕",
                        "队列" to "☷", "我的" to "❤", "设置" to "⚙",
                    ),
                    selected = if (showSources) -1 else tab,
                    onSelect = { tab = it; showSources = false },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            ScanBanner(runtime)
            when {
                showSources -> SourcesScreen(
                    storageList, onRescanAll, onRetryUnavailable, onAddSaf, onAddMediaStore,
                    onAddWebDav,
                    onAddStrm,
                    onBack = { showSources = false },
                )
                tab == 0 -> LibraryScreen(
                    trackList, onRescanAll, onRetryUnavailable, onPlayTracks,
                ) { track -> addToPlaylistTrack = track }
                tab == 1 -> PlaylistsScreen(
                    playlistList,
                    onCreate = bridge::onCreatePlaylist,
                    onPlay = bridge::onPlayPlaylist,
                    onDelete = bridge::onDeletePlaylist,
                    onRename = bridge::onRenamePlaylist,
                    onMoveItem = bridge::onMovePlaylistItem,
                    onRemoveItem = bridge::onRemovePlaylistItem,
                    observeTracks = bridge::observePlaylistTracks,
                )
                tab == 2 -> SearchScreen(query, { query = it }, searchResults)
                tab == 3 -> QueueScreen(
                    queueList,
                    titles = trackList.associate { it.id.value to it },
                    onJump = bridge::onQueueJump,
                )
                tab == 4 -> MyMusicScreen(bridge, trackList)
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
                )
            }
        }
    }

    if (showOutput) {
        OutputDialog(output, onOutputSelected) { showOutput = false }
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
        LegacyPlayerPage(bridge = bridge, onClose = { showPlayer = false })
    }
}

@Composable
private fun ScanBanner(state: RuntimeState) {
    val text = when {
        state.scanning -> "扫描中 ${state.scanned}/${state.total}"
        state.errors > 0 -> "扫描完成，${state.errors} 项失败"
        state.lastEffect.isNotBlank() -> "生效：${state.lastEffect}"
        else -> "曲库就绪"
    }
    CompScanBanner(text)
}

/**
 * `12 §61.42` / `legacy_e0g.xml`（view_vinyl_records）+ `legacy_bnx.xml`：
 * 迷你条由「37dip 圆环 → 36dip 旋转封面 → 9dip 红点 → 14sp 跑马灯(音符 drawableStart)
 * → 上一首 / 播放 / 下一首」组成，背景是 18dip 圆角的
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
    onNext: () -> Unit,
    onPrev: () -> Unit,
    coverRef: String?,
    title: String?,
    onOpen: () -> Unit,
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

        MiniControlButton(LegacyAsset.Prev, "上一首", LegacyMetrics.ControlPrevNextDp, onPrev)
        MiniPlayPauseButton(playing, onPlay, onPause)
        MiniControlButton(LegacyAsset.Next, "下一首", LegacyMetrics.ControlPrevNextDp, onNext)
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
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
    onAddWebDav: (String, String, String, String) -> Unit,
    onAddStrm: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var showWebDav by remember { mutableStateOf(false) }
    var showStrm by remember { mutableStateOf(false) }
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
                            onClick = {},
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
                CompDialogInput(paths, { paths = it }, hint = "挂载路径（可空=委托根目录；每行 目录 或 名:目录）")
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
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(
                title = "歌单",
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
@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Track>,
) {
    CompPage {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompSearchField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消",
                    color = Comp.TextPrimary,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp)
                        .clickable { onQuery("") },
                )
            }
            when {
                query.isBlank() -> CompEmpty("输入关键字开始搜索（TrackRepository.search）")
                results.isEmpty() -> CompEmpty("无匹配结果")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id.value }) { track ->
                        CompTrackRow(
                            title = track.title,
                            subtitle = "${track.artist} · ${track.album}",
                            coverRef = track.coverRef,
                            onClick = {},
                        )
                    }
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
) {
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(title = "播放列表 · ${queue.size}")
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
private fun MyMusicScreen(bridge: PlayerStateBridge, allTracks: List<Track>) {
    val favorites by bridge.observeFavorites().collectAsState(initial = emptySet())
    val history by bridge.observeHistory().collectAsState(initial = emptyList())
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompHeaderBar(title = "我的音乐")
            LazyColumn(Modifier.fillMaxSize()) {
                if (favorites.isNotEmpty()) {
                    item(key = "fav-header") { CompGroupTitle("收藏 · ${favorites.size}") }
                    items(allTracks.filter { it.id in favorites }, key = { "fav-" + it.id.value }) { track ->
                        CompTrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverRef = track.coverRef,
                            onClick = {},
                            coverGlyph = "♥",
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    item(key = "hist-header") { CompGroupTitle("最近播放 · ${history.size}") }
                    items(history, key = { "hist-" + it.id.value }) { track ->
                        CompTrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverRef = track.coverRef,
                            onClick = {},
                            coverGlyph = "↺",
                        )
                    }
                }
                if (favorites.isEmpty() && history.isEmpty()) {
                    item { CompEmpty("收藏曲目或播放曲目后在此显示") }
                }
            }
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
) {
    // 操作结果反馈走行右侧 11sp 提示位（b1c drawerItemMsg 语义）。
    var cacheMsg by remember { mutableStateOf("") }
    var showScanDuration by remember { mutableStateOf(false) }
    var showIgnoreDirs by remember { mutableStateOf(false) }
    CompPage {
        Column(Modifier.fillMaxSize()) {
            CompSettingsHeader("设置")
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                CompGroupTitle("播放")
                CompSwitchRow(
                    icon = "⇅",
                    title = "允许移动数据播放",
                    checked = !wifiOnly,
                    onChange = { onWifiOnlyChanged(!it) },
                    rightHint = if (wifiOnly) {
                        "默认仅 WiFi 播放网络音源（01 §6.1）；本地曲目不受影响"
                    } else {
                        "移动网络下也可播放网络音源"
                    },
                )
                CompGroupTitle("维护")
                CompSettingsRow(
                    icon = "☁",
                    title = "数据源管理",
                    onClick = onOpenSources,
                    rightHint = "SAF / 媒体库 / WebDAV",
                )
                CompSettingsRow(
                    icon = "⌾",
                    title = "输出设备",
                    onClick = onOpenOutput,
                    rightHint = "本机 / DLNA",
                )
                CompSettingsRow(
                    icon = "↻",
                    title = "触发全量重扫",
                    onClick = onRescan,
                    rightHint = "refresh=true",
                )
                CompSettingsRow(
                    icon = "✕",
                    title = "清理播放缓存",
                    onClick = {
                        onClearCache()
                        cacheMsg = "清理指令已执行（播放中会被拒绝）；结果见 logcat CloudMusicRuntime"
                    },
                    rightHint = cacheMsg,
                )
                CompSettingsRow(
                    icon = "⚖",
                    title = "分析 ReplayGain",
                    onClick = {
                        onAnalyzeReplayGain()
                        cacheMsg = "ReplayGain 分析已启动（LOCAL 未分析曲目，上限 50 首）；进度见 logcat CM-RG"
                    },
                    rightHint = cacheMsg,
                )
                CompGroupTitle("扫描设置")
                CompSettingsRow(
                    icon = "◎",
                    title = "扫描最短时长",
                    onClick = { showScanDuration = true },
                    rightHint = "短于该值的音频不入曲库 · ${scanMinDurationMs / 1000} 秒",
                )
                CompSettingsRow(
                    icon = "◎",
                    title = "忽略目录",
                    onClick = { showIgnoreDirs = true },
                    rightHint = if (scanIgnoreDirs.isEmpty()) "逗号分隔" else "${scanIgnoreDirs.size} 项",
                )
                CompGroupTitle("运行态")
                CompSettingsRow(
                    icon = "⚙",
                    title = "播放内核",
                    onClick = {},
                    // §61.108 口径修缮：当前生效内核是 B2 开源引擎舱（11-11 换绑，12 §61.65~61.66）；
                    // EngineAdapter 仅是 A 路线回退实现，不再作为生产绑定表述。
                    rightHint = "Media3EngineAdapter（B2 开源引擎；11-11 换绑，12 §61.65~61.66）",
                )
                CompSettingsRow(
                    icon = "⇄",
                    title = "本地流代理",
                    onClick = {},
                    rightHint = if (runtime.proxyRunning) "运行中" else "按需启动",
                )
                CompSettingsRow(
                    icon = "✎",
                    title = "最近生效副作用",
                    onClick = {},
                    rightHint = runtime.lastEffect.ifBlank { "无" },
                )
                Text(
                    "设置写入由 SettingsStore 返回 effects，PlaybackRuntime 是唯一执行者（08 §7 / 12 §31）。" +
                        "照搬播放页属 T-L L3，真机验收属 T-V，本页不伪造已接通。",
                    color = Comp.TextHint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
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
    onOutputSelected: (ActiveOutput) -> Unit,
    onDismiss: () -> Unit,
) {
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "输出设备 · 当前：${if (state.activeOutput == ActiveOutput.LOCAL) "本机" else "DLNA"}",
        content = {
            Column {
                Text(
                    "§61.110：切换 / 推流已接线（第一个生产调用点），本机静音随切换联动" +
                        "（T09 / 03-08 §14 #9 成对契约）；断连回落、DMR 进度轮询、" +
                        "切歌保持投屏属 DLNA 会话引擎（挂账）。" +
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
            }
        },
        actions = listOf(
            "本机（手机扬声器 / 有线输出）" to { onOutputSelected(ActiveOutput.LOCAL) },
            "DLNA（投屏到局域网设备，03-08 §10.1）" to { onOutputSelected(ActiveOutput.DLNA) },
            "关闭" to onDismiss,
        ),
    )
}
