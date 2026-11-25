package com.ccmsc.cloudmusic.ui

/**
 * §61.292（V0.4 页面状态保持）：一级页面在 `SaveableStateHolder` 里的槽位 key。
 *
 * 缺陷来历：`CloudMusicApp` 的顶层内容是 `when { showSources -> … ; tab == 0 -> … }`，
 * 切 tab 时旧页面的可组合项**整体离开组合**。Compose 的 `rememberSaveable` 只在
 * `SaveableStateHolder.SaveableStateProvider` 内才会跨「离场→返场」保留，于是：
 * - `LazyColumn` 自带的 `rememberLazyListState` 被丢弃 → 7,500 首曲库滚到半截，切走再回来回到顶部；
 * - 曲库的视图选择（`viewIndex`，本来就是 `rememberSaveable`）也一起丢 → 「专辑」视图变回「全部」。
 *
 * key 必须与内容 `when` 的分支一一对应（同一 key 只能对应同一页），否则两页会串用同一份状态。
 * 这里单独抽成纯函数，便于 JVM 断言「9 个槽互不重复」——串槽是这类改造最隐蔽的错误。
 */
internal fun pageStateKey(showSources: Boolean, showMore: Boolean, tab: Int): String = when {
    // 覆盖层优先：数据源页 / 更多面板压在任何 tab 之上
    showSources -> "sources"
    showMore -> "more"
    tab == 0 -> "home"
    tab == 1 -> "library"
    tab == 2 -> "playlists"
    tab == 3 -> "search"
    tab == 4 -> "queue"
    tab == 5 -> "mine"
    // 与内容 when 的 `else -> SettingsScreen` 保持一致：6 及其它值都是设置页
    else -> "settings"
}
