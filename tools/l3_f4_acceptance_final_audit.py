#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.75 / F4 · 真机验收终局裁决（纯离线、只读、零生产改动）。

用户明确「真机部分验证完」：§61.73 三轮纯观测 + §61.75 播放控制轮
（cmd media_session dispatch play/next×3/fast-forward/rewind）已把设备在场
能取的数据全部取完。本工具只读这些已落盘证据，按 `17 §8.3` 的 F4 验收
判据逐条定案，并按 `§61.74` 修正后的多信号口径纠偏：
**MediaSession state=3 不等于播放成功**（state=3 但 position 恒 0 /
无本 app AudioTrack / Write start play 0 / -103 在场 ⇒ 实际未输出音频）。

不改历史证据、不重跑真机、不连 adb。产出
analysis/l3-f4-acceptance-final.json 与
analysis/l3-f4-acceptance-final-report.md。

诚实边界：判据未满足写 FAIL / NOT RUN；真机取证已完结，后续是修复与回归，
**不再把「设备上线」留作下一步**。F4 仍 BLOCKED。
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
OUT = A / "l3-f4-acceptance-final.json"
REPORT = A / "l3-f4-acceptance-final-report.md"

ROUNDS = A / "l3-f4-live-rounds-audit.json"
CONTROL = A / "l3-f4-playback-control.json"
GATE = A / "l3-f4-play-gate-disasm.json"
PLAYBACK_STATE = A / "l3-f4-live-playback-state-20261111.txt"
UI = A / "l3-f4-live-ui-20261111.png"
F3_MATRIX = A / "l3-f3-matrix-report.md"

