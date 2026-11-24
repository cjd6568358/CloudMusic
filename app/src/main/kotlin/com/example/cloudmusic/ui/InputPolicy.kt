package com.example.cloudmusic.ui

/**
 * §61.293 输入法交互策略（纯 Kotlin，可 JVM 断言）。
 *
 * 这里只放「不依赖 Compose 运行时」的判定；UI 侧只负责把结论接到
 * [FnSearchField] / [FnDialogInput] 的 `autoFocus` / `imeAction` 参数上，
 * 避免同一条产品口径散落在各调用点。
 */

/**
 * §61.344（G-7）：进入搜索页**不自动聚焦**搜索框。
 *
 * 原口径（§61.293）是「空词进页即抢焦点弹键盘」；用户 2026-11-25 一手反馈改为
 * 「切到搜索页不弹键盘，主动点输入框才弹」。BasicTextField 本身点击即聚焦，
 * 故本函数恒 false 即可，**不需要额外的点击接线**。
 *
 * 保留函数而非直接写死 `autoFocus = false`：口径有一个可测试的单一出口，
 * 且调用点形态不变（§61.293 只改判据锚点与函数实现，不降强度）。
 */
internal fun shouldAutoFocusSearch(initialQuery: String): Boolean = false

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
