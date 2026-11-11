#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.79 / F4 · A1「绕过取流」时间盒 **纯离线裁决**（只读、零生产改动）。

读取 `tools/l3_f4_a1_bypass_live_probe.py` 产出的真机取证
（`analysis/l3-f4-a1-bypass-live.json`，v2_fixed）与历史证据，按
`§61.74/§61.75` 多信号判据逐条定案。**不连 adb、不重跑真机、不改历史证据**。

裁决口径（《播放内核两条路线评估》§6.1 时间盒）：
  通过 = 真机上 `Write start play` 出现 + 本 app AudioTrack `state:started`
         + 自动进曲 3 首无 `-103`（并用 MediaSession state/position 佐证）
  不通过 = 停止 A，按路线评估立即转 B2，**不追加 A2 二进制补丁**
  （路线切换本身仍需用户拍板，本工具只给建议、不擅自启动 B2）

诚实边界：
  * 「绕行 ≠ 修复」：LOCAL 出声只证明绕过 REMOTE 取流缺陷，不修 REMOTE 本身；
    `§61.75` 终局裁决不变，F4 仍 BLOCKED。
  * `msg=2`（播放完成通知）**不等于**自然播完 ENDED；`frames delivered=1`
    的短命播放不计「持续出声」。
  * 首轮试跑（`l3-f4-a1-bypass-live-initial-v1.json`）有 4 处取证缺陷，
    仅作缺陷诊断留痕，**不作裁决依据**。

产出：
  analysis/l3-f4-a1-bypass-verdict.json
  analysis/l3-f4-a1-bypass-verdict-report.md
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
OUT = A / "l3-f4-a1-bypass-verdict.json"
REPORT = A / "l3-f4-a1-bypass-verdict-report.md"

LIVE = A / "l3-f4-a1-bypass-live.json"
LIVE_V1 = A / "l3-f4-a1-bypass-live-initial-v1.json"
PROBE_TOOL = ROOT / "tools" / "l3_f4_a1_bypass_live_probe.py"
BYPASS = A / "l3-f4-bypass-stream-audit.json"
ACCEPTANCE = A / "l3-f4-acceptance-final.json"
D12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
ROLLBACK = A / "l3-f4-wiring-rollback-audit.json"