FORBIDDEN = ("F4 已" + "解决", "m4a 已" + "支持", "全格式已" + "支持",
             "level-5 已" + "开启", "播放已" + "成功", "推断已" + "升级为实证")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def build_report(d: dict) -> str:
    lines = [
        "# F4 · 真机验收终局裁决（§61.75，2026-11-11）",
        "",
        "> **性质**：纯离线只读汇总，**真机取证已完结**。不连 adb、不重跑实验、",
        "> 不改历史证据、零 P0 源码改动。判据未满足一律 FAIL / NOT RUN。",
        "> **F4 仍 BLOCKED**。",
        "",
        "## 1. 现场（设备在场能取的，已全部取完）",
        "",
        "- `§61.73`：端点 `10.0.0.2:37509` → app 未装 → 装不含 §61.69 接线的",
        "  基线 APK，**三轮 60s 纯观测**（冷缓存 / 暖缓存 / 用户拖动），",
        "  共 10573 行 logcat + 3 份 probe JSON + 3 份 live verdict + UI 截图。",
        "- `§61.75`：端点 `10.0.0.2:37163`，`cmd media_session dispatch`",
        "  绕过 MIUI 的 INJECT_EVENTS 限制，**主动执行** `play` / `next`×3 /",
        "  `fast-forward` / `rewind`（6 次全部 rc=0），3895 行 logcat +",
        "  4 次 media_session/audio 快照。**无需用户物理在场**。",
        "- 下线前 `dumpsys` 补证 + 播放页 `screencap`。",
        "",
        "## 2. F4 验收判据逐条定案（`17 §8.3`）",
        "",
        "| # | 判据 | 实测 | 结论 |",
        "|---|---|---|---|",
    ]
    for i, c in enumerate(d["criteria"], 1):
        lines.append("| %d | %s | %s | **%s** |" % (
            i, c["name"], c["evidence"], c["verdict"]))
    lines.extend([
        "",
        "## 3. 关键纠偏：`state=3` 不是播放成功",
        "",
        "- 控制轮 `snapshot_A/B` 的 MediaSession 上报 **state=3 (PLAYING)**，",
        "  但 `position=0` 恒不变、`metadata: null`、**无本 app AudioTrack",
        "  `state:started`**、`Write start play` 0 次、`-103` 1 次。",
        "- 按 `§61.74` 的多信号口径：状态机上报 PLAYING **不构成播放成功**；",
        "  音频输出须由 AudioTrack / 写日志 / position 推进共同佐证。",
        "- `dispatch next`×3 后 state 回落到 2(PAUSED)，position 仍 0，",
        "  codec 仅 1 次 → 切歌动作被接收但没有新曲目真正开始解码。",
        "- `§61.74` 反汇编已证明 `Write start play` 打印点前有 4 道静默早退",
        "  门控，故其 0 次**不是「没写 AudioTrack」的充分证据**；但无",
        "  AudioTrack started + position 恒 0 是独立证据，结论不依赖它。",
        "",
        "## 4. 终局结论",
        "",
        "- **%s**" % d["verdict"],
        "- **真机取证到此完结**：设备在场能执行的观测、注入、播放控制、",
        "  UI 截图、dumpsys 全部完成；**不需要再为「验证」上线**。",
        "  后续只有 F4 修复后的**回归验证**才需要设备。",
        "- F3 14 格未改写；`-103` / 自动进曲 3 首 / seek / ENDED 判据未通过；",
        "  A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 与指纹不回填。",
        "- 零 P0 源码改动、未重编译 / 未重装含接线 APK、so 二进制原样、",
        "  未改用户网络 / 未 `pm grant` / 配对码不落盘；",
        "  §61.67~§61.74 历史正文与证据 JSON 一字未改。",
        "- 接线回滚仍待用户拍板（不属真机验证，属源码决策）。",
        "",
        "## 5. 实跑核验",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(d["failed_checks"]) or "无"),
        "- 证据来源：`%s`、`%s`、`%s` 均只读、零改写。" % (
            ROUNDS.name, CONTROL.name, GATE.name),
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    rounds = jload(ROUNDS)
    ctrl = jload(CONTROL)
    gate = jload(GATE)
    pb = read(PLAYBACK_STATE)
    la = ctrl.get("log_analysis", {})
    cr = ctrl.get("criteria", {})
    snaps = ctrl.get("snapshots", {})
    states = [s.get("playback_state") for s in snaps.values()
              if s.get("playback_state") is not None]
    positions = [s.get("position") for s in snaps.values()
                 if s.get("position") is not None]
    any_started = any(s.get("app_audio_track_started") for s in snaps.values())
    dispatch_rc = [x.get("rc") for x in ctrl.get("dispatches", [])]
    round_writes = [r.get("write_start_play", 0) for r in rounds.get("rounds", [])]
    round_103 = [r.get("has_103") for r in rounds.get("rounds", [])]

    criteria = [
        {"name": "双路径格式矩阵（`17 §8.3` F3 前置）",
         "evidence": "F3 14 格 = 11 PASS / 3 FAIL / 7 种 NO_SAMPLE，未改写",
         "verdict": "PARTIAL（非本轮真机范围，需样本）"},
        {"name": "`Open codec` → `Write start play`",
         "evidence": "三轮 %d/%d/%d 次 + 控制轮 %d 次；codec 仅冷/暖轮 aac" % (
             round_writes[0] if round_writes else -1,
             round_writes[1] if len(round_writes) > 1 else -1,
             round_writes[2] if len(round_writes) > 2 else -1,
             len(la.get("write_start_play", []))),
         "verdict": "FAIL（全程 0 次；按 §61.74 为非充分证据，需并用其它信号）"},
        {"name": "完整时长 / UI 进度条",
         "evidence": "UI 截图两端 00:00；metadata:null；mDuration 2933 未映射到 UI",
         "verdict": "FAIL（总时长不可用）"},
        {"name": "MediaSession 进入 PLAYING",
         "evidence": "控制轮快照 state=%s（曾出现 3），position=%s" % (
             states, positions),
         "verdict": "PARTIAL（state=3 仅状态机上报，无音频输出佐证）"},
        {"name": "本 app AudioTrack `state:started`",
         "evidence": "快照 app_audio_track_started=%s；dumpsys 无本 app 媒体轨" % any_started,
         "verdict": "FAIL（未出现）"},
        {"name": "自动进曲 3 首（无 -103、无释放后回调）",
         "evidence": "dispatch next×3 rc=%s；新 codec=%s；-103 三轮 %s + 控制轮 %d" % (
             dispatch_rc[1:4] if len(dispatch_rc) >= 4 else dispatch_rc,
             la.get("open_codec"), round_103, la.get("finish_async_103", 0)),
         "verdict": "FAIL（无 3 次新解码，-103 仍在场）"},
        {"name": "seek / pause→play 位置连续",
         "evidence": "fast-forward+rewind rc=0，但 position 序列=%s" % positions,
         "verdict": "FAIL（position 恒 0）"},
        {"name": "ENDED（自然播完）",
         "evidence": "受控窗口内未跑到曲末，无 ENDED 日志",
         "verdict": "NOT RUN（不伪造）"},
        {"name": "`-103` 判据",
         "evidence": "三轮 %s + 控制轮 %d 次" % (
             round_103, la.get("finish_async_103", 0)),
         "verdict": "FAIL（-103 仍在场）"},
    ]

    checks = {
        "three_rounds_evidence_present": len(rounds.get("rounds", [])) == 3
        and rounds.get("all_pass") is True,
        "control_round_evidence_present": (
            ctrl.get("log_analysis", {}).get("log_lines", 0) > 1000
            and len(ctrl.get("dispatches", [])) == 6),
        "all_dispatch_actions_succeeded": all(r == 0 for r in dispatch_rc)
        and len(dispatch_rc) == 6,
        "state3_observed_but_no_audio_output": (
            3 in states and not any_started
            and all(p == 0 for p in positions)),
        "no_write_start_play_in_all_rounds": (
            all(w == 0 for w in round_writes)
            and len(la.get("write_start_play", [])) == 0),
        "minus_103_still_present": (
            any(round_103) and la.get("finish_async_103", 0) >= 1),
        "no_new_codec_after_next_x3": len(la.get("open_codec", [])) < 3,
        "seek_position_unchanged": len(set(positions)) <= 1,
        "gate_disasm_evidence_present": (
            gate.get("all_pass") is True and len(gate.get("gates", {})) == 4),
        "ui_and_dumpsys_evidence_present": (
            UI.is_file() and UI.stat().st_size > 10000
            and "state=PlaybackState {state=2" in pb),
        "f4_still_blocked": ctrl.get("f4_status") == "BLOCKED"
        and gate.get("f4_status") == "BLOCKED",
        "no_false_claim_in_source": all(
            s not in read(Path(__file__)) for s in FORBIDDEN),
    }
    failed = [k for k, v in checks.items() if not v]
    no_pass = not any(c["verdict"].startswith("PASS") for c in criteria)

    d = {
        "method": "12 §61.75 / F4 真机验收终局裁决（纯离线、只读、零生产改动）",
        "generated_by": "tools/l3_f4_acceptance_final_audit.py",
        "mode": "offline_readonly",
        "device_run": False,
        "criteria": criteria,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "main_criteria_all_pass": False,
        "no_criterion_pass": no_pass,
        "f4_status": "BLOCKED",
        "truthfulness": (
            "真机取证已完结：三轮纯观测 + 播放控制轮 + UI/dumpsys 全部落盘；"
            "MediaSession state=3 仅状态机上报，无 AudioTrack / position / 写日志佐证，"
            "不构成播放成功。F4 主判据全部 FAIL / PARTIAL / NOT RUN，F4 仍 BLOCKED。"),
        "verdict": (
            "F4 验收未通过：所有主判据逐条登记为 FAIL / PARTIAL / NOT RUN，"
            "无一项 PASS；真机取证到此完结，后续是 F4 修复与回归验证，"
            "不再以「设备上线」作为验证前置。"),
        "next_phase": (
            "离线修复方向：代次错配 → commit 冻结 → 读侧 -103 → 播放链未起；"
            "接线回滚待用户拍板（源码决策，非真机验证）。"
            "F4 修复后仅需一次回归验证。"),
        "evidence_sources": [
            str(ROUNDS.relative_to(ROOT)).replace("\\", "/"),
            str(CONTROL.relative_to(ROOT)).replace("\\", "/"),
            str(GATE.relative_to(ROOT)).replace("\\", "/"),
            str(PLAYBACK_STATE.relative_to(ROOT)).replace("\\", "/"),
            str(UI.relative_to(ROOT)).replace("\\", "/"),
        ],
        "honest_notes": [
            "不连 adb、不重跑真机、不改写任何历史证据；只读汇总。",
            "零 P0 源码改动、未重编译 / 未重装、so 二进制原样。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 与指纹不回填。",
            "F3 14 格未改写；7 种 NO_SAMPLE 需用户提供样本或换源。",
        ],
    }
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / F4=%s"
          % (len(checks), not failed, d["f4_status"]))
    if failed:
        print("[FAIL] §61.75 终局裁决未通过：%s" % failed)
        return 1
    print("[PASS] §61.75 真机验收终局裁决：主判据无一 PASS、"
          "state=3 非播放成功；真机取证完结，F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
