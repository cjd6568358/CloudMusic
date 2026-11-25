# -*- coding: utf-8 -*-
"""§61.360 文档同步：docs12 / docs09 / docs17 / 总账 / 报告 / memory。"""

def rw(path, fn):
    s = open(path, encoding='utf-8').read()
    s2 = fn(s)
    open(path, 'w', encoding='utf-8', newline='').write(s2)
    print('updated', path)

rw('docs/12-编码阶段第一批交付.md', lambda s: s + '''

### §61.360（2026-11-25）启动页系统栏刷品牌红：红屏不再压两条黑边

**一手**：`52-small.png`（§61.359 装机后冷启动）—— 页面红 `#E54545`，但顶部状态栏与底部
导航栏仍是 `themes.xml` 写死的纯黑，红屏上下各压一条黑，明显不统一。

**根因**：`SystemBars.apply`（§61.291 的声明式目标态）只挂在 `CloudMusicApp` 分支里；
启动页走的是 `MainActivity` 的 `if (showSplash)` 分支，期间没有任何代码碰过两栏 ⇒ 停在
theme 的黑色。

**修法**：`SplashScreen` 用 `DisposableEffect` 在显示期间把 `statusBarColor` /
`navigationBarColor` 刷成 `LegacyPalette.BrandRed`，并把 `isAppearanceLightStatusBars` /
`isAppearanceLightNavigationBars` 置 false（红底配浅色图标）。**刻意不做还原配对** ——
`onDispose` 留空，退出后由 `CloudMusicApp` 的 `SystemBars.apply` 按当前主题重算（同一份
声明式口径，避免多一条可能漏掉的还原路径）。`LocalView.context` 用 `findActivity()`
逐层解包（ContextWrapper 下直接强转会崩）。

**真机验证**（`53-splash3.png` / `54-home-after.png`，端点 mDNS `adb-2a81a46c-…`）：
- 启动页：status `(229,69,69)` / body `(228,69,69)` / nav `(229,69,69)` —— 三处同红，
  状态栏时间与电池为白色可见，底部三键为浅色可见，无黑边。
- 退出后：status / nav / body 均为 `(30,30,33)` = `FnComp.PageBg`，恢复主题目标态。

**判据**：新增 §61.360（两栏赋值 + 浅色图标 + findActivity 解包 + 取 BrandRed 单一真相源，
缺一即红）。guard 307→308；A/B 2 条（去掉状态栏赋值 / 去掉浅色图标）均 FAIL 后逐字节恢复。
''')

rw('docs/09-文档闭环总览.md', lambda s: s + ('> **§61.360（2026-11-25）启动页系统栏刷红**：启动页红屏上下原各压一条黑'
    '（SystemBars.apply 只在 CloudMusicApp 分支跑）。SplashScreen 用 DisposableEffect 把两栏刷成 '
    '`BrandRed` + 浅色图标，不做还原配对（退出后由 SystemBars 按主题重算）。真机：启动页三处同为 '
    '`(229,69,69)` 无黑边，退出后回 `(30,30,33)`。guard 308、A/B 2 条。\n'))

rw('docs/17-竞品模块清单与复刻规划.md', lambda s: s + ('''
### 8.253 启动页系统栏刷品牌红（§61.360，2026-11-25）

- 现象：启动页红 `#E54545` + 状态栏/导航栏纯黑（theme 写死），红屏压两条黑边。
- 根因：`SystemBars.apply` 只在 CloudMusicApp 分支执行，splash 分支无人碰两栏。
- 修法：SplashScreen 内 DisposableEffect 刷两栏为 BrandRed + 浅色图标；不还原，退出后由
  SystemBars 声明式重算。真机：启动页 status/body/nav 均 (229,69,69)，退出回 (30,30,33)。
'''))

def ledger(s):
    old = '> **2026-11-25 真机窗（§61.356 → §61.357 二次修正）**'
    new = ('> **2026-11-25 晚窗（§61.360）**：启动页红屏上下两条黑边（SystemBars.apply 不在 splash 分支）'
           '⇒ SplashScreen 内刷两栏为 BrandRed + 浅色图标，退出由 SystemBars 重算；真机三处同红、退出回 PageBg。\n'
           '> **2026-11-25 真机窗（§61.356 → §61.357 二次修正）**')
    assert s.count(old) == 1
    return s.replace(old, new)
rw('analysis/pending-register-2026-11-24.md', ledger)

rw('analysis/e2e-2026-11-25-report.md', lambda s: s + '''

## §61.360 启动页系统栏补验（2026-11-25 21:24，mDNS 端点 `adb-2a81a46c-…`）

装机 21:23 构建版（含 §61.359 图标/命名 + §61.360 栏色）。冷启动连拍 3 张：

| 采样点 | 启动页（53-splash3.png） | 退出后（54-home-after.png） |
|---|---|---|
| 状态栏 y=60 | `(229,69,69)` | `(30,30,33)` |
| 页面主体 | `(228,69,69)` | `(30,30,33)` |
| 导航栏 y=3120 | `(229,69,69)` | `(30,30,33)` |

- 启动页：整屏统一红，状态栏时间/电池为白色可见（顶栏近白像素 104 个），底部三键浅色可见，
  **无黑边**；白色声波标 + 「听见好声音」正常。
- 退出后：三处均为 `#1E1E21` = `FnComp.PageBg`，说明 `SystemBars.apply` 按主题重算生效，
  没有把红色留在窗口上。
''')
print('docs synced')

m = open('.workbuddy/memory/2026-11-25.md', encoding='utf-8').read()
m += '''
## 启动页系统栏刷红（§61.360，21:21~21:30）

- 用户给端点 `.1.15:40047`（实为 10.1.1.15，连不上），但 **adb mDNS 自动发现设备**
  `adb-2a81a46c-yCAQTs._adb-tls-connect._tcp` 直接可用 —— **教训：端点连不上先看 adb devices
  有没有 mdns 设备，别再让用户反复报端口**。
- 现象：启动页红 + 状态栏/导航栏纯黑（themes.xml 写死），红屏压两条黑边。
- 根因：`SystemBars.apply` 只挂在 CloudMusicApp 分支，splash 分支没人碰两栏。
- 修法：SplashScreen `DisposableEffect` 刷 statusBarColor/navigationBarColor = BrandRed +
  isAppearanceLight* = false；**onDispose 留空**（退出由 SystemBars 声明式重算，不做还原配对）；
  `Context.findActivity()` 递归解包（LocalView.context 可能是 ContextWrapper）。
- 真机：启动页 status/body/nav 全 (229,69,69)、顶栏白色图标可见；退出回 (30,30,33)。
- guard 307→308（§61.360）；A/B 2 条（去 statusBarColor / 去浅色图标）FAIL 后恢复。
'''
open('.workbuddy/memory/2026-11-25.md', 'w', encoding='utf-8', newline='').write(m)
print('memory synced')
