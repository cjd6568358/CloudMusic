package com.example.cloudmusic.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * `12 §61.42`：竞品视觉规格与素材的**单一真相源**。
 *
 * 用户 2026-11-09 口径：不要求连着竞品代码一起搬，做不到时「严格参考竞品 UI / 风格 /
 * 交互自己实现一套」，除核心播放逻辑外其余全部自己实现；**能复用的素材一律复用竞品，
 * 避免产生色差**。因此本文件把竞品反编译产物里的色值、尺寸、位图路径集中登记，
 * UI 层只从这里取值，不自造颜色 / 尺寸。
 *
 * 取值出处（均为 `app/src/main/assets/legacy-player/` 下 D-146 导出的一手证据）：
 * - 色值：`values/legacy_colors.xml`、`layout/legacy_c5y.xml` / `legacy_are.xml`
 * - 品牌红：2026-11-09 真机 adb screencap 启动屏（1440x3200）六点像素采样
 * - 尺寸：`values/legacy_dimens.xml`、`drawable/legacy_bn5.xml`、`legacy_bp1.xml`、
 *   `legacy_bnx.xml`、`drawable-anydpi-v24/legacy_coy.xml`、
 *   `layout/legacy_e0g.xml` / `legacy_are.xml` / `legacy_gr.xml` / `legacy_gc.xml`
 * - 位图与字体：D-146 导出的 51 个 assets 文件（`replaced=false` 保持）；
 *   图标语义由 `analysis/l3-reference/legacy-icons-sheet.png` /
 *   `legacy-control-zoom.png` 与 layout 引用 id 双向确认
 *
 * 全部素材随 debug APK 打包（`12 §61.36` 实证），这里经 AssetManager 直接读取，
 * **不搬进 `app/src/main/res/`、不新增依赖、不改发布基线**。
 */
object LegacyPalette {
    /** `layout/legacy_c5y.xml` / `legacy_gc.xml`：`android:background="#ff262626"`。 */
    val PageBackground = 0xFF262626.toInt()

    /** 真机启动屏 1440x3200 六点采样，2026-11-09，值一致。 */
    val BrandRed = 0xFFA22016.toInt()

    /** `legacy_ew` / `drawable/legacy_bn5.xml` 的 progress 实心色：`#ffff3a3a`。 */
    val PrimaryRed = 0xFFFF3A3A.toInt()

    /** `legacy_anm`（`legacy_ani` 指向它）/ `legacy_bp1.xml` 红点：`#ffff2c55`。 */
    val AccentRed = 0xFFFF2C55.toInt()

    /** `legacy_ff`：`#eecc0000`（注意 alpha 为 EE，不是 FF）。 */
    val DeepRed = 0xEECC0000.toInt()

    /** `legacy_b12` / `legacy_b1e`：纯白。 */
    val White = 0xFFFFFFFF.toInt()

    /** `legacy_b1e`：主文字白。 */
    val TextPrimary = 0xFFFFFFFF.toInt()

    /** `legacy_b1w`：次文字（约 70% 白）。 */
    val TextSecondary = 0xB3FFFFFF.toInt()

    /** `legacy_b1n`：三级文字（约 40% 白，`legacy_gc.xml` currentMode）。 */
    val TextTertiary = 0x66FFFFFF.toInt()

    /** `legacy_b1q`：提示文字（约 50% 白）。 */
    val TextHint = 0x80FFFFFF.toInt()

    /** `legacy_b1z`：图标 / 时间文字（约 80% 白）。 */
    val IconTint = 0xCDFFFFFF.toInt()

    /** `legacy_b1d` / `legacy_ajd` / `legacy_bn5.xml` background：进度条轨道。 */
    val TrackBackground = 0x1AFFFFFF.toInt()

    /** `legacy_b1h` / `legacy_bn5.xml` secondaryProgress：缓冲。 */
    val TrackBuffer = 0x33FFFFFF.toInt()

    /** `legacy_b1t` / `legacy_h0o.xml` progress：柔白进度（8dip 变体）。 */
    val TrackProgressSoft = 0x99FFFFFF.toInt()

    /** `legacy_dv`：顶部遮罩。 */
    val ScrimTop = 0x1A000000.toInt()

    /** `legacy_dz` / `legacy_bnx.xml` 渐变起点：底部遮罩。 */
    val ScrimBottom = 0x33000000.toInt()

