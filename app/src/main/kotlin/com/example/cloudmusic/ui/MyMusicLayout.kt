package com.example.cloudmusic.ui

/**
 * §61.284（B07 收口）：「我的音乐」懒列表的 item 序号算法（纯 Kotlin，可 JVM 断言）。
 *
 * 页面结构固定为「统计卡 → [空态] → 收藏段（段标题 + 曲目… + 组间空隙）→ 最近播放段」。
 * 从「最近」入口进来时要把最近播放段顶到屏上，就必须知道它在 `LazyColumn` 里的 item 序号；
 * 此前用 `onGloballyPositioned` 量 y 坐标再 `animateScrollTo`，懒列表下未组合项没有坐标，
 * 于是把序号算法抽到这里，UI 与单测共用同一份口径（防两处各算一套）。
 *
 * 序号口径必须与 [com.example.cloudmusic.ui.CloudMusicApp] 里 `MyMusicScreen` 的
 * `item` / `itemsIndexed` 发出顺序一致——守护 `§61.284` 会核对页面结构与本算法同步。
 */
internal object MyMusicLayout {

    /** 统计卡（含上下留白）恒占 1 个 item。 */
    private const val STAT_ITEMS = 1

    /**
     * 「最近播放」段标题的 item 序号；无历史时返回 `-1`（没有该段，不应滚动）。
     *
     * 空态只在「收藏与历史皆空」时发出，而那种情况下 [hasHistory] 必为 false，
     * 因此空态不参与本式；收藏段非空时恒占「段标题 1 + 曲目 [favoriteCount] + 组间空隙 1」。
     */
    fun historyHeaderIndex(favoriteCount: Int, hasHistory: Boolean): Int {
        if (!hasHistory) return -1
        val favoriteItems = if (favoriteCount > 0) 2 + favoriteCount else 0
        return STAT_ITEMS + favoriteItems
    }
}
