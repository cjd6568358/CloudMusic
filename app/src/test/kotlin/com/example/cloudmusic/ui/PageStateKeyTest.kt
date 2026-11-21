package com.example.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * §61.292：页面 saveable 槽位 key 的真值表。
 *
 * 为什么值得纯 JVM 断言：`SaveableStateHolder` 的槽位一旦重复，两页会串用同一份状态
 * （典型症状：切到「搜索」再回「音乐库」，曲库恢复成搜索结果列表的滚动位置）。
 * 这种错误在真机上表现为「偶发跳位」，只能靠静态断言挡住。
 */
class PageStateKeyTest {

    @Test
    @DisplayName("七个一级 tab 各自一个槽位")
    fun tabsMapToOwnSlot() {
        assertEquals("home", pageStateKey(showSources = false, showMore = false, tab = 0))
        assertEquals("library", pageStateKey(showSources = false, showMore = false, tab = 1))
        assertEquals("playlists", pageStateKey(showSources = false, showMore = false, tab = 2))
        assertEquals("search", pageStateKey(showSources = false, showMore = false, tab = 3))
        assertEquals("queue", pageStateKey(showSources = false, showMore = false, tab = 4))
        assertEquals("mine", pageStateKey(showSources = false, showMore = false, tab = 5))
    }

    @Test
    @DisplayName("设置页是 else 分支：tab 6 与未知值必须共用同一槽（与内容 when 一致）")
    fun settingsIsCatchAll() {
        assertEquals("settings", pageStateKey(false, false, 6))
        assertEquals("settings", pageStateKey(false, false, 99))
    }

    @Test
    @DisplayName("覆盖层优先级：数据源页 > 更多面板 > tab")
    fun overlaysWinOverTab() {
        for (t in 0..6) {
            assertEquals("sources", pageStateKey(showSources = true, showMore = true, tab = t))
            assertEquals("sources", pageStateKey(showSources = true, showMore = false, tab = t))
            assertEquals("more", pageStateKey(showSources = false, showMore = true, tab = t))
        }
    }

    @Test
    @DisplayName("九个槽位 key 全局唯一：串槽会让两页共用同一份滚动位置")
    fun keysAreUnique() {
        val keys = mutableSetOf<String>()
        for (t in 0..6) {
            keys += pageStateKey(false, false, t)
            keys += pageStateKey(true, false, t)
            keys += pageStateKey(false, true, t)
        }
        assertEquals(
            expected = setOf(
                "home", "library", "playlists", "search", "queue", "mine", "settings",
                "sources", "more",
            ),
            actual = keys,
            message = "槽位集合变了：新增页面必须同时给独立 key，否则会与前页串状态",
        )
    }
}
