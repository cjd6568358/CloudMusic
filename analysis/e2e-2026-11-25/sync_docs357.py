# -*- coding: utf-8 -*-
"""§61.357 文档四件套同步：总账 / docs12 / docs09 / docs17。"""
import io

def rw(path, fn):
    s = open(path, encoding='utf-8').read()
    s2 = fn(s)
    assert s2 != s, 'no change: ' + path
    open(path, 'w', encoding='utf-8', newline='').write(s2)
    print('updated', path, '| CR', s2.count('\r'), 'LF', s2.count('\n'))

# ---------- 总账 ----------
def ledger(s):
    # header note
    old_note = '> **2026-11-25 真机窗（§61.356）**：G-6 真机复现 FAIL 并修正（IME 让位不扣 navigationBars，A/B 真机弹起贴合/收起回原位），E-11 收起回原位 PASS；G-7/G-5/G-10/G-11/E-14 同窗 PASS；G-4 变灰仅 debug fixture 源非病根；E-13 无有效 CUE 封面样本。'
    new_note = ('> **2026-11-25 真机窗（§61.356 → §61.357 二次修正）**：G-6 真机复现 FAIL（红圆被键盘切 109px/60%）；'
                '§61.356 首修（底栏整体上移到键盘上沿）被用户一手否定「tab 和播放条跟着键盘推高」；'
                '§61.357 定稿为「键盘弹起时底栏让位消失 + 内容区自吃 ime」，真机 A/B 通过（弹起时四 tab/迷你条节点消失、'
                '内容贴键盘上沿；收起回 2797~2915 / 2524~2608 分毫不动）。E-11 收起判据 PASS；G-7/G-5/G-10/G-11/E-14 同窗 PASS；'
                'G-4 变灰仅 debug fixture 源非病根；E-13 无有效 CUE 封面样本。')
    assert s.count(old_note) == 1
    s = s.replace(old_note, new_note)

    # A-1 row
    old_a1 = '| ~~A-1~~ | ✅ | **已完成（§61.325）**：IME 让位收口到 `Scaffold.bottomBar`（`WindowInsets.ime.exclude(navigationBars)`），搜索页删掉自己的 `imePadding` 防双倍留白；§61.262 判据同步升级 | 真机 01:43 截图 | 已验收：guard §61.325 正反两判据 + A/B（抽掉让位 → §61.325/§61.262 双 FAIL）；真机落位见 E-11 |'
    new_a1 = '| ~~A-1~~ | ✅ | **已完成（§61.325 → §61.357 定稿）**：让位收口到 Scaffold —— 键盘弹起时 `bottomBar` 按 `WindowInsets.isImeVisible` 让位消失（高度 0），内容区 `.consumeWindowInsets(padding).imePadding()` 自吃 inset；搜索页零 imePadding 防双倍留白。§61.356 的「底栏跟着 IME 推高」被真机否定 | 真机 10.0.0.5:40345 截图 + UI dump + 像素扫描 | 已验收：guard §61.325/§61.357 正反判据 + A/B 4 条（去 isImeVisible / 推高回潮 / 去 consume / 搜索页重复让位 全 FAIL）；真机落位见 E-11 |'
    assert s.count(old_a1) == 1
    s = s.replace(old_a1, new_a1)

    # E-11 row
    old_e11 = '| ~~E-11~~ | ✅ | **已判读（§61.356，部分经 G-6 修正后达标）**：修正版 A/B 真机弹起时 tab 1777~1959 完整贴键盘上沿、收起回 2797~2915 原位；修正前版本实测半截被切（G-6 FAIL 一手）。添加源表单弹键盘未单独判读 |'
    new_e11 = '| ~~E-11~~ | ✅ | **已判读（§61.357 定稿口径）**：弹键盘时四 tab 与迷你条让位消失（uiautomator 节点消失）、热词/结果直接到键盘上沿，无浮空底栏、无「底栏厚度黑带」；收键盘 tab 回 `[356,2797][538,2915]`、迷你条「曾经」回 `[259,2524][362,2608]` 分毫不动。添加源表单弹键盘未单独判读 |'
    assert s.count(old_e11) == 1
    s = s.replace(old_e11, new_e11)

    # G-6 row
    old_g6 = '| ~~G-6~~ | ✅ | **已完成（§61.356，真机复现+修正）**：红圆被键盘切 109px（60%）=「黑遮罩」真身；根因是 A-1 的 `ime.exclude(navigationBars)` 在净键盘高度上重复扣 165px 导航栏。改全量 `WindowInsets.ime` 后 A/B 真机实测弹起贴键盘上沿、收起回原位 | 真机 10.0.0.5:40345（Redmi K60）截图 + UI dump 几何；guard §61.356 + §61.262/§61.325 升级（反向禁 exclude 回潮）；A/B 3 条 FAIL 后恢复；analysis/e2e-2026-11-25-report.md |'
    new_g6 = '| ~~G-6~~ | ✅ | **已完成（§61.356 复现 → §61.357 定稿）**：真身是底栏被键盘切 109px（60%）；§61.356 改成「底栏上移到键盘上沿」被用户一手否定（浮在半空）；§61.357 改为「键盘弹起时底栏让位消失 + 内容区自吃 ime」，真机 A/B 弹起时四 tab/迷你条消失、内容贴键盘上沿，收起回原位 | 真机 10.0.0.5:40345（Redmi K60）截图 `18-small.png`/`19-restored.png` + UI dump 几何；guard §61.357 新增 + §61.262/§61.325 升级 + §61.356 退化证据链（305 PASS）；A/B 4 条 FAIL 后恢复；analysis/e2e-2026-11-25-report.md |'
    assert s.count(old_g6) == 1
    s = s.replace(old_g6, new_g6)

    # plan line
    old_plan = '~~  7. **G 组 11 项**~~ ✅ 10/11 完成（§61.344~§61.355），仅剩 G-6（P0 真机键盘黑遮罩）与 E 组同窗判读。'
    new_plan = '~~  7. **G 组 11 项**~~ ✅ 11/11 完成（§61.344~§61.355 + G-6 §61.356/§61.357 真机定稿）。'
    assert s.count(old_plan) == 1
    s = s.replace(old_plan, new_plan)

    # section title
    old_title = '## G · 用户 2026-11-25 新提（11 项，已完成 10，剩 1 真机）'
    new_title = '## G · 用户 2026-11-25 新提（11 项，全部完成）'
    assert s.count(old_title) == 1
    s = s.replace(old_title, new_title)
    return s

