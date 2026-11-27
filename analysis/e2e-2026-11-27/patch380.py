# -*- coding: utf-8 -*-
"""§61.379：组合根接 AppNav 决策表（瘦身 + 语义单源）。

把上一版散在组合根里的命令式赋值换成 applyBack(AppNav.xxx(...))，
新增的 40 多行注释同时收回（语义说明已迁到 AppNavigation.kt）。
"""
import io

P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/CloudMusicApp.kt'

with io.open(P, encoding='utf-8', newline='') as f:
    s = f.read()


def sub(old, new, n=1):
    global s
    assert s.count(old) == n, (s.count(old), old[:80])
    s = s.replace(old, new)


# 1) exitSubPage + applyBack
sub(
    '''    fun exitSubPage() {
        if (subOrigin >= 0) {
            tab = subOrigin
        } else {
            // §61.378：从「更多」进来的次级页 —— 先把 tab 恢复成进入前的一级 tab，再叠回更多面板。
            // 原来只置 showMore=true 不动 tab：返回更多面板后 cur=2/4/5/6，再按返回清掉 showMore
            // 又跳回刚退出的那次级页（「后退乱跳」的直接来源）。
            tab = subReturnTab
            showMore = true
        }
        // §61.378：**必须清**。不清会让下次 exitSubPage 读到旧来源（设置页 subOrigin=6 → tab=6
        // 自指），表现为「按返回页面完全不动」的死锁。
        subOrigin = -1
    }''',
    '''    // §61.379：导航决策全部在 [AppNav]（纯函数 + 单测），这里只负责把结果写回 Compose 状态。
    fun applyBack(b: AppNav.Back) {
        b.tab?.let { tab = it }
        b.moreOpen?.let { showMore = it }
        b.sourcesOpen?.let { showSources = it }
        b.playerOpen?.let { showPlayer = it }
        b.query?.let { query = it }
        b.subOrigin?.let { subOrigin = it }
        b.subReturnTab?.let { subReturnTab = it }
        b.searchReturnTo?.let { searchReturnTo = it }
    }
    fun exitSubPage() = applyBack(AppNav.exitSub(subOrigin, subReturnTab))''',
)

# 2) BackHandler
sub(
    '''    // §61.378：一级 tab（0 首页 / 1 音乐库 / 3 搜索）在**没有关键词、也没有来源**时不拦截，
    // 交给系统退出 App —— 底栏 tab 是平级导航，不该被当成「历史栈」回退。
    val searchHasContext = tab == 3 && (query.isNotEmpty() || searchReturnTo != -1)
    BackHandler(
        enabled = showPlayer || showSources || showMore ||
            tab == 2 || tab == 4 || tab == 5 || tab == 6 || searchHasContext,
    ) {
        when {
            showPlayer -> showPlayer = false
            // §61.378：来源页返回走**同一个出口**（原来只清标志 ⇒ 从更多进来会掉到首页）。
            showSources -> {
                showSources = false
                exitSubPage()
            }
            showMore -> showMore = false
            // 搜索页的「返回」= 清词 + 回进入方（与页内「取消」同一语义，§61.187）。
            tab == 3 -> {
                // 有关键词先清词（停留本页）；再按才退出到来源。
                if (query.isNotEmpty()) {
                    query = ""
                } else {
                    // -2 = 从「更多」面板进来（回更多）；>=0 = 从某个次级页进来。
                    if (searchReturnTo == -2) {
                        tab = subReturnTab
                        showMore = true
                    } else if (searchReturnTo >= 0) {
                        tab = searchReturnTo
                    }
                    searchReturnTo = -1
                }
            }
            else -> exitSubPage()
        }
    }''',
    '''    // §61.379：逐层退出（播放页 → 来源页 → 更多面板 → 搜索清词/退出 → 次级页）；
    // 一级 tab 不拦截，交给系统退 App。完整语义与真机复现的三条乱跳见 [AppNav]。
    BackHandler(
        enabled = AppNav.shouldIntercept(
            tab, searchReturnTo, query, showPlayer, showSources, showMore,
        ),
    ) {
        applyBack(
            AppNav.back(
                tab, subOrigin, subReturnTab, searchReturnTo, query,
                showPlayer, showSources, showMore,
            ),
        )
    }''',
)

