package com.example.cloudmusic.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `§61.296` 搜索归一化：中文输入法的空格键常吐全角空格 U+3000，
 * 而 `\s` 只认 ASCII——`"周杰伦\u3000稻香".split("\\s+")` 实测只有 1 段。
 * 这些用例钉死「分隔符一律折叠成半角空格、可见字符一个不动」。
 */
class SearchTextTest {

    @Test
    fun `全角空格是分隔符`() {
        assertEquals(listOf("周杰伦", "稻香"), SearchText.tokens("周杰伦\u3000稻香"))
        assertEquals("周杰伦 稻香", SearchText.normalize("周杰伦\u3000稻香"))
    }

    @Test
    fun `不换行空格与窄空格是分隔符`() {
        // U+00A0 / U+202F 的 isWhitespace=false，只靠 isSpaceChar 命中
        assertEquals(listOf("Hotel", "California"), SearchText.tokens("Hotel\u00A0California"))
        assertEquals(listOf("a", "b"), SearchText.tokens("a\u202Fb"))
        assertEquals(listOf("a", "b"), SearchText.tokens("a\u2009b"))
    }

    @Test
    fun `连续分隔符折叠成一个半角空格 首尾去掉`() {
        assertEquals("晴天 周杰伦", SearchText.normalize("\u3000 晴天 \t\u3000 周杰伦\n\u3000"))
        assertEquals("a b c", SearchText.normalize("a\u3000\u3000b\t\tc"))
    }

    @Test
    fun `纯全角空格归一为空 不依赖 trim`() {
        // Java 的 String.trim() 只剥 <= U+0020（"\u3000\u3000".trim().length == 2），
        // 所以这里必须由 normalize 自己判定，不能指望 trim 兜底。
        assertEquals("", SearchText.normalize("\u3000\u3000"))
        assertTrue(SearchText.tokens("\u3000\u3000").isEmpty())
        assertEquals("", SearchText.normalize(""))
        assertTrue(SearchText.tokens("").isEmpty())
    }

    @Test
    fun `可见字符原样保留 不做大小写或字形折叠`() {
        assertEquals("California Love", SearchText.normalize("California Love"))
        assertEquals("晴天", SearchText.normalize("\u3000晴天\u3000"))
        // 不做 NFKC：全角字母ＡＢＣ保持原样，避免把用户输入改成别的字
        assertEquals("ＡＢＣ", SearchText.normalize("ＡＢＣ"))
    }

    @Test
    fun `归一化幂等`() {
        val once = SearchText.normalize(" 周杰伦\u3000\u3000稻香 ")
        assertEquals(once, SearchText.normalize(once))
        assertEquals(listOf("周杰伦", "稻香"), SearchText.tokens(once))
    }

    @Test
    fun `词元不含空串`() {
        val tokens = SearchText.tokens("  周杰伦 \u3000 稻香  ")
        assertFalse(tokens.any { it.isEmpty() })
        assertEquals(2, tokens.size)
    }
}
