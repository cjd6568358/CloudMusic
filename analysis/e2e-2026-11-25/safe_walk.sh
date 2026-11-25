#!/usr/bin/env bash
# §61.364 安全走查：只走 SurfaceNav 支持的 5 个直达 surface，
# 每步先校验前台包名是本 App，否则整条脚本立即中止（绝不盲点）。
set -u
PKG="com.ccmsc.cloudmusic"
ACT="$PKG/.MainActivity"
D="adb -s 192.168.1.15:40047"
OUT="analysis/e2e-2026-11-25"

fg() { $D shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1; }

require_ours() {
  local line
  line="$(fg)"
  case "$line" in
    *"$PKG"*) echo "  [ok] $line" ;;
    *) echo "  [ABORT] 前台不是本 App：$line"; exit 9 ;;
  esac
}

shot() {
  $D shell screencap -p /sdcard/_g.png
  $D exec-out cat /sdcard/_g.png > "$OUT/$1.png"
  echo "  shot $1"
}

open_surface() {   # open_surface <ACTION> <shot-name>
  echo "== $1 =="
  $D shell am start -n "$ACT" -a "$PKG.action.$1" --activity-single-top >/dev/null 2>&1
  sleep 3
  require_ours
  shot "$2"
}

require_ours
open_surface OPEN_LIBRARY  90-safe-library
open_surface OPEN_SEARCH   91-safe-search
open_surface OPEN_SETTINGS 92-safe-settings
open_surface OPEN_PLAYER   93-safe-player
open_surface CLOSE_PLAYER  94-safe-home
echo "== all surfaces captured =="
