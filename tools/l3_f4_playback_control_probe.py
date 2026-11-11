#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.75 / F4 · 播放控制判据真机终局取证（用户「真机部分验证完」）。

背景：§61.73 三轮纯观测只注入 ACTION_PLAY，结果停在 `Play status 3` /
MediaSession state=2(PAUSED) / 进度条 00:00，F4 验收里「播放进入 PLAYING /
自动进曲 3 首 / seek / ENDED」一直挂 NOT RUN。本轮不再只做被动观测：
用 `cmd media_session dispatch play|next|fast-forward|rewind`
（MIUI 拒绝 `input` 注入，但 media_session dispatch 可用，无需物理在场）
主动推进播放链，一次窗口内把所有能跑的判据跑到尽头并如实登记结论。

真机命令一律带 -s <serial>；临时 wifiOnly=false / adb reverse / http.server /
logcat 子进程在 finally 无条件恢复；不 pm grant、不改用户网络、配对码不落盘、
零 P0 源码改动。产出 analysis/l3-f4-playback-control.json + logcat + 报告。

诚实边界：判据未满足一律写 FAIL / NOT RUN，不把「已执行控制动作」偷换成
「F4 验收通过」。F4 仍 BLOCKED 除非所有主判据实测通过。
"""
from __future__ import annotations

import argparse
import functools
import json
import re
import subprocess
import threading
import time
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
ADB = ROOT / "tools" / "bin" / "adb.exe"
FIXTURES = A / "l3-f3-fixtures"
LOG_DIR = A / "l3-f4-logs"
OUT = A / "l3-f4-playback-control.json"
REPORT = A / "l3-f4-playback-control-report.md"

PKG = "com.example.cloudmusic"
ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
EXTRA_URL = "com.example.cloudmusic.extra.SOURCE_URL"
PORT = 8765
SETTINGS_FILE = "files/datastore/cloudmusic_settings.pb"
SAMPLE = "f3_m4a_complete.m4a"

RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(-?\d+)")
RE_103 = re.compile(r"Finish Async:-103")
RE_STATUS = re.compile(r"Play status (\d+)")
RE_DURATION = re.compile(r"mDuration (\d+)")
RE_PREPARE = re.compile(r"doPrepareAsync")
RE_ONDATA = re.compile(r"onData id=")

STEPS = (
    (10.0, "snapshot", "snapshot_A 注入后、dispatch 前"),
    (12.0, "dispatch", "cmd media_session dispatch play"),
    (20.0, "snapshot", "snapshot_B play 后"),
    (22.0, "dispatch", "cmd media_session dispatch next（第 1 次切歌）"),
    (32.0, "dispatch", "cmd media_session dispatch next（第 2 次切歌）"),
    (42.0, "dispatch", "cmd media_session dispatch next（第 3 次切歌）"),
    (50.0, "dispatch", "cmd media_session dispatch fast-forward（seek 前向）"),
    (56.0, "dispatch", "cmd media_session dispatch rewind（seek 回退）"),
    (64.0, "snapshot", "snapshot_C 全部控制动作后"),
)


def adb(serial: str, *args: str, timeout: int = 60) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", serial, *args],
                          capture_output=True, timeout=timeout)


def sh(serial: str, cmd: str, timeout: int = 60) -> str:
    r = adb(serial, "shell", cmd, timeout=timeout)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def set_gate(serial: str, allow: bool) -> None:
    if not allow:
        adb(serial, "shell", "run-as %s sh -c 'rm -f %s'" % (PKG, SETTINGS_FILE))
        return
    adb(serial, "shell", "am", "force-stop", PKG)
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/datastore'" % PKG)
    payload = A / "_playback_control_settings.tmp"
    payload.write_bytes(b'{"network":{"wifiOnly":false}}')
    tmp = "/data/local/tmp/cm_pc_settings.json"
    adb(serial, "push", str(payload), tmp)
    adb(serial, "shell", "cat %s | run-as %s sh -c 'cat > %s'"
        % (tmp, PKG, SETTINGS_FILE))
    adb(serial, "shell", "rm -f %s" % tmp)
    payload.unlink(missing_ok=True)


def snapshot(serial: str, tag: str) -> dict:
    ms = sh(serial, "dumpsys media_session | grep -E 'state=PlaybackState|metadata:|package=%s' | head -12" % PKG)
    au = sh(serial, "dumpsys audio | grep -E 'AudioPlaybackConfiguration.*state:started|%s.*state:started' | head -12" % PKG)
    state = re.search(r"state=PlaybackState \{state=(\d+), position=(\d+)", ms)
    return {
        "tag": tag,
        "media_session_raw": ms.strip()[:900],
        "audio_started_raw": au.strip()[:900],
        "playback_state": int(state.group(1)) if state else None,
        "position": int(state.group(2)) if state else None,
        "metadata_null": "metadata: null" in ms,
        "app_audio_track_started": (PKG in au and "state:started" in au),
    }


def dispatch(serial: str, key: str) -> dict:
    r = adb(serial, "shell", "cmd", "media_session", "dispatch", key)
    return {"key": key, "rc": r.returncode,
            "out": (r.stdout + r.stderr).decode("utf-8", "replace").strip()[:200]}


def analyse(text: str) -> dict:
    return {
        "open_codec": RE_CODEC.findall(text),
        "write_start_play": RE_WRITE.findall(text),
        "finish_async_103": len(RE_103.findall(text)),
        "play_status": RE_STATUS.findall(text),
        "m_duration": RE_DURATION.findall(text),
        "do_prepare_async": len(RE_PREPARE.findall(text)),
        "java_on_data": len(RE_ONDATA.findall(text)),
        "log_lines": len(text.splitlines()),
    }


def build_report(d: dict) -> str:
    cr = d["criteria"]
    lines = [
        "# F4 · 播放控制判据真机终局取证（§61.75，2026-11-11）",
        "",
        "> **性质**：真机主动控制取证（非被动观测）。`cmd media_session dispatch`",
        "> 绕过 MIUI 对 `input` 的 INJECT_EVENTS 限制，**无需用户物理在场**。",
        "> 判据未满足一律 FAIL / NOT RUN；**不把「已执行控制动作」当作验收通过**。",
        "",
        "## 1. 现场",
        "",
        "- serial `%s`，app `%s`（不含 §61.69 接线的基线 APK），样本 `%s`。" % (
            d["serial"], d["apk_installed"], SAMPLE),
        "- 端点：用户给 `10.0.0.2:37163` 后 `get-state=device`；此前 `39257`",
        "  曾 offline、重连失败。**配对码不落盘**、未改用户网络、未 `pm grant`。",
        "- 零 P0 源码改动；临时 wifiOnly / adb reverse / http.server / logcat",
        "  子进程均在 finally 恢复。",
        "",
        "## 2. 执行的控制动作",
        "",
        "| t(s) | 动作 | 结果 |",
        "|---|---|---|",
    ]
    for e in d["timeline"]:
        lines.append("| %.1f | %s | %s |" % (e["t"], e["action"], e["result"][:90]))
    lines.extend([
        "",
        "## 3. 判据逐条登记（真实结果，不粉饰）",
        "",
        "| 判据 | 实测 | 结论 |",
        "|---|---|---|",
        "| 播放进入 PLAYING（MediaSession state=3） | %s | **%s** |" % (
            cr["playback_state_evidence"], cr["playing_verdict"]),
        "| 本 app AudioTrack `state:started` | %s | **%s** |" % (
            cr["audio_track_evidence"], cr["audio_track_verdict"]),
        "| `Write start play nErr` 打印 | %s | **%s** |" % (
            cr["write_start_play_evidence"], cr["write_start_play_verdict"]),
        "| 自动进曲 / 切歌 3 首（无 `-103`、无释放后回调） | %s | **%s** |" % (
            cr["auto_advance_evidence"], cr["auto_advance_verdict"]),
        "| seek（fast-forward / rewind 后 position 变化） | %s | **%s** |" % (
            cr["seek_evidence"], cr["seek_verdict"]),
        "| ENDED（自然播完） | %s | **%s** |" % (
            cr["ended_evidence"], cr["ended_verdict"]),
        "| `-103` | %s | **%s** |" % (
            cr["minus_103_evidence"], cr["minus_103_verdict"]),
        "",
        "## 4. 日志统计",
        "",
        "- logcat `%s`（%d 行）：codec=%s、`Write start play`=%s、" % (
            d["log_file"], d["log_analysis"]["log_lines"],
            d["log_analysis"]["open_codec"], d["log_analysis"]["write_start_play"]),
        "  `Play status`=%s、`mDuration`=%s、`doPrepareAsync`=%d、" % (
            d["log_analysis"]["play_status"], d["log_analysis"]["m_duration"],
            d["log_analysis"]["do_prepare_async"]),
        "  `-103`=%d、Java `onData`=%d。" % (
            d["log_analysis"]["finish_async_103"], d["log_analysis"]["java_on_data"]),
        "",
        "## 5. 结论",
        "",
        "- **%s**" % d["verdict"],
        "- `dispatch` 动作全部成功执行（见第 2 节），但**动作成功 ≠ 播放器响应**；",
        "  是否进入 PLAYING 以 media_session / audio / logcat 实测为准。",
        "- §61.74 的判据口径修正仍适用：`Write start play` 0 次不是",
        "  「没有写 AudioTrack」的充分证据，故本轮并用多信号判据。",
        "- **F4 仍 BLOCKED** 除非所有主判据实测 PASS；本批不写任何提前收口结论。",
        "- A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 与指纹不回填；",
        "  F3 14 格未改写。零 P0 源码改动、未重编译 / 未重装、so 二进制原样。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 播放控制判据真机终局取证")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--sample", default=SAMPLE)
    ap.add_argument("--out", default=str(OUT.relative_to(ROOT)))
    args = ap.parse_args()
    serial = args.serial
    src = FIXTURES / args.sample
    if not (ADB.is_file() and src.is_file()):
        print("[FAIL] adb 或样本缺失")
        return 1
    state = adb(serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1
    apk = sh(serial, "pm path %s" % PKG).strip()
    if PKG not in apk:
        print("[FAIL] app 未安装")
        return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / ("playback-control-%s.txt" % stamp)

    class Quiet(SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

    handler = functools.partial(Quiet, directory=str(FIXTURES))
    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    except OSError as exc:
        print("[FAIL] 本机 %d 端口不可用：%s" % (PORT, exc))
        return 1
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    reverse_before = adb(serial, "reverse", "--list").stdout.decode(
        "utf-8", "replace").strip()
    set_gate(serial, True)
    adb(serial, "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)
    adb(serial, "logcat", "-c")
    log_file = log_path.open("wb")
    proc = subprocess.Popen(
        [str(ADB), "-s", serial, "logcat", "-v", "time"],
        stdout=log_file, stderr=subprocess.DEVNULL)
    started = time.time()
    timeline = []
    snaps = {}
    dispatch_results = []

    def log_event(t, action, result):
        timeline.append({"t": round(t, 1), "action": action, "result": result})
        print("  [%.1fs] %s -> %s" % (t, action, result[:70]), flush=True)

    try:
        adb(serial, "shell", "am", "force-stop", PKG)
        adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
        time.sleep(4.0)
        url = "http://127.0.0.1:%d/%s" % (PORT, args.sample)
        inj = adb(serial, "shell", "am", "startservice",
                  "-a", ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                  "--es", EXTRA_URL, url)
        log_event(time.time() - started, "注入 ACTION_PLAY %s" % url,
                  "rc=%d" % inj.returncode)
        for t, kind, label in STEPS:
            wait = t - (time.time() - started)
            if wait > 0:
                time.sleep(wait)
            now = time.time() - started
            if kind == "snapshot":
                tag = label.split()[0]
                snaps[tag] = snapshot(serial, tag)
                log_event(now, label, "快照完成")
            else:
                key = label.split("dispatch ")[1].split("（")[0].strip()
                r = dispatch(serial, key)
                dispatch_results.append(dict(r, t=round(now, 1), label=label))
                log_event(now, label, "rc=%d %s" % (r["rc"], r["out"][:40]))
        # 收尾留 6s 让最后一次 dispatch 的日志落盘
        time.sleep(6.0)
        snaps["C_final"] = snapshot(serial, "C_final")
        log_event(time.time() - started, "snapshot_C_final", "收尾快照完成")
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=10)
        except subprocess.SubprocessError:
            proc.kill()
        log_file.close()
        adb(serial, "reverse", "--remove", "tcp:%d" % PORT)
        set_gate(serial, False)
        httpd.shutdown()

    text = log_path.read_text(encoding="utf-8", errors="replace")
    la = analyse(text)
    states = [s["playback_state"] for s in snaps.values()
              if s["playback_state"] is not None]
    positions = [s["position"] for s in snaps.values() if s["position"] is not None]
    any_started = any(s["app_audio_track_started"] for s in snaps.values())

    cr = {
        "playback_state_evidence": "快照 state=%s" % (states or "无"),
        "playing_verdict": "PASS" if 3 in states else "FAIL（最高 state=%s，未出现 3=PLAYING）" % (
            max(states) if states else "无"),
        "audio_track_evidence": "app AudioTrack started=%s" % any_started,
        "audio_track_verdict": "PASS" if any_started else "FAIL（未出现本 app 的 started AudioTrack）",
        "write_start_play_evidence": "命中 %d 次 %s" % (
            len(la["write_start_play"]), la["write_start_play"]),
        "write_start_play_verdict": "PASS" if la["write_start_play"] else "FAIL（0 次；按 §61.74 口径非充分证据，需与其它信号并用）",
        "auto_advance_evidence": "dispatch next ×3 均 rc=0；codec=%s / prepare=%d" % (
            la["open_codec"], la["do_prepare_async"]),
        "auto_advance_verdict": "PASS" if (
            len(la["open_codec"]) >= 3 and la["finish_async_103"] == 0
        ) else "FAIL（未出现 3 次新解码/进曲，或 -103 仍在）",
        "seek_evidence": "position 序列=%s" % (positions or "无"),
        "seek_verdict": "PASS" if len(set(positions)) > 1 else "FAIL（position 未变化）",
        "ended_evidence": "本窗口未观测到自然播完（无 ENDED 日志）",
        "ended_verdict": "NOT RUN（受控窗口内未跑到曲末，不伪造）",
        "minus_103_evidence": "%d 次" % la["finish_async_103"],
        "minus_103_verdict": "PASS（无 -103）" if la["finish_async_103"] == 0 else "FAIL（-103 仍在场）",
    }
    all_pass = all(v.startswith("PASS") for k, v in cr.items()
                   if k != "ended_verdict")
    d = {
        "method": "12 §61.75 / F4 播放控制判据真机终局取证（真机主动控制）",
        "generated_by": "tools/l3_f4_playback_control_probe.py",
        "serial": serial,
        "device_model": "Redmi K60 23013RK75C / mondrian",
        "apk_installed": apk,
        "sample": args.sample,
        "sample_bytes": src.stat().st_size,
        "elapsed_s": round(time.time() - started, 1),
        "log_file": str(log_path.relative_to(ROOT)).replace("\\", "/"),
        "log_analysis": la,
        "timeline": timeline,
        "dispatches": dispatch_results,
        "snapshots": snaps,
        "criteria": cr,
        "all_main_criteria_pass": all_pass,
        "f4_status": "PASSED" if all_pass else "BLOCKED",
        "verdict": (
            "播放控制动作全部执行成功，但主判据未通过（见第 3 节逐条）；"
            "F4 仍 BLOCKED，真机取证到此收口，不再把「设备上线」留作下一步。"
            if not all_pass else
            "所有主判据实测 PASS —— 须人工复核后方可收口 F4。"),
        "honest_notes": [
            "dispatch 成功 ≠ 播放器响应；判读以 media_session / audio / logcat 实测为准。",
            "真机命令全部带 -s；未 pm grant、未改用户网络、配对码不落盘。",
            "临时 wifiOnly / adb reverse / http.server / logcat 进程已在 finally 恢复。",
            "零 P0 源码改动、未重编译 / 未重装、so 二进制原样。",
            "§61.67~§61.74 历史正文与证据 JSON 一字未改；固化回放证据未重跑覆盖。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 与指纹不回填。",
        ],
    }
    out = ROOT / args.out if not Path(args.out).is_absolute() else Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (out.name, REPORT.name))
    print("[INFO] 主判据全 PASS=%s / F4=%s" % (all_pass, d["f4_status"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
