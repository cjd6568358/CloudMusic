# -*- coding: utf-8 -*-
"""§61.365 三处布局裁切 + §61.366 播放页空态降透明 —— 判据 + A/B。"""
import subprocess, shutil, sys

GUARD = 'tools/check_source_contracts.py'
HOME = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/HomeScreen.kt'
LIB = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnLibraryScreen.kt'
SET = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SettingsScreen.kt'
PLAYER = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/player/LegacyPlayerPage.kt'
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.365（2026-11-25 三处布局裁切）/ §61.366（播放页空态降透明）----
    # 用户一手「大量问题没解决」；走查（am start 直达 + 包名校验）发现：
    # 首页第三张卡被右缘硬切、音乐库第五个 chip 被硬切、设置页最后一项贴胶囊。
    _ui365 = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "ccmsc" / "cloudmusic" / "ui"
    _home365 = (_ui365 / "HomeScreen.kt").read_text(encoding="utf-8", errors="replace")
    _lib365 = (_ui365 / "FnLibraryScreen.kt").read_text(encoding="utf-8", errors="replace")
    _set365 = (_ui365 / "SettingsScreen.kt").read_text(encoding="utf-8", errors="replace")
    _pl365 = (_ui365 / "player" / "LegacyPlayerPage.kt").read_text(encoding="utf-8", errors="replace")
    _c365 = []
    if "horizontalGradient" not in _home365 or "matchParentSize()" not in _home365:
        _c365.append("首页卡片缺右缘渐隐（第三张会硬切）")
    if "FlowRow(" not in _lib365:
        _c365.append("音乐库分类 chip 未换行（第五个会被切）")
    if "Spacer(Modifier.height(44.dp))" not in _set365:
        _c365.append("设置页尾部留白不足（最后一项会贴胶囊）")
    if "enabled = track != null" not in _pl365:
        _c365.append("播放页进度条未按空态降透明")
    if "alpha(if (enabled) 1f else 0.35f)" not in _pl365:
        _c365.append("播放页进度条缺空态透明度")
    if _c365:
        failures.append("§61.365/366 布局与空态：%s" % "；".join(_c365))
    else:
        print("[PASS] §61.365/366 布局裁切三修 + 播放页空态降透明")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard 365/366 inserted')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after insert ->', fails[:3] or 'clean')

# A/B 1: 去掉首页渐隐
shutil.copy(HOME, BAK + 'HomeScreen.kt.ab365')
src = open(HOME, encoding='utf-8').read()
open(HOME, 'w', encoding='utf-8', newline='').write(src.replace('horizontalGradient', 'verticalGradient'))
out, fails = run_guard()
print('A/B no-fade ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'HomeScreen.kt.ab365', HOME)

# A/B 2: chips 回 Row+scroll
shutil.copy(LIB, BAK + 'FnLibraryScreen.kt.ab365')
src = open(LIB, encoding='utf-8').read()
open(LIB, 'w', encoding='utf-8', newline='').write(src.replace('FlowRow(', 'Row('))
out, fails = run_guard()
print('A/B chips-row ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'FnLibraryScreen.kt.ab365', LIB)

# A/B 3: 进度条回全亮
shutil.copy(PLAYER, BAK + 'LegacyPlayerPage.kt.ab366')
src = open(PLAYER, encoding='utf-8').read()
open(PLAYER, 'w', encoding='utf-8', newline='').write(
    src.replace('.alpha(if (enabled) 1f else 0.35f)', ''))
out, fails = run_guard()
print('A/B progress-full ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'LegacyPlayerPage.kt.ab366', PLAYER)

out, fails = run_guard()
print('restored ->', fails[:2] or 'clean')
print('pass count:', sum(1 for l in out.splitlines() if l.startswith('[PASS]')))
