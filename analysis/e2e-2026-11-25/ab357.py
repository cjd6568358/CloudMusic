# -*- coding: utf-8 -*-
"""§61.357 A/B 反证：逐条变异 -> guard 必须 FAIL -> 逐字节恢复 -> PASS。"""
import subprocess, shutil, sys

APP = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/CloudMusicApp.kt'
SEARCH = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SearchScreen.kt'
BAK = 'analysis/e2e-2026-11-25/ab/'
shutil.copy(APP, BAK + 'CloudMusicApp.kt.ab357')
shutil.copy(SEARCH, BAK + 'SearchScreen.kt.ab357')

def run_guard():
    r = subprocess.run([sys.executable, 'tools/check_source_contracts.py'],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    hits = [l for l in out.splitlines() if l.startswith('[FAIL]') or '§61.357' in l and 'FAIL' in l]
    return out, hits

def mutate(path, old, new):
    s = open(path, encoding='utf-8').read()
    assert s.count(old) == 1, ('anchor', path, s.count(old))
    open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new))

cases = []

# A: 去掉键盘可见性判断（底栏无条件渲染）-> 推高/被切回潮
mutate(APP, 'if (!WindowInsets.isImeVisible) {', 'if (true) {')
out, hits = run_guard()
cases.append(('A 去掉 isImeVisible 让位', any('§61.357' in h or '§61.325' in h or '§61.262' in h for h in hits), hits[:2]))
shutil.copy(BAK + 'CloudMusicApp.kt.ab357', APP)

# B: 底栏推高回潮
mutate(APP, 'if (!WindowInsets.isImeVisible) {',
       'Column(Modifier.windowInsetsPadding(WindowInsets.ime)) { if (!WindowInsets.isImeVisible) {')
out, hits = run_guard()
cases.append(('B 底栏推高回潮', any('§61.357' in h or '§61.325' in h or '§61.262' in h for h in hits), hits[:2]))
shutil.copy(BAK + 'CloudMusicApp.kt.ab357', APP)

# C: 去掉 consumeWindowInsets（导航栏双计 = 黑带）
mutate(APP, '.consumeWindowInsets(padding)', '.padding(0.dp)')
out, hits = run_guard()
cases.append(('C 去掉 consumeWindowInsets', any('§61.357' in h for h in hits), hits[:2]))
shutil.copy(BAK + 'CloudMusicApp.kt.ab357', APP)

# D: 搜索页重复让位
mutate(SEARCH, 'Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {',
       'Column(Modifier.fillMaxSize().background(FnComp.PageBg).imePadding()) {')
out, hits = run_guard()
cases.append(('D 搜索页重复 imePadding', any('§61.357' in h or '§61.325' in h or '§61.262' in h for h in hits), hits[:2]))
shutil.copy(BAK + 'SearchScreen.kt.ab357', SEARCH)

print('=' * 60)
ok = True
for name, failed, hits in cases:
    print(('PASS(A/B有效) ' if failed else 'INVALID(A/B无效) ') + name)
    for h in hits:
        print('    ' + h[:160])
    ok = ok and failed

# 恢复后必须全 PASS
out, hits = run_guard()
print('--- RESTORED ---')
print('restored clean:', 'FAIL' not in out, '| §61.357 line:', [l for l in out.splitlines() if '§61.357' in l])
print('ALL_AB_VALID' if ok and 'FAIL' not in out else 'AB_OR_RESTORE_PROBLEM')
