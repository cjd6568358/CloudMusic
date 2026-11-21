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
