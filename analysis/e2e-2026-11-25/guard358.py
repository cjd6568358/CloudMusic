# -*- coding: utf-8 -*-
"""§61.358: 胶囊内层 shadow 判据 + A/B。"""
import subprocess, shutil, sys

GUARD = 'tools/check_source_contracts.py'
FEI = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FeiniuUi.kt'
BAK = 'analysis/e2e-2026-11-25/ab/'

s = open(GUARD, encoding='utf-8').read()
anchor = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block = '''    # ---- §61.358（2026-11-25 用户一手：tab 条「白色透明线」= 胶囊内层 shadow）----
    # 用户截图（1440x3200）逐像素：胶囊顶边 (43,43,45) 单调衰减到中部 (33,33,36)，而 4 个
    # 52dp tab 内容区保持 (42) ⇒ 中心高度浮出一条 +8 的亮带 = 用户说的「白色透明线」。
    # 根因是 `clip → background → shadow` 的修饰符顺序：shadow 在 background 之后（内层），
    # Compose 为内容建 layer 并把 elevation 阴影画进胶囊内部。深色底上 8dp 黑影本就不可见，
    # 删掉零视觉损失。判据：FnCapsuleBar 体内不得出现 .shadow(（反向）+ 盒模型四件套在位（正向）。
    _c358 = []
    _fei358 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                          / "cloudmusic" / "ui" / "FeiniuUi.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _i358 = _fei358.find("fun FnCapsuleBar(")
    if _i358 == -1:
        _c358.append("FnCapsuleBar 缺失")
    else:
        _j358 = _fei358.find("\\nfun ", _i358 + 10)
        _body358 = _fei358[_i358:_j358 if _j358 != -1 else len(_fei358)]
        if ".shadow(" in _body358:
            _c358.append("胶囊栏内层 shadow 回潮（内部会再浮出 +8 亮带 = 白色透明线）")
        if ".background(FnComp.CapsuleBg)" not in _body358:
            _c358.append("胶囊底色缺失（底栏会变透明）")
        if ".clip(RoundedCornerShape(28.dp))" not in _body358:
            _c358.append("胶囊圆角裁剪缺失")
        if ".padding(vertical = 6.dp, horizontal = 10.dp)" not in _body358:
            _c358.append("胶囊内边距被改（触控盒模型会变）")
    if _c358:
        failures.append("§61.358 胶囊白色透明线：%s" % "；".join(_c358))
    else:
        print("[PASS] §61.358 胶囊白色透明线：无内层 shadow（盒模型四件套在位）")

'''
assert s.count(anchor) == 1
s = s.replace(anchor, block + anchor)
open(GUARD, 'w', encoding='utf-8', newline='').write(s)
print('guard §61.358 inserted')

def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    return out, [l for l in out.splitlines() if l.startswith('[FAIL]')]

out, fails = run_guard()
print('after insert -> FAILs:', fails[:3] or 'none')

# A/B: 注回 shadow -> 必须 FAIL
shutil.copy(FEI, BAK + 'FeiniuUi.kt.ab358')
src = open(FEI, encoding='utf-8').read()
old = '''                .background(FnComp.CapsuleBg)
                .padding(vertical = 6.dp, horizontal = 10.dp),'''
new = '''                .background(FnComp.CapsuleBg)
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .padding(vertical = 6.dp, horizontal = 10.dp),'''
assert src.count(old) == 1
open(FEI, 'w', encoding='utf-8', newline='').write(src.replace(old, new))
out, fails = run_guard()
print('A/B (shadow 注回) -> FAILs:', fails[:2] or 'NONE (判据无效!)')
shutil.copy(BAK + 'FeiniuUi.kt.ab358', FEI)
out, fails = run_guard()
print('restored ->', 'clean' if not fails else fails[:2])
