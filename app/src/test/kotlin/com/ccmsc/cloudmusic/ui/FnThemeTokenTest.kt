package com.ccmsc.cloudmusic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §61.283：`FnComp` 语义令牌必须随主题切色。
 *
 * 背景：§61.234 主题化时 `FnComp` 只 var 化了 7 个令牌，另有 5 个语义令牌
 * （封面占位 / 分隔线 / 输入框底 / 行间分隔线 / 胶囊底）留成 `val` 深色常量，
 * 外加 `FnSwitch` 关闭态硬编码 `#3A3A3E`。§61.277 飞牛化全面铺开后暴露：
 * 用户切「浅色」主题会看到**黑胶囊、重黑分隔线、看不见的分隔线与输入框、深灰封面占位**。
 *
 * 本测试是纯 JVM 断言（`Color` 为 value class，通道读取是纯位运算，不触 Android runtime），
 * 真机观感判读仍归 V0.4-09。
 */
class FnThemeTokenTest {

    /** 全局 object 有可变状态——每条用例结束后复位深色，避免污染同 JVM 的其他测试。 */
    @AfterEach
    fun restoreDark() {
        FnComp.applyTheme(isDark = true)
    }

    private fun argb(c: Color): Int = c.toArgb()
    private fun r(c: Color): Int = (argb(c) shr 16) and 0xFF
    private fun g(c: Color): Int = (argb(c) shr 8) and 0xFF
    private fun b(c: Color): Int = argb(c) and 0xFF

    @Test
    @DisplayName("六个语义令牌在浅深两态下取值互异（val 化回归会立刻炸在这里）")
    fun semanticTokensFlipWithTheme() {
        FnComp.applyTheme(isDark = true)
        val dark = listOf(
            FnComp.CoverPlaceholder, FnComp.Divider, FnComp.InputBg,
            FnComp.RowDivider, FnComp.CapsuleBg, FnComp.SwitchOffBg,
        )
        FnComp.applyTheme(isDark = false)
        val light = listOf(
            FnComp.CoverPlaceholder, FnComp.Divider, FnComp.InputBg,
            FnComp.RowDivider, FnComp.CapsuleBg, FnComp.SwitchOffBg,
        )

        val names = listOf("CoverPlaceholder", "Divider", "InputBg", "RowDivider", "CapsuleBg", "SwitchOffBg")
        names.forEachIndexed { i, name ->
            assertNotEquals(dark[i], light[i], "§61.283：$name 未随主题切色（仍是深色常量）")
        }
    }

    @Test
    @DisplayName("浅色下不出现深色块：胶囊/占位/开关轨道必须比白卡片深一档而非深色块")
    fun lightModeHasNoDarkBlocks() {
        FnComp.applyTheme(isDark = false)

        // 深色基准：这三个在深色下是 #2B2B2E / #3A3A3E 级别的深灰（RGB 均 < 0x40）。
        listOf(
            "CoverPlaceholder" to FnComp.CoverPlaceholder,
            "CapsuleBg" to FnComp.CapsuleBg,
            "SwitchOffBg" to FnComp.SwitchOffBg,
            "RowDivider" to FnComp.RowDivider,
        ).forEach { (name, c) ->
            assertTrue(
                r(c) > 0xB0 && g(c) > 0xB0 && b(c) > 0xB0,
                "§61.283：浅色主题下 $name 仍是深色块（argb=${argb(c).toUInt().toString(16)}）",
            )
        }

        // 反向：必须仍与纯白有区分，否则等于看不见（「比卡片深一档」的语义不能丢）。
        listOf(
            "CoverPlaceholder" to FnComp.CoverPlaceholder,
            "CapsuleBg" to FnComp.CapsuleBg,
            "SwitchOffBg" to FnComp.SwitchOffBg,
            "RowDivider" to FnComp.RowDivider,
        ).forEach { (name, c) ->
            assertTrue(
                r(c) < 0xFA || g(c) < 0xFA || b(c) < 0xFA,
                "§61.283：浅色主题下 $name 与白卡片同色（不可辨）",
            )
        }
    }

