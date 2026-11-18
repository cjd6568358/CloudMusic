package com.example.cloudmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 飞牛音乐风格设计令牌 + 悬浮胶囊底栏（docs/01 §0 拍板；基准见
 * `analysis/l3-reference/feiniu-ui-adaptation-spec.md`）。
 *
 * 一手采样（真机 1440×3200，2026-11-16）：
 * - 页面底 `#1E1E21` (30,30,33)；卡片/分组底 `#2B2B2E` (43,43,46)；
 * - 品牌橙 `#FD7423`（首页漫游卡）；开关绿 `#0DD07F`。
 *
 * **辨别铁律**：飞牛是 NAS App 二级入口，其返回箭头 / 账号体系（admin/密码/用户管理/
 * 退出登录/服务器设置）属宿主壳层，我们不照搬（见 spec §2）。本文件只提供纯视觉骨架。
 *
 * 独立 object 而非改 [Comp]，便于逐页切换、不破坏现有深色主题页。
 */
object FnComp {
    /** §61.234：主题切色（同 [Comp.applyTheme] 口径；品牌色 BrandOrange/IconRed/SwitchGreen 恒定）。 */
    var PageBg = Color(0xFF1E1E21)
    var CardBg = Color(0xFF2B2B2E)
    var TextPrimary = Color(0xFFFFFFFF)
    var TextSecondary = Color(0x99FFFFFF)
    var TextHint = Color(0x66FFFFFF)
    /** 品牌色——主题恒定。 */
    val BrandOrange = Color(0xFFFD7423)
    val IconRed = Color(0xFFE54545)
    val SwitchGreen = Color(0xFF0DD07F)

    /**
     * 搜索框底：`12 §61.175` 实测推翻 spec 旧值。
     *
     * spec §1 原写「搜索框白 `#FFFFFF`」，逐像素采样 `161534_feiniu.png` 的框体
     * （x 22.3~345.7dp / y 42.3~82.0dp）实际是 **`#2B2B2E`**，与卡片底同色；
     * 白色只是截图缩略时的观感误差。以采样为准。
     */
    var SearchBg = Color(0xFF2B2B2E)
    /** 搜索占位文字：实测 `#959597`（折算 50% 白叠加在 `#2B2B2E` 上）。 */
    var SearchHint = Color(0xFF959597)
    /** 曲目行封面占位底（比卡片底亮一档，避免与行底糊在一起）。 */
    val CoverPlaceholder = Color(0xFF3A3A3E)
    val Divider = Color(0x1AFFFFFF)
    /** 卡片组内**行间分隔线**：实测 `#3C3C3F`（`161550` y=307dp / 478dp 采样）。 */
    val RowDivider = Color(0xFF3C3C3F)
    val CapsuleBg = Color(0xFF2B2B2E)

    /** §61.234：浅色映射（同 Comp 口径；搜索框实测值保留深色语义，浅色取通用灰）。 */
    private val darkBase2 = mapOf(
        "PageBg" to 0xFF1E1E21, "CardBg" to 0xFF2B2B2E, "TextPrimary" to 0xFFFFFFFF,
        "TextSecondary" to 0x99FFFFFF, "TextHint" to 0x66FFFFFF,
        "SearchBg" to 0xFF2B2B2E, "SearchHint" to 0xFF959597,
    )

    fun applyTheme(isDark: Boolean) {
        fun base(name: String, light: Long) =
            Color(if (isDark) darkBase2.getValue(name) else light)
        PageBg = base("PageBg", 0xFFFAFAFA)
        CardBg = base("CardBg", 0xFFFFFFFF)
        TextPrimary = base("TextPrimary", 0xFF1A1A1A)
        TextSecondary = base("TextSecondary", 0x991A1A1A)
        TextHint = base("TextHint", 0x661A1A1A)
        SearchBg = base("SearchBg", 0xFFF2F2F2)
        SearchHint = base("SearchHint", 0xFF8A8A8C)
    }
}

/**
 * 飞牛页面骨架的量化常量 —— 全部来自 `161550_feiniu.png` / `161534_feiniu.png` 的
 * 逐像素测量（density 3.5、1440×3200 = 411.4×914.3dp），不是目测值。
 *
 * 复跑方式见 `analysis/l3-reference/feiniu-page-metrics.json`（`12 §61.175`）。
 */
