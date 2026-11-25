"""§61.369/370 判据 A/B：改坏一处必须被拦。

不插判据（已插），只做反向验证。判定口径同 guard367：任意 FAIL 即算拦住。
"""
import io
import subprocess
import sys

GUARD = 'tools/check_source_contracts.py'
UI = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/'
FE = UI + 'FeiniuUi.kt'
PL = UI + 'PlaylistsScreen.kt'
MM = UI + 'MyMusicScreen.kt'
ML = UI + 'MyMusicLayout.kt'
TEST = 'app/src/test/kotlin/com/ccmsc/cloudmusic/ui/MyMusicLayoutTest.kt'


def read(p):
    with io.open(p, encoding='utf-8', newline='') as f:
        return f.read()


def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)


def run():
    r = subprocess.run([sys.executable, GUARD], capture_output=True, text=True)
    out = (r.stdout or '') + (r.stderr or '')
    fails = [ln for ln in out.splitlines() if ln.startswith('[FAIL]')]
    if not fails:
        return False, '无任何 FAIL（静默放行）'
    own = [ln for ln in fails if '61.36' in ln and '370' in ln or '61.369' in ln]
    return True, (own[0] if own else fails[0])[:150]


caught, detail = run()
print('基线干净 =', not caught)
if caught:
    print('  !!', detail)
    sys.exit(1)

CASES = [
    (FE, '    icon: FnIcon? = null,', '    iconPlaceholder: FnIcon? = null,', 'FnCover 矢量占位'),
    (PL, 'placeholderIcon = FnIcon.Playlist', 'placeholderIcon = FnIcon.Search', '占位图标类型', 'all'),
    (PL, '暂无可播放曲目', '点击播放', '0 首文案', 'all'),
    (MM, '"mm-hist-empty"', '"mm-hist-x"', '最近段空态'),
    (ML, 'if (favoriteCount > 0) 2 + favoriteCount else 2',
         'if (favoriteCount > 0) 2 + favoriteCount else 0', '序号算法'),
    (TEST, 'assertEquals(3, MyMusicLayout.historyHeaderIndex(favoriteCount = 0',
           'assertEquals(1, MyMusicLayout.historyHeaderIndex(favoriteCount = 0', '序号单测'),
]

bad = 0
for case in CASES:
    path, old, new, name = case[:4]
    allow_all = len(case) > 4 and case[4] == 'all'
    src = read(path)
    n = src.count(old)
    if n == 0 or (n != 1 and not allow_all):
        print('SKIP %-14s 命中 %d 次' % (name, n))
        bad += 1
        continue
    write(path, src.replace(old, new))
    caught, detail = run()
    write(path, src)
    print('%s %-14s %s' % ('OK  ' if caught else 'MISS', name, detail))
    if not caught:
        bad += 1

# 合并空态回潮：插回一块 mm-empty
src = read(MM)
anchor = '            item(key = "mm-fav-title")'
assert src.count(anchor) == 1
write(MM, src.replace(anchor, '            item(key = "mm-empty") { FnEmpty("x") }\n' + anchor))
caught, detail = run()
write(MM, src)
print('%s %-14s %s' % ('OK  ' if caught else 'MISS', '合并空态回潮', detail))
if not caught:
    bad += 1

print('A/B 完成，漏抓 %d 条' % bad)
sys.exit(0 if bad == 0 else 1)
