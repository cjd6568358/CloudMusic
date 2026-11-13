package com.example.cloudmusic.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `03-10` 用户决策 ①：播放页整页参考竞品（View 体系 + 自定义唱片 View），
 * **其余页面纯 Compose**，局部混用两套 UI 框架被限制在播放页一屏。
 *
 * `12 §61.42`（用户 2026-11-09 口径）：不搬竞品代码，严格参考竞品 UI / 风格 / 交互
 * 自己实现一套；**能复用的素材一律复用竞品，避免色差**。本页所有色值 / 尺寸 / 图标
 * 均取自 [LegacyPalette] / [LegacyMetrics] / [LegacyAsset]（一手反编译证据），
 * 不自行配色；唱片叠层按 `legacy_gc.xml` / `legacy_bv5.xml` 的 z 序重排。
 *
 * `12 §61.109`（P2 播放页 1:1 对齐第一批，基准
 * `analysis/l3-reference/competitor-player-baseline.png` 1440×3200）：
 * - ① 封面驱动背景：封面主色 → 垂直渐变（`§61.43` 逆向链的自研实现；无封面回退
 *   `legacy_c5y.xml` `#ff262626`）；渐变端值为**自定值**（竞品未导出该算法），挂账；
 * - ② 圆形真实封面替代红色占位盘（[VinylDiscView.setCover]，缺失回退竞品 `g9f`）；
 * - ③ 白色弧形唱臂（[VinylDiscView] 自绘重绘，pivot / 起落动画参数不变）；
 * - ④ 白色进度条替代旧 `legacy_bn5.xml` 红色（基准图一手形态；`#ffff3a3a` 保留在
 *   [LegacyPalette] 供旧皮肤回溯）；
 * - ⑤ 双排控制行（基准图：主排 模式/上一首/播放/下一首/队列；副排 音效接线 +
 *   极简几何占位）；
 * - ⑥ 顶栏 / 文案 / 间距（chevron 旋转 270° 得下箭头；标题下移到唱片下方大字号）。
 *
 * **不伪造边界（诚实优先于像素）**：基准图中的「422人」在线数、心形 `5w+` /
 * 评论 `226` 计数、「关注」按钮、「极高音质」标签、私人漫游标题、cast 图标等
 * **生产侧无真实数据源或属 P5 冻结域**，一律省略或以只读图标呈现，**不造数、
 * 不造具象图标**（闭包外具象图形如鲸鱼 / 灯泡 / 评论气泡挂账，见 `12 §61.109`）。
 * 自绘极简几何（队列三线 / info 圆 / more 三点）沿用 `12 §61.40` 暂停条自绘先例。
 *
 * `12 §61.40` 交互复刻（参数逐项来自实证，不改动）：唱片旋转 / 唱针起落 →
 * [VinylDiscView]；标题跑马灯 → 平台 `TextView` MARQUEE；左右滑动切歌 → 72dp；
 * 进度条 / 播放 / 上下曲 → `PlayerStateBridge` 单向绑定；音效开合 → 桥接
 * `onEffect`（§61.93 audiofx，本页为其第一个生产 UI 入口）。
 * 本页不新建模块、不新增依赖、不动 assets / Manifest / 权限。
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
    val mode by bridge.playMode.collectAsState(initial = PlayMode.SEQUENCE)
    val playing = state.phase == PlayerPhase.PLAYING

    val maxMs = durationMs.coerceAtLeast(1L).toFloat()
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }

    // `03-10 §6`：进度条读 `positionMs`；拖动期间由本地 seekValue 接管，松手才下发 seek。
    LaunchedEffect(state.positionMs) {
        if (!seeking) seekValue = state.positionMs.toFloat()
    }

    // §61.109 ②：真实封面（`Track.coverRef` = 本地文件路径，BridgeModule.readCover
    // 同源）；解码失败 / 无封面 → null（唱片回退竞品 g9f、背景回退 #262626）。
    val coverPath = track?.coverRef
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverPath) {
        coverBitmap = withContext(Dispatchers.IO) {
            coverPath?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }
    }
    // §61.109 ①：封面主色 → 背景渐变（端值自定，挂账；见方法 KDoc）。
    val bgTop = remember(coverBitmap) { dominantCoverColor(coverBitmap) ?: LegacyPalette.PageBackground }
    val bgBottom = remember(coverBitmap) { darken(bgTop, 0.55f) }

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { LegacyMetrics.SwipeThresholdDp.dp.toPx() }

    // §61.118（T5-1）：均衡器弹窗开关。
    var showEqualizer by remember { mutableStateOf(false) }

    Surface(
        color = Color(bgTop),
        contentColor = Color(LegacyPalette.TextPrimary),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(bgTop), Color(bgBottom))),
                )
                .padding(
                    horizontal = LegacyMetrics.PagePaddingDp.dp,
                    vertical = 12.dp,
                ),
        ) {
            // ── 顶栏（§61.109 ⑥，基准图）：chevron 旋转 270° = 下箭头（收起播放页）；
            //    中央模式标头用真实 playMode 数据；cast 图标闭包外省略（挂账） ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegacyIconButton(
                    assetPath = LegacyAsset.BackArrow,
                    description = "收起播放页",
                    sizeDp = LegacyMetrics.BackBarHeightDp,
                    rotateDegrees = 270f,
                    onClick = onClose,
                )
                Text(
                    text = "本地播放 · ${modeLabel(mode)}",
                    fontSize = LegacyMetrics.TitleSp.sp,
                    color = Color(LegacyPalette.TextPrimary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                // 右槽位与 chevron 等宽占位，保证标题居中（图标闭包外，挂账）。
                Box(modifier = Modifier.size(LegacyMetrics.BackBarHeightDp.dp))
            }

            // ── 唱片区：属性动画唱片 + 白色弧形唱臂 + 手势切歌覆盖层 ────────────
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx -> VinylDiscView(ctx) },
                    update = {
                        it.setPlaying(playing)
                        it.setCover(coverBitmap)
                    },
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

            // ── 标题区（§61.109 ⑥）：基准图标题在唱片下方大字号 + 心形（只读）；
            //    在线人数 / 计数 / 关注生产侧无数据源，一律省略不造数 ─────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
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
                            setTextColor(LegacyPalette.TextPrimary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, LegacyMetrics.PlayerTitleSp)
                            loadLegacyTypeface(ctx)?.let { typeface = it }
                        }
                    },
                    update = { it.text = track?.title ?: "未在播放" },
                    modifier = Modifier.weight(1f),
                )
                LegacyIconButton(
                    assetPath = LegacyAsset.Favorite,
                    description = "收藏（只读）",
                    sizeDp = LegacyMetrics.InfoRowIconDp,
                )
            }
            Text(
                text = track?.artist ?: "",
                fontSize = LegacyMetrics.TimeSp.sp,
                color = Color(LegacyPalette.TextSecondary),
                maxLines = 1,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            // ── 进度条（§61.109 ④，基准图白色一手形态）：`#ffff3a3a` 旧红保留在
            //    Palette 供旧皮肤回溯；「极高音质」无质量元数据省略（不伪造） ──────
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
                        thumbColor = Color(LegacyPalette.White),
                        activeTrackColor = Color(LegacyPalette.White),
                        inactiveTrackColor = Color(LegacyPalette.TrackBuffer),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    formatMs(durationMs),
                    fontSize = LegacyMetrics.TimeSp.sp,
                    color = Color(LegacyPalette.IconTint),
                )
            }

            // ── 主控制行（§61.109 ⑤，基准图）：模式 / 上一首 / 播放 / 下一首 / 队列；
            //    队列图标闭包外按极简几何自绘（§61.40 暂停条先例），只读展示 ─────────
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
                Box(
                    modifier = Modifier.size(LegacyMetrics.ControlLikeDp.dp),
                    contentAlignment = Alignment.Center,
                ) { QueueGlyph() }
            }

            // ── 副控制行（§61.109 ⑤，基准图新增）：音效（真实素材 h0h + §61.93
            //    接线 = 第一个生产 UI 入口）/ info / more 为极简几何只读占位；
            //    鲸鱼 / 灯泡等具象闭包外图标省略（挂账） ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegacyIconButton(
                    assetPath = LegacyAsset.SoundEffect,
                    // §61.118（T5-1）：分频段 EQ 弹窗（档位循环升级为完整均衡器）。
                    description = "音效 / 均衡器",
                    sizeDp = LegacyMetrics.SecondaryControlDp,
                    onClick = { showEqualizer = true },
                )
                Box(
                    modifier = Modifier.size(LegacyMetrics.SecondaryControlDp.dp),
                    contentAlignment = Alignment.Center,
                ) { InfoGlyph() }
                Box(
                    modifier = Modifier.size(LegacyMetrics.SecondaryControlDp.dp),
                    contentAlignment = Alignment.Center,
                ) { MoreGlyph() }
            }
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
                    Slider(
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

/** §61.109 ⑤：队列图标（闭包外）按极简几何自绘三条横线（基准图形态），只读。 */
@Composable
private fun QueueGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
        Box(Modifier.width(14.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
        Box(Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(LegacyPalette.IconTint)))
    }
}

