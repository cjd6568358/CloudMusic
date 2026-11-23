package com.example.cloudmusic.ui

/**
 * §61.293 输入法交互策略（纯 Kotlin，可 JVM 断言）。
 *
 * 这里只放「不依赖 Compose 运行时」的判定；UI 侧只负责把结论接到
 * [FnSearchField] / [FnDialogInput] 的 `autoFocus` / `imeAction` 参数上，
 * 避免同一条产品口径散落在各调用点。
 */

/**
 * 进入搜索页时是否自动聚焦搜索框。
 *
 * 判据是**进入那一刻**的关键字：
 * - 空（含纯空白）⇒ 抢焦点并弹键盘。用户点搜索就是为了打字，不该再点一下输入框；
 * - 已有词 ⇒ 不抢焦点。典型路径是「搜完点结果去播放、返回搜索页」，此时再弹键盘
 *   会盖住半屏结果，而用户此刻要的是浏览而不是继续输入。
 *
 * 调用方必须用 `remember { shouldAutoFocusSearch(query) }` 冻结「进入时」的判定，
 * 否则用户点 ✕ 清空关键字会引发一次多余的重新聚焦。
 */
internal fun shouldAutoFocusSearch(initialQuery: String): Boolean = initialQuery.isBlank()

/**
 * §61.328（B-1）搜索防抖窗口。
 *
 * 原实现每次按键就换一个 Flow，Room 立刻跑一次 FTS + 一次全表 LIKE；中文输入法一次上屏
 * 还会连发 2~3 次输入事件 ⇒ 打一个词打出好几轮重查询。改为静默 [SEARCH_DEBOUNCE_MS] 后再查。
 *
 * **空查询不等**：清空是「回热词页」的即时动作，等半拍会看到上一轮结果闪一下；
 * 热词点击也不走这条等待（调用方直接落 [debouncedQuery]，见 `SearchScreen.onPickHotWord`）。
 */
internal const val SEARCH_DEBOUNCE_MS = 300L

/** 该关键字需要静默多久才值得查一次库（0 = 立刻查）。 */
internal fun searchDebounceMs(query: String): Long =
    if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS
