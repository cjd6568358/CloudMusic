#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.57 守护 / F4 A/B 编译期预检的独立审计（离线、只读）。

防把「编译通过」偷换成「A/B 已执行 / 环节 B 已证实」，
防 P0 残留变体、防 Windows 启动器缺陷回潮、防凭据落盘。
产出 analysis/l3-f4-ab-compile-precheck-audit.json。
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
T = ROOT / "tools"
PRECHECK_JSON = A / "l3-f4-ab-compile-precheck.json"
PRECHECK_TOOL = T / "l3_f4_ab_compile_precheck.py"
REPORT = A / "l3-f4-ab-compile-precheck-report.md"
AB_TOOL = T / "l3_f4_kind_slot_ab.py"
AB_PLAN = A / "l3-f4-kind-slot-ab-plan.json"
AB_AUDIT = A / "l3-f4-kind-slot-ab-audit.json"
AB_RESULT = A / "l3-f4-kind-slot-ab.json"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
FP = ROOT / "assets/ENGINE-FINGERPRINT"
CONTRACTS = T / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-ab-compile-precheck-audit.json"

CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    pc = json.loads(read(PRECHECK_JSON) or "{}")
    plan = json.loads(read(AB_PLAN) or "{}")
    ab_audit = json.loads(read(AB_AUDIT) or "{}")
    ck = pc.get("checks", {})
    ev = pc.get("evidence", {})
    shape = ev.get("variant_shape", {})
    report = read(REPORT)
    ab_src = read(AB_TOOL)
    p0 = read(P0)
    fp = read(FP)

    so_digest = hashlib.sha256(SO.read_bytes()).hexdigest() if SO.is_file() else ""
    fp_line = next((ln.split()[0] for ln in fp.splitlines()
                    if ln.strip().endswith("libNMCommonCache.so")), "")
    try:
        diff = subprocess.run(
            ["git", "diff", "--", str(P0.relative_to(ROOT)).replace("\\", "/")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=30).stdout.strip()
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        diff, head = "?", ""

    checks = {
        "precheck_json_present": PRECHECK_JSON.is_file() and bool(pc),
        "precheck_all_pass": (
            pc.get("all_pass") is True and len(ck) == 9
            and all(ck.values()) and not pc.get("failed_checks")),
        "variant_compiles_offline": ck.get("variant_compiles_offline") is True,
        "baseline_recompiles_offline": ck.get("baseline_recompiles_offline") is True,
        "variant_is_single_precise_change": (
            shape.get("old_call_absent") is True
            and shape.get("new_call_present") is True
            and shape.get("single_const_occurrence") is True
            and shape.get("byte_delta", 0) > 0),
        "source_restored_byte_exact": (
            ck.get("source_restored_byte_exact") is True
            and ev.get("source_restored") is True),
        "git_diff_clean_now": diff == "",
        "no_variant_left_in_p0": (
            'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "windows_gradle_launcher_fixed": (
            'GRADLE.with_name("gradlew.bat")' in ab_src
            and 'os.name == "nt"' in ab_src),
        "restored_judgement_independent": (
            "baseline_rebuild_ok" in ab_src
            and "return 0 if (restored and baseline_rebuild_ok) else 1" in ab_src),
        "dry_run_anchors_still_true": all(
            plan.get("anchor_checks", {}).values()) and len(
                plan.get("anchor_checks", {})) == 4,
        "ab_audit_still_14_pass": (
            ab_audit.get("all_pass") is True
            and len(ab_audit.get("checks", {})) == 14),
        # 12 §61.59：A/B 已执行，改为验收「结果如实登记」。
        "ab_result_honest": (
            AB_RESULT.is_file()
            and ck.get("ab_result_honest") is True
            and ab_audit.get("status", {}).get("ab_executed") is True
            and json.loads(read(AB_RESULT)).get("primary_criterion_met") is False
            and json.loads(read(AB_RESULT)).get("p0_restored") is True),
        "report_present_and_honest": (
            REPORT.is_file()
            and "零净改动" in report
            and "WinError 193" in report
            and "**F4 仍判 BLOCKED**" in report
            and "NOT RUN" in report
            and "临时改过" in report
            and "已实证" in report),
        "so_matches_engine_fingerprint": bool(so_digest) and so_digest == fp_line,
        "engine_compat_test_not_backfilled": "engine_compat_test" not in fp,
        "no_credentials_in_evidence": not CRED_PATTERN.search(
            read(PRECHECK_JSON) + report + read(PRECHECK_TOOL)),
        "contract_guard_present": "12 §61.57 守护" in read(CONTRACTS),
        "delivery12_has_61_57": "61.57" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.57 / F4 A/B 编译期预检的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_ab_compile_precheck_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "ab_variant_b_compiles": True,
            "windows_launcher_bug_fixed": True,
            "p0_net_change": "ZERO（临时变体已逐字节恢复）",
            "ab_executed": True,
            "f4_status": "BLOCKED（A/B 主判据未达成，待第二卡点定位）",
            "primary_criterion": "NOT MET（25s / 45s 两次均未达成）",
        },
        "honest_notes": [
            "本批临时改过生产源码用于编译验证，最终零净改动；不写「零代码改动」。",
            "编译通过只排除语法/类型/依赖/启动器问题，不证明变体真机行为，更不证明 §61.56 环节 B 因果链。",
            "§61.59：A/B 已执行两次（25s/45s）+ 基线对照，主判据均未达成；自动进曲 3 首 / seek / ENDED 仍 NOT RUN。",
            "A3 仍 G09 PARTIAL、舱不冻结；F3 矩阵 14 格未改写。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.57 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.57 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