    /** `legacy_du` / `legacy_b1c`：全透明（`legacy_bnx.xml` 渐变终点）。 */
    val Transparent = 0x00000000

    /** `legacy_are.xml` miSongName：`#b3fff5eb`（暖白 70%，迷你播放页变体）。 */
    val MiniTitleWarm = 0xB3FFF5EB.toInt()

    /** `legacy_are.xml` miArtistName：`#73fff5eb`（暖白 45%）。 */
    val MiniArtistWarm = 0x73FFF5EB.toInt()

    /** `legacy_are.xml` miContainer：`#ff33333e`（深蓝灰，迷你播放页变体）。 */
    val MiniPageBackground = 0xFF33333E.toInt()
}

/** 竞品尺寸（dp / sp / ms），逐项来自反编译 XML，不自定；自定值单独标注。 */
object LegacyMetrics {
    const val PagePaddingDp = 10f        // legacy_auh = 10.0dip
    const val ControlSizeDp = 54f        // legacy_aui = 54.0dip / legacy_gr.xml btnPlay 54dip
    const val DiscPlateMarginDp = 10f    // legacy_gc.xml smallAlbumDisc margin @dimen/auh
    const val DiscBgMarginDp = 54f       // legacy_gc.xml playDiscBg margin @dimen/aui
    const val DiscAlbumDp = 170f         // legacy_gc.xml smallAlbumCover 170dip
    const val TrackCornerDp = 3f         // legacy_bn5.xml corners 3.0dip
    const val TrackHeightDp = 8f         // legacy_h0o.xml item height 8.0dip
    const val TrackMaxHeightDp = 3f      // legacy_gr.xml seekBar maxHeight 3.0dip
    const val ThumbDp = 12f              // legacy_ay6.xml 白圆点 12.0dip
    const val MiniBarCornerDp = 18f      // legacy_bnx.xml corners 18.0dip
    const val MiniCoverDp = 36f          // legacy_e0g.xml cover 36.0dip
    const val MiniCoverImageDp = 22f     // legacy_e0g.xml coverImage 22.0dip
    const val MiniProgressDp = 37f       // legacy_e0g.xml progressView 37.0dip
    const val RedDotDp = 9f              // legacy_bp1.xml / legacy_e0g.xml redDot 9.0dip
    const val BackArrowDp = 26f          // legacy_coy.xml viewport 26.0dip
    const val BackBarHeightDp = 44f      // legacy_gr.xml ivFinish 44.0dip
    const val ShareDp = 40f              // legacy_gr.xml ivShare 40.0dip
    const val ControlPrevNextDp = 30f    // legacy_are.xml miPrev/miNext 30dip（gr.xml 变体 24dip）
    const val ControlPlayDp = 36f        // legacy_are.xml miPlayBtn 36dip（gr.xml 变体 54dip）
    const val ControlModeDp = 40f        // legacy_are.xml miModeBtn 40.0dip
    const val ControlLikeDp = 28f        // legacy_are.xml miLikeBtn 28.0dip
    const val TitleSp = 18f              // legacy_gr.xml tvTitle 18.0sp
    const val TimeSp = 12f               // legacy_gr.xml durationTime/totalTime 12.0sp
    const val MarqueeTextSp = 14f        // legacy_e0g.xml slogan 14.0sp
    const val MarqueeMaxWidthDp = 125f   // legacy_e0g.xml maxWidth 125.0dip
    const val MarqueeHeightDp = 38f      // legacy_e0g.xml 38.0dip
    const val SwipeThresholdDp = 72f     // 12 §61.40 手势切歌阈值
    const val NeedleAnimMs = 260L        // 12 §61.40 唱针起落
    const val DiscPeriodMs = 20_000L     // 12 §39 唱片基准周期

    /** 自定值（**非竞品实证**）：迷你条封面旋转周期，竞品未导出该参数，`12 §61.42` 挂账。 */
    const val MiniCoverSpinMs = 12_000L
}

/** D-146 导出的竞品位图 / 字体在 assets 下的路径（51 文件闭包，均 `replaced=false`）。 */
object LegacyAsset {
    private const val ROOT = "legacy-player"
    private const val XX = "$ROOT/drawable-xxhdpi"

