#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.63 守护 / F4 vfile 落盘一手取证 + 清缓存对照 + 变体 D 受阻的离线复核。

防伪造三件事：
  1. 防把清缓存实验的 RULED_OUT 写成「根因已定位 / 陈旧切片是根因」；
  2. 防把变体 D 两次「无有效判据」写成「假设证伪 / PASS / 崩溃」；
  3. 防伪造 P0 恢复、防把 F4 写成已解决。
产出 analysis/l3-f4-vfile-diagnosis-audit.json。
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
LOGS = A / "l3-f4-logs"
IDX_BEFORE = A / "l3-f4-vfile-index-before.json"
IDX_AFTER = A / "l3-f4-vfile-index-after.json"
RESET_VERDICT = A / "l3-f4-cache-reset-verdict.json"
RESET_PROBE = A / "l3-f4-cache-reset-probe.json"
RUN1 = A / "l3-f4-data-kind-ab-run1.json"
RUN2 = A / "l3-f4-data-kind-ab-run2.json"
RESET_LOG = LOGS / "vfile-commit-20261110-114804.txt"
RUN1_LOG = LOGS / "vfile-commit-20261110-115354.txt"
REPORT = A / "l3-f4-vfile-diagnosis-report.md"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
PROBE_TOOL = ROOT / "tools" / "l3_f4_vfile_commit_probe.py"
P0 = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "example" / \
    "cloudmusic" / "enginelegacy" / "UrlDataRequest.kt"
OUT = A / "l3-f4-vfile-diagnosis-audit.json"

CRED = re.compile(r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p) or "{}")
    except (ValueError, TypeError):
        return {}


def trunk_total(groups: list) -> int:
    return sum(t.get("size", 0)
               for group in groups for s in group
               for t in s.get("trunks", []))


