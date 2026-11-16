package com.example.cloudmusic.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cloudmusic.model.LrcParser
import com.example.cloudmusic.model.Lyrics
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.storage.LyricFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `12 §61.174` ⑨：**按墨迹居中**的文本样式。
 *
 * Compose `Text` 默认带字体额外内边距（`includeFontPadding = true`），落在固定行框里时
 * 墨带顶会比「(行高-墨高)/2」的居中模型再往下约 4dp —— 真机上时间行实测偏低 4.4dp，
 * 与基准图逐 dp 比对属系统性偏差。标题行用的平台 `TextView` 早已关掉该留白（实测精准命中），
 * 这里把纯 Compose 的艺人行 / 时间行对齐到同一口径。
 */
private val InkCenteredStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * `03-10` 用户决策 ①：播放页整页参考竞品（View 体系 + 自定义唱片 View），
 * **其余页面纯 Compose**，局部混用两套 UI 框架被限制在播放页一屏。
 *
 * `12 §61.42`（用户 2026-11-09 口径）：不搬竞品代码，严格参考竞品 UI / 风格 / 交互
 * 自己实现一套；**能复用的素材一律复用竞品，避免色差**。本页所有色值 / 尺寸 / 图标
 * 均取自 [LegacyPalette] / [LegacyMetrics] / [PlayerBaseline] / [LegacyAsset]，
 * 不自行配色。
 *
 * `12 §61.174`（播放页整体 1:1，基准
 * `analysis/l3-reference/competitor-player-baseline.png` 1440×3200 @3.5）：
 * 整页按**基准图逐像素测量**的纵向刻度重排（常量见 [PlayerBaseline]，可复跑证据
 * `tools/measure_player_baseline.py`）：
 *
 * | 区段 | 基准绝对 y (dp) | 实现 |
 * |---|---|---|
 * | 内容顶（状态栏底） | 37 | Column 起点 |
 * | 顶栏文字带 | 51 ~ 70 | 46dp 顶栏，内容居中 |
 * | 唱片 | 236 ~ 550（直径 311 = 0.757W） | 唱片区 weight + 底部对齐 |
 * | 标题文字带 | 602 ~ 619 | 32dp 行，24sp |
 * | 艺人 | 623 ~ 645 | 17dp 行，12sp |
 * | 进度条轨道 | 676 | 自绘 Canvas（2dp 轨 + 5dp 白点） |
 * | 时间行 | 690 ~ 699 | 11sp，轨道下方一行三槽 |
 * | 主控制行 | 744 ~ 774 | 5 等分 SpaceEvenly |
 * | 副控制行 | 820 ~ 837 | 5 等分 SpaceEvenly |
 *
 * `12 §61.163` 封面驱动背景（用户铁律）：`dominantCoverColor` 从当前封面取主色 →
 * HSV(S×0.8, V×1.3) → 顶端；底端 = 顶端 ×0.515（2026-11-16 重新按基准图**页底**
 * 采样修正，旧值 0.56 采的是页面上部 81% 处的渐变中段）。
 *
 * **不伪造边界（诚实优先于像素）**：基准图中的「422人」在线数、心形 `5w+` /
 * 评论 `226` 计数、「关注」按钮、「极高音质」标签、私人漫游标题、cast 图标等
 * **生产侧无真实数据源或属 P5 冻结域**，一律省略或以只读几何呈现，**不造数**；
 * 闭包外具象图标（灯泡 / 鲸鱼）按基准轮廓用极简几何近似并挂账（`12 §61.174`）。
 *
 * `12 §61.40` 交互复刻（参数逐项来自实证，不改动）：唱片旋转 / 唱针起落 →
 * [VinylDiscView]；标题跑马灯 → 平台 `TextView` MARQUEE；左右滑动切歌 → 72dp；
 * 进度条 / 播放 / 上下曲 → `PlayerStateBridge` 单向绑定；音效开合 → 桥接
 * `onEffect`（§61.93 audiofx，本页为其第一个生产 UI 入口）；
 * **点击唱片区切「唱片视图 ↔ 歌词视图」**（竞品同一交互，§61.174 新增），
 * 歌词默认不占基准布局，保证唱片形态严格像素级。
 * 本页不新建模块、不新增依赖、不动 assets / Manifest / 权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyPlayerPage(
    bridge: PlayerStateBridge,
    onClose: () -> Unit,
    /** §61.192（W2）：输出/DLNA 入口——副控制行「更多」位接输出弹窗（本机 / DLNA 投屏）。 */
    onOpenOutput: () -> Unit = {},
) {
    val state by bridge.state.collectAsState()
    val track by bridge.track.collectAsState(initial = null)
    val durationMs by bridge.durationMs.collectAsState(initial = 0L)
    val mode by bridge.playMode.collectAsState(initial = PlayMode.SEQUENCE)
    val playing = state.phase == PlayerPhase.PLAYING

    // §61.174：点击唱片区在「唱片 / 歌词」之间切换（竞品同一交互）。
    var showLyrics by remember { mutableStateOf(false) }

    // §61.109 ②：真实封面（`Track.coverRef` = 本地文件路径，BridgeModule.readCover 同源）；
    // 解码失败 / 无封面 → null（唱片回退竞品 g9f、背景回退 #262626）。
    val coverPath = track?.coverRef
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverPath) {
        coverBitmap = withContext(Dispatchers.IO) {
            coverPath?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }
    }
    // 封面主色 → 背景渐变（严格像素级校准，见 dominantCoverColor KDoc）：
    // 竞品背景随当前封面自动生成，端值由竞品真机基准图反推（§0 用户拍板铁律）。
    val bgTop = remember(coverBitmap) { dominantCoverColor(coverBitmap) ?: LegacyPalette.PageBackground }
    val bgBottom = remember(bgTop) { shadeBottom(bgTop) }

    // docs/01 §0（2026-11-16 拍板）：歌词本地优先。扫描期内嵌 USLT 写盘到
    // cacheDir/lyrics/<trackId>.lrc，这里按当前曲目读取 + LrcParser 解析；
    // 无歌词文件时 Lyrics.EMPTY → 歌词视图显示占位，不造数。
    val context = LocalContext.current
    val trackId = track?.id
    var lyrics by remember { mutableStateOf(Lyrics.EMPTY) }
    LaunchedEffect(trackId) {
        lyrics = withContext(Dispatchers.IO) {
            val id = trackId ?: return@withContext Lyrics.EMPTY
            val raw = LyricFileStore(context.cacheDir).readRaw(id)
            raw?.let { LrcParser.parse(it) } ?: Lyrics.EMPTY
        }
    }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { LegacyMetrics.SwipeThresholdDp.dp.toPx() }

    // §61.118（T5-1）：均衡器弹窗开关。
    var showEqualizer by remember { mutableStateOf(false) }
    // §61.130（M5）：收藏集合（♡ 可交互——FavoriteRepository.toggle 生产入口）。
    val favoriteIds by bridge.observeFavorites().collectAsState(initial = emptySet())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(bgTop), Color(bgBottom)))),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶栏（§61.174，基准 y 51~70）：下箭头（收起）/ 居中「来源 · 模式」/
            //    右侧换一批（竞品 cast/刷新位；漫游无数据源 → 只读几何） ────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.TopBarHeightDp.dp)
                    .padding(horizontal = PlayerBaseline.TopBarSidePaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegacyIconButton(
                    assetPath = LegacyAsset.BackArrow,
                    description = "收起播放页",
                    sizeDp = PlayerBaseline.TopBarIconDp,
                    rotateDegrees = 270f,
                    onClick = onClose,
                )
                Text(
                    text = "本地播放 · ${modeLabel(mode)}",
                    fontSize = PlayerBaseline.TopBarTitleSp.sp,
                    color = Color(LegacyPalette.TextSecondary),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(PlayerBaseline.TopBarIconDp.dp),
                    contentAlignment = Alignment.Center,
                ) { RefreshGlyph() }
            }

            // ── 唱片区（§61.174，基准 y 83~550 = 467dp）：唱片底部对齐，
            //    上方 153dp 空档容纳唱针轴心与弧杆；整块可点/可横滑 ────────────────
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (showLyrics) {
                    LyricsPanel(
                        lyrics = lyrics,
                        positionMs = state.positionMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AndroidView(
                        factory = { ctx -> VinylDiscView(ctx) },
                        update = {
                            it.setPlaying(playing)
                            it.setCover(coverBitmap)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 透明覆盖层：单击切「唱片/歌词」，横向拖动切歌。
                // 与 AndroidView 内部 View 的触摸派发不冲突（手势在 Compose 侧统一处理）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showLyrics = !showLyrics })
                        }
                        .pointerInput(swipeThresholdPx) {
                            var accumulated = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = { accumulated = 0f },
                                onDragCancel = { accumulated = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    accumulated += dragAmount
                                    when {
                                        accumulated > swipeThresholdPx -> {
                                            bridge.onPrev()
                                            accumulated = 0f
                                        }
                                        accumulated < -swipeThresholdPx -> {
                                            bridge.onNext()
                                            accumulated = 0f
                                        }
                                    }
                                },
                            )
                        },
                )
            }

            // ── 标题行（§61.174，基准字带 602.3~618.3；行框 598.3~622.3，文字居中） ──
            Spacer(Modifier.height(PlayerBaseline.GapDiscToTitleDp.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.TitleRowHeightDp.dp)
                    .padding(horizontal = PlayerBaseline.SidePaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 竞品 `MarqueTextView`：单行、超长滚动、无限循环，用平台原生 marquee；
                // 字体用 D-146 导出的 `legacy_a.ttf`（Dolphin Bold）。
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.MARQUEE
                            marqueeRepeatLimit = -1
                            isSelected = true
                            gravity = Gravity.CENTER_VERTICAL
                            // 关掉字体额外内边距，让字带在行框内**严格居中**（§61.174 ②）：
                            // 居中后 ink 顶 = 行框顶 + (行高 - 墨高)/2，可与基准逐 dp 对齐。
                            includeFontPadding = false
                            setTextColor(LegacyPalette.TextPrimary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, PlayerBaseline.TitleSp)
                            loadLegacyTypeface(ctx)?.let { typeface = it }
                        }
                    },
                    update = { it.text = track?.title ?: "未在播放" },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                // §61.130（M5）：收藏切换——FavoriteRepository.toggle 的生产入口。
                val isFav = track?.id in favoriteIds
                LegacyIconButton(
                    assetPath = LegacyAsset.Favorite,
                    description = if (isFav) "取消收藏" else "收藏",
                    sizeDp = LegacyMetrics.InfoRowIconDp,
                    onClick = { track?.id?.let { bridge.onToggleFavorite(it) } },
                )
            }

            // ── 艺人行（§61.174，基准字带 632.3~645.1；「关注」胶囊无数据源故省略） ──
            Spacer(Modifier.height(PlayerBaseline.GapTitleToArtistDp.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.ArtistRowHeightDp.dp)
                    .padding(horizontal = PlayerBaseline.SidePaddingDp.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = track?.artist ?: "",
                    fontSize = PlayerBaseline.ArtistSp.sp,
                    color = Color(LegacyPalette.TextSecondary),
                    maxLines = 1,
                    style = InkCenteredStyle,
                )
            }

            // ── 进度条（§61.174 ④，基准轨道 y=676 / 左右边距 30dp / 轨高 2dp / 点 5dp） ──
            Spacer(Modifier.height(PlayerBaseline.GapArtistToProgressDp.dp))
            BaselineProgressBar(
                positionMs = state.positionMs,
                durationMs = durationMs,
                onSeek = { bridge.onSeek(it) },
            )
            Spacer(Modifier.height(PlayerBaseline.GapProgressToTimeDp.dp))

            // ── 时间行（§61.174，基准 690~699）：「极高音质」无质量元数据省略，中槽留空 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.TimeRowHeightDp.dp)
                    .padding(horizontal = PlayerBaseline.ProgressSidePaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatMs(state.positionMs),
                    fontSize = PlayerBaseline.TimeSp.sp,
                    color = Color(LegacyPalette.IconTint),
                    style = InkCenteredStyle,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                Text(
                    formatMs(durationMs),
                    fontSize = PlayerBaseline.TimeSp.sp,
                    color = Color(LegacyPalette.IconTint),
                    style = InkCenteredStyle,
                )
            }

            // ── 主控制行（§61.174，基准 744~774）：5 等分 SpaceEvenly ────────────────
            Spacer(Modifier.height(PlayerBaseline.GapTimeToMainControlDp.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.MainControlIconDp.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 模式切换生产侧无后端，只读展示（`12 §61.40` 挂账）。
                LegacyIconButton(
                    assetPath = LegacyAsset.ModeLoop,
                    description = "播放模式：${modeLabel(mode)}（只读）",
                    sizeDp = PlayerBaseline.MainControlSideDp,
                )
                LegacyIconButton(
                    assetPath = LegacyAsset.Prev,
                    description = "上一首",
                    sizeDp = PlayerBaseline.MainControlIconDp,
                    onClick = bridge::onPrev,
                )
                LegacyPlayPauseButton(playing, bridge::onPlayPause)
                LegacyIconButton(
                    assetPath = LegacyAsset.Next,
                    description = "下一首",
                    sizeDp = PlayerBaseline.MainControlIconDp,
                    onClick = bridge::onNext,
                )
                Box(
                    modifier = Modifier.size(PlayerBaseline.MainControlSideDp.dp),
                    contentAlignment = Alignment.Center,
                ) { QueueGlyph() }
            }

            // ── 副控制行（§61.174，基准 820~837）：5 个位置严格对齐，音效为真实素材
            //    + §61.93 接线；灯泡 / 鲸鱼 / info / more 为闭包外极简几何（挂账） ──────
            Spacer(Modifier.height(PlayerBaseline.GapMainToSecondaryDp.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerBaseline.SecondaryControlDp.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegacyIconButton(
                    assetPath = LegacyAsset.SoundEffect,
                    // §61.118（T5-1）：分频段 EQ 弹窗（档位循环升级为完整均衡器）。
                    description = "音效 / 均衡器",
                    sizeDp = PlayerBaseline.SecondaryControlDp,
                    onClick = { showEqualizer = true },
                )
                Box(
                    modifier = Modifier.size(PlayerBaseline.SecondaryControlDp.dp),
                    contentAlignment = Alignment.Center,
                ) { BulbGlyph() }
                Box(
                    modifier = Modifier.size(PlayerBaseline.SecondaryControlDp.dp),
                    contentAlignment = Alignment.Center,
                ) { WhaleGlyph() }
                Box(
                    modifier = Modifier.size(PlayerBaseline.SecondaryControlDp.dp),
                    contentAlignment = Alignment.Center,
                ) { InfoGlyph() }
                Box(
                    modifier = Modifier
                        .size(PlayerBaseline.SecondaryControlDp.dp)
                        // §61.192（W2）：「更多」位 = 输出/DLNA 入口（此前极简几何无交互，
                        // 用户「说好的 DLNA 全都没有」——点击打开输出弹窗：本机 / DLNA 投屏）。
                        .clickable { onOpenOutput() },
                    contentAlignment = Alignment.Center,
                ) { MoreGlyph() }
            }
            Spacer(Modifier.height(PlayerBaseline.BottomPaddingDp.dp))
        }
    }

    if (showEqualizer) {
        EqualizerDialog(
            onDismiss = { showEqualizer = false },
            onApply = { enabled, level, bands ->
                bridge.onEqualizer(enabled, level, bands)
            },
        )
    }
}

