package com.example.cloudmusic.ui.player

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase

/**
 * `03-10` 用户决策 ①：播放页整页参考竞品（View 体系 + 自定义唱片 View），
 * **其余页面纯 Compose**，局部混用两套 UI 框架被限制在播放页一屏。
 *
 * `12 §61.42`（用户 2026-11-09 口径）：不搬竞品代码，严格参考竞品 UI / 风格 / 交互
 * 自己实现一套；**能复用的素材一律复用竞品，避免色差**。本页所有色值 / 尺寸 / 图标
 * 均取自 [LegacyPalette] / [LegacyMetrics] / [LegacyAsset]（一手反编译证据），
 * 不自行配色；唱片叠层按 `legacy_gc.xml` / `legacy_bv5.xml` 的 z 序重排。
 *
 * `12 §61.40` 交互复刻（参数逐项来自实证，不改动）：
 * - 唱片旋转 / 唱针起落 → [VinylDiscView]（平台属性动画，非 Compose Canvas）；
 * - 标题跑马灯 → 平台 `TextView` 的 `MARQUEE`（对应 `MarqueTextView`）；
 * - 左右滑动切歌 → `detectHorizontalDragGestures`（72dp 阈值）；
 * - 进度条 / 播放 / 暂停 / 上下曲 → `PlayerStateBridge` 单向绑定。
 *
 * **不伪造**：模式切换 / 收藏 / 歌词 / 输出切换 / 队列弹窗当前生产侧无后端，
 * 以只读或占位形态呈现；暂停位图与唱针不在竞品导出闭包内，按竞品形态自绘。
 * 挂账见 `12 §61.40` / `12 §61.42`。本页不新建模块、不新增依赖、不动 assets / Manifest / 权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyPlayerPage(
    bridge: PlayerStateBridge,
    onClose: () -> Unit,
) {
    val state by bridge.state.collectAsState()
    val track by bridge.track.collectAsState(initial = null)
    val durationMs by bridge.durationMs.collectAsState(initial = 0L)
    val queueCount by bridge.queueCount.collectAsState(initial = 0)
    val mode by bridge.playMode.collectAsState(initial = PlayMode.SEQUENCE)
    val playing = state.phase == PlayerPhase.PLAYING

    val maxMs = durationMs.coerceAtLeast(1L).toFloat()
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }

    // `03-10 §6`：进度条读 `positionMs`；拖动期间由本地 seekValue 接管，松手才下发 seek。
    LaunchedEffect(state.positionMs) {
        if (!seeking) seekValue = state.positionMs.toFloat()
    }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { LegacyMetrics.SwipeThresholdDp.dp.toPx() }

    Surface(
        color = Color(LegacyPalette.PageBackground),   // legacy_c5y.xml #ff262626
        contentColor = Color(LegacyPalette.TextPrimary),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = LegacyMetrics.PagePaddingDp.dp,
                    vertical = 12.dp,
                ),
        ) {
            // ── 顶栏：竞品 `legacy_gr.xml` ivFinish(44dip) + tvTitle(18sp) / 艺人 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegacyIconButton(
                    assetPath = LegacyAsset.BackArrow,
                    description = "返回",
                    sizeDp = LegacyMetrics.BackBarHeightDp,
                    onClick = onClose,
                )
                Column(modifier = Modifier.weight(1f)) {
                    // 竞品 `MarqueTextView`：单行、超长滚动、无限循环，用平台原生 marquee，
                    // 不自行近似动画参数；字体用 D-146 导出的 `legacy_a.ttf`（Dolphin Bold）。
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setSingleLine(true)
                                ellipsize = TextUtils.TruncateAt.MARQUEE
                                marqueeRepeatLimit = -1
                                isSelected = true
                                gravity = Gravity.CENTER_VERTICAL
                                setTextColor(LegacyPalette.TextPrimary)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, LegacyMetrics.TitleSp)
                                loadLegacyTypeface(ctx)?.let { typeface = it }
                            }
                        },
                        update = { it.text = track?.title ?: "未在播放" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = track?.artist ?: "",
                        fontSize = LegacyMetrics.TimeSp.sp,
                        color = Color(LegacyPalette.IconTint),
                        maxLines = 1,
                    )
                }
                // `03-10 §5` 队列弹窗属 V0.2 完善项，此处只读展示数量，不伪造点击。
                Text(
                    text = "$queueCount 首",
                    fontSize = LegacyMetrics.TimeSp.sp,
                    color = Color(LegacyPalette.TextHint),
                )
            }

            // ── 唱片区：属性动画唱片 + 唱针 + 手势切歌覆盖层 ───────────────────
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx -> VinylDiscView(ctx) },
                    update = { it.setPlaying(playing) },
                    modifier = Modifier.fillMaxSize(),
                )
                // 透明覆盖层只接管横向手势，避免与 AndroidView 内部 View 的触摸派发冲突。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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

            // `MiLyricViewContainer` 的占位：生产侧无歌词数据源，不伪造逐行歌词。
            Text(
                text = "· 暂无歌词 ·",
                fontSize = LegacyMetrics.TimeSp.sp,
                color = Color(LegacyPalette.TextHint),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )

            // ── 进度条：`legacy_bn5.xml` progress=#ffff3a3a / background=#1affffff /
            //    secondaryProgress=#33ffffff；时间文字 `legacy_gr.xml` 12sp @color/b1z ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatMs(state.positionMs),
                    fontSize = LegacyMetrics.TimeSp.sp,
                    color = Color(LegacyPalette.IconTint),
                )
                Slider(
                    value = seekValue.coerceIn(0f, maxMs),
                    onValueChange = { seeking = true; seekValue = it },
                    onValueChangeFinished = {
                        bridge.onSeek(seekValue.toLong())
                        seeking = false
                    },
                    valueRange = 0f..maxMs,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(LegacyPalette.PrimaryRed),
                        activeTrackColor = Color(LegacyPalette.PrimaryRed),
                        inactiveTrackColor = Color(LegacyPalette.TrackBackground),
                        activeTickColor = Color(LegacyPalette.TrackBuffer),
                        inactiveTickColor = Color(LegacyPalette.TrackBackground),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    formatMs(durationMs),
                    fontSize = LegacyMetrics.TimeSp.sp,
                    color = Color(LegacyPalette.IconTint),
                )
            }

            // ── 控制行：`legacy_are.xml` 顺序 模式(40) / 上一首(30) / 播放(36) /
            //    下一首(30) / 收藏(28)，全部用竞品导出的真实白色位图 ──────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 模式切换生产侧无后端，只读展示（`12 §61.40` 挂账）。
                LegacyIconButton(
                    assetPath = LegacyAsset.ModeLoop,
                    description = "播放模式：${modeLabel(mode)}（只读）",
                    sizeDp = LegacyMetrics.ControlModeDp,
                )
                LegacyIconButton(
                    assetPath = LegacyAsset.Prev,
                    description = "上一首",
                    sizeDp = LegacyMetrics.ControlPrevNextDp,
                    onClick = bridge::onPrev,
                )
                LegacyPlayPauseButton(playing, bridge::onPlayPause)
                LegacyIconButton(
                    assetPath = LegacyAsset.Next,
                    description = "下一首",
                    sizeDp = LegacyMetrics.ControlPrevNextDp,
                    onClick = bridge::onNext,
                )
                // 收藏：无收藏字段 / Repository，只读占位，不伪造交互。
                LegacyIconButton(
                    assetPath = LegacyAsset.Favorite,
                    description = "收藏（只读）",
                    sizeDp = LegacyMetrics.ControlLikeDp,
                )
            }

        }
    }
}

/** 竞品真实位图按钮；无 onClick 时即只读占位，不接伪造交互。 */
@Composable
private fun LegacyIconButton(
    assetPath: String,
    description: String,
    sizeDp: Float,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = rememberLegacyImageBitmap(assetPath)
    val interactionSource = remember { MutableInteractionSource() }
    val modifier = Modifier.size(sizeDp.dp).let { base ->
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

/** 播放键复用竞品 `g_k`；暂停位图不在导出闭包内，按竞品形态自绘两条白色圆角竖条。 */
@Composable
private fun LegacyPlayPauseButton(playing: Boolean, onClick: () -> Unit) {
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
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PauseBar()
                PauseBar()
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
private fun PauseBar() {
    Box(
        Modifier
            .width(5.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(LegacyPalette.White)),
    )
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