def main() -> int:
    ib = jload(IDX_BEFORE)
    ia = jload(IDX_AFTER)
    rv = jload(RESET_VERDICT)
    rp = jload(RESET_PROBE)
    r1 = jload(RUN1)
    r2 = jload(RUN2)
    report = read(REPORT)
    reset_log = read(RESET_LOG)
    run1_log = read(RUN1_LOG)

    ib_targets = ib.get("target_entries", [])
    ia_targets = ia.get("target_entries", [])
    ib_flat = [[{"trunks": s.get("trunks", [])} for s in
                e.get("index", {}).get("slices", []) if s.get("trunks")]
               for e in ib_targets]
    ia_flat = [[{"trunks": s.get("trunks", [])} for s in
                e.get("index", {}).get("slices", []) if s.get("trunks")]
               for e in ia_targets]
    ib_bytes = trunk_total(ib_flat)
    ia_bytes = trunk_total(ia_flat)

    # 其它格式的索引里存在「全满 1 MiB trunk」→ vfile 落盘机制本身能工作。
    other_full = False
    for e in ib.get("entries", []):
        idx = e.get("index", {})
        if idx.get("file_size") == 16573387:
            continue
        trunks = [t for s in idx.get("slices", []) for t in s.get("trunks", [])]
        if any(t.get("size", 0) >= 1048576 for t in trunks):
            other_full = True
            break

    r1_main = r1.get("main", {})
    r1_reg = r1.get("regression", [])
    r1_no_chain = (
        not r1_main.get("codec") and not r1_main.get("write_start_play_nerr")
        and not r1_main.get("commit_values")
        and all(not c.get("codec") for c in r1_reg))

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
        "index_before_present": bool(ib) and ib.get("index_count", 0) >= 1,
        "index_before_single_target": len(ib_targets) == 1,
        "index_before_trunk_exactly_368640": ib_bytes == 368640,
        "index_before_target_file_size": (
            ib_targets[0].get("index", {}).get("file_size") == 16573387
            if ib_targets else False),
        "other_formats_have_full_trunks": other_full,
        "index_after_present": bool(ia),
        "index_after_trunk_exactly_180224": ia_bytes == 180224,
        "cache_reset_verdict_present": bool(rv),
        "cache_reset_ruled_out": "RULED_OUT" in rv.get("verdict", ""),
        "cache_reset_trunk_grew_false": rv.get("criteria", {}).get("trunk_grew") is False,
        "cache_reset_bytes_recorded": (
            rv.get("cache_indexes", {}).get("before_trunk_bytes") == 368640
            and rv.get("cache_indexes", {}).get("after_trunk_bytes") == 180224),
        "cache_reset_log_present": len(reset_log.splitlines()) >= 1000,
        "cache_reset_new_slice_not_use_old": (
            "NMSliceFile Open Init :d6a57b" in reset_log
            and "Use Old:d6a57b" not in reset_log),
        "cache_reset_start_offset_zero": "Start id=1 offset=0 length=1048576" in reset_log,
        "cache_reset_commit_180224": "commit :  180224" in reset_log,
        "cache_reset_minus_103": "-103" in reset_log,
        "cache_reset_no_write_start": "Write start play" not in reset_log,
        "variant_run1_present": bool(r1) and r1.get("p0_restored") is True,
        "variant_run1_no_valid_chain": r1_no_chain,
        "variant_run1_prepare_seen": "prepare trackId=d6a57b" in run1_log,
        "variant_run1_log_no_create_handler": (
            "createDataRequestHandler" not in run1_log),
        "variant_run2_present": bool(r2),
        "variant_run2_device_offline": (
            r2.get("status") == "BLOCKED_DEVICE_OFFLINE"
            and "device offline" in json.dumps(r2, ensure_ascii=False)),
        "variant_run2_install_failed": (
            r2.get("steps", {}).get("install", "").startswith("rc=1")),
        "variant_run2_probes_not_run": (
            r2.get("steps", {}).get("primary_probe", "").startswith("NOT RUN")),
        "variant_run2_restored": (
            r2.get("restore", {}).get("source_restored_byte_equal") is True
            and r2.get("restore", {}).get("baseline_rebuild_ok") is True),
        "p0_git_diff_clean": diff == "",
        "report_has_ruled_out": "RULED_OUT" in report and "陈旧切片复用不是 F4 根因" in report,
        "report_has_no_valid_criterion": "NO_VALID_CRITERION" in report,
        "report_rejects_false_claims": (
            "不写" in report and "F4 仍判 BLOCKED" in report
            and "未证实" in report),
        "probe_tool_has_out_and_warmup": (
            "--out" in read(PROBE_TOOL) and "--warmup" in read(PROBE_TOOL)),
        "no_credentials_in_evidence": not CRED.search(
            read(IDX_BEFORE) + read(RESET_VERDICT) + read(RUN2) + report),
        "contract_guard_61_63_present": "12 §61.63 守护" in read(CONTRACTS),
        "delivery12_has_61_63": "61.63" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    status = {
        "vfile_index_evidence_locked": True,
        "stale_slice_hypothesis": "RULED_OUT",
        "kind_mismatch_hypothesis": "UNVERIFIED",
        "variant_d_verdict": "NO_VALID_CRITERION",
        "variant_d_runs": 2,
        "variant_d_blockers": ["run1 冷启动注入过早 / 判据缺陷", "run2 设备离线安装失败"],
        "device_offline_at_end": True,
        "p0_restored": diff == "",
        "fix_implemented": False,
        "fix_verified": False,
        "f4_status": "BLOCKED",
        "auto_advance_3_tracks": "NOT RUN",
        "seek_and_ended": "NOT RUN",
        "a3_g09": "PARTIAL",
        "engine_bay_frozen": False,
    }
    data = {
        "method": "12 §61.63 / F4 vfile 落盘取证 + 清缓存对照 + 变体 D 受阻的离线复核",
        "generated_by": "tools/l3_f4_vfile_diagnosis_audit.py",
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence_summary": {
            "index_before_trunk_bytes": ib_bytes,
            "index_after_trunk_bytes": ia_bytes,
            "other_formats_full_trunk_seen": other_full,
            "cache_reset_verdict": rv.get("verdict"),
            "variant_run1_verdict_raw": r1.get("verdict"),
            "variant_run2_status": r2.get("status"),
        },
        "status": status,
        "honest_notes": [
            "清缓存实验判据已修正（比较落盘总字节数，非 JSON 是否变化），RULED_OUT 为离线重判结果。",
            "变体 D 两次均无有效判据：不得写假设证伪、不得写 PASS、不得写崩溃。",
            "kind 错配只是假说，未证实；native 级反汇编定位仍待做。",
            "F4 仍 BLOCKED；自动进曲 3 首 / seek / ENDED 仍 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
            "P0 引擎舱零净改动；删除的仅为可重建的 app 私有 vfile 缓存。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.63 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.63 审计：%d 项 checks 全 PASS；陈旧切片假说 RULED_OUT、"
          "变体 D NO_VALID_CRITERION、P0 零净改动，F4 仍 BLOCKED" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
