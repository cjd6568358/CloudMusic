# -*- coding: utf-8 -*-
"""§61.360: 启动页状态栏/导航栏刷品牌红 —— guard 判据 + A/B。"""
import subprocess, shutil, sys

GUARD = 'tools/check_source_contracts.py'
SPLASH = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SplashScreen.kt'
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.360（2026-11-25 启动页状态栏/导航栏刷品牌红）----
    # 真机 52-small.png 实证：启动页红屏上下各压一条黑边（themes.xml 写死 black，
    # 而 SystemBars.apply 只在 splash 结束后的 CloudMusicApp 分支里跑）。
    # 修法：SplashScreen 显示期间把两栏刷成 BrandRed + 浅色图标，不做还原配对
    # （退出后由 SystemBars.apply 按主题重算）。判据钉四件事，缺一即红。
    _sp360 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "ui" / "SplashScreen.kt").read_text(encoding="utf-8", errors="replace")
    _c360 = []
    if "window.statusBarColor = red" not in _sp360:
        _c360.append("启动页未刷状态栏（红屏会压黑边）")
    if "window.navigationBarColor = red" not in _sp360:
        _c360.append("启动页未刷导航栏")
    if "isAppearanceLightStatusBars = false" not in _sp360 or "isAppearanceLightNavigationBars = false" not in _sp360:
        _c360.append("红底未配浅色图标（图标会看不见）")
    if "fun Context.findActivity()" not in _sp360:
        _c360.append("缺 Activity 解包（ContextWrapper 下取不到 window）")
    if "LegacyPalette.BrandRed" not in _sp360:
        _c360.append("栏色未取品牌红单一真相源")
    if _c360:
        failures.append("§61.360 启动页系统栏：%s" % "；".join(_c360))
    else:
        print("[PASS] §61.360 启动页系统栏：状态栏/导航栏刷品牌红 + 浅色图标")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard §61.360 inserted')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after insert ->', fails[:3] or 'clean')

# A/B: 去掉状态栏设置 -> 必须 FAIL
shutil.copy(SPLASH, BAK + 'SplashScreen.kt.ab360')
src = open(SPLASH, encoding='utf-8').read()
open(SPLASH, 'w', encoding='utf-8', newline='').write(
    src.replace('            window.statusBarColor = red\n', ''))
out, fails = run_guard()
print('A/B no-statusbar ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'SplashScreen.kt.ab360', SPLASH)

# A/B: 去掉白图标设置 -> 必须 FAIL
src = open(SPLASH, encoding='utf-8').read()
open(SPLASH, 'w', encoding='utf-8', newline='').write(
    src.replace('            controller.isAppearanceLightStatusBars = false\n', ''))
out, fails = run_guard()
print('A/B dark-icons ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'SplashScreen.kt.ab360', SPLASH)

out, fails = run_guard()
print('restored ->', fails[:2] or 'clean')