    @Test
    @DisplayName("浅色下 α 叠加色必须反相为黑系，否则浅底上完全不可见")
    fun translucentTokensInvertToBlack() {
        FnComp.applyTheme(isDark = true)
        // 深色：#1AFFFFFF（10% 白）/ #14FFFFFF（8% 白）——正是「浅底上不可见」的成因。
        assertTrue(r(FnComp.Divider) > 0xE0 && r(FnComp.InputBg) > 0xE0)

        FnComp.applyTheme(isDark = false)
        assertTrue(r(FnComp.Divider) < 0x20, "§61.283：浅色 Divider 未反相为黑系")
        assertTrue(r(FnComp.InputBg) < 0x20, "§61.283：浅色 InputBg 未反相为黑系")
        // α 保留（分隔线 10% / 输入底 8%），否则会变成实心黑条。
        assertTrue(FnComp.Divider.alpha < 0.20f && FnComp.Divider.alpha > 0.02f)
        assertTrue(FnComp.InputBg.alpha < 0.20f && FnComp.InputBg.alpha > 0.02f)
    }

    @Test
    @DisplayName("切回深色可精确复位（单程赋值会炸）")
    fun returningToDarkRestoresBaseline() {
        FnComp.applyTheme(isDark = false)
        FnComp.applyTheme(isDark = true)

        assertEquals(0xFF1E1E21.toInt(), argb(FnComp.PageBg))
        assertEquals(0xFF2B2B2E.toInt(), argb(FnComp.CardBg))
        assertEquals(0xFF3A3A3E.toInt(), argb(FnComp.CoverPlaceholder))
        assertEquals(0x1AFFFFFF, argb(FnComp.Divider))
        assertEquals(0x14FFFFFF, argb(FnComp.InputBg))
        assertEquals(0xFF3C3C3F.toInt(), argb(FnComp.RowDivider))
        assertEquals(0xFF2B2B2E.toInt(), argb(FnComp.CapsuleBg))
        assertEquals(0xFF3A3A3E.toInt(), argb(FnComp.SwitchOffBg))
    }

    @Test
    @DisplayName("§61.323 · 强调色唯一（红）且不随主题变；橙已归并删除")
    fun accentColorIsThemeConstant() {
        FnComp.applyTheme(isDark = true)
        val iconRed = argb(FnComp.IconRed)
        val green = argb(FnComp.SwitchGreen)

        FnComp.applyTheme(isDark = false)
        assertEquals(iconRed, argb(FnComp.IconRed))
        assertEquals(green, argb(FnComp.SwitchGreen))
        // A-2 拍板后主色只有一个：红。橙若被重新引入，这里会因编译失败而暴露。
        assertEquals(0xFFE54545.toInt(), iconRed, "强调色必须是 IconRed #E54545")
    }

    @Test
    @DisplayName("§61.323 · 源码侧不留第二主色（BrandOrange 全仓移除）")
    fun noSecondPrimaryColor() {
        // 只扫 app 模块 UI 源码：残留的 `BrandOrange`（含注释）都算未收口——
        // 注释里留着会让后来者以为还有第二个可用主色。
        val uiDir = java.io.File("src/main/kotlin/com/ccmsc/cloudmusic/ui")
        val files = uiDir.walkTopDown().filter { it.extension == "kt" }.toList()
        // 防「目录不存在 → 扫到 0 个文件 → 恒 PASS」的假判据。
        assertTrue(files.size > 10, "UI 源码目录未定位到（找到 ${files.size} 个 .kt）：${uiDir.absolutePath}")
        val hits = files
            .flatMap { f -> f.readLines().map { f.name to it } }
            .filter { (_, line) -> "BrandOrange" in line }
        assertTrue(
            hits.isEmpty(),
            "第二主色残留：" + hits.take(3).joinToString("; ") { "${it.first}: ${it.second.trim()}" },
        )
    }
}
