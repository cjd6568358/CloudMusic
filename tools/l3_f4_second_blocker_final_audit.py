#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.61 守护 / F4 第二卡点最终真机结论的离线独立审计（只读、零依赖）。

证据来源全部是已落盘的真机 logcat 与 JSON，不连 adb、不连网络、不改代码：
  analysis/l3-f4-logs/selfconsist-remote.txt   P0 基线 × 自洽样本 REMOTE
  analysis/l3-f4-logs/selfconsist-local.txt    P0 基线 × 自洽样本 LOCAL（PASS 对照）
  analysis/l3-f4-logs/ksab-m4a-remote.txt     变体 B × 自洽样本 REMOTE
  analysis/l3-f4-logs/ab-run2-m4a-remote.txt  §61.59 变体 B × 非自洽样本（历史对照）
  analysis/l3-f3-fixtures/f3_m4a_complete.m4a 自洽 fMP4 样本

产出：
  analysis/l3-f4-second-blocker-final.json        一手证据摘要
  analysis/l3-f4-second-blocker-final-audit.json  checks / 结论 / 诚实边界

防伪造：防把「根因锁定」写成「F4 已解决 / 已修复 / 已验证」，防把
「样本不自洽」或「错误完成通知」重新写回第二卡点，防 P0 基线被改动。
"""
from __future__ import annotations

import json
import re
import struct
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
LOGS = A / "l3-f4-logs"
SAMPLE = A / "l3-f3-fixtures" / "f3_m4a_complete.m4a"
PROBE = A / "l3-f4-selfconsistent-probe.json"
VARIANT_B = A / "l3-f4-ab-selfconsistent.json"
REPORT = A / "l3-f4-second-blocker-final-report.md"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
CONTRACTS = ROOT / "tools" / "check_source_contracts.py"
P0 = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "example" / \
    "cloudmusic" / "enginelegacy" / "UrlDataRequest.kt"
OUT_EVIDENCE = A / "l3-f4-second-blocker-final.json"
OUT_AUDIT = A / "l3-f4-second-blocker-final-audit.json"

LOG_FILES = {
    "baseline_remote": "selfconsist-remote.txt",
    "baseline_local": "selfconsist-local.txt",
    "variantb_remote": "ksab-m4a-remote.txt",
    "prior_variantb_remote": "ab-run2-m4a-remote.txt",
}

RE_READ = re.compile(
    r"ReadFromFileNoLock\s+Local File Read expect len:\d+,\s*"
    r"offset:(\d+),\s*readed_len:(\d+),\s*result:(-?\d+)")
RE_WAIT = re.compile(
    r"WaitRequest cur offset:\s*(\d+),\s*cur end :\s*(\d+),\s*"
    r"expect offset :\s*(\d+),\s*end :\s*(\d+),\s*commit :\s*(\d+)")
RE_START = re.compile(r"Start id=(\d+) offset=(\d+) length=(\d+)")
RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(\d+)")
RE_STATUS = re.compile(r"Play status (\d+)")
CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def load_json(p: Path) -> dict:
    try:
        return json.loads(read(p) or "{}")
    except (ValueError, TypeError):
        return {}


def parse_boxes(path: Path):
    """ISO-BMFF 顶层 box 一手解析：返回 (boxes, end_offset, file_size)。"""
    if not path.is_file():
        return [], 0, 0
    data = path.read_bytes()
    off, boxes = 0, []
    while off + 8 <= len(data):
        size, typ = struct.unpack(">I4s", data[off:off + 8])
        if size == 1 and off + 16 <= len(data):
            size = struct.unpack(">Q", data[off + 8:off + 16])[0]
        if size < 8 or off + size > len(data):
            break
        boxes.append({"type": typ.decode("latin1"), "offset": off, "size": size})
        off += size
    return boxes, off, len(data)


def parse_log(path: Path) -> dict:
    text = read(path)
    reads = [
        {"offset": int(a), "readed_len": int(b), "result": int(c)}
        for a, b, c in RE_READ.findall(text)]
    waits = [
        {"cur_offset": int(a), "cur_end": int(b), "expect_offset": int(c),
         "end": int(d), "commit": int(e)}
        for a, b, c, d, e in RE_WAIT.findall(text)]
    starts = [
        {"id": int(a), "offset": int(b), "length": int(c)}
        for a, b, c in RE_START.findall(text)]
    return {
        "path": str(path.relative_to(ROOT)).replace("\\", "/"),
        "bytes": len(text.encode("utf-8", errors="replace")),
        "open_codec": RE_CODEC.findall(text),
        "write_start": RE_WRITE.findall(text),
        "play_status": RE_STATUS.findall(text),
        "reads": reads,
        "waits": waits,
        "starts": starts,
        "jump_read_failures": [
            r for r in reads
            if r["offset"] == 605007 and r["readed_len"] == 0 and r["result"] == -2],
        "has_103": "-103" in text,
    }


def main() -> int:
    boxes, end_off, sample_size = parse_boxes(SAMPLE)
    counts = Counter(b["type"] for b in boxes)
    tail_type = boxes[-1]["type"] if boxes else ""
    head_types = [b["type"] for b in boxes[:2]]
    logs = {k: parse_log(LOGS / v) for k, v in LOG_FILES.items()}
    probe = load_json(PROBE)
    variantb = load_json(VARIANT_B)
    report = read(REPORT)

    br = logs["baseline_remote"]
    bl = logs["baseline_local"]
    vb = logs["variantb_remote"]
    pv = logs["prior_variantb_remote"]

    br_fail = [r for r in br["jump_read_failures"]]
    vb_fail = [r for r in vb["jump_read_failures"]]
    br_wait = next((w for w in br["waits"] if w["commit"] == 368640), {})
    vb_wait = next((w for w in vb["waits"] if w["commit"] == 368640), {})
    pv_wait = next((w for w in pv["waits"] if w["commit"] == 147456), {})

    probe_cells = {c.get("path"): c for c in probe.get("cells", [])}
    pr = probe_cells.get("remote", {})
    pl = probe_cells.get("local", {})

    sample_ok = (
        sample_size == 16573387 and len(boxes) == 158
        and counts["moof"] == 52 and counts["mdat"] == 52
        and tail_type == "mdat" and head_types == ["ftyp", "moov"]
        and not any(t in counts for t in ("sidx", "mfra", "mfro")))
    local_pass = (
        "aac" in bl["open_codec"] and "0" in bl["write_start"]
        and "4" in bl["play_status"])
    remote_fail = (
        not br["open_codec"] and not br["write_start"]
        and not vb["open_codec"] and not vb["write_start"]
        and len(br_fail) >= 3 and len(vb_fail) >= 1
        and "3" in br["play_status"] and "3" in vb["play_status"])
    commit_below_jump = (
        br_wait.get("commit") == 368640 and br_wait.get("expect_offset") == 605007
        and vb_wait.get("commit") == 368640 and vb_wait.get("expect_offset") == 605007
        and 368640 < 605007
        and pv_wait.get("commit") == 147456 and pv_wait.get("expect_offset") == 605007
        and len(pv["jump_read_failures"]) >= 1)
    sample_ruled_out = sample_ok and local_pass and remote_fail
    notification_ruled_out = (
        remote_fail
        and variantb.get("primary_criterion_met") is False
        and not any(s["id"] >= 3 for s in vb["starts"])
        and any(s["id"] >= 3 for s in br["starts"])
        and br_wait.get("commit") == vb_wait.get("commit"))
    root_cause_locked = (
        sample_ruled_out and notification_ruled_out and commit_below_jump)

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
        "selfconsistent_sample_present": SAMPLE.is_file() and sample_size > 0,
        "sample_bytes_16573387": sample_size == 16573387,
        "sample_158_top_level_boxes": len(boxes) == 158,
        "sample_box_parse_consumes_whole_file": end_off == sample_size and end_off > 0,
        "sample_moof_mdat_paired_52": counts["moof"] == 52 and counts["mdat"] == 52,
        "sample_tail_is_mdat": tail_type == "mdat",
        "sample_head_is_ftyp_moov": head_types == ["ftyp", "moov"],
        "sample_no_index_boxes": not any(t in counts for t in ("sidx", "mfra", "mfro")),
        "all_four_logs_present": all(
            (LOGS / v).is_file() for v in LOG_FILES.values()),
        "baseline_remote_no_codec": not br["open_codec"],
        "baseline_remote_no_write_start": not br["write_start"],
        "baseline_remote_jump_read_fails_3x": len(br_fail) >= 3,
        "baseline_remote_commit_368640_below_605007": (
            br_wait.get("commit") == 368640
            and br_wait.get("expect_offset") == 605007),
        "baseline_local_open_codec_aac": "aac" in bl["open_codec"],
        "baseline_local_write_start_nerr0": "0" in bl["write_start"],
        "baseline_local_play_status_4": "4" in bl["play_status"],
        "variantb_remote_no_codec": not vb["open_codec"],
        "variantb_remote_no_write_start": not vb["write_start"],
        "variantb_remote_jump_read_fails": len(vb_fail) >= 1,
        "variantb_remote_commit_368640_below_605007": (
            vb_wait.get("commit") == 368640
            and vb_wait.get("expect_offset") == 605007),
        "variantb_starts_only_id1_id2": (
            [s["id"] for s in vb["starts"]] == [1, 2]),
        "baseline_starts_have_id3_id4": any(
            s["id"] >= 3 for s in br["starts"]),
        "prior_variantb_same_jump_failure_lower_commit": (
            len(pv["jump_read_failures"]) >= 1
            and pv_wait.get("commit") == 147456
            and pv_wait.get("expect_offset") == 605007),
        "no_103_in_any_failing_cell": not any(
            logs[k]["has_103"] for k in
            ("baseline_remote", "variantb_remote", "prior_variantb_remote")),
        "probe_json_remote_fail_local_pass": (
            pr.get("codec") is None and pr.get("write") is False
            and pl.get("codec") == "aac" and pl.get("write") is True),
        "probe_json_sample_is_complete": probe.get("sample") == "f3_m4a_complete.m4a",
        "variantb_json_uses_selfconsistent_sample": (
            variantb.get("primary_sample") == "f3_m4a_complete.m4a"),
        "variantb_json_primary_criterion_false": (
            variantb.get("primary_criterion_met") is False),
        "variantb_json_p0_restored": variantb.get("p0_restored") is True,
        "variantb_json_regression_all_pass": (
            variantb.get("regression_all_pass") is True),
        "sample_hypothesis_ruled_out": sample_ruled_out,
        "completion_notification_hypothesis_ruled_out": notification_ruled_out,
        "vfile_streaming_root_cause_locked": root_cause_locked,
        "report_has_final_verdict": (
            REPORT.is_file()
            and "F4 第二卡点 = normal mode vfile" in report
            and "F4 仍判 BLOCKED" in report
            and "NOT RUN" in report
            and "设备可断开" in report),
        "report_no_false_claims": (
            "根因锁定 ≠ 修复完成" in report
            and "未实施、未验证任何修复" in report
            and "样本自洽性与错误完成通知均已被真机对照排除" in report),
        "p0_git_diff_clean": diff == "",
        "no_credentials_in_evidence": not CRED_PATTERN.search(
            read(OUT_EVIDENCE) + report),
        "contract_guard_61_61_present": "12 §61.61 守护" in read(CONTRACTS),
        "delivery12_has_61_61": "61.61" in read(DELIVERY12),
        "head_still_uncommitted": head.startswith("8c9d79b1"),
    }
    failed = [k for k, v in checks.items() if not v]

    status = {
        "root_cause": (
            "normal mode vfile 流式落盘跟不上 fMP4 demuxer 的跳跃式 probe 读："
            "commit=368640 (< offset=605007) → ReadFromFileNoLock "
            "readed_len:0 result:-2 → 无 Open codec"),
        "sample_selfconsistency_ruled_out": sample_ruled_out,
        "wrong_completion_notification_ruled_out": notification_ruled_out,
        "commit_below_jump_evidence": commit_below_jump,
        "root_cause_locked": root_cause_locked,
        "native_mechanism_localized": False,
        "fix_implemented": False,
        "fix_verified": False,
        "f4_status": "BLOCKED",
        "device_phase": "COMPLETE",
        "device_can_disconnect": True,
        "auto_advance_3_tracks": "NOT RUN",
        "seek_and_ended": "NOT RUN",
        "a3_g09": "PARTIAL",
        "engine_bay_frozen": False,
    }
    evidence = {
        "method": "12 §61.61 / F4 第二卡点最终真机结论（离线复核已落盘 logcat，不连 adb）",
        "generated_by": "tools/l3_f4_second_blocker_final_audit.py",
        "sample": {
            "path": str(SAMPLE.relative_to(ROOT)).replace("\\", "/"),
            "bytes": sample_size,
            "top_level_boxes": len(boxes),
            "box_counts": dict(counts),
            "head_types": head_types,
            "tail_type": tail_type,
            "parse_consumes_whole_file": end_off == sample_size,
            "paired_moof_mdat": counts["moof"] == counts["mdat"],
        },
        "logs": logs,
        "json_evidence": {
            "selfconsistent_probe": str(PROBE.relative_to(ROOT)).replace("\\", "/"),
            "variant_b_selfconsistent": str(VARIANT_B.relative_to(ROOT)).replace("\\", "/"),
            "variant_b_primary_criterion_met": variantb.get("primary_criterion_met"),
            "variant_b_p0_restored": variantb.get("p0_restored"),
            "variant_b_regression_all_pass": variantb.get("regression_all_pass"),
        },
        "decisive_comparison": {
            "pass_cell": "P0 基线 × 自洽样本 LOCAL：Open codec for aac / "
                         "Play status 4 / Write start play nErr:0",
            "fail_cell": "P0 基线（及变体 B）× 自洽样本 REMOTE："
                         "ReadFromFileNoLock offset:605007 readed_len:0 result:-2，"
                         "WaitRequest commit:368640 < expect offset:605007",
            "prior_snapshot": "§61.59 变体 B × 非自洽样本同样 "
                              "offset:605007 readed_len:0 result:-2，commit:147456",
        },
        "attribution_history": {
            "61_59": "第二卡点写作「fMP4 多 Range × normal mode vfile 落盘 / probe 数据不足」",
            "61_60": "离线样本结构实证把优先级改为「样本不自洽为主、vfile 落盘为次」"
                     "（PENDING_REAL_SAMPLE；本批真机对照推翻其主次）",
            "61_61": "样本自洽性 + 错误完成通知均排除，锁定 vfile 流式落盘 / 跳跃式 probe 读",
            "history_rewritten": False,
        },
        "status": status,
        "honest_notes": [
            "根因锁定 ≠ 修复完成；F4 仍判 BLOCKED，自动进曲 3 首 / seek / ENDED 均未跑。",
            "commit 是「落盘速度不足」还是「commit / Range 记账语义不允许回填跳跃区间」，"
            "尚未在 native 层区分；具体函数与字段待 native 级定位。",
            "§61.59 环节 B 部分成立（确实消除 id>=3 重复请求）、§61.60 样本不完整的"
            "结构事实均不改写，只修正对第二卡点的归因。",
            "变体 B 临时存在，finally 无条件恢复 P0 基线并重编译重装；git diff 空。",
            "设备按用户指令可断开，本批不连 adb、不连 OpenList、零生产代码改动。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 不回填。",
        ],
    }
    OUT_EVIDENCE.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # 凭据扫描必须覆盖本轮刚写出的证据文件本身（而不是上一轮的文件）。
    checks["no_credentials_in_evidence"] = not CRED_PATTERN.search(
        read(OUT_EVIDENCE) + report)
    failed = [k for k, v in checks.items() if not v]
    audit = {
        "method": "12 §61.61 / F4 第二卡点最终结论的离线独立审计（只读、零依赖）",
        "generated_by": "tools/l3_f4_second_blocker_final_audit.py",
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "status": status,
        "honest_notes": evidence["honest_notes"],
    }
    OUT_AUDIT.write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("[INFO] 输出 %s" % OUT_EVIDENCE.relative_to(ROOT))
    print("[INFO] 输出 %s" % OUT_AUDIT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.61 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.61 审计：%d 项 checks 全 PASS；"
          "第二卡点锁定 vfile 流式落盘 / 跳跃式 probe 读；F4 仍 BLOCKED"
          % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
