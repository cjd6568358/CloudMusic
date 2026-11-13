#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.105 / ③ ape-remote 加长等待单格的**可执行性与证据有效性**审计。

本轮设备端点全部 offline（无线调试未上线），真机单格**无法执行**，
按 §61.92 纪律写 NOT RUN，不用缓存 / 旧矩阵 / 推断代替一手 logcat。

同时核实工作区里 15:00 出现的两份 `*-2026-11-13.json`：
它们是 11-12 历史 22 格全矩阵快照的副本（旧 serial / 22 格 / elapsed 219.0s /
日志日期 11-12），**不是本轮真机跑批产物**，不采信、不作任何判据；
历史证据零改写（文件原样保留，只在 verdict 中标注 invalid）。

产物：analysis/b2-f3-longwait-verdict-2026-11-13.{json,report.md}
`--check` 只读复跑比对冻结 JSON。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
COPIES = [
    ROOT / "analysis" / "b2-f3-ape-remote-longwait-2026-11-13.json",
    ROOT / "analysis" / "b2-f3-retest2-2026-11-13.json",
]
OLD_LOG = ROOT / "analysis" / "l3-f3-logs" / "ape-remote-retest2-2026-11-13.txt"
SECTION_104_SERIAL = "10.0.0.2:43355"
OUT_JSON = ROOT / "analysis" / "b2-f3-longwait-verdict-2026-11-13.json"
OUT_REPORT = ROOT / "analysis" / "b2-f3-longwait-verdict-2026-11-13.report.md"


def adb_devices() -> tuple[list[dict], str]:
    try:
        r = subprocess.run([str(ADB), "devices", "-l"], capture_output=True,
                           text=True, timeout=20)
        raw = r.stdout or ""
    except Exception as exc:                       # adb 不可用也如实记录
        return [], "adb unavailable: %s" % exc
    out = []
    for line in raw.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            out.append({"serial": parts[0], "state": parts[1]})
    return out, raw.strip()


def collect() -> dict:
    devices, raw = adb_devices()
    online = [d["serial"] for d in devices if d["state"] == "device"]
    checks: list[dict] = []

    def add(name: str, ok: bool, evidence: str) -> None:
        checks.append({"name": name, "pass": bool(ok), "evidence": evidence})

    # ① 设备在线判据（真机命令一律带 -s <serial>）
    add("adb 存在可复跑的 device 端点", bool(online),
        "adb devices -l → %s" % (", ".join("%s=%s" % (d["serial"], d["state"])
                                          for d in devices) or "<空>"))

    # ② 两份副本的身份判据（全部命中即证明是历史快照副本）
    copy_meta = []
    for p in COPIES:
        if not p.is_file():
            copy_meta.append({"file": p.name, "present": False})
            continue
        d = json.loads(p.read_text(encoding="utf-8"))
        copy_meta.append({
            "file": p.name,
            "present": True,
            "serial": d.get("serial"),
            "cells": len(d.get("cells", [])),
            "counts": d.get("counts"),
            "elapsed_s": d.get("elapsed_s"),
        })
    all_old = all(
        m.get("present") and m.get("serial") == "192.168.1.11:38407" and
        m.get("cells") == 22 and m.get("counts") == {"PASS": 14, "FAIL": 8} and
        m.get("elapsed_s") == 219.0
        for m in copy_meta)
    add("两份 15:00 副本经判据识别为 11-12 历史 22 格快照（不采信）", all_old,
        "serial=192.168.1.11:38407 / 22 格 / 14 PASS 8 FAIL / elapsed 219.0s")

    log_old = OLD_LOG.is_file() and "11-12" in OLD_LOG.read_text(
        encoding="utf-8", errors="replace")[:2000]
    add("随附 *-retest2 日志为 11-12 旧 logcat（非 11-13 一手）", log_old,
        str(OLD_LOG.relative_to(ROOT)))

    add("§61.104 装机端点与副本端点不一致", SECTION_104_SERIAL not in
        [m.get("serial") for m in copy_meta],
        "§61.104 真机 serial=%s；副本 serial=%s" % (
            SECTION_104_SERIAL, [m.get("serial") for m in copy_meta]))

    # ③ 复跑剧本：加长等待 8s → 30s，单格 ape × remote
    add("已登记加长等待单格复跑命令（设备上线后可执行）", True,
        "python tools/l3_f3_matrix_run.py --serial <endpoint> "
        "--formats ape --paths remote --wait 30")

    passed = sum(1 for c in checks if c["pass"])
    return {
        "method": "12 §61.105 / ape-remote 加长等待单格：可达性与证据有效性裁决",
        "generated_by": "tools/b2_f3_longwait_audit.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_run": False,
        "status": "NOT_RUN",
        "reason": ("设备端点全部 offline（无线调试未上线）；不执行真机命令、"
                   "不用旧矩阵 / 缓存 / 推断代替一手 logcat。"
                   "§61.104 的 ape-remote NO_READY_IN_WINDOW 仍不判缺陷也不判通过。"),
        "adb_devices_raw": raw,
        "online_endpoints": online,
        "copies": copy_meta,
        "invalid_copies_not_used_as_evidence": all_old,
        "rerun_command": ("python tools/l3_f3_matrix_run.py --serial <endpoint> "
                          "--formats ape --paths remote --wait 30"),
        "rerun_precondition": (
            "设备上线后先 adb -s <endpoint> get-state=device；"
            "运行前备份 analysis/l3-f3-matrix.json 与 l3-f3-logs/（脚本会覆盖），"
            "跑完 git restore 恢复历史证据，新结果另存 -longwait 文件；"
            "真机命令全部带 -s <serial>；不擅自 pm grant / 不改设备设置。"),
        "boundary": (
            "不宣称 8 格已修复 / 全格式覆盖；PASS 判据仅 READY(duration>0) + isPlaying，"
            "未逐格验证 seek / 完整时长 / ENDED；未做破坏性注入，"
            "未改 Manifest / CMake / 生产构建配置。"),
        "checks": checks,
        "checks_passed": passed,
        "checks_total": len(checks),
    }


