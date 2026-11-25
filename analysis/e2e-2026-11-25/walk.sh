#!/usr/bin/env bash
# §61.364 全页面走查：逐个入口点击 + 截图，一次跑完减少 adb 往返。
set -u
D="adb -s 192.168.1.15:40047"
OUT="analysis/e2e-2026-11-25"
sh() { $D shell "$@"; }
shot() {  # shot <name>
  sh screencap -p /sdcard/_s.png
  $D exec-out cat /sdcard/_s.png > "$OUT/$1.png"
  echo "shot $1"
}
tap() { sh input tap "$1" "$2"; }

echo "== boot =="
sh am force-stop com.example.cloudmusic
sleep 1
sh monkey -p com.example.cloudmusic -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
shot 70-home

echo "== tab1 library =="
tap 629 2856; sleep 2; shot 71-library

echo "== library: songs chip =="
tap 200 700; sleep 2; shot 72-lib-songs
tap 447 700; sleep 2; shot 73-lib-artists
tap 700 700; sleep 2; shot 74-lib-albums

echo "== back to library root, open a track list entry =="
tap 629 2856; sleep 2
tap 400 900; sleep 2; shot 75-lib-row-tap
sh input keyevent 4; sleep 1

echo "== tab2 search =="
tap 811 2856; sleep 2; shot 76-search
echo "-- type --"
sh input text "sun"; sleep 2; shot 77-search-typed
sh input keyevent 4; sleep 1

echo "== tab3 more =="
tap 993 2856; sleep 2; shot 78-more

echo "== more: settings =="
sh uiautomator dump /sdcard/_u.xml >/dev/null 2>&1
SET=$(sh cat /sdcard/_u.xml | tr '>' '\n' | grep -oE 'text="设置"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '\[[0-9]+,[0-9]+\]' | head -1)
echo "settings bounds: $SET"
tap 720 1217; sleep 2; shot 79-settings
sh input keyevent 4; sleep 1

echo "== more: sources =="
tap 720 1660; sleep 2; shot 80-sources
sh input keyevent 4; sleep 1

echo "== more: playlists =="
tap 720 300; sleep 2; shot 81-playlists
sh input keyevent 4; sleep 1

echo "== player page =="
tap 500 2566; sleep 3; shot 82-player
sh input keyevent 4; sleep 1

echo "== queue =="
tap 1100 2566; sleep 2; shot 83-queue
sh input keyevent 4; sleep 1

echo "== back key behavior from home =="
sh input keyevent 4; sleep 2; shot 84-back-from-home
$D shell dumpsys window | grep mCurrentFocus | head -1

echo "== done =="
