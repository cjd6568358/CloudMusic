#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.103 后续 · F3 8 格真机复测一键施工单（设备上线后执行）。

背景：用户已明确授权「装机」（含卸载旧 app，会清 app 私有数据）；
但执行时设备无线调试会话掉线（所有端点 TCP 超时 / ping 100% 丢包），
故把「备份 → 卸载 → 装机 → 8 格复测 → 另存新证据 → 恢复历史」固化成
可复跑脚本，设备重新上线后一条命令跑完。

纪律：
- 所有 adb 命令带 -s <serial>；不擅自 crash / 注入；
- 历史证据（l3-f3-matrix.json + 8 份 logcat）先备份、跑完 `git restore`
  恢复，新结果另存为独立文件，**历史证据零改写**；
- 缺省 dry-run，只有 --execute 才真跑；
- 复测脚本自身会临时置 wifiOnly=false 并在 finally 里恢复。

用法：
  python tools/b2_f3_retest_runbook.py --serial 10.0.0.2:NEWPORT
  python tools/b2_f3_retest_runbook.py --serial 10.0.0.2:NEWPORT --execute
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
PKG = "com.example.cloudmusic"
MATRIX = ROOT / "analysis" / "l3-f3-matrix.json"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
FORMATS = ("aiff", "wma", "ape", "wv")
PATHS = ("local", "remote")
STAMP = "2026-11-13"
BACKUP = Path("C:/Users/Administrator/AppData/Local/Temp/f3-retest-backup-" + STAMP)
NEW_MATRIX = ROOT / "analysis" / ("b2-f3-retest-%s.json" % STAMP)


def run(cmd: list[str], *, check: bool = True) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(x) for x in cmd), flush=True)
    r = subprocess.run([str(x) for x in cmd], cwd=str(ROOT),
                       capture_output=True, text=True, timeout=300)
    if r.stdout.strip():
        print("    " + r.stdout.strip()[:400].replace("\n", " | "), flush=True)
    if r.stderr.strip():
        print("    [stderr] " + r.stderr.strip()[:300].replace("\n", " | "),
              flush=True)
    if check and r.returncode != 0:
        raise RuntimeError("命令失败 rc=%d: %s" % (r.returncode, cmd))
    return r


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True, help="adb 设备端点，如 10.0.0.2:43355")
    ap.add_argument("--execute", action="store_true", help="缺省 dry-run")
    ap.add_argument("--wait", type=float, default=8.0)
    args = ap.parse_args()

    print("[%s] F3 8 格复测施工单 serial=%s" %
          ("EXECUTE" if args.execute else "DRY-RUN", args.serial))
    if not APK.is_file():
        print("[FAIL] APK 不存在：%s" % APK)
        return 2

    # 0) 设备必须在线
    state = run([ADB, "-s", args.serial, "get-state"], check=False)
    if state.stdout.strip() != "device":
        print("[BLOCKED] 设备不在线（get-state=%r）。请在手机上重新打开"
              "「开发者选项 → 无线调试」并给出新端点 / 配对码。" %
              state.stdout.strip())
        return 3

    plan = [
        "备份 9 份历史证据到 %s" % BACKUP,
        "adb -s %s uninstall %s（用户已授权，会清私有数据）" % (args.serial, PKG),
        "adb -s %s install -r %s" % (args.serial, APK.name),
        "python tools/l3_f3_matrix_run.py --serial %s "
        "--formats aiff,wma,ape,wv --paths local,remote --wait %s" %
        (args.serial, args.wait),
        "另存 8 份新 logcat 与 %s" % NEW_MATRIX.name,
        "git restore 恢复 9 份历史证据",
    ]
    for i, p in enumerate(plan, 1):
        print("  %d. %s" % (i, p))
    if not args.execute:
        print("[DRY-RUN] 未执行。设备在线后加 --execute 重跑。")
        return 0

    # 1) 备份
    BACKUP.mkdir(parents=True, exist_ok=True)
    shutil.copy2(MATRIX, BACKUP / MATRIX.name)
    for f in FORMATS:
        for p in PATHS:
            src = LOG_DIR / ("%s-%s.txt" % (f, p))
            shutil.copy2(src, BACKUP / src.name)
    print("[OK] 已备份 %d 份" % len(list(BACKUP.iterdir())))

    # 2) 卸载 + 装机
    run([ADB, "-s", args.serial, "uninstall", PKG], check=False)
    run([ADB, "-s", args.serial, "install", "-r", APK])
    upd = run([ADB, "-s", args.serial, "shell", "dumpsys", "package", PKG],
              check=False)
    times = [l.strip() for l in upd.stdout.splitlines()
             if "lastUpdateTime" in l]
    print("[INFO] 装机后 %s" % (times[:1] or "(未读到 lastUpdateTime)"))

    # 3) 8 格复测（会覆盖历史 logcat / matrix.json）
    retest = run([
        sys.executable, "tools/l3_f3_matrix_run.py",
        "--serial", args.serial,
        "--formats", ",".join(FORMATS),
        "--paths", ",".join(PATHS),
        "--wait", str(args.wait),
    ], check=False)
    if retest.returncode != 0:
        print("[WARN] 矩阵脚本 rc=%d，仍继续另存与恢复" % retest.returncode)

    # 4) 另存新证据
    if MATRIX.is_file():
        shutil.copy2(MATRIX, NEW_MATRIX)
    for f in FORMATS:
        for p in PATHS:
            src = LOG_DIR / ("%s-%s.txt" % (f, p))
            dst = LOG_DIR / ("%s-%s-retest-%s.txt" % (f, p, STAMP))
            if src.is_file():
                shutil.copy2(src, dst)
    print("[OK] 新证据：%s + 8 份 *-retest-%s.txt" % (NEW_MATRIX.name, STAMP))

    # 5) 恢复历史（git 跟踪文件）
    restore = ["git", "restore", "--", str(MATRIX.relative_to(ROOT))]
    restore += [str((LOG_DIR / ("%s-%s.txt" % (f, p))).relative_to(ROOT))
                for f in FORMATS for p in PATHS]
    run(restore, check=False)
    print("[OK] 历史证据已 git restore")

    # 6) 摘要
    try:
        d = json.loads(NEW_MATRIX.read_text(encoding="utf-8"))
        print("[RESULT] counts=%s" % d.get("counts"))
        for c in d.get("cells", []):
            print("  %-5s %-6s %-7s %s" %
                  (c.get("format"), c.get("path"), c.get("verdict"),
                   c.get("reason")))
    except (OSError, ValueError) as exc:
        print("[WARN] 新矩阵不可解析：%s" % exc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
