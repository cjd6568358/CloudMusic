package com.ccmsc.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §61.170（2026-11-16 真机首页一手）：旧 ID3 GBK 乱码修复的纯 JVM 契约测试。 */
class LegacyTextRepairTest {

    /** 把 GBK 字节串当 ISO-8859-1 解出来 —— 真机上的乱码形态。 */
    private fun mojibake(gbk: ByteArray): String = String(gbk, Charsets.ISO_8859_1)

    @Test
    @DisplayName("GBK 中文标题被当 Latin-1 解出后能还原")
    fun repairsGbkTitle() {
        val gbk = byteArrayOf(
            0xD6.toByte(), 0xDC.toByte(), // 周
            0xBD.toByte(), 0xDC.toByte(), // 杰
            0xC2.toByte(), 0xD7.toByte(), // 伦
        )
        val garbled = mojibake(gbk)
        assertEquals("周杰伦", LegacyTextRepair.fix(garbled))
    }

    @Test
    @DisplayName("ASCII 标题原样返回（不触发任何重解）")
    fun keepsAscii() {
        assertEquals("Hello World", LegacyTextRepair.fix("Hello World"))
    }

    @Test
    @DisplayName("正常中文（已是 Unicode）原样返回")
    fun keepsUnicodeChinese() {
        assertEquals("周杰伦", LegacyTextRepair.fix("周杰伦"))
    }

    @Test
    @DisplayName("真 Latin-1 文本不被误改：Café")
    fun keepsLatin1Accent() {
        // 'é' = U+00E9，单字节 0xE9 在 GB18030 中是不完整序列 ⇒ 必回退
        assertEquals("Café", LegacyTextRepair.fix("Café"))
    }

    @Test
    @DisplayName("§61.173 百分号编码：解出汉字才采用，纯英文不误改")
    fun decodesPercentOnlyWhenCjk() {
        // 真机 STRM 残留原值
        assertEquals("S.H.E - \u8331\u7F57\u8BB0", LegacyTextRepair.fix("S.H.E%20-%20%E8%8C%B1%E7%BD%97%E8%AE%B0"))
        assertEquals("G.E.M.\u9093\u7D2B\u68CB", LegacyTextRepair.fix("G.E.M.%E9%82%93%E7%B4%AB%E6%A3%8B"))
        // 通用通道保守：纯英文/无 CJK 不采用（原文返回）
        assertEquals("My%20Song", LegacyTextRepair.fix("My%20Song"))
        // 文件名通道放宽
        assertEquals("My Song", LegacyTextRepair.decodePercent("My%20Song", requireCjk = false))
        // 字面 '+' 不被当成空格
        assertEquals("A+B C", LegacyTextRepair.decodePercent("A+B%20C", requireCjk = false))
        // 非法编码 / 无转义原样
        assertEquals(null, LegacyTextRepair.decodePercent("100%", requireCjk = false))
        assertEquals(null, LegacyTextRepair.decodePercent("plain", requireCjk = false))
    }

    @Test
    @DisplayName("空 / null 安全")
    fun handlesEmpty() {
        assertNull(LegacyTextRepair.fix(null))
        assertEquals("", LegacyTextRepair.fix(""))
    }

    @Test
    @DisplayName("真机 WEBDAV 残留样本：大约在冬季 / 秋天不回来 / 刘德华")
    fun repairsRealDeviceSamples() {
        // 全用 \u 转义钉死字符，避免源码编码差异引入误差。
        // 真机残留原值：0xB4 0xF3 0xD4 0xBC 0xD4 0xDA 0xB6 0xAC 0xBC 0xBE
        assertEquals("\u5927\u7EA6\u5728\u51AC\u5B63", LegacyTextRepair.fix("\u00B4\u00F3\u00D4\u00BC\u00D4\u00DA\u00B6\u00AC\u00BC\u00BE"))
        // 0xC7 0xEF 0xCC 0xEC 0xB2 0xBB 0xBB 0xD8 0xC0 0xB4
        assertEquals("\u79CB\u5929\u4E0D\u56DE\u6765", LegacyTextRepair.fix("\u00C7\u00EF\u00CC\u00EC\u00B2\u00BB\u00BB\u00D8\u00C0\u00B4"))
        // 0xC1 0xF5 0xB5 0xC2 0xBB 0xAA
        assertEquals("\u5218\u5FB7\u534E", LegacyTextRepair.fix("\u00C1\u00F5\u00B5\u00C2\u00BB\u00AA"))
    }

    @Test
    @DisplayName("含非拉丁字符的文本不走重解（避免二次破坏日文等）")
    fun skipsNonLatin() {
        // 假名 U+3042 > U+00FF ⇒ looksLikeHighSingleByte 直接 false
        assertEquals("あいう", LegacyTextRepair.fix("あいう"))
    }
}
