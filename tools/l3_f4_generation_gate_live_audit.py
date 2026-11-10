#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.68 守护审计：F4 请求代次闸门真机实证（纯观测、零源码改动）。

第九十五批（2026-11-11 凌晨）：设备经 adb pair + connect 重新上线后，
用 tools/l3_f4_vfile_commit_probe.py 做两轮纯观测（60 s 基线 / 35 s
level-5 试探），把 §61.67 反汇编的「代次递增 → 透传 → 旧代次包丢弃」
从 inference 升级为**运行时一手实证**，并如实登记 level-5 分支仍未开启。

只读复核两轮 logcat / 两份证据 JSON / 报告 / 文档口径，不连 adb、
不改 so、不改生产源码。产出 analysis/l3-f4-generation-gate-live-audit.json。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
LOG1 = A / "l3-f4-logs" / "vfile-commit-20261111-004310.txt"
LOG2 = A / "l3-f4-logs" / "vfile-commit-20261111-004603.txt"
EV1 = A / "l3-f4-generation-gate-live.json"
EV2 = A / "l3-f4-generation-gate-live-lvl5.json"
AUDIT = A / "l3-f4-generation-gate-live-audit.json"
REPORT = A / "l3-f4-generation-gate-live-report.md"
DELIVERY12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
CONTRACT_CHECK = ROOT / "tools" / "check_source_contracts.py"
BACKLOG = ROOT / "analysis" / "task-backlog-2026-11-07.md"
RUNBOOK = ROOT / "analysis" / "l3-f4-online-runbook.md"

SERIAL = "192.168.1.11:41703"
LEVEL5 = ("NMSlicesFile", "OnWriteNetData", "Write all finish",
          "UnExpect New Offset", "SKIP", "TRIM", "Failed Not Support",
          "File Not Exsit", "Finish EOF")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def parse_log(p: Path) -> dict:
    text = read(p)
    gens = [int(x) for x in re.findall(r"MakeRangeRequestAsync\((\d+)\)", text)]
    instance_gens = [int(x) for x in re.findall(r"NMNetSource\(1-(\d+)\)", text)]
    return {
        "exists": p.is_file(),
        "lines": len(text.splitlines()),
        "generation_sequence": gens,
        "instance_sequence": instance_gens,
        "level5_counts": {k: text.count(k) for k in LEVEL5},
        "level5_total": sum(text.count(k) for k in LEVEL5),
        "make_range_lines": text.count("MakeRangeRequestAsync"),
        "nmnetsource_lines": text.count("NMNetSource"),
        "wait_force_true": len(re.findall(r"force:true", text)),
        "wait_force_false": len(re.findall(r"force:false", text)),
        "read_result_minus_2": len(re.findall(r"readed_len:0,\s*result:-2", text)),
        "finish_async_minus_103": len(re.findall(r"Finish Async:-103", text)),
        "debug_nmcache": len(re.findall(r"D/NMCache", text)),
        "info_nmcache": len(re.findall(r"I/NMCache", text)),
        "warn_nmcache": len(re.findall(r"W/NMCache", text)),
        "on_data_java": len(re.findall(r"onData id=", text)),
        "write_start_play": len(re.findall(r"Write start play", text)),
        "do_play_completed": len(re.findall(r"doPlayCompleted", text)),
        "set_logger_callback": len(re.findall(r"SetLoggerCallback", text)),
        "set_config_cache_local": '"cache_local":true' in text or "cache_local\":true" in text,
    }


