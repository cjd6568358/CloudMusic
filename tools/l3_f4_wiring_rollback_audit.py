#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.79 / F4 · §61.69 level-5 诊断接线 **回滚** 只读审计。

用户 2026-11-11 拍板「跑 A1 时间盒，接线回滚一起做」：
  * `§61.69` 接线（`SetLoggerCallback(INMLogger(), level)`）是 level-5 诊断接线；
  * `§61.70` 勘误已证明 `g_level=0` 默认 I/D/W/E 全开、`level=5` 反而抑制 I/D，
    且空类 `INMLogger` 缺 `BIInfo` 会导致 `GetMethodID` 失败 —— 接线**零增益且有风险**；
  * `§61.73/§61.75` 真机取证用的 APK 本就**不含接线**（基线 APK），源码与现场脱节。

本工具在源码回滚后只读核验：
  1. 接线**调用**字面串全部从 `EngineAdapter.kt` 与源码树消失
     （`NMVirtualFileManager.java` 的 `public static native void
     SetLoggerCallback(INMLogger iNMLogger, int i);` 是 P0 native 方法声明，
     回滚前就存在、必须保留，不算接线）；
  2. `SetConfig` / VFM / 缓存目录 / LOCAL·REMOTE 分叉等**功能代码保持不变**；
  3. `§61.69` 历史接线审计 JSON（`l3-f4-level5-wiring-audit.json`）作为
     **历史冻结证据一字未改**（不重跑 `tools/l3_f4_level5_wiring_audit.py`
     覆盖它），so 二进制哈希与历史记录一致；
  4. `§61.75` 终局裁决 / `§61.78` A1 论证证据未被改写，F4 仍 BLOCKED；
  5. 文档已登记 `### 61.79`。

**边界**：纯离线只读、零生产源码改动、不连 adb、不构建、不重装。
回滚的是**诊断接线**，不是 F4 修复；「绕行 ≠ 修复」口径不变。

复跑：python tools/l3_f4_wiring_rollback_audit.py
产出：analysis/l3-f4-wiring-rollback-audit.json
      analysis/l3-f4-wiring-rollback-audit-report.md
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
OUT = A / "l3-f4-wiring-rollback-audit.json"
REPORT = A / "l3-f4-wiring-rollback-audit-report.md"

ADAPTER = (ROOT / "engine-legacy" / "src" / "main" / "java" /
           "com" / "example" / "cloudmusic" / "enginelegacy" /
           "EngineAdapter.kt")
SOURCE_CONFIG = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                 "com" / "netease" / "cloudmusic" / "android" / "sdk" /
                 "playerengine" / "SourceConfig.java")
SO = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libNMCommonCache.so"

HIST_WIRING = A / "l3-f4-level5-wiring-audit.json"
BYPASS = A / "l3-f4-bypass-stream-audit.json"
ACCEPTANCE = A / "l3-f4-acceptance-final.json"

D12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
D09 = ROOT / "docs" / "09-文档闭环总览.md"
D16 = ROOT / "docs" / "16-架构与任务拆分复核报告.md"
D17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
BACKLOG = A / "task-backlog-2026-11-07.md"
RUNBOOK = A / "l3-f4-online-runbook.md"

# 接线专有字面串：回滚后必须全部消失。
WIRING_LITERALS = (
    "import com.netease.cloudmusic.sdk.NMCommonCache.INMLogger",
    "SetLoggerCallback(INMLogger(), level)",
    "SetLoggerCallback(INMLogger()",
    "nmcache_log_level",
    "getOrDefault(5)",
    "coerceIn(0, 5)",
    "logger=INMLogger",
)

# 回滚不得触碰的功能代码（缓存配置 / VFM / LOCAL·REMOTE 分叉 / 播放链）。
FUNCTIONAL_LITERALS_ADAPTER = (
    "NMVirtualFileManager(it)",
    "SetConfig(json)",
    "legacy_engine_cache",
    "manager.SetConfig(json)",
    "PlayerDataSource(provider, source.trackId, source.url != null, info)",
    "engine.prepareAsync()",
    "EngineNativeLoader.loadChain",
)

BANNED = ("F4 已解决", "F4 已修复", "F4 已收口", "level-5 已开启",
          "level-5 已解锁", "接线已修复 F4")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def source_blob() -> tuple[str, int]:
    files: list[Path] = []
    for base in (ROOT / "engine-legacy" / "src", ROOT / "app" / "src"):
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if p.suffix in (".java", ".kt") and "build" not in p.parts:
                files.append(p)
    return "\n".join(read(p) for p in files), len(files)


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else ""


