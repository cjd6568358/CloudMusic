package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.275（V0.4-02）：错误码 → 人话映射（错误码不得出现在用户文案里）。 */
class ErrorTextTest {

    @Test
    @DisplayName("已知码：给出「原因 + 能否自己解决」的白话，不带原始码")
    fun knownCodes() {
        val cases = listOf(
            "E_DS_UNREACHABLE" to "连接失败",
            "E_DS_AUTH_FAILED" to "认证失败",
            "E_DS_STORAGE_MISSING" to "来源已不存在",
            "E_DS_NOT_SUPPORT" to "无法识别",
            "E_DS_IO" to "读取失败",
        )
        for ((code, needle) in cases) {
            val text = ErrorText.human(code)
            assertTrue(needle in text, code + " → " + text)
            assertFalse(code in text, "用户文案泄漏错误码：" + text)
        }
    }

    @Test
    @DisplayName("带 detail：保留诊断信息在括号里，主句仍是人话")
    fun keepsDetail() {
        val text = ErrorText.human("E_DS_UNREACHABLE: connect timeout")
        assertTrue(text.startsWith("连接失败"))
        assertTrue("connect timeout" in text)
        assertFalse("E_DS" in text)
    }

    @Test
    @DisplayName("未知码 / 空串：不崩、不暴露码值")
    fun unknownAndBlank() {
        assertEquals("", ErrorText.human("  "))
        assertEquals("扫描失败", ErrorText.human("E_DS_WHATEVER"))
        assertEquals("扫描失败（boom）", ErrorText.human("E_DS_WHATEVER: boom"))
    }

    @Test
    @DisplayName("§61.323：新登记的四码都有专属文案，不再落「扫描失败」兜底")
    fun newlyRegisteredCodesHaveOwnText() {
        val fallback = "扫描失败"
        for (code in listOf(
            "E_SCAN_ORPHAN_SOURCE", "E_SCAN_IMPORT", "E_SKIP_GUARD", "E_LINK_TIMEOUT",
        )) {
            val text = ErrorText.human(code)
            assertTrue(text.isNotBlank(), "$code 文案为空")
            assertFalse(text.startsWith(fallback), "$code 仍落兜底文案：$text")
            assertFalse(code in text, "用户文案泄漏错误码：$text")
        }
    }
}