/**
 * §61.174 ③：播放进度条——基准图形态（2dp 细轨 + 5dp 白点 + 纯白已播放段），
 * 不用 Material3 `Slider`（默认轨道 4dp、滑块 20dp，与基准差一个量级）。
 *
 * 拖动期间本地接管（`dragFraction`），松手才下发 `onSeek`，语义与 `03-10 §6` 一致：
 * 进度读 `positionMs`，拖动不被回流的 position 打断。
 */
@Composable
private fun BaselineProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerBaseline.ProgressSidePaddingDp.dp)
            .height(PlayerBaseline.ProgressTouchDp.dp)
            .pointerInput(safeDuration) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width * safeDuration).toLong())
                }
            }
            .pointerInput(safeDuration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek((dragFraction * safeDuration).toLong())
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val cy = size.height / 2f
        val trackH = PlayerBaseline.ProgressTrackDp.dp.toPx()
        val thumbR = PlayerBaseline.ProgressThumbDp.dp.toPx() / 2f
        val fraction = if (dragging) dragFraction else liveFraction

        // 未播放轨道（`legacy_b1h` 33% 白）
        drawRoundRect(
            color = Color(LegacyPalette.TrackBuffer),
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
        )
        // 已播放段（纯白）
        if (fraction > 0f) {
            drawRoundRect(
                color = Color(LegacyPalette.White),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width * fraction, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
            )
        }
        // 滑块（基准 18px = 5.1dp 白点；拖动时放大到 2 倍便于命中反馈）
        drawCircle(
            color = Color(LegacyPalette.White),
            radius = if (dragging) thumbR * 2f else thumbR,
            center = Offset(size.width * fraction, cy),
        )
    }
}

