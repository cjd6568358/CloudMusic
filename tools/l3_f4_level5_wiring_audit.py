# -*- coding: utf-8 -*-
"""12 §61.69 · F4 level-5 诊断接线只读审计。

复跑：python tools/l3_f4_level5_wiring_audit.py
产出：analysis/l3-f4-level5-wiring-audit.json

职责边界：只读。审计「用户授权改 P0 后落地的唯一生产改动」是否保持
既定安全不变量、历史证据是否回潮、文档是否如实登记「真机 NOT RUN /
设备离线 / F4 仍 BLOCKED」。不连 adb、不构建、不写任何生产源码。
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANALYSIS = ROOT / "analysis"
OUT = ANALYSIS / "l3-f4-level5-wiring-audit.json"

ENGINE_ADAPTER = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                  "com" / "example" / "cloudmusic" / "enginelegacy" /
                  "EngineAdapter.kt")
SO = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libNMCommonCache.so"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

PROBE_JSON = ANALYSIS / "l3-f4-loglevel-gate-probe.json"
LIVE_JSON = ANALYSIS / "l3-f4-generation-gate-live-audit.json"
DISASM_JSON = ANALYSIS / "l3-f4-generation-gate-audit.json"

D12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
D17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
D09 = ROOT / "docs" / "09-文档闭环总览.md"
D16 = ROOT / "docs" / "16-架构与任务拆分复核报告.md"
BACKLOG = ANALYSIS / "task-backlog-2026-11-07.md"
RUNBOOK = ANALYSIS / "l3-f4-online-runbook.md"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def load(p: Path) -> dict:
    if not p.is_file():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return {}


def clean(t: str) -> str:
    """剔除「不写「X」/ 防「X」伪证 / 禁止臆断 X」等否定式引用后再查伪证短语。"""
    t = re.sub(r"不写「[^」]*」", "", t)
    t = re.sub(r"防「[^」]*」伪证", "", t)
    t = re.sub(r"禁止臆断[^\n]*", "", t)
    return t


def source_files() -> list[Path]:
    out: list[Path] = []
    for base in (ROOT / "engine-legacy" / "src", ROOT / "app" / "src"):
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if p.suffix in (".java", ".kt") and "build" not in p.parts:
                out.append(p)
    return out


def section(text: str, marker: str) -> str:
    return text.split(marker, 1)[1] if marker in text else ""


def main() -> int:
    src = source_files()
    src_blob = "\n".join(read(p) for p in src)
    ea = read(ENGINE_ADAPTER)
    d12 = read(D12)
    d17 = read(D17)
    d09 = read(D09)
    d16 = read(D16)
    bl = read(BACKLOG)
    rb = read(RUNBOOK)
    sec12 = section(d12, "### 61.69")
    sec17 = section(d17, "### 8.29")

    probe = load(PROBE_JSON)
    live = load(LIVE_JSON)
    disasm = load(DISASM_JSON)
    probe_blob = json.dumps(probe, ensure_ascii=False)
    probe_checks = probe.get("checks", {})
    live_checks = live.get("checks", {})

    so_sha = ""
    if SO.is_file():
        so_sha = hashlib.sha256(SO.read_bytes()).hexdigest()

    apk_size = APK.stat().st_size if APK.is_file() else None

    banned = ("level-5 已开启", "level-5 已解锁", "level-5 已生效",
              "F4 已解决", "F4 已修复", "F4 已收口")
    honesty_targets = [clean(sec12), clean(sec17)]

    checks = {
        # ---- wiring 8：唯一生产改动的安全不变量 ----
        "inmlogger_import_present": (
            "import com.netease.cloudmusic.sdk.NMCommonCache.INMLogger" in ea),
        "setlogger_called_with_instance": (
            "SetLoggerCallback(INMLogger(), level)" in ea),
        "setlogger_no_null_argument_anywhere": (
            "SetLoggerCallback(null" not in src_blob),
        "level_coerce_hard_cap_0_5": (
            "coerceIn(0, 5)" in ea and "coerceIn(0, 6)" not in src_blob),
        "level_default_5": "getOrDefault(5)" in ea,
        "level_private_file_override": "nmcache_log_level" in ea,
        "wiring_comment_cites_61_69": "12 §61.69" in ea,
        "setconfig_still_present": "SetConfig(json)" in ea,

        # ---- evidence 5：历史证据不回潮、风险标注未被抹除 ----
        "loglevel_probe_all_pass_19": (
            probe.get("all_pass") is True
            and not probe.get("failed_checks")
            and len(probe_checks) == 19
            and all(v is True for v in probe_checks.values())),
        "loglevel_probe_records_null_risk": (
            "null_logger_risk" in probe_blob and "INMLogger" in probe_blob),
        "loglevel_probe_records_strict_gt_semantics": (
            "level_compare_semantics" in probe_blob
            and ("严格大于" in probe_blob or "cset w0,gt" in probe_blob)),
        "gen_gate_live_audit_intact": (
            live.get("all_pass") is True
            and not live.get("failed_checks")
            and len(live_checks) == 29
            and live_checks.get("f4_still_blocked") is True),
        "gen_gate_disasm_audit_intact": (
            disasm.get("all_pass") is True
            and not disasm.get("failed_checks")
            and len(disasm.get("checks", {})) == 25),

        # ---- binary 2：so 原样 + 接线唯一 ----
        "so_binary_present_and_hashed": bool(so_sha) and len(so_sha) == 64,
        "setlogger_single_callsite_in_tree": (
            src_blob.count("SetLoggerCallback(INMLogger()") == 1),

        # ---- docs 5：批次登记到位 ----
        "docs12_has_61_69": "### 61.69" in d12,
        "docs17_has_8_29": "### 8.29" in d17,
        "backlog_has_61_69": "61.69" in bl,
        "runbook_records_device_offline": (
            "完全离线" in rb
            and "Test-NetConnection :41703 = False" in rb
            and "上线判据" in rb),
        "docs09_and_16_record_61_69": "61.69" in d09 and "61.69" in d16,

        # ---- honesty 3：诚实边界 ----
        "f4_still_blocked_in_docs": (
            "F4 仍 BLOCKED" in sec12 and "F4 仍 BLOCKED" in sec17),
        "no_level5_enabled_claim": not any(
            b in t for t in honesty_targets for b in banned),
        "device_state_recorded_as_offline": all(
            ("完全离线" in t or "设备离线" in t) for t in (sec12, bl, rb)),
    }

    failed = [k for k, v in checks.items() if not v]
    groups = {
        "wiring": list(checks)[0:8],
        "evidence": list(checks)[8:13],
        "binary": list(checks)[13:15],
        "docs": list(checks)[15:20],
        "honesty": list(checks)[20:23],
    }
    group_counts = {g: sum(1 for k in ks if checks[k]) for g, ks in groups.items()}

    result = {
        "method": "12 §61.69 level-5 诊断接线只读审计（先读进变量再写，不碰生产源码 / 不连 adb）",
        "generated_by": "tools/l3_f4_level5_wiring_audit.py",
        "batch": "12 §61.69 / 第九十六批",
        "date": "2026-11-11",
        "device": {
            "endpoint": "192.168.1.11:41703",
            "state": "offline",
            "tcp_41703": False,
            "icmp": False,
            "local_wlan": "192.168.1.19",
            "network_unchanged": True,
            "note": "连 ICMP 都不通；重新上线须用户开启无线调试并给新配对端点 + 一次性配对码（不落盘）",
        },
        "artifacts": {
            "engine_adapter": str(ENGINE_ADAPTER.relative_to(ROOT)).replace("\\", "/"),
            "engine_adapter_p0_lines_added": 20,
            "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
            "so_sha256": so_sha,
            "debug_apk": str(APK.relative_to(ROOT)).replace("\\", "/"),
            "debug_apk_present": apk_size is not None,
            "debug_apk_size": apk_size,
            "debug_apk_build": "BUILD SUCCESSFUL in 2m31s (:app:assembleDebug --offline)",
            "backup_tar": "analysis/l3-f4-backup/app-data-20261111.tar",
            "backup_size": 50969600,
        },
        "groups": groups,
        "group_counts": group_counts,
        "counts": {
            "checks_total": len(checks),
            "checks_pass": len(checks) - len(failed),
            "source_files_scanned": len(src),
            "loglevel_probe_checks": len(probe_checks),
            "gen_gate_live_checks": len(live_checks),
            "gen_gate_disasm_checks": len(disasm.get("checks", {})),
        },
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "honest_notes": [
            "本批推进的是「诊断接线 + 编译产物就绪」，不是 F4 修复、不是真机验证。",
            "真机 level-5 观测 NOT RUN：全新安装被 MIUI INSTALL_FAILED_USER_RESTRICTED 阻挡，随后设备完全离线（TCP/ICMP 均不通）。",
            "后续真机起点是冷缓存（卸载清空 legacy_engine_cache），与 §61.68 暖缓存条件不同。",
            "level 硬上限 5 的依据：g_level > 5 会令 NMSlicesFile::Write 直接返回 -102/-1/-99，注入原本不存在的错误返回会污染 F4 观测。",
            "INMLogger 是空类；JNI 0x32d70 把 logger 交给唯一 callee 0x36198，传 null 的安全性未实证。",
            "getter 0x37e20 是严格大于比较（cmp w8,w0; cset w0,gt），level=5 不是必然解锁 level-5 的可靠结论，须真机 0/4/5 对照。",
            "F4 仍 BLOCKED：-103 未通过、自动进曲 3 首 / seek / ENDED NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。",
            "未改动用户网络、配对码不落盘、未改 so 二进制、零新增依赖。",
        ],
    }

    ANALYSIS.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    for g, ks in groups.items():
        print(f"[{g.upper()}] {group_counts[g]}/{len(ks)}")
    for k, v in checks.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}")
    print(f"\n结果：{'ALL PASS' if not failed else 'FAIL'} "
          f"({len(checks) - len(failed)}/{len(checks)})；"
          f"failed={failed}；输出 {OUT.relative_to(ROOT)}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
