import io

# ① CompeteUi.Comp：色值 var 化 + applyTheme（基色映射；品牌红不动）
p = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/CompeteUi.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''object Comp {
    /** `aei` —— 曲库/搜索/歌单页背景。 */
    val PageBg = Color(0xFF222222)
    /** `aer` —— 曲目行容器背景。 */
    val RowBg = Color(0xFF212121)
    /** `a6a`（@drawable/b5y）—— 弹窗/底部面板底色。 */
    val DialogBg = Color(0xF71C1C1C)
    /** `b1v` —— 行选中态叠色（bec selector）。 */
    val RowSelected = Color(0x12FFFFFF)
    /** `b1y` —— 历史词 chip 背景（bfi）。 */
    val ChipBg = Color(0x14FFFFFF)
    /** 封面占位底色（`Track.coverRef` 缺失/解码失败时回退，§61.138 真实封面已接）。 */
    val CoverPlaceholder = Color(0x26FFFFFF)
    /** `b12` —— 主文本（白）。 */
    val TextPrimary = Color(0xFFFFFFFF)
    /** `b17`/`b1n` —— 次级文本（歌手 · 时长 / 歌单副标题）。 */
    val TextSecondary = Color(0x66FFFFFF)
    /** `b1h` —— hint。 */
    val TextHint = Color(0x33FFFFFF)
    /** `b1t` —— 60% 白（三级提示）。 */
    val TextWhite60 = Color(0x99FFFFFF)
    /** `b18` —— tab 未选中 / 描边钮文字。 */
    val TabUnselected = Color(0x4DFFFFFF)
    /** `b1p` —— 0.5dip 分割线。 */
    val Divider = Color(0x0DFFFFFF)
    /** 品牌红（cvg CustomButton `aer`；PRD §13 #9 同源）。 */
    val AccentRed = Color(0xFFFF3A3A)
    /** `#4DFF3A3A` —— 红系淡背景。 */
    val AccentRedSub = Color(0x4DFF3A3A)

    /** 底栏/Tab 背景（§61.136 h7/qx——竞品主底栏无原生 layout，挂账待真机对照）。 */
    val BarBg = Color(0xFF262626)
    /** `ap` —— 底栏未选中。 */
    val BarUnselected = Color(0x99FFFFFF)
}''',
'''object Comp {
    /**
     * §61.234（V0.2 主题化）：色值 var 化——深色基准（竞品像素级）与浅色映射由
     * [applyTheme] 切换，Activity recreate 后生效（设置页改主题即 recreate）。
     * 品牌红 / 红系淡背景不随主题（品牌色恒定）。
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
    /** 品牌红（cvg CustomButton `aer`；PRD §13 #9 同源）——主题恒定。 */
    val AccentRed = Color(0xFFFF3A3A)
    /** `#4DFF3A3A` —— 红系淡背景——主题恒定。 */
    val AccentRedSub = Color(0x4DFF3A3A)

    /** 底栏/Tab 背景（§61.136 h7/qx——竞品主底栏无原生 layout，挂账待真机对照）。 */
    var BarBg = Color(0xFF262626)
    /** `ap` —— 底栏未选中。 */
    var BarUnselected = Color(0x99FFFFFF)

    /** 深色基准快照（applyTheme 回深色时恢复竞品像素级原值）。 */
    private val darkBase = mapOf(
        "PageBg" to 0xFF222222, "RowBg" to 0xFF212121, "DialogBg" to 0xF71C1C1C,
        "RowSelected" to 0x12FFFFFF, "ChipBg" to 0x14FFFFFF, "CoverPlaceholder" to 0x26FFFFFF,
        "TextPrimary" to 0xFFFFFFFF, "TextSecondary" to 0x66FFFFFF, "TextHint" to 0x33FFFFFF,
        "TextWhite60" to 0x99FFFFFF, "TabUnselected" to 0x4DFFFFFF, "Divider" to 0x0DFFFFFF,
        "BarBg" to 0xFF262626, "BarUnselected" to 0x99FFFFFF,
    )

    /**
     * §61.234：主题切色。深色 = 竞品基准原值；浅色 = 通用映射（竞品无浅色基准，
     * **非像素级**，如实登记）：背景反转白灰、白系 alpha 文字 → 黑系 alpha、品牌红不变。
     */
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
}''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ② FeiniuUi.FnComp：同款 var 化 + applyTheme
p = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FeiniuUi.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''object FnComp {
    val PageBg = Color(0xFF1E1E21)
    val CardBg = Color(0xFF2B2B2E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0x99FFFFFF)
    val TextHint = Color(0x66FFFFFF)
    val BrandOrange = Color(0xFFFD7423)
    val IconRed = Color(0xFFE54545)
    val SwitchGreen = Color(0xFF0DD07F)''',
'''object FnComp {
    /** §61.234：主题切色（同 [Comp.applyTheme] 口径；品牌色 BrandOrange/IconRed/SwitchGreen 恒定）。 */
    var PageBg = Color(0xFF1E1E21)
    var CardBg = Color(0xFF2B2B2E)
    var TextPrimary = Color(0xFFFFFFFF)
    var TextSecondary = Color(0x99FFFFFF)
    var TextHint = Color(0x66FFFFFF)
    /** 品牌色——主题恒定。 */
    val BrandOrange = Color(0xFFFD7423)
    val IconRed = Color(0xFFE54545)
    val SwitchGreen = Color(0xFF0DD07F)''')
s = s.replace('''    val SearchBg = Color(0xFF2B2B2E)
    /** 搜索占位文字：实测 `#959597`（折算 50% 白叠加在 `#2B2B2E` 上）。 */
    val SearchHint = Color(0xFF959597)''',
'''    var SearchBg = Color(0xFF2B2B2E)
    /** 搜索占位文字：实测 `#959597`（折算 50% 白叠加在 `#2B2B2E` 上）。 */
    var SearchHint = Color(0xFF959597)''')
# FnComp.applyTheme 追加（object 尾部）
import re
m = re.search(r'(object FnComp \{.*?\n)(\})', s, re.S)
assert m
fn_theme = m.group(1) + '''
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
}'''
s = s[:m.start()] + fn_theme + s[m.end():]
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("tokens done")
