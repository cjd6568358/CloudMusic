package com.ccmsc.cloudmusic.ui

/**
 * §61.379（2026-11-27）返回栈的**决策表**——把「按返回该去哪」从组合根里的命令式赋值
 * 收敛成纯函数，可单测、可穷举。
 *
 * ## 为什么抽出来
 * 用户一手「后退乱跳」。真机复现三条，根因都在**状态语义重叠**：`tab` 同时当一级 tab 与
 * 次级页索引、`subOrigin` 不清空会指向自己、`showMore` 覆盖层又遮蔽 `tab`。
 *
 * 1. **更多 → 音乐来源 → 返回**：掉到首页。当时 `showSources` 分支只清标志、不恢复来源，
 *    `tab` 停在进入前的一级 tab（首页）。
 * 2. **设置 → 音乐来源 → 返回（回设置）→ 再返回**：页面纹丝不动。`subOrigin` 仍是 6，
 *    `exitSubPage()` 执行 `tab = 6`（自指）。
 * 3. **设置页点底栏搜索 → 返回**：跳回设置。底栏把「当前 tab」记成返回目标，而 `tab=6`
 *    同时是次级页索引，两者混在一起。
 *
 * ## 现在的语义（三句话）
 * - 一级 tab（0 首页 / 1 音乐库 / 3 搜索）**平级**：底栏直接进来的页面，按返回交给系统；
 * - 从「更多」进来的次级页：退出时先回**进来前的一级 tab**，再叠回更多面板（两层）；
 * - 有 `subOrigin` 的（首页卡 / 设置进来源页）：退出直接回那个来源，不叠面板。
 */
internal object AppNav {

    /** 一次「返回」要写回的状态；`null` 字段 = 保持不变（只写真正变化的项）。 */
    internal data class Back(
        val tab: Int? = null,
        val moreOpen: Boolean? = null,
        val sourcesOpen: Boolean? = null,
        val playerOpen: Boolean? = null,
        val query: String? = null,
        val subOrigin: Int? = null,
        val subReturnTab: Int? = null,
        val searchReturnTo: Int? = null,
    )

    /** 一级 tab 常量（组合根里 tab 的取值域）。 */
    const val TAB_HOME = 0
    const val TAB_LIBRARY = 1
    const val TAB_PLAYLISTS = 2
    const val TAB_SEARCH = 3
    const val TAB_QUEUE = 4
    const val TAB_MINE = 5
    const val TAB_SETTINGS = 6

    /** 从「更多」面板进来的搜索页：返回时回更多面板（哨兵值）。 */
    const val SEARCH_FROM_MORE = -2

    /**
     * 是否需要拦截返回键。
     *
     * **一级 tab 不拦截**（交给系统退 App）：`tab==0/1` 恒 false；`tab==3` 只有「有关键词」
     * 或「从别处跳进来」时才拦——底栏点进来的搜索页是平级导航，不该被当成历史栈。
     */
    fun shouldIntercept(
        tab: Int,
        searchReturnTo: Int,
        query: String,
        playerOpen: Boolean,
        sourcesOpen: Boolean,
        moreOpen: Boolean,
    ): Boolean = playerOpen || sourcesOpen || moreOpen ||
        tab == TAB_PLAYLISTS || tab == TAB_QUEUE || tab == TAB_MINE || tab == TAB_SETTINGS ||
        (tab == TAB_SEARCH && (query.isNotEmpty() || searchReturnTo != -1))

    /** 返回键落地：逐层退出（播放页 → 来源页 → 更多面板 → 搜索清词/退出 → 次级页）。 */
    fun back(
        tab: Int,
        subOrigin: Int,
        subReturnTab: Int,
        searchReturnTo: Int,
        query: String,
        playerOpen: Boolean,
        sourcesOpen: Boolean,
        moreOpen: Boolean,
    ): Back = when {
        playerOpen -> Back(playerOpen = false)
        // §61.379：来源页**走统一出口**（原来只清标志 ⇒ 从更多进来会掉到首页）。
        sourcesOpen -> exitSub(subOrigin, subReturnTab).copy(sourcesOpen = false)
        moreOpen -> Back(moreOpen = false)
        // 搜索：有关键词先清词（留在本页）；无词才退出到来源。
        tab == TAB_SEARCH ->
            if (query.isNotEmpty()) Back(query = "")
            else exitSearch(searchReturnTo, subReturnTab)
        else -> exitSub(subOrigin, subReturnTab)
    }

    /**
     * 次级页退出：有来源回来源；否则回「进来前的一级 tab」并叠回更多面板。
     *
     * **必须同时清 subOrigin**：不清会让下一个人读到旧来源（设置页 subOrigin=6 → tab=6 自指）。
     */
    fun exitSub(subOrigin: Int, subReturnTab: Int): Back =
        if (subOrigin >= 0) {
            Back(tab = subOrigin, subOrigin = -1)
        } else {
            // 只置 moreOpen 不动 tab 的话：返回更多面板后再返回，tab 仍是刚退出的次级页，
            // 又会跳回去（「乱跳」的直接来源）。
            Back(tab = subReturnTab, moreOpen = true, subOrigin = -1)
        }

    /**
     * 搜索页退出。
     * @param searchReturnTo `-2` = 从更多面板进来（回它）；`>=0` = 回该 tab；`-1` = 底栏进来（回首页）。
     */
    fun exitSearch(searchReturnTo: Int, subReturnTab: Int): Back = when {
        searchReturnTo == SEARCH_FROM_MORE ->
            Back(tab = subReturnTab, moreOpen = true, searchReturnTo = -1, query = "")
        searchReturnTo >= 0 -> Back(tab = searchReturnTo, searchReturnTo = -1, query = "")
        else -> Back(tab = TAB_HOME, searchReturnTo = -1, query = "")
    }

    /** 从「更多」面板进入次级页：记下进来前的一级 tab（退出时靠它回到正确的面板之上）。 */
    fun fromMore(currentTab: Int, target: Int): Back =
        Back(tab = target, moreOpen = false, subOrigin = -1, subReturnTab = currentTab)

    /** 从「更多」面板进入搜索页：额外打 -2 哨兵。 */
    fun searchFromMore(currentTab: Int): Back =
        fromMore(currentTab, TAB_SEARCH).copy(searchReturnTo = SEARCH_FROM_MORE)

    /** 底栏点搜索：平级导航，清掉返回目标。 */
    fun searchFromBottomBar(): Back =
        Back(tab = TAB_SEARCH, moreOpen = false, searchReturnTo = -1)

    /**
     * 打开来源页。
     * @param origin `-1` = 从更多面板来；`>=0` = 从某个 tab 来（首页 0 / 设置 6）。
     */
    fun openSources(currentTab: Int, origin: Int): Back =
        Back(sourcesOpen = true, moreOpen = false, subOrigin = origin, subReturnTab = currentTab)
}
