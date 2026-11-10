#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.67 守护审计：只读复核本批反汇编证据、报告与口径，不写证据 JSON 之外的文件。

与 §61.66 的「历史快照工具」不同，本工具不钉死任何 HEAD 值，可反复重跑；
长效守护以 check_source_contracts.py 的 §61.67 段 + 已固化 JSON 为准。

产出 analysis/l3-f4-generation-gate-audit.json。
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DISASM = ROOT / "analysis" / "l3-f4-generation-gate-disasm.json"
REPORT = ROOT / "analysis" / "l3-f4-generation-gate-report.md"
AUDIT = ROOT / "analysis" / "l3-f4-generation-gate-audit.json"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
CONTRACT_CHECK = ROOT / "tools" / "check_source_contracts.py"
BACKLOG = ROOT / "analysis" / "task-backlog-2026-11-07.md"


def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    missing = [str(p.relative_to(ROOT)) for p in
               (DISASM, REPORT, DELIVERY12, PLAN17, CONTRACT_CHECK, BACKLOG)
               if not p.is_file()]
    if missing:
        print("[FAIL] 缺少文件：%s" % missing)
        return 1

    d = json.loads(DISASM.read_text(encoding="utf-8"))
    rep = read_text(REPORT)
    d12, d17, ck, bl = (read_text(p) for p in
                        (DELIVERY12, PLAN17, CONTRACT_CHECK, BACKLOG))
    anchors = d.get("anchors", {})
    strings = d.get("rodata_strings", {})
    checks = d.get("checks", {})
    finding = d.get("finding", {})
    notes = " ".join(d.get("honest_notes", []))
    binary = " ".join(finding.get("binary_confirmed", []))

    core_checks = (
        "generation_increment_chain", "generation_passed_to_start_callback",
        "generation_compare_chain", "early_drop_skips_data_callback",
        "commit_write_gated", "slice_write_disabled_flag",
        "slice_write_alignment_gate", "slice_write_file_len_gate",
        "slice_write_eof_gate", "slice_write_actual_call",
        "slice_write_error_and_finish", "prior_61_65_evidence_intact",
        "f4_still_blocked",
    )

    evidence = {
        "disasm_json_all_pass": d.get("all_pass") is True
                                and not d.get("failed_checks"),
        "disasm_checks_total_18": len(checks) == 18 and all(checks.values()),
        "disasm_checks_core_all": all(checks.get(k) is True for k in core_checks),
        "anchors_total_45": len(anchors) == 45,
        "anchors_all_ok": bool(anchors) and all(v.get("ok") for v in anchors.values()),
        "strings_total_11": len(strings) == 11,
        "strings_all_ok": bool(strings) and all(v.get("ok") for v in strings.values()),
        "generation_increment_point_0x104B8": "0x104B8" in binary and "0x104B0" in binary,
        "generation_passed_0x104D8": "0x104D8" in binary,
        "early_drop_0x10798": "0x10798" in binary,
        "data_callback_0x1086C_skipped": "0x1086C" in binary and "0x108E0" in binary,
        "slice_file_len_gate_0x12820": "0x12820" in binary and "file_len" in binary,
        "slice_log_0x3CF90_restored": "0x3CF90" in binary or "0x3CF90" in rep,
        "inference_not_promoted_to_proof": (
            "inference" in json.dumps(finding, ensure_ascii=False)
            and "未做新的真机 A/B" in json.dumps(finding, ensure_ascii=False)),
        "f4_status_blocked_in_notes": "F4 仍 BLOCKED" in notes,
        "no_solved_claim": (
            "F4 已解决" not in rep.replace("不写「F4 已解决", "")
            and "全格式已支持" not in rep.replace("不写「F4 已解决 / m4a 已支持 / 全格式已支持」", "")),
        "device_41417_documented": (
            "41417" in finding.get("device_endpoint_41417", "")
            and "NOT RUN" in finding.get("device_endpoint_41417", "")
            and "41417" in rep),
        "zero_device_operation": "零设备操作" in notes,
        "no_credential_leak": not any(s in rep for s in ("Bearer ", "OPENLIST_PASSWORD=", "password=")),
    }

    docs = {
        "delivery12_has_61_67": "61.67" in d12,
        "plan17_has_8_27": "8.27" in d17,
        "contract_guard_present": "12 §61.67 守护" in ck,
        "backlog_has_61_67": "61.67" in bl,
        "report_has_key_anchors": all(s in rep for s in (
            "0x104B8", "0x10798", "0x1086C", "0x12820", "0x3CF90",
            "41417", "F4 仍 BLOCKED", "不写", "剩余断点")),
        "report_has_method": "capstone" in rep and "pyelftools" in rep,
    }

    all_checks = {**evidence, **docs}
    failed = [k for k, v in all_checks.items() if not v]
    result = {
        "method": "12 §61.67 审计（只读复核证据 JSON / 报告 / 文档口径，可反复重跑）",
        "generated_by": "tools/l3_f4_generation_gate_audit.py",
        "inputs": {
            "disasm_json": str(DISASM.relative_to(ROOT)).replace("\\", "/"),
            "report": str(REPORT.relative_to(ROOT)).replace("\\", "/"),
        },
        "counts": {
            "disasm_checks": len(checks), "anchors": len(anchors),
            "strings": len(strings), "audit_checks": len(all_checks),
        },
        "evidence_checks": evidence,
        "doc_checks": docs,
        "checks": all_checks,
        "all_pass": not failed,
        "failed_checks": failed,
    }
    AUDIT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
    print("[INFO] 输出 %s" % AUDIT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.67 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.67 审计：%d 项 checks 全 PASS" % len(all_checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
