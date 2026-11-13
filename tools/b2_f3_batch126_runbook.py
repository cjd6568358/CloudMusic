#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.106 · 第一百二十六批修复后的 F3 真机复测一键施工单。

承接 §61.105：设备端点重新上线后，把 §61.105 的 ①Asf / ②PCM 预置 /
④ReplayGain·gapless 装进真机，并按同一口径复跑 F3 8 格 + ape-remote
加长等待单格（③ 的 NOT RUN 转正）。

与 §61.104 的 runbook 差异：
- **不卸载**（§61.104 已用本机 debug keystore 重装，签名一致 → 直接
  `install -r`，避免不可逆地清掉 app 私有数据）；
- 两阶段：8 格 `--wait 8`（与 §61.104 同口径可对比）→ 单格 ape×remote
  `--wait 30`（8s→30s 排除时序）；
- 两阶段结果分别另存，历史证据零改写。

纪律：
- 所有 adb 命令带 `-s <serial>`；不擅自 crash / 注入 / pm grant；
- 历史证据（`l3-f3-matrix.json` + 8 份 logcat）先备份、跑完 `git restore`
  恢复，新结果另存独立文件；
- 缺省 dry-run，只有 `--execute` 才真跑；
- 矩阵脚本自身临时置 `wifiOnly=false` 并在 finally 恢复。

用法：
  python tools/b2_f3_batch126_runbook.py --serial 10.0.0.2:41971
  python tools/b2_f3_batch126_runbook.py --serial 10.0.0.2:41971 --execute
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime
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
TAG = "batch126"
BACKUP = Path("C:/Users/Administrator/AppData/Local/Temp/f3-%s-backup-%s" % (TAG, STAMP))
NEW_MATRIX = ROOT / "analysis" / ("b2-f3-%s-%s.json" % (TAG, STAMP))
LONG_MATRIX = ROOT / "analysis" / ("b2-f3-ape-remote-longwait-device-%s.json" % STAMP)
SUMMARY = ROOT / "analysis" / ("b2-f3-%s-run-%s.json" % (TAG, STAMP))


def run(cmd: list[str], *, check: bool = True, timeout: int = 600) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(x) for x in cmd), flush=True)
    r = subprocess.run([str(x) for x in cmd], cwd=str(ROOT),
                       capture_output=True, text=True, timeout=timeout)
    if r.stdout.strip():
        print("    " + r.stdout.strip()[:600].replace("\n", " | "), flush=True)
    if r.stderr.strip():
        print("    [stderr] " + r.stderr.strip()[:300].replace("\n", " | "),
              flush=True)
    if check and r.returncode != 0:
        raise RuntimeError("命令失败 rc=%d: %s" % (r.returncode, cmd))
    return r


