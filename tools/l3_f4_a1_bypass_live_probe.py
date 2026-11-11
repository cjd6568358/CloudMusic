#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.79 / F4 · A1「绕过取流」时间盒 **真机探针**（用户 2026-11-11 拍板授权）。

承接 `12 §61.78` 的 A1 时间盒实验设计（此前 `NOT_RUN__REQUIRES_USER_ROUTE_DECISION`）。
用户授权「跑 A1 时间盒，接线回滚一起做」后，本工具只负责 **真机取证**：
把同一样本 `f3_m4a_complete.m4a` 完整预落为 App 私有本地文件，令
`EngineSource(url=null, localPath=...)` → `SourceConfig.Type.LOCAL` →
`XorLocalSource::Open(fd)`，不进入 `NMCommonCache` REMOTE / vfile / commit 链，
然后用 `§61.74/§61.75` 的多信号口径观测播放链是否真正起得来。

两轮取证的定位：
  * **LOCAL 主轮**（~67s）：注入 `ACTION_PLAY + EXTRA_LOCAL_PATH`，流式 logcat +
    `dumpsys media_session/audio` 快照 + `cmd media_session dispatch`
    play / fast-forward / rewind / next×3。补 F3 14 格从未跑过的
    AudioTrack started / MediaSession position 递增 / 自动进曲 / seek / ENDED。
  * **REMOTE 同窗对照轮**（~32s）：同一样本、同一设备、同一次运行，走
    `ACTION_PLAY + EXTRA_SOURCE_URL`（`http://127.0.0.1:8765/` + adb reverse +
    PC http.server），已知 FAIL，用于同窗对照而非重新取证。

**边界（必须遵守）**：
  * 本工具 **只取证、不下最终裁决**；裁决交给纯离线工具
    `tools/l3_f4_a1_bypass_verdict.py`（`12 §61.79`）。
  * 「绕行 ≠ 修复」：即使 LOCAL 主判据全 PASS，也只证明绕过 REMOTE 取流缺陷
    可出声，**不修 REMOTE 本身**；`§61.75` 终局裁决不变，F4 仍 BLOCKED。
  * 不 `pm grant`、不改设备系统设置、配对码不落盘；临时
    `wifiOnly=false`（仅 REMOTE 轮）/ adb reverse / http.server / logcat 子进程 /
    注入的私有样本与 `/data/local/tmp/` 残留均在 `finally` 无条件恢复。
  * 零 P0 源码改动、未重编译 / 未重装、so 二进制原样、不动解码器。
  * 真机命令全部带 `-s <serial>`；纯 stdlib、零第三方依赖、零个人路径。

产出：
  analysis/l3-f4-a1-bypass-live.json              结构化取证（含两轮信号）
  analysis/l3-f4-logs/a1-bypass-local-<stamp>.txt  LOCAL 全量 logcat
  analysis/l3-f4-logs/a1-bypass-remote-<stamp>.txt REMOTE 全量 logcat
