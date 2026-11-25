package com.ccmsc.cloudmusic.ui

import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * §61.326（A-3）：搜索发现热词的「同一实体只出现一次」。
 *
 * 真机一手证据（评审 §六-5 / 01:43 截图）：热词区同时出现 `刘若英` / `劉若英` / `"刘若英`。
 * 本测试钉住三件事：
 *  1. 归一链四层（NFKC / 两端标点 / 空白 / 繁简）逐层可断言；
 *  2. 折叠只作用去重键，**不改展示形态、不改搜索词**；
 *  3. 字表本身自洽（每组两字、繁体不重复、折叠幂等）——手写数据必须能被测试枚举复核。
 */
class SearchDiscoveryTest {

    private var seq = 0

    private fun track(artist: String, album: String = "未知专辑", title: String = "曲$seq") =
        Track(
            id = TrackId("id-${seq++}"),
            title = title,
            artist = artist,
            album = album,
            durationMs = 1000L,
            sourceRef = SourceRef("s", "/${seq}.flac"),
            sourceType = SourceType.LOCAL,
        )

    @Test
    @DisplayName("三种写法（简体/繁体/带引号）合并为一个热词，展示取最高频的干净写法")
    fun mergesVariantsOfSameEntity() {
        val tracks = listOf(
            track("刘若英"), track("刘若英"), track("刘若英"),
            track("劉若英"), track("劉若英"),
            track("\"刘若英"),
        )
        val words = SearchDiscovery.hotWords(tracks, limit = 10)
        assertEquals(1, words.count { SearchDiscovery.dedupeKey(it) == "刘若英" })
        assertEquals("刘若英", words.first { SearchDiscovery.dedupeKey(it) == "刘若英" })
        assertFalse(words.contains("劉若英"), "繁体写法不该单独成词：$words")
        assertFalse(words.contains("\"刘若英"), "带引号写法不该单独成词：$words")
    }

    @Test
    @DisplayName("展示形态不被折叠改写：繁体仍是繁体，只有去重键变简体")
    fun foldingOnlyAffectsKeyNotDisplay() {
        assertEquals("劉若英", SearchDiscovery.clean("劉若英"))
        assertEquals("刘若英", SearchDiscovery.dedupeKey("劉若英"))
        assertEquals("刘若英", SearchDiscovery.dedupeKey("  \"刘若英」 "), "两端标点/空白应剥净")
    }

    @Test
    @DisplayName("不同实体不被误合并")
    fun keepsDistinctEntitiesApart() {
        val tracks = listOf(
            track("陈奕迅"), track("陳奕迅"),
            track("林宥嘉"), track("林宥嘉"),
        )
        val words = SearchDiscovery.hotWords(tracks, limit = 10)
        assertEquals(2, words.size, "只有两个实体：$words")
        assertTrue(words.contains("林宥嘉"), words.toString())
    }

    @Test
    @DisplayName("合并计数参与排序：繁体 3 + 简体 2 要压过单写法 4")
    fun mergedCountDrivesRanking() {
        val tracks = listOf(
            track("劉德華"), track("劉德華"),
            track("刘德华"), track("刘德华"), track("刘德华"),
            track("张学友"), track("张学友"), track("张学友"), track("张学友"),
        )
        val words = SearchDiscovery.hotWords(tracks, limit = 10)
        assertEquals("刘德华", words.first(), "合并后 5 > 4 应排首位，且展示取多数写法：$words")
    }

    @Test
    @DisplayName("占位符与空值不进热词")
    fun dropsPlaceholders() {
        val tracks = listOf(
            track("未知"), track("未知艺人"), track("<unknown>"), track("UNKNOWN"), track(""),
        )
        assertTrue(SearchDiscovery.hotWords(tracks, limit = 10).isEmpty())
    }

    @Test
    @DisplayName("艺人名与专辑名撞名时只留一个（跨半区去重）")
    fun dedupesAcrossArtistAndAlbumHalves() {
        val tracks = listOf(track("周杰伦", album = "周杰伦"))
        val words = SearchDiscovery.hotWords(tracks, limit = 10)
        assertEquals(listOf("周杰伦"), words)
    }

    @Test
    @DisplayName("空曲库返回空（不造在线热搜）")
    fun emptyLibraryGivesNoWords() {
        assertTrue(SearchDiscovery.hotWords(emptyList()).isEmpty())
        assertTrue(SearchDiscovery.hotWords(listOf(track("A")), limit = 0).isEmpty())
    }

    @Test
    @DisplayName("字表自检：每组两字、繁体不重复、简繁不同、折叠幂等")
    fun foldTableIsSelfConsistent() {
        val pairs = SearchDiscovery.foldPairs()
        assertTrue(pairs.size > 500, "字表规模异常：${pairs.size}")
        val seen = HashSet<Char>()
        pairs.forEach { pair ->
            assertEquals(2, pair.length, "条目必须是「繁简」两字：$pair")
            val (trad, simp) = pair[0] to pair[1]
            assertTrue(seen.add(trad), "繁体字重复（映射有歧义）：$trad")
            assertFalse(trad == simp, "简繁相同是无效条目：$pair")
            assertEquals(simp.toString(), SearchDiscovery.dedupeKey(trad.toString()), "映射未生效：$pair")
        }
        assertEquals(pairs.size, SearchDiscovery.HAN_FOLD.size, "真实生效的映射数与字表条数不一致")
    }

    @Test
    @DisplayName("一简对多繁是合法映射（鍾/鐘 → 钟，復/複 → 复）")
    fun allowsManyToOne() {
        assertEquals("钟", SearchDiscovery.dedupeKey("鍾"))
        assertEquals("钟", SearchDiscovery.dedupeKey("鐘"))
        assertEquals("复", SearchDiscovery.dedupeKey("復"))
        assertEquals("复", SearchDiscovery.dedupeKey("複"))
    }

    @Test
    @DisplayName("全角与大小写归一，且内部连字符不被拆（A-Lin）")
    fun normalizesWidthAndCaseButKeepsInnerPunct() {
        assertEquals("abc", SearchDiscovery.dedupeKey("ＡＢＣ"))
        assertEquals(SearchDiscovery.dedupeKey("Taylor Swift"), SearchDiscovery.dedupeKey("TAYLOR  SWIFT"))
        assertEquals("a-lin", SearchDiscovery.dedupeKey("-A-Lin-"))
    }
}
