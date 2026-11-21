package com.example.cloudmusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.288：浅色主题是**我方自造**（飞牛无浅色基准，同 §61.234 口径），
 * 所以它的可读性不能被基准掩盖——补出来的浅色不该比深色基准更难读。
 *
 * 背景：§61.283 把 `FnComp` 六个语义令牌 var 化、修掉了「黑胶囊 / 重黑线 / 隐形线」，
 * 但没做对比度量化。量化后发现浅色两处**实际不可读**：
 *
 * | 令牌 | 浅色（改动前） | 对比度 | 深色基准 |
 * |---|---|---|---|
 * | `TextHint` | `0x661A1A1A`（40% 黑） | **2.52:1** / 2.42:1 | 3.58 / 3.77 |
 * | `SearchHint` | `#8A8A8C` | **3.08:1** | 4.72 |
 *
 * 判定口径刻意**不是**「硬追 WCAG AA 4.5」——深色 `TextHint` 本身只有 3.58（逐像素
 * 采样自飞牛，照抄基准），拿 4.5 要求浅色会把提示文字压得比基准重一个档。
 * 口径是「**浅色 ≥ 深色同令牌的实测对比度**」：语义层次与基准保持一致。
 *
 * 本测试是纯 JVM 断言（`Color` 为 value class，通道读取是位运算，不触 Android runtime），
 * 真机观感判读归 V0.4-09（U-12 浅色 / U-16 大字号）。
 */
class FnContrastTest {

    @AfterEach
    fun restoreDark() {
        FnComp.applyTheme(isDark = true)
    }

    // ── WCAG 2.x 相对亮度与对比度 ────────────────────────────────────────────
    private fun argb(c: Color): Int = c.toArgb()
    private fun channel(c: Color, shift: Int): Int = (argb(c) shr shift) and 0xFF

