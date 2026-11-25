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

/** §61.137 队列页（dialog_music_list/a6h 风格基准）：标题栏 + 序号行，点击跳播。 */
@Composable
internal fun QueueScreen(
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

