package com.example.cloudmusic.strm

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/03-03 §7 T01~T04 与 §2.2 边界 B01~B05。 */
class StrmParserTest {

    @Test
    @DisplayName("03-03 T01 首行注释 + 第二行 URL，query 原样保留")
    fun t01() {
        val result = parseStrm("# 自动生成\n\n  https://nas.example.com/a.flac?sign=abc&e=123  \nhttps://backup/b.flac")
        assertEquals(StrmParseResult.Url("https://nas.example.com/a.flac?sign=abc&e=123"), result)
    }

    @Test
    @DisplayName("03-03 T02 UTF-8 BOM 剥掉后正常解析")
    fun t02() {
        assertEquals(StrmParseResult.Url("https://x/a.mp3"), parseStrm("\uFEFFhttps://x/a.mp3"))
        // BOM 后紧跟注释行也不能被误当成 URL
        assertEquals(StrmParseResult.Url("https://x/b.mp3"), parseStrm("\uFEFF# note\nhttps://x/b.mp3"))
    }

    @Test
    @DisplayName("03-03 T03 非 http/https scheme 不执行、不联网")
    fun t03() {
        assertEquals(StrmParseResult.Unsupported("smb"), parseStrm("smb://nas/a.flac"))
        assertEquals(StrmParseResult.Unsupported("file"), parseStrm("file:///sdcard/a.flac"))
        assertEquals(StrmParseResult.Unsupported(""), parseStrm("not-a-url"))
    }

    @Test
    @DisplayName("03-03 T04 全空行 / 全注释 → Empty")
    fun t04() {
        assertEquals(StrmParseResult.Empty, parseStrm("\n  \n# a\n# b\n"))
    }

    @Test
    @DisplayName("B05 第二行备用源忽略；B04 带签名 URL 不重组")
    fun ignoreSecondLine() {
        val url = "https://x/a.flac?X-Amz-Signature=deadbeef"
        assertEquals(StrmParseResult.Url(url), parseStrm("$url\nhttps://y/b.flac"))
    }

    @Test
    @DisplayName("03-03 §2.2 文件名兜底 艺人 - 标题")
    fun titleHint() {
        assertEquals("周杰伦" to "晴天", strmTitleHint("周杰伦 - 晴天.strm"))
        assertEquals(null, strmTitleHint("晴天.strm"))
    }

    @Test
    @DisplayName("07 §8 安全描述不含完整签名 URL")
    fun safeDescription() {
        val description = safeStrmDescription("https://x/a.flac?sign=SECRET")
        assertTrue("SECRET" !in description)
        assertTrue("https://" !in description)
        assertTrue("hasQuery=true" in description)
    }
}