def report(d: dict) -> str:
    lines = [
        "# §61.105 ape-remote 加长等待单格裁决（2026-11-13）",
        "",
        "- **status = %s，device_run = false**：%s" % (d["status"], d["reason"]),
        "- 判据 %d/%d PASS（其中「设备在线」判据为 FAIL 是 NOT RUN 的直接原因）。"
        % (d["checks_passed"], d["checks_total"]),
        "",
        "## 设备现场（只读）",
        "",
        "```",
        d["adb_devices_raw"] or "<空>",
        "```",
        "",
        "## 15:00 两份副本的处置",
        "",
        "判据命中：%s → 判定为 11-12 历史 22 格全矩阵快照副本，"
        "**不采信、不作任何判据、不覆盖、不删除**（历史零改写）。"
        % d["invalid_copies_not_used_as_evidence"],
        "",
        "| 文件 | serial | 格数 | counts | elapsed |",
        "|---|---|---|---|---|",
    ]
    for m in d["copies"]:
        lines.append("| %s | %s | %s | %s | %s |" % (
            m.get("file"), m.get("serial", "-"), m.get("cells", "-"),
            m.get("counts", "-"), m.get("elapsed_s", "-")))
    lines += [
        "",
        "## 设备上线后的复跑剧本（本轮未执行）",
        "",
        "```",
        d["rerun_command"],
        "```",
        "",
        d["rerun_precondition"],
        "",
        "## 判据明细",
        "",
        "| # | 判据 | 结果 | 证据 |",
        "|---|---|---|---|",
    ]
    for i, c in enumerate(d["checks"], 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["pass"] else "FAIL", c["evidence"]))
    lines += ["", "## 诚实边界", "", d["boundary"], "",
              "复跑：`python tools/b2_f3_longwait_audit.py --check`（只读比对冻结 JSON）。", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.105 加长等待单格裁决")
    ap.add_argument("--check", action="store_true", help="只读复跑，与冻结 JSON 比对")
    args = ap.parse_args()
    data = collect()
    if args.check:
        if not OUT_JSON.is_file():
            print("[FAIL] 冻结证据缺失：%s" % OUT_JSON)
            return 2
        frozen = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        # 「设备在线」是环境快照：§61.105 记录时离线、§61.106 设备上线后会变。
        # 冻结证据零改写，故 --check 只比结论与非环境判据（证据等级不提升）。
        env_check = "adb 存在可复跑的 device 端点"
        keys = ("status", "device_run", "invalid_copies_not_used_as_evidence",
                "rerun_command", "checks_total")
        ok = all(data.get(k) == frozen.get(k) for k in keys)

        def stable_checks(d: dict) -> list[tuple[str, bool]]:
            return [(c.get("name"), c.get("pass"))
                    for c in d.get("checks", []) if c.get("name") != env_check]

        ok = ok and stable_checks(data) == stable_checks(frozen)
        print("[%s] §61.105 加长等待裁决 --check：结论 / 非环境判据一致"
              "（设备在线状态属环境快照，允许漂移）" % ("PASS" if ok else "FAIL"))
        return 0 if ok else 1
    OUT_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    OUT_REPORT.write_text(report(data), encoding="utf-8")
    print("[INFO] status=%s device_run=%s；%d/%d 判据 PASS → %s / %s" % (
        data["status"], data["device_run"], data["checks_passed"],
        data["checks_total"], OUT_JSON.name, OUT_REPORT.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