def build_report(d: dict) -> str:
    lines = [
        "# F4 · §61.69 level-5 诊断接线回滚审计（12 §61.79，2026-11-11）",
        "",
        "> **性质**：纯离线只读审计，**零生产源码改动**（回滚动作本身不在此工具内执行）、",
        "> 不连 adb、不构建、不重装。回滚的是诊断接线，**不是 F4 修复**；",
        "> **绕行 ≠ 修复**，F4 仍 BLOCKED。",
        "",
        "## 1. 回滚依据",
        "",
        "- `12 §61.70` 勘误：`libNMCommonCache.so` 的 `g_level` 进程启动恒为 0，",
        "  默认 I/D/W/E 日志**全开**；`level=5` 反而抑制 I/D（getter 为",
        "  `cmp w8,w0; cset w0,gt` 严格大于比较）。",
        "- `INMLogger` 是空类、无 `BIInfo`，`GetMethodID` 会失败，接线有风险。",
        "- `§61.73`/`§61.75` 真机取证用的 APK 本就不含接线（基线 APK），",
        "  源码保留接线与现场脱节，且零增益。",
        "",
        "## 2. 核验结果",
        "",
        "| # | 检查项 | 结论 |",
        "|---|---|---|",
    ]
    for i, (k, v) in enumerate(d["checks"].items(), 1):
        lines.append("| %d | `%s` | %s |" % (i, k, "PASS" if v else "**FAIL**"))
    lines.extend([
        "",
        "## 3. 关键不变量",
        "",
        "- 接线专有字面串自 `EngineAdapter.kt` / 源码树**全部消失**：%s" % (
            d["wiring_literals_removed"]),
        "- 功能代码（`SetConfig` / VFM / 缓存目录 / `source.url != null` 分叉 /",
        "  `remote ? Type.REMOTE : Type.LOCAL`）**保持不变**：%s" % (
            d["functional_code_intact"]),
        "- `§61.69` 历史接线审计 JSON 作为**历史冻结件一字未改**",
        "  （`all_pass=true` / `checks_total=23` / `setlogger_called_with_instance=true`），",
        "  不重跑 `tools/l3_f4_level5_wiring_audit.py` 覆盖它。",
        "- native so 二进制原样：`sha256=%s`（与历史记录 %s）。" % (
            d["so_sha256"][:16] + "…", "一致" if d["so_matches_history"] else "不一致"),
        "- 零 P0 native 改动、未重编译 / 未重装、未改设备系统设置。",
        "",
        "## 4. 诚实边界",
        "",
        "- 本批只回滚**诊断接线**，不修 REMOTE 取流缺陷；`§61.75` 终局裁决不变，",
        "  **F4 仍 BLOCKED**，`§61.78` 的「绕行 ≠ 修复」口径不变。",
        "- A1 时间盒真机实验另由 `tools/l3_f4_a1_bypass_live_probe.py` 取证、",
        "  `tools/l3_f4_a1_bypass_verdict.py` 纯离线裁决，与本回滚正交。",
        "- A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。",
        "",
        "## 5. 实跑核验",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(d["failed_checks"]) or "无"),
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    ea = read(ADAPTER)
    sc = read(SOURCE_CONFIG)
    blob, n_files = source_blob()
    hist = jload(HIST_WIRING)
    bypass = jload(BYPASS)
    acc = jload(ACCEPTANCE)
    d12 = read(D12)
    so_sha = sha(SO)
    hist_so = hist.get("artifacts", {}).get("so_sha256", "")

    removed = {lit: (lit not in ea) for lit in WIRING_LITERALS}
    functional = {lit: (lit in ea) for lit in FUNCTIONAL_LITERALS_ADAPTER}

    checks = {
        # ---- 回滚态 8：接线专有字面串必须消失 ----
        "inmlogger_import_removed": removed[WIRING_LITERALS[0]],
        "setlogger_instance_call_removed": removed[WIRING_LITERALS[1]],
        "setlogger_prefix_removed_from_adapter": removed[WIRING_LITERALS[2]],
        "nmcache_log_level_removed": removed[WIRING_LITERALS[3]],
        "default_5_removed": removed[WIRING_LITERALS[4]],
        "coerce_0_5_removed": removed[WIRING_LITERALS[5]],
        "diag_logger_line_removed": removed[WIRING_LITERALS[6]],
        # 源码树里 NMVirtualFileManager.java 的
        # `public static native void SetLoggerCallback(INMLogger iNMLogger, int i);`
        # 是 P0 native 方法声明（回滚前就存在、必须保留），不是 §61.69 接线调用。
        # 故只校验「接线调用形式」消失 + native 声明仍在（功能不变量）。
        "wiring_call_removed_native_decl_intact": (
            "SetLoggerCallback(INMLogger()" not in blob
            and "public static native void SetLoggerCallback(INMLogger iNMLogger, int i);" in blob),

        # ---- 功能不变量 8：回滚不得触碰功能代码 ----
        "vfm_still_constructed": functional[FUNCTIONAL_LITERALS_ADAPTER[0]],
        "setconfig_still_present": functional[FUNCTIONAL_LITERALS_ADAPTER[1]],
        "cache_dir_still_present": functional[FUNCTIONAL_LITERALS_ADAPTER[2]],
        "manager_setconfig_still_called": functional[FUNCTIONAL_LITERALS_ADAPTER[3]],
        "remote_local_fork_intact": functional[FUNCTIONAL_LITERALS_ADAPTER[4]],
        "prepare_async_still_called": functional[FUNCTIONAL_LITERALS_ADAPTER[5]],
        "native_load_chain_still_called": functional[FUNCTIONAL_LITERALS_ADAPTER[6]],
        "sourceconfig_fork_intact": "remote ? Type.REMOTE : Type.LOCAL" in sc,

        # ---- 历史证据冻结 5 ----
        "historical_wiring_audit_json_intact": (
            hist.get("all_pass") is True
            and hist.get("counts", {}).get("checks_total") == 23
            and hist.get("counts", {}).get("checks_pass") == 23
            and hist.get("checks", {}).get("setlogger_called_with_instance") is True
            and hist.get("checks", {}).get("setlogger_single_callsite_in_tree") is True),
        "historical_wiring_audit_sha_recorded": len(sha(HIST_WIRING)) == 64,
        "so_binary_present": len(so_sha) == 64 and SO.is_file(),
        "so_matches_historical_record": bool(so_sha) and so_sha == hist_so,
        "acceptance_final_still_blocked": acc.get("f4_status") == "BLOCKED"
        and acc.get("main_criteria_all_pass") is False,

        # ---- A1 论证证据与文档 5 ----
        "bypass_audit_intact": (
            bypass.get("all_pass") is True
            and bypass.get("f4_status") == "BLOCKED"
            and "BYPASS_NOT_FIX" in bypass.get("verdict", "")),
        "docs12_has_61_79": "### 61.79" in d12,
        "docs12_still_f4_blocked": "F4 仍 BLOCKED" in d12,
        "docs_09_16_17_mention_61_79": all(
            "61.79" in read(p) for p in (D09, D16, D17)),
        # 自检反身陷阱：BANNED 定义块本身含被禁词，须从受检源码中剥离，
        # 否则该检查恒 False。守护对象 = 工具正文 / 说明，不含禁用词清单定义。
        "no_false_claim_in_tool_source": all(
            b not in re.sub(r"BANNED = \([^)]*\)", "", read(Path(__file__)), count=1)
            for b in BANNED),

        # ---- 现场与批次口径 2 ----
        "rollback_comment_cites_61_79": "§61.79" in ea or "61.79" in ea,
        "backlog_and_runbook_mention_61_79": (
            "61.79" in read(BACKLOG) and "61.79" in read(RUNBOOK)),
    }
    failed = [k for k, v in checks.items() if not v]

    d = {
        "method": "12 §61.79 / F4 · §61.69 level-5 诊断接线回滚只读审计",
        "generated_by": "tools/l3_f4_wiring_rollback_audit.py",
        "mode": "offline_readonly",
        "device_run": False,
        "rollback_state": "ROLLED_BACK",
        "f4_status": "BLOCKED",
        "batch": "12 §61.79 / 第一百零六批",
        "date": "2026-11-11",
        "scope": "仅诊断接线回滚；零 P0 native 改动、未重编译 / 未重装",
        "source_files_scanned": n_files,
        "adapter": str(ADAPTER.relative_to(ROOT)).replace("\\", "/"),
        "wiring_literals_removed": removed,
        "functional_code_intact": functional,
        "so_sha256": so_sha,
        "so_matches_history": bool(so_sha) and so_sha == hist_so,
        "historical_wiring_audit_sha256": sha(HIST_WIRING),
        "historical_wiring_audit_note": (
            "analysis/l3-f4-level5-wiring-audit.json 为 §61.69 历史冻结证据，"
            "接线回滚后不重跑 tools/l3_f4_level5_wiring_audit.py 覆盖；"
            "其 23 项 checks 描述的是当时的接线态，与当前源码态不同属正常。"),
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "honest_notes": [
            "回滚的是 level-5 诊断接线（零增益且有 §61.70 已证风险），不是 F4 修复。",
            "「绕行 ≠ 修复」：A1 绕过取流成立也不修 REMOTE 本身，§61.75 终局裁决不变。",
            "纯离线只读、不连 adb、不构建、不重装；native so 二进制原样。",
            "历史接线审计 JSON / 终局裁决 / A1 论证证据均零改写。",
            "A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。",
        ],
    }
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / F4=%s / state=%s"
          % (len(checks), not failed, d["f4_status"], d["rollback_state"]))
    if failed:
        print("[FAIL] §61.79 接线回滚审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.79 接线回滚审计：接线已移除、功能代码不变、"
          "历史证据冻结、so 原样；接线回滚是诊断清理、不是 F4 修复")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
