#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.60 守护 / F4 第二卡点样本侧复核的独立审计（离线、只读）。
防把「归因优先级修正」写成「第二卡点已定位 / F4 已解决」，
防改写 §61.59 的 A/B 实测事实、防伪造真机验证。
产出 analysis/l3-f4-second-blocker-audit.json。
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
STRUCT = A / "l3-f4-fmp4-structure.json"
STRUCT_TOOL = A / ".." / "tools" / "l3_f4_fmp4_structure_audit.py"
REPORT = A / "l3-f4-second-blocker-report.md"
RESULT = A / "l3-f4-kind-slot-ab.json"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-second-blocker-audit.json"

CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    # 12 §61.66：本工具是 §61.6x 当时的历史快照审计，其
    # head_still_uncommitted 断言钉死「HEAD=8c9d79b1 且未提交」。
    # 仓库同步（c26e7cb8 已 push）后重跑会把当时快照误判为 FAIL 并覆盖
    # 历史证据 JSON；按 §61.46 既有纪律拒绝重跑（返回 2），长效守护以
    # check_source_contracts.py + 已固化 JSON 为准。
    _snapshot_head = subprocess.run(
        ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
        capture_output=True, text=True, timeout=20).stdout.strip()
    if _snapshot_head and not _snapshot_head.startswith("8c9d79b1"):
        print("[SKIP] 12 §61.66 历史快照审计：当前 HEAD=%s 已非当时的 "
              "8c9d79b1，拒绝重跑以免覆盖历史证据 JSON（返回 2）"
              % _snapshot_head)
        return 2
    st = json.loads(read(STRUCT) or "{}")
    ck = st.get("checks", {})
    ev = st.get("evidence", {})
    report = read(REPORT)
    ab = json.loads(read(RESULT) or "{}")
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
        "structure_json_present": STRUCT.is_file() and bool(st),
        "structure_all_pass": (
            st.get("all_pass") is True and len(ck) == 12 and all(ck.values())),
        "orphan_tail_moof": ck.get("tail_is_orphan_moof_no_mdat") is True,
        "no_index_boxes": ck.get("no_sidx_mfra_mfro_index") is True,
        "fragment_counts_asymmetric": (
            ev.get("box_counts", {}).get("moof") == 13
            and ev.get("box_counts", {}).get("mdat") == 12),
        "two_unrequested_gaps": (
            ev.get("unrequested_gaps") == [[0, 147456], [327895, 605007]]),
        "ondata_exact_no_loss": ck.get("ondata_matches_request_exactly") is True,
        "report_present_and_honest": (
            REPORT.is_file()
            and "归因修正" in report
            and "PENDING_REAL_SAMPLE" in report
            and "F4 仍判 BLOCKED" in report
            and "不改写" in report
            and "未真机验证" in report),
        "ab_facts_not_rewritten": (
            ab.get("primary_criterion_met") is False
            and ab.get("p0_restored") is True
            and ab.get("regression_all_pass") is True),
        "attribution_correction_in_delivery12": (
            "归因修正见 `§61.60`" in read(DELIVERY12)),
        "p0_git_diff_clean": diff == "",
        "no_credentials_in_evidence": not CRED_PATTERN.search(
            read(STRUCT) + report + read(STRUCT_TOOL)),
        "contract_guard_present": "12 §61.60 守护" in read(CONTRACTS),
        "delivery12_has_61_60": "61.60" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.60 / F4 第二卡点样本侧复核的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_second_blocker_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "second_blocker_primary_suspect": "人工重排样本不自洽（缺索引 / 尾部截断 / 分片跳读）",
            "second_blocker_secondary": "normal mode vfile 落盘时序",
            "verification": "PENDING_REAL_SAMPLE（设备已离线，未真机验证）",
            "ab_facts_rewritten": False,
            "f4_status": "BLOCKED",
        },
        "honest_notes": [
            "本批只做归因优先级修正，未定位也未证实第二卡点的真机成因。",
            "§61.59 A/B 实测结论（环节 B 部分成立、主判据未达成、P0 零净改动）不改写。",
            "人工构造的容器不能用来判定播放器缺陷；自动进曲 3 首 / seek / ENDED 仍 NOT RUN。",
            "纯离线、只读、零代码改动；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.60 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.60 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
