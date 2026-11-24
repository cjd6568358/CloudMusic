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

/** §61.137 搜索页（asc 搜索框 + asa 结果 + cvq chip 样式预留）：取消清词、框内 ✕。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
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