/** §61.109 ⑤：info 图标（闭包外）按极简几何自绘（圆环 + i），只读。 */
@Composable
private fun InfoGlyph() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(1.5.dp, Color(LegacyPalette.IconTint), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("i", fontSize = 12.sp, color = Color(LegacyPalette.IconTint))
    }
}

/** §61.109 ⑤：more 图标（闭包外）按极简几何自绘三点，只读。 */
@Composable
private fun MoreGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color(LegacyPalette.IconTint)),
            )
        }
    }
}

/**
 * §61.109 ①：封面主色提取（16×16 降采样 → 饱和度×频次加权 → 压暗到基准图的
 * 深蓝夜色带）。**算法与端值为自定值**（竞品未导出该实现），像素级校准挂账；
 * 失败 / 无封面返回 null → 调用方回退 `#ff262626`。
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
            // 过暗 / 过灰的桶不给高分（基准图背景是带色相的深色渐变）。
            val score = sat * n + n * 0.05f
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        darken(best, 0.34f)
    }.getOrNull()
}

/** 等比压暗并夹到不透明深色带（自定值，§61.109 ① 挂账）。 */
private fun darken(argb: Int, factor: Float): Int {
    val r = (((argb shr 16) and 0xFF) * factor).toInt().coerceIn(10, 90)
    val g = (((argb shr 8) and 0xFF) * factor).toInt().coerceIn(12, 95)
    val b = ((argb and 0xFF) * factor).toInt().coerceIn(18, 110)
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