    /** sRGB 分量 → 线性光（WCAG 2.x 定义）。 */
    private fun linear(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(rgb: Triple<Int, Int, Int>): Double =
        0.2126 * linear(rgb.first) + 0.7152 * linear(rgb.second) + 0.0722 * linear(rgb.third)

    private fun contrast(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun rgbOf(c: Color) = Triple(channel(c, 16), channel(c, 8), channel(c, 0))

    /** 把带 α 的前景色合成到不透明背景上（Compose Color 的 α 是 0..1 float）。 */
    private fun over(fg: Color, bg: Color): Triple<Int, Int, Int> {
        val a = fg.alpha.toDouble()
        return Triple(
            (channel(fg, 16) * a + channel(bg, 16) * (1 - a)).roundToInt(),
            (channel(fg, 8) * a + channel(bg, 8) * (1 - a)).roundToInt(),
            (channel(fg, 0) * a + channel(bg, 0) * (1 - a)).roundToInt(),
        )
    }

    /** 某令牌在指定背景上的**有效**对比度（自动合成 α）。 */
    private fun effective(fg: Color, bg: Color): Double = contrast(over(fg, bg), rgbOf(bg))

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("浅色 TextHint 可读性不低于深色基准（40% 黑只有 2.52:1，实测不可读）")
    fun lightTextHintMatchesDarkBaseline() {
        FnComp.applyTheme(isDark = true)
        val darkOnCard = effective(FnComp.TextHint, FnComp.CardBg)
        val darkOnPage = effective(FnComp.TextHint, FnComp.PageBg)

        FnComp.applyTheme(isDark = false)
        val lightOnCard = effective(FnComp.TextHint, FnComp.CardBg)
        val lightOnPage = effective(FnComp.TextHint, FnComp.PageBg)

        assertTrue(
            lightOnCard >= darkOnCard,
            "§61.288：浅色 TextHint on CardBg = %.2f:1，低于深色基准 %.2f:1".format(lightOnCard, darkOnCard),
        )
        assertTrue(
            lightOnPage >= darkOnPage,
            "§61.288：浅色 TextHint on PageBg = %.2f:1，低于深色基准 %.2f:1".format(lightOnPage, darkOnPage),
        )
        // 硬底：提示文字至少要达到非文本 UI 的 3:1，否则等于看不见。
        assertTrue(lightOnCard >= 3.0, "§61.288：浅色 TextHint 连 3:1 都不到（%.2f）".format(lightOnCard))
    }

    @Test
    @DisplayName("浅色 SearchHint 可读性不低于深色基准（#8A8A8C 只有 3.08:1）")
    fun lightSearchHintMatchesDarkBaseline() {
        FnComp.applyTheme(isDark = true)
        val dark = effective(FnComp.SearchHint, FnComp.SearchBg)

        FnComp.applyTheme(isDark = false)
        val lightOnSearch = effective(FnComp.SearchHint, FnComp.SearchBg)
        val lightOnCard = effective(FnComp.SearchHint, FnComp.CardBg)

        assertTrue(
            lightOnSearch >= dark,
            "§61.288：浅色 SearchHint on SearchBg = %.2f:1，低于深色基准 %.2f:1"
                .format(lightOnSearch, dark),
        )
        assertTrue(
            lightOnCard >= dark,
            "§61.288：浅色 SearchHint on CardBg = %.2f:1，低于深色基准 %.2f:1"
                .format(lightOnCard, dark),
        )
    }

    @Test
    @DisplayName("浅色 TextHint 与 TextSecondary 仍可区分（提到 56% 不能与 60% 糊成一层）")
    fun lightHintStaysDistinctFromSecondary() {
        FnComp.applyTheme(isDark = false)
        val hint = effective(FnComp.TextHint, FnComp.CardBg)
        val secondary = effective(FnComp.TextSecondary, FnComp.CardBg)

        assertTrue(
            secondary - hint >= 0.25,
            "§61.288：浅色 TextHint(%.2f) 与 TextSecondary(%.2f) 层次糊在一起"
                .format(hint, secondary),
        )
    }

    @Test
    @DisplayName("深色基准被钉住（不得为修浅色问题而改动逐像素采样值）")
    fun darkBaselineIsPinned() {
        FnComp.applyTheme(isDark = true)
        assertEquals(0xFF1E1E21.toInt(), argb(FnComp.PageBg), "深色 PageBg 是采样值")
        assertEquals(0xFF2B2B2E.toInt(), argb(FnComp.CardBg), "深色 CardBg 是采样值")
        assertEquals(0xFFFFFFFF.toInt(), argb(FnComp.TextPrimary))
        assertEquals(0x99FFFFFF.toInt(), argb(FnComp.TextSecondary))
        assertEquals(0x66FFFFFF.toInt(), argb(FnComp.TextHint), "深色 TextHint 是采样值（40% 白）")
        assertEquals(0xFF2B2B2E.toInt(), argb(FnComp.SearchBg))
        assertEquals(0xFF959597.toInt(), argb(FnComp.SearchHint), "深色 SearchHint 是采样值")
    }

    @Test
    @DisplayName("浅色正文仍达 AA：TextPrimary 充裕，TextSecondary 在卡片底过 4.5")
    fun lightBodyTextIsReadable() {
        FnComp.applyTheme(isDark = false)
        val primary = effective(FnComp.TextPrimary, FnComp.CardBg)
        val secondary = effective(FnComp.TextSecondary, FnComp.CardBg)

        assertTrue(primary >= 10.0, "§61.288：浅色 TextPrimary 只有 %.2f:1".format(primary))
        assertTrue(secondary >= 4.5, "§61.288：浅色 TextSecondary 在卡片底只有 %.2f:1".format(secondary))
        // 分隔线属装饰，只要「可辨」即可（与深色 1.28:1 同级）。
        val rowDivider = contrast(rgbOf(FnComp.RowDivider), rgbOf(FnComp.CardBg))
        assertTrue(rowDivider >= 1.15, "§61.288：浅色行分隔线与卡片底几乎同色（%.2f:1）".format(rowDivider))
    }
}
