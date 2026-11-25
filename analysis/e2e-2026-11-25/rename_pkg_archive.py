# -*- coding: utf-8 -*-
"""把 archive/ 下的 com/example/cloudmusic 也迁到 com/ccmsc/cloudmusic（保持全局一致）。"""
import os
import shutil

OLD, NEW = 'com.example.cloudmusic', 'com.ccmsc.cloudmusic'
TEXT_EXT = {'.kt', '.kts', '.xml', '.pro', '.properties', '.java'}
SKIP = {'build', '.git', '.gradle', '.idea', 'node_modules'}
SEP = os.sep

moved = 0
for root, dirs, files in os.walk('./archive'):
    parts = set(os.path.normpath(root).split(SEP))
    if parts & SKIP:
        dirs[:] = []
        continue
    norm = os.path.normpath(root)
    tail = SEP + 'com' + SEP + 'example' + SEP + 'cloudmusic'
    if norm.endswith(tail):
        base = norm[: -len(tail)]
        dst = os.path.join(base, 'com', 'ccmsc', 'cloudmusic')
        if not os.path.exists(dst):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.move(norm, dst)
            moved += 1
            print('  moved', norm, '->', dst)
        dirs[:] = []
print('archive dirs moved:', moved)

changed = 0
for root, dirs, files in os.walk('./archive'):
    parts = set(os.path.normpath(root).split(SEP))
    if parts & SKIP:
        dirs[:] = []
        continue
    for fn in files:
        if os.path.splitext(fn)[1].lower() not in TEXT_EXT:
            continue
        p = os.path.join(root, fn)
        try:
            s = open(p, encoding='utf-8').read()
        except Exception:
            continue
        if OLD not in s:
            continue
        open(p, 'w', encoding='utf-8', newline='').write(s.replace(OLD, NEW))
        changed += 1
print('archive files rewritten:', changed)
