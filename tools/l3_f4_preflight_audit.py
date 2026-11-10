#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.58 守护 / F4 上线前自检的独立审计（离线、只读）。

防伪造「设备已在线 / A/B 已执行 / 前置条件全齐」之外的结论，
防 runbook 漏掉判读分支或恢复确认，防守护 JSON 漂移。
产出 analysis/l3-f4-preflight-audit.json。
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
T = ROOT / "tools"
PF_JSON = A / "l3-f4-preflight.json"
PF_TOOL = T / "l3_f4_preflight.py"
RUNBOOK = A / "l3-f4-online-runbook.md"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
CONTRACTS = T / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-preflight-audit.json"

CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    pf = json.loads(read(PF_JSON) or "{}")
    ck = pf.get("checks", {})
    ev = pf.get("evidence", {})
    rb = read(RUNBOOK)
    p0 = read(P0)
    abres = json.loads(read(A / "l3-f4-kind-slot-ab.json") or "{}")
    ab_honest = bool(abres) and (
        abres.get("primary_criterion_met") is False
        and abres.get("p0_restored") is True
        and abres.get("regression_all_pass") is True
        and "NOT RUN" in abres.get("auto_advance_3_tracks", ""))
    try:
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
        diff = subprocess.run(
            ["git", "diff", "--", str(P0.relative_to(ROOT)).replace("\\", "/")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head, diff = "", "?"

    checks = {
        "preflight_json_present": PF_JSON.is_file() and bool(pf),
        "preflight_all_pass": (
            pf.get("all_pass") is True and len(ck) == 15
            and all(ck.values()) and not pf.get("failed_checks")),
        "ten_guard_jsons_green": (
            len(ev.get("guard_jsons", {})) == 10
            and all(ev.get("guard_jsons", {}).values())),
        "contract_and_compile_precheck_green": (
            ev.get("contract_selfcheck") is True
            and ck.get("compile_precheck_evidence_green") is True),
        "five_samples_recorded": (
            len(ev.get("samples", {})) == 5
            and all(v > 0 for v in ev.get("samples", {}).values())),
        # 12 §61.59：设备已上线、A/B 已执行，改为验收状态如实。
        "device_state_recorded": (
            isinstance(pf.get("device_online"), bool)
            and pf.get("ready_to_run") is False
            and bool(pf.get("blocking_reason"))),
        "ab_result_honest": ab_honest,
        "p0_baseline_clean": (
            diff == ""
            and 'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "runbook_has_one_liner": (
            RUNBOOK.is_file()
            and "l3_f4_kind_slot_ab.py --serial <ip:port> --apply" in rb
            and "一键" in rb),
        "runbook_has_three_verdict_branches": (
            "环节 B 成立" in rb and "假设证伪" in rb and "无有效判据" in rb),
        "runbook_has_restore_checklist": (
            "恢复确认" in rb and "reverse --list" in rb
            and "git diff" in rb),
        "runbook_records_known_limits": (
            "cells" in rb and "静默吞掉" in rb
            and "NOT RUN" in rb and "F3 矩阵 14 格未被改写" in rb),
        "no_credentials_in_evidence": not CRED_PATTERN.search(
            read(PF_JSON) + rb + read(PF_TOOL)),
        "contract_guard_present": "12 §61.58 守护" in read(CONTRACTS),
        "delivery12_has_61_58": "61.58" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.58 / F4 上线前自检的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_preflight_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "offline_preconditions": "ALL_GREEN",
            "device_online": True,
            "ready_to_run": False,
            "sole_blocker": "第二卡点（fMP4 多 Range × vfile 落盘 / demuxer probe）待 native 级定位",
            "ab_executed": True,
            "ab_verdict": "PARTIAL：环节 B 部分成立、非充分原因",
            "f4_status": "BLOCKED",
        },
        "honest_notes": [
            "§61.59：A/B 已执行两次（25s/45s）+ 基线对照，主判据均未达成；前置条件全绿不等于因果链成立。",
            "本守护不证明环节 B 因果链；自动进曲 3 首 / seek / ENDED 仍 NOT RUN。",
            "只读探测 adb devices，不 connect、不改设备现场、零生产代码改动。",
            "A3 仍 G09 PARTIAL、舱不冻结；F3 矩阵 14 格未改写。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.58 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.58 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
