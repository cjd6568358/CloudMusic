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

/** §61.137 歌单页（cvn 分组标题 + cvm 60dip 行 + ⋮ more-ops 面板）。 */
@Composable
internal fun PlaylistsScreen(
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
    /** §61.327（A-7）：当前播放曲目 id——曲目行据此点亮「正在播放」。 */
    nowPlayingId: TrackId? = null,
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
    // §61.286：待确认的「从歌单移除」目标（曲目行 ✕ 先落这里，弹层确认后才真删）。
    var removeTarget by remember { mutableStateOf<PendingItemRemoval?>(null) }
    // §61.284（B07 收口）：展开歌单的曲目要作为父 LazyColumn 的独立 item 发出（懒加载），
    // 而 `LazyListScope` 里不能 collectAsState → 曲目流提升到列表作用域外，且只在有展开项
    // 时订阅（收起即取消，不常驻；与 §61.140 首曲封面的生命周期口径一致）。
    val expandedTracks by remember(expandedId) {
        expandedId?.let { id -> observeTracks(id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // §61.321（R3）：智能歌单一律按曲库变化算一次，`remember` 住。
    // 此前 `SmartPlaylist.evaluate(allTracks, rule)` 写在 items 的 content lambda 里，
    // 每个预设每重组一次就全表过滤一遍（5 × N），滚动 / 任意根重组都会重跑。
    val smartHits = remember(allTracks) {
        SmartPlaylist.PRESETS.associateWith { SmartPlaylist.evaluate(allTracks, it) }
    }
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
            // §61.387：「（实时规则）」是开发者措辞，用户不需要知道它怎么算的。
            item { FnSectionTitle("智能歌单 · ${SmartPlaylist.PRESETS.size}") }
            items(SmartPlaylist.PRESETS, key = { it.name }) { rule ->
                // §61.321：命中集来自上面 remember 住的表，item 重组只做一次查表。
                val hits = smartHits[rule].orEmpty()
                FnPlaylistRow(
                    // §61.369：名字不再挂 "✧ " 字符前缀——占位从字形换成矢量图标后，
                    // 前缀既无意义又与图标重复；缺封面即显示歌单图标。
                    name = rule.name,
                    placeholderIcon = FnIcon.Playlist,
                    // 0 首仍写「点击播放」是假承诺：点了没有任何反应，用户只会以为按钮坏了。
                    subtitle = if (hits.isEmpty()) {
                        "命中 0 首 · 暂无可播放曲目"
                    } else {
                        "命中 ${hits.size} 首 · 点击播放"
                    },
                    onClick = {
                        if (hits.isNotEmpty()) {
                            onPlaySmart(hits)
                        } else {
                            context.showFeedback("「${rule.name}」当前没有命中的曲目")
                        }
                    },
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
            // §61.284（B07 收口）：歌单行与「展开后的曲目行」都发成父列表的独立 item。
            // 此前整段曲目塞在一个 item 的 Column 里 → 展开 2000 首就一次性全量组合。
            // 展开态统一读列表作用域外的 expandedTracks（LazyListScope 内不能 collectAsState）。
            playlists.forEach { playlist ->
                item(key = "pl-" + playlist.id) {
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
                        // §61.369：自建歌单没有首曲封面时，占位同样是歌单图标（不再是 ☰ 字形）。
                        placeholderIcon = FnIcon.Playlist,
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
                }
                if (expandedId == playlist.id) {
                    if (expandedTracks.isEmpty()) {
                        item(key = "pl-" + playlist.id + "-empty") {
                            Text(
                                "（空歌单——在音乐库曲目行点「＋」加入）",
                                color = FnComp.TextHint,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 78.dp, bottom = 6.dp),
                            )
                        }
                    }
                    itemsIndexed(
                        expandedTracks,
                        // 同一首歌可在歌单里重复出现（位置即身份）→ key 必须带序号。
                        key = { index, track ->
                            "pl-" + playlist.id + "-" + index + "-" + track.id.value
                        },
                    ) { index, track ->
                        FnTrackRow(
                            title = "${index + 1}. ${track.title}",
                            // §61.275：艺人/专辑缺失不渲染空段
                            subtitle = TrackInfo.listSubtitle(track),
                            coverRef = track.coverRef,
                            dimmed = track.availability != Availability.OK,
                            badge = unavailableBadge(track),
                            // §61.327（A-7）：歌单里同一首可重复出现，位置即身份——
                            // 命中当前曲的**每一行**都点亮（不是只亮第一行）。
                            playing = track.id == nowPlayingId,
                            onClick = { playOrExplain(context, track) {} },
                            trailing = {
                                // §61.286：三键独立 40dp 槽位（原 6dp padding 文字只有 ~26dp，
                                // 紧邻排列极易误触）；✕ 不再单击即删，走二次确认。
                                PlaylistRowActions(
                                    canUp = index > 0,
                                    canDown = index < expandedTracks.size - 1,
                                    onUp = { onMoveItem(playlist.id, index, index - 1) },
                                    onDown = { onMoveItem(playlist.id, index, index + 1) },
                                    onRemove = {
                                        removeTarget = PendingItemRemoval(
                                            playlistId = playlist.id,
                                            index = index,
                                            title = track.title,
                                        )
                                    },
                                )
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
                if (name.isNotBlank()) {
                    val trimmed = name.trim()
                    onCreate(trimmed)
                    context.showFeedback(FeedbackText.createdPlaylist(trimmed))
                }
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
                if (name.isNotBlank()) {
                    val trimmed = name.trim()
                    onRename(target, trimmed)
                    context.showFeedback(FeedbackText.renamedPlaylist(trimmed))
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    // §61.286：从歌单移除曲目同样二次确认——与「删除歌单」 (§61.121) 同一口径。
    // 此前 ✕ 单击即移除：2,000 首歌单里丢了位置就找不回来（移除不可撤销），
    // 且触控目标只有 ~26dp 又与 ↑/↓ 紧邻，误触概率不低。
    removeTarget?.let { target ->
        FnSheetDialog(
            onDismiss = { removeTarget = null },
            title = "从歌单移除「${target.title}」？",
            content = {
                Text(
                    "只从歌单移除，曲库文件不受影响；移除后顺序不可撤销。",
                    color = FnComp.TextHint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            },
            actions = listOf(
                "移除" to {
                    onRemoveItem(target.playlistId, target.index)
                    removeTarget = null
                    context.showFeedback(FeedbackText.removedFromPlaylist(target.title))
                },
                "取消" to { removeTarget = null },
            ),
            destructiveIndexOf = 0,
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
                    context.showFeedback(FeedbackText.deletedPlaylist(target.name))
                },
                "取消" to { deleteTarget = null },
            ),
            destructiveIndexOf = 0,
        )
    }
}

/**
 * §61.286：歌单曲目行操作槽边长。
 *
 * 40dp 与飞牛顶栏圆钮同量值；旧实现是 14sp 文字 + 6dp padding（≈26dp），
 * 三个键紧邻排列，移动端误触率高（尤其 ✕ 是破坏性操作）。
 */
internal const val PlaylistActionSlotDp = 40

/** §61.286：待确认的「从歌单移除」目标（位置即身份，故存 index 而非 trackId）。 */
private data class PendingItemRemoval(val playlistId: String, val index: Int, val title: String)

/**
 * 歌单曲目行尾部三键（上移 / 下移 / 移除）。
 *
 * §61.286：越界方向 [canUp]/[canDown] 为 false 时置灰且不响应——旧实现点了没反应，
 * 用户看不出是「到头了」还是「点空了」。
 */
@Composable
internal fun PlaylistRowActions(
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlaylistActionSlot("↑", canUp, "上移", onUp)
        PlaylistActionSlot("↓", canDown, "下移", onDown)
        PlaylistActionSlot("✕", true, "从歌单移除", onRemove)
    }
}

@Composable
internal fun PlaylistActionSlot(
    glyph: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(PlaylistActionSlotDp.dp)
            .clip(CircleShape)
            // §61.290：↑↓✕ 三个字形无语义，TalkBack 会读「向上箭头 / 乘号」；
            // 越界置灰时还要播报「不可用」，否则用户会一直点。
            .semantics { contentDescription = description }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) FnComp.TextSecondary else FnComp.TextHint,
            fontSize = 14.sp,
        )
    }
}

/** 建单/重命名共用输入面板（飞牛卡片弹层 + FnDialogInput，§61.277）。 */
@Composable
internal fun CreatePlaylistSheet(
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
        // §61.293：建单/重命名弹层只为输入而开——直接聚焦，回车收键盘。
        content = { FnDialogInput(text, { text = it }, hint = hint, autoFocus = true) },
        actions = listOf(confirmLabel to { onConfirm(text) }),
    )
}

/** §61.119 加入歌单面板：已有歌单行 + 新建内联。 */
@Composable
internal fun AddToPlaylistSheet(
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
                            .heightIn(min = 50.dp)
                            .clickable { onPick(pl.id) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(pl.name, color = FnComp.TextPrimary, fontSize = 16.sp, maxLines = 1)
                    }
                }
                FnDialogInput(newName, { newName = it }, hint = "新歌单名", autoFocus = true)
            }
        },
        actions = listOf("建并加入" to {
            if (newName.isNotBlank()) onCreateAndAdd(newName.trim())
        }),
    )
}

