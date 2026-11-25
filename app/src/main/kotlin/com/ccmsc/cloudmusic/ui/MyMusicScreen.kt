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

/**
 * §61.130（M5 我的音乐）：收藏 + 最近播放两段（FavoriteRepo/HistoryRepo 全收口）。
 * §61.137 视觉：44dip 页头 + cvn 分组标题 + cvg 曲目行。
 * §61.277（V0.4-04）：页头/标题/行改飞牛骨架。
 */
@Composable
internal fun MyMusicScreen(
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
            // §61.370：两段标题**常驻**，空态各自分段。此前空库时只有一块合并空态
            // 「收藏曲目或播放曲目后在此显示」——从首页「收藏」卡或「最近」卡进来的用户，
            // 既看不出自己落在哪一段，也看不出这里本该有两段。
            item(key = "mm-fav-title") { FnSectionTitle("收藏 · ${favTracks.size}") }
            if (favTracks.isEmpty()) {
                item(key = "mm-fav-empty") {
                    FnEmpty("还没有收藏的曲目：在曲目行的心形上点一下即可收藏", Modifier.height(100.dp))
                }
            } else {
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
            // §61.328：小节标题用**总数**，与上方统计卡同口径（窗口截断不谎报）。
            item(key = "mm-hist-title") { FnSectionTitle("最近播放 · $historyCount") }
            if (history.isEmpty()) {
                item(key = "mm-hist-empty") {
                    FnEmpty("还没有播放记录：播放任意曲目后在此显示", Modifier.height(100.dp))
                }
            } else {
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
