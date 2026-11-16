#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前台参考页自动截图守候（MIUI 禁注入，无法自动点击导航，故监听画面变化自动捕获）。

用户在手机上切页面 → 脚本检测画面显著变化 → 自动 screencap + pull + 归档到本地。
每轮打印心跳，避免 5 分钟无反馈；默认运行 10 分钟后自动退出。

用法：C:/Python313/python.exe tools/capture_reference_screens.py [设备端点] [秒数]
默认端点 192.168.1.2:42335，时长 600s。
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

from PIL import Image

DEVICE = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.2:42335"
DURATION = int(sys.argv[2]) if len(sys.argv) > 2 else 600
INTERVAL = 3
OUT = Path("analysis/screenshots/reference-capture")
TMP = Path(".temp/_refcap_current.png")
OUT.mkdir(parents=True, exist_ok=True)
TMP.parent.mkdir(parents=True, exist_ok=True)
REMOTE = "/sdcard/_refcap.png"


def adb(*args: str, timeout: int = 20) -> str:
    r = subprocess.run(
        ["adb", "-s", DEVICE, *args],
        capture_output=True, text=True, timeout=timeout,
    )
    return (r.stdout or "") + (r.stderr or "")


def fingerprint(path: Path) -> list[int] | None:
    try:
        im = Image.open(path).convert("L").resize((36, 72))
        return list(im.getdata())
    except Exception:
        return None


def diff_ratio(a: list[int] | None, b: list[int] | None) -> float:
    if a is None or b is None or len(a) != len(b):
        return 1.0
    changed = sum(1 for x, y in zip(a, b) if abs(x - y) > 26)
    return changed / len(a)


def focus_app() -> str:
    out = adb("shell", "dumpsys", "window")
    # 通知栏 / 锁屏优先识别并跳过（下拉通知栏会污染基准集）
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ u0 ([^/]+)/", out)
    focus_raw = m.group(1) if m else ""
    if "NotificationShade" in focus_raw or "StatusBar" in focus_raw or "Keyguard" in focus_raw:
        return "system_overlay"
    if focus_raw and "/" in focus_raw:
        return focus_raw
    m2 = re.search(r"mFocusedApp=ActivityRecord\{[^ ]+ u0 (\S+)", out)
    return m2.group(1) if m2 else "unknown"


def short_app(app: str) -> str:
    if "netease.cloudmusic" in app:
        return "netease"
    if "trim.app" in app:
        return "feiniu"
    if "example.cloudmusic" in app:
        return "self"
    return app.replace(".", "_")[:24]


def main() -> int:
    print(f"[watch] device={DEVICE} duration={DURATION}s interval={INTERVAL}s out={OUT}", flush=True)
    start = time.time()
    prev_fp: list[int] | None = None
    prev_app = ""
    saved = 0
    try:
        while time.time() - start < DURATION:
            elapsed = int(time.time() - start)
            app = focus_app()
            # 只截目标 app：飞牛音乐 / 网易云 / 我们自己的 App；系统叠层（通知栏等）跳过
            if app == "system_overlay" or not any(
                tag in app for tag in ("netease.cloudmusic", "trim.app", "example.cloudmusic")
            ):
                print(f"[skip {elapsed}s] app={app} (not a target / overlay)", flush=True)
                time.sleep(INTERVAL)
                continue
            adb("shell", "screencap", "-p", REMOTE)
            adb("pull", REMOTE, str(TMP))
            adb("shell", "rm", REMOTE)
            fp = fingerprint(TMP)
            ratio = diff_ratio(prev_fp, fp)
            app_changed = app != prev_app

            if ratio > 0.08 or app_changed:
                ts = datetime.now().strftime("%H%M%S")
                dest = OUT / f"{ts}_{short_app(app)}.png"
                TMP.replace(dest)
                print(
                    f"[capture #{saved + 1}] {dest.name} app={app} "
                    f"diff={ratio:.3f} app_changed={app_changed}",
                    flush=True,
                )
                prev_fp = fp
                prev_app = app
                saved += 1
            else:
                print(f"[heartbeat {elapsed}s] app={short_app(app)} diff={ratio:.3f} (waiting)", flush=True)
                prev_app = app
            time.sleep(INTERVAL)
    except KeyboardInterrupt:
        print("[watch] interrupted", flush=True)
    finally:
        adb("shell", "rm", REMOTE)
    print(f"[watch] done: saved={saved} screenshots in {OUT}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
