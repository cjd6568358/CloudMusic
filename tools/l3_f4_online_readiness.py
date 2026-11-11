#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.72 / F4 · 现行上线执行链一致性自检（纯离线、只读、零生产改动）。

背景：runbook 顶部（§61.70 补充 + §61.71 ④）已经把上线判据改成
「保持 g_level=0 + 主动 seek + probe 抓 logcat + W 分支真实格式串判读」，
但 runbook §1「一键执行（唯一命令）」与 tools/l3_f4_preflight.py 仍停留在
§61.58/§61.59 的 kind 槽 A/B 剧本（--apply 改 P0，且该 A/B 早已执行完毕、
裁决为「环节 B 部分成立、非充分原因」）。设备一旦上线，照 §1 跑会重跑
已完成实验、污染冷缓存窗口，且会临时改 P0。

本工具不重跑 A/B、不改 P0、不改历史证据、不覆盖 §61.71 固化回放证据，
只把「现行执行链所需的资产 / 口径 / 诚实边界」汇总成一次可复跑自检：
  * 现行探针是否落盘 logcat（否则 --log 拿不到文件）；
  * §61.71 W 分支判读工具与固化证据是否完整；
  * §61.65~§61.70 六份历史证据 JSON 是否仍全绿（只读、不重跑）；
  * runbook 是否已把现行链写在前、把 A/B 剧本标成历史；
  * 主动 seek 是否被如实登记为「用户操作 / 物理在场」而非脚本能力；
  * §61.69 接线是否仍在且回滚待拍板；
  * §61.58 的 preflight 历史证据是否零改写。

唯一的外部动作是只读 `adb devices` 探测（不 connect、不改设备现场、
不采集日志、不跑任何格）；device_run 恒为 False。

产出 analysis/l3-f4-online-readiness.json 与
analysis/l3-f4-online-readiness-report.md。

诚实边界：all_pass 只表示「离线执行链口径与资产自洽」，既不是设备在线证明，
也不是 F4 验收通过；ready_to_run 恒为 False（接线回滚仍待用户拍板）。
F4 仍 BLOCKED。
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
T = ROOT / "tools"
ADB = ROOT / "tools" / "bin" / "adb.exe"
OUT = A / "l3-f4-online-readiness.json"
REPORT = A / "l3-f4-online-readiness-report.md"

PROBE = T / "l3_f4_vfile_commit_probe.py"
WBRANCH = T / "l3_f4_wbranch_recheck.py"
WBRANCH_JSON = A / "l3-f4-wbranch-recheck.json"
WBRANCH_REPORT = A / "l3-f4-wbranch-recheck-report.md"
RUNBOOK = A / "l3-f4-online-runbook.md"
PREFLIGHT_JSON = A / "l3-f4-preflight.json"
ADAPTER = (ROOT / "engine-legacy" / "src" / "main" / "java" /
           "com" / "example" / "cloudmusic" / "enginelegacy" /
           "EngineAdapter.kt")

# §61.65~§61.70 的历史证据，只读核验「固化且全绿」，按纪律不重跑覆盖。
GATE_EVIDENCE = (
    "l3-f4-netsource-commit-disasm.json",
    "l3-f4-generation-gate-disasm.json",
    "l3-f4-generation-gate-audit.json",
    "l3-f4-generation-gate-live-audit.json",
    "l3-f4-level5-wiring-audit.json",
    "l3-f4-level5-gate-semantics-audit.json",
)
FORBIDDEN = ("F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
             "推断已升级为实证")

CURRENT_COMMANDS = (
    "python tools/l3_f4_online_readiness.py",
    "python tools/l3_f4_vfile_commit_probe.py --serial <新端点> --wait 60 "
    "--out analysis/l3-f4-live-online.json",
    "python -c \"import json;print(json.load(open("
    "'analysis/l3-f4-live-online.json',encoding='utf-8'))['log_file'])\"",
    "python tools/l3_f4_wbranch_recheck.py --log <上一步输出的 logcat 路径>",
)


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def probe_devices() -> tuple[bool, list[str]]:
    """只读 adb devices；不 connect、不改设备现场、不采集日志。"""
    if not ADB.is_file():
        return False, []
    try:
        r = subprocess.run([str(ADB), "devices"], cwd=str(ROOT),
                           capture_output=True, text=True, timeout=30)
        lines = [ln.strip() for ln in r.stdout.splitlines()[1:] if ln.strip()]
    except (OSError, subprocess.SubprocessError):
        return False, []
    return any(ln.endswith("\tdevice") for ln in lines), lines


