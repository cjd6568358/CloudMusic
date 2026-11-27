package com.ccmsc.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §61.379：返回栈决策表的纯 JVM 断言。
 *
 * 这组断言对应真机复现的三条「后退乱跳」——状态机一旦回退到这三种形态，测试先红，
 * 不必等真机。
 */
class AppNavTest {

    private fun intercept(
        tab: Int = AppNav.TAB_HOME,
        searchReturnTo: Int = -1,
        query: String = "",
        player: Boolean = false,
        sources: Boolean = false,
        more: Boolean = false,
    ) = AppNav.shouldIntercept(tab, searchReturnTo, query, player, sources, more)

    @Test
    @DisplayName("一级 tab（首页/音乐库）不拦截：返回交给系统退 App")
    fun topLevelTabsDoNotIntercept() {
        assertFalse(intercept(tab = AppNav.TAB_HOME))
        assertFalse(intercept(tab = AppNav.TAB_LIBRARY))
    }

    @Test
    @DisplayName("底栏点进来的搜索页不拦截；有关键词则先清词")
    fun bottomBarSearchOnlyInterceptsWhenTyping() {
        assertFalse(intercept(tab = AppNav.TAB_SEARCH, searchReturnTo = -1, query = ""))
        assertTrue(intercept(tab = AppNav.TAB_SEARCH, searchReturnTo = -1, query = "sun"))
        assertTrue(intercept(tab = AppNav.TAB_SEARCH, searchReturnTo = AppNav.SEARCH_FROM_MORE))
    }

    @Test
    @DisplayName("次级页（歌单/队列/我的音乐/设置）一律拦截")
    fun subTabsAlwaysIntercept() {
        listOf(AppNav.TAB_PLAYLISTS, AppNav.TAB_QUEUE, AppNav.TAB_MINE, AppNav.TAB_SETTINGS)
            .forEach { assertTrue(intercept(tab = it), "tab=$it 应拦截") }
    }

    @Test
    @DisplayName("从更多进来的次级页退出：回进来前的一级 tab + 叠回更多面板 + 清 subOrigin")
    fun exitFromMoreReturnsToBaseTabAndMore() {
        val b = AppNav.exitSub(subOrigin = -1, subReturnTab = AppNav.TAB_HOME)
        assertEquals(AppNav.TAB_HOME, b.tab)
        assertEquals(true, b.moreOpen)
        assertEquals(-1, b.subOrigin)
    }

    @Test
    @DisplayName("有来源的次级页退出：直接回来源，不叠面板（设置→来源 返回应回设置，不是更多）")
    fun exitWithOriginGoesBackToOrigin() {
        val b = AppNav.exitSub(subOrigin = AppNav.TAB_SETTINGS, subReturnTab = AppNav.TAB_HOME)
        assertEquals(AppNav.TAB_SETTINGS, b.tab)
        assertNull(b.moreOpen, "有来源时不该动更多面板")
        assertEquals(-1, b.subOrigin, "必须清来源，否则下次读到旧值会自指成死锁")
    }

    @Test
    @DisplayName("设置→来源→返回→再返回：第二次回到进来前的一级 tab + 更多面板（不死锁）")
    fun settingsSourcesThenBackTwiceDoesNotDeadlock() {
        // 第一次：来源页关闭，回设置（subOrigin=6）
        val first = AppNav.back(
            tab = AppNav.TAB_SETTINGS, subOrigin = AppNav.TAB_SETTINGS, subReturnTab = AppNav.TAB_HOME,
            searchReturnTo = -1, query = "", playerOpen = false, sourcesOpen = true, moreOpen = false,
        )
        assertEquals(AppNav.TAB_SETTINGS, first.tab)
        assertEquals(false, first.sourcesOpen)
        assertEquals(-1, first.subOrigin)

        // 第二次：subOrigin 已被清 ⇒ 回首页 + 更多面板（原来这里 tab=6 自指，页面不动）
        val second = AppNav.back(
            tab = AppNav.TAB_SETTINGS, subOrigin = first.subOrigin ?: -1,
            subReturnTab = AppNav.TAB_HOME, searchReturnTo = -1, query = "",
            playerOpen = false, sourcesOpen = false, moreOpen = false,
        )
        assertEquals(AppNav.TAB_HOME, second.tab)
        assertEquals(true, second.moreOpen)
    }

