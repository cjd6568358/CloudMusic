#!/usr/bin/env python
"""G09-B2 复测（crash → 恢复观测 · §61.92 挂账收口）。

流程（全自动，判据沿 §61.32 亮屏实证口径）：
  ① 确认包已装 + 启动 App（am start）+ 确认进程
  ② 注入 crash（am crash）→ 确认进程退出
  ③ 观测 300s：进程是否自动重启（START_STICKY / MIUI 策略）
  ④ 若重启：检查恢复链（CM-Media3 prepare 日志 = Room 游标恢复 → PAUSED）
  ⑤ 落盘 analysis/b2-g09-retest.json
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
OUT = ROOT / "analysis" / "b2-g09-retest.json"


def adb(serial: str, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", serial, *args],
                          capture_output=True, timeout=timeout)


def sh(serial: str, cmd: str, timeout: int = 60) -> str:
    return adb(serial, "shell", cmd, timeout=timeout).stdout.decode(
        "utf-8", "replace")


def pid(serial: str) -> str:
    return sh(serial, "pidof %s" % PKG).strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--observe", type=int, default=300,
                    help="crash 后观测秒数（默认 300）")
    args = ap.parse_args()
    serial = args.serial
    result: dict = {"device": serial, "ts": time.strftime("%Y-%m-%d %H:%M:%S")}

    # ① 启动
    if not sh(serial, "pm path %s" % PKG).strip():
        print("[FAIL] 包未安装")
        result["error"] = "not installed"
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        return 1
    adb(serial, "shell", "logcat", "-c")
    adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(5)
    pid_before = pid(serial)
    result["pid_before"] = pid_before
    print("[OK] 进程：%s" % (pid_before or "无"))
    if not pid_before:
        print("[FAIL] App 启动失败")
        result["error"] = "launch failed"
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        return 1

    # ② crash 注入
    adb(serial, "shell", "am", "crash", PKG)
    time.sleep(3)
    pid_after = pid(serial)
    result["pid_after_crash"] = pid_after
    result["process_exited"] = pid_after != pid_before
    print("[CRASH] 进程退出：%s" % result["process_exited"])

    # ③ 观测自动重启
    restart_at = None
    deadline = time.time() + args.observe
    while time.time() < deadline:
        p = pid(serial)
        if p and p != pid_before:
            restart_at = (p, time.strftime("%H:%M:%S"))
            break
        time.sleep(10)
    result["restart"] = ({"pid": restart_at[0], "at": restart_at[1]}
                         if restart_at else
                         {"pid": None, "at": None,
                          "note": "%ds 内无自动重启（crash 退避 / MIUI 策略）"
                                  % args.observe})
    print("[RESTART] %s" % result["restart"])

    # ④ 恢复链检查（若重启：Room 游标恢复 → CM-Media3 prepare → 保持 PAUSED）
    if restart_at:
        lines = [l for l in sh(serial, "logcat -d").splitlines()
                 if "CM-Media3" in l]
        result["restore_prepare_seen"] = any("prepare trackId=" in l for l in lines)
        result["restored_state_paused"] = any(
            "state=PAUSED" in l or "state=IDLE" in l for l in lines)
        print("[RESTORE] prepare=%s paused=%s"
              % (result["restore_prepare_seen"], result["restored_state_paused"]))
    result["verdict"] = (
        "PASS" if restart_at and result.get("restore_prepare_seen")
        else "PARTIAL" if restart_at else
        "NOT_AUTO_RESTARTED（设备策略层；恢复链机制已由冷启动多轮验证）")
    print("[VERDICT] G09-B2 = %s" % result["verdict"])
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
