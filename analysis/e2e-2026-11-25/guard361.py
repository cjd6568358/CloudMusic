# -*- coding: utf-8 -*-
"""§61.361: tab 图标几何对齐 + APP 唱片缩小 —— guard 判据（几何阈值，不钉字面坐标）+ A/B。"""
import re
import subprocess
import shutil
import sys

GUARD = 'tools/check_source_contracts.py'
ICONS = 'app/src/main/kotlin/com/example/cloudmusic/ui/FnIcons.kt'
LAUNCH = 'app/src/main/res/drawable/ic_launcher_foreground.xml'
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.361（2026-11-25 tab 图标对齐 + APP 唱片缩小）----
    # 用户一手：「tab 图标奇奇怪怪的」「APP 应用图标 唱片再小一点」。
    # 真机 182px Box 实测：Home 77×63 / Library 73×65 / Search **62×57** / More 75×19。
    # 放大镜是唯一明显缩水的；三点 19px 高是 more_horiz 的语形特征（首轮加重到 r=2.9
    # 反而变笨重，已回退）。判据用**几何阈值**而非字面坐标（改小数绕不过）：
    #   Search 圆半径 ≥ 7.0、柄终点 ≥ 20.5（原 6.0 / 20.0）
    #   APP 唱片半径 26~29（原 32 顶满安全区）
    _c361 = []
    _ic361 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "ui" / "FnIcons.kt").read_text(encoding="utf-8", errors="replace")
    _m361 = re.search(r"FnIcon\\.Search -> \\{(.*?)\\n            \\}", _ic361, re.S)
    if not _m361:
        _c361.append("FnIcon.Search 分支缺失")
    else:
        _b361 = _m361.group(1)
        _r361 = re.search(r"ring\\(([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f\\)", _b361)
        _l361 = re.search(r"line\\(([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f\\)", _b361)
        if not _r361 or float(_r361.group(3)) < 7.0:
            _c361.append("放大镜圆半径 < 7.0（会比同排图标小一圈）")
        if not _l361 or float(_l361.group(3)) < 20.5:
            _c361.append("放大镜柄终点 < 20.5（字形未铺满 24 视框）")
    _lc361 = (ROOT / "app" / "src" / "main" / "res" / "drawable"
              / "ic_launcher_foreground.xml").read_text(encoding="utf-8", errors="replace")
    _d361 = re.search(r"M54,([\\d.]+) A([\\d.]+),\\2 0 1,0 54,([\\d.]+)", _lc361)
    if not _d361:
        _c361.append("唱片盘身路径缺失")
    else:
        _rdisk = float(_d361.group(2))
        if not (26.0 <= _rdisk <= 29.0):
            _c361.append("唱片半径 %.1f 不在 26~29（用户要求「再小一点」，原 32 顶满）" % _rdisk)
    if _c361:
        failures.append("§61.361 图标几何：%s" % "；".join(_c361))
    else:
        print("[PASS] §61.361 图标几何：放大镜铺满视框（r>=7.0/柄>=20.5）+ 唱片半径 26~29")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard §61.361 inserted')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after insert ->', fails[:3] or 'clean')

# A/B 1: search 回退到旧坐标
shutil.copy(ICONS, BAK + 'FnIcons.kt.ab361')
src = open(ICONS, encoding='utf-8').read()
open(ICONS, 'w', encoding='utf-8', newline='').write(
    src.replace('ring(10.8f, 10.8f, 7.2f)', 'ring(11f, 11f, 6f)')
       .replace('line(15.9f, 15.9f, 20.9f, 20.9f)', 'line(15.2f, 15.2f, 20f, 20f)'))
out, fails = run_guard()
print('A/B search-revert ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'FnIcons.kt.ab361', ICONS)

# A/B 2: 唱片回 32
shutil.copy(LAUNCH, BAK + 'ic_launcher_foreground.xml.ab361')
src = open(LAUNCH, encoding='utf-8').read()
open(LAUNCH, 'w', encoding='utf-8', newline='').write(
    src.replace('M54,26 A28,28 0 1,0 54,82 A28,28 0 1,0 54,26 Z',
                'M54,22 A32,32 0 1,0 54,86 A32,32 0 1,0 54,22 Z'))
out, fails = run_guard()
print('A/B disc-32 ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'ic_launcher_foreground.xml.ab361', LAUNCH)

out, fails = run_guard()
print('restored ->', fails[:2] or 'clean')
