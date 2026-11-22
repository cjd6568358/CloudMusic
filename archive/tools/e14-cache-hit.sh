#!/usr/bin/env bash
# E14：缓存命中专项实验（12 §61.8/§62 预案）
# 前置：设备亮屏解锁、无线 adb 已连（adb connect 192.168.1.11:39525）
# 验证链：首次播放（EOS → msg=100 → 61.8 释放 → 索引提交）→ 免网络二次播放（缓存命中出声）
# 用法: bash tools/e14-cache-hit.sh
set -e
ADB="tools/bin/adb.exe -s 192.168.1.11:39525"
PKG=com.example.cloudmusic

echo "== 0. 安装含 61.8 修复的 APK =="
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB reverse tcp:8765 tcp:8765
$ADB shell "rm -rf /data/data/$PKG/cache/legacy_engine_cache/g"  # 清缓存（首轮必须走网络）

echo "== 1. 首次播放（网络可用期，预期 EOS → msg=100 → 61.8 释放 → 索引提交）=="
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
$ADB shell cmd media_session dispatch play; sleep 6
$ADB shell cmd media_session dispatch play; sleep 6

echo "== 2. 验证索引提交（.nmsfi 应含切片表）=="
$ADB exec-out "run-as $PKG cat cache/legacy_engine_cache/g/g01-local.nmsfi" > /tmp/e14.nmsfi
ls -la /tmp/e14.nmsfi

echo "== 3. 免网络二次播放：断开 reverse 后缓存命中 =="
$ADB reverse --remove tcp:8765
$ADB logcat -c
$ADB shell cmd media_session dispatch play; sleep 4
$ADB shell cmd media_session dispatch play; sleep 8

echo "== 4. 判据采集 =="
$ADB logcat -d | grep -e "ReadFromFileNoLock" -e "doDecoder" -e "EOS" -e "openSource" \
    -e "tracks of which" -e "msg=100" | tail -20
echo "== 判据：ReadFromFileNoLock readed_len>0（缓存命中）且 doDecoder 无 -541478725 =="
echo "== 且 audio_flinger active tracks >= 1 =="