/**
 * §61.174：歌词视图（点唱片区切换）。竞品歌词页是独立 layout（P4 冻结域），
 * 这里只做「唱片区原位替换」的居中 5 行形态：当前行高亮加粗，上下各两行淡出。
 * 无歌词显示占位，不造数。
 */
@Composable
private fun LyricsPanel(
    lyrics: Lyrics,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "· 暂无歌词 ·",
                fontSize = 13.sp,
                color = Color(LegacyPalette.TextHint),
            )
        }
        return
    }
    val active = lyrics.activeIndex(positionMs)
    val visible = 5
    val start = (active - 2).coerceIn(0, (lyrics.lines.size - visible).coerceAtLeast(0))
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        lyrics.lines.drop(start).take(visible).forEachIndexed { i, line ->
            val isActive = start + i == active
            Text(
                text = line.text,
                fontSize = if (isActive) 18.sp else 15.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) Color(LegacyPalette.White) else Color(LegacyPalette.TextTertiary),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * §61.118（T5-1）：分频段均衡器弹窗——开关（audiofx 三件套）+ 预设
 * （平直/低音增强/人声/高音）+ 5 段 slider（millibel，±15dB）。
 * band 数以设备实际为准（adapter 取 min 应用）；预设表 5 段。
 */
@Composable
private fun EqualizerDialog(
    onDismiss: () -> Unit,
    onApply: (enabled: Boolean, effectLevel: Int, bands: List<Int>) -> Unit,
) {
    var enabled by remember { mutableStateOf(true) }
    var level by remember { mutableIntStateOf(2) }
    var bands by remember { mutableStateOf(listOf(0, 0, 0, 0, 0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("均衡器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (enabled) "开（档位 $level）" else "关",
                         style = MaterialTheme.typography.titleSmall)
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        onApply(enabled, level, bands)
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                        "平直" to listOf(0, 0, 0, 0, 0),
                        "低音" to listOf(500, 300, 0, 0, 0),
                        "人声" to listOf(-100, 0, 200, 300, 100),
                        "高音" to listOf(0, 0, 0, 300, 500),
                    )
                    presets.forEach { (name, table) ->
                        FilterChip(
                            selected = bands == table,
                            onClick = {
                                bands = table
                                onApply(enabled, level, table)
                            },
                            label = { Text(name) },
                        )
                    }
                }
                listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz").forEachIndexed { i, label ->
                    Text("$label  ${bands[i] / 100.0} dB", style = MaterialTheme.typography.bodySmall)
                    MaterialSlider(
                        value = bands[i].toFloat(),
                        onValueChange = { v ->
                            val stepped = ((v / 100).toInt() * 100)
                            bands = bands.toMutableList().also { it[i] = stepped }
                        },
                        onValueChangeFinished = { onApply(enabled, level, bands) },
                        valueRange = -1500f..1500f,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(enabled, level, bands)
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 竞品真实位图按钮；无 onClick 时即只读占位，不接伪造交互。 */
@Composable
private fun LegacyIconButton(
    assetPath: String,
    description: String,
    sizeDp: Float,
    rotateDegrees: Float = 0f,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = rememberLegacyImageBitmap(assetPath)
    val interactionSource = remember { MutableInteractionSource() }
    val modifier = Modifier
        .size(sizeDp.dp)
        .graphicsLayer { rotationZ = rotateDegrees }
        .let { base ->
            if (onClick == null) base else base.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 播放键复用竞品 `g_k`；暂停位图不在导出闭包内，按基准图形态自绘两条白色圆角竖条
 * （§61.174 重新量化：9.4 × 29.7dp，间距 8.6dp，圆角 2dp）。
 */
@Composable
private fun LegacyPlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(PlayerBaseline.MainControlIconDp.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(PlayerBaseline.PauseBarGapDp.dp)) {
                PauseBar()
                PauseBar()
            }
        } else {
            val bitmap = rememberLegacyImageBitmap(LegacyAsset.Play)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "播放",
                    modifier = Modifier.size(PlayerBaseline.MainControlIconDp.dp),
                )
            }
        }
    }
}

@Composable
private fun PauseBar() {
    Box(
        Modifier
            .width(PlayerBaseline.PauseBarWidthDp.dp)
            .height(PlayerBaseline.PauseBarHeightDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(LegacyPalette.White)),
    )
}

/** §61.174：队列图标（闭包外）按极简几何自绘三条横线（基准图形态），只读。 */
@Composable
private fun QueueGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
        Box(Modifier.width(12.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
        Box(Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
    }
}

/** §61.196：info 图标（圆环 + 点 + 竖 stem），24dp 线性。 */
@Composable
private fun InfoGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val c = Color(LegacyPalette.IconTint)
        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(c, radius = 10.dp.toPx(), center = Offset(cx, cy), style = s)
        drawCircle(c, radius = 1.2.dp.toPx(), center = Offset(cx, cy - 4.5f.dp.toPx()))
        drawLine(c, Offset(cx, cy - 0.5f.dp.toPx()), Offset(cx, cy + 5.dp.toPx()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** §61.196：more 图标（横向三圆点，间距 7dp）。 */
@Composable
private fun MoreGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(3.2f.dp)
                    .clip(CircleShape)
                    .background(Color(LegacyPalette.IconTint)),
            )
        }
    }
}