"""
from __future__ import annotations

import argparse
import functools
import hashlib
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
OUT = A / "l3-f4-a1-bypass-live.json"

PKG = "com.example.cloudmusic"
ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
EXTRA_LOCAL = "com.example.cloudmusic.extra.LOCAL_PATH"
EXTRA_URL = "com.example.cloudmusic.extra.SOURCE_URL"
PORT = 8765
PRIVATE_DIR = "/data/data/%s/files/music" % PKG
SETTINGS_FILE = "files/datastore/cloudmusic_settings.pb"
SAMPLE = "f3_m4a_complete.m4a"

# LOCAL 主轮时间轴（秒，相对注入时刻）。覆盖 §61.74/§61.75 多信号判据：
# play 进入 PLAYING、seek 前后 position、自动进曲 3 首、收尾快照。
LOCAL_STEPS = (
    (2.0, "snapshot", "snapshot_A0 注入后 2s（早窗口，尽量贴近写日志时刻）"),
    (6.0, "snapshot", "snapshot_A 注入后 6s、dispatch 前"),
    (9.0, "dispatch", "cmd media_session dispatch play"),
    (19.0, "snapshot", "snapshot_B play 后"),
    (23.0, "dispatch", "cmd media_session dispatch fast-forward（seek 前向）"),
    (29.0, "snapshot", "snapshot_C seek 前向"),
    (33.0, "dispatch", "cmd media_session dispatch rewind（seek 回退）"),
    (39.0, "snapshot", "snapshot_D seek 回退"),
    (43.0, "dispatch", "cmd media_session dispatch next（第 1 次切歌）"),
    (49.0, "dispatch", "cmd media_session dispatch next（第 2 次切歌）"),
    (55.0, "dispatch", "cmd media_session dispatch next（第 3 次切歌）"),
    (61.0, "snapshot", "snapshot_E 全部控制动作后"),
)
LOCAL_TAIL_S = 6.0

# REMOTE 同窗对照轮：已知 FAIL（`§61.61` / `§61.75`），只做同设备同窗对照。
REMOTE_STEPS = (
    (8.0, "snapshot", "snapshot_R1 注入后"),
    (10.0, "dispatch", "cmd media_session dispatch play"),
    (20.0, "snapshot", "snapshot_R2 play 后"),
    (28.0, "snapshot", "snapshot_R3 收尾"),
)
REMOTE_TAIL_S = 4.0

RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(-?\d+)")
RE_103 = re.compile(r"Finish Async:-103")
RE_STATUS = re.compile(r"Play status (\d+)")
RE_DURATION = re.compile(r"mDuration (\d+)")
RE_PREPARE = re.compile(r"doPrepareAsync")
RE_WIRING = re.compile(r"SetLoggerCallback level=(\d+)")
RE_AT_PLAY = re.compile(r"AudioTrack\(\d+\): Play\((-?\d+)\)")
RE_AT_START = re.compile(r"start\(\d+\): prior state:STATE_STOPPED")
RE_FRAMES = re.compile(r"stop\(\d+\): called with (\d+) frames delivered")
RE_AT_PAUSED = re.compile(r"AudioTrack\(\d+\): Paused")
RE_MSG2 = re.compile(r"PlayerNotifyEvent msg=2\b")
RE_PREPARE_REMOTE = re.compile(r"prepare trackId=\S+ remote=(\w+)")
RE_NATURAL_END = re.compile(
    r"onCompletion|PlaybackState\.STATE_STOPPED|doPlayCompleted")

# REMOTE 取流链特征（`§61.78` 审计口径）：LOCAL 主轮应当全部为 0。
REMOTE_MARKERS = (
    "Start id=", "WaitRequest", "MakeRangeRequestAsync",
    "ReadFromFileNoLock", "NMSliceFile", "commit :", "OnWriteNetData",
    "result:-2",
)
# LOCAL 装配链特征：LOCAL 主轮应当全部命中。
LOCAL_MARKERS = (
    "getLocalPath path=", "CreateLocalSource", "XorLocalSource::Open success",
    "createDataRequestHandler url=null",
)


def adb(serial: str, *args: str, stdin: bytes | None = None,
        timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", serial, *args],
                          input=stdin, capture_output=True, timeout=timeout)


def sh(serial: str, cmd: str, timeout: int = 90) -> str:
    r = adb(serial, "shell", cmd, timeout=timeout)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def push_local(serial: str, src: Path, name: str) -> dict:
    """`12 §61.47` 零权限通道：push 到 /data/local/tmp → shell cat | run-as cat。
    adb shell 的 stdin 直传会截断（实测只写 248 B），故必须用该通道。"""
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/music'" % PKG)
    tmp = "/data/local/tmp/%s" % name
    r = adb(serial, "push", str(src), tmp, timeout=300)
    adb(serial, "shell",
        "cat %s | run-as %s sh -c 'cat > files/music/%s'" % (tmp, PKG, name),
        timeout=300)
    check = adb(serial, "shell", "run-as %s sh -c 'ls -l files/music/%s'" % (PKG, name))
    adb(serial, "shell", "rm -f %s" % tmp)
    size_ok = str(src.stat().st_size).encode() in check.stdout
    return {
        "channel": "12 §61.47 / adb push + run-as cat（零权限）",
        "pushed_bytes": src.stat().st_size,
        "private_path": PRIVATE_DIR + "/" + name,
        "push_rc": r.returncode,
        "size_match": size_ok,
        "ls_raw": check.stdout.decode("utf-8", "replace").strip()[:200],
    }


def cleanup_private_sample(serial: str, name: str) -> None:
    """恢复现场：删除注入的私有样本；目录空则一并移除。"""
    adb(serial, "shell", "run-as %s sh -c 'rm -f files/music/%s; "
        "rmdir files/music 2>/dev/null; true'" % (PKG, name))
    adb(serial, "shell", "rm -f /data/local/tmp/%s" % name)


def set_gate(serial: str, allow: bool) -> None:
    """仅 REMOTE 轮：设备默认网络 MOBILE[NR]，`AppSettings.network.wifiOnly=true`
    会由 `PlayerController.blockedByNetworkPolicy()` 拒绝 URL 源。等价于用户在
    设置页开启「移动数据播放」，写 app 私有 DataStore；跑完删除恢复默认。"""
    if not allow:
        adb(serial, "shell", "run-as %s sh -c 'rm -f %s'" % (PKG, SETTINGS_FILE))
        return
    adb(serial, "shell", "am", "force-stop", PKG)
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/datastore'" % PKG)
    payload = A / "_a1_bypass_settings.tmp"
    payload.write_bytes(b'{"network":{"wifiOnly":false}}')
    tmp = "/data/local/tmp/cm_a1_bypass_settings.json"
    adb(serial, "push", str(payload), tmp)
    adb(serial, "shell", "cat %s | run-as %s sh -c 'cat > %s'"
        % (tmp, PKG, SETTINGS_FILE))
    adb(serial, "shell", "rm -f %s" % tmp)
    payload.unlink(missing_ok=True)


def resolve_app_uid(serial: str) -> str:
    """v2 修正：`dumpsys audio` 的 AudioPlaybackConfiguration 行不含包名，
    只有 `u/pid:<uid>/<pid>`。用本 app uid 关联才能判定 started 轨归属。"""
    out = sh(serial, "dumpsys package %s | grep -m1 userId=" % PKG)
    m = re.search(r"userId=(\d+)", out)
    return m.group(1) if m else ""


def snapshot(serial: str, tag: str, app_uid: str) -> dict:
    ms = sh(serial, "dumpsys media_session | grep -E "
            "'state=PlaybackState|metadata:|package=%s' | head -12" % PKG)
    au = sh(serial, "dumpsys audio | grep -E "
            "'AudioPlaybackConfiguration.*state:started|%s.*state:started' | head -12" % PKG)
    state = re.search(r"state=PlaybackState \{state=(\d+), position=(\d+)", ms)
    # v1 缺陷：`PKG in au` 恒 False（该行只有 uid/pid，没有包名）→ 系统性假阴性。
    # v2：按 uid 关联；同时保留 raw 供人工核对。
    uid_line = ("u/pid:%s/" % app_uid) in au if app_uid else False
    return {
        "tag": tag,
        "media_session_raw": ms.strip()[:900],
        "audio_started_raw": au.strip()[:900],
        "playback_state": int(state.group(1)) if state else None,
        "position": int(state.group(2)) if state else None,
        "metadata_null": "metadata: null" in ms,
        "app_uid": app_uid,
        "app_uid_line_present": uid_line,
        "app_audio_track_started": (uid_line and "state:started" in au),
    }


def dispatch(serial: str, key: str) -> dict:
    r = adb(serial, "shell", "cmd", "media_session", "dispatch", key)
    return {"key": key, "rc": r.returncode,
            "out": (r.stdout + r.stderr).decode("utf-8", "replace").strip()[:200]}


def analyse(text: str, pid: str) -> dict:
    """v2 修正：按本轮 app PID 分段统计，彻底隔离跨轮 logcat 残留；
    并补齐 AudioTrack 状态信号（Play/ACTIVE/frames delivered/Paused/Release/msg=2），
    不再只靠 6s 后的 dumpsys 快照推断「有没有播过」。只提取原始信号，不下结论。"""
    lines = text.splitlines()
    scoped = "\n".join(ln for ln in lines if ("(%s)" % pid) in ln) if pid else text
    slines = scoped.splitlines()
    full_writes = RE_WRITE.findall(text)
    full_codec = RE_CODEC.findall(text)
    writes = RE_WRITE.findall(scoped)
    return {
        "app_pid": pid,
        "log_lines": len(lines),
        "scoped_lines": len(slines),
        "scope_filter": "logcat 行含 (%s)" % pid if pid else "NO_PID__FULL_LOG",
        "open_codec": RE_CODEC.findall(scoped),
        "write_start_play": writes,
        "finish_async_103": len(RE_103.findall(scoped)),
        "minus_103_lines": sum(1 for ln in slines if "-103" in ln),
        "play_status": RE_STATUS.findall(scoped),
        "play_status_4": len(re.findall(r"Play status 4\b", scoped)),
        "play_status_3": len(re.findall(r"Play status 3\b", scoped)),
        "m_duration": RE_DURATION.findall(scoped),
        "do_prepare_async": len(RE_PREPARE.findall(scoped)),
        "prepare_remote_flags": RE_PREPARE_REMOTE.findall(scoped),
        "wiring_level_logs": RE_WIRING.findall(scoped),
        "remote_marker_counts": {m: scoped.count(m) for m in REMOTE_MARKERS},
        "local_marker_counts": {m: scoped.count(m) for m in LOCAL_MARKERS},
        # AudioTrack 一手状态（logcat 时序证据，不受快照窗口限制）
        "audio_track_play_calls": RE_AT_PLAY.findall(scoped),
        "audio_track_start_calls": len(RE_AT_START.findall(scoped)),
        "audio_track_active_markers": scoped.count("prior state:STATE_ACTIVE"),
        "frames_delivered": [int(x) for x in RE_FRAMES.findall(scoped)],
        "audio_track_paused": len(RE_AT_PAUSED.findall(scoped)),
        "audio_track_release_end": scoped.count("Release End"),
        "msg2_completion": len(RE_MSG2.findall(scoped)),
        "natural_ended_markers": len(RE_NATURAL_END.findall(scoped)),
        "seek_markers": sum(1 for ln in slines
                            if "seekTo" in ln or "ACTION_SEEK" in ln),
        "ended_markers": len(RE_MSG2.findall(scoped))
                         + len(RE_NATURAL_END.findall(scoped)),
        "no_write_start_play": len(writes) == 0,
        # 对照：v1 缺陷下若用全量日志会把上一轮信号误算进来。
        "full_log_open_codec_before_scope": full_codec,
        "full_log_write_start_play_before_scope": full_writes,
    }


def run_round(serial: str, kind: str, sample: str, steps: tuple, tail_s: float,
              stamp: str, app_uid: str) -> dict:
    """跑一轮：前置 → **先清空 logcat 再启动流式子进程** → 注入 → 取本轮 PID
    → 时间轴快照/dispatch → 收尾 → 按 PID 分段返回结构化取证。

    v2 修正：`v1` 把 `Popen(logcat)` 放在 `logcat -c` 之前，导致第二轮把
    第一轮残留日志读进管道（REMOTE 轮混入 LOCAL 轮 codec/write 信号）。
    """
    log_path = LOG_DIR / ("a1-bypass-%s-%s.txt" % (kind, stamp))
    log_file = log_path.open("wb")
    started = time.time()
    timeline: list[dict] = []
    snaps: dict[str, dict] = {}
    dispatches: list[dict] = []
    proc: subprocess.Popen | None = None
    pid = ""
    target = ""
    inj: subprocess.CompletedProcess | None = None

    def log_event(t: float, action: str, result: str) -> None:
        timeline.append({"t": round(t, 1), "action": action, "result": result})
        print("    [%.1fs] %s -> %s" % (t, action, result[:70]), flush=True)

    try:
        adb(serial, "shell", "am", "force-stop", PKG)
        adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
        time.sleep(4.0)
        adb(serial, "logcat", "-c")           # v2：先清空
        proc = subprocess.Popen(               # v2：再启动流式 logcat
            [str(ADB), "-s", serial, "logcat", "-v", "time"],
            stdout=log_file, stderr=subprocess.DEVNULL)
        if kind == "local":
            inj = adb(serial, "shell", "am", "startservice",
                      "-a", ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                      "--es", EXTRA_LOCAL, PRIVATE_DIR + "/" + sample)
            target = PRIVATE_DIR + "/" + sample
        else:
            url = "http://127.0.0.1:%d/%s" % (PORT, sample)
            inj = adb(serial, "shell", "am", "startservice",
                      "-a", ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                      "--es", EXTRA_URL, url)
            target = url
        log_event(time.time() - started, "注入 ACTION_PLAY %s" % target,
                  "rc=%d" % (inj.returncode if inj else -1))
        # v2：注入后取本轮 app PID，用于把 logcat 按进程分段（隔离残留）。
        time.sleep(1.0)
        pid_out = sh(serial, "pidof %s" % PKG).strip().split()
        pid = pid_out[0] if pid_out else ""
        log_event(time.time() - started, "解析本轮 app PID",
                  "pid=%s uid=%s" % (pid or "未知", app_uid))

        for t, act, label in steps:
            wait = t - (time.time() - started)
            if wait > 0:
                time.sleep(wait)
            now = time.time() - started
            if act == "snapshot":
                tag = label.split()[0]
                snaps[tag] = snapshot(serial, tag, app_uid)
                log_event(now, label, "快照完成 state=%s pos=%s started=%s"
                          % (snaps[tag]["playback_state"], snaps[tag]["position"],
                             snaps[tag]["app_audio_track_started"]))
            else:
                key = label.split("dispatch ")[1].split("（")[0].strip()
                r = dispatch(serial, key)
                dispatches.append(dict(r, t=round(now, 1), label=label))
                log_event(now, label, "rc=%d %s" % (r["rc"], r["out"][:40]))
        time.sleep(tail_s)
        tag = "F_final" if kind == "local" else "R_final"
        snaps[tag] = snapshot(serial, tag, app_uid)
        log_event(time.time() - started, "snapshot_%s" % tag, "收尾快照完成")
    finally:
        if proc is not None:
            try:
                proc.terminate()
                proc.wait(timeout=10)
            except subprocess.SubprocessError:
                proc.kill()
        log_file.close()

    text = log_path.read_text(encoding="utf-8", errors="replace")
    states = [s["playback_state"] for s in snaps.values()
              if s["playback_state"] is not None]
    positions = [s["position"] for s in snaps.values()
                 if s["position"] is not None]
    return {
        "kind": kind,
        "target": target,
        "elapsed_s": round(time.time() - started, 1),
        "app_pid": pid,
        "app_uid": app_uid,
        "log_file": str(log_path.relative_to(ROOT)).replace("\\", "/"),
        "log_sha256": hashlib.sha256(log_path.read_bytes()).hexdigest(),
        "injection": {
            "action": ACTION_PLAY,
            "extra": EXTRA_LOCAL if kind == "local" else EXTRA_URL,
            "startservice_rc": inj.returncode if inj else -1,
            "startservice_stdout": (
                inj.stdout.decode("utf-8", "replace").strip()[:200]
                if inj else ""),
        },
        "timeline": timeline,
        "dispatches": dispatches,
        "snapshots": snaps,
        "states_observed": states,
        "positions_observed": positions,
        "distinct_positions": sorted(set(positions)),
        "app_audio_track_started_any": any(
            s["app_audio_track_started"] for s in snaps.values()),
        "log_analysis": analyse(text, pid),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 / A1 绕过取流时间盒真机探针")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--sample", default=SAMPLE)
    ap.add_argument("--skip-remote", action="store_true",
                    help="设备窗口紧张时跳过 REMOTE 同窗对照轮（如实登记）")
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
    app_uid = resolve_app_uid(serial)
    print("[INFO] 本 app uid=%s（用于 dumpsys audio 归属判定）" % (
        app_uid or "未解析"), flush=True)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    LOG_DIR.mkdir(parents=True, exist_ok=True)

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

    push_info: dict = {}
    local_round: dict = {}
    remote_round: dict = {}
    restore_errors: list[str] = []
    try:
        # ---- LOCAL 主轮前置：零权限注入完整样本到 app 私有目录 ----
        print("[INFO] LOCAL 主轮：注入 app 私有本地文件（绕过取流）...", flush=True)
        push_info = push_local(serial, src, args.sample)
        print("[INFO] push size_match=%s / %s" % (
            push_info["size_match"], push_info["ls_raw"]), flush=True)
        print("[INFO] LOCAL 主轮：约 67s 流式 logcat + 多信号快照...", flush=True)
        local_round = run_round(serial, "local", args.sample, LOCAL_STEPS,
                                LOCAL_TAIL_S, stamp, app_uid)

        # ---- REMOTE 同窗对照轮（已知 FAIL，仅作同设备同窗对照）----
        if args.skip_remote:
            print("[INFO] --skip-remote：REMOTE 对照轮 NOT RUN（如实登记）", flush=True)
        else:
            print("[INFO] REMOTE 对照轮：同一样本走 URL 源（约 32s）...", flush=True)
            # v2 修正：先删掉 LOCAL 轮注入的私有样本，避免 app 播放队列里
            # 残留 LOCAL 源被 REMOTE 轮复用，造成两轮装配链串扰。
            cleanup_private_sample(serial, args.sample)
            set_gate(serial, True)
            adb(serial, "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)
            remote_round = run_round(serial, "remote", args.sample, REMOTE_STEPS,
                                     REMOTE_TAIL_S, stamp, app_uid)
    finally:
        # 无条件恢复现场：reverse / wifiOnly 闸门 / http.server / 私有样本 / tmp
        try:
            adb(serial, "reverse", "--remove", "tcp:%d" % PORT)
        except Exception as exc:                       # noqa: BLE001
            restore_errors.append("reverse: %s" % exc)
        try:
            set_gate(serial, False)
        except Exception as exc:                       # noqa: BLE001
            restore_errors.append("set_gate: %s" % exc)
        try:
            httpd.shutdown()
        except Exception as exc:                       # noqa: BLE001
            restore_errors.append("httpd: %s" % exc)
        try:
            cleanup_private_sample(serial, args.sample)
        except Exception as exc:                       # noqa: BLE001
            restore_errors.append("cleanup: %s" % exc)
        print("[INFO] 现场恢复完成（异常 %d 项）" % len(restore_errors), flush=True)

    d = {
        "probe_run": "v2_fixed",
        "supersedes": "analysis/l3-f4-a1-bypass-live-initial-v1.json（首轮试跑，仅作缺陷诊断留痕）",
        "defects_fixed": [
            "v1：Popen(logcat) 早于 logcat -c，REMOTE 轮读入 LOCAL 轮残留日志，造成跨轮信号污染；v2 先 -c 再启动，并按本轮 app PID 分段统计。",
            "v1：app_audio_track_started 用 PKG in dumpsys audio 判定，但该行只有 u/pid 不含包名，恒 False（系统性假阴性）；v2 用 app uid 关联，并补 logcat 一手 AudioTrack 状态信号。",
            "v1：REMOTE 轮前未删除 LOCAL 轮注入的私有样本，播放队列可能复用 LOCAL 源；v2 在 REMOTE 轮前显式清理。",
            "v1：首快照在注入后 6s，错过 LOCAL 轮 AudioTrack ACTIVE 的约 350ms 窗口；v2 增加 t=2s 早快照，并以 logcat 时序信号为主证据。",
        ],
        "app_uid": app_uid,
        "method": "12 §61.79 / F4 · A1「绕过取流」时间盒真机探针（只取证、不裁决）",
        "generated_by": "tools/l3_f4_a1_bypass_live_probe.py",
        "mode": "device_live_probe",
        "device_run": True,
        "serial": serial,
        "device_model": "Redmi K60 23013RK75C / mondrian / Android 13 / arm64-v8a",
        "apk_installed": apk,
        "sample": args.sample,
        "sample_bytes": src.stat().st_size,
        "route": "A1_BYPASS_STREAM__LOCAL_VS_REMOTE_SAME_SAMPLE",
        "verdict_tool": "tools/l3_f4_a1_bypass_verdict.py（纯离线裁决，本工具不下结论）",
        "push": push_info,
        "local_round": local_round,
        "remote_round": remote_round or {
            "kind": "remote", "status": "NOT_RUN__SKIP_REMOTE",
        },
        "remote_round_skipped": bool(args.skip_remote),
        "restore_errors": restore_errors,
        "honest_notes": [
            "本工具只取证；PASS/FAIL 裁决由离线工具按 §61.74/§61.75 多信号口径给出。",
            "「绕行 ≠ 修复」：LOCAL 出声只证明绕过 REMOTE 取流缺陷可播，"
            "不修 REMOTE 本身；§61.75 终局裁决不变，F4 仍 BLOCKED。",
            "真机命令全部带 -s <serial>；未 pm grant、未改设备系统设置、配对码不落盘。",
            "临时 wifiOnly=false（仅 REMOTE 轮）/ adb reverse / http.server / "
            "logcat 子进程 / 注入的私有样本与 /data/local/tmp 残留均在 finally 恢复。",
            "零 P0 源码改动、未重编译 / 未重装、so 二进制原样、不动解码器。",
            "wiring_level_logs 为空 ⇔ 现场 APK 不含 §61.69 接线（基线 APK），"
            "与 §61.73/§61.75 的现场口径一致；本工具不改 APK。",
            "v2 为首轮试跑发现 4 处取证缺陷后的修正重跑；首轮证据归档为 "
            "l3-f4-a1-bypass-live-initial-v1.json，仅作缺陷诊断留痕，不作裁决依据。",
            "logcat 按本轮 app PID 分段；AudioTrack 一手状态（Play/ACTIVE/"
            "frames delivered/Paused/Release/msg=2）优先于 6s 后的 dumpsys 快照。",
        ],
    }
    out = ROOT / args.out if not Path(args.out).is_absolute() else Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 取证落盘 %s（v2_fixed / 首轮留痕不改）" % out.name)
    for kind, r in (("LOCAL", local_round), ("REMOTE", remote_round)):
        if not r or "log_analysis" not in r:
            continue
        la = r["log_analysis"]
        print("[INFO] %s 轮 pid=%s scoped=%d 行 / codec=%s / write=%s / -103=%d / "
              "AT_Play=%s / ACTIVE=%d / frames=%s / msg2=%d / released=%d / "
              "remote_flags=%s / states=%s / pos=%s"
              % (kind, la["app_pid"], la["scoped_lines"], la["open_codec"],
                 la["write_start_play"], la["finish_async_103"],
                 la["audio_track_play_calls"], la["audio_track_active_markers"],
                 la["frames_delivered"], la["msg2_completion"],
                 la["audio_track_release_end"], la["prepare_remote_flags"],
                 r["states_observed"], r["distinct_positions"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
