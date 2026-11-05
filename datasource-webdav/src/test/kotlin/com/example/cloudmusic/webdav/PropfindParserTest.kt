package com.example.cloudmusic.webdav

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 03-01 §12 T17、03-02 §9 T07~T09：PROPFIND 四条硬规则 W1~W4。 */
class PropfindParserTest {

    @Test
    @DisplayName("03-01 T17 跳过首 response、尾斜杠归一化后不重复、无幽灵目录")
    fun t17() {
        val items = PropfindParser.parse(NEXTCLOUD)
        assertEquals(2, items.size)
        assertTrue(items.none { it.name == "music" }, "集合自身不应出现在子项里")
        assertEquals(1, items.count { it.name == "Album" }, "带/不带尾斜杠的同一目录应去重")
        assertTrue(items.first { it.name == "Album" }.isDir)
        assertTrue(items.first { it.name == "Album" }.modified > 0)
    }

    @Test
    @DisplayName("03-02 T07 / W3 D: / d: / 无前缀混用都能解析")
    fun t07() {
        assertEquals(2, PropfindParser.parse(NEXTCLOUD).size, "D:/d:/无前缀混用解析后应去重为 2 项")
        val nginx = """
            <?xml version="1.0"?>
            <multistatus xmlns="DAV:">
              <response><href>/music/</href><propstat><prop><resourcetype><collection/></resourcetype></prop></propstat></response>
              <response><href>/music/a.flac</href><propstat><prop><getcontentlength>2048</getcontentlength></prop></propstat></response>
            </multistatus>
        """.trimIndent()
        val items = PropfindParser.parse(nginx)
        assertEquals(1, items.size)
        assertEquals("a.flac", items.single().name)
        assertEquals(2048L, items.single().size)
    }

    @Test
    @DisplayName("03-02 T09 / W4 中文与空格文件名 URL-decode 正确")
    fun t09() {
        val items = PropfindParser.parse(NEXTCLOUD)
        val song = items.first { it.name.contains("歌") }
        assertEquals("中文 歌.flac", song.name)
        assertEquals(1024L, song.size)
        assertEquals("audio/flac", song.contentType)
    }

    @Test
    @DisplayName("get 场景：skipFirst=false 保留集合自身")
    fun skipFirstFalse() {
        val items = PropfindParser.parse(NEXTCLOUD, skipFirst = false)
        assertEquals(3, items.size)
        assertEquals("music", items.first().name)
    }

    companion object {
        val NEXTCLOUD = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/music/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/music/Album/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>
                  <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <d:response xmlns:d="DAV:">
                <d:href>/music/Album</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <response>
                <href>/music/%E4%B8%AD%E6%96%87%20%E6%AD%8C.flac</href>
                <propstat><prop><getcontentlength>1024</getcontentlength>
                  <getcontenttype>audio/flac</getcontenttype></prop></propstat>
              </response>
            </D:multistatus>
        """.trimIndent()
    }
}
