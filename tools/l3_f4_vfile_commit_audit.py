#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.62 守护 / F4 vfile commit 时序观测的离线复核（只读、零依赖）。

背景：§61.61 把第二卡点锁定为「vfile 流式落盘跟不上 fMP4 跳跃式
probe 读」，但挂账「落盘速度不足 vs commit/Range 记账语义」未区分，
且当时较短窗口（约 12 s / 45 s）判为「仍无 codec」。
本批 60 s 纯观测日志给出更完整的序列，本工具离线复核：
  - commit 是否恒定（排除速度假说）
  - 是否回退到连续提交点后打开 codec
  - codec 打开后是否真的出声（Write start / -103 / AudioTrack Release）
并给出结论修正，不回改任何历史证据文件。

防伪造：防把「Open codec」写成「已出声 / F4 已解决」、防把 commit 恒定
写成「落盘速度不足」、防把 §61.61 结论改写、防 P0 基线被改动。
"""
from __future__ import annotations

import json
import re
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
PROBE = A / "l3-f4-vfile-commit-probe.json"
REPORT = A / "l3-f4-vfile-commit-report.md"
AUDIT = A / "l3-f4-vfile-commit-audit.json"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
P0 = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "example" / \
    "cloudmusic" / "enginelegacy" / "UrlDataRequest.kt"
PROBE_TOOL = ROOT / "tools" / "l3_f4_vfile_commit_probe.py"

RE_TS = re.compile(r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)")
RE_PREPARE = re.compile(
    r"prepare trackId=\S+ remote=(\w+) url=(\S+) expectedLength=\d+")
RE_COMMIT = re.compile(r"commit :\s*(\d+)")
RE_FORCE = re.compile(r"WaitRequest offset:605007, length:\d+, signal_size:\d+, force:true")
RE_FALLBACK = re.compile(r"Start id=6 offset=368640 length=\d+")
RE_PRESeek = re.compile(r"PreSeekReadTaskRead offset :\s*35726.*out_len :\s*(\d+)")
RE_READ_FAIL = re.compile(r"offset:605007, readed_len:0, result:-2")
CRED = re.compile(r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def ts_of_line(line: str):
    m = RE_TS.search(line)
    if not m:
        return None
    try:
        # logcat 的 time 格式不含年份；补当前年份避免 strptime 的
        # 无年份解析歧义（同一天内求秒差，跨年边界不影响本批窗口）。
        return datetime.strptime(
            "%d-%s" % (datetime.now().year, m.group(1)),
            "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None


def secs(a, b) -> float | None:
    return round((b - a).total_seconds(), 3) if a and b else None


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
    probe = json.loads(read(PROBE) or "{}")
    log_rel = probe.get("log_file", "")
    log_path = ROOT / log_rel if log_rel else None
    text = read(log_path) if log_path and log_path.is_file() else ""
    report = read(REPORT)
    lines = text.splitlines()

    prepare = RE_PREPARE.search(text)
    commits = [int(x) for x in RE_COMMIT.findall(text)]
    force_n = len(RE_FORCE.findall(text))
    fails = len(RE_READ_FAIL.findall(text))
    codec_lines = [l for l in lines if "Open codec for aac" in l]
    write_lines = [l for l in lines if "Write start play" in l]
    status4 = [l for l in lines if "Play status 4" in l]
    m103 = [l for l in lines if "lasterr -103" in l or "arg2=-103" in l]
    release = [l for l in lines if "AudioTrack(0): Release" in l]
    start1 = next((l for l in lines if "Start id=1 offset=368640" in l), "")
    fallback = bool(RE_FALLBACK.search(text))
    preseek = RE_PRESeek.search(text)
    delay = secs(ts_of_line(start1), ts_of_line(codec_lines[0])) if codec_lines else None

    try:
        head = subprocess.run(
            ["git", "log", "-1", "--format=%h"], cwd=str(ROOT),
            capture_output=True, text=True, timeout=20).stdout.strip()
        diff = subprocess.run(
            ["git", "diff", "--", str(P0.relative_to(ROOT)).replace("\\", "/")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head, diff = "", "?"

    commit_constant = commits == [368640]
    codec_without_audio = bool(codec_lines) and not write_lines and bool(m103)
    fallback_evidence = fallback and preseek is not None
    speed_ruled_out = commit_constant and force_n >= 3 and fails >= 5

    checks = {
        "probe_json_present": bool(probe),
        "log_file_present_and_nonempty": bool(text) and len(lines) >= 1000,
        "probe_wait_60s": probe.get("wait_seconds") == 60.0,
        "probe_sample_selfconsistent": probe.get("sample") == "f3_m4a_complete.m4a",
        # 端点每次上线都会变，守护只校验格式，不钉死本批 IP（便于复跑）。
        "serial_present_and_wellformed": bool(
            re.fullmatch(r"\d+\.\d+\.\d+\.\d+:\d+", str(probe.get("serial", "")))),
        "single_remote_prepare_confirmed": (
            prepare is not None and prepare.group(1) == "true"
            and "f3_m4a_complete.m4a" in prepare.group(2)),
        "commit_only_368640": commit_constant,
        "commit_never_reached_605007": all(c < 605007 for c in commits),
        "force_retry_at_least_3": force_n >= 3,
        "jump_read_fail_at_least_5": fails >= 5,
        "probe_json_commit_distinct_single": (
            probe.get("evidence", {}).get("commit_distinct") == [368640]),
        "fallback_start_id6_to_368640": fallback_evidence,
        "preseek_refilled_data": preseek is not None and int(preseek.group(1)) > 0,
        "codec_opened_once": len(codec_lines) == 1,
        "codec_delay_about_33s": delay is not None and 25.0 <= delay <= 45.0,
        "no_write_start_play": len(write_lines) == 0,
        "no_play_status_4": len(status4) == 0,
        "minus_103_observed": len(m103) >= 1,
        "audiotrack_released": len(release) >= 1,
        "codec_opened_but_no_audio": codec_without_audio,
        "speed_hypothesis_ruled_out": speed_ruled_out,
        "probe_initial_verdict_preserved": (
            "UNEXPECTED_CODEC_OPENED" in probe.get("mechanism_verdict", "")),
        "report_has_refinement": (
            REPORT.is_file()
            and "F4 判据收窄到 `-103`" in report
            and "观察窗口不足" in report
            and "F4 仍判 BLOCKED" in report
            and "不改写" in report),
        "report_no_false_audio_claim": (
            "不等于**出声" in report and "Write start play` 0 次" in report),
        "p0_git_diff_clean": diff == "",
        "no_credentials_in_evidence": not CRED.search(
            read(PROBE) + text + report),
        "contract_guard_61_62_present": "12 §61.62 守护" in read(CONTRACTS),
        "delivery12_has_61_62": "61.62" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
        "probe_tool_present": PROBE_TOOL.is_file(),
    }
    failed = [k for k, v in checks.items() if not v]

    status = {
        "root_cause_direction": (
            "§61.61 用户裁决强化：vfile 连续提交点（commit=368640）"
            "与 fMP4 跳跃式 probe 读（offset=605007）错位、中间空洞未补齐"),
        "commit_constant_60s": commit_constant,
        "speed_progress_hypothesis_ruled_out": speed_ruled_out,
        "commit_range_bookkeeping_supported": commit_constant,
        "native_fallback_to_368640": fallback_evidence,
        "codec_opened_after_seconds": delay,
        "write_start_play_count": len(write_lines),
        "minus_103_observed": bool(m103),
        "audio_output": False,
        "f4_status": "BLOCKED",
        "f4_primary_criterion_refined": "自动进曲连续 3 首无 -103、无释放后回调",
        "auto_advance_3_tracks": "NOT RUN",
        "seek_and_ended": "NOT RUN",
        "fix_implemented": False,
        "fix_verified": False,
        "native_mechanism_localized": False,
        "device_endpoint": probe.get("serial", ""),
        "device_can_disconnect": True,
        "a3_g09": "PARTIAL",
        "engine_bay_frozen": False,
    }
    data = {
        "method": "12 §61.62 / F4 vfile commit 时序观测的离线复核（只读、零依赖）",
        "generated_by": "tools/l3_f4_vfile_commit_audit.py",
        "log_file": log_rel,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence_extracted": {
            "commit_values": commits,
            "force_wait_request_count": force_n,
            "jump_read_failure_count": fails,
            "codec_line": codec_lines[0] if codec_lines else None,
            "codec_delay_seconds": delay,
            "preseek_out_len": int(preseek.group(1)) if preseek else None,
            "minus_103_lines": m103[:3],
            "write_start_play_count": len(write_lines),
        },
        "refinement": {
            "strengthened": (
                "commit 60s 恒定 368640 + 5 次 force 重试仍拿不到 605007 → "
                "排除单纯落盘速度不足；只有回退到 368640 重新请求才补齐数据打开 codec，"
                "印证 §61.61「空洞未补齐」机理"),
            "corrected": (
                "§61.61 的「REMOTE 无 codec」源于观察窗口不足：60s 窗口下 "
                "≈32.9s 时 codec 打开，但立即 -103 终止、Write start play 0 次、未出声；"
                "历史日志与实测判据不改写，只补充窗口条件"),
            "narrowed": (
                "F4 主判据收窄到 -103（与 §61.50/§61.51 原始判据一致），"
                "真实阻塞是长时间重试 + 数据不足，而非 codec 打不开"),
            "history_rewritten": False,
        },
        "status": status,
        "honest_notes": [
            "本批是纯观测，未实施、未验证任何修复；F4 仍 BLOCKED，-103 判据未通过。",
            "Open codec for aac ≠ 出声：无 Write start play、无 Play status 4、有 -103 与 AudioTrack Release。",
            "probe 初判 UNEXPECTED_CODEC_OPENED 如实保留，不回改原始证据。",
            "P0 引擎舱零净改动、零发布基线变化；A3 仍 G09 PARTIAL、舱不冻结。",
            "自动进曲 3 首 / seek / ENDED 仍 NOT RUN；F3 14 格矩阵未改写。",
            "commit 恒定的具体 native 字段 / PreSeekReadTask 回退触发条件仍未反汇编定位。",
        ],
    }
    AUDIT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
    print("[INFO] 输出 %s" % AUDIT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.62 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.62 审计：%d 项 checks 全 PASS；commit 60s 恒定 368640、"
          "≈%.1fs 回退后打开 codec 但 -103 未出声；F4 仍 BLOCKED"
          % (len(checks), delay or 0.0))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
