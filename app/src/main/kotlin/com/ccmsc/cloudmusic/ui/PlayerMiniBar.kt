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
internal fun MiniPlayer(
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
internal fun MiniBarButton(icon: FnIcon, description: String, onClick: () -> Unit) {
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