/** §61.174：顶栏「换一批」图标（基准 19.4dp 圆头箭头）；漫游无数据源 → 只读。 */
@Composable
private fun RefreshGlyph() {
    Canvas(modifier = Modifier.size(PlayerBaseline.TopBarIconDp.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension * 0.34f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = Color(LegacyPalette.IconTint),
            startAngle = -60f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // 箭头：弧起点处的小三角（近似基准图「↻」的收口）
        val tipAngle = Math.toRadians(-60.0)
        val tip = Offset(
            c.x + r * Math.cos(tipAngle).toFloat(),
            c.y + r * Math.sin(tipAngle).toFloat(),
        )
        drawLine(
            color = Color(LegacyPalette.IconTint),
            start = Offset(tip.x - stroke * 0.9f, tip.y - stroke * 1.6f),
            end = Offset(tip.x + stroke * 0.9f, tip.y + stroke * 0.4f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(LegacyPalette.IconTint),
            start = Offset(tip.x - stroke * 0.9f, tip.y - stroke * 1.6f),
            end = Offset(tip.x - stroke * 2.6f, tip.y + stroke * 0.2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * §61.196（W14）：副控制行四键按基准 `competitor-player-baseline.png` 重绘——
 * 统一 24dp 视框 / 1.6dp 圆头描边 / `IconTint` 色。旧实现 11~18dp 极简几何
 * （圆环+i 文本/小点）与竞品形态差距大（用户「丑得要命」）。
 */
@Composable
private fun BulbGlyph() {
    // 灯泡：玻璃弧（上半圆）+ 收颈两条斜线 + 底座横线
    Canvas(Modifier.size(24.dp)) {
        val c = Color(LegacyPalette.IconTint)
        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f - 1.dp.toPx()
        drawArc(
            color = c, startAngle = 180f, sweepAngle = 180f, useCenter = false, style = s,
            topLeft = Offset(cx - 6.dp.toPx(), cy - 6.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 12.dp.toPx()),
        )
        drawLine(c, Offset(cx - 6.dp.toPx(), cy), Offset(cx - 3.dp.toPx(), cy + 4.dp.toPx()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c, Offset(cx + 6.dp.toPx(), cy), Offset(cx + 3.dp.toPx(), cy + 4.dp.toPx()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c, Offset(cx - 3.dp.toPx(), cy + 5.5f.dp.toPx()), Offset(cx + 3.dp.toPx(), cy + 5.5f.dp.toPx()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** §61.196：鲸鱼（弧背 + 尾鳍双叉 + 腹线），24dp 线性。 */
@Composable
private fun WhaleGlyph() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val c = Color(LegacyPalette.IconTint)
        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        // 身体弧（背）
        drawArc(
            color = c, startAngle = 200f, sweepAngle = 150f, useCenter = false, style = s,
            topLeft = Offset(w * 0.08f, h * 0.22f),
            size = Size(w * 0.82f, h * 0.58f),
        )
        // 尾鳍（右上双叉）
        drawLine(c, Offset(w * 0.88f, h * 0.38f), Offset(w * 0.98f, h * 0.20f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.88f, h * 0.38f), Offset(w * 0.99f, h * 0.50f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        // 腹线（前下短弧）
        drawArc(
            color = c, startAngle = 30f, sweepAngle = 60f, useCenter = false, style = s,
            topLeft = Offset(w * 0.12f, h * 0.30f),
            size = Size(w * 0.62f, h * 0.50f),
        )
    }
}

/**
 * 播放页背景顶端色：从**当前歌曲封面**自动取色生成（§0 用户拍板铁律，封面驱动不可改为固定色）。
 *
 * 严格像素级标定（2026-11-16）：以竞品真机基准图 `competitor-player-baseline.png` 与其
 * 同曲封面 `competitor-cover-extract.png` 对照反推。封面主色 (16×16 → 饱和度×频次加权)
 * 得 (18,55,78)；竞品背景顶端实测 (40,79,103)。二者同色相 (H≈203°)，
 * 竞品对主色做 HSV 变换：饱和度 ×0.8（夹 [0.35,0.65]）、明度 ×1.3（夹 [0.32,0.45]），
 * 复算得 (39,78,102)，与基准误差 ≤1；背景底端（基准**页底** y=2900px）实测 (20,40,52)
 * = 顶端 ×0.515，复算 (19,39,51)，误差 ≤1。
 *
 * 失败 / 无封面返回 null → 调用方回退默认深色。
 */
private fun dominantCoverColor(cover: Bitmap?): Int? {
    val src = cover ?: return null
    return runCatching {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        var best = 0
        var bestScore = -1f
        val counts = HashMap<Int, Int>(64)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        for ((c, n) in counts) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0f else (max - min) / max.toFloat()
            val score = sat * n + n * 0.05f
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        val r = (best shr 16) and 0xFF
        val g = (best shr 8) and 0xFF
        val b = best and 0xFF
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        hsv[1] = (hsv[1] * 0.8f).coerceIn(0.35f, 0.65f)
        hsv[2] = (hsv[2] * 1.3f).coerceIn(0.32f, 0.45f)
        android.graphics.Color.HSVToColor(hsv)
    }.getOrNull()
}

/** 背景底端色：竞品页底实测 = 顶端 ×0.515（逐通道，误差 ≤1）。 */
private fun shadeBottom(topArgb: Int): Int {
    val r = (((topArgb shr 16) and 0xFF) * 0.515f).toInt().coerceIn(0, 255)
    val g = (((topArgb shr 8) and 0xFF) * 0.515f).toInt().coerceIn(0, 255)
    val b = ((topArgb and 0xFF) * 0.515f).toInt().coerceIn(0, 255)
    return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun modeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENCE -> "顺序"
    PlayMode.SINGLE_LOOP -> "单曲"
    PlayMode.LIST_LOOP -> "循环"
    PlayMode.SHUFFLE -> "随机"
}
