package com.example.cloudmusic.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cloudmusic.ui.player.LegacyAsset
import com.example.cloudmusic.ui.player.LegacyMetrics
import com.example.cloudmusic.ui.player.LegacyPalette
import com.example.cloudmusic.ui.player.loadLegacyBitmap
import com.example.cloudmusic.ui.player.loadLegacyTypeface
import com.example.cloudmusic.ui.player.rememberLegacyImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

/**
 * T-A6（`12 §33`）/ 第三十一批（`12 §40`）：V0.1 最小自研 UI。
 *
 * 页面（03-10 §5 的 V0.1 子集）：曲库 / 歌单 / 搜索 / 队列 / 设置 五页 + 数据源管理入口
 * + 输出设备弹窗。
 *
 * `12 §61.40`（用户「UI 交互严格复刻竞品」）：点击迷你播放条进入 `LegacyPlayerPage`，
 * 按 `12 §39` / `docs/14 §4.1` / `03-10 §3.3` 的实证参数复刻唱片旋转、唱针起落、
 * 跑马灯、横向手势切歌与进度控制。唱片动画用平台 `ValueAnimator` + `setRotation`
 * 实现，**不是 Compose Canvas 重写**（`03-10 §11 #1`）、也不是 `animateFloatAsState` 近似；
 * 交互经 `PlayerStateBridge` 单向绑定，播放页不持有 `PlayerController` / `IMusicEngine`。
 * 零新增模块 / 依赖 / 权限，不改 `assets` 与 Manifest。
 *
 * 边界（`03-10 §11`）：只经 Repository / StateFlow 取数，不碰 Driver / Room / OkHttp；
 * 蓝牙只作状态提示、**不列为与本机/DLNA 并列的可选输出**（禁止事项 8）。
 * V0.1 用状态切页，未引 navigation-compose / coil-compose。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // V0.1 五页 tab 导航栏（曲库/歌单/搜索/队列/设置）不再出现在首屏；
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

    Scaffold(
        topBar = {
            if (showSources) {
                TopAppBar(
                    title = { Text("数据源管理") },
                    navigationIcon = {
                        IconButton(onClick = { showSources = false }) { Text("‹") }
                    },
                )
            } else {
                TopAppBar(title = { Text("CloudMusic") })
            }
        },
        bottomBar = {
            Column {
                MiniPlayer(player, onPlay, onPause, onNext, onPrev) { showPlayer = true }
                NavigationBar {
                    listOf(
                        "曲库" to "♫", "歌单" to "☰", "搜索" to "⌕", "队列" to "☷", "设置" to "⚙",
                    ).forEachIndexed { index, (label, glyph) ->
                        NavigationBarItem(
                            selected = !showSources && tab == index,
                            onClick = { tab = index; showSources = false },
                            icon = { Text(glyph) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanBanner(runtime)
            when {
                showSources -> SourcesScreen(
                    storageList, onRescanAll, onRetryUnavailable, onAddSaf, onAddMediaStore,
                )
                tab == 0 -> LibraryScreen(
                    trackList, onRescanAll, onRetryUnavailable, onPlayTracks,
                ) { track -> addToPlaylistTrack = track }
                tab == 1 -> PlaylistsScreen(
                    playlistList,
                    onCreate = bridge::onCreatePlaylist,
                    onPlay = bridge::onPlayPlaylist,
                    onDelete = bridge::onDeletePlaylist,
                )
                tab == 2 -> SearchScreen(query, { query = it }, searchResults)
                tab == 3 -> QueueScreen(queueList, onJump = bridge::onQueueJump)
                else -> SettingsScreen(
                    runtime, appSettings.network.wifiOnly, onRescanAll,
                    { showSources = true },
                    // §61.29 / 03-11 §2：打开输出弹窗时按动作申请蓝牙权限显示设备名；
                    // 弹窗本身不等待授权结果（B01 拒绝不阻断）。
                    { showOutput = true; onOpenOutput() },
                    onWifiOnlyChanged,
                    onClearCache,
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

    // §61.119（M3）：加入歌单弹窗——曲库行 "+" 触发；支持新建内联。
    if (addToPlaylistTrack != null) {
        AlertDialog(
            onDismissRequest = { addToPlaylistTrack = null },
            title = { Text("加入歌单：${addToPlaylistTrack?.title ?: ""}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (playlistList.isEmpty()) {
                        Text("还没有歌单，先新建一个", style = MaterialTheme.typography.bodySmall)
                    }
                    playlistList.forEach { pl ->
                        ListItem(
                            headlineContent = { Text(pl.name) },
                            modifier = Modifier.clickable {
                                bridge.onAddToPlaylist(pl.id, addToPlaylistTrack!!.id)
                                addToPlaylistTrack = null
                            },
                        )
                    }
                    var newName by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新歌单名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = newName.isNotBlank(), onClick = {
                            val track = addToPlaylistTrack
                            bridge.onCreatePlaylist(newName.trim()) { id ->
                                if (track != null) bridge.onAddToPlaylist(id, track.id)
                            }
                            addToPlaylistTrack = null
                        }) { Text("建并加入") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addToPlaylistTrack = null }) { Text("关闭") }
            },
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
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
    onOpen: () -> Unit,
) {
    val playing = player.phase == PlayerPhase.PLAYING
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
        MiniCoverCluster(playing)

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
                update = { it.text = player.currentTrackId?.value ?: "未在播放" },
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
private fun MiniCoverCluster(playing: Boolean) {
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
        RotatingMiniCover(playing, Modifier.size(LegacyMetrics.MiniCoverDp.dp))
        Box(
            Modifier
                .size(LegacyMetrics.RedDotDp.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(Color(LegacyPalette.AccentRed)),
        )
    }
}

/** 平台属性动画驱动的旋转封面（`RotateFrameLayout` 等价），不加载任何 `res/anim/` XML。 */
@Composable
private fun RotatingMiniCover(playing: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(loadLegacyBitmap(ctx, LegacyAsset.CoverPlaceholder))
                val animator = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                    duration = LegacyMetrics.MiniCoverSpinMs
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
                tag = animator
            }
        },
        update = { view ->
            val animator = view.tag as? ObjectAnimator ?: return@AndroidView
            if (playing) {
                when {
                    !animator.isStarted -> animator.start()
                    animator.isPaused -> animator.resume()
                }
            } else if (animator.isStarted && !animator.isPaused) {
                animator.pause()
            }
        },
        modifier = modifier.clip(CircleShape),
    )
}

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
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("曲库 · ${tracks.size} 首", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRescan) { Text("重新扫描") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryView.entries.forEachIndexed { index, v ->
                FilterChip(
                    selected = view == index,
                    onClick = { view = index },
                    label = {
                        val count = when (v) {
                            LibraryView.ALBUM -> tracks.map { it.album }.distinct().size
                            LibraryView.ARTIST -> tracks.map { it.artist }.distinct().size
                            LibraryView.UNAVAILABLE ->
                                tracks.count { it.availability != Availability.OK }
                            else -> tracks.size
                        }
                        Text("${v.label()} $count")
                    },
                )
            }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("曲库为空：添加数据源后执行扫描", textAlign = TextAlign.Center)
            }
            return
        }
        when (LibraryView.entries[view]) {
            LibraryView.ALL -> LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id.value }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 1) },
                        supportingContent = {
                            Text("${track.artist} · ${track.album}", maxLines = 1)
                        },
                        leadingContent = { Text("♪") },
                        trailingContent = {
                            Text("＋", modifier = Modifier.clickable {
                                onAddToPlaylist(track)
                            })
                        },
                        modifier = Modifier.clickable {
                            val idx = tracks.indexOfFirst { it.id == track.id }
                            onPlayTracks(tracks, idx)
                        },
                    )
                    HorizontalDivider()
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
                            ListItem(
                                headlineContent = { Text(key, maxLines = 1) },
                                supportingContent = { Text("${groupTracks.size} 首") },
                                trailingContent = { Text(if (open) "▾" else "▸") },
                                modifier = Modifier.clickable {
                                    expanded = if (open) expanded - key else expanded + key
                                },
                            )
                            HorizontalDivider()
                        }
                        if (key in expanded) {
                            items(groupTracks, key = { it.id.value + "-" + key }) { track ->
                                ListItem(
                                    headlineContent = { Text(track.title, maxLines = 1) },
                                    supportingContent = { Text(track.artist, maxLines = 1) },
                                    leadingContent = { Text("♪") },
                                    modifier = Modifier.clickable {
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
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("没有不可用曲目") }
                        }
                    } else {
                        item {
                            Button(onClick = onRetry, modifier = Modifier.padding(16.dp)) {
                                Text("重试不可用曲目")
                            }
                        }
                        items(bad, key = { it.id.value }) { track ->
                            ListItem(
                                headlineContent = { Text(track.title, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        "${track.availability} · "
                                            + (track.sourceRef.path ?: track.sourceRef.url ?: ""),
                                        maxLines = 1,
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track) {
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1) },
        supportingContent = { Text("${track.artist} · ${track.album}", maxLines = 1) },
        leadingContent = { Text("♪") },
    )
}

@Composable
private fun SourcesScreen(
    storages: List<Storage>,
    onRescan: () -> Unit,
    onRetry: () -> Unit,
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("数据源 · ${storages.size}", style = MaterialTheme.typography.titleMedium)
        // 12 §52：真机 A3 暴露「数据源页只有展示、没有添加入口」→ 曲库 0 首只能靠
        // run-as 灌 Room 夹具。SAF 优先（无需读取权限），MediaStore 按动作申请。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddSaf) { Text("添加本地目录 (SAF)") }
            Button(onClick = onAddMediaStore) { Text("从媒体库导入") }
        }
        Text(
            "SAF 目录无需读取权限；媒体库导入会按动作申请读媒体权限（03-11 §2）",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRescan) { Text("全量重扫") }
            Button(onClick = onRetry) { Text("重试失败项") }
        }
        if (storages.isEmpty()) {
            Text("还没有数据源，点上方按钮添加本地目录或导入媒体库")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(storages, key = { it.id }) { storage ->
                    ListItem(
                        headlineContent = {
                            Text(storage.remark.ifBlank { storage.mountPath }, maxLines = 1)
                        },
                        supportingContent = {
                            val status = if (storage.isWork) "正常" else "不可用 ${storage.errorMsg}"
                            Text("${storage.driverType} · ${storage.mountPath} · $status", maxLines = 2)
                        },
                        leadingContent = { Text("☁") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** §61.119（M3）：歌单页——创建 / 播放 / 删除闭环（PlaylistRepository 契约不变）。 */
@Composable
private fun PlaylistsScreen(
    playlists: List<Playlist>,
    onCreate: (String) -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("歌单 · ${playlists.size}", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新歌单名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = newName.isNotBlank(), onClick = {
                onCreate(newName.trim())
                newName = ""
            }) { Text("创建") }
        }
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有歌单；在曲库行点「＋」加入曲目时会自动创建", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1) },
                        leadingContent = { Text("☰") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("▶", modifier = Modifier.clickable { onPlay(playlist.id) })
                                Text("🗑", modifier = Modifier.clickable { onDelete(playlist.id) })
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Track>,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("搜索标题 / 艺人 / 专辑") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            query.isBlank() -> Text("输入关键字开始搜索（TrackRepository.search）")
            results.isEmpty() -> Text("无匹配结果")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id.value }) { track ->
                    TrackRow(track)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(queue: List<QueueItem>, onJump: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("队列 · ${queue.size}（点曲目跳播）", style = MaterialTheme.typography.titleMedium)
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("队列为空（播放后由 QueueRepository.observe() 提供）", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(queue, key = { "${it.queueId}-${it.position}" }) { item ->
                    ListItem(
                        headlineContent = { Text("#${item.position} · ${item.trackId.value}", maxLines = 1) },
                        supportingContent = { Text("来源：${item.sourceRef}", maxLines = 1) },
                        leadingContent = { Text("☷") },
                        modifier = Modifier.clickable { onJump(item.position) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    runtime: RuntimeState,
    wifiOnly: Boolean,
    onRescan: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenOutput: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    scanMinDurationMs: Long,
    onScanMinDurationChanged: (Long) -> Unit,
    scanIgnoreDirs: List<String>,
    onScanIgnoreDirsChanged: (List<String>) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("V0.1 运行态", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("播放内核") },
            // §61.108 口径修缮：当前生效内核是 B2 开源引擎舱（11-11 换绑，12 §61.65~61.66）；
            // EngineAdapter 仅是 A 路线回退实现，不再作为生产绑定表述。
            supportingContent = { Text("Media3EngineAdapter（B2 开源引擎；11-11 换绑，12 §61.65~61.66）") },
        )
        ListItem(
            headlineContent = { Text("本地流代理") },
            supportingContent = { Text(if (runtime.proxyRunning) "运行中" else "按需启动") },
        )
        ListItem(
            headlineContent = { Text("最近生效副作用") },
            supportingContent = { Text(runtime.lastEffect.ifBlank { "无" }) },
        )
        // 01 §6.1 / 03-11 §4：默认仅 WiFi 播放网络音源；打开开关即允许移动数据播放。
        // 写入 SettingsStore 后由 Flow 即时生效，下一次播放就按新值裁决（不重启）。
        ListItem(
            headlineContent = { Text("允许移动数据播放") },
            supportingContent = {
                Text(
                    if (wifiOnly) "默认仅 WiFi 播放网络音源（01 §6.1）；本地曲目不受影响"
                    else "移动网络下也可播放网络音源",
                )
            },
            trailingContent = {
                Switch(checked = !wifiOnly, onCheckedChange = { onWifiOnlyChanged(!it) })
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenSources) { Text("数据源管理") }
            Button(onClick = onOpenOutput) { Text("输出设备") }
        }
        Button(onClick = onRescan) { Text("触发全量重扫（refresh=true）") }

        // ── §61.116 维护段（M6-3 / M2-2）─────────────────────────────────
        Text("维护", style = MaterialTheme.typography.titleMedium)
        var cacheMsg by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onClearCache()
                cacheMsg = "清理指令已执行（播放中会被拒绝）；结果见 logcat CloudMusicRuntime"
            }) { Text("清理播放缓存") }
        }
        if (cacheMsg.isNotBlank()) {
            Text(cacheMsg, style = MaterialTheme.typography.bodySmall)
        }
        Text("扫描最短时长（下次扫描生效，短于该值的音频不入曲库）",
             style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5_000L to "5s", 10_000L to "10s", 30_000L to "30s", 60_000L to "60s")
                .forEach { (ms, label) ->
                    FilterChip(
                        selected = scanMinDurationMs == ms,
                        onClick = { onScanMinDurationChanged(ms) },
                        label = { Text(label) },
                    )
                }
        }
        var ignoreDirsText by remember(scanIgnoreDirs) {
            mutableStateOf(scanIgnoreDirs.joinToString(", "))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ignoreDirsText,
                onValueChange = { ignoreDirsText = it },
                label = { Text("忽略目录（逗号分隔）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onScanIgnoreDirsChanged(
                        ignoreDirsText.split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                    )
                },
            ) { Text("应用") }
        }

        Text(
            "设置写入由 SettingsStore 返回 effects，PlaybackRuntime 是唯一执行者（08 §7 / 12 §31）。" +
                "照搬播放页属 T-L L3，真机验收属 T-V，本页不伪造已接通。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 03-10 §11 #8：蓝牙只作状态提示，不列为与本机/DLNA 并列的可选输出。 */
@Composable
private fun OutputDialog(
    state: OutputDeviceState,
    onOutputSelected: (ActiveOutput) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输出设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "当前：${if (state.activeOutput == ActiveOutput.LOCAL) "本机" else "DLNA"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                ListItem(
                    headlineContent = { Text("本机") },
                    supportingContent = { Text("手机扬声器 / 有线输出") },
                    modifier = Modifier.clickable { onOutputSelected(ActiveOutput.LOCAL) },
                )
                ListItem(
                    headlineContent = { Text("DLNA") },
                    supportingContent = { Text("投屏到局域网设备（发现 → 推流起播，03-08 §10.1）") },
                    modifier = Modifier.clickable { onOutputSelected(ActiveOutput.DLNA) },
                )
                if (state.routeHint.isNotBlank()) {
                    Text("状态提示：${state.routeHint}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "§61.110：切换 / 推流已接线（第一个生产调用点），本机静音随切换联动" +
                        "（T09 / 03-08 §14 #9 成对契约）；断连回落、DMR 进度轮询、" +
                        "切歌保持投屏属 DLNA 会话引擎（挂账）。" +
                        "蓝牙仅显示连接状态、不作为并列输出（03-10 §11 #8）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
