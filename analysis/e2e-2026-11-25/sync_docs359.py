# -*- coding: utf-8 -*-
"""§61.359 文档同步：docs12 / docs09 / docs17 / 总账 / 报告 / memory。"""

def rw(path, fn):
    s = open(path, encoding='utf-8').read()
    s2 = fn(s)
    open(path, 'w', encoding='utf-8', newline='').write(s2)
    print('updated', path, '| CR', s2.count('\r'))

# docs/12
rw('docs/12-编码阶段第一批交付.md', lambda s: s + '''

### §61.359（2026-11-25）G-1 重设计：图标 / app 名「云音乐」/ 启动页红统一

**用户一手**：「图标重新设计一下，丑死了」；并拍板 app 名 = B「云音乐」、启动页红「统一」。

**旧图标为什么丑**（§61.351 版）：黑盘 + **红标签** + 红底 —— 标签色与背景同色，
中间像被挖了一个红洞；盘面又是单层死黑，48dp 下只剩「红方块 + 黑洞」，没有唱片感。

**新设计**（三层分开 + 质感）：
- 盘身 `#121212` + 内层 `#1A1A1A`（不再死黑）
- 沟槽三圈 `stroke #2E2E2E / 0.7`，半径 27 / 22.5 / 18
- 高光弧 `stroke #1AFFFFFF / 1.0`，210°→330°（首版 15%/1.4 像一道月牙，收敛）
- 中心标签 **米白 `#F3EEE4`**（真实黑胶纸质标签色），下垫 `#33000000` 制造浮起
- 标签上品牌红**整圈细环** `stroke #E54545 / 0.8`（首版是上半弧，48dp 读成一道眉毛）
- 主轴孔 `#121212 / r2.3`；主体半径 32（直径 64 < 自适应图标 66dp 安全区）
- 红只出现在标签环上，与红底形成「背景红 / 盘身黑 / 标签米白」三层，缩小不糊

**app 名**：`strings.xml` 的 `app_name` 由 `CloudMusic` 改为 **云音乐**，并在
`<application>` 挂 `android:label="@string/app_name"` —— 此前根本没有 label，
真机桌面显示的是包名 `com.ccmsc.cloudmusic`（截图 43-real-icon2.png 实证）。
aapt2 复核：`application: label='云音乐'`。

**启动页红统一**：`LegacyPalette.BrandRed` / `SplashRedBottom` 由竞品采样值
`#A22016` / `#AE2318` 改为品牌红 `#E54545`（与 `FnComp.IconRed` 同值）。原值是
2026-11-09 对竞品启动屏的六点采样，照搬基准；但自家图标是 `#E54545`，两个红并排
像两个应用。`tools/l3_visual_replica_spec.py` 的 BrandRed 期望值同步（14 项色值对照仍全对）。

**判据**：新增 §61.359（图标三层色在位 / Manifest 挂 label / app_name=云音乐 /
两个红常量=0xFFE54545）；§61.351 的「红标」形态判据从 `fillColor` 放开到「品牌红出现」
（新设计用 stroke，强度不变）。guard 306→307；A/B 3 条（标签回红、去 label、红回旧值）
全部 FAIL 后逐字节恢复。

**遗留登记**：`tools/l3_visual_replica_spec.py` 不在闸门链内，本次复跑暴露两处**既有漂移**
（非本轮引入）：`module_count 19→21`、`feiniu_pages_follow_baseline=False`（飞牛化后不再跟
竞品基准）、`res_violations` 把自研图标三件套算成竞品素材。如实挂账，待专项收口。
''')

# docs/09
rw('docs/09-文档闭环总览.md', lambda s: s + ('> **§61.359（2026-11-25）G-1 重设计**：旧图标「红标签+红底」= 中间一个红洞（用户「丑死了」）'
    '⇒ 新设计黑盘 + 米白 `#F3EEE4` 标签 + 品牌红整圈细环 + 三圈沟槽 + 弱高光，红只作标签环。'
    'app 名定为「云音乐」并在 Manifest 挂 `android:label`（此前桌面显示包名）。启动页红由竞品采样 '
    '`#A22016`/`#AE2318` 统一到品牌红 `#E54545`。guard 307、A/B 3 条；l3 视觉审计的两处既有漂移如实挂账。\n'))

# docs/17
rw('docs/17-竞品模块清单与复刻规划.md', lambda s: s + ('''
### 8.252 G-1 图标重设计 / 云音乐 / 启动页红统一（§61.359，2026-11-25）

- 旧版问题：红标签与红底同色 ⇒ 红洞；盘面单层死黑，48dp 无唱片感。
- 新版：黑盘 `#121212`+内层 `#1A1A1A`、三圈 `#2E2E2E` 沟槽、`#1AFFFFFF` 高光弧、
  米白 `#F3EEE4` 标签、`#E54545` 标签整圈细环、`#121212` 主轴孔；主体 r=32（安全区内）。
- app 名「云音乐」+ Manifest `android:label`（此前桌面显示包名）。
- 启动页 `BrandRed`/`SplashRedBottom` 由 `#A22016`/`#AE2318` 统一到 `#E54545`。
- guard §61.359 新增、§61.351 形态放开（强度不变），307 PASS；A/B 3 条。
'''))

