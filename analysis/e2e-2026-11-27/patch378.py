# -*- coding: utf-8 -*-
"""§61.378：修「后退乱跳」——导航退出逻辑收敛。

真机复现的三条：
1. 更多 → 音乐来源 → 返回：掉到**首页**（应回更多面板）。根因：showSources 分支只清标志，
   不恢复来源，tab 停在进入前的一级 tab。
2. 设置 → 音乐来源 → 返回（回设置）→ 再返回：**页面纹丝不动**（死锁）。
   根因：subOrigin 没清，仍为 6 ⇒ exitSubPage() 执行 tab = 6，自指。
3. 底栏点搜索（在设置页时）→ 返回：回到**设置页**。根因：底栏把「当前 tab」记成返回目标，
   而 tab=6 同时是次级页索引，语义混淆；且回到设置后 subOrigin 仍残留 ⇒ 继续触发 2。

修法（不重构架构，收敛状态语义）：
- 新增 subReturnTab：进入次级页前的一级 tab（0/1/3）。
- exitSubPage() 统一出口并清 subOrigin；从「更多」进来时 tab 恢复成 subReturnTab 再叠更多面板
  （原来不清 tab ⇒ 返回更多、再返回又跳回刚退出的那次级页）。
- 来源页返回走同一出口（showSources = false + exitSubPage）。
- 底栏点搜索不再记录返回目标（一级 tab 平级，返回交给系统）；「更多 → 搜索」用 -2 哨兵表示回更多面板。
"""
import io

P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/CloudMusicApp.kt'

with io.open(P, encoding='utf-8', newline='') as f:
    s = f.read()


def sub(old, new, count=1):
    global s
    assert s.count(old) == count, (s.count(old), old[:60])
    s = s.replace(old, new)


# 1) 新增 subReturnTab
sub(
    '    var subOrigin by rememberSaveable { mutableIntStateOf(-1) }',
    '    var subOrigin by rememberSaveable { mutableIntStateOf(-1) }\n'
    '    /** §61.378：进入次级页前的一级 tab（0/1/3）——从「更多」进来的次级页退出时要回到它。 */\n'
    '    var subReturnTab by rememberSaveable { mutableIntStateOf(0) }',
)

# 2) exitSubPage 统一出口 + 清 subOrigin
sub(
    '''    fun exitSubPage() {
        if (subOrigin >= 0) tab = subOrigin else showMore = true
    }''',
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
)

# 3) BackHandler enabled + when
sub(
    '''    BackHandler(
        enabled = showPlayer || showSources || showMore || tab in 2..6,
    ) {
        when {
            showPlayer -> showPlayer = false
            showSources -> showSources = false
            showMore -> showMore = false''',
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
            showMore -> showMore = false''',
)

# 4) 搜索返回分支
sub(
    '''            tab == 3 -> {
                query = ""
                if (searchReturnTo >= 0) tab = searchReturnTo else showMore = true
            }''',
    '''            tab == 3 -> {
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
            }''',
)

# 5) 底栏点搜索：不再记录返回目标
sub(
    '''                                2 -> {
                                    if (tab != 3) searchReturnTo = tab
                                    tab = 3
                                }''',
    '''                                // §61.378：底栏是平级导航 —— 进来时清掉返回目标，
                                // 搜索页在无关键词时按返回交给系统（原来会把当前 tab 记成目标，
                                // 在设置页点搜索再返回会「跳回设置」）。
                                2 -> {
                                    searchReturnTo = -1
                                    tab = 3
                                }''',
)

# 6) 更多面板 6 个入口：记录 subReturnTab；搜索用 -2
sub(
    '''                    onPlaylists = { showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 2 },
                    onSearch = { showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 3 },
                    onQueue = { showMore = false; subOrigin = -1; tab = 4 },
                    onMine = { showMore = false; mineFocusRecent = false; subOrigin = -1; tab = 5 },
                    onSettings = { showMore = false; subOrigin = -1; tab = 6 },
                    onSources = { showMore = false; subOrigin = -1; showSources = true },''',
    '''                    // §61.378：全部记下「进来之前的一级 tab」，退出时才能回到正确的更多面板之上。
                    onPlaylists = { subReturnTab = tab; showMore = false; searchReturnTo = -1; subOrigin = -1; tab = 2 },
                    onSearch = { subReturnTab = tab; showMore = false; searchReturnTo = -2; subOrigin = -1; tab = 3 },
                    onQueue = { subReturnTab = tab; showMore = false; subOrigin = -1; tab = 4 },
                    onMine = { subReturnTab = tab; showMore = false; mineFocusRecent = false; subOrigin = -1; tab = 5 },
                    onSettings = { subReturnTab = tab; showMore = false; subOrigin = -1; tab = 6 },
                    onSources = { subReturnTab = tab; showMore = false; subOrigin = -1; showSources = true },''',
)

# 7) 首页两个入口补 subReturnTab（subOrigin 分支不用它，但保持一致）
sub(
    '                    onOpenSources = { subOrigin = 0; showSources = true },',
    '                    onOpenSources = { subReturnTab = tab; subOrigin = 0; showSources = true },',
)
sub(
    '''                    onOpenMine = { recent ->
                        mineFocusRecent = recent
                        subOrigin = 0
                        tab = 5
                    },''',
    '''                    onOpenMine = { recent ->
                        mineFocusRecent = recent
                        subReturnTab = tab
                        subOrigin = 0
                        tab = 5
                    },''',
)

# 8) 设置 → 来源页
sub(
    '                    { subOrigin = 6; showSources = true },',
    '                    { subReturnTab = tab; subOrigin = 6; showSources = true },',
)

# 9) 搜索页「取消」按钮：-2 回更多；>=0 回来源；-1（底栏进来）回首页
sub(
    '''                    onClose = {
                        query = ""
                        if (searchReturnTo >= 0) tab = searchReturnTo else showMore = true
                    },''',
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
)

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('导航退出逻辑已收敛；改动点 9 处')