# 3) 底栏点搜索
sub(
    '''                                // §61.378：底栏是平级导航 —— 进来时清掉返回目标，
                                // 搜索页在无关键词时按返回交给系统（原来会把当前 tab 记成目标，
                                // 在设置页点搜索再返回会「跳回设置」）。
                                2 -> {
                                    searchReturnTo = -1
                                    tab = 3
                                }''',
    '''                                2 -> applyBack(AppNav.searchFromBottomBar())''',
)

# 4) 更多面板 6 个入口
sub(
    '''                    // §61.378：全部记下「进来之前的一级 tab」，退出时才能回到正确的更多面板之上。
                    onPlaylists = { subReturnTab = tab; showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 2 },
                    onSearch = { subReturnTab = tab; showMore = false; searchReturnTo = -2; subOrigin = -1; tab = 3 },
                    onQueue = { subReturnTab = tab; showMore = false; subOrigin = -1; tab = 4 },
                    onMine = { subReturnTab = tab; showMore = false; mineFocusRecent = false; subOrigin = -1; tab = 5 },
                    onSettings = { subReturnTab = tab; showMore = false; subOrigin = -1; tab = 6 },
                    onSources = { subReturnTab = tab; showMore = false; subOrigin = -1; showSources = true },''',
    '''                    onPlaylists = { applyBack(AppNav.fromMore(tab, AppNav.TAB_PLAYLISTS)) },
                    onSearch = { applyBack(AppNav.searchFromMore(tab)) },
                    onQueue = { applyBack(AppNav.fromMore(tab, AppNav.TAB_QUEUE)) },
                    onMine = {
                        mineFocusRecent = false
                        applyBack(AppNav.fromMore(tab, AppNav.TAB_MINE))
                    },
                    onSettings = { applyBack(AppNav.fromMore(tab, AppNav.TAB_SETTINGS)) },
                    onSources = { applyBack(AppNav.openSources(tab, origin = -1)) },''',
)

# 5) 首页两个入口
sub(
    '                    onOpenSources = { subReturnTab = tab; subOrigin = 0; showSources = true },',
    '                    onOpenSources = { applyBack(AppNav.openSources(tab, origin = AppNav.TAB_HOME)) },',
)
sub(
    '''                    onOpenMine = { recent ->
                        mineFocusRecent = recent
                        subReturnTab = tab
                        subOrigin = 0
                        tab = 5
                    },''',
    '''                    onOpenMine = { recent ->
                        mineFocusRecent = recent
                        applyBack(AppNav.fromMore(tab, AppNav.TAB_MINE).copy(subOrigin = AppNav.TAB_HOME))
                    },''',
)

# 6) 设置 → 来源页
sub(
    '                    { subReturnTab = tab; subOrigin = 6; showSources = true },',
    '                    { applyBack(AppNav.openSources(tab, origin = AppNav.TAB_SETTINGS)) },',
)

# 7) 搜索页「取消」
sub(
    '''                    onClose = {
                        query = ""
                        when {
                            searchReturnTo == -2 -> {
                                tab = subReturnTab
                                showMore = true
                            }
                            searchReturnTo >= 0 -> tab = searchReturnTo
                            // 底栏直接进来的搜索：取消 = 回首页（不叠加更多面板）。
                            else -> tab = 0
                        }
                        searchReturnTo = -1
                    },''',
    '''                    onClose = { applyBack(AppNav.exitSearch(searchReturnTo, subReturnTab)) },''',
)

# 8) subReturnTab 声明处的注释收一句
sub(
    '''    /** §61.378：进入次级页前的一级 tab（0/1/3）——从「更多」进来的次级页退出时要回到它。 */
    var subReturnTab by rememberSaveable { mutableIntStateOf(0) }''',
    '''    /** §61.379：进入次级页前的一级 tab——从「更多」进来的次级页退出时回到它（见 [AppNav]）。 */
    var subReturnTab by rememberSaveable { mutableIntStateOf(0) }''',
)

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('组合根已接 AppNav，行数:', s.count(chr(10)) + 1)