# 总账 G-1 行
def ledger(s):
    old = '| ~~G-1~~ | ✅ | **已完成（§61.351）**：用户拍板黑胶唱片 + 强调色 `#E54545`。自适应图标三件套（前景绳槽+红标 / 背景强调色 / anydpi-v26 接前后景）+ Manifest 挂 icon/roundIcon；minSdk 26 无 PNG 兜底 | 一手：Manifest 原无 icon | 已验收：guard §61.351 + A/B（篡改 `<color>` 完整形态 → FAIL）；**桌面外观真机未验**，挂 E 组 |'
    new = ('| ~~G-1~~ | ✅ | **已完成（§61.351 → §61.359 重设计）**：用户「丑死了」二次拍板 ⇒ 旧版「红标签+红底=红洞」改为'
           '黑盘 + 米白 `#F3EEE4` 标签 + 品牌红整圈细环 + 三圈沟槽 + 弱高光；app 名「云音乐」并在 Manifest 挂 `android:label`'
           '（此前桌面显示包名 `com.ccmsc.cloudmusic`）；启动页红由竞品采样 `#A22016`/`#AE2318` 统一到 `#E54545` '
           '| 真机 10.0.0.5:42963 桌面截图 + aapt2 `label=\'云音乐\'` | 已验收：guard §61.359 新增 + §61.351 形态放开（强度不变），'
           '307 PASS；A/B 3 条（标签回红 / 去 label / 红回旧值）全 FAIL 后恢复；**新图标桌面观感与启动页红待解锁后补验** |')
    assert s.count(old) == 1, ('g1 anchor', s.count(old))
    return s.replace(old, new)
rw('analysis/pending-register-2026-11-24.md', ledger)

# 报告
rw('analysis/e2e-2026-11-25-report.md', lambda s: s + '''

## §61.359 G-1 重设计（2026-11-25 14:10~14:30）

用户一手：「图标重新设计一下，丑死了」；同时拍板 app 名 = **云音乐**、启动页红「**统一**」。

**旧图标实测问题**（真机截图 43-real-icon2.png）：黑盘 + 红标签 + 红底 —— 标签与背景同色，
远看是一个红洞；盘面单层死黑，无沟槽无高光。

**新设计**（渲染预览 46-icon-v2-sheet.png，512/96/64/48 四档）：
- 盘身 `#121212` + 内层 `#1A1A1A`；沟槽三圈 `#2E2E2E / 0.7`（r=27 / 22.5 / 18）
- 高光弧 `#1AFFFFFF / 1.0`（210°→330°）；首版 15%/1.4 像月牙，已收敛
- 中心标签米白 `#F3EEE4`（+`#33000000` 投影浮起）；品牌红整圈细环 `#E54545 / 0.8`
- 主轴孔 `#121212` r=2.3；主体 r=32（直径 64 < 66dp 安全区）

**命名**：`app_name` = 云音乐；`<application android:label="@string/app_name">`。
aapt2 复核 `application: label='云音乐'`。此前无 label ⇒ 桌面显示包名（真机实证）。

**启动页**：`BrandRed` / `SplashRedBottom` `#A22016`/`#AE2318` → `#E54545`；
l3 spec 期望值同步，14 项色值对照仍全对。

**判据**：§61.359 新增，§61.351 形态放开（强度不变），guard 306→307；A/B 3 条全 FAIL 后恢复。

**未完成**：设备在 14:20 后进入锁屏，桌面图标观感与启动页红**真机截图待补**（用户解锁后）。
''')
print('documents synced')

# memory
m = open('.workbuddy/memory/2026-11-25.md', encoding='utf-8').read()
m += '''

## G-1 图标重设计 / 云音乐 / 启动页红统一（§61.359，14:10~14:35）

- 用户拍板：app 名 **B=云音乐**、启动页红**统一**、图标「丑死了」重设计。
- 旧图标病根：红标签(#E54545)+红底同色 ⇒ 远看一个红洞；盘面单层死黑无质感。
- 新设计：黑盘 #121212 + 内层 #1A1A1A、三圈沟槽 #2E2E2E/0.7、高光弧 #1AFFFFFF/1.0(210°→330°)、
  米白标签 #F3EEE4（+投影 #33000000）、品牌红整圈细环 #E54545/0.8、主轴孔 r2.3；主体 r=32。
  **教训**：① 红标签放红底上必须有第三色隔开；② 高光 15%/1.4 在缩略图里是「白月牙」，
  收到 10%/1.0 才像反光；③ 标签红「上半弧」在 48dp 读成眉毛，整圈细环更耐缩小。
- app 名：strings app_name 云音乐 + Manifest 加 android:label（此前**根本没有 label**，
  桌面显示包名 com.ccmsc.cloudmusic —— 真机截图才发现，aapt2 badging 也显示 label=''）。
- 启动页红：LegacyAssets BrandRed/SplashRedBottom 由竞品采样 #A22016/#AE2318 → #E54545，
  同步 tools/l3_visual_replica_spec.py 期望值（14 项色值对照仍全对）。
- guard 306→307（§61.359 新增；§61.351 的「红标」判据从 fillColor 放开到「品牌红出现」，
  因新设计用 stroke，强度不变）；A/B 3 条全 FAIL 后恢复。
- **l3_visual_replica_spec 既有漂移（非本轮引入）**：module_count 19→21、
  feiniu_pages_follow_baseline=False（飞牛化后不跟竞品基准）、res_violations 把自研图标三件套
  当竞品素材。该工具不在 C1~C6 闸门与 guard 链内，如实挂账待专项。
- **真机未验**：14:20 后设备锁屏（指纹），桌面图标观感 + 启动页红截图待用户解锁后补。
'''
open('.workbuddy/memory/2026-11-25.md', 'w', encoding='utf-8', newline='').write(m)
print('memory synced')
