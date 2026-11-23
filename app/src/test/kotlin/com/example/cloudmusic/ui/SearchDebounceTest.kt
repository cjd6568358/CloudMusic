package com.example.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * §61.328（B-1）：搜索防抖窗口的真值表。
 *
 * 这条策略只回答一个问题：「这个关键字值不值得等 300ms 再查」。
 * 两个方向都会出错：
 * - 对空查询也等 ⇒ 用户点 ✕ 清空后，热词页会晚半拍才回来，期间还闪着上一轮结果；
 * - 对所有输入都不等 ⇒ 回到原来的「一键一查」，中文输入法一次上屏连发 2~3 次事件，
 *   每次都跑一遍 FTS + 全表 LIKE。
 */
class SearchDebounceTest {

    @Test
    @DisplayName("非空关键字要静默 300ms 才查库")
    fun typedQueryWaits() {
        assertEquals(SEARCH_DEBOUNCE_MS, searchDebounceMs("周杰伦"))
        assertEquals(300L, searchDebounceMs("a"))
        assertEquals(SEARCH_DEBOUNCE_MS, searchDebounceMs(" 晴天 "))
    }

    @Test
    @DisplayName("空/纯空白查询不等：清空是即时动作，不是一次新搜索")
    fun blankQueryIsImmediate() {
        assertEquals(0L, searchDebounceMs(""))
        assertEquals(0L, searchDebounceMs("   "))
        assertEquals(0L, searchDebounceMs("\u3000"), "全角空格也是空白")
    }

    @Test
    @DisplayName("窗口值本身：300ms 是「感知不到卡顿」与「合并连发」的交点，别随手改")
    fun windowIsPinned() {
        assertEquals(300L, SEARCH_DEBOUNCE_MS)
    }
}
