package com.example.cloudmusic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * §61.137 全页面竞品 UI 重建（`12 §61.137` / `analysis/ui-replica/page-spec-61-137.md`）：
 * 色值 / 尺寸全部取自竞品反编译一手证据（`analysis/decoded/res/` 的 values 与
 * `music_library_*` / `main_account` / `item_account_*` layout 家族），零素材导出（C4）。
 *
 * 三级文本层级：b12 #FFFFFF（主）/ b17、b1n #66FFFFFF（次）/ b1h #33FFFFFF（hint）；
 * 页面背景 aei #FF222222、行背景 aer #FF212121、弹窗底 a6a #F71C1C1C（顶部圆角 10dip）。
 */
object Comp {
    /**
     * §61.234（V0.2 主题化）：色值 var 化——深色基准（竞品像素级）与浅色映射由
     * [applyTheme] 切换，Activity recreate 后生效。品牌红恒定。
     */
    var PageBg = Color(0xFF222222)
    var RowBg = Color(0xFF212121)
    var DialogBg = Color(0xF71C1C1C)
    var RowSelected = Color(0x12FFFFFF)
    var ChipBg = Color(0x14FFFFFF)
    var CoverPlaceholder = Color(0x26FFFFFF)
    var TextPrimary = Color(0xFFFFFFFF)
    var TextSecondary = Color(0x66FFFFFF)
    var TextHint = Color(0x33FFFFFF)
    var TextWhite60 = Color(0x99FFFFFF)
    var TabUnselected = Color(0x4DFFFFFF)
    var Divider = Color(0x0DFFFFFF)
    /** 品牌红——主题恒定。 */
    val AccentRed = Color(0xFFFF3A3A)
    /** `#4DFF3A3A` —— 红系淡背景——主题恒定。 */
    val AccentRedSub = Color(0x4DFF3A3A)

    var BarBg = Color(0xFF262626)
    var BarUnselected = Color(0x99FFFFFF)

    /** 深色基准快照（回深色时恢复竞品像素级原值）。 */
    private val darkBase = mapOf(
        "PageBg" to 0xFF222222, "RowBg" to 0xFF212121, "DialogBg" to 0xF71C1C1C,
        "RowSelected" to 0x12FFFFFF, "ChipBg" to 0x14FFFFFF, "CoverPlaceholder" to 0x26FFFFFF,
        "TextPrimary" to 0xFFFFFFFF, "TextSecondary" to 0x66FFFFFF, "TextHint" to 0x33FFFFFF,
        "TextWhite60" to 0x99FFFFFF, "TabUnselected" to 0x4DFFFFFF, "Divider" to 0x0DFFFFFF,
        "BarBg" to 0xFF262626, "BarUnselected" to 0x99FFFFFF,
    )

    /** §61.234：主题切色。浅色 = 通用映射（竞品无浅色基准，非像素级，如实登记）。 */
    fun applyTheme(isDark: Boolean) {
        fun base(name: String, light: Long) =
            Color(if (isDark) darkBase.getValue(name) else light)
        PageBg = base("PageBg", 0xFFFAFAFA)
        RowBg = base("RowBg", 0xFFF2F2F2)
        DialogBg = base("DialogBg", 0xF7FFFFFF)
        RowSelected = base("RowSelected", 0x12000000)
        ChipBg = base("ChipBg", 0x14000000)
        CoverPlaceholder = base("CoverPlaceholder", 0x26000000)
        TextPrimary = base("TextPrimary", 0xFF1A1A1A)
        TextSecondary = base("TextSecondary", 0x661A1A1A)
        TextHint = base("TextHint", 0x331A1A1A)
        TextWhite60 = base("TextWhite60", 0x991A1A1A)
        TabUnselected = base("TabUnselected", 0x4D1A1A1A)
        Divider = base("Divider", 0x0D1A1A1A)
        BarBg = base("BarBg", 0xFFFFFFFF)
        BarUnselected = base("BarUnselected", 0x991A1A1A)
    }
}

/** §61.234：主题统一切换入口（Comp + FnComp 同口径，MainActivity 启动/设置页调用）。 */
fun applyThemeCompat(isDark: Boolean) {
    Comp.applyTheme(isDark)
    com.example.cloudmusic.ui.FnComp.applyTheme(isDark)
}

/** 竞品页面根：aei #FF222222 全屏底。 */
@Composable
fun CompPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Comp.PageBg)) { content() }
}

/**
 * 页头（`fragment_my_playlist_music`/asp）：44dip 标题栏（back 15/12 padding）+
 * 标题 18sp b1e 居中 + 0.3dip 分割线（44dip 处）。
 */
@Composable
fun CompHeaderBar(
    title: String,
    onBack: (() -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            if (onBack != null) {
                Text(
                    "‹",
                    color = Comp.TextPrimary,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 15.dp, end = 15.dp)
                        .clickable(onClick = onBack),
                )
            }
            Text(
                title,
                color = Comp.TextPrimary,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            if (right != null) {
                Box(Modifier.align(Alignment.CenterEnd)) { right() }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = Comp.Divider)
    }
}