    @Test
    @DisplayName("更多→来源→返回：回更多面板（不是掉到首页）")
    fun sourcesFromMoreReturnsToMorePanel() {
        val b = AppNav.back(
            tab = AppNav.TAB_HOME, subOrigin = -1, subReturnTab = AppNav.TAB_HOME,
            searchReturnTo = -1, query = "", playerOpen = false, sourcesOpen = true, moreOpen = false,
        )
        assertEquals(AppNav.TAB_HOME, b.tab)
        assertEquals(true, b.moreOpen)
        assertEquals(false, b.sourcesOpen)
    }

    @Test
    @DisplayName("搜索退出三种来源：-2 回更多 / >=0 回来源 / -1 回首页")
    fun searchExitTargets() {
        val fromMore = AppNav.exitSearch(AppNav.SEARCH_FROM_MORE, subReturnTab = AppNav.TAB_LIBRARY)
        assertEquals(AppNav.TAB_LIBRARY, fromMore.tab)
        assertEquals(true, fromMore.moreOpen)

        val fromTab = AppNav.exitSearch(AppNav.TAB_SETTINGS, subReturnTab = AppNav.TAB_HOME)
        assertEquals(AppNav.TAB_SETTINGS, fromTab.tab)
        assertNull(fromTab.moreOpen, "从某个 tab 进来时不叠更多面板")

        val fromBottom = AppNav.exitSearch(-1, subReturnTab = AppNav.TAB_HOME)
        assertEquals(AppNav.TAB_HOME, fromBottom.tab)
        assertEquals(-1, fromBottom.searchReturnTo)
    }

    @Test
    @DisplayName("层级顺序：播放页 > 来源页 > 更多面板 > 搜索 > 次级页")
    fun backPriorityOrder() {
        val b1 = AppNav.back(
            tab = AppNav.TAB_HOME, subOrigin = -1, subReturnTab = 0, searchReturnTo = -1, query = "",
            playerOpen = true, sourcesOpen = true, moreOpen = true,
        )
        assertEquals(false, b1.playerOpen, "播放页最优先")
        assertNull(b1.sourcesOpen)

        val b2 = AppNav.back(
            tab = AppNav.TAB_HOME, subOrigin = -1, subReturnTab = 0, searchReturnTo = -1, query = "",
            playerOpen = false, sourcesOpen = true, moreOpen = true,
        )
        assertEquals(false, b2.sourcesOpen)
        // subOrigin=-1（从更多面板进来）⇒ 退出时叠回更多面板是**正确**行为。
        assertEquals(true, b2.moreOpen)
    }

    @Test
    @DisplayName("更多面板进入：记下来之前的一级 tab、关面板、清来源")
    fun fromMoreRecordsBaseTab() {
        val b = AppNav.fromMore(currentTab = AppNav.TAB_LIBRARY, target = AppNav.TAB_PLAYLISTS)
        assertEquals(AppNav.TAB_PLAYLISTS, b.tab)
        assertEquals(false, b.moreOpen)
        assertEquals(AppNav.TAB_LIBRARY, b.subReturnTab)
        assertEquals(-1, b.subOrigin)
    }

    @Test
    @DisplayName("底栏点搜索：清返回目标（平级导航）")
    fun bottomBarSearchClearsReturnTarget() {
        val b = AppNav.searchFromBottomBar()
        assertEquals(AppNav.TAB_SEARCH, b.tab)
        assertEquals(-1, b.searchReturnTo)
        assertEquals(false, b.moreOpen)
    }
}
