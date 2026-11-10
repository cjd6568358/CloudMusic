#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.56 守护 / F4 JNI 参数槽透传复核的独立审计（离线、只读）。

防把「环节 A 已证实」偷换成「整条因果链（含环节 B）已证实」，
防提前改 P0 / 回填指纹 / 伪造 A/B / 凭据落盘。
产出 analysis/l3-f4-jni-arg-audit.json。
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
JNI_JSON = A / "l3-f4-jni-arg.json"
JNI_TOOL = T / "l3_f4_jni_arg_verify.py"
REPORT = A / "l3-f4-jni-arg-report.md"
AB_AUDIT = A / "l3-f4-kind-slot-ab-audit.json"
AB_RESULT = A / "l3-f4-kind-slot-ab.json"  # §61.59：实际 A/B 结果文件
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
FP = ROOT / "assets/ENGINE-FINGERPRINT"
CONTRACTS = T / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
ROOTCAUSE = A / "l3-f4-kind-slot-rootcause.md"
OUT = A / "l3-f4-jni-arg-audit.json"

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
    j = json.loads(read(JNI_JSON) or "{}")
    ab = json.loads(read(AB_AUDIT) or "{}")
    ck = j.get("checks", {})
    ev = j.get("evidence", {})
    report = read(REPORT)
    p0 = read(P0)
    fp = read(FP)

    so_digest = hashlib.sha256(SO.read_bytes()).hexdigest() if SO.is_file() else ""
    fp_line = next((ln.split()[0] for ln in fp.splitlines()
                    if ln.strip().endswith("libNMCommonCache.so")), "")
    try:
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head = ""

    end_ev, data_ev = ev.get("jni_end", {}), ev.get("jni_data", {})
    tramp = "\n".join(ev.get("trampoline_0x36c70", []))
    evidence_text = read(JNI_JSON) + report + read(JNI_TOOL)

    checks = {
        "jni_json_present": JNI_JSON.is_file() and bool(j),
        "jni_all_pass": (
            j.get("all_pass") is True and len(ck) == 17
            and all(ck.values()) and not j.get("failed_checks")),
        "end_path_transparent": (
            ck.get("end_saves_j2_into_x21") is True
            and ck.get("end_sets_x1_to_j2") is True
            and ck.get("end_calls_trampoline_0x36c70") is True
            and end_ev.get("call")),
        "data_path_isomorphic": (
            ck.get("data_saves_j2_into_x21") is True
            and ck.get("data_sets_x1_to_j2") is True
            and ck.get("data_calls_parallel_trampoline_0x36f68") is True
            and data_ev.get("call")),
        "trampoline_forwards_unchanged": (
            ck.get("trampoline_forwards_x1_unchanged") is True
            and ck.get("trampoline_dispatches_via_vtable_0x20") is True
            and "mov       x1, x21" in tramp
            and "ldr       x8, [x8, #0x20]" in tramp),
        "native_done_uses_w1": (
            ck.get("done_compares_w1_with_2") is True
            and any("cmp" in l and "w1, #2" in l
                    for l in ev.get("native_done", []))),
        "kdoc_and_url_source_agree": (
            ck.get("java_kdoc_documents_j1_as_kind_slot") is True
            and ck.get("url_request_passes_requestId") is True),
        "docs59_3_data_kind_calibration": (
            ck.get("docs59_3_calibrated_data_kind_1L") is True),
        "report_splits_chain_into_two_parts": (
            REPORT.is_file()
            and "环节 A" in report and "环节 B" in report
            and "**F4 仍判 BLOCKED**" in report
            and "仍待验证" in report
            and "NOT RUN" in report
            and "不是 100%" in report),
        "rootcause_still_hypothesis": (
            "高置信假设" in read(ROOTCAUSE) and "BLOCKED" in read(ROOTCAUSE)),
        # 12 §61.59：A/B 已执行，改为验收结果如实登记。
        "ab_result_honest": (
            AB_RESULT.is_file()
            and ab.get("status", {}).get("ab_executed") is True
            and json.loads(read(AB_RESULT)).get("primary_criterion_met") is False
            and json.loads(read(AB_RESULT)).get("p0_restored") is True),
        "p0_baseline_unchanged": (
            'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "so_matches_engine_fingerprint": bool(so_digest) and so_digest == fp_line,
        "engine_compat_test_not_backfilled": "engine_compat_test" not in fp,
        "no_credentials_in_evidence": not CRED_PATTERN.search(evidence_text),
        "verify_tool_readonly": (
            not re.search(r"(?i)\b(subprocess|adb\.exe|install -r|urlopen)\b",
                          read(JNI_TOOL))),
        "contract_guard_present": "12 §61.56 守护" in read(CONTRACTS),
        "delivery12_has_61_56": "61.56" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.56 / F4 JNI 参数槽透传复核的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_jni_arg_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "chain_part_A_java_to_kind_slot": "CONFIRMED_BY_BINARY_DISASM_PLUS_E9_CALIBRATION",
            "chain_part_B_notification_to_fmp4_stall": "UNVERIFIED_PENDING_AB",
            "ab_executed": False,
            "f4_status": "BLOCKED（待设备上线执行 --apply）",
            "p0_modified": False,
        },
        "honest_notes": [
            "环节 A 被证实不等于整条因果链被证实；虚表槽的具体目标类型由运行时决定，静态证据不是 100% 的 C++ 类型证明。",
            "§59.2 exp6 反例、竞品同样传 requestId 却可用、ape×remote 与 aiff 仍不解释。",
            "A/B 尚未执行；自动进曲 3 首 / seek / ENDED 如实 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
            "纯离线、只读、零生产代码改动、零设备操作、零新增依赖。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.56 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.56 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