/**
 * 分组标题（`music_library_playlist_title_item`/cvn）：高 36dip、13sp b1n、
 * paddingH 16、marginTop 4。
 */
@Composable
fun CompGroupTitle(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(36.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = Comp.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** 0.5dip 分割线（`b1p`）。 */
@Composable
fun CompDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = Comp.Divider)
}

/**
 * 设置页头（`main_account`/cky）：52dip（art）行 + 16sp 白标题；竞品是头像 27×27 +
 * 昵称，自研无账号系统，标题占位（挂账）。
 */
@Composable
fun CompSettingsHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Comp.TextPrimary, fontSize = 16.sp, maxLines = 1)
    }
}

/**
 * 设置行（`item_account_normal_item`/b1c + include bzv）：高 50dip（a1u）、
 * marginH 16（bi）+ paddingH 16（a4m）、图标 20×20（a1v）+ marginEnd 10、
 * 标题 16sp 白、右侧提示 11sp 次级、箭头 33% 白（c_i 深）。
 */
@Composable
fun CompSettingsRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
    rightHint: String? = null,
    rightHintColor: Color = Comp.TextSecondary,
    /** §61.275（B06）：行尾槽位（失败源「不可用」角标等）。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(50.dp)
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            icon,
            color = Comp.TextWhite60,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = Comp.TextPrimary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!rightHint.isNullOrBlank()) {
            Text(
                rightHint,
                color = rightHintColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Text("›", color = Comp.TextHint, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 开关行（`item_account_item_with_switch`/b1a）：同设置行布局，右侧 CustomThemeSwitch。 */
@Composable
fun CompSwitchRow(
    icon: String,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    rightHint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(50.dp)
            .padding(horizontal = 16.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            icon,
            color = Comp.TextWhite60,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Comp.TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!rightHint.isNullOrBlank()) {
                Text(
                    rightHint,
                    color = Comp.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Comp.AccentRed,
                checkedThumbColor = Comp.TextPrimary,
                uncheckedTrackColor = Comp.ChipBg,
                uncheckedThumbColor = Comp.TextWhite60,
                uncheckedBorderColor = Comp.Divider,
            ),
        )
    }
}

/**
 * §61.138 封面缓存：`Track.coverRef`（扫描期落盘的本地封面文件路径）→ 降采样 Bitmap。
 * 命中缓存/确认缺失（misses）直接返回；LruCache 容量 64 张（列表行 48dip 用，
 * 256px 级降采样后单张 ≈ 256KB，峰值 ≈ 16MB 上限内）。解码失败计入 misses 不重试。
 * 与播放页（LegacyPlayerPage §61.109 ②）同源同策略，IO + runCatching。
 */
private val coverCache = LruCache<String, Bitmap>(64)
private val coverMisses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

@Composable
fun rememberCompCover(path: String?, maxPx: Int = 256): Bitmap? {
    var bitmap by remember(path) { mutableStateOf(path?.let { coverCache.get(it) }) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank() || bitmap != null || path in coverMisses) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx &&
                    bounds.outHeight / (sample * 2) >= maxPx
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inSampleSize = sample
                    },
                )
            }.getOrNull()
        }
        if (decoded != null) coverCache.put(path, decoded) else coverMisses.add(path)
        bitmap = decoded
    }
    return bitmap
}

/**
 * 封面格（§61.138）：真实封面（crop）→ 缺失回退占位底 + 字形。
 * 圆角/尺寸由调用方 modifier 给定（曲目行 48dip r4 / 歌单行 50dip r8）。
 */
@Composable
fun CompCover(
    path: String?,
    modifier: Modifier = Modifier,
    cornerDp: Int = 4,
    glyph: String = "♪",
    glyphSizeSp: Int = 18,
) {
    val bitmap = rememberCompCover(path)
    Box(
        modifier
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(Comp.CoverPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(glyph, color = Comp.TextSecondary, fontSize = glyphSizeSp.sp)
        }
    }
}

/**
 * 曲目行（`music_library_music_item`/cvi + `music_library_music_info_layout`/cvg）：
 * 容器 bg aer、上下 margin 5、尾 margin 16；封面 48×48 marginStart 16 上下 10
 * （行高 68）、歌名 16sp b12、副行 marginTop 6：副文本 12sp b17。
 * §61.138：coverRef 接真实封面（`Track.coverRef`），缺失回退占位字形。
 */
@Composable
fun CompTrackRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    coverGlyph: String = "♪",
    coverRef: String? = null,
    /** §61.276（V0.4-03）：不可用曲目整行降透明（仍可见、可点并给原因）。 */
    dimmed: Boolean = false,
    /** 「暂不可用」角标；null = 正常曲目。 */
    badge: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f)
            .padding(top = 5.dp, bottom = 5.dp, end = 16.dp)
            .background(Comp.RowBg)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompCover(
            path = coverRef,
            modifier = Modifier
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp)
                .size(48.dp),
            cornerDp = 4,
            glyph = coverGlyph,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Comp.TextPrimary,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        color = Comp.AccentRed,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Comp.AccentRed.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                color = Comp.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 歌单行（`music_library_playlist_item`/cvm）：高 60dip、封面 50×50 圆角 8
 * marginStart 16、名 16sp b12 marginStart 10、副 12sp b1n marginStart 10。
 * §61.138：coverRef 可传首曲封面（调用方自取）；缺省 ☰ 占位。
 */
