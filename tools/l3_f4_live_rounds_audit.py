#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.73 / F4 · 三轮真机纯观测汇总审计（纯离线、只读、零生产改动）。

2026-11-11 用户给出新调试端点 10.0.0.2:37509，设备短暂在线窗口内完成：
  * app 原本未装（§61.69 卸载后的冷缓存现场）→ 用**不含 §61.69 level-5
    接线的历史基线 APK** `app-debug.apk` 安装成功（MIUI 这次未阻挡）；
    P0 源码未改，接线回滚仍待用户拍板。
  * 三轮各 60s 纯观测：冷缓存 / 暖缓存 / 用户配合的「主动 seek」轮。
  * 每轮先跑 l3_f4_vfile_commit_probe.py，再用
    l3_f4_wbranch_recheck.py --log 按真实 .rodata 格式串判读。

本工具只读取已落盘的证据（probe JSON / live verdict JSON / logcat），
做一致性核验并汇总，**不连 adb、不采集日志、不重跑任何真机实验、
不覆盖 §61.71 固化回放证据**。产出
analysis/l3-f4-live-rounds-audit.json 与
analysis/l3-f4-live-rounds-report.md。

诚实边界：W 级目标格式串三轮全 0 命中是「分支未触发的有效负结果」；
主动 seek 轮日志无 seek 痕迹，判据仍 NOT RUN；`-103` / `Write start play`
判据未通过。F4 仍 BLOCKED。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
OUT = A / "l3-f4-live-rounds-audit.json"
REPORT = A / "l3-f4-live-rounds-report.md"

ROUNDS = (
    ("cold", A / "l3-f4-live-online.json", A / "l3-f4-live-cold-verdict.json",
     "冷缓存（全新安装后首轮）"),
    ("warm", A / "l3-f4-live-online-warm.json", A / "l3-f4-live-warm-verdict.json",
     "暖缓存（不清缓存第二轮）"),
    ("seek", A / "l3-f4-live-online-seek.json", A / "l3-f4-live-seek-verdict.json",
     "用户口述已拖动 seek 轮（总时长 00:00，拖动未生效）"),
)
WBRANCH_EVIDENCE = A / "l3-f4-wbranch-recheck.json"
UI_SCREENSHOT = A / "l3-f4-live-ui-20261111.png"
ADAPTER = (ROOT / "engine-legacy" / "src" / "main" / "java" /
           "com" / "example" / "cloudmusic" / "enginelegacy" /
           "EngineAdapter.kt")
FORBIDDEN = ("F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
             "推断已升级为实证", "seek 已执行")

