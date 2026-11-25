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

    /** 真机启动屏 1440x3200 六点采样，2026-11-09，值一致（渐变顶）。 */
    val BrandRed = 0xFFA22016.toInt()

    /** 真机启动屏渐变底（docs/01 §0 严格像素级，2026-11-16 PIL 采样 #AE2318）。 */
    val SplashRedBottom = 0xFFAE2318.toInt()

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
    // §61.109 自定值（基准图目测，竞品未导出该皮肤参数；真机截图比对后校准）：
    const val PlayerTitleSp = 22f        // 基准图唱片下方大字号标题
    const val InfoRowIconDp = 24f        // 基准图标题行右侧心形（真实素材 gca）
    const val SecondaryControlDp = 26f   // 基准图副控制行图标
    const val MarqueeTextSp = 14f        // legacy_e0g.xml slogan 14.0sp
    const val MarqueeMaxWidthDp = 125f   // legacy_e0g.xml maxWidth 125.0dip
    const val MarqueeHeightDp = 38f      // legacy_e0g.xml 38.0dip
    const val SwipeThresholdDp = 72f     // 12 §61.40 手势切歌阈值
    const val NeedleAnimMs = 260L        // 12 §61.40 唱针起落
    const val DiscPeriodMs = 20_000L     // 12 §39 唱片基准周期

    /** 自定值（**非竞品实证**）：迷你条封面旋转周期，竞品未导出该参数，`12 §61.42` 挂账。 */
    const val MiniCoverSpinMs = 12_000L
}

/**
 * §61.174（2026-11-16 播放页整体 1:1）：**基准图像素量化表**。
 *
 * 与 [LegacyMetrics] 的区别：[LegacyMetrics] 全部来自反编译 XML（dp/sp 原值）；
 * 本表全部来自竞品真机基准图 `analysis/l3-reference/competitor-player-baseline.png`
 * （1440x3200 @density 3.5 → 411.43 x 914.29 dp）的**逐像素测量**，用于还原
 * 「竞品皮肤实际渲染出来的位置与尺寸」。可复跑证据：`tools/measure_player_baseline.py`。
 *
 * 测量方法：按行/列统计亮像素带（文字、图标、轨道）与暗中性像素（黑胶环），
 * 得到每个元素的 bbox 中心与跨度，再除以 density。误差 ±1dp（基准图抗锯齿）。
 *
 * 内容区基准：状态栏底 y=37dp，导航栏顶 y=871dp，可用高 834dp。
 */
object PlayerBaseline {
    // ── 顶栏 ────────────────────────────────────────────────────────────────
    /** 顶栏高：chevron 44dip 槽位（[LegacyMetrics.BackBarHeightDp]）与基准文字带 46dp 取齐。 */
    const val TopBarHeightDp = 46f
    /** chevron 中心 x = 28dp → 左边距。 */
    const val TopBarSidePaddingDp = 18f
    /** 顶栏图标视觉尺寸（chevron 17dp / 刷新 19.4dp）。 */
    const val TopBarIconDp = 20f
    /** 居中标题字号（基准文字带高 19dp）。 */
    const val TopBarTitleSp = 14f

    // ── 唱片 ────────────────────────────────────────────────────────────────
    /** 唱片外接圆直径 / 屏宽：1087px / 1440px = 0.7549（实测 310.6dp）。 */
    const val DiscDiameterRatio = 0.755f
    /** 唱片直径占唱片区高度的上限（310.6dp / 462dp = 0.672），防矮屏溢出。 */
    const val DiscMaxHeightRatio = 0.672f

    // ── 唱针 ────────────────────────────────────────────────────────────────
    /** 轴心 x / 屏宽：719.5px / 1440px = 0.500（**正好水平居中**）。 */
    const val NeedlePivotXRatio = 0.500f
    /** 轴心 y / 唱片区高：基准绝对 149.3dp，唱片区自 83.1dp 起 → 66.2/462 = 0.143。 */
    const val NeedlePivotYRatio = 0.143f
    /** 轴心白圆半径：水平最宽 60px / 2 / 3.5 = 8.6dp。 */
    const val NeedlePivotRadiusDp = 8.6f
    /** 唱臂杆宽：22px = 6.3dp。 */
    const val NeedleArmWidthDp = 6.3f
    /** 唱头中心 x / 屏宽：998px / 1440px = 0.693。 */
    const val NeedleHeadXRatio = 0.693f
    /** 唱头中心 y / 唱片区高：基准绝对 289.1dp → 206/462 = 0.446。 */
    const val NeedleHeadYRatio = 0.446f
    /** 唱头块尺寸：斜置后整体跨度 108x~110px → 块本体约 23x19dp（倾角 24°）。 */
    const val NeedleHeadWidthDp = 23f
    const val NeedleHeadHeightDp = 19f

