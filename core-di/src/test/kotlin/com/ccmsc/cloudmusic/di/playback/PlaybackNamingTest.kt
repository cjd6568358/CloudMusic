package com.ccmsc.cloudmusic.di.playback

import com.ccmsc.cloudmusic.model.SourceType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.182 · 注入通道命名同源契约（三层守护网 ① 初版）。
 *
 * `remoteFixtureTrack`（playUrl 的 Track 构造）此前直接取 URL 原始路径段当曲名，
 * 真机一手天书标题 `V.A.%20-%20Love%20Letter%20%28Piano%29`（用户判读 §61.181
 * 判据② 时发现）。修复 = 与扫描管线同一清洗入口 `NameParser`（%XX 解码 + GBK
 * 修复，§61.170 口径）——本测试把「注入曲目的命名行为 = 扫描行为」钉死。
 */
class PlaybackNamingTest {

    /** 真机一手 URL（用户看到的原样标题），§61.182 判据② 复验素材。 */
    private val loveLetterUrl = "http://192.168.1.1:15244/d/ecloud_self/" +
        "%E6%88%91%E7%9A%84%E9%9F%B3%E4%B9%90/V.A.%20-%20Love%20Letter%20%28Piano%29.mp3" +
        "?sign=abc:0"

    @Test
    @DisplayName("百分号编码曲名解码 + 艺人 - 标题 拆分，与扫描链同口径")
    fun decodesPercentAndSplitsArtistTitle() {
        val t = remoteFixtureTrack(loveLetterUrl)
        assertEquals("Love Letter (Piano)", t.title, "%20/%28/%29 必须解码")
        assertEquals("V.A.", t.artist, "「 - 」拆分同 NameParser")
        assertEquals("F3 双路径矩阵", t.album, "夹具标记保留（曲库审计识别用）")
        assertEquals(SourceType.STRM, t.sourceType)
        assertEquals(0L, t.durationMs, "STRM 曲库时长恒 0（真值由引擎解出，§61.181 A 案）")
        assertEquals(loveLetterUrl, t.sourceRef.url)
    }

    @Test
    @DisplayName("CJK 百分号曲名解码为汉字（扫描链同类样本）")
    fun decodesCjkPercent() {
        val url = "http://192.168.1.1:15244/dav/strm_test_probe/" +
            "S.H.E%20-%20%E8%8C%B1%E7%BD%97%E8%AE%B0.strm"
        val t = remoteFixtureTrack(url)
        assertEquals("茱罗记", t.title)
        assertEquals("S.H.E", t.artist)
    }

    @Test
    @DisplayName("字面 '+' 不是空格：无 %XX 时按 NameParser 契约原样保留")
    fun keepsLiteralPlus() {
        val url = "http://192.168.1.1:15244/d/ecloud_self/x/Trouble+Is+A+Friend.mp3"
        val t = remoteFixtureTrack(url)
        assertEquals("Trouble+Is+A+Friend", t.title, "服务器文件名字面含 +，展示须忠实")
    }

    @Test
    @DisplayName("同一 URL 稳定映射同一 id（历史 DB 行不漂移）；异 URL 不同 id")
    fun idStaysRawForStability() {
        val a = remoteFixtureTrack(loveLetterUrl)
        val b = remoteFixtureTrack(loveLetterUrl)
        assertEquals(a.id, b.id, "同 URL 幂等")
        val other = remoteFixtureTrack(
            "http://192.168.1.1:15244/d/ecloud_self/x/Trouble+Is+A+Friend.mp3")
        assertTrue(a.id != other.id, "异 URL 不同行")
    }
}