def save_logs(tag: str, only: tuple[str, ...] | None = None) -> list[str]:
    saved = []
    for f in FORMATS:
        for p in PATHS:
            if only and (f, p) not in only:
                continue
            src = LOG_DIR / ("%s-%s.txt" % (f, p))
            dst = LOG_DIR / ("%s-%s-%s-%s.txt" % (f, p, tag, STAMP))
            if src.is_file():
                shutil.copy2(src, dst)
                saved.append(dst.name)
    return saved


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.106 真机复测施工单")
    ap.add_argument("--serial", required=True, help="adb 设备端点")
    ap.add_argument("--execute", action="store_true", help="缺省 dry-run")
    ap.add_argument("--wait", type=float, default=8.0, help="8 格每格等待秒数（与 §61.104 对齐）")
    ap.add_argument("--longwait", type=float, default=30.0, help="ape-remote 单格加长等待秒数")
    args = ap.parse_args()

    steps = [
        "adb -s %s get-state（须为 device）" % args.serial,
        "备份 9 份历史证据到 %s" % BACKUP,
        "adb -s %s install -r %s（不卸载，保留 app 私有数据）" % (args.serial, APK.name),
        "阶段 A：8 格矩阵 --wait %s → 另存 %s + 8 份 *-%s-%s.txt"
        % (args.wait, NEW_MATRIX.name, TAG, STAMP),
        "阶段 B：ape×remote 单格 --wait %s → 另存 %s + 1 份 logcat"
        % (args.longwait, LONG_MATRIX.name),
        "git restore 恢复 9 份历史证据",
        "落盘运行摘要 %s" % SUMMARY.name,
    ]
    print("[PLAN] §61.106 真机复测")
    for i, s in enumerate(steps, 1):
        print("  %d. %s" % (i, s))
    if not args.execute:
        print("[DRY-RUN] 未执行。加 --execute 真跑。")
        return 0

    state = run([ADB, "-s", args.serial, "get-state"], check=False)
    if state.stdout.strip() != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state.stdout.strip())
        return 1

    # 1) 备份历史证据（9 份）
    BACKUP.mkdir(parents=True, exist_ok=True)
    shutil.copy2(MATRIX, BACKUP / MATRIX.name)
    for f in FORMATS:
        for p in PATHS:
            src = LOG_DIR / ("%s-%s.txt" % (f, p))
            if src.is_file():
                shutil.copy2(src, BACKUP / src.name)
    print("[OK] 已备份 %d 份" % len(list(BACKUP.iterdir())))

    # 2) 装机（不卸载）
    inst = run([ADB, "-s", args.serial, "install", "-r", APK], check=False)
    install_ok = inst.returncode == 0 and "Success" in (inst.stdout or "")
    upd = run([ADB, "-s", args.serial, "shell", "dumpsys", "package", PKG],
              check=False)
    times = [l.strip() for l in upd.stdout.splitlines() if "lastUpdateTime" in l]
    print("[INFO] 装机 rc=%s / %s" % (inst.returncode, times[:1] or "(无 lastUpdateTime)"))
    if not install_ok:
        print("[FAIL] 装机失败，终止（不跑矩阵）")
        return 1

    summary: dict = {
        "method": "12 §61.106 / 第一百二十六批修复后的真机复测",
        "generated_by": "tools/b2_f3_batch126_runbook.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "serial": args.serial,
        "apk_bytes": APK.stat().st_size if APK.is_file() else None,
        "install_reinstall_no_uninstall": True,
        "install_last_update_time": times[:1] or [],
        "wait_8cells_s": args.wait,
        "wait_ape_remote_s": args.longwait,
        "device_run": True,
    }

    # 3) 阶段 A：8 格
    a = run([
        sys.executable, "tools/l3_f3_matrix_run.py",
        "--serial", args.serial,
        "--formats", ",".join(FORMATS),
        "--paths", ",".join(PATHS),
        "--wait", str(args.wait),
    ], check=False, timeout=1200)
    summary["stage_a_rc"] = a.returncode
    if MATRIX.is_file():
        shutil.copy2(MATRIX, NEW_MATRIX)
    summary["stage_a_logs"] = save_logs(TAG)

    # 4) 阶段 B：ape-remote 单格加长等待（会再次覆盖 MATRIX 与 ape-remote.txt）
    b = run([
        sys.executable, "tools/l3_f3_matrix_run.py",
        "--serial", args.serial,
        "--formats", "ape",
        "--paths", "remote",
        "--wait", str(args.longwait),
    ], check=False, timeout=600)
    summary["stage_b_rc"] = b.returncode
    if MATRIX.is_file():
        shutil.copy2(MATRIX, LONG_MATRIX)
    summary["stage_b_logs"] = save_logs("longwait-device", only=(("ape", "remote"),))

    # 5) 恢复历史证据
    restore = ["git", "restore", "--", str(MATRIX.relative_to(ROOT))]
    restore += [str((LOG_DIR / ("%s-%s.txt" % (f, p))).relative_to(ROOT))
                for f in FORMATS for p in PATHS]
    run(restore, check=False)
    summary["history_restored"] = True
    print("[OK] 历史证据已 git restore")

    # 6) 摘要
    try:
        d = json.loads(NEW_MATRIX.read_text(encoding="utf-8"))
        summary["stage_a_counts"] = d.get("counts")
        summary["stage_a_cells"] = [
            {"format": c.get("format"), "path": c.get("path"),
             "verdict": c.get("verdict"), "reason": c.get("reason"),
             "duration_raw": c.get("mDuration_raw"),
             "errors": c.get("errors")}
            for c in d.get("cells", [])
        ]
        print("[RESULT] 阶段 A counts=%s" % d.get("counts"))
        for c in d.get("cells", []):
            print("  %-5s %-6s %-7s %s" %
                  (c.get("format"), c.get("path"), c.get("verdict"),
                   str(c.get("reason"))[:100]))
    except (OSError, ValueError) as exc:
        summary["stage_a_parse_error"] = str(exc)
    try:
        dl = json.loads(LONG_MATRIX.read_text(encoding="utf-8"))
        summary["stage_b_counts"] = dl.get("counts")
        summary["stage_b_cells"] = [
            {"format": c.get("format"), "path": c.get("path"),
             "verdict": c.get("verdict"), "reason": c.get("reason"),
             "duration_raw": c.get("mDuration_raw")}
            for c in dl.get("cells", [])
        ]
        print("[RESULT] 阶段 B counts=%s" % dl.get("counts"))
    except (OSError, ValueError) as exc:
        summary["stage_b_parse_error"] = str(exc)
    SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")
    print("[OK] 摘要 %s" % SUMMARY.name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