object FnBaseline {
    // ── 卡片容器 ────────────────────────────────────────────────────────────
    /** 卡片左右边距：实测所有卡片 x 16.0~395.1dp（右留 16.3）。 */
    const val PagePaddingDp = 16f
    /** 卡片圆角：逐行内缩量枚举拟合 r = 15.4（设计值 16）。 */
    const val CardCornerDp = 16f
    /** 行高：组 2 高 201.4dp / 4 行 = 50.35；组 3 高 151.1 / 3 = 50.37。 */
    const val RowHeightDp = 50.4f
    /** 组间空隙：407.4→427.7 与 578.6→598.9，实测 20.6dp（拟合值）。 */
    const val GroupGapDp = 20.3f
    /** 顶栏底 82.0 → 首卡顶 102.3 = 20.3dp（与组间距同一节奏）。 */
    const val HeaderToCardGapDp = 20.3f
    /** 统计卡底 170.0 → 下一组顶 206.3 = 36.3dp（比普通组间距大一档，视觉分组）。 */
    const val StatToNextGroupDp = 36.3f
    /** 组内行间分隔线：1dp `#3C3C3F`，左右各内缩 16dp（x 32.0~379.1）。 */
    const val RowDividerDp = 1f
    const val RowDividerInsetDp = 16f

    // ── 行内元素 ────────────────────────────────────────────────────────────
    /** 图标 bbox 宽：x 33.7~50.0dp = 16.3dp。 */
    const val RowIconDp = 16.6f
    /** 图标左内边距：卡左 16.0 → 图标左 33.7 = 17.7dp。 */
    const val RowIconLeftDp = 17.7f
    /** 文字左起（相对卡左）：实测 64.6dp − 16.0 = 48.6dp。 */
    const val RowTextLeftDp = 48.6f
    /** 行标题字号：`修改密码` 四字左缘 64.6/79.1/92.9/106.9 ⇒ 步进 14.5/13.8/14.0，中位 14.0。 */
    const val RowTitleSp = 14.0f
    /** 右侧值字号（提示位，比标题小一档）。 */
    const val RowValueSp = 12f
    /** chevron 右内边距：卡右 395.1 − chevron 右 372.3 = 22.8dp。 */
    const val RowChevronRightDp = 22.8f

    // ── 顶栏 ────────────────────────────────────────────────────────────────
    /** 顶栏圆钮：x 16.0~55.7dp = 39.7 ≈ 40dp，圆心与标题同一水平带。 */
    const val HeaderButtonDp = 40f
    /** 顶栏标题字号：`设置` 两字左缘 188.3 / 206.6 ⇒ 步进 18.3dp。 */
    const val HeaderTitleSp = 18.3f
    /** 状态栏底 37.1 → 顶栏控件顶 42.3 = 5.2dp（与搜索框顶同一值，两页一致）。 */
    const val HeaderTopGapDp = 5.2f

    // ── 曲库统计卡（替代飞牛 admin 账号卡，见 spec §2 #2） ──────────────────
    /** 实测 102.3~170.0 = 67.7dp。 */
    const val StatCardHeightDp = 68f
    /** 绿头像 32.0~75.7 = 43.7 ≈ 44dp，左内边距 16dp。 */
    const val StatAvatarDp = 44f

    // ── 搜索页 ──────────────────────────────────────────────────────────────
    /** 搜索框：y 42.3~82.0 = 40dp 高。 */
    const val SearchBoxHeightDp = 40f
    /** 搜索框左缘 **16.0dp**，与卡片同一左缘。 */
    const val SearchBoxLeftDp = 16f
    /** 状态栏底 37.1 → 搜索框顶 42.3 = 5.2dp。 */
    const val SearchTopGapDp = 5.2f
    /**
     * 搜索框圆角 **8dp**（拟合 7.9），**不是**半高的胶囊形。
     *
     * `§61.175` 首版按「框高 40 ⇒ 圆角 = 半高 20」推断，方向就错了；
     * 逐行内缩量枚举拟合后是 7.9 —— 视觉上确实只是普通圆角矩形，与截图一致。
     */
    const val SearchBoxCornerDp = 8f
    /** 放大镜：x 28.3~43.4 = 15.4dp，框内左 12.3dp。 */
    const val SearchGlyphDp = 15.4f
    /** 占位文字字号：`搜索` 两字左缘 61.1 / 75.4 ⇒ 步进 14.3dp。 */
    const val SearchHintSp = 14.3f
    /** 占位文字左起（相对框左）：61.1 − 16.0 = 45.1dp。 */
    const val SearchHintLeftDp = 45.1f
    /** 「取消」字号：`取消` 左缘 368.9 / 382.3 ⇒ 步进 13.4dp。 */
    const val CancelSp = 13.4f
    /** 搜索框右缘 352.0 → 「取消」左缘 368.9 = 16.9dp。 */
    const val SearchToCancelDp = 16.9f
    /** 「取消」右缘 394.3 → 屏右 411.4 = 17.1dp。 */
    const val CancelRightDp = 17.1f

