package com.ccmsc.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * §61.284：`MyMusicLayout.historyHeaderIndex` 的序号口径。
 *
 * 背景：`MyMusicScreen` 从 `Column + verticalScroll + forEach` 改为 `LazyColumn` 后，
 * 「从最近入口进来滚到最近播放段」不能再量 y 坐标，只能按 item 序号定位。
 * 序号算错的后果是**滚到错误位置**（滚到收藏中间 / 滚到顶），这在没有设备窗口时
 * 无法用截图发现，所以用纯 JVM 断言钉死：结构 = 统计卡 1 + [收藏标题 1 + 曲目 n + 空隙 1]。
 */
class MyMusicLayoutTest {

    @Test
    @DisplayName("无历史时不滚（返回 -1，而不是滚到统计卡）")
    fun noHistoryNeverScrolls() {
        assertEquals(-1, MyMusicLayout.historyHeaderIndex(favoriteCount = 0, hasHistory = false))
        assertEquals(-1, MyMusicLayout.historyHeaderIndex(favoriteCount = 7, hasHistory = false))
    }

    @Test
    @DisplayName("只有历史：收藏段「标题 + 空态」占 2 项后，最近段序号 3")
    fun historyOnlyStartsAfterStatCard() {
        // §61.370：收藏为空时该段不再消失，标题与空态都常驻 ⇒ 2 项。
        assertEquals(3, MyMusicLayout.historyHeaderIndex(favoriteCount = 0, hasHistory = true))
    }

    @Test
    @DisplayName("收藏 1 首：段标题 + 曲目 + 空隙 = 3 个 item，最近段序号 4")
    fun singleFavoriteCountsSectionChrome() {
        assertEquals(4, MyMusicLayout.historyHeaderIndex(favoriteCount = 1, hasHistory = true))
    }

    @Test
    @DisplayName("收藏 3 首：最近段序号 = 1 + (1 + 3 + 1) = 6")
    fun favoritesShiftHistoryDown() {
        assertEquals(6, MyMusicLayout.historyHeaderIndex(favoriteCount = 3, hasHistory = true))
    }

    @Test
    @DisplayName("收藏段随曲目数线性后移（+1 首 = +1 序号）")
    fun indexGrowsLinearlyWithFavorites() {
        val a = MyMusicLayout.historyHeaderIndex(favoriteCount = 200, hasHistory = true)
        val b = MyMusicLayout.historyHeaderIndex(favoriteCount = 201, hasHistory = true)
        assertEquals(a + 1, b)
    }
}