rw('analysis/pending-register-2026-11-24.md', ledger)

# ---------- docs/12 ----------
def doc12(s):
    return s + '''

### §61.357（2026-11-25）G-6 定稿：键盘弹起时底栏「让位消失」，不跟着 IME 推高

**用户一手**：「搜索页为什么 tab 和播放条跟着键盘推高了？」——§61.356 把整条底栏上移到
键盘上沿（tab 1777~1959 贴 y=2015），真机观感是「tab 和播放条浮在键盘上方」，方案当场作废。
结论：让位方向错了，不是推底栏，而是底栏让位消失。

**改法**：`bottomBar = { if (!WindowInsets.isImeVisible) { Column { MiniPlayer; FnCapsuleBar } } }`
⇒ 键盘弹起时 bottomBar 高度 0，Scaffold 不再为底栏留白；内容区
`.padding(padding).consumeWindowInsets(padding).displayCutoutPadding().imePadding()`
⇒ 先把 Scaffold padding 登记为已消费，imePadding 才不会把 navigationBars（真机 165px）
再补一遍（多补就是一条导航栏高的黑带）。搜索页保持零 imePadding（防双倍留白）。

**真机实测**（Redmi K60 / 1440x3200 / 三键导航）：弹键盘时 uiautomator 里四个 tab 节点与
迷你条 `text="曾经"` 全部消失，截图热词直接到键盘上沿、无浮空底栏、无黑带；收键盘 tab 回
`[356,2797][538,2915]`、迷你条回 `[259,2524][362,2608]`，分毫不动。键盘顶边 y=2015 的
(237,239,242) 浅灰线是 MIUI 输入法自带，不是 app UI。

**判据**：§61.262/§61.325 升级 —— 正钉 `WindowInsets.isImeVisible` + 内容 `.imePadding()`；
反禁 `exclude(navigationBars)`、`windowInsetsPadding(WindowInsets.ime)`（推高回潮）、
搜索页内重复让位。§61.356 退化为真机证据链（报告锚点 + 禁 exclude）。新增 §61.357
（consumeWindowInsets 防导航栏双计）。guard 304→305；A/B 4 条（去 isImeVisible、推高回潮、
去 consumeWindowInsets、搜索页重复 imePadding）全部 FAIL 后逐字节恢复。
'''
rw('docs/12-编码阶段第一批交付.md', doc12)

# ---------- docs/09 ----------
def doc09(s):
    return s + ('> **§61.357（2026-11-25）G-6 定稿**：§61.356 的「底栏跟着 IME 上移」被用户一手否定'
                '（「tab 和播放条跟着键盘推高」）⇒ 改为键盘弹起时底栏按 `WindowInsets.isImeVisible` 让位消失、'
                '内容区 `consumeWindowInsets(padding) + imePadding()` 自吃 inset。真机：弹起时四 tab 与迷你条节点消失、'
                '内容贴键盘上沿；收起回 2797~2915 / 2524~2608 分毫不动。§61.262/§61.325 判据升级、§61.356 退化证据链、'
                '新增 §61.357；guard 305。\n')
rw('docs/09-文档闭环总览.md', doc09)

# ---------- docs/17 ----------
def doc17(s):
    return s + ('''
### 8.250 G-6 定稿：底栏让位而非推高（§61.357，2026-11-25）

- §61.356 把底栏整体上移到键盘上沿 → 真机「tab 和播放条浮在键盘上」，用户否定。
- 定稿：键盘弹起时 `bottomBar` 让位消失（`WindowInsets.isImeVisible` 反判），内容区
  `consumeWindowInsets(padding).imePadding()` 自吃 inset；导航栏不双计 ⇒ 键盘上方无黑带。
- 真机：弹起时四 tab/迷你条节点消失、内容贴键盘上沿；收起回 `[356,2797][538,2915]` /
  `[259,2524][362,2608]` 分毫不动。guard 305、A/B 4 条 FAIL 后恢复。
''')
rw('docs/17-竞品模块清单与复刻规划.md', doc17)