    // ── 曲目行 ──────────────────────────────────────────────────────────────
    const val TrackCoverDp = 46f
    const val TrackRowHeightDp = 60f
    const val TrackCoverCornerDp = 10f
}

/** 飞牛风格空态（居中提示，不造插画）。 */
@Composable
fun FnEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = FnComp.TextHint,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * 飞牛风格覆盖层页头（标题居中 + 可选圆形返回钮）。
 *
 * **返回钮只给「页内二级页」用**（如从「更多」进入的设置/数据源），语义是「回上一屏」；
 * 飞牛截图里的返回箭头是「回 NAS 主屏」的**宿主壳层**，两者视觉相近但语义不同，
 * 我们只保留前者（`12 §61.175` 辨别口径）。
 */
@Composable
fun FnPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(
                start = FnBaseline.PagePaddingDp.dp,
                end = FnBaseline.PagePaddingDp.dp,
                top = FnBaseline.HeaderTopGapDp.dp,
            )
            .height(FnBaseline.HeaderButtonDp.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(FnBaseline.HeaderButtonDp.dp)
                    .clip(CircleShape)
                    .background(FnComp.CardBg)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = FnComp.TextPrimary, fontSize = 22.sp)
            }
        }
        Text(
            title,
            color = FnComp.TextPrimary,
            fontSize = FnBaseline.HeaderTitleSp.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** 飞牛分组卡片：圆角 16 + `#2B2B2E` 底，行之间**无分隔线**（实测如此）。 */
@Composable
fun FnGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = FnBaseline.PagePaddingDp.dp)
            .clip(RoundedCornerShape(FnBaseline.CardCornerDp.dp))
            .background(FnComp.CardBg),
        content = content,
    )
}

/**
 * 飞牛设置行：红色线性图标 + 标题 + 可选值 + chevron，行高 50.4dp。
 *
 * 图标用 Unicode 字形落在固定 16.6dp 槽位里（视觉高保真自研，不引竞品素材 —— 竞品素材
 * 沿用沿用 `assets-mapping.csv` 口径 `replaced=false`）。
 */
@Composable
fun FnRow(
    glyph: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String = "",
    showChevron: Boolean = true,
    /** 在行上方画 1dp 分隔线（组内**非首行**设 true）。行总高恒为 50.4dp。 */
    dividerAbove: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (dividerAbove) FnDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(
                    if (dividerAbove) {
                        (FnBaseline.RowHeightDp - FnBaseline.RowDividerDp).dp
                    } else {
                        FnBaseline.RowHeightDp.dp
                    },
                )
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            Modifier
                .padding(start = FnBaseline.RowIconLeftDp.dp)
                .size(FnBaseline.RowIconDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = FnComp.IconRed, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(
            Modifier.width(
                (FnBaseline.RowTextLeftDp - FnBaseline.RowIconLeftDp - FnBaseline.RowIconDp).dp,
            ),
        )
        Text(
            title,
            color = FnComp.TextPrimary,
            fontSize = FnBaseline.RowTitleSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                color = FnComp.TextHint,
                fontSize = FnBaseline.RowValueSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 190.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (showChevron) {
            Text(
                "›",
                color = FnComp.TextHint,
                fontSize = 18.sp,
                modifier = Modifier.padding(
                    start = 8.dp,
                    end = (FnBaseline.RowChevronRightDp - 8f).dp,
                ),
            )
        } else {
            Spacer(Modifier.width(FnBaseline.RowChevronRightDp.dp))
        }
        }
    }
}

/**
 * 卡片组内**行间分隔线**：1dp `#3C3C3F`，左右各内缩 16dp（实测 x 32.0~379.1）。
 *
 * `§61.175` 首版曾判定「飞牛组内无分隔线」——错。逐像素采样 y=307dp / 478dp
 * 都取到 `#3C3C3F`，只是 1dp 太细、缩略图上看不见。
 */
@Composable
fun FnDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(FnBaseline.RowDividerDp.dp)
            .padding(horizontal = FnBaseline.RowDividerInsetDp.dp)
            .background(FnComp.RowDivider),
    )
}

