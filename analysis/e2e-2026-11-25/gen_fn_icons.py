# -*- coding: utf-8 -*-
"""§61.363：把 FnIcon 全部 32 枚换成 Material Symbols Rounded 官方 SVG 原文。

下载 https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/<name>/default/24px.svg
提取 path，生成 res/drawable/ic_fn_<snake>.xml（960 视框 + group translateY=960）。
"""
import os
import re
import subprocess
import sys

OUT = 'app/src/main/res/drawable'
BASE = 'https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/%s/default/24px.svg'

# FnIcon -> Material Symbols 名称
MAP = {
    'Home': 'home',
    'Library': 'library_music',
    'More': 'more_horiz',
    'Songs': 'music_note',
    'Artists': 'person',
    'Albums': 'album',
    'Sources': 'dns',
    'Unavailable': 'block',
    'Playlist': 'playlist_play',
    'Favorite': 'favorite',
    'Recent': 'history',
    'Search': 'search',
    'Refresh': 'refresh',
    'Broadcast': 'cast',
    'Network': 'swap_vert',
    'Output': 'tv',
    'Trash': 'delete',
    'Power': 'power_settings_new',
    'FolderOff': 'folder_off',
    'Gauge': 'speed',
    'Level': 'equalizer',
    'Image': 'image',
    'Duplicate': 'content_copy',
    'Contrast': 'contrast',
    'Wave': 'waves',
    'Sliders': 'tune',
    'Lock': 'lock',
    'Add': 'add_box',
    'Settings': 'settings',
    'Queue': 'queue_music',
    'Play': 'play_arrow',
    'Pause': 'pause',
}

snake = lambda n: re.sub(r'(?<!^)(?=[A-Z])', '_', n).lower()

def fetch(name):
    r = subprocess.run(['curl', '-sL', '--max-time', '15', BASE % name],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return None
    m = re.search(r'd="([^"]+)"', r.stdout)
    return m.group(1) if m else None

ok, fail = [], []
for java, symbol in MAP.items():
    d = fetch(symbol)
    if not d:
        fail.append((java, symbol))
        continue
    path = os.path.join(OUT, 'ic_fn_%s.xml' % snake(java))
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<!-- §61.363：Material Symbols Rounded「%s」官方 SVG 原文（Apache 2.0）。
     源 https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/%s/default/24px.svg
     官方视框 0 -960 960 960，group translateY=960 归零；单条闭合 fill path（无拼接接头）。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <group android:translateY="960">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="%s" />
    </group>
</vector>
''' % (symbol, symbol, d)
    open(path, 'w', encoding='utf-8', newline='').write(xml)
    ok.append((java, symbol, os.path.basename(path)))

print('OK %d / FAIL %d' % (len(ok), len(fail)))
for f in fail:
    print('  FAILED', f)
print('--- generated ---')
for o in ok:
    print(' ', o[0], '->', o[1], '->', o[2])