    // ── 文本（唱片下方） ────────────────────────────────────────────────────
    /**
     * 文本块纵向标定（`12 §61.174` 修正，2026-11-16 二次量测）：
     * 旧值 `TitleSp=24` 是**从字带高 17.4dp 反推**的错值——CJK 字墨高 ≈ 0.94em 而非 1em。
     * 改用**字符步进**（同排汉字相邻左缘间距，等价 1em 宽）重测：
     * 标题 27.1 / 44.6 / 61.7 / 78.9 → 步进 17.5 / 17.1 / 17.2 ⇒ **17.3sp**；
     * 艺人 27.1 / 41.7 / 54.9 → 步进 14.6 / 13.2 ⇒ **13.5sp**（含半角字重偏差）。
     * 24sp 塞进 18dp 行框必然裁字，这才是真机「标题只剩半截」的根因，而非行高不够。
     */
    const val TitleSp = 17.5f
    const val ArtistSp = 13.5f
    /** 标题 / 艺人左起 x = 95px = 27dp。 */
    const val SidePaddingDp = 27f
    /**
     * 唱片底(545.7) → 标题行框顶(598.3) = 52.6。其中竞品「422人」在线胶囊（568.3~576.9）
     * 无数据源故省略，留白并入本段。
     */
    const val GapDiscToTitleDp = 52.6f
    /** 标题行框高：容 17.5sp 文字（行盒 ≈ 20.5dp + 字体上下留白），居中后字带 602.2~618.5。 */
    const val TitleRowHeightDp = 24f
    /**
     * 标题行底(622.3) → 艺人行顶(628.1) = 5.8。
     * 基准图里标题字带底 618.3 与艺人字带顶 632.3 之间实为 14dp 空档（竞品此处在艺人名后
     * 挂「关注」胶囊，胶囊无数据源已省略，其垂直占位保留）。
     */
    const val GapTitleToArtistDp = 5.8f
    /** 艺人行框高：13.5sp 居中，字带 632.2~645.0（基准 632.3~645.1）。 */
    const val ArtistRowHeightDp = 21f
    /** 艺人行底(649.1) → 进度条触控区顶(665.4) = 16.2。 */
    const val GapArtistToProgressDp = 16.2f

    // ── 进度条 ──────────────────────────────────────────────────────────────
    /** 轨道左右边距：106px / 30dp；右端 1333px → 381dp（右边距 30dp）。 */
    const val ProgressSidePaddingDp = 30f
    /** 轨道高：5px ≈ 1.4dp，取 2dp 保证可见（基准实测 1.4~2dp）。 */
    const val ProgressTrackDp = 2f
    /** 滑块直径：18px = 5.1dp。 */
    const val ProgressThumbDp = 5f
    /** 时间行字号：字高 30px = 8.6dp → 约 11sp。 */
    const val TimeSp = 11f
    /** 进度条触控区高（轨道居中，含上下拖动余量）。 */
    const val ProgressTouchDp = 19f
    /** 进度区底 → 时间行顶：基准 684.4dp → 687.1dp。 */
    const val GapProgressToTimeDp = 2.7f
    /** 时间行高（11sp）。 */
    const val TimeRowHeightDp = 15f

    // ── 控制行 ──────────────────────────────────────────────────────────────
    /** 时间行底 → 主控制行顶：702dp → 744.2dp = 42dp。 */
    const val GapTimeToMainControlDp = 42.1f
    /** 主控制行图标：上一首 / 下一首 60px = 17dp 视觉宽（`legacy_g_o` 90px 位图内缩）。 */
    const val MainControlIconDp = 30f
    /** 暂停双条：宽 33px = 9.4dp，高 104px = 29.7dp，条间距 30px = 8.6dp。 */
    const val PauseBarWidthDp = 9.4f
    const val PauseBarHeightDp = 29.7f
    const val PauseBarGapDp = 8.6f
    /** 模式 / 队列图标：87px / 66px → 取 24dp。 */
    const val MainControlSideDp = 24f
    /** 主控制行底 → 副控制行顶：774.2dp → 820.6dp = 46dp。 */
    const val GapMainToSecondaryDp = 46.2f
    /** 副控制行图标：基准 6~20dp（各行差异大），取 16dp 与带高一致。 */
    const val SecondaryControlDp = 16f
    /** 副控制行底 → 内容底（导航栏上缘 853.7dp）：836.6 → 853.7 = 17dp。 */
    const val BottomPaddingDp = 17.4f

    // ── §61.354（G-11）：副控制行重设（用户要求删死控件、触控 ≥40dp） ──────────
    /** 副控制行高：原 16dp（竞品像素基准 SecondaryControlDp）触点远小于 40dp；抬到 44dp。 */
    const val SecondaryRowHeightDp = 44f
    /** 副控制行图标视觉尺寸（三个真动作：音效 / 歌曲信息 / 输出）。 */
    const val SecondaryIconDp = 24f
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
    /**
     * 588x588 红色唱片盘（中心轴孔）：`legacy_gc.xml` smallAlbumCover 的默认占位图。
     *
     * **§61.278 起退役**：该位图带两圈竞品版权文字（「网易云音乐保留全部著作权权利」/
     * "All rights are reserved by NetEase Cloud Music"），播放页占位盘改自绘
     * [DiscLabelArt]。常量保留仅作素材出处留痕，**UI 侧禁止再引用**（守护 §61.278）。
     */
    @Deprecated("竞品版权文字素材，改用 DiscLabelArt 自绘红标")
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
