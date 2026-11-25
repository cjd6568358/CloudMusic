# -*- coding: utf-8 -*-
"""§61.359: 图标重设计 / app 名 / 启动页红统一 —— guard 判据 + A/B。"""
import subprocess, shutil, sys

GUARD = 'tools/check_source_contracts.py'
FILES = {
    'manifest': 'app/src/main/AndroidManifest.xml',
    'strings': 'app/src/main/res/values/strings.xml',
    'icon': 'app/src/main/res/drawable/ic_launcher_foreground.xml',
    'assets': 'app/src/main/kotlin/com/example/cloudmusic/ui/player/LegacyAssets.kt',
}
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.359（2026-11-25 G-1 重设计：图标 / app 名 / 启动页红统一）----
    # 用户一手：「图标重新设计一下，丑死了」+ 拍板 app 名「云音乐」+ 启动页红「统一」。
    # 旧图标（§61.351）黑盘+红标签+红底：红标签与红底同色，远看是一个红洞，没有唱片感。
    # 新版改为米白纸质标签 #F3EEE4 + 标签印刷红环 #E54545 + 沟槽/高光，红仍在但克制。
    # 判据四项：图标三层色在位 / app 名非包名 / Manifest 挂 label / 启动页红=品牌红。
    _c359 = []
    _ico359 = (ROOT / "app" / "src" / "main" / "res" / "drawable"
               / "ic_launcher_foreground.xml").read_text(encoding="utf-8", errors="replace")
    if "#F3EEE4" not in _ico359:
        _c359.append("图标缺米白标签层（会退回「红洞」观感）")
    if "#E54545" not in _ico359:
        _c359.append("图标缺品牌红印刷环")
    if "#1AFFFFFF" not in _ico359:
        _c359.append("图标缺沟槽高光（盘面会一片死黑）")
    _man359 = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8", errors="replace")
    if 'android:label="@string/app_name"' not in _man359:
        _c359.append("Manifest 未挂 android:label（桌面显示包名）")
    _str359 = (ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml").read_text(encoding="utf-8", errors="replace")
    if ">云音乐<" not in _str359:
        _c359.append("app_name 不是「云音乐」")
    _pal359 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
               / "ui" / "player" / "LegacyAssets.kt").read_text(encoding="utf-8", errors="replace")
    if "val BrandRed = 0xFFE54545.toInt()" not in _pal359:
        _c359.append("启动页红未统一到 #E54545")
    if "val SplashRedBottom = 0xFFE54545.toInt()" not in _pal359:
        _c359.append("启动页渐变底未统一到 #E54545")
    if _c359:
        failures.append("§61.359 图标/命名/启动页红：%s" % "；".join(_c359))
    else:
        print("[PASS] §61.359 图标重设计（米白标签+红环+沟槽高光）+ 云音乐 + 启动页红统一")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard §61.359 inserted')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after insert -> FAILs:', fails[:3] or 'none')

# A/B 1: 图标退回旧版（无米白，红标签）
for k, v in FILES.items():
    shutil.copy(v, BAK + k + '.ab359')
src = open(FILES['icon'], encoding='utf-8').read()
open(FILES['icon'], 'w', encoding='utf-8', newline='').write(
    src.replace('#F3EEE4', '#E54545'))   # 标签退回红 = 旧观感
out, fails = run_guard()
print('A/B icon-label-red ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'icon.ab359', FILES['icon'])

# A/B 2: 去掉 label
src = open(FILES['manifest'], encoding='utf-8').read()
open(FILES['manifest'], 'w', encoding='utf-8', newline='').write(
    src.replace('        android:label="@string/app_name"\n', ''))
out, fails = run_guard()
print('A/B no-label ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'manifest.ab359', FILES['manifest'])

# A/B 3: 启动页红退回竞品采样
src = open(FILES['assets'], encoding='utf-8').read()
open(FILES['assets'], 'w', encoding='utf-8', newline='').write(
    src.replace('val BrandRed = 0xFFE54545.toInt()', 'val BrandRed = 0xFFA22016.toInt()'))
out, fails = run_guard()
print('A/B old-red ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'assets.ab359', FILES['assets'])

out, fails = run_guard()
print('restored ->', fails[:2] or 'clean')
