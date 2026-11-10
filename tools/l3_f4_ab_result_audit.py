#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.59 守护 / F4 kind 槽 A/B 真机结果的独立审计（离线、只读）。

防把「环节 B 部分成立」写成 PASS / 证伪 / F4 已解决，
防伪造 P0 恢复、防把 NOT RUN 的连播判据写成已测，
并用 §61.59 修正后的重复模式判据离线重判三轮日志。
产出 analysis/l3-f4-ab-result-audit.json。
"""
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import l3_f4_kind_slot_ab as ab  # noqa: E402

A = ROOT / "analysis"
RESULT = A / "l3-f4-kind-slot-ab.json"
RUN1 = A / "l3-f4-ab-run1-25s.json"
RUN2 = A / "l3-f4-ab-run2-45s.json"
CONTROL = A / "l3-f4-ab-control-baseline.json"
REPORT = A / "l3-f4-ab-result-report.md"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"
OUT = A / "l3-f4-ab-result-audit.json"

CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def load(p: Path) -> dict:
    return json.loads(read(p) or "{}")


def primary(d: dict) -> dict:
    return next((c for c in d.get("cells", [])
                 if c.get("tag") == "ksab-m4a-remote"), {})


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
    r1, r2, cur = load(RUN1), load(RUN2), load(RESULT)
    ctl = load(CONTROL)
    p1, p2, pc = primary(r1), primary(r2), primary(cur)
    cs = ctl.get("starts", [])
    report = read(REPORT)
    p0 = read(P0)

    try:
        diff = subprocess.run(
            ["git", "diff", "--", str(P0.relative_to(ROOT)).replace("\\", "/")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=30).stdout.strip()
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        diff, head = "?", ""

    runs = (r1, r2)
    primaries = (p1, p2)
    checks = {
        "ab_result_present": RESULT.is_file() and bool(cur),
        "two_runs_backed_up": RUN1.is_file() and RUN2.is_file() and bool(r1) and bool(r2),
        "baseline_control_present": CONTROL.is_file() and bool(ctl),
        "primary_criterion_not_met_all_runs": all(
            d.get("primary_criterion_met") is False for d in runs + (cur,)),
        "no_codec_in_all_variant_runs": all(
            p.get("codec") is None for p in primaries + (pc,)),
        "no_write_in_all_variant_runs": all(
            p.get("write") is False for p in primaries + (pc,)),
        "regression_all_pass_all_runs": all(
            d.get("regression_all_pass") is True for d in runs + (cur,)),
        "p0_restored_all_runs": all(
            d.get("p0_restored") is True and d.get("baseline_rebuild_ok") is True
            for d in runs + (cur,)),
        "auto_advance_not_faked": all(
            "NOT RUN" in d.get("auto_advance_3_tracks", "") for d in runs + (cur,)),
        "variant_eliminated_repeated_range": all(
            not ab.repeated_range(p.get("starts", [])) for p in primaries + (pc,)),
        "baseline_control_has_repeated_range": ab.repeated_range(cs),
        "two_runs_reproducible": (
            p1.get("starts") == p2.get("starts") and p1.get("on_end") == p2.get("on_end")),
        "new_predicate_correct": (
            callable(ab.repeated_range)
            and ab.repeated_range(cs) is True
            and ab.repeated_range(p2.get("starts", [])) is False),
        "report_present_and_honest": (
            REPORT.is_file()
            and "F4 仍判 BLOCKED" in report
            and "部分成立" in report
            and "第二卡点" in report
            and "NOT RUN" in report
            and "45 s" in report),
        "p0_git_diff_clean": (
            diff == ""
            and 'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "no_credentials_in_evidence": not CRED_PATTERN.search(
            read(RESULT) + read(RUN1) + read(RUN2) + read(CONTROL) + report),
        "contract_guard_present": "12 §61.59 守护" in read(CONTRACTS),
        "delivery12_has_61_59": "61.59" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.59 / F4 kind 槽 A/B 真机结果的独立审计（离线、只读）",
        "generated_by": "tools/l3_f4_ab_result_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "ab_executed": True,
            "serial": cur.get("serial"),
            "runs": 2,
            "wait_s": [25, 45],
            "primary_criterion_met": False,
            "verdict": "PARTIAL（环节 B 部分成立、非充分原因）",
            "variant_effect": "消除了 id>=3 同区间重复请求（4 个 Start → 2 个）",
            "second_blocker": "fMP4 多 Range × normal mode vfile 落盘 / demuxer probe 数据不足（待 native 级定位）",
            "p0_restored": True,
            "f4_status": "BLOCKED",
            "auto_advance_3_tracks": "NOT RUN",
        },
        "evidence": {
            "variant_starts_run1": p1.get("starts", []),
            "variant_starts_run2": p2.get("starts", []),
            "baseline_control_starts": cs,
            "repeated_range_predicate": {
                "baseline": ab.repeated_range(cs),
                "variant_run2": ab.repeated_range(p2.get("starts", [])),
            },
        },
        "honest_notes": [
            "环节 B 部分成立（错误完成通知是重复请求的成因）≠ 整条因果链成立；消除重试后仍无 codec。",
            "不得写「假设被证伪」「F4 已解决」「m4a 已支持」「全格式已支持」。",
            "两个 m4a 是人工截取/重排 fMP4 片段；F3 矩阵 14 格未改写；自动进曲 3 首 / seek / ENDED 仍 NOT RUN。",
            "A3 仍 G09 PARTIAL、舱不冻结；P0 零净改动、设备前置已恢复。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.59 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.59 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
