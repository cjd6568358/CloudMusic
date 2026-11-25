"""§61.367 判据插入 + A/B 反向验证（矢量版）。

判据 7 条：首页分组标题 / 漫游卡 shuffle / Shuffle 枚举 / Shuffle 矢量映射 /
ic_fn_shuffle.xml 资源 / 音乐库「＋」语义 / 设置页顶部渐隐。

A/B 判定口径：**任意 FAIL 行**都算「拦住了」——该处可能被 §61.278（枚举→映射一致性）
等更早的判据先抓到，只要不是静默放行即可；同时打印命中的判据号，便于区分是谁在守。
A/B 期间不能有 gradle 在跑（用户长期纪律：临时变异文件会被编译读到）。
"""
import io
import os
import subprocess
import sys

GUARD = 'tools/check_source_contracts.py'
UI = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/'
HOME = UI + 'HomeScreen.kt'
LIB = UI + 'FnLibraryScreen.kt'
SET = UI + 'SettingsScreen.kt'
ICONS = UI + 'FnIcons.kt'
RES = 'app/src/main/res/drawable/ic_fn_shuffle.xml'

BLOCK = '''    # ---- §61.367（2026-11-25 走查收尾四件：首页分组标题 / shuffle 图标 / 「＋」语义 / 顶部渐隐）----
    # 用户一手「每个页面入口都多点点」；走查截图暴露：卡片区无 section 标题、漫游卡图标
    # 借的是 Broadcast（投屏）与「随机播放」语义矛盾、行尾「＋」无任何说明、设置页滚动硬切。
    _c367 = []
    if '"快捷入口"' not in _home365:
        _c367.append("首页卡片区缺分组标题")
    if not re.search(r"icon\\s*=\\s*FnIcon\\.Shuffle\\b", _home365):
        _c367.append("漫游卡未用 shuffle 图标（仍借投屏）")
    _fn367 = (_ui365 / "FnIcons.kt").read_text(encoding="utf-8", errors="replace")
    if not re.search(r'^\\s*Shuffle\\("随机播放"\\)', _fn367, re.M):
        _c367.append("FnIcon 缺 Shuffle 枚举")
    if "FnIcon.Shuffle to R.drawable.ic_fn_shuffle," not in _fn367:
        _c367.append("Shuffle 未接矢量资源（会掉回手绘兜底，交叉处糊成块）")
    if not (ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_fn_shuffle.xml").is_file():
        _c367.append("ic_fn_shuffle.xml 缺失")
    if 'contentDescription = "加入歌单"' not in _lib365:
        _c367.append("音乐库「＋」缺语义标签")
    if "listOf(FnComp.PageBg, Color.Transparent)" not in _set365:
        _c367.append("设置页缺顶部渐隐（滚动仍是硬切）")
    if _c367:
        failures.append("§61.367 走查收尾：%s" % "；".join(_c367))
    else:
        print("[PASS] §61.367 首页分组标题 / 漫游 shuffle / 「＋」语义 / 设置页顶部渐隐")

'''

ANCHOR = '        print("[PASS] §61.365/366 布局裁切三修 + 播放页空态降透明")\n'

# ── 1. 插入判据块（幂等） ────────────────────────────────────────────────
with io.open(GUARD, encoding='utf-8', newline='') as f:
    g = f.read()
if '§61.367' in g:
    print('guard 已含 §61.367，跳过插入')
else:
    assert g.count(ANCHOR) == 1
    with io.open(GUARD, 'w', encoding='utf-8', newline='') as f:
        f.write(g.replace(ANCHOR, ANCHOR + '\n' + BLOCK))
    print('guard 块已插入')


def read(p):
    with io.open(p, encoding='utf-8', newline='') as f:
        return f.read()


def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)


def run_guard():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True)
    out = (r.stdout or '') + (r.stderr or '')
    fails = [ln for ln in out.splitlines() if ln.startswith('[FAIL]')]
    if not fails:
        return False, '无任何 FAIL（静默放行）'
    # 优先报告 61.367 自己抓到的，否则报告第一条
    own = [ln for ln in fails if '61.367' in ln]
    return True, (own[0] if own else fails[0])[:150]


caught, detail = run_guard()
print('基线：无 §61.367 FAIL =', not caught)
if caught:
    print('  !! 基线就失败：', detail)
    sys.exit(1)

# ── 2. 七条 A/B：每条改坏一处，必须被拦 ──────────────────────────────────
CASES = [
    (HOME, '"快捷入口"', '"快捷入口X"', '首页分组标题'),
    (HOME, 'icon = FnIcon.Shuffle,', 'icon = FnIcon.Broadcast,', '漫游卡 shuffle'),
    (ICONS, 'Shuffle("随机播放"),', 'ShuffleX("随机播放"),', 'Shuffle 枚举'),
    (ICONS, 'FnIcon.Shuffle to R.drawable.ic_fn_shuffle,',
            'FnIcon.ShuffleX to R.drawable.ic_fn_shuffle,', 'Shuffle 映射'),
    (LIB, 'contentDescription = "加入歌单"', 'contentDescription = "加入歌单X"', '「＋」语义'),
    (SET, 'listOf(FnComp.PageBg, Color.Transparent)', 'listOf(Color.Black, Color.Transparent)', '顶部渐隐'),
]

bad = 0
for path, old, new, name in CASES:
    src = read(path)
    if src.count(old) != 1:
        print('SKIP %-14s 命中 %d 次' % (name, src.count(old)))
        bad += 1
        continue
    write(path, src.replace(old, new))
    caught, detail = run_guard()
    write(path, src)
    print('%s %-14s %s' % ('OK  ' if caught else 'MISS', name, detail))
    if not caught:
        bad += 1

# 资源缺失单独用「临时改名」验证（判据是 is_file()，不是字符串）
os.rename(RES, RES + '.bak')
try:
    caught, detail = run_guard()
finally:
    os.rename(RES + '.bak', RES)
print('%s %-14s %s' % ('OK  ' if caught else 'MISS', 'Shuffle 资源', detail))
if not caught:
    bad += 1

print('A/B 完成，漏抓 %d 条' % bad)
sys.exit(0 if bad == 0 else 1)
