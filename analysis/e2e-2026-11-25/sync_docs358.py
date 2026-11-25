# -*- coding: utf-8 -*-
"""§61.358 文档同步：报告 / docs12 / docs09 / docs17 / 总账。"""

def rw(path, fn):
    s = open(path, encoding='utf-8').read()
    s2 = fn(s)
    open(path, 'w', encoding='utf-8', newline='').write(s2)
    print('updated', path, '| CR', s2.count('\r'), 'LF', s2.count('\n'))

# ---------- 报告：纠正前判 + 追加定案 ----------
def report(s):
    old = ('**「白色透明线」排查记录**：搜索页迷你条与胶囊栏做竖扫（x=60/200/340/400/620/1120，\n'
           'y 1380~1990）与 2× 裁切（14-zoom.png），**像素层面不存在贯穿亮线**；胶囊内 (33~44)\n'
           '为 CapsuleBg/CardBg 与其抗锯齿，未出现 >100 的亮行。当前唯一实测到的白色细线是键盘\n'
           '自带顶边（y=2015, 237,239,242）。待用户指认具体位置后另批处理。')
    new = ('**「白色透明线」：我此前判读错了**。按「找 >100 的亮行」去扫，胶囊内部确实没有亮线，\n'
           '于是误判成缩略图错觉。用户发来 10:09 原图（1440x3200）后按「相对周围 +6~8」重扫：\n'
           '\n'
           '- 胶囊顶边 `(43,43,45)` → 中部 `(33,33,36)` 是**单调衰减的渐变**，而 4 个 52dp tab\n'
           '  内容区在 y=1879~1904 保持 `(40~43)` ⇒ 中心高度浮出一条 +8 的亮带，肉眼即「白色透明线」。\n'
           '- 根因：`FnCapsuleBar` 的修饰符顺序是 `clip → background → shadow`，**shadow 在\n'
           '  background 之后（内层）**，Compose 为内容建 graphics layer 并把 8dp elevation 阴影\n'
           '  画进胶囊内部；layer 边界正好落在 4 个 tab 的内容区，于是内部亮、外面暗。\n'
           '- 修法：删掉 `.shadow(8.dp, RoundedCornerShape(28.dp))`（深色底上黑影本就不可见，\n'
           '  属于零视觉损失的减法）。判据 §61.358：FnCapsuleBar 体内禁 `.shadow(`，且盒模型\n'
           '  四件套（clip/background/padding/圆角）在位；guard 305→306，A/B 注回 shadow → FAIL。\n'
           '- 教训：亮度判据不能只钉绝对值（>100）。深色 UI 的 banding 是**相对差**（+6~8/255），\n'
           '  必须扫「同列相邻 y 的差值」而不是「是否达到某个亮度阈值」。')
    assert s.count(old) == 1, ('report anchor', s.count(old))
    return s.replace(old, new)

rw('analysis/e2e-2026-11-25-report.md', report)

# ---------- docs/12 ----------
def doc12(s):
    return s + '''

### §61.358（2026-11-25）tab 胶囊「白色透明线」：内层 shadow 被画进胶囊内部

**用户一手**：截图指出胶囊栏正中有一条贯穿的浅色带（「播放条上一条白色透明线」）。

**一手取证**（用户 1440×3200 原图）：胶囊顶边 `(43,43,45)` 单调衰减到中部 `(33,33,36)`，
而 4 个 52dp tab 内容区在 y=1879~1904 保持 `(40~43)` ⇒ 中心高度浮出 +8 亮带。此前按
「亮行 >100」扫描漏判——深色 UI 的 banding 是相对差（+6~8/255），不是绝对亮度。

**根因**：`FnCapsuleBar` 的 `Row` 写成 `clip → background → shadow`。`shadow` 在
`background` **之后（内层）**时，Compose 会为后续内容建 graphics layer 并把 elevation
阴影画进胶囊内部，layer 边界恰是 4 个 tab 的内容区 ⇒ 内亮外暗 = 一条线。

**修法**：删除 `.shadow(8.dp, RoundedCornerShape(28.dp))`。深色底（#1E1E21）上 8dp 黑影
本就不可见，删掉零视觉损失、banding 归零；胶囊仍是 `clip + background(CapsuleBg) + padding`
四件套，盒模型与触控区完全不变。

**判据**：新增 §61.358 —— FnCapsuleBar 函数体内禁 `.shadow(`（反向），clip / CapsuleBg /
6dp×10dp padding / 28dp 圆角在位（正向）。guard 305→306；A/B 注回 shadow → FAIL，恢复 → PASS。
'''

rw('docs/12-编码阶段第一批交付.md', doc12)

# ---------- docs/09 ----------
def doc09(s):
    return s + ('> **§61.358（2026-11-25）tab 胶囊白色透明线**：用户原图逐像素取证——胶囊顶边 (43) '
                '衰减到中部 (33)，而 4 个 tab 内容区保持 (42) ⇒ +8 亮带。根因是 `clip → background → shadow` '
                '顺序把 elevation 阴影画进胶囊内部；删除 8dp shadow（深色底不可见）后 banding 归零，'
                '盒模型不变。§61.358 判据新增（禁体内 `.shadow(`），guard 306。教训：深色 UI 的 banding '
                '必须扫相邻 y 差值，不能只钉绝对亮度阈值。\n')
rw('docs/09-文档闭环总览.md', doc09)

# ---------- docs/17 ----------
def doc17(s):
    return s + ('''
### 8.251 tab 胶囊内层 shadow 造成「白色透明线」（§61.358，2026-11-25）

- 用户原图实测：胶囊顶边 (43,43,45) → 中部 (33,33,36) 渐变，4 个 tab 内容区保持 (40~43)，
  中心浮出 +8 亮带。
- 根因：`clip → background → shadow` 顺序错误，elevation 阴影被画进胶囊内部。
- 修法：删掉 8dp shadow（深色底不可见、零视觉损失），盒模型四件套不变；guard §61.358。
''')
rw('docs/17-竞品模块清单与复刻规划.md', doc17)

# ---------- 总账 G-6 行补充 ----------
def ledger(s):
    old = '| ~~G-6~~ | ✅ | **已完成（§61.356 复现 → §61.357 定稿）**'
    new = '| ~~G-6~~ | ✅ | **已完成（§61.356 复现 → §61.357 定稿；同窗又定位 §61.358 胶囊白色透明线）**'
    assert s.count(old) == 1
    s = s.replace(old, new)

    old_tail = 'A/B 4 条 FAIL 后恢复；analysis/e2e-2026-11-25-report.md |'
    new_tail = ('A/B 4 条 FAIL 后恢复；analysis/e2e-2026-11-25-report.md。'
                '**追加 §61.358**：用户指出胶囊栏正中「白色透明线」= `clip→background→shadow` 顺序把 8dp 阴影'
                '画进胶囊内部（顶边 43→中部 33、内容区 42 ⇒ +8 亮带）；删除 shadow（深色底不可见）后归零，'
                'guard 306、A/B 注回 shadow FAIL |')
    assert s.count(old_tail) == 1
    s = s.replace(old_tail, new_tail)
    return s

rw('analysis/pending-register-2026-11-24.md', ledger)
