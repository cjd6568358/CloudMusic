# -*- coding: utf-8 -*-
"""§61.364：包名 com.ccmsc.cloudmusic -> com.ccmsc.cloudmusic。

范围：
  - 源码目录（*/src/*/kotlin/com/ccmsc/cloudmusic）整体迁移到 com/ccmsc/
  - kt / kts / xml 文本全量替换
  - 跳过：build/、.git/、.gradle/、archive/（归档模块未 include，保留历史）
文档（docs/analysis 的 md）不改：那是历史记录，改了等于篡改账本。
"""
import os
import re
import shutil

OLD = 'com.ccmsc.cloudmusic'
NEW = 'com.ccmsc.cloudmusic'
SKIP_DIRS = {'build', '.git', '.gradle', 'archive', 'node_modules', '.idea', 'ab'}
TEXT_EXT = {'.kt', '.kts', '.xml', '.pro', '.properties'}

moved = []
for root, dirs, files in os.walk('.'):
    parts = set(os.path.normpath(root).split(os.sep))
    if parts & SKIP_DIRS:
        dirs[:] = []
        continue
    # 只处理 .../com/ccmsc/cloudmusic 这一层
    norm = os.path.normpath(root).replace('\\', '/')
    if norm.endswith('/com/ccmsc/cloudmusic'):
        dst = norm[: -len('/com/ccmsc/cloudmusic')] + '/com/ccmsc/cloudmusic'
        if os.path.exists(dst):
            print('SKIP exists', dst)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(norm, dst)
        moved.append((norm, dst))
        # 清理空的 com/example
        parent = os.path.dirname(norm)
        try:
            if not os.listdir(parent):
                os.rmdir(parent)
        except OSError:
            pass
        dirs[:] = []

print('moved dirs:', len(moved))
for a, b in moved[:3]:
    print('  ', a, '->', b)

changed = []
for root, dirs, files in os.walk('.'):
    parts = set(os.path.normpath(root).split(os.sep))
    if parts & SKIP_DIRS:
        dirs[:] = []
        continue
    for fn in files:
        ext = os.path.splitext(fn)[1].lower()
        if ext not in TEXT_EXT:
            continue
        p = os.path.join(root, fn)
        try:
            s = open(p, encoding='utf-8').read()
        except (UnicodeDecodeError, OSError):
            continue
        if OLD not in s:
            continue
        n = s.replace(OLD, NEW)
        open(p, 'w', encoding='utf-8', newline='').write(n)
        changed.append(p)

print('rewritten files:', len(changed))
for p in changed[:6]:
    print('  ', p)