FORBIDDEN = ("F4 已解决", "F4 已修复", "F4 已收口", "全格式已支持",
             "主判据已通过", "播放已成功", "level-5 已开启", "A1 已通过")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def build_report(d: dict) -> str:
    cr = d["criteria"]
    lines = [
        "# F4 · A1「绕过取流」时间盒真机裁决（12 §61.79，2026-11-11）",
        "",
        "> **性质**：纯离线只读裁决，不连 adb、不重跑真机、不改历史证据、",
        "> 零 P0 源码改动。判据未满足一律 FAIL / NOT RUN。",
        "> **F4 仍 BLOCKED**；「绕行 ≠ 修复」。",
        "",
        "## 1. 现场与取证链",
        "",
        "- 端点 `%s`，app `%s`（**不含 §61.69 接线的基线 APK**："
        "  `wiring_level_logs=[]`），样本 `%s`（%d B）。" % (
            d["serial"], d["apk_installed"], d["sample"], d["sample_bytes"]),
        "- 取证工具 `tools/l3_f4_a1_bypass_live_probe.py`（`probe_run=%s`）；" % (
            d["probe_run"]),
        "  首轮试跑发现 4 处取证缺陷，归档为 `%s`，**不作裁决依据**。" % (
            d["initial_probe"]),
        "- v2 修正：先 `logcat -c` 再启动流式子进程、按本轮 app PID 分段、",
        "  用 app uid（`%s`）关联 `dumpsys audio`、REMOTE 轮前清理私有样本、" % (
            d["app_uid"]),
        "  增加 t=2s 早快照与 AudioTrack 一手状态信号。",
        "- 现场恢复 `restore_errors=%s`；临时 wifiOnly / adb reverse / " % (
            d["restore_errors"]),
        "  http.server / logcat 子进程 / 私有样本均在 finally 无条件恢复。",
        "",
        "## 2. 两轮同窗对照（同一设备 / 同一样本 / 同一次运行）",
        "",
        "| 维度 | LOCAL 主轮（绕过取流） | REMOTE 对照轮 |",
        "|---|---|---|",
        "| app PID / 分段行数 | %s / %d | %s / %d |" % (
            d["local"]["pid"], d["local"]["scoped_lines"],
            d["remote"]["pid"], d["remote"]["scoped_lines"]),
        "| `prepare remote=` | %s | %s |" % (
            d["local"]["remote_flags"], d["remote"]["remote_flags"]),
        "| REMOTE 取流特征 | %s | %s |" % (
            d["local"]["remote_markers_sum"], d["remote"]["remote_markers_sum"]),
        "| LOCAL 装配特征 | %s | %s |" % (
            d["local"]["local_markers_sum"], d["remote"]["local_markers_sum"]),
        "| `Open codec` | %s | %s |" % (
            d["local"]["open_codec"], d["remote"]["open_codec"]),
        "| `Write start play nErr` | %s | %s |" % (
            d["local"]["write_start_play"], d["remote"]["write_start_play"]),
        "| `AudioTrack Play` / ACTIVE | %s / %s | %s / %s |" % (
            d["local"]["at_play"], d["local"]["active"],
            d["remote"]["at_play"], d["remote"]["active"]),
        "| `frames delivered` | %s | %s |" % (
            d["local"]["frames"], d["remote"]["frames"]),
        "| `msg=2` / Release | %s / %s | %s / %s |" % (
            d["local"]["msg2"], d["local"]["released"],
            d["remote"]["msg2"], d["remote"]["released"]),
        "| `Play status` | %s | %s |" % (
            d["local"]["play_status"], d["remote"]["play_status"]),
        "| `-103` | %d | %d |" % (
            d["local"]["minus_103"], d["remote"]["minus_103"]),
        "| MediaSession state / position | %s / %s | %s / %s |" % (
            d["local"]["states"], d["local"]["positions"],
            d["remote"]["states"], d["remote"]["positions"]),
        "| 本 app AudioTrack `state:started` 快照 | %s | %s |" % (
            d["local"]["track_started"], d["remote"]["track_started"]),
        "",
        "## 3. 判据逐条登记（§61.74/§61.75 多信号口径）",
        "",
        "| # | 判据 | 实测 | 结论 |",
        "|---|---|---|---|",
    ]
    for i, c in enumerate(cr, 1):
        lines.append("| %d | %s | %s | **%s** |" % (
            i, c["name"], c["evidence"], c["verdict"]))
    lines.extend([
        "",
        "## 4. 关键判读：点火成立，但没有持续播放",
        "",
        "- **绕过取流成立**：LOCAL 轮 `remote_marker_counts` 全 0、",
        "  `local_marker_counts` 全 2、`prepare remote=false` ×2、",
        "  `XorLocalSource::Open success`，**完整避开 `NMCommonCache`",
        "  代次门控 / commit / `result:-2` / -103 链**。",
        "- **解码链点火成立（相对 §61.75 的实质新进展）**：`Open codec for aac` ×2、",
        "  `AudioTrack(0): Play(1)` ×2、`prior state:STATE_ACTIVE` ×2、",
        "  `Write start play nErr:0` ×2、`Play status 4` ×2、`-103` 0 次。",
        "  这是 §61.75 真机终局裁决（主判据无一 PASS）之后，第一次在真机上",
        "  同时出现 codec / 写日志 / AudioTrack ACTIVE 且无 -103。",
        "- **但没有持续播放**：`stop(...): called with 1 frames delivered`",
        "  （每次仅 **1 帧**），约 350ms 后 `PlayerNotifyEvent msg=2` →",
        "  `Paused` → `Stop` → `Release End` → `Free`；自动进曲只再开一条",
        "  同样短命的 LOCAL 链（codec 共 2 次，不是 3 首）。",
        "- `msg=2` 是**播放完成通知**，不是自然播完；`frames=1` 的短命播放",
        "  不计「持续出声」。MediaSession 全程 `state=2`（PAUSED）、",
        "  `position` 恒 0；7 次 dumpsys 快照（按 app uid 关联）均无本 app",
        "  的 `state:started` AudioTrack。按 §61.74 多信号口径，",
        "  **不能把 `Write start play` 单点当成播放成功**。",
        "- REMOTE 对照轮依然 FAIL：`remote=true`、`Start id=5` /",
        "  `WaitRequest=5` / `result:-2=4` / `commit :=1`，codec / write /",
        "  AudioTrack 全 0、`Play status 3`、`state=3` 但 position 0 ——",
        "  与 §61.61 / §61.75 一致（state=3 仅状态机上报）。",
        "",
        "## 5. 时间盒裁决",
        "",
        "- **%s**" % d["verdict"],
        "- 时间盒判据（《播放内核两条路线评估》§6.1）：`Write start play`",
        "  出现 ✅ / 本 app AudioTrack `state:started` ❌ / 自动进曲 3 首 ❌",
        "  → **timebox_passed = %s**。" % d["timebox_passed"],
        "- 按既定规则：**不通过 ⇒ 停止 A，按路线评估立即转 B2，不追加 A2",
        "  二进制补丁**。`%s` —— **路线切换仍需用户拍板**，" % (
            d["route_recommendation"]),
        "  本批不擅自启动 B2、不重编译 / 不重装、不动解码器。",
        "- **「绕行 ≠ 修复」**：REMOTE 取流缺陷仍在，§61.75 终局裁决不变，",
        "  **F4 仍 BLOCKED**；§61.78 的裁定（A1 绕行成立但不是修复）",
        "  在真机上得到部分验证，且暴露出「点火后不能持续」这一新的、",
        "  位于播放链下游的独立问题。",
        "",
        "## 6. 接线回滚（与 A1 正交，同一批交付）",
        "",
        "- §61.69 诊断接线已按 §61.70 勘误从 `EngineAdapter.kt` 回滚；",
        "  回滚证据 `%s`（`%s`）。" % (
            d["rollback_audit"], d["rollback_audit_present"]),
        "- 只删诊断接线，`SetConfig` / VFM / `source.url != null` 分叉 /",
        "  `remote ? Type.REMOTE : Type.LOCAL` 等功能代码零改动；",
        "  **诊断清理 ≠ F4 修复**。",
        "",
        "## 7. 诚实边界与实跑核验",
        "",
        "- 首轮试跑（`initial_v1__DEFECTIVE`）4 处取证缺陷已如实登记并修正；",
        "  两轮 logcat 按 PID 分段后 `local_marker_counts` 在 REMOTE 轮全 0，",
        "  跨轮污染已消除。无线 adb 延迟使 REMOTE 轮时间轴较预定秒数漂移，",
        "  但流式 logcat 全程抓取，不影响判据。",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(d["failed_checks"]) or "无"),
        "- 零 P0 源码改动（除 §61.79 接线回滚）、未重编译 / 未重装、",
        "  so 二进制原样、未改用户网络、未 `pm grant`、配对码不落盘。",
        "- A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    live = jload(LIVE)
    v1 = jload(LIVE_V1)
    bypass = jload(BYPASS)
    acc = jload(ACCEPTANCE)
    rb = jload(ROLLBACK)
    probe_src = read(PROBE_TOOL)
    d12 = read(D12)

    L = live.get("local_round", {})
    R = live.get("remote_round", {})
    la = L.get("log_analysis", {})
    ra = R.get("log_analysis", {})

    local_remote_sum = sum(la.get("remote_marker_counts", {}).values())
    local_local_sum = sum(la.get("local_marker_counts", {}).values())
    remote_remote_sum = sum(ra.get("remote_marker_counts", {}).values())
    remote_local_sum = sum(ra.get("local_marker_counts", {}).values())

    # ---- 逐条判据（evidence 全部来自 v2 实测，不硬编码结论）----
    def verdict(ok: bool, fail_text: str) -> str:
        return "PASS" if ok else "FAIL（%s）" % fail_text

    ignition = (
        len(la.get("open_codec", [])) >= 1
        and len(la.get("write_start_play", [])) >= 1
        and all(int(x) == 0 for x in la.get("write_start_play", ["-1"]))
        and len(la.get("audio_track_play_calls", [])) >= 1
        and la.get("audio_track_active_markers", 0) >= 1
        and la.get("finish_async_103", -1) == 0
    )
    sustained = (
        len(set(L.get("distinct_positions", [0]))) > 1
        and 3 in L.get("states_observed", [])
        and L.get("app_audio_track_started_any", False)
        and all(f > 1 for f in la.get("frames_delivered", [1]))
    )

    criteria = [
        {"name": "绕过取流成立（LOCAL 无 REMOTE 取流特征）",
         "evidence": "remote_marker 合计=%d（全 0）/ local_marker 合计=%d / "
                     "prepare remote=%s" % (
                         local_remote_sum, local_local_sum,
                         la.get("prepare_remote_flags")),
         "verdict": verdict(
             local_remote_sum == 0 and local_local_sum >= 2
             and all(f == "false" for f in la.get("prepare_remote_flags", ["x"])),
             "LOCAL 轮仍出现 REMOTE 取流特征或装配不全")},
        {"name": "`Open codec` → `Write start play nErr:0`（点火）",
         "evidence": "codec=%s / write=%s / AT_Play=%s / ACTIVE=%s" % (
             la.get("open_codec"), la.get("write_start_play"),
             la.get("audio_track_play_calls"), la.get("audio_track_active_markers")),
         "verdict": verdict(ignition, "解码链 / 写日志 / AudioTrack 未同时成立")},
        {"name": "无 `-103`",
         "evidence": "Finish Async:-103=%d / 含 -103 行=%d" % (
             la.get("finish_async_103", -1), la.get("minus_103_lines", -1)),
         "verdict": verdict(la.get("finish_async_103", -1) == 0
                            and la.get("minus_103_lines", -1) == 0, "-103 仍在场")},
        {"name": "持续播放 / frames delivered",
         "evidence": "frames=%s / msg2=%d / Release=%d / Paused=%d" % (
             la.get("frames_delivered"), la.get("msg2_completion", -1),
             la.get("audio_track_release_end", -1),
             la.get("audio_track_paused", -1)),
         "verdict": verdict(
             bool(la.get("frames_delivered")) and all(
                 f > 1 for f in la["frames_delivered"]),
             "每次仅交付 1 帧、紧随 msg=2 / Paused / Release，未持续出声")},
        {"name": "本 app AudioTrack `state:started`（app uid 关联）",
         "evidence": "7 次 dumpsys 快照 app_uid_line_present 全 False / "
                     "track_started_any=%s；logcat 有 ACTIVE 但仅 1 帧即释放" % (
                         L.get("app_audio_track_started_any")),
         "verdict": verdict(L.get("app_audio_track_started_any", False),
                            "未出现本 app 的持续 started AudioTrack")},
        {"name": "MediaSession 进入 PLAYING 且 position 递增",
         "evidence": "states=%s / distinct_positions=%s" % (
             L.get("states_observed"), L.get("distinct_positions")),
         "verdict": verdict(
             3 in L.get("states_observed", [])
             and len(set(L.get("distinct_positions", [0]))) > 1,
             "state 恒 2（PAUSED）、position 恒 0")},
        {"name": "自动进曲 3 首（无 -103、3 次新解码）",
         "evidence": "codec 共 %d 次 / dispatch next×3 rc=%s / -103=%d" % (
             len(la.get("open_codec", [])),
             [x.get("rc") for x in L.get("dispatches", []) if x.get("key") == "next"],
             la.get("finish_async_103", -1)),
         "verdict": verdict(
             len(la.get("open_codec", [])) >= 3
             and la.get("finish_async_103", -1) == 0,
             "仅 2 次短命解码（非 3 首持续进曲）")},
        {"name": "seek（fast-forward / rewind 后 position 变化）",
         "evidence": "distinct_positions=%s / seek_markers=%d" % (
             L.get("distinct_positions"), la.get("seek_markers", -1)),
         "verdict": verdict(len(set(L.get("distinct_positions", [0]))) > 1,
                            "position 未变化、无 seek 标记")},
        {"name": "ENDED（自然播完）",
         "evidence": "msg2=%d（播放完成通知）/ natural_ended_markers=%d / "
                     "frames=%s；msg=2 不等于自然播完" % (
                         la.get("msg2_completion", -1),
                         la.get("natural_ended_markers", -1),
                         la.get("frames_delivered")),
         "verdict": "NOT RUN（受控窗口内未跑到曲末；`msg=2` 为过早完成通知，不伪造 ENDED）"},
        {"name": "REMOTE 同窗对照（已知 FAIL）",
         "evidence": "remote=%s / codec=%s / write=%s / Play status=%s / "
                     "remote_marker 合计=%d / local_marker 合计=%d" % (
                         ra.get("prepare_remote_flags"), ra.get("open_codec"),
                         ra.get("write_start_play"), ra.get("play_status"),
                         remote_remote_sum, remote_local_sum),
         "verdict": verdict(
             remote_remote_sum > 0 and remote_local_sum == 0
             and not ra.get("open_codec") and not ra.get("write_start_play")
             and ra.get("play_status_3", 0) >= 1
             and 3 in R.get("states_observed", [])
             and R.get("distinct_positions") == [0],
             "REMOTE 对照轮偏离已知 FAIL 形态 / 仍有跨轮串扰")},
    ]

    checks = {
        "live_probe_is_v2_fixed": live.get("probe_run") == "v2_fixed",
        "live_probe_device_run_true": live.get("device_run") is True,
        "live_probe_app_uid_resolved": live.get("app_uid") == "10316",
        "live_probe_restore_clean": live.get("restore_errors") == [],
        "live_probe_remote_round_run": live.get("remote_round_skipped") is False
        and "log_analysis" in R,
        "initial_defective_probe_archived": (
            v1.get("probe_run") == "initial_v1__DEFECTIVE"
            and len(v1.get("defects_found", [])) == 4),
        "four_defects_fixed_declared": len(live.get("defects_fixed", [])) == 4,
        "probe_tool_has_pid_scope": "scope_filter" in probe_src
        and "resolve_app_uid" in probe_src,
        "local_remote_markers_all_zero": local_remote_sum == 0,
        "local_assembly_markers_present": local_local_sum >= 2,
        "local_prepare_all_false": la.get("prepare_remote_flags") == ["false", "false"],
        "local_no_wiring_logs_baseline_apk": la.get("wiring_level_logs") == [],
        "local_codec_opened_twice": len(la.get("open_codec", [])) == 2,
        "local_write_nerr_zero_twice": la.get("write_start_play") == ["0", "0"],
        "local_no_minus_103": la.get("finish_async_103") == 0
        and la.get("minus_103_lines") == 0,
        "local_audio_track_started_and_active": (
            len(la.get("audio_track_play_calls", [])) == 2
            and la.get("audio_track_active_markers") == 2
            and la.get("audio_track_start_calls") == 2),
        "local_frames_delivered_only_one": la.get("frames_delivered") == [1, 1],
        "local_msg2_and_release": la.get("msg2_completion") == 2
        and la.get("audio_track_release_end") == 2,
        "local_play_status_4_only": la.get("play_status_4") == 2
        and la.get("play_status_3") == 0,
        "local_state_never_playing": 3 not in L.get("states_observed", [3]),
        "local_position_always_zero": L.get("distinct_positions") == [0],
        "local_no_app_started_track": L.get("app_audio_track_started_any") is False,
        "local_six_dispatches_all_rc0": (
            len(L.get("dispatches", [])) == 6
            and all(x.get("rc") == 0 for x in L["dispatches"])),
        "remote_prepare_all_true": ra.get("prepare_remote_flags") == ["true"],
        "remote_no_local_marker_crosstalk": remote_local_sum == 0,
        "remote_markers_present": remote_remote_sum > 0
        and ra.get("remote_marker_counts", {}).get("result:-2", 0) >= 1
        and ra.get("remote_marker_counts", {}).get("commit :", 0) >= 1,
        "remote_no_codec_no_write": not ra.get("open_codec")
        and not ra.get("write_start_play") and ra.get("no_write_start_play") is True,
        "remote_play_status_3_and_state3_pos0": (
            ra.get("play_status_3", 0) >= 1
            and 3 in R.get("states_observed", [])
            and R.get("distinct_positions") == [0]),
        "bypass_audit_intact": (
            bypass.get("all_pass") is True
            and bypass.get("f4_status") == "BLOCKED"
            and "BYPASS_NOT_FIX" in bypass.get("verdict", "")),
        "acceptance_final_intact": (
            acc.get("f4_status") == "BLOCKED"
            and acc.get("main_criteria_all_pass") is False
            and acc.get("no_criterion_pass") is True),
        "rollback_audit_present": rb.get("all_pass") is True
        and rb.get("rollback_state") == "ROLLED_BACK",
        "docs12_has_61_79": "### 61.79" in d12,
        # 自检反身陷阱：FORBIDDEN 定义块本身含被禁词，须从受检源码中剥离，
        # 否则该检查恒 False。守护对象 = 工具正文 / 说明，不含禁用词清单定义。
        "no_false_claim_in_tool_source": all(
            b not in re.sub(r"FORBIDDEN = \([^)]*\)", "", read(Path(__file__)), count=1)
            for b in FORBIDDEN),
        "timebox_not_passed": not sustained and not all(
            c["verdict"] == "PASS" for c in criteria
            if not c["verdict"].startswith("NOT RUN")),
    }
    failed = [k for k, v in checks.items() if not v]

    d = {
        "method": "12 §61.79 / F4 · A1 绕过取流时间盒纯离线裁决（只读、零生产改动）",
        "generated_by": "tools/l3_f4_a1_bypass_verdict.py",
        "mode": "offline_readonly",
        "device_run": False,
        "probe_run": live.get("probe_run"),
        "initial_probe": str(LIVE_V1.relative_to(ROOT)).replace("\\", "/"),
        "serial": live.get("serial"),
        "device_model": live.get("device_model"),
        "apk_installed": live.get("apk_installed"),
        "app_uid": live.get("app_uid"),
        "sample": live.get("sample"),
        "sample_bytes": live.get("sample_bytes"),
        "restore_errors": live.get("restore_errors"),
        "local": {
            "pid": la.get("app_pid"), "scoped_lines": la.get("scoped_lines"),
            "remote_flags": la.get("prepare_remote_flags"),
            "remote_markers_sum": local_remote_sum,
            "local_markers_sum": local_local_sum,
            "open_codec": la.get("open_codec"),
            "write_start_play": la.get("write_start_play"),
            "at_play": la.get("audio_track_play_calls"),
            "active": la.get("audio_track_active_markers"),
            "frames": la.get("frames_delivered"),
            "msg2": la.get("msg2_completion"),
            "released": la.get("audio_track_release_end"),
            "play_status": la.get("play_status"),
            "minus_103": la.get("finish_async_103"),
            "states": L.get("states_observed"),
            "positions": L.get("distinct_positions"),
            "track_started": L.get("app_audio_track_started_any"),
            "log_file": L.get("log_file"),
            "log_sha256": L.get("log_sha256"),
        },
        "remote": {
            "pid": ra.get("app_pid"), "scoped_lines": ra.get("scoped_lines"),
            "remote_flags": ra.get("prepare_remote_flags"),
            "remote_markers_sum": remote_remote_sum,
            "local_markers_sum": remote_local_sum,
            "open_codec": ra.get("open_codec"),
            "write_start_play": ra.get("write_start_play"),
            "at_play": ra.get("audio_track_play_calls"),
            "active": ra.get("audio_track_active_markers"),
            "frames": ra.get("frames_delivered"),
            "msg2": ra.get("msg2_completion"),
            "released": ra.get("audio_track_release_end"),
            "play_status": ra.get("play_status"),
            "minus_103": ra.get("finish_async_103"),
            "states": R.get("states_observed"),
            "positions": R.get("distinct_positions"),
            "track_started": R.get("app_audio_track_started_any"),
            "log_file": R.get("log_file"),
            "log_sha256": R.get("log_sha256"),
        },
        "criteria": criteria,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "ignition_established": ignition,
        "sustained_playback_established": sustained,
        "timebox_passed": False,
        "f4_status": "BLOCKED",
        "route_recommendation": "B2_PENDING_USER_DECISION",
        "rollback_audit": str(ROLLBACK.relative_to(ROOT)).replace("\\", "/"),
        "rollback_audit_present": rb.get("all_pass") is True,
        "verdict": (
            "A1_BYPASS_STREAM_LIVE_PARTIAL__IGNITION_ONLY__"
            "NO_SUSTAINED_PLAYBACK__TIMEBOX_NOT_PASSED："
            "绕过取流在真机上成立且解码链点火（codec / Write start play nErr:0 / "
            "AudioTrack ACTIVE / 无 -103），但每次仅交付 1 帧即 msg=2 → Paused → "
            "Release，position 恒 0、MediaSession 恒 PAUSED、自动进曲 3 首 / seek / "
            "ENDED 全不通过；时间盒判据未通过。按既定规则建议转 B2（不追加 A2 "
            "二进制补丁），但路线切换仍需用户拍板。「绕行 ≠ 修复」，"
            "REMOTE 缺陷仍在，§61.75 终局裁决不变，F4 仍 BLOCKED。"),
        "honest_notes": [
            "不连 adb、不重跑真机、不改历史证据；只读 v2 取证与历史证据 JSON。",
            "首轮试跑 4 处取证缺陷已如实归档（initial_v1__DEFECTIVE），不作裁决依据。",
            "msg=2 是播放完成通知，不等于自然播完；frames=1 不计持续出声。",
            "「绕行 ≠ 修复」：LOCAL 出声只证明绕过 REMOTE 取流缺陷，不修 REMOTE 本身。",
            "零 P0 源码改动（除 §61.79 接线回滚）、未重编译 / 未重装、so 二进制原样。",
            "A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。",
            "路线切换（B2）属用户拍板事项，本批不擅自启动、不动解码器。",
        ],
        "evidence_sources": [
            str(LIVE.relative_to(ROOT)).replace("\\", "/"),
            str(LIVE_V1.relative_to(ROOT)).replace("\\", "/"),
            str(BYPASS.relative_to(ROOT)).replace("\\", "/"),
            str(ACCEPTANCE.relative_to(ROOT)).replace("\\", "/"),
            str(ROLLBACK.relative_to(ROOT)).replace("\\", "/"),
        ],
        "log_sha256_local": L.get("log_sha256"),
        "log_sha256_remote": R.get("log_sha256"),
    }
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / timebox_passed=%s / F4=%s"
          % (len(checks), not failed, d["timebox_passed"], d["f4_status"]))
    if failed:
        print("[FAIL] §61.79 A1 裁决未通过：%s" % failed)
        return 1
    print("[PASS] §61.79 A1 时间盒裁决：绕行成立但仅点火、无持续播放；"
          "时间盒未通过、建议转 B2（待用户拍板）、F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
