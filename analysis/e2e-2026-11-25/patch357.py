# -*- coding: utf-8 -*-
"""§61.357: bottomBar 不再跟着 IME 推高 -> 键盘弹起时底栏让位隐藏，内容区自己 imePadding。"""
p = 'app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicApp.kt'
src = open(p, encoding='utf-8').read()
lines = src.split('\n')

start = None
for i, l in enumerate(lines):
    if l == '        bottomBar = {':
        start = i
        break
assert start is not None, 'bottomBar anchor missing'

end = None
for j in range(start, len(lines)):
    if lines[j].startswith('    ) { padding ->'):
        end = j
        break
assert end is not None, 'scaffold content anchor missing'
print('bottomBar block: lines %d..%d (1-based %d..%d)' % (start, end - 1, start + 1, end))

new_bottom = '''        bottomBar = {
            // §61.357（G-6 二次修正，2026-11-25 用户一手）：键盘弹起时**整条底栏不参与排版**。
            // §61.356 让底栏跟着 IME 上移到键盘上沿——真机观感是「tab 和播放条被键盘推高、
            // 浮在半空」，用户当场质疑，该方案作废。改回主流口径（网易云/QQ音乐同款）：
            // 输入时键盘贴底、底栏让位消失；内容区自己吃 ime inset 滚到键盘上沿。
            // 此时 bottomBar 高度为 0，Scaffold 不再为底栏留白，键盘上方也就不会出现
            // 「底栏高度那么厚」的黑带——那正是用户最初反馈的「键盘上方黑色遮罩」
            // （§61.325 把让位收口到底栏时，误判成「输入时导航消失」）。
            if (!WindowInsets.isImeVisible) {
                Column {
                    // §61.196（W13）：设置页不显示播放条（基准 161550 无播放条，仅底部胶囊）。
                    // §61.279（V0.4-06）：**无当前曲目时不显示空条**——冷启动只有一条
                    // 「未在播放」+ 一个按了没反应的播放键，是死控件；对应 03-10 §9 B03。
                    if (tab != 6 && player.currentTrackId != null) {
                        MiniPlayer(
                            player = player,
                            onPlay = onPlay,
                            onPause = onPause,
                            coverRef = miniCoverRef,
                            title = miniTitle,
                            subtitle = miniSubtitle,
                            onOpen = { showPlayer = true },
                            onOpenQueue = { showMore = false; tab = 4 },
                        )
                    }
                    // §61.222（用户指示）：底栏加搜索 tab——四胶囊（首页/音乐库/搜索/更多）。
                    // §61.187/§61.195 的「搜索页隐藏胶囊栏」口径随本次调整作废：搜索成为
                    // 一等 tab 后保持底栏可见（选中态可见性优先）。
                    // §61.168 内部 tab 索引 0~7 保持不变；「更多」面板承载歌单/队列/我的/设置，
                    // 功能不丢。无返回箭头/无账号入口（辨别铁律）。
                    // §61.191（W4）：仅图标化——白色图标 + 选中红圆底白图标，去文字标签。
                    // §61.330（A-4）：图标集合与画法都收口了——`CapsuleTabs` 是唯一列表，
                    // 四枚字形全部来自 `FnIcon`（线性、同视框、同笔画），此处不再自绘。
                    FnCapsuleBar(
                        icons = CapsuleTabs,
                        selected = when {
                            showSources -> -1
                            showMore -> 3
                            tab == 0 -> 0
                            tab == 1 -> 1
                            tab == 3 -> 2
                            else -> 3
                        },
                        onSelect = { i ->
                            showSources = false
                            showMore = false
                            when (i) {
                                0, 1 -> tab = i
                                2 -> {
                                    if (tab != 3) searchReturnTo = tab
                                    tab = 3
                                }
                                3 -> showMore = true
                            }
                        },
                    )
                }
            }
        },'''.split('\n')

lines[start:end] = new_bottom
src2 = '\n'.join(lines)

old_mod = '''            Modifier
                .fillMaxSize()
                .padding(padding)
                // §61.352（G-5）：Scaffold 的 padding 已含 statusBars（系统窗口内边距），
                // 原先此处再叠一层 statusBarsPadding 修饰符 = 状态栏高度加两次 ⇒ 顶部多出一整条。
                // 只保留 displayCutoutPadding（竖屏挖孔只补左右安全区，不补顶部）。
                .displayCutoutPadding(),'''

new_mod = '''            Modifier
                .fillMaxSize()
                .padding(padding)
                // §61.357（G-6 二次修正）：Scaffold 的 padding 是普通 padding、不消费 window
                // insets；先把它登记为「已消费」，imePadding 才不会把里面的 navigationBars
                // 再补一遍（真机 MIUI 三键导航 165px，不 consume 会多空一条导航栏的黑带）。
                // §61.352（G-5）：此处不再叠 statusBarsPadding——Scaffold padding 已含
                // statusBars，加两次会顶部多出一整条；只保留 displayCutoutPadding（竖屏
                // 挖孔只补左右安全区，不补顶部）。
                .consumeWindowInsets(padding)
                .displayCutoutPadding()
                .imePadding(),'''

assert src2.count(old_mod) == 1, ('content modifier anchor', src2.count(old_mod))
src2 = src2.replace(old_mod, new_mod)

imp_old = '''import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
'''
imp_new = '''import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
'''
assert src2.count(imp_old) == 1, ('import anchor', src2.count(imp_old))
src2 = src2.replace(imp_old, imp_new)

open(p, 'w', encoding='utf-8', newline='').write(src2)
print('patched. CR count=%d LF count=%d' % (src2.count('\r'), src2.count('\n')))
