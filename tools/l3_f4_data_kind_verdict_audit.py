#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.64 守护 / 变体 D（OnDataRequestData kind 1→2）真机裁决的离线复核。

防伪造：防把「有效负结果」写成「无有效判据 / 假设未测」，
防把回归 PASS 写成主判据 PASS，防伪造 P0 恢复、防写 F4 已解决。
产出 analysis/l3-f4-data-kind-verdict-audit.json。
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
LOGS = A / "l3-f4-logs"
RUN3 = A / "l3-f4-data-kind-ab-run3.json"
MAIN_JSON = A / "l3-f4-data-kind-ab-main.json"
MP3_JSON = A / "l3-f4-data-kind-ab-mp3.json"
WAV_JSON = A / "l3-f4-data-kind-ab-wav.json"
REPORT = A / "l3-f4-data-kind-verdict-report.md"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
P0 = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "example" / \
    "cloudmusic" / "enginelegacy" / "UrlDataRequest.kt"
OUT = A / "l3-f4-data-kind-verdict-audit.json"

RE_COMMIT = re.compile(r"commit :\s*(\d+)")
RE_JUMP = re.compile(r"offset:327895, readed_len:0, result:-2")
RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(\d+)")
CRED = re.compile(r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p) or "{}")
    except (ValueError, TypeError):
        return {}


def log_of(doc: dict) -> str:
    rel = doc.get("log_file", "")
    return read(ROOT / rel) if rel else ""


def main() -> int:
    r3 = jload(RUN3)
    main_doc = jload(MAIN_JSON)
    mp3_doc = jload(MP3_JSON)
    wav_doc = jload(WAV_JSON)
    report = read(REPORT)
    main_log = log_of(main_doc)
    mp3_log = log_of(mp3_doc)
    wav_log = log_of(wav_doc)

    commits = [int(x) for x in RE_COMMIT.findall(main_log)]
    main_writes = RE_WRITE.findall(main_log)
    mp3_writes = RE_WRITE.findall(mp3_log)
    wav_writes = RE_WRITE.findall(wav_log)
    mp3_codec = RE_CODEC.findall(mp3_log)
    wav_codec = RE_CODEC.findall(wav_log)

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
        "run3_result_present": bool(r3) and r3.get("serial") == "10.0.0.2:41761",
        "run3_verdict_not_met": "NOT_MET" in r3.get("verdict", ""),
        "run3_primary_criterion_false": r3.get("primary_criterion_met") is False,
        "run3_regression_all_pass": r3.get("regression_all_pass") is True,
        "run3_p0_restored": r3.get("p0_restored") is True,
        "run3_baseline_rebuild_ok": r3.get("baseline_rebuild_ok") is True,
        "run3_chain_started": r3.get("main", {}).get("chain_started") is True,
        "run3_warmup_4": r3.get("main", {}).get("warmup_seconds") == 4.0,
        "main_log_line_count": len(main_log.splitlines()) >= 3000,
        "main_handler_created": "createDataRequestHandler -> NEW UrlDataRequest" in main_log,
        "main_new_slice_no_use_old": (
            "NMSliceFile Open Init :d6a57b" in main_log
            and "Use Old:d6a57b" not in main_log),
        "main_start_offset_zero": "Start id=1 offset=0 length=1048576" in main_log,
        "main_commit_only_16384": commits == [16384, 16384],
        "main_jump_read_fails_5": len(RE_JUMP.findall(main_log)) >= 5,
        "main_fallback_to_16384": "Start id=7 offset=16384" in main_log,
        "main_no_codec": not RE_CODEC.findall(main_log),
        "main_no_write_start": not main_writes,
        "main_minus_103_present": "-103" in main_log,
        "regression_mp3_codec_and_write": (
            "mp3" in mp3_codec and "0" in mp3_writes and "-103" not in mp3_log),
        "regression_wav_codec_and_write": (
            "pcm_s16le" in wav_codec and "0" in wav_writes
            and "-103" not in wav_log),
        "regression_wav_commit_1048576": "commit :  1048576" in wav_log,
        "kind1_vs_kind2_commit_contrast": (
            r3.get("main", {}).get("commit_values") == [16384, 16384]),
        "report_has_falsified": (
            REPORT.is_file() and "修复预测被证伪" in report
            and "有效判据成立" in report),
        "report_points_to_write_chain": (
            "OnWriteNetData" in report and "NMSliceFile::Write" in report),
        "report_no_false_claims": (
            "F4 仍判 BLOCKED" in report
            and "不写" in report and "NOT RUN" in report),
        "p0_git_diff_clean": diff == "",
        "no_credentials_in_evidence": not CRED.search(
            read(RUN3) + read(MAIN_JSON) + report),
        "contract_guard_61_64_present": "12 §61.64 守护" in read(CONTRACTS),
        "delivery12_has_61_64": "61.64" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    status = {
        "variant_d_verdict": "FALSIFIED_EFFECTIVE_NEGATIVE",
        "primary_criterion_met": False,
        "regression_all_pass": True,
        "chain_started": True,
        "commit_kind1_baseline": 180224,
        "commit_kind2_variant": 16384,
        "kind_slot_affects_vfile_write": True,
        "root_cause_direction": (
            "OnWriteNetData / NMSliceFile::Write / commit 记账的 native 落盘链路"
            "（非 Java 侧把 kind 改成 2）"),
        "fix_implemented": False,
        "fix_verified": False,
        "f4_status": "BLOCKED",
        "auto_advance_3_tracks": "NOT RUN",
        "seek_and_ended": "NOT RUN",
        "device_endpoint": "10.0.0.2:41761",
        "device_can_disconnect": True,
        "p0_restored": diff == "",
        "a3_g09": "PARTIAL",
        "engine_bay_frozen": False,
    }
    data = {
        "method": "12 §61.64 / 变体 D 真机裁决的离线复核（只读、零依赖）",
        "generated_by": "tools/l3_f4_data_kind_verdict_audit.py",
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence_extracted": {
            "main_commit_values": commits,
            "main_jump_read_failures": len(RE_JUMP.findall(main_log)),
            "main_open_codec": RE_CODEC.findall(main_log),
            "main_write_start_nerr": main_writes,
            "mp3_codec": mp3_codec,
            "mp3_write_start_nerr": mp3_writes,
            "wav_codec": wav_codec,
            "wav_write_start_nerr": wav_writes,
        },
        "status": status,
        "honest_notes": [
            "本轮是有效判据（链路完整展开、回归两格真实出声），不是 §61.63 run1 的空跑。",
            "证伪的是「kind 改成 2 能修复落盘」这一具体预测；kind 槽确实影响落盘量，方向相反。",
            "根因方向转移到 native 落盘链路，具体函数仍待反汇编定位。",
            "F4 仍 BLOCKED；自动进曲 3 首 / seek / ENDED 仍 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
            "P0 引擎舱零净改动、零发布基线变化；变体只临时存在，finally 无条件恢复。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.64 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.64 审计：%d 项 checks 全 PASS；变体 D 有效负结果"
          "（kind=2 → commit 16384、无 codec、回归 PASS），F4 仍 BLOCKED"
          % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