@Composable
fun CompPlaylistRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    coverRef: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompCover(
            path = coverRef,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(50.dp),
            cornerDp = 8,
            glyph = "☰",
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 16.dp),
        ) {
            Text(
                name,
                color = Comp.TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = Comp.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 搜索框（`fragment_music_library_search_tip`/asc）：bg bff（#0DFFFFFF 圆角 18dip）、
 * 高 36dip、paddingH 10、放大镜 16×16（alpha 0.2 → 33% 白）、15sp 文字、
 * hint「搜索音乐」（ew7）。
 */
@Composable
fun CompSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索音乐",
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Comp.Divider)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", color = Comp.TextHint, fontSize = 15.sp)
        Box(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            if (value.isEmpty()) {
                Text(hint, color = Comp.TextHint, fontSize = 15.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Comp.TextPrimary, fontSize = 15.sp),
                cursorBrush = Brush.verticalGradient(listOf(Comp.AccentRed, Comp.AccentRed)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "✕",
                color = Comp.TextHint,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

/** 历史词 chip（`music_library_search_history_item`/cvq）：高 32dip、paddingH 16、bfi 圆角 16。 */
@Composable
fun CompHistoryChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Comp.ChipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Comp.TextPrimary, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * 曲库顶 tab（`fragment_music_library`/as5）：高 45dip、字 17sp（style ld）、
 * 选中 b12 / 未选 b18、指示器 bfn 白色 inset 16dip 高 1.0dip（asw 直译，存疑挂账）。
 * trailing 供页级动作（如重扫）占 48dip 尾位。
 */
@Composable
fun CompTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(45.dp)) {
            tabs.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (index == selected) Comp.TextPrimary else Comp.TabUnselected,
                        fontSize = 17.sp,
                        maxLines = 1,
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.width(48.dp).fillMaxHeight()) { trailing() }
            }
        }
        // 指示器（bfn：白色、左右 inset 16dip、高 1dip）骑在分割线上方。
        Box(Modifier.fillMaxWidth().height(1.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(Comp.TextPrimary),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = Comp.Divider)
    }
}

/**
 * 底部操作面板（`@drawable/b5y`：a6a #F71C1C1C 顶部圆角 10dip）。
 * 竞品 more-ops 模式：标题 12sp 次级 + 50dip 居中操作行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompSheetDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    content: (@Composable () -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    destructiveIndexOf: Int? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Comp.DialogBg,
        contentColor = Comp.TextPrimary,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            if (title != null) {
                Text(
                    title,
                    color = Comp.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            content?.invoke()
            actions.forEachIndexed { index, (label, action) ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clickable(onClick = action),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (index == destructiveIndexOf) Comp.AccentRed else Comp.TextPrimary,
                        fontSize = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 弹窗输入行（自研组合：无竞品直证，按 chip 同族 8% 白底圆角，挂账）。 */
@Composable
fun CompDialogInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    password: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Comp.ChipBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = Comp.TextHint, fontSize = 15.sp, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Comp.TextPrimary, fontSize = 15.sp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = Brush.verticalGradient(listOf(Comp.AccentRed, Comp.AccentRed)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 竞品曲库「使用」钮近似（cvg CustomButton 属性混淆 pl/pp/aer/aey，视觉惯例 =
 * 红描边圆角钮）：高 26dip、radius 13、红字 14sp（不确定度见规格 §4）。
 */
@Composable
fun CompActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    heightDp: Int = 26,
) {
    val shape = RoundedCornerShape((heightDp / 2).dp)
    Box(
        modifier = modifier
            .height(heightDp.dp)
            .clip(shape)
            .background(if (filled) Comp.AccentRed else Color.Transparent)
            .border(1.dp, Comp.AccentRed.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) Comp.TextPrimary else Comp.AccentRed,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * 底部 tab 栏（竞品无原生 layout——代码绘制，挂账待真机对照；按 §61.136 h7
 * #FF262626 底 + 10sp label + 选中品牌红近似）。
 */
@Composable
fun CompBottomBar(
    tabs: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.background(Comp.BarBg)) {
        HorizontalDivider(thickness = 0.5.dp, color = Comp.Divider)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(49.dp)) {
            tabs.forEachIndexed { index, (label, glyph) ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        glyph,
                        color = if (index == selected) Comp.AccentRed else Comp.BarUnselected,
                        fontSize = 18.sp,
                    )
                    Text(
                        label,
                        color = if (index == selected) Comp.AccentRed else Comp.BarUnselected,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 扫描状态条（重样式：12sp 次级文本 + aer #FF212121 底）。 */
@Composable
fun CompScanBanner(text: String) {
    Box(Modifier.fillMaxWidth().background(Comp.RowBg)) {
        Text(
            text,
            color = Comp.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** 空态（居中 14sp 次级）。 */
@Composable
fun CompEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Comp.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
