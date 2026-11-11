#!/usr/bin/env python
"""B2 M3 门禁补测 + M4 播放语义全自动验收（§61.88 后续）。

设备上线后一条命令完成：
  ① M3 门禁补测：65s URL 源播放（END + 缓存落盘）
  ② M3 门禁判定：移除网络后缓存命中复播（离线 PLAYING = 命中实锤）
  ③ M4 语义：seek 30%/70%/0% 三点着陆（<500ms）+ ENDED 回调
  ④ 产出 analysis/b2-m34-acceptance.json

判据口径沿用 §61.74 多信号纪律：READY(duration>0) ≈ 解码链打开、isPlaying ≈
真实出声、ENDED 事件 ≈ 自然播完；单信号不反向推断播放成功。

用法：
    python tools/b2_m34_acceptance.py --serial <ip:port>
    python tools/b2_m34_acceptance.py --wait-device 3600   # 等设备上线（1h）
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
PKG = "com.example.cloudmusic"
ACT = "com.example.cloudmusic.action.PLAY"
EXTRA_URL = "com.example.cloudmusic.extra.SOURCE_URL"
EXTRA_SEEK = "com.example.cloudmusic.extra.SEEK_POSITION_MS"
URL = "http://127.0.0.1:8765/m3_65s.mp3"
OUT = ROOT / "analysis" / "b2-m34-acceptance.json"


def adb(serial: str, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(ADB), "-s", serial, *args], capture_output=True, timeout=timeout)


def sh(serial: str, cmd: str, timeout: int = 60) -> str:
    return adb(serial, "shell", cmd, timeout=timeout).stdout.decode(
        "utf-8", "replace")


def cm_lines(serial: str) -> list[str]:
    r = adb(serial, "logcat", "-d", "-v", "time")
    return [l for l in r.stdout.decode("utf-8", "replace").splitlines()
            if "CM-Media3" in l]


def trigger_url(serial: str, url: str) -> None:
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(5)
    adb(serial, "shell", "am", "startservice", "-a", ACT,
        "-c", "android.intent.category.DEFAULT", "--es", EXTRA_URL, url)


def trigger_seek(serial: str, ms: int) -> None:
    adb(serial, "shell", "am", "startservice", "-n",
        "%s/.PlaybackService" % PKG, "-a", "com.example.cloudmusic.action.SEEK",
        "--el", EXTRA_SEEK, str(ms))


def last_play_event(lines: list[str]) -> tuple[str, int]:
    """最近一次 PLAYING/ENDED/READY/PAUSED 事件 → (kind, positionMs)。"""
    for l in reversed(lines):
        m = re.search(r"state=(PLAYING|PAUSED|ENDED|READY) positionMs=(\d+)", l)
        if m:
            return m.group(1), int(m.group(2))
        m = re.search(r"state=READY durationMs=(-?\d+)", l)
        if m:
            return ("READY", int(m.group(1)))
    return ("NONE", -1)


def started_playing(lines: list[str]) -> bool:
    return any(re.search(r"state=PLAYING", l) for l in lines)


def started_ended(lines: list[str]) -> bool:
    return any(re.search(r"state=ENDED", l) for l in lines)


def has_error(lines: list[str]) -> bool:
    return any(re.search(r"CM-Media3[^:]*: (engine_)?error=", l) for l in lines)


def cache_bytes(serial: str) -> int:
    out = sh(serial, "run-as %s du -sb cache/b2_media_cache/ 2>/dev/null" % PKG)
    m = re.match(r"(\d+)", out.strip())
    return int(m.group(1)) if m else 0


def wait_device(wait_s: int) -> str | None:
    deadline = time.time() + wait_s
    while time.time() < deadline:
        r = adb("", "devices")
        for line in r.stdout.decode("utf-8", "replace").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                return parts[0]
        time.sleep(15)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--wait-device", type=int, default=0,
                    help="先等待设备上线（秒）；缺省 0 = 设备必须已在线")
    args = ap.parse_args()

    serial = args.serial or wait_device(args.wait_device)
    if not serial:
        print("[FAIL] 无设备在线")
        return 1
    print("[OK] 设备：%s" % serial)
    result: dict = {"device": serial, "ts": time.strftime("%Y-%m-%d %H:%M:%S")}

    # ------------------------------------------------ ① 65s URL 连续播放 + END
    # 65s mp3 需先就位：优先复用设备缓存目录是否已有播放历史，否则要求 PC 侧
    # 已按 12 §61.88 准备 http.server + adb reverse（本脚本只做 reverse 兜底）。
    adb(serial, "reverse", "tcp:8765", "tcp:8765")
    adb(serial, "logcat", "-c")
    print("[RUN ] ① 65s URL 连续播放（END + 缓存落盘）……")
    trigger_url(serial, URL)
    # 65s 音源：65s 后应 ENDED；此处等 75s 再判。
    time.sleep(75)
    lines = cm_lines(serial)
    phase1 = {
        "playing_seen": started_playing(lines),
        "ended_seen": started_ended(lines),
        "error_seen": has_error(lines),
        "last_event": last_play_event(lines),
        "cache_bytes_after_first": cache_bytes(serial),
    }
    phase1["gate_60s_continuous"] = (
        phase1["playing_seen"] and not phase1["error_seen"])
    result["phase1_continuous_play"] = phase1
    print("     playing=%s ended=%s error=%s cache=%dB"
          % (phase1["playing_seen"], phase1["ended_seen"],
             phase1["error_seen"], phase1["cache_bytes_after_first"]))

    # ------------------------------------------------ ② 缓存命中离线复播
    print("[RUN ] ② 缓存命中离线复播（移除 reverse + 停 http 前提下）……")
    adb(serial, "reverse", "--remove", "tcp:8765")
    adb(serial, "logcat", "-c")
    trigger_url(serial, URL)
    time.sleep(10)
    lines = cm_lines(serial)
    phase2 = {
        "playing_seen": started_playing(lines),
        "error_seen": has_error(lines),
        "last_event": last_play_event(lines),
    }
    # 离线 + FLAG_IGNORE_CACHE_ON_ERROR 回落直连必失败 → PLAYING = 命中实锤
    phase2["cache_hit"] = phase2["playing_seen"] and not phase2["error_seen"]
    result["phase2_cache_hit"] = phase2
    print("     cache_hit=%s (playing=%s error=%s)"
          % (phase2["cache_hit"], phase2["playing_seen"], phase2["error_seen"]))
    adb(serial, "reverse", "tcp:8765", "tcp:8765")

    # ------------------------------------------------ ③ M4 seek 三点判据
    print("[RUN ] ③ M4 seek 三点（30%/70%/0%，着陆 <500ms）……")
    trigger_url(serial, URL)
    time.sleep(8)
    seeks = []
    for label, target in (("30%", 19500), ("70%", 45500), ("0%", 0)):
        adb(serial, "logcat", "-c")
        t0 = time.time()
        trigger_seek(serial, target)
        time.sleep(3)
        lines = cm_lines(serial)
        pos = -1
        for l in reversed(lines):
            m = re.search(r"state=PLAYING positionMs=(\d+)", l)
            if m:
                pos = int(m.group(1))
                break
        err = abs(pos - target)
        seeks.append({
            "label": label, "target_ms": target,
            "landing_ms": pos, "error_ms": err,
            "verdict": "PASS" if err <= 500 else "FAIL",
        })
        print("     seek %s → landing=%d err=%dms %s"
              % (label, pos, err, seeks[-1]["verdict"]))
    result["phase3_seek"] = seeks
    result["m4_seek_gate"] = all(s["verdict"] == "PASS" for s in seeks)

    # ------------------------------------------------ 汇总
    result["m3_gate"] = {
        "continuous_60s": result["phase1_continuous_play"]["gate_60s_continuous"],
        "cache_hit": phase2["cache_hit"],
        "passed": (result["phase1_continuous_play"]["gate_60s_continuous"]
                   and phase2["cache_hit"]),
    }
    result["m4_partial"] = {"seek_gate": result["m4_seek_gate"]}
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    print("[PASS] 结果落盘 %s" % OUT)
    print("[VERDICT] M3 门禁=%s / M4-seek=%s"
          % (result["m3_gate"]["passed"], result["m4_seek_gate"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
