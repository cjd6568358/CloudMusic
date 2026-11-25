# -*- coding: utf-8 -*-
"""§61.361 判据跟随 lineB；新增 §61.362（底栏改 Material 矢量）。"""
import subprocess, shutil, sys

GUARD = 'tools/check_source_contracts.py'
FEI = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FeiniuUi.kt'
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()

# 1) §61.361：line() -> lineB?()（柄改平头后原正则匹配不到）
old = '''        _l361 = re.search(r"line\\(([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f\\)", _b361)'''
new = '''        _l361 = re.search(r"lineB?\\(([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f,\\s*([\\d.]+)f\\)", _b361)'''
assert s.count(old) == 1, ('line regex', s.count(old))
s = s.replace(old, new)

# 2) 新增 §61.362
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.362（2026-11-25 底栏图标改 Material Symbols 官方矢量）----
    # 用户一手：「tab 图标为什么感觉像是拼出来的、有毛刺」⇒ 手写 Canvas 是多次 drawLine
    # 拼接，圆头在接头处硬叠出疙瘩。改用官方单条 fill path（Apache 2.0），零新依赖。
    # 判据：四个 drawable 在位 + 映射函数在位 + 渲染走 painterResource（缺一即红）。
    _c362 = []
    _fei362 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                          / "cloudmusic" / "ui" / "FeiniuUi.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _dw362 = ROOT / "app" / "src" / "main" / "res" / "drawable"
    for _n in ("ic_tab_home", "ic_tab_library", "ic_tab_search", "ic_tab_more"):
        _f = _dw362 / (_n + ".xml")
        if not _f.is_file():
            _c362.append("缺矢量图标 %s.xml" % _n)
        elif "pathData" not in _f.read_text(encoding="utf-8", errors="replace"):
            _c362.append("%s.xml 无 pathData" % _n)
    if "fun capsuleTabDrawable(" not in _fei362:
        _c362.append("缺 tab→矢量映射（会退回手写拼装图标）")
    if "painterResource(tabPainter)" not in _fei362:
        _c362.append("底栏未用矢量渲染（毛刺会复发）")
    if _c362:
        failures.append("§61.362 底栏矢量图标：%s" % "；".join(_c362))
    else:
        print("[PASS] §61.362 底栏矢量图标：四枚 Material Symbols + 映射 + painterResource 渲染")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard patched (61.361 regex + 61.362)')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after patch ->', fails[:4] or 'clean')

# A/B: 底栏渲染退回手写
shutil.copy(FEI, BAK + 'FeiniuUi.kt.ab362')
src = open(FEI, encoding='utf-8').read()
open(FEI, 'w', encoding='utf-8', newline='').write(
    src.replace('painterResource(tabPainter)', 'painterResource(R.drawable.ic_tab_home)')
       .replace('val tabPainter = capsuleTabDrawable(icon)', 'val tabPainter: Int? = null'))
out, fails = run_guard()
print('A/B no-vector-render ->', fails[:1] or 'NONE (无效!)')
shutil.copy(BAK + 'FeiniuUi.kt.ab362', FEI)

out, fails = run_guard()
print('restored ->', fails[:3] or 'clean')
print('pass count:', sum(1 for l in out.splitlines() if l.startswith('[PASS]')))
