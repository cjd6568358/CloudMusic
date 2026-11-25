#!/usr/bin/env python
"""全 surface 截屏取证 sweep（§61.182 三层守护网之 ③ · §61.183 通道落地）。

流程（全自动取证，零事件注入 —— MIUI 禁 input tap 的替代路径）：
  ① 前置：设备在线 / 包已装 /（可选 --install 装最新 APK）
  ② 前台占用守卫：前台非本 App（用户在用手机）→ REFUSE（无人值守纪律，§61.183）
  ③ surface 链（intent 导航，免 tap）：
     首页 → OPEN_PLAYER 播放页 → CLOSE_PLAYER 回首页，各截屏一张
  ④ 运行时证据：App MediaSession PlaybackState（ownerPid 过滤）+ CM-Media3 logcat 尾部
  ⑤ 落盘 analysis/b2-surface-sweep-<ts>/（PNG ×3 + evidence.json）

判读口径（诚实边界）：脚本只做**确定性取证**；画面不变量（总时长 ≠ 00:00、曲名无
`%20` 天书、封面非兜底红盘、进度条位置合理）由会话内 agent 读 PNG 或用户判读，
脚本不伪造判读结果 —— 见 `12 §61.183`。

用法：
  python tools/b2_surface_sweep.py --serial <host:port> [--install app/build/outputs/apk/debug/app-debug.apk]
"""
from __future__ import annotations

import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
PKG = "com.ccmsc.cloudmusic"
OUT_ROOT = ROOT / "analysis"

NAV_BASE = ["am", "start", "-n", "com.ccmsc.cloudmusic/.MainActivity",
            "--activity-single-top", "--activity-clear-top"]


def adb(serial: str, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", serial, *args],
                          capture_output=True, timeout=timeout)


def sh(serial: str, cmd: str, timeout: int = 60) -> str:
    r = adb(serial, "shell", cmd, timeout=timeout)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def screencap(serial: str, dest: Path) -> bool:
    # 二进制流必须 exec-out（adb shell 会污染字节流，§61.92 工具链口径）
    r = adb(serial, "exec-out", "screencap", "-p", timeout=60)
    if r.returncode != 0 or len(r.stdout) < 1000:
        return False
    dest.write_bytes(r.stdout)
    return True


def foreground_package(serial: str) -> str:
    """当前前台包名（topResumedActivity / ResumedActivity 兜底）。"""
    txt = sh(serial, "dumpsys activity activities")
    m = re.search(r"topResumedActivity=.*?\s(\S+?)/", txt)
    if not m:
        m = re.search(r"ResumedActivity: ActivityRecord\{[^}]*\s(\S+?)/", txt)
    return m.group(1) if m else ""


def app_session_state(serial: str) -> str:
    """App 自己的 MediaSession PlaybackState（双会话陷阱：必须 ownerPid 过滤，§61.182）。"""
    txt = sh(serial, "dumpsys media_session")
    pid = sh(serial, "pidof %s" % PKG).strip()
    blocks = txt.split("MediaSessionRecord")
    for b in blocks:
        if "ownerPid=%s," % pid in b or ("ownerPid=%s" % pid) in b:
            m = re.search(r"state=PlaybackState \{[^}]*\}", b)
            if m:
                return m.group(0)
    return ""


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--install", default="", help="可选：装机后 sweep（adb install -r）")
    args = ap.parse_args()
    serial = args.serial

    result: dict = {"device": serial, "ts": time.strftime("%Y-%m-%d %H:%M:%S")}
    out_dir = OUT_ROOT / ("b2-surface-sweep-" + time.strftime("%Y%m%d-%H%M%S"))
    out_dir.mkdir(parents=True, exist_ok=True)

    if not sh(serial, "pm path %s" % PKG).strip():
        print("[FAIL] 包未安装")
        result["error"] = "not installed"
        (OUT_ROOT / "b2-surface-sweep.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return 1
    if args.install:
        r = subprocess.run([str(ADB), "-s", serial, "install", "-r", args.install],
                           capture_output=True, timeout=300)
        ok = b"Success" in r.stdout
        result["install"] = "Success" if ok else r.stdout.decode("utf-8", "replace")[-200:]
        print("[STEP] install -r → %s" % result["install"])
        if not ok:
            print("[FAIL] 装机失败（签名不同源时请走 备份→卸载→回灌 流程，需用户显式确认）")
            return 1

    # ② 前台占用守卫（2026-11-17 首跑教训：用户正在用手机时 am start 构成打断，
    #    违反「无人值守禁止破坏性注入」纪律）——空闲口径 = 前台为本 App **或桌面**
    #    （com.miui.home / launcher / 熄屏；§61.183 automation runbook 同口径），
    #    其余第三方前台一律拒跑。2026-11-18 用户在场授权验证时修正：桌面 ≠ 占用。
    fg = foreground_package(serial)
    IDLE_PREFIXES = (PKG, "com.miui.home", "com.android.launcher", "com.google.android.apps.nexuslauncher")
    fg_idle = bool(fg) and any(fg == p or fg.startswith(p + ".") for p in IDLE_PREFIXES)
    if not fg_idle:
        print("[REFUSE] 前台是 %s（用户在用手机）——打断用机违反无人值守纪律，拒绝执行。"
              "等手机空闲（前台为本 App 或桌面空闲）后重跑。" % (fg or "未知"))
        result["error"] = "device-busy: foreground=%s" % (fg or "unknown")
        (OUT_ROOT / "b2-surface-sweep.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return 2
    result["foreground"] = fg

    # ③ surface 链（导航 intent 免注入；启动页 1.2s + restore 余量）
    # §61.212 勘误：此前 `";".join(NAV_BASE + [...])` 把单个 am start 的参数拆成了
    # 多条 shell 命令（`;` 是命令分隔符）——`-a ACTION` 从未传到 onNewIntent，
    # 02/03 恒截到上一页；01-home 亦坏（被「App 已在前台」掩盖）。改为空格 join 单命令。
    steps = [
        ("01-home", " ".join(NAV_BASE), 8.0),
        ("02-player", " ".join(NAV_BASE +
            ["-a", "com.ccmsc.cloudmusic.action.OPEN_PLAYER"]), 3.0),
        ("03-home-again", " ".join(NAV_BASE +
            ["-a", "com.ccmsc.cloudmusic.action.CLOSE_PLAYER"]), 3.0),
    ]
    for name, cmd, wait in steps:
        sh(serial, cmd)
        time.sleep(wait)
        ok = screencap(serial, out_dir / ("%s.png" % name))
        result[name] = str(out_dir / ("%s.png" % name)) if ok else "SCREENCAP-FAIL"
        print("[STEP] %s → %s" % (name, "OK" if ok else "SCREENCAP-FAIL"))

    # ④ 运行时证据
    result["app_playback_state"] = app_session_state(serial)
    result["cm_media3_tail"] = [
        ln for ln in sh(serial, "logcat -d -s CM-Media3").strip().splitlines()[-8:]
    ]
    result["out_dir"] = str(out_dir)
    result["verdict_scope"] = ("脚本只取证；画面不变量（总时长≠00:00 / 曲名无 %20 / "
                              "封面非兜底 / 进度合理）由会话内 agent 读 PNG 或用户判读")

    out_json = OUT_ROOT / "b2-surface-sweep.json"
    out_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[INFO] 证据：%s（+ %s）" % (out_dir, out_json.name))
    print("[NOTE] %s" % result["verdict_scope"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
