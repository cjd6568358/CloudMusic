package com.ccmsc.cloudmusic.ui

/**
 * §61.284（B07 收口）：「我的音乐」懒列表的 item 序号算法（纯 Kotlin，可 JVM 断言）。
 *
 * 页面结构固定为「统计卡 → [空态] → 收藏段（段标题 + 曲目… + 组间空隙）→ 最近播放段」。
 * 从「最近」入口进来时要把最近播放段顶到屏上，就必须知道它在 `LazyColumn` 里的 item 序号；
 * 此前用 `onGloballyPositioned` 量 y 坐标再 `animateScrollTo`，懒列表下未组合项没有坐标，
 * 于是把序号算法抽到这里，UI 与单测共用同一份口径（防两处各算一套）。
 *
 * 序号口径必须与 [com.ccmsc.cloudmusic.ui.CloudMusicApp] 里 `MyMusicScreen` 的
 * `item` / `itemsIndexed` 发出顺序一致——守护 `§61.284` 会核对页面结构与本算法同步。
 */
internal object MyMusicLayout {

    /** 统计卡（含上下留白）恒占 1 个 item。 */
    private const val STAT_ITEMS = 1

    /**
     * 「最近播放」段标题的 item 序号；无历史时返回 `-1`（没有该段，不应滚动）。
     *
     * 收藏段恒占「段标题 1 + 内容」：有曲目时内容 = [favoriteCount] 行 + 组间空隙 1，
     * 无曲目时内容 = 空态 1（§61.370 起标题与空态都常驻）。
     */
    fun historyHeaderIndex(favoriteCount: Int, hasHistory: Boolean): Int {
        if (!hasHistory) return -1
        // §61.370：收藏段标题改成**常驻**，收藏为空时该段 = 标题 1 + 空态 1 = 2 项
        //（此前两段共用一块空态，favoriteCount=0 时记 0 项）。
        val favoriteItems = if (favoriteCount > 0) 2 + favoriteCount else 2
        return STAT_ITEMS + favoriteItems
    }
}
