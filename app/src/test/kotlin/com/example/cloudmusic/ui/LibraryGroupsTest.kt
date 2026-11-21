package com.example.cloudmusic.ui

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * §61.289：曲库分组 / 不可用筛选的取数语义。
 *
 * 背景：这两段逻辑此前直接写在 `LazyListScope` 的 content lambda 里——它是 LazyColumn 的
 * item 构建器，**每次列表重建都会重跑**（切视图、展开/收起分组、曲库更新）。7,500 首时
 * 一次 `groupBy + sortedBy` 是几十毫秒级，展开分组明显掉帧。本批抽成纯函数并在
 * composable 顶部 `remember`，本测试钉住**取数语义不变**（抽函数最容易悄悄改掉排序与兜底）。
 */
class LibraryGroupsTest {

    private fun track(
        id: String,
        artist: String = "A",
        album: String = "B",
        source: SourceType = SourceType.LOCAL,
        availability: Availability = Availability.OK,
    ) = Track(
        id = TrackId(id),
        title = "t-$id",
        artist = artist,
        album = album,
        durationMs = 1000L,
        sourceRef = SourceRef("s", "/$id.flac"),
        sourceType = source,
        availability = availability,
    )

    // 周杰伦 ×2（不同专辑）/ 陈奕迅 ×2（同专辑）/ 未知艺人 ×1
    private val t1 = track("1", artist = "周杰伦", album = "叶惠美")
    private val t2 = track("2", artist = "周杰伦", album = "七里香")
    private val t3 = track("3", artist = "未知", album = "", source = SourceType.WEBDAV,
        availability = Availability.UNREACHABLE)
    private val t4 = track("4", artist = "陈奕迅", album = "U87", source = SourceType.WEBDAV)
    private val t5 = track("5", artist = "陈奕迅", album = "U87", source = SourceType.STRM,
        availability = Availability.LINK_EXPIRED)
    private val all = listOf(t1, t2, t3, t4, t5)

    @Test
    @DisplayName("歌手视图：按艺人分组、名称升序、兜底值归「未标注艺人」且不产生空组")
    fun artistGroups() {
        val groups = libraryGroups(all, LibraryView.ARTIST)
        assertEquals(
            listOf("周杰伦", "未标注艺人", "陈奕迅"),
            groups.map { it.first },
            "按组名升序（UTF-16 码点序）",
        )
        assertEquals(2, groups[0].second.size, "周杰伦 2 首")
        assertEquals(listOf(t1, t2), groups[0].second, "组内保持原列表顺序")
        assertEquals(listOf(t3), groups[1].second, "「未知」是兜底值 ⇒ 归未标注，且不丢曲目")
        assertTrue(groups.none { it.first.isBlank() }, "不得出现空名称分组")
    }

    @Test
    @DisplayName("专辑视图：按专辑分组、空白专辑归「未归类专辑」")
    fun albumGroups() {
        val groups = libraryGroups(all, LibraryView.ALBUM)
        assertEquals(listOf("U87", "七里香", "叶惠美", "未归类专辑"), groups.map { it.first })
        assertEquals(listOf(t4, t5), groups[0].second, "同专辑两首按原序")
        assertEquals(listOf(t3), groups[3].second, "空专辑 ⇒ 未归类专辑")
    }

    @Test
    @DisplayName("音源视图：先按名称升序再按组内数量降序（稳定排序，同数量保持名称序）")
    fun sourceGroups() {
        val groups = libraryGroups(all, LibraryView.SOURCE)
        assertEquals(listOf("WebDAV", "本机", "STRM"), groups.map { it.first },
            "数量降序：WebDAV(2) / 本机(2) 同数量保持名称序，STRM(1) 居末")
        assertEquals(listOf(2, 2, 1), groups.map { it.second.size })
    }

    @Test
    @DisplayName("全部歌曲 / 不可用视图不产生分组（分组只服务三个分组视图）")
    fun nonGroupingViewsReturnEmpty() {
        assertTrue(libraryGroups(all, LibraryView.ALL).isEmpty())
        assertTrue(libraryGroups(all, LibraryView.UNAVAILABLE).isEmpty())
    }

    @Test
    @DisplayName("不可用筛选：只留非 OK，保持原列表顺序，OK 曲目一首不漏不多")
    fun unavailableFilter() {
        assertEquals(listOf(t3, t5), unavailableTracks(all))
        assertTrue(unavailableTracks(listOf(t1, t4)).isEmpty(), "全 OK 时为空")
        assertTrue(unavailableTracks(emptyList()).isEmpty(), "空曲库不出异常")
    }

    @Test
    @DisplayName("分组是纯函数：同一输入两次调用结果等价（可安全 remember 缓存）")
    fun groupingIsPure() {
        val a = libraryGroups(all, LibraryView.ARTIST)
        val b = libraryGroups(all, LibraryView.ARTIST)
        assertEquals(a.map { it.first }, b.map { it.first })
        assertEquals(a.map { it.second.size }, b.map { it.second.size })
        // 入参不被修改（纯函数不得原地排序/改动调用方列表）
        assertEquals(listOf("1", "2", "3", "4", "5"), all.map { it.id.value })
    }
}