/**
 * 曲库统计卡 —— 替代飞牛设置页的 **admin 账号卡**（spec §2 #2）。
 *
 * 飞牛那行是 NAS 账号体系（头像 / 用户名 / 角色 / fnOS）；我们没有账号（PRD 硬边界），
 * 改为呈现**本地曲库真实统计**：曲目数 + 数据源数。骨架（68dp 高 / 44dp 圆形 /
 * 左内边距 16 / 圆角 16）照搬，内容不造。
 */
@Composable
fun FnStatCard(
    glyph: String,
    title: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
    accent: Color = FnComp.SwitchGreen,
) {
    FnGroupCard(modifier) {
        Row(
            Modifier.fillMaxWidth().height(FnBaseline.StatCardHeightDp.dp)
                .padding(horizontal = FnBaseline.PagePaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(FnBaseline.StatAvatarDp.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = FnComp.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        primary,
                        color = FnComp.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(FnComp.Divider)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    secondary,
                    color = FnComp.TextHint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 飞牛搜索框：40dp 高 / 20dp 圆角 / `#2B2B2E` 底 / 放大镜 15.4dp 槽位。
 *
 * 与 `CompSearchField`（竞品 36dp / `Comp.Divider` 底）的区别就是飞牛的这套量值，
 * 逐像素依据见 [FnBaseline]。
 */
@Composable
fun FnSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索",
) {
    Row(
        modifier
            .height(FnBaseline.SearchBoxHeightDp.dp)
            .clip(RoundedCornerShape(FnBaseline.SearchBoxCornerDp.dp))
            .background(FnComp.SearchBg)
            .padding(start = 12.3.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(FnBaseline.SearchGlyphDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("⌕", color = FnComp.SearchHint, fontSize = 14.sp)
        }
        Spacer(
            Modifier.width(
                (FnBaseline.SearchHintLeftDp - 12.3f - FnBaseline.SearchGlyphDp).dp,
            ),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    hint,
                    color = FnComp.SearchHint,
                    fontSize = FnBaseline.SearchHintSp.sp,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = FnComp.TextPrimary, fontSize = FnBaseline.SearchHintSp.sp),
                cursorBrush = SolidColor(FnComp.IconRed),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "✕",
                color = FnComp.SearchHint,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

/** 飞牛封面格：真实封面 → 缺失回退占位底 + 字形（复用 `rememberCompCover` 缓存）。 */
@Composable
fun FnCover(
    path: String?,
    modifier: Modifier = Modifier,
    glyph: String = "♪",
) {
    val bitmap = rememberCompCover(path, maxPx = 160)
    Box(
        modifier
            .clip(RoundedCornerShape(FnBaseline.TrackCoverCornerDp.dp))
            .background(FnComp.CoverPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(glyph, color = FnComp.TextHint, fontSize = 18.sp)
        }
    }
}

/** 飞牛曲目行：46dp 封面 + 曲名 15sp + 副行 12sp，整行可点（点击即起播）。 */
@Composable
fun FnTrackRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverRef: String? = null,
    glyph: String = "♪",
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(FnBaseline.TrackRowHeightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = FnBaseline.PagePaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FnCover(coverRef, Modifier.size(FnBaseline.TrackCoverDp.dp), glyph)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = FnComp.TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = FnComp.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing()
    }
}

/**
 * 飞牛开关：开=品牌绿 `#0DD07F`，关=`#3A3A3E` 轨道 + 白钮。
 *
 * 尺寸取 40×22dp（行高 50.4dp 内可容纳且不顶破行框），与飞牛设置页的开关比例一致。
 */
@Composable
fun FnSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) FnComp.SwitchGreen else Color(0xFF3A3A3E))
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 飞牛分组小标题（卡片之外的行间标题）。 */
@Composable
fun FnSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = FnComp.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(
            start = FnBaseline.PagePaddingDp.dp + 2.dp,
            top = 18.dp,
            bottom = 10.dp,
        ),
    )
}

/**
 * 飞牛风格悬浮胶囊三 tab 底栏（首页 / 音乐库 / 更多）。
 *
 * 真机形态：`#2B2B2E` 圆角胶囊悬浮于底部，居中留边；**仅图标无文字标签**——
 * 选中项 = 红圆底 + 白色图标，未选中 = 灰白图标（§61.191，161526/161602 对照）。
 * **不含返回箭头、不含任何账号入口**（见 spec §2）。
 *
 * @param icons 三项：首页 / 音乐库 / 更多
 */
/** §61.191（W4）：底栏胶囊图标种别——飞牛口径仅图标无文字标签（161526/161602 逐段对照）。 */
/** §61.222：底栏加搜索 tab（用户指示）——四胶囊：首页 / 音乐库 / 搜索 / 更多。 */
enum class FnCapsuleIcon { Home, Music, Search, More }

/**
 * §61.191（W4）：底部胶囊栏对齐飞牛——**仅图标**（旧实现 = 字形 ⌂♫☰ + 文字标签，
 * 竞品 = 白色图标，选中项为红圆底白图标），图标 Canvas 自绘（房子/音符/笑脸，
 * 飞牛无导出素材，按 161526/161602 基准形状绘制）。
 */
@Composable
fun FnCapsuleBar(
    icons: List<FnCapsuleIcon>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(FnComp.CapsuleBg)
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .padding(vertical = 6.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            icons.forEachIndexed { i, kind ->
                val active = i == selected
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (active) FnComp.IconRed else Color.Transparent)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    FnCapsuleGlyph(
                        kind = kind,
                        tint = if (active) Color.White else FnComp.TextHint,
                    )
                }
            }
        }
    }
}

