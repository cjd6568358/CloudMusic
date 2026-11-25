# -*- coding: utf-8 -*-
"""§61.365：三处布局裁切/遮挡（修正版：渐隐用比例渐变，不引入 density）。"""
P_HOME = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/HomeScreen.kt'
P_LIB = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnLibraryScreen.kt'
P_SET = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SettingsScreen.kt'

# ---------- 1. 首页卡片右侧渐隐 ----------
s = open(P_HOME, encoding='utf-8').read()
old = '''        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                RoamCard(count = tracks.size) {
                    if (tracks.isNotEmpty()) {
                        onPlayTracks(tracks.shuffled(), 0)
                    }
                }
                Spacer(Modifier.width(14.dp))
                CollectCard(count = favoriteCount, onClick = { onOpenMine(false) })
                Spacer(Modifier.width(14.dp))
                RecentCard(count = recentCount, onClick = { onOpenMine(true) })
                Spacer(Modifier.width(20.dp))
            }
        }'''
new = '''        item {
            // §61.365：三张 160dp 卡片 + 2×14dp 间距 = 548dp，屏宽只有 411dp ⇒ 第三张被
            // 右边缘硬切掉三分之一，用户读作「渲染 bug」。不动卡片设计，只在最外层叠一条
            // 占宽 15% 的右缘渐隐（固定，不随内容滚动）：被切的部分淡出成「还能右滑」。
            Box {
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    RoamCard(count = tracks.size) {
                        if (tracks.isNotEmpty()) {
                            onPlayTracks(tracks.shuffled(), 0)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    CollectCard(count = favoriteCount, onClick = { onOpenMine(false) })
                    Spacer(Modifier.width(14.dp))
                    RecentCard(count = recentCount, onClick = { onOpenMine(true) })
                    Spacer(Modifier.width(20.dp))
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0.85f to Color.Transparent,
                                1f to FnComp.PageBg,
                            ),
                        ),
                )
            }
        }'''
assert s.count(old) == 1, ('home cards', s.count(old))
s = s.replace(old, new)
if 'import androidx.compose.ui.graphics.Brush' not in s:
    s = s.replace('import androidx.compose.ui.graphics.Color',
                  'import androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color', 1)
open(P_HOME, 'w', encoding='utf-8', newline='').write(s)
print('home: right-edge fade added')

# ---------- 2. 音乐库 chip 改 FlowRow ----------
s = open(P_LIB, encoding='utf-8').read()
old = '''        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                views.forEachIndexed { i, v ->
                    FnViewChip(v.label, v.icon, i == viewIndex) { viewIndex = i }
                }
            }
        }'''
new = '''        item {
            // §61.365：五个 chip 横排需要 480dp+，可用宽只有 371dp ⇒ 第五个「不可用」被
            // 右缘切掉一半。改 FlowRow 自动换行，五个视图全部可见。
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                views.forEachIndexed { i, v ->
                    FnViewChip(v.label, v.icon, i == viewIndex) { viewIndex = i }
                }
            }
        }'''
assert s.count(old) == 1, ('lib chips', s.count(old))
s = s.replace(old, new)
if 'import androidx.compose.foundation.layout.FlowRow' not in s:
    s = s.replace('import androidx.compose.foundation.layout.Column',
                  'import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.FlowRow', 1)
if 'ExperimentalLayoutApi' not in s:
    s = s.replace('@Composable\nfun FnLibraryScreen(',
                  '@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)\n@Composable\nfun FnLibraryScreen(', 1)
open(P_LIB, 'w', encoding='utf-8', newline='').write(s)
print('library: chips -> FlowRow')

# ---------- 3. 设置页尾部留白 ----------
s = open(P_SET, encoding='utf-8').read()
old = '            Spacer(Modifier.height(28.dp))'
assert s.count(old) >= 1, ('settings tail', s.count(old))
new = ('            // §61.365：28dp 太贴底，滚动到底时最后一项（音量均衡分析）正好压在胶囊\n'
       '            // 圆角上。加到 44dp，留出与分组间距同级的呼吸缝。\n'
       '            Spacer(Modifier.height(44.dp))')
s = s.replace(old, new, 1)
open(P_SET, 'w', encoding='utf-8', newline='').write(s)
print('settings: bottom spacer 28 -> 44')
