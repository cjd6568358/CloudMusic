#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.49 / F3 · 逐格式 × REMOTE/LOCAL 双路径真机矩阵（可复跑）。

前置：
  1. `python tools/l3_f3_fixture_gen.py` 已产出 analysis/l3-f3-fixtures/ 与清单；
  2. debug APK 已安装（含 §61.49 的 `EXTRA_SOURCE_URL` 注入入口）；
  3. 设备在线，所有 adb 命令带 `-s <serial>`。

LOCAL  : `ACTION_PLAY` + `EXTRA_LOCAL_PATH`，样本经 `run-as` 写入 app 私有
         `files/music/`，零权限（§61.47 通道）。
REMOTE : `ACTION_PLAY` + `EXTRA_SOURCE_URL`，PC 侧 `http.server 8765` +
         `adb reverse tcp:8765 tcp:8765`，URL = `http://127.0.0.1:8765/f3_<ext>.<ext>`
         （A3 G01 回环口径）。

每格抓全量 logcat 存 analysis/l3-f3-logs/，解析 extension / codec / 播放链 /
错误回调，产出 analysis/l3-f3-matrix.json。**判据不补全、不伪造**：
`NO_SAMPLE` 不计 PASS 也不计 FAIL；截断样本不声称完整时长 / ENDED 通过。
"""
from __future__ import annotations

import argparse
import functools
import json
import re
import subprocess
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
FIXTURES = ROOT / "analysis" / "l3-f3-fixtures"
MANIFEST = ROOT / "analysis" / "l3-f3-fixtures.json"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
OUT = ROOT / "analysis" / "l3-f3-matrix.json"

PKG = "com.example.cloudmusic"
ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
EXTRA_LOCAL = "com.example.cloudmusic.extra.LOCAL_PATH"
EXTRA_URL = "com.example.cloudmusic.extra.SOURCE_URL"
PORT = 8765
PRIVATE_DIR = "/data/data/%s/files/music" % PKG

TAGS = ("CM-DataReq", "CM-Player", "NEPlayer", "NMAudioPlayer", "CloudMusicRuntime",
        "AndroidRuntime", "BufferSource", "AudioTrack", "CM-Media3",
        "MediaCodecAudioRenderer", "FfmpegAudioRenderer")


def adb(serial: str, *args: str, stdin: bytes | None = None,
        timeout: int = 90) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(ADB), "-s", serial, *args],
        input=stdin, capture_output=True, timeout=timeout,
    )


def push_local(serial: str, src: Path, name: str) -> bool:
    """adb shell 的 stdin 直传会截断（实测只写 248 B，见 §61.49 诊断）；
    改为 §61.47 已验证的「push 到 /data/local/tmp → shell cat | run-as cat」通道。"""
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/music'" % PKG)
    tmp = "/data/local/tmp/%s" % name
    r = adb(serial, "push", str(src), tmp)
    if r.returncode != 0:
        print("  [WARN] adb push rc=%d %s" % (r.returncode, r.stderr[:160]))
    adb(serial, "shell",
        "cat %s | run-as %s sh -c 'cat > files/music/%s'" % (tmp, PKG, name))
    check = adb(serial, "shell", "run-as %s sh -c 'ls -l files/music/%s'" % (PKG, name))
    adb(serial, "shell", "rm -f %s" % tmp)
    return str(src.stat().st_size).encode() in check.stdout


# 设备当前默认网络实测为 MOBILE[NR]（WiFi 仅用于无线 adb），而 AppSettings 默认
# network.wifiOnly=true → PlayerController.blockedByNetworkPolicy() 会拒绝 URL 源。
# 这是产品闸门的真实行为，不是缺陷。为跑 REMOTE 矩阵，按用户在设置页可做的等价操作
# 临时写入 app 私有 DataStore（显式 JSON codec，字段缺失回落默认值），跑完删除恢复默认。
SETTINGS_FILE = "files/datastore/cloudmusic_settings.pb"


def set_network_gate(serial: str, allow_mobile: bool) -> None:
    if not allow_mobile:
        adb(serial, "shell", "run-as %s sh -c 'rm -f %s'" % (PKG, SETTINGS_FILE))
        return
    adb(serial, "shell", "am", "force-stop", PKG)
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/datastore'" % PKG)
    payload = Path(ROOT / "analysis" / "_f3_settings.tmp")
    payload.write_bytes(b'{"network":{"wifiOnly":false}}')
    tmp = "/data/local/tmp/cm_f3_settings.json"
    adb(serial, "push", str(payload), tmp)
    adb(serial, "shell",
        "cat %s | run-as %s sh -c 'cat > %s'" % (tmp, PKG, SETTINGS_FILE))
    adb(serial, "shell", "rm -f %s" % tmp)
    payload.unlink(missing_ok=True)


def last(pattern: str, text: str, group: int = 1) -> str | None:
    found = re.findall(pattern, text)
    return found[-1] if found else None


def parse_log(text: str, ext: str, path_kind: str) -> dict:
    prepare = re.findall(r"prepare trackId=([^ ]+) remote=(\w+) url=(\S+)", text)
    last_prepare = prepare[-1] if prepare else None
    extension = last(r"resourceInfo extension=(\S+)", text)
    codec = last(r"Open codec for (\S+)", text)
    duration = last(r"mDuration (\d+)", text)
    writes = re.findall(r"Write start play nErr:(\d+)", text)
    probe = last(r"probeLength=(-?\d+)", text)
    keys = [l for l in text.splitlines() if any(t in l for t in TAGS)]

    # §61.86：引擎指纹——B2（Media3）与 A 路线（EngineAdapter）的日志特征不同，
    # 判据分支必须跟引擎走，不能用 A 路线签名硬套 B2 运行（会整批假 FAIL）。
    engine = ("media3" if ("engine=media3-1.11.1" in text or "CM-Media3" in text)
              else "legacy" if "CM-DataReq" in text else "unknown")

    errors = {
        "source_callback_on_error": "SourceCallback.onError" in text,
        "fatal_exception": "FATAL EXCEPTION" in text,
        "anr": "ANR in com.example.cloudmusic" in text,
        "write_nerr_nonzero": any(int(x) != 0 for x in writes),
        "open_source_failed": bool(re.search(r"openSource [1-9]", text)),
        "engine_prepare_failed": "ENGINE_PREPARE" in text,
        "probe_failed": "probeLength FAILED" in text,
        # §61.86 B2 侧错误信号（engine_error= / onPlayerError 的 error= 行）
        "media3_error": bool(re.search(r"CM-Media3: (engine_)?error=", text)),
    }
    hard_error = any(errors[k] for k in (
        "source_callback_on_error", "fatal_exception", "anr",
        "write_nerr_nonzero", "open_source_failed", "engine_prepare_failed",
        "media3_error",
    ))

    path_evidence = {
        "local": {
            "get_local_path": "getLocalPath path=" in text,
            "create_local_source": "CreateLocalSource" in text,
            "xor_open": "XorLocalSource::Open success" in text,
            # B2：prepare 行的 localPath 字段非 "-" 即为 LOCAL 装配证据
            "media3_local": bool(re.search(r"localPath=(?!-)\S+", text)),
        },
        "remote": {
            "get_url": "getUrl url=http://127.0.0.1:%d/" % PORT in text,
            "data_request_handler": "createDataRequestHandler" in text,
            "start_range": bool(re.search(r"Start id=\d+", text)),
            # B2：prepare 行 url=http://127.0.0.1:8765/... 即 REMOTE 装配证据
            "media3_remote": "url=http://127.0.0.1:%d/" % PORT in text,
        },
    }[path_kind]

    codec_opened = bool(codec) or "Open input file success" in text
    played = any(int(x) == 0 for x in writes)

    if engine == "media3":
        # §61.86 B2 判据（对齐 §61.74 多信号口径，不依赖 A 路线原生日志）：
        # READY 且 durationMs>0 ≈「解码链打开」；state=PLAYING ≈「真实出声」。
        ready = re.findall(r"CM-Media3: state=READY durationMs=(-?\d+)", text)
        dur_ms = int(ready[-1]) if ready else -1
        playing = "CM-Media3: state=PLAYING" in text
        codec_opened = dur_ms > 0
        played = playing
        duration = str(dur_ms) if dur_ms > 0 else None

    if last_prepare is None:
        verdict = "FAIL"
        reason = "未见 prepare（注入未生效 / 后台启动被拒）"
    elif hard_error:
        verdict = "FAIL"
        reason = "存在明确错误回调 / 崩溃 / 打开失败"
    elif codec_opened and played:
        verdict = "PASS"
        reason = ("解码链打开且 Write start play nErr:0" if engine == "legacy"
                  else "STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声）")
    elif codec_opened:
        verdict = "PARTIAL"
        reason = ("解码链打开但未见成功 Write start play" if engine == "legacy"
                  else "STATE_READY(duration>0) 但未见 isPlaying")
    else:
        verdict = "FAIL"
        reason = ("未见 Open codec / Open input file success" if engine == "legacy"
                  else "未见 STATE_READY(duration>0)")

    return {
        "path": path_kind,
        "engine": engine,
        "verdict": verdict,
        "reason": reason,
        "prepare_seen": last_prepare is not None,
        "prepare_remote": last_prepare[1] if last_prepare else None,
        "prepare_url": last_prepare[2] if last_prepare else None,
        "extension": extension,
        "extension_ok": (extension or "").lower() == ext,
        "codec": codec,
        # 原生日志的原始 mDuration 值（单位未在 ABI 契约中钉死，不是毫秒，不换算）。
        "mDuration_raw": int(duration) if duration else None,
        "write_start_play": played,
        "write_nerr_values": writes[:5],
        "probe_length": probe,
        "path_evidence": path_evidence,
        "errors": errors,
        "seek_observed": "seekTo" in text or "ACTION_SEEK" in text,
        "ended_observed": "PlaybackState.STATE_STOPPED" in text or
                          "onCompletion" in text or "ENDED" in text,
        "diag_line_count": len(keys),
    }


def run_cell(serial: str, ext: str, path_kind: str, wait_s: float,
             truncated: bool) -> dict:
    src = FIXTURES / ("f3_%s.%s" % (ext, ext))
    name = src.name
    adb(serial, "shell", "am", "force-stop", PKG)
    adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(1.4)                       # 等前台态稳定 / 上一次自动恢复结束
    adb(serial, "logcat", "-c")

    if path_kind == "local":
        ok = push_local(serial, src, name)
        if not ok:
            print("  [WARN] %s-%s 文件推送后大小核对失败，仍继续取证" % (ext, path_kind))
        r = adb(serial, "shell", "am", "startservice", "-a", ACTION_PLAY,
                "-c", "android.intent.category.DEFAULT",
                "--es", EXTRA_LOCAL, PRIVATE_DIR + "/" + name)
    else:
        url = "http://127.0.0.1:%d/%s" % (PORT, name)
        r = adb(serial, "shell", "am", "startservice", "-a", ACTION_PLAY,
                "-c", "android.intent.category.DEFAULT",
                "--es", EXTRA_URL, url)

    time.sleep(wait_s)
    log = adb(serial, "logcat", "-d", "-v", "time").stdout.decode("utf-8", "replace")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    (LOG_DIR / ("%s-%s.txt" % (ext, path_kind))).write_text(log, encoding="utf-8")
    cell = parse_log(log, ext, path_kind)
    cell["format"] = ext
    cell["truncated_sample"] = truncated
    cell["sample_bytes"] = src.stat().st_size
    cell["startservice_stdout"] = r.stdout.decode("utf-8", "replace").strip()[:200]
    cell["startservice_stderr"] = r.stderr.decode("utf-8", "replace").strip()[:200]
    cell["log_file"] = "analysis/l3-f3-logs/%s-%s.txt" % (ext, path_kind)
    return cell


def main() -> int:
    ap = argparse.ArgumentParser(description="F3 双路径真机矩阵")
    ap.add_argument("--serial", default="10.0.0.2:39853")
    ap.add_argument("--wait", type=float, default=6.0, help="每格注入后等待秒数")
    ap.add_argument("--formats", default="", help="逗号分隔，缺省=清单里全部")
    ap.add_argument("--paths", default="local,remote")
    args = ap.parse_args()

    if not MANIFEST.is_file():
        print("[FAIL] 缺少样本清单，请先跑 l3_f3_fixture_gen.py")
        return 1
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    available = list(manifest["formats"].keys())
    formats = [x.strip() for x in args.formats.split(",") if x.strip()] or available
    bad = [x for x in formats if x not in available]
    if bad:
        print("[FAIL] 无样本格式：%s" % bad)
        return 1
    paths = [x.strip() for x in args.paths.split(",") if x.strip()]

    state = adb(args.serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1

    class Quiet(SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

    handler = functools.partial(Quiet, directory=str(FIXTURES))
    httpd = ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    if "remote" in paths:
        rev = adb(args.serial, "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)
        print("[INFO] adb reverse: %s" % rev.stdout.decode().strip())

    cells: list[dict] = []
    started = time.time()
    if "remote" in paths:
        set_network_gate(args.serial, True)
        print("[INFO] 测试前置：临时置 network.wifiOnly=false（device 默认网络 MOBILE），"
              "跑完删除设置文件恢复默认")
    try:
        for ext in formats:
            truncated = manifest["formats"][ext].get("truncated", False)
            for path_kind in paths:
                print("[RUN ] %-5s × %-6s ..." % (ext, path_kind), flush=True)
                cell = run_cell(args.serial, ext, path_kind, args.wait, truncated)
                cells.append(cell)
                print("       → %-7s ext=%-10s codec=%-10s write=%s reason=%s"
                      % (cell["verdict"], cell["extension"], cell["codec"],
                         cell["write_start_play"], cell["reason"]), flush=True)
    finally:
        httpd.shutdown()
        if "remote" in paths:
            adb(args.serial, "reverse", "--remove", "tcp:%d" % PORT)
            set_network_gate(args.serial, False)
            print("[INFO] 测试前置已恢复：删除 app 私有设置文件，wifiOnly 回到默认 true")

    counts: dict[str, int] = {}
    for c in cells:
        counts[c["verdict"]] = counts.get(c["verdict"], 0) + 1
    no_sample = manifest["no_sample_formats"]
    result = {
        "method": "12 §61.49 / F3：逐格式 × REMOTE/LOCAL 双路径真机矩阵",
        "generated_by": "tools/l3_f3_matrix_run.py",
        "serial": args.serial,
        "device": "Redmi K60 23013RK75C / Android 13 / arm64-v8a",
        "local_channel": "ACTION_PLAY + EXTRA_LOCAL_PATH → app 私有 files/music/（零权限）",
        "remote_channel": ("ACTION_PLAY + EXTRA_SOURCE_URL → http://127.0.0.1:8765/ "
                           "+ adb reverse + PC http.server"),
        "cells": cells,
        "counts": counts,
        "no_sample_formats": no_sample,
        "no_sample_cells": [{"format": e, "path": p, "verdict": "NO_SAMPLE"}
                            for e in no_sample for p in ("local", "remote")],
        "elapsed_s": round(time.time() - started, 1),
        "test_precondition": {
            "network_wifi_only": "false（仅 REMOTE 跑批期间）",
            "reason": ("设备默认网络实测 MOBILE[NR]，AppSettings 默认 wifiOnly=true 会由 "
                       "PlayerController.blockedByNetworkPolicy() 拒绝 URL 源；"
                       "等价于用户在设置页开启「移动数据播放」"),
            "mechanism": "写入 app 私有 files/datastore/cloudmusic_settings.pb（显式 JSON codec）",
            "restored": "跑完删除该文件恢复默认",
            "device_settings_unchanged": True,
        },
        "overall_verdict": "PARTIAL",
        "overall_reason": (
            "7 种格式无真实样本（NO_SAMPLE）；大文件为前 4 MiB 片段，只验证 "
            "demuxer/codec 打开与播放链；未逐格验证 seek / 完整时长 / ENDED；"
            "不得据此写「全格式已支持」"
        ),
        "honest_notes": [
            "每格均 force-stop + 前台拉起 + 清空 logcat 后单独注入，避免上一格残留。",
            "截断样本（flac/wav/ape）不计完整时长 / ENDED 通过；WAV 片段已修正 RIFF/data 长度。",
            "aiff 为纯标准库合成容器、未经第三方解码器校验；mp3/wma/ogg 为真实完整音频。",
            "本工具不申请权限、不改设备设置；真机命令全部带 -s <serial>。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("\n[PASS] 矩阵落盘 %s（%d 格 / %.1fs）" % (OUT.relative_to(ROOT),
                                                  len(cells), result["elapsed_s"]))
    print("[INFO] counts=%s / NO_SAMPLE=%d 种" % (counts, len(no_sample)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