/** 底栏三枚白色图标（实心房子 / 实心音符 / 笑脸描线）。 */
@Composable
private fun FnCapsuleGlyph(kind: FnCapsuleIcon, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val w = 2.2.dp.toPx()
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun p(block: (androidx.compose.ui.graphics.Path.() -> Unit)) =
            androidx.compose.ui.graphics.Path().apply(block)
        when (kind) {
            FnCapsuleIcon.Home -> {
                // 实心房子：屋顶尖 + 方身
                drawPath(
                    p {
                        moveTo(cx - 9.dp.toPx(), cy)
                        lineTo(cx, cy - 9.dp.toPx())
                        lineTo(cx + 9.dp.toPx(), cy)
                        lineTo(cx + 9.dp.toPx(), cy + 8.dp.toPx())
                        lineTo(cx - 9.dp.toPx(), cy + 8.dp.toPx())
                        close()
                    },
                    tint,
                )
            }
            FnCapsuleIcon.Music -> {
                // 实心音符：椭圆头 + 右上杆 + 小旗
                drawPath(
                    p {
                        moveTo(cx - 6.dp.toPx(), cy + 5.dp.toPx())
                        lineTo(cx + 1.dp.toPx(), cy + 3.dp.toPx())
                        lineTo(cx + 1.dp.toPx(), cy - 8.dp.toPx())
                        lineTo(cx + 7.dp.toPx(), cy - 6.dp.toPx())
                        lineTo(cx + 7.dp.toPx(), cy - 3.dp.toPx())
                        lineTo(cx + 3.dp.toPx(), cy - 5.dp.toPx())
                        lineTo(cx + 3.dp.toPx(), cy + 4.dp.toPx())
                        close()
                    },
                    tint,
                )
            }
            FnCapsuleIcon.Search -> {
                // 放大镜：圆环描线 + 右下斜手柄（对齐首页搜索框图标形状）
                drawCircle(tint, radius = 7.dp.toPx(), center = Offset(cx - 2.dp.toPx(), cy - 2.dp.toPx()), style = stroke)
                drawPath(
                    p {
                        moveTo(cx + 3.4.dp.toPx(), cy + 3.4.dp.toPx())
                        lineTo(cx + 8.6.dp.toPx(), cy + 8.6.dp.toPx())
                    },
                    tint,
                    style = stroke,
                )
            }
            FnCapsuleIcon.More -> {
                // 笑脸：圆描线 + 两眼点 + 弧嘴
                drawCircle(tint, radius = 10.dp.toPx(), center = Offset(cx, cy), style = stroke)
                drawCircle(tint, radius = 1.3.dp.toPx(), center = Offset(cx - 3.2f.dp.toPx(), cy - 2.6f.dp.toPx()))
                drawCircle(tint, radius = 1.3.dp.toPx(), center = Offset(cx + 3.2f.dp.toPx(), cy - 2.6f.dp.toPx()))
                drawPath(
                    p {
                        moveTo(cx - 4.dp.toPx(), cy + 3.dp.toPx())
                        quadraticBezierTo(cx, cy + 6.dp.toPx(), cx + 4.dp.toPx(), cy + 3.dp.toPx())
                    },
                    tint,
                    style = stroke,
                )
            }
        }
    }
}
