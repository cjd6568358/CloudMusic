#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.54 守护 / F4 native 侧一手二进制复核的独立审计（离线、只读）。

职责：防止把「§59.2 分支语义已被二进制复核」偷换成
「§61.52 的 kind 槽因果假设已证实」，并防止借本批名义提前改 P0、
回填引擎指纹、伪造 A/B 结果或把未跑的连播判据写成 PASS。

产出 analysis/l3-f4-disasm-verify-audit.json。
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
VERIFY_JSON = A / "l3-f4-disasm-verify.json"
VERIFY_TOOL = T / "l3_f4_disasm_verify.py"
REPORT = A / "l3-f4-disasm-verify-report.md"
AB_AUDIT = A / "l3-f4-kind-slot-ab-audit.json"
AB_RESULT = A / "l3-f4-kind-slot-ab.json"  # §61.59：实际 A/B 结果文件
ROOTCAUSE = A / "l3-f4-kind-slot-rootcause.md"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
FP = ROOT / "assets/ENGINE-FINGERPRINT"
CONTRACTS = T / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-disasm-verify-audit.json"

# 通用凭据模式：只匹配「键 = 明文值」形态，不在工具里写入任何真实口令。
CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    v = json.loads(read(VERIFY_JSON) or "{}")
    ck = v.get("checks", {})
    ev = v.get("evidence", {})
    rel = ev.get("got_relocations", {})
    tool = read(VERIFY_TOOL)
    report = read(REPORT)
    p0 = read(P0)
    fp = read(FP)
    ab = json.loads(read(AB_AUDIT) or "{}")
    ab_status = ab.get("status", {})

    so_digest = hashlib.sha256(SO.read_bytes()).hexdigest() if SO.is_file() else ""
    fp_line = next((ln.split()[0] for ln in fp.splitlines()
                    if ln.strip().endswith("libNMCommonCache.so")), "")

    try:
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head = ""

    checks = {
        "verify_json_present": VERIFY_JSON.is_file() and bool(v),
        "verify_all_pass": v.get("all_pass") is True and not v.get("failed_checks"),
        "verify_checks_count_10": len(ck) == 10 and all(ck.values()),
        "branch_semantics_evidence": (
            ev.get("cmp_kind_line", "").startswith("0xbf08")
            and "cmp" in ev.get("cmp_kind_line", "")
            and "#0xbfa0" in ev.get("branch_line", "")
            and "#0xed0" in ev.get("got_kind2_load", "")
            and "#0xee8" in ev.get("got_other_load", "")
            and ev.get("queue_call_sites") == ["0xbf7c", "0xc004"]),
        "got_relocs_map_exact_symbols": (
            rel.get("0x4bed0", {}).get("symbol", "").endswith(
                "PreloadDoneTaskOnWriteThreadEv")
            and rel.get("0x4bee8", {}).get("symbol", "").endswith(
                "OnFlushNetDataEv")
            and rel.get("0x4bed0", {}).get("reloc_type") == 1025
            and rel.get("0x4bee8", {}).get("reloc_type") == 1025),
        "report_present_and_honest": (
            REPORT.is_file()
            and "**F4 仍判 BLOCKED**" in report
            and "仍待验证" in report
            and "一手二进制证据" in report
            and "NOT RUN" in report),
        "rootcause_still_marked_hypothesis": (
            "高置信假设" in read(ROOTCAUSE)
            and "BLOCKED" in read(ROOTCAUSE)),
        # 12 §61.59：A/B 已执行，改为验收结果如实登记。
        "ab_result_honest": (
            AB_RESULT.is_file()
            and ab_status.get("ab_executed") is True
            and json.loads(read(AB_RESULT)).get("primary_criterion_met") is False
            and json.loads(read(AB_RESULT)).get("p0_restored") is True),
        "p0_baseline_unchanged": (
            'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "so_matches_engine_fingerprint": bool(so_digest) and so_digest == fp_line,
        "engine_compat_test_not_backfilled": "engine_compat_test" not in fp,
        # 只检查「真实调用特征」；docstring 里「不连 adb」这类说明文字不算。
        "verify_tool_is_readonly": (
            VERIFY_TOOL.is_file()
            and not re.search(r"(?i)\b(subprocess|os\.system|urlopen|requests\.)\b", tool)
            and "adb.exe" not in tool
            and "install -r" not in tool
            and "OPENLIST_" not in tool),
        "no_credentials_in_evidence": (
            not CRED_PATTERN.search(tool + report + read(VERIFY_JSON))),
        "contract_guard_present": "12 §61.54 守护" in read(CONTRACTS),
        "delivery12_has_61_54": "61.54" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, val in checks.items() if not val]
    data = {
        "method": "12 §61.54 / F4 native 二进制复核的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_disasm_verify_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "native_branch_semantics": "CONFIRMED_BY_BINARY_DISASM",
            "kind_slot_causal_hypothesis": "UNVERIFIED_PENDING_AB",
            "ab_executed": False,
            "f4_status": "BLOCKED（待设备上线执行 --apply）",
            "p0_modified": False,
        },
        "honest_notes": [
            "本守护只确认 §59.2 分支语义的一手证据，不证明 Java 侧传 requestId 与 fMP4 卡点的因果。",
            "§59.2 exp6 反例、竞品同样传 requestId 却可用，原因未实证；ape×remote / aiff 不被该分支解释。",
            "A3 仍 G09 PARTIAL、舱不冻结；F3 矩阵 14 格未改写；自动进曲 3 首 / seek / ENDED 如实 NOT RUN。",
            "纯离线、只读、零生产代码改动、零设备操作、零新增依赖。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.54 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.54 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
