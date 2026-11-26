# -*- coding: utf-8 -*-
"""§61.377：MIUI 说明块从常驻改为弹层。

真机一级页实测：「外观与维护」整个分组被挤出屏幕，根因是 MIUI 说明块常驻占了 ~90dp。
说明是低频信息，按 §61.376 的两级化思路下沉到「点行打开的面板」。
"""
import io

P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SettingsScreen.kt'

with io.open(P, encoding='utf-8', newline='') as f:
    s = f.read()

# 1) 删常驻说明块
BLOCK = '''            if (com.ccmsc.cloudmusic.MiuiAutostartGuide
                .shouldShow(android.os.Build.MANUFACTURER)
            ) {
                // §61.275（V0.4-02）：说明块加边界样式——与卡片行区分，长文不再像「行内小字」
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FnBaseline.PagePaddingDp.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(FnComp.CardBg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        com.ccmsc.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
'''
assert s.count(BLOCK) == 1, s.count(BLOCK)
s = s.replace(BLOCK, '')

# 2) MIUI 行改为打开弹层
OLD_ROW = '''                    FnRow(
                        dividerAbove = true,
                        icon = FnIcon.Power,
                        title = "后台播放不中断（MIUI）",
                        onClick = onOpenAutostartSettings,
                        value = "去开启自启动",
                    )'''
NEW_ROW = '''                    FnRow(
                        dividerAbove = true,
                        icon = FnIcon.Power,
                        title = "后台播放不中断（MIUI）",
                        // §61.377：先开说明面板（含「去开启自启动」动作）——原实现点行直接跳系统设置，
                        // 但那段 4 行说明常驻在页面上，把「外观与维护」整组挤出了屏幕。
                        onClick = { showMiuiGuide = true },
                        value = "需手动开自启动",
                    )'''
assert s.count(OLD_ROW) == 1
s = s.replace(OLD_ROW, NEW_ROW)

# 3) 状态变量
ANCHOR = '    var subPage by remember { mutableStateOf(SettingsSubPage.NONE) }'
assert s.count(ANCHOR) == 1
s = s.replace(ANCHOR, ANCHOR + '\n    var showMiuiGuide by remember { mutableStateOf(false) }')

# 4) 弹层（插在主题弹层之前，与其它弹层同层）
DIALOG_ANCHOR = '    if (showTheme) {'
assert s.count(DIALOG_ANCHOR) == 1
DIALOG = '''    if (showMiuiGuide) {
        // §61.377：MIUI 专属说明——内容给全，但不常驻占屏。
        FnSheetDialog(
            onDismiss = { showMiuiGuide = false },
            title = "后台播放不中断（MIUI）",
            content = {
                Text(
                    com.ccmsc.cloudmusic.MiuiAutostartGuide.GUIDE_TEXT,
                    color = FnComp.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            },
            actions = listOf(
                "去开启自启动" to {
                    showMiuiGuide = false
                    onOpenAutostartSettings()
                },
                "知道了" to { showMiuiGuide = false },
            ),
        )
    }
'''
s = s.replace(DIALOG_ANCHOR, DIALOG + DIALOG_ANCHOR)

with io.open(P, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('MIUI 说明改为弹层；一级页省出 ~90dp')
