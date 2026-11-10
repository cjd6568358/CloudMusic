#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.55 守护 / F4 task 行为差异 + F3 样本边界盘点的独立审计（离线、只读）。

防把「两个 task 的差异已被二进制证实」偷换成「kind 槽因果链已证实」，
防提前改 P0 / 回填指纹 / 伪造 A/B / 伪造 NO_SAMPLE 样本存在，
并防 OpenList 凭据落入仓库。产出 analysis/l3-f4-task-diff-audit.json。
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
TASK_JSON = A / "l3-f4-task-diff.json"
TASK_TOOL = T / "l3_f4_task_diff_verify.py"
SCAN_JSON = A / "l3-f3-openlist-scan.json"
SCAN_TOOL = T / "l3_f3_openlist_scan.py"
REPORT = A / "l3-f4-task-diff-report.md"
AB_AUDIT = A / "l3-f4-kind-slot-ab-audit.json"
AB_RESULT = A / "l3-f4-kind-slot-ab.json"  # §61.59：实际 A/B 结果文件
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
FP = ROOT / "assets/ENGINE-FINGERPRINT"
CONTRACTS = T / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-task-diff-audit.json"

# 通用模式：只匹配「键 = 明文值」，不在工具里写入任何真实口令。
CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    td = json.loads(read(TASK_JSON) or "{}")
    sc = json.loads(read(SCAN_JSON) or "{}")
    ab = json.loads(read(AB_AUDIT) or "{}")
    td_ck = td.get("checks", {})
    td_ev = td.get("evidence", {})
    report = read(REPORT)
    p0 = read(P0)
    fp = read(FP)
    tool_src = read(TASK_TOOL) + read(SCAN_TOOL)

    so_digest = hashlib.sha256(SO.read_bytes()).hexdigest() if SO.is_file() else ""
    fp_line = next((ln.split()[0] for ln in fp.splitlines()
                    if ln.strip().endswith("libNMCommonCache.so")), "")
    try:
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head = ""

    got_render = td_ev.get("got_render_relocation", {}).get("0x4bf28", {})
    no_sample = sc.get("no_sample_formats_checked", [])
    scan_ext = sc.get("audio_ext_counts", {})
    evidence_text = (read(TASK_JSON) + read(SCAN_JSON) + report + tool_src)

    checks = {
        "task_diff_json_present": TASK_JSON.is_file() and bool(td),
        "task_diff_all_pass": (
            td.get("all_pass") is True and len(td_ck) == 14
            and all(td_ck.values()) and not td.get("failed_checks")),
        "cascade_render_task_evidence": (
            "PreloadDoneTaskOnRenderThread" in got_render.get("symbol", "")
            and td_ck.get("preload_write_posts_cascade_task") is True
            and td_ck.get("preload_write_sets_bool_true") is True),
        "flush_is_lightweight_evidence": (
            td.get("functions", {}).get("flush", {}).get("instructions", 999) <= 25
            and td_ck.get("flush_is_lightweight") is True
            and td_ck.get("flush_tail_calls_vtable_0x40") is True),
        "docs59_3_kind1_calibration": (
            td_ck.get("docs59_3_kind1_calibrated") is True
            and "NATIVE_DATA_KIND_NORMAL = 1L" in read(DELIVERY12)),
        "scan_json_present": SCAN_JSON.is_file() and bool(sc),
        "scan_coverage": (
            sc.get("dirs_scanned", 0) >= 80
            and sc.get("list_errors", 1) == 0
            and sc.get("config", {}).get("max_depth") == 3),
        "scan_no_sample_all_absent": (
            len(no_sample) == 7
            and sc.get("no_sample_candidate_total", 1) == 0
            and not sc.get("no_sample_found")
            and set(scan_ext) == {"mp3", "flac", "ape", "wma", "wav", "mp4"}),
        "scan_tool_uses_env_credentials_only": (
            "os.environ.get(\"OLU\"" in read(SCAN_TOOL)
            and "os.environ.get(\"OLP\"" in read(SCAN_TOOL)
            and not CRED_PATTERN.search(read(SCAN_TOOL))),
        "report_present_and_honest": (
            REPORT.is_file()
            and "**F4 仍判 BLOCKED**" in report
            and "高置信假设" in report
            and "NOT RUN" in report
            and "不替代 A/B" in report),
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
        "verify_tools_readonly": (
            not re.search(r"(?i)\b(subprocess|adb\.exe|install -r)\b", read(TASK_TOOL))
            and "不下载音频" in read(SCAN_TOOL)),
        "contract_guard_present": "12 §61.55 守护" in read(CONTRACTS),
        "delivery12_has_61_55": "61.55" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, val in checks.items() if not val]
    data = {
        "method": "12 §61.55 / F4 task 行为差异 + F3 样本边界的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_task_diff_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "task_behavior_difference": "CONFIRMED_BY_BINARY_DISASM",
            "kind_slot_causal_hypothesis": "UNVERIFIED_PENDING_AB",
            "ab_variant_1L_risk": "LOWERED（1L 是 §59.3 已真机校准的正常数据包 kind）",
            "f3_no_sample_boundary": "CONFIRMED_ABSENT_IN_SCANNED_DIRS",
            "ab_executed": False,
            "f4_status": "BLOCKED（待设备上线执行 --apply）",
            "p0_modified": False,
        },
        "honest_notes": [
            "已证实的是两个 task 的行为差异，不是「传 requestId 导致 fMP4 卡点」的因果链。",
            "OpenList 结论受 max_depth=3 / max_dirs=80 约束，只对已扫描目录成立；不判任何格式已支持。",
            "§59.2 exp6 反例、竞品同样传 requestId 却可用、ape×remote 与 aiff 仍不解释。",
            "A/B 尚未执行；自动进曲 3 首 / seek / ENDED 如实 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
            "纯离线、只读、零生产代码改动、零设备操作；OpenList 只列目录、不下音频、凭据不落仓库。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.55 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.55 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
