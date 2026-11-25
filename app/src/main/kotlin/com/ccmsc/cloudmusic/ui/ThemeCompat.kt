package com.ccmsc.cloudmusic.ui

import androidx.compose.ui.graphics.Color

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
    com.ccmsc.cloudmusic.ui.FnComp.applyTheme(isDark)
}