SERIAL = "10.0.0.2:37509"
APK_BASELINE = "app/build/outputs/apk/debug/app-debug.apk"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def build_report(data: dict) -> str:
    lines = [
        "# F4 · 三轮真机纯观测汇总（§61.73，2026-11-11）",
        "",
        "> **性质**：设备短暂在线窗口内完成的真机取证；本报告为**离线只读汇总**，",
        "> 不连 adb、不采集日志、不重跑实验、不覆盖 §61.71 固化回放证据。",
        "> **F4 仍 BLOCKED**。",
        "",
        "## 1. 现场与前提",
        "",
        "- 端点：`%s`（Redmi K60 `23013RK75C` / `mondrian`，" % SERIAL,
        "  `get-state=device`）；用户先给的 `10.0.0.2:40467` 连接超时 10060，",
        "  已 disconnect。全端口 TCP 扫描出现 7848 个「开放」端口系本机",
        "  `10.0.0.3` 网卡的代理假象，**不作证据**。",
        "- app 原本**未安装**（`pm path` 空 / `run-as` unknown package），即",
        "  §61.69 卸载后的**冷缓存**现场。",
        "- 安装 `%s`（31433878 B / md5 `7c1eb6e6…39`）→" % APK_BASELINE,
        "  `adb -s %s install -r` **Success**（MIUI 本次未阻挡）。" % SERIAL,
        "  该 APK 的 dex **无 `nmcache_log_level` 字符串**，是 §61.69 接线之前的",
        "  历史基线；三轮 logcat 中 `SetLoggerCallback` **0 次** → 一手坐实设备",
        "  运行在 `g_level=0`（I/D/W/E 默认全开），符合 §61.70 观测口径。",
        "- **P0 源码未改**：`EngineAdapter.kt` 的 `SetLoggerCallback(INMLogger(), 5)`",
        "  接线仍在，回滚**待用户拍板**；设备上运行的是不含接线的基线 APK，",
        "  即「运行产物 ≠ 当前源码」，如实登记不混淆。",
        "- 设备第三轮后曾掉线，随后用户给新端点 `10.0.0.2:39257` 重新上线，",
        "  `screencap` 抓到播放页 UI：`analysis/l3-f4-live-ui-20261111.png`。",
        "",
        "### 用户一手观察与 UI 证据（关于「主动 seek」）",
        "",
        "用户明确口述**已执行拖动**，且「进度条也没有时间」。截图核对：确为",
        "播放页（曲目 `f3_m4a_complete` / `回环音源` / `1 首`），进度条左右",
        "**均为 `00:00`（总时长 0）**，播放按钮仍是 ▶（未播放态），红色进度",
        "条约在 1/4 处（拖动留下的位置痕迹），「暂无歌词」。",
        "第三轮 logcat 交叉印证：**无 `mDuration`、无 `Open codec`、仅",
        "`Play status 3`**；冷 / 暖轮虽有 `mDuration 2933` 与 `Open codec for aac`，",
        "但 `Write start play` 仍 0。→ **播放未进入 prepare 完成 / 可 seek 态，",
        "总时长不可用，用户拖动无法转化为有效 seek**；日志中 `onSeek` /",
        "`seekTo` / `Slider` 全 0 与之一致。**seek 判据仍 NOT RUN**，",
        "但原因由「用户未配合」更正为「播放未进入可 seek 态」，不得写成",
        "用户未操作，也不得把 UI 红色进度条当成 seek 已生效的证据。",
        "",
        "## 2. 三轮结果",
        "",
        "| 轮次 | logcat 行数 | commit | 代次 | W 级行 | W 分支命中 | codec | "
        "`Write start play` | `-103` | `result:-2` | onData |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in data["rounds"]:
        a = r["verdict_analysis"]
        lines.append(
            "| %s | %d | %s | %s | %d | %d | %s | %d | %d | %d | %d |" % (
                r["label"], r["log_lines"], r["commit_values"],
                "%d→%d" % (r["generation_first"], r["generation_last"])
                if r["generation_sequence"] else "无",
                a["w_level_lines"], a["w_error_total"] + a["w_success_finish"],
                ",".join(a["open_codec"]) or "无",
                a["write_start_play"], a["finish_async_minus_103"],
                a["read_result_minus_2"], a["java_on_data"]))
    lines.extend([
        "",
        "- 三轮 `MakeRangeRequestAsync(N)` 代次均**严格递增**、`commit` 各自",
        "  **恒定冻结**（253952 / 262144 / 262144），与 §61.68 的 16384 数值",
        "  不同但**现象一致**：读侧重发使旧代次在途包被丢弃，commit 不再前进。",
        "- 冷缓存轮 W 级 `NMSliceFile Open Index File ... Not Exist Not Cached`",
        "  证实冷起点；冷 / 暖轮各有 3 / 1 行 `W/NMCache` → **W 级日志在默认",
        "  `g_level=0` 下确实输出**，把 §61.70 的离线推断升级为真机一手实证。",
        "- **7 条真实 `.rodata` W 分支格式串三轮全部 0 命中** —— 不是日志被",
        "  门控挡住，而是错误 / 边界分支未触发的**有效负结果**；",
        "  `UnExpect New Offsetcommted` 未出现，故「代次错配 → commit 冻结」",
        "  **仍无直接分支日志证据**，不升级为实证、不自动收口。",
        "- 冷 / 暖轮 `Open codec for aac` 打开但 `Write start play` **0 次**；",
        "  `-103` 各 1 次、`result:-2` 6 / 5 次 → **判据未通过**。",
        "- 第三轮（用户口述已拖动）：3001 行、commit 262144、代次 1→4、",
        "  **无 `mDuration` / 无 `Open codec` / 仅 `Play status 3`**、`-103` 0 次、",
        "  W 级 0 行；日志中 `onSeek` / `seekTo` / `Slider` **全部 0 次**。",
        "  结合 UI 截图总时长 `00:00` → 播放未进入可 seek 态，拖动未转化为",
        "  有效 seek，**seek 判据如实 NOT RUN**。`adb shell input swipe` 已实测",
        "  被 MIUI 以 `SecurityException: INJECT_EVENTS` 拒绝，脚本无法代做。",
        "- 三轮 `restore` 字段全 True（wifiOnly 临时文件 / adb reverse /",
        "  http.server / logcat 子进程均已在 finally 恢复，设备系统设置未变）。",
        "  第三轮结束后设备掉线。",
        "",
        "## 3. 实跑核验",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            data["checks_total"], data["all_pass"],
            ", ".join(data["failed_checks"]) or "无"),
        "- §61.71 固化回放证据 sha256 `%s…`，" % data["wbranch_sha256"][:8],
        "  `mode=replay` / `single_log_verdict=null`，**零改写**。",
        "- UI 截图 `analysis/l3-f4-live-ui-20261111.png`（新端点 39257）在库且",
        "  非空；用户口述 + 截图 + 第三轮日志三者交叉一致，分开登记不互相冒充。",
        "- 新增 3 份 logcat / 3 份 probe JSON / 3 份 live verdict；",
        "  §61.71 回放证据仍为 34 份归档的当时快照，**未重跑覆盖**。",
        "",
        "## 4. 诚实边界",
        "",
        "- **F4 仍 BLOCKED**：`-103` 判据未通过、`Write start play` 0、",
        "  自动进曲 3 首 / seek / ENDED 仍 NOT RUN、F3 14 格未改写；",
        "  A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 不回填、指纹不回填。",
        "- 不写任何提前收口结论（禁词表见 §61.71 守护）；",
        "  证据等级提升 ≠ 修复 ≠ 验证。",
        "- 零 P0 源码改动、未重编译 / 未重装含接线 APK、so 二进制原样、",
        "  未改用户网络 / 未 `pm grant`、配对码不落盘、",
        "  §61.67~§61.72 历史正文与证据 JSON 一字未改。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    rounds = []
    for key, pj, vj, label in ROUNDS:
        p, v = jload(pj), jload(vj)
        single = v.get("single_log_verdict") or {}
        a = single.get("analysis") or {}
        log_rel = p.get("log_file", "")
        log_path = ROOT / log_rel if log_rel else None
        text = read(log_path) if log_path else ""
        gens = a.get("generation_sequence") or []
        rounds.append({
            "key": key, "label": label,
            "probe_json": str(pj.relative_to(ROOT)).replace("\\", "/"),
            "verdict_json": str(vj.relative_to(ROOT)).replace("\\", "/"),
            "log_file": log_rel,
            "log_lines": p.get("log_lines", len(text.splitlines())),
            "probe_verdict": p.get("verdict"),
            "mechanism_verdict": p.get("mechanism_verdict"),
            "commit_values": p.get("evidence", {}).get("commit_distinct", []),
            "open_codec": p.get("evidence", {}).get("open_codec", []),
            "write_start_play": len(p.get("evidence", {}).get("write_start_play", [])),
            "has_103": p.get("evidence", {}).get("has_103"),
            "restore": p.get("restore", {}),
            "live_verdict": single.get("live_verdict"),
            "verdict_analysis": a,
            "generation_sequence": gens,
            "generation_first": gens[0] if gens else None,
            "generation_last": gens[-1] if gens else None,
            "setlogger_calls": text.count("SetLoggerCallback"),
            "w_level_lines_in_log": text.count("W/NMCache"),
            "seek_markers": {
                m: text.count(m) for m in ("onSeek", "seekTo", "Slider")
            },
            "duration_lines": text.count("mDuration"),
            "open_codec_lines": text.count("Open codec for"),
            "play_status_lines": text.count("Play status"),
            "cold_cache_index_missing": "Not Exist Not Cached" in text,
        })

    by = {r["key"]: r for r in rounds}
    wr = jload(WBRANCH_EVIDENCE)
    wr_bytes = WBRANCH_EVIDENCE.read_bytes() if WBRANCH_EVIDENCE.is_file() else b""
    adapter = read(ADAPTER)

    def w_zero(r):
        a = r["verdict_analysis"]
        return all(v == 0 for v in (a.get("w_branch_hits") or {}).values())

    # 拼接 marker，避免「检查用的字面串本身就出现在被检查的源码里」导致恒 false。
    no_adb_markers = ("subprocess." + "run", "adb" + ".exe")
    restore_keys = ("reverse_removed", "wifi_gate_file_removed",
                    "http_server_stopped", "logcat_process_stopped",
                    "device_settings_unchanged")

    checks = {
        "three_probe_jsons_observed": (
            len(rounds) == 3
            and all(r["probe_verdict"] == "OBSERVED" for r in rounds)),
        "three_logcats_present": all(
            (ROOT / r["log_file"]).is_file() and r["log_lines"] > 1000
            for r in rounds if r["log_file"]),
        "three_verdicts_offline_judged": all(
            r["live_verdict"] and r["verdict_analysis"] for r in rounds),
        "all_rounds_restored_clean": all(
            all(r["restore"].get(k) is True for k in restore_keys)
            for r in rounds if r["restore"]),
        "g_level_zero_no_setlogger_in_any_round": all(
            r["setlogger_calls"] == 0 for r in rounds),
        "w_level_output_visible_in_default_config": (
            by["cold"]["w_level_lines_in_log"] > 0
            and by["warm"]["w_level_lines_in_log"] > 0),
        "cold_cache_index_missing_confirmed": by["cold"]["cold_cache_index_missing"],
        "seven_rodata_w_branches_zero_in_all_rounds": all(
            w_zero(r) for r in rounds),
        "commit_frozen_in_all_rounds": all(
            len(r["commit_values"]) == 1 for r in rounds),
        "generation_strictly_increasing_in_all_rounds": all(
            r["generation_sequence"] == list(range(1, len(r["generation_sequence"]) + 1))
            for r in rounds),
        "no_write_start_play_in_any_round": all(
            r["write_start_play"] == 0 for r in rounds),
        "seek_round_has_no_seek_evidence": all(
            v == 0 for v in by["seek"]["seek_markers"].values()),
        "seek_round_no_duration_and_no_codec": (
            by["seek"]["duration_lines"] == 0
            and by["seek"]["open_codec_lines"] == 0
            and by["seek"]["play_status_lines"] >= 1),
        "cold_and_warm_have_duration": (
            by["cold"]["duration_lines"] >= 1
            and by["warm"]["duration_lines"] >= 1),
        "ui_screenshot_present": (
            UI_SCREENSHOT.is_file() and UI_SCREENSHOT.stat().st_size > 10000),
        "wbranch_replay_evidence_intact": (
            wr.get("mode") == "replay"
            and wr.get("single_log_verdict") is None
            and wr.get("all_pass") is True
            and len(wr_bytes) > 0),
        "p0_wiring_still_present_pending_decision": (
            "SetLoggerCallback(INMLogger()" in adapter),
        "f4_still_blocked": (
            all(r["write_start_play"] == 0 for r in rounds)
            and by["cold"]["has_103"] is True
            and by["warm"]["has_103"] is True),
        "device_run_false_for_this_audit": all(
            m not in read(Path(__file__)) for m in no_adb_markers),
    }
    failed = [k for k, v in checks.items() if not v]

    data = {
        "method": ("12 §61.73 / F4 三轮真机纯观测汇总审计"
                   "（纯离线、只读、零生产改动）"),
        "generated_by": "tools/l3_f4_live_rounds_audit.py",
        "mode": "offline_readonly",
        "device_run": False,
        "serial": SERIAL,
        "device_model": "Redmi K60 23013RK75C / mondrian",
        "endpoint_40467": "connect 超时 10060，已 disconnect；不作证据",
        "port_scan_note": ("本机 10.0.0.3 网卡对大范围 TCP 均返回成功，"
                           "7848 个「开放端口」属代理假象，不作证据"),
        "apk_installed": {
            "path": APK_BASELINE,
            "reason": ("app 未装（§61.69 卸载后冷缓存现场）；"
                       "选 §61.69 接线之前的基线 APK，使 g_level=0"),
            "install_result": "Success",
            "contains_level5_wiring": False,
            "p0_source_modified": False,
        },
        "rounds": rounds,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "f4_status": "BLOCKED",
        "wbranch_sha256": hashlib.sha256(wr_bytes).hexdigest(),
        "seek_status": (
            "NOT RUN —— 用户口述已拖动，但 UI 总时长 00:00、日志无 mDuration / "
            "Open codec / onSeek / seekTo / Slider；播放未进入可 seek 态，"
            "拖动未转化为有效 seek"),
        "user_reported_seek_attempt": True,
        "user_reported_progress_bar_no_time": True,
        "ui_evidence": {
            "path": "analysis/l3-f4-live-ui-20261111.png",
            "source": "adb exec-out screencap -p（新端点 10.0.0.2:39257）",
            "observed": ("播放页：曲目 f3_m4a_complete / 回环音源 / 1 首；"
                         "进度条左右均 00:00（总时长 0）；播放按钮为 ▶ 未播放态；"
                         "红色进度条约 1/4 处（拖动留下的位置痕迹）；暂无歌词"),
            "note": ("用户口述已执行拖动 + 口述进度条无时间；截图为其一手 UI 佐证，"
                     "与第三轮日志无 mDuration / 无 Open codec 交叉一致；"
                     "UI 红色进度条不作为 seek 已生效的证据"),
        },
        "input_swipe_result": ("SecurityException: Injecting input events requires "
                              "INJECT_EVENTS permission（MIUI 无线 adb 拒绝）"),
        "device_state_at_end": (
            "第三轮后曾掉线；用户给新端点 10.0.0.2:39257 重新上线并抓取 UI 截图"),
        "truthfulness": (
            "W 级目标格式串三轮全 0 命中是分支未触发的有效负结果；"
            "g_level=0 由 SetLoggerCallback 0 次与 W 级日志可见双重坐实；"
            "用户口述已拖动且 UI 截图总时长 00:00，但第三轮日志无 mDuration / "
            "Open codec / onSeek / seekTo / Slider → 播放未进入可 seek 态、"
            "主动 seek 判据 NOT RUN（原因不是用户未配合）；"
            "-103 / Write start play 判据未通过；F4 仍 BLOCKED，"
            "证据等级提升 ≠ 修复 ≠ 验证。"),
        "honest_notes": [
            "零 P0 源码改动；设备上运行不含接线的历史基线 APK，与当前源码不同，如实登记。",
            "三轮 restore 全 True；未改用户网络 / 未 pm grant / 配对码不落盘。",
            "用户口述与 UI 截图属一手观察，与日志证据分开登记，不互相冒充。",
            "§61.71 固化回放证据零改写；新增 3 份 logcat 未触发重跑覆盖。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 不回填。",
            "接线回滚仍待用户拍板；本批未执行回滚。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(data), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / F4=%s"
          % (len(checks), not failed, data["f4_status"]))
    if failed:
        print("[FAIL] §61.73 汇总审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.73 三轮真机纯观测自洽：g_level=0 一手坐实、"
          "commit 三轮冻结、W 分支 0 命中为有效负结果；F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