    // ── 唱片叠层（`legacy_gc.xml` + `legacy_bv5.xml` 的 RelativeLayout 先后即 z 序） ──
    /** 900x900 浅色圆盘：`legacy_gc.xml` playDiscBg，唱片底层光晕。 */
    const val DiscBacking = "$XX/legacy_ga6.webp"
    /** 828x828 黑色圆环（中心透明）：`legacy_gc.xml` smallAlbumDisc，黑胶盘主体。 */
    const val DiscVinyl = "$XX/legacy_ga0.png"
    /** 924x925 黑色圆环：`legacy_bv5.xml` smallAlbumDisc0NoShadow，外圈。 */
    const val DiscRing = "$XX/legacy_ga9.webp"
    /** 924x924 带阴影黑色圆环：`legacy_bv5.xml` smallAlbumDisc0WithShadow，最上层框。 */
    const val DiscFrame = "$XX/legacy_ga8.png"
    /** 588x588 红色唱片盘（中心轴孔）：`legacy_gc.xml` smallAlbumCover 的默认占位图。 */
    const val DiscRed = "$XX/legacy_g9f.webp"

    // ── 封面 / 装饰 ──
    /** 108x108 黑圆白音符：`legacy_e0g.xml` placeholderIv / coverImage 默认封面。 */
    const val CoverPlaceholder = "$XX/legacy_epi.png"
    /** 780x780 行车电台节目封面：`legacy_gc.xml` programSmallAlbumCover 占位。 */
    const val ProgramCover = "$XX/legacy_dwr.webp"
    /** 36x48 音符小图：`legacy_e0g.xml` slogan 的 drawableStart。 */
    const val NoteGlyph = "$XX/legacy_f6m.png"
    /** 78x78 返回 chevron：`legacy_gr.xml` ivFinish / `legacy_coy.xml` 26dip 矢量。 */
    const val BackArrow = "$XX/legacy_coy.png"
    /** 78x78 外链 / 分享箭头：`legacy_gr.xml` ivShare。 */
    const val ShareArrow = "$XX/legacy_gr7.png"
    /** 87x29「试听中」文字图：`legacy_c6a.xml`。 */
    const val TrialLabel = "$XX/legacy_f6j.webp"

    // ── 播放控制（`legacy_are.xml` 的 id 语义 + 放大位图双向确认） ──
    /** 90x90 `|◀` 上一首：`legacy_are.xml` miPrevBtn。 */
    const val Prev = "$XX/legacy_g_o.webp"
    /** 108x108 `▶` 播放：`legacy_are.xml` miPlayBtn / `legacy_gr.xml`。 */
    const val Play = "$XX/legacy_g_k.webp"
    /** 90x90 `▶|` 下一首：`legacy_are.xml` miNextBtn。 */
    const val Next = "$XX/legacy_g_a.webp"
    /** 78x78 循环箭头：`legacy_are.xml` miModeBtn（只读模式图标）。 */
    const val ModeLoop = "$XX/legacy_gc6.webp"
    /** 84x84 心形：`legacy_are.xml` mi_control_LikeBtn（只读收藏占位）。 */
    const val Favorite = "$XX/legacy_gca.webp"
    /** 84x84 `×`：`legacy_gc.xml` closeBtn。 */
    const val Close = "$XX/legacy_h0g.webp"
    /** 84x84 六边形音效：`legacy_gc.xml` settingBtn。 */
    const val SoundEffect = "$XX/legacy_h0h.webp"

    /** "Dolphin Bold" 文本字体（name table 实证，非图标字体）；`legacy_are.xml` app:xs="@font/a"。 */
    const val Font = "$ROOT/font/legacy_a.ttf"
}

/** 从 assets 读取竞品位图；缺失时返回 null，由 UI 回退到竞品纯色，不崩溃。 */
fun loadLegacyBitmap(context: Context, assetPath: String): Bitmap? = try {
    context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
} catch (_: Exception) {
    null
}

/** 从 assets 读取竞品字体；缺失时由调用方回退到系统字体。 */
fun loadLegacyTypeface(context: Context): Typeface? = try {
    Typeface.createFromAsset(context.assets, LegacyAsset.Font)
} catch (_: Exception) {
    null
}

/** Compose 侧复用：按 assets 路径记忆竞品位图，避免每帧重复解码。 */
@Composable
fun rememberLegacyImageBitmap(assetPath: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(assetPath) { loadLegacyBitmap(context, assetPath)?.asImageBitmap() }
}