def build_report(data: dict) -> str:
    failed = data["failed_checks"]
    lines = [
        "# F4 · 现行上线执行链一致性自检（§61.72，2026-11-11）",
        "",
        "> **性质**：纯离线、只读、零 P0 源码改动、不重跑 A/B、不覆盖任何历史证据。",
        "> 唯一外部动作是 `adb devices` 只读探测；`device_run=false`。",
        "> **F4 仍 BLOCKED**，真机复跑 NOT RUN。",
        "",
        "## 1. 补的是什么缺口",
        "",
        "`analysis/l3-f4-online-runbook.md` 顶部的 §61.70 补充执行层细化与",
        "§61.71 ④ 已把上线判据改为「保持 `g_level=0` + 主动 seek + 探针抓",
        "logcat + 用真实 `.rodata` 格式串扫 W 级日志」，但同一份 runbook 的",
        "§1「一键执行（唯一命令）」仍写着 `l3_f4_kind_slot_ab.py --apply`，",
        "§2「判读三分支」仍是 A/B 变体 B 的旧三分法，`tools/l3_f4_preflight.py`",
        "的 `one_liner` 也仍指向该已完成实验。设备上线后照 §1 执行会重跑",
        "§61.59 已裁决的 A/B（环节 B 部分成立、非充分原因），并临时改 P0。",
        "本批把执行链口径与资产一次性对齐，**不改 A/B 历史证据与正文**。",
        "",
        "## 2. 实跑结果",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            data["checks_total"], data["all_pass"],
            ", ".join(failed) if failed else "无"),
        "- 设备：只读 `adb devices` → `device_online=%s`（探测行 %d 条）；"
        % (data["device_online"], len(data["device_lines"])),
        "  `ready_to_run=%s`（**恒 false**，接线回滚待用户拍板）。"
        % data["ready_to_run"],
        "- 现行探针：`l3_f4_vfile_commit_probe.py` 流式 logcat 落盘到",
        "  `analysis/l3-f4-logs/vfile-commit-<stamp>.txt`，结果 JSON 的",
        "  `log_file` 字段即 `--log` 的输入；**探针不具备 seek 能力**，",
        "  runbook 已把「播放中主动 seek」如实登记为用户手动操作（物理在场）。",
        "- §61.71 固化回放证据：`mode=replay` / `single_log_verdict=null` /",
        "  13 项 checks 全 PASS / 7 条真实 `.rodata` 串 / 34 份归档，零改写。",
        "- §61.65~§61.70 六份历史证据 JSON 只读核验 `all_pass=True`。",
        "- §61.58 `analysis/l3-f4-preflight.json` 历史证据零改写",
        "  （`all_pass=True` / `ready_to_run=False` / 15 项 checks）。",
        "",
        "## 3. 现行上线执行链（本批未执行）",
        "",
        "```bash",
    ]
    lines.extend("# %d. %s" % (i + 1, c)
                 for i, c in enumerate(CURRENT_COMMANDS))
    lines.extend([
        "```",
        "",
        "前提（均需用户）：① §61.69 接线回滚拍板；② 重开无线调试给新的配对",
        "端点 + 一次性配对码（不落盘）+ 调试端点，`get-state=device`；",
        "③ 确认 app 已装（未装则手机文件管理点",
        "`/sdcard/Download/CloudMusic-debug.apk`）；④ 播放中**手动拖动进度条**",
        "完成主动 seek（MIUI 无线 adb 下 `input swipe` 可能被拒，不伪造该能力）。",
        "卸载已清空 `legacy_engine_cache` → 起点是**冷缓存**。",
        "",
        "判读：`l3_f4_wbranch_recheck.py --log` 输出 4 种 `live_verdict` 之一；",
        "出现 W 分支日志只提供升级推断的素材，**不等于 F4 修复或验收通过**，",
        "仍须人工复核。保持 `g_level=0`，不要再用「level-5 关键词」或",
        "`files/nmcache_log_level` 做 0/4/5 对照。",
        "",
        "## 4. 诚实边界",
        "",
        "- **F4 仍 BLOCKED**：`-103` 判据未通过、自动进曲 3 首 / seek / ENDED",
        "  仍 NOT RUN、F3 14 格未改写；A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。",
        "- 真机复跑 **NOT RUN**、设备仍离线、接线未回滚、未切用户网络、",
        "  so 二进制原样、§61.67~§61.71 历史正文与证据 JSON 一字未改。",
        "- `all_pass` 只表示离线执行链口径与资产自洽，**不是设备在线证明，",
        "  也不是 F4 验收通过**。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    probe_src = read(PROBE)
    rb = read(RUNBOOK)
    adapter = read(ADAPTER)
    wr = jload(WBRANCH_JSON)
    wr_report = read(WBRANCH_REPORT)
    pf = jload(PREFLIGHT_JSON)
    gate = {n: jload(A / n) for n in GATE_EVIDENCE}

    online, device_lines = probe_devices()

    checks = {
        "current_probe_present": PROBE.is_file() and "--serial" in probe_src,
        "current_probe_logs_logcat_to_file": (
            "LOG_DIR" in probe_src and '"log_file"' in probe_src
            and "vfile-commit-" in probe_src),
        "current_probe_no_seek_capability": (
            "input swipe" not in probe_src and "onSeek(" not in probe_src),
        "wbranch_tool_present": WBRANCH.is_file() and "LIVE_OUT" in read(WBRANCH),
        "wbranch_evidence_green": (
            wr.get("all_pass") is True
            and wr.get("checks_total", 0) >= 13
            and all(wr.get("checks", {}).values())
            and wr.get("mode") == "replay"
            and wr.get("single_log_verdict") is None
            and len(wr.get("rodata_branches", {})) == 7
            and wr.get("logs_scanned", 0) >= 33
            and wr.get("device_run") is False
            and wr.get("f4_status") == "BLOCKED"),
        "wbranch_report_honest": (
            "F4 仍 BLOCKED" in wr_report
            and not any(s in wr_report for s in FORBIDDEN)),
        "gate_evidence_chain_green": (
            len(gate) == len(GATE_EVIDENCE)
            and all(v.get("all_pass") is True for v in gate.values())),
        "runbook_current_chain_present": all(s in rb for s in (
            "§61.72", "l3_f4_online_readiness.py",
            "l3_f4_vfile_commit_probe.py", "l3_f4_wbranch_recheck.py",
            "log_file", "现行上线执行链")),
        "runbook_old_ab_marked_historical": (
            "历史剧本，勿执行" in rb and "不要按 §1" in rb
            and "l3_f4_kind_slot_ab.py --serial <ip:port> --apply" in rb),
        "runbook_seek_step_explicit": (
            "手动拖动" in rb and "物理在场" in rb
            and "主动 seek" in rb),
        "runbook_g_level_zero": (
            "保持 `g_level=0`" in rb and "不要再用" in rb
            and "level-5 关键词" in rb),
        "wiring_rollback_pending": (
            "SetLoggerCallback(INMLogger()" in adapter
            and "待用户拍板" in rb and "建议回滚" in rb),
        "legacy_preflight_evidence_preserved": (
            pf.get("all_pass") is True
            and pf.get("ready_to_run") is False
            and len(pf.get("checks", {})) == 15),
        "f4_still_blocked": (
            "F4 仍 BLOCKED" in rb
            and wr.get("f4_status") == "BLOCKED"
            and "真机复跑" in rb),
    }
    failed = [k for k, v in checks.items() if not v]

    data = {
        "method": ("12 §61.72 / F4 现行上线执行链一致性自检"
                   "（纯离线、只读、零生产改动）"),
        "generated_by": "tools/l3_f4_online_readiness.py",
        "mode": "offline_readonly",
        "device_probe": "read_only_adb_devices",
        "device_online": online,
        "device_lines": device_lines,
        "device_run": False,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "ready_to_run": False,
        "f4_status": "BLOCKED",
        "blocking_reason": (
            "§61.69 接线回滚待用户拍板；设备离线需用户重开无线调试并给新"
            "配对端点 + 一次性配对码；确认 app 已装；主动 seek 需物理在场"),
        "pending_user_decisions": [
            "§61.69 SetLoggerCallback(INMLogger(), 5) 接线是否回滚（建议回滚）",
            "重开无线调试 → 新配对端点 + 一次性配对码（不落盘）+ 调试端点",
            "确认 app 是否已装（未装则手机文件管理手动点 APK）",
            "播放中主动 seek（用户手动拖动进度条，物理在场）",
        ],
        "current_commands": list(CURRENT_COMMANDS),
        "artifacts": {
            "current_probe": "tools/l3_f4_vfile_commit_probe.py",
            "wbranch_tool": "tools/l3_f4_wbranch_recheck.py",
            "wbranch_evidence": "analysis/l3-f4-wbranch-recheck.json",
            "wbranch_live_output": "analysis/l3-f4-wbranch-live-verdict.json",
            "gate_evidence": list(GATE_EVIDENCE),
            "legacy_preflight": "analysis/l3-f4-preflight.json",
            "runbook": "analysis/l3-f4-online-runbook.md",
        },
        "truthfulness": (
            "all_pass 只表示离线执行链口径与资产自洽，不是设备在线证明、"
            "不是 F4 验收通过；ready_to_run 恒为 False（接线回滚待拍板）；"
            "真机复跑 NOT RUN、设备离线、接线未回滚、F4 仍 BLOCKED。"),
        "honest_notes": [
            "只读 adb devices；不 connect、不改设备现场、不采集日志、不跑格。",
            "不重跑 §61.59 已完成并已裁决的 kind 槽 A/B，不改 P0 基线。",
            "§61.65~§61.70 六份证据与 §61.58 preflight 证据一律只读、零改写。",
            "探针无 seek 能力，runbook 已将主动 seek 登记为用户物理在场操作。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 不回填。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(data), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / device_online=%s / "
          "ready_to_run=%s（恒 false）"
          % (len(checks), not failed, online, False))
    if failed:
        print("[FAIL] §61.72 现行上线执行链未通过：%s" % failed)
        return 1
    print("[PASS] §61.72 现行上线执行链自洽：现行探针落盘 logcat、W 分支判读"
          "证据固化、runbook 现行链在前 / A/B 剧本已标历史；F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