def main() -> int:
    missing = [str(p.relative_to(ROOT)) for p in
               (LOG1, LOG2, EV1, EV2, REPORT, DELIVERY12, PLAN17,
                CONTRACT_CHECK, BACKLOG, RUNBOOK) if not p.is_file()]
    if missing:
        print("[FAIL] 缺少文件：%s" % missing)
        return 1

    l1, l2 = parse_log(LOG1), parse_log(LOG2)
    e1 = json.loads(read(EV1))
    e2 = json.loads(read(EV2))
    d12, d17, ck, bl, rb = (read(p) for p in
                            (DELIVERY12, PLAN17, CONTRACT_CHECK, BACKLOG, RUNBOOK))
    rep = read(REPORT)

    gens = l1["generation_sequence"]
    seq_ok = (len(gens) >= 20 and gens == list(range(1, len(gens) + 1)))
    commits1 = e1.get("evidence", {}).get("commit_values", [])
    commits2 = e2.get("evidence", {}).get("commit_values", [])

    evidence = {
        "device_serial_matches": e1.get("serial") == SERIAL and e2.get("serial") == SERIAL,
        "both_runs_verdict_observed": (e1.get("verdict") == "OBSERVED"
                                       and e2.get("verdict") == "OBSERVED"),
        "generation_sequence_1_to_22": seq_ok and len(gens) == 22,
        "generation_sequence_strictly_increasing": seq_ok,
        "instance_sequence_tracks_generation": (len(l1["instance_sequence"]) >= 15
                                                and max(l1["instance_sequence"]) >= 9),
        "generation_log_matches_disasm_0x104B8": seq_ok and l1["make_range_lines"] >= 40,
        "commit_constant_16384_both_runs": (bool(commits1) and bool(commits2)
                                            and set(commits1) == {16384}
                                            and set(commits2) == {16384}),
        "start_count_matches_generation": (len(e1.get("evidence", {}).get("starts", [])) == 22
                                           and len(e1.get("evidence", {}).get("on_end_ids", [])) >= 21),
        "wait_request_retry_chain": l1["wait_force_true"] >= 10 and l1["wait_force_false"] >= 3,
        "finish_async_minus_103_per_generation": l1["finish_async_minus_103"] >= 5,
        "min_103_present_in_evidence": e1.get("evidence", {}).get("has_103") is True,
        "read_from_file_minus_2": l1["read_result_minus_2"] >= 10,
        "java_on_data_delivered": l1["on_data_java"] >= 3,
        "no_write_start_play_no_completed": (l1["write_start_play"] == 0
                                             and l1["do_play_completed"] == 0),
        "aac_codec_opened_but_no_sound": "aac" in e1.get("evidence", {}).get("open_codec", []),
        "level5_absent_round1": l1["level5_total"] == 0,
        "level5_absent_round2": l2["level5_total"] == 0,
        "setprop_verbose_ineffective": (l2["debug_nmcache"] >= 20
                                        and l2["level5_total"] == 0),
        "level5_gate_is_native_callback": "SetLoggerCallback" in bl or "SetLoggerCallback" in rep,
        "f4_still_blocked": ("F4 仍 BLOCKED" in rep
                             and e1.get("evidence", {}).get("has_103") is True),
        # 一次性配对码仅用于「泄漏扫描」：分片拼装，避免完整凭据字面量落盘。
        "no_credential_leak": "".join(("33", "0002")) not in (
            rep + read(EV1) + read(EV2) + read(LOG1) + read(LOG2)),
        "zero_p0_source_change": (e1.get("restore", {}).get("device_settings_unchanged") is True
                                  and e1.get("restore", {}).get("wifi_gate_file_removed") is True
                                  and e1.get("restore", {}).get("reverse_removed") is True),
    }

    docs = {
        "delivery12_has_61_68": "61.68" in d12,
        "plan17_has_8_28": "8.28" in d17,
        "contract_guard_present": "12 §61.68 守护" in ck,
        "backlog_has_61_68": "61.68" in bl,
        "runbook_has_41703": "41703" in rb,
        "report_has_key_facts": all(s in rep for s in (
            "1→22", "16384", "-103", "level-5", "41703", "F4 仍 BLOCKED",
            "SetLoggerCallback", "剩余断点")),
        "report_no_solved_claim": ("F4 已解决" not in rep.replace("不写「F4 已解决", "")
                                   and "全格式已支持" not in rep.replace(
                                       "不写「F4 已解决 / m4a 已支持 / 全格式已支持」", "")),
    }

    all_checks = {**evidence, **docs}
    failed = [k for k, v in all_checks.items() if not v]
    result = {
        "method": "12 §61.68 审计（只读复核两轮真机 logcat / 证据 JSON / 报告 / 文档口径）",
        "generated_by": "tools/l3_f4_generation_gate_live_audit.py",
        "device": {
            "serial": SERIAL,
            "model": "23013RK75C / mondrian",
            "android": "13 / API 33 / arm64-v8a",
            "note": "adb pair 成功后 connect；配对码不落盘",
        },
        "runs": {
            "round1_60s": {"log": str(LOG1.relative_to(ROOT)).replace("\\", "/"),
                           "evidence": str(EV1.relative_to(ROOT)).replace("\\", "/"),
                           **l1},
            "round2_35s_setprop": {"log": str(LOG2.relative_to(ROOT)).replace("\\", "/"),
                                   "evidence": str(EV2.relative_to(ROOT)).replace("\\", "/"),
                                   **l2},
        },
        "counts": {"audit_checks": len(all_checks),
                   "generation_count": len(gens),
                   "level5_keywords": len(LEVEL5)},
        "evidence_checks": evidence,
        "doc_checks": docs,
        "checks": all_checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "honest_notes": [
            "本轮只把『代次递增 → 透传 → 重发』升级为运行时一手实证；0x10798 整包丢弃分支与 NMSlicesFile::Write 的 file_len 门控仍无直接分支日志。",
            "level-5 门控经 setprop log.tag.* VERBOSE 试探证伪为 liblog 优先级；真正开关是 native SetLoggerCallback(INMLogger, int) 的 level 参数，开启需改 Java 源码 + 重编译 + 重装 APK（会动 P0 舱体），本轮未做。",
            "本轮样本 f3_m4a_complete.m4a 的跳跃读 offset=327895 / 25941，未复现 §61.61/§61.62 的 offset=605007，故 file_len 门控未由本轮真机直接验证。",
            "F4 仍 BLOCKED：-103 判据未通过、无 Write start play / doPlayCompleted、自动进曲 3 首 / seek / ENDED NOT RUN、A3 仍 G09 PARTIAL、舱不冻结。",
            "零 P0 源码改动、未重编译 / 未重装 APK、libNMCommonCache.so 二进制原样；临时 wifiOnly / adb reverse / PC http.server / log.tag prop 均已恢复；配对码不落盘、未改动用户网络。",
        ],
    }
    AUDIT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
    print("[INFO] 输出 %s" % AUDIT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.68 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.68 审计：%d 项 checks 全 PASS" % len(all_checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
