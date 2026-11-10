#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.50 / F4 · moov-at-end 竞态探针（可复跑、只读分析 + 真机取证）。

背景：`UrlDataRequest.sink.onEnd()` 对成功 EOF 固定等待 2s 才回传
`OnDataRequestEnd`；历史 A3 §61.26 实证「立即 onEnd 会让 push→读0→重试以
5ms/轮烧完预算（-103），因 native normal mode 的 OnWriteNetData 写线程异步
落 vfile 需 ~50ms」。F4 要根治该掩盖，但历史 G01 用的是 **faststart m4a**
（moov 前置，绕开了 seek-to-end 路径），缺真正的复现载体。

本探针用 `analysis/l3-f3-fixtures/f3_m4a.m4a` 做验证：该样本由设备上已有的
fMP4（`ftyp + moov + [styp/moof/mdat]×N`）**把 moov 整体挪到文件末尾**构造，
fMP4 片段靠 moof/trun 相对偏移定位、不依赖 stco 绝对地址，故重排后结构合法。
若 native 需要 seek 到尾部读 moov，就会复现 F4 关心的竞态路径。

复用 `l3_f3_matrix_run.py` 的 adb / 推送 / 网络前置 / logcat 解析函数，
不修改 F3 矩阵 JSON（本探针是 F4 的独立证据）。
产出 `analysis/l3-f4-logs/` + `analysis/l3-f4-eof-probe.json`。
"""
from __future__ import annotations

import argparse
import functools
import importlib.util
import json
import re
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("f3runner", ROOT / "tools" / "l3_f3_matrix_run.py")
f3 = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(f3)

FIXTURES = ROOT / "analysis" / "l3-f3-fixtures"
SAMPLE = FIXTURES / "f3_m4a.m4a"
LOG_DIR = ROOT / "analysis" / "l3-f4-logs"
OUT = ROOT / "analysis" / "l3-f4-eof-probe.json"
PKG = f3.PKG
PORT = f3.PORT


def analyse(text: str, path_kind: str) -> dict:
    starts = re.findall(r"Start id=(\d+) offset=(\d+) length=(\d+)", text)
    size = SAMPLE.stat().st_size
    tail_reads = [s for s in starts if int(s[1]) > size * 0.90]
    return {
        "path": path_kind,
        "prepare_seen": "prepare trackId=" in text,
        "extension": (re.findall(r"resourceInfo extension=(\S+)", text) or [None])[-1],
        "start_requests": len(starts),
        "tail_start_requests": len(tail_reads),
        "tail_start_samples": tail_reads[:5],
        "moov_seek_triggered": bool(tail_reads),
        "codec": (re.findall(r"Open codec for (\S+)", text) or [None])[-1],
        "open_source": (re.findall(r"doPrepareAsync openSource (\d+)", text) or [None])[-1],
        "write_start_ok": "Write start play nErr:0" in text,
        "on_end_delayed": "delayed 2s or immediate" in text,
        "eof_retry_minus103": "-103" in text or "Retry Failed" in text,
        "source_error": "SourceCallback.onError" in text,
        "fatal": "FATAL EXCEPTION" in text,
        "on_end_lines": re.findall(r"onEnd id=\d+ error=\S+[^\n]*", text)[-5:],
        "start_lines": re.findall(r"Start id=\d+ offset=\d+ length=\d+", text)[-8:],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 moov-at-end 竞态探针")
    ap.add_argument("--serial", default="10.0.0.2:39853")
    ap.add_argument("--wait", type=float, default=9.0)
    ap.add_argument("--paths", default="local,remote")
    args = ap.parse_args()

    if not SAMPLE.is_file():
        print("[FAIL] 缺少 moov-at-end 样本 %s" % SAMPLE)
        return 1
    if f3.adb(args.serial, "get-state").stdout.decode().strip() != "device":
        print("[FAIL] 设备不在线")
        return 1
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    paths = [p.strip() for p in args.paths.split(",") if p.strip()]
    name = SAMPLE.name

    class Quiet(SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", PORT),
                                functools.partial(Quiet, directory=str(FIXTURES)))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    f3.adb(args.serial, "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)
    f3.set_network_gate(args.serial, True)
    cells = []
    try:
        for kind in paths:
            f3.adb(args.serial, "shell", "am", "force-stop", PKG)
            f3.adb(args.serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
            time.sleep(1.4)
            f3.adb(args.serial, "logcat", "-c")
            if kind == "local":
                ok = f3.push_local(args.serial, SAMPLE, name)
                print("[INFO] push_local size_ok=%s" % ok)
                f3.adb(args.serial, "shell", "am", "startservice", "-a", f3.ACTION_PLAY,
                       "-c", "android.intent.category.DEFAULT", "--es", f3.EXTRA_LOCAL,
                       f3.PRIVATE_DIR + "/" + name)
            else:
                url = "http://127.0.0.1:%d/%s" % (PORT, name)
                f3.adb(args.serial, "shell", "am", "startservice", "-a", f3.ACTION_PLAY,
                       "-c", "android.intent.category.DEFAULT", "--es", f3.EXTRA_URL, url)
            time.sleep(args.wait)
            log = f3.adb(args.serial, "logcat", "-d", "-v", "time").stdout.decode(
                "utf-8", "replace")
            (LOG_DIR / ("m4a-%s.txt" % kind)).write_text(log, encoding="utf-8")
            cell = analyse(log, kind)
            cell["log_file"] = "analysis/l3-f4-logs/m4a-%s.txt" % kind
            cells.append(cell)
            print("[RUN ] m4a × %-6s prepare=%s ext=%s codec=%s openSource=%s "
                  "tailStarts=%d delayed=%s -103=%s"
                  % (kind, cell["prepare_seen"], cell["extension"], cell["codec"],
                     cell["open_source"], cell["tail_start_requests"],
                     cell["on_end_delayed"], cell["eof_retry_minus103"]))
    finally:
        httpd.shutdown()
        f3.adb(args.serial, "reverse", "--remove", "tcp:%d" % PORT)
        f3.set_network_gate(args.serial, False)

    triggered = any(c["moov_seek_triggered"] for c in cells)
    playable = any(c["write_start_ok"] for c in cells)
    result = {
        "method": "12 §61.50 / F4：moov-at-end fMP4 样本的竞态路径真机探针",
        "generated_by": "tools/l3_f4_eof_probe.py",
        "serial": args.serial,
        "sample": str(SAMPLE.relative_to(ROOT)).replace("\\", "/"),
        "sample_bytes": SAMPLE.stat().st_size,
        "sample_origin": ("设备 /sdcard/Download/SaveTwitter…(1280p).mp4 前 16MiB；"
                          "纯 Python 把顶层 moov 从 ftyp 之后挪到文件末尾"
                          "（fMP4 靠 moof/trun 相对偏移，不依赖 stco 绝对地址）"),
        "moov_offset": 3917485,
        "cells": cells,
        "verdict": {
            "moov_tail_seek_triggered": triggered,
            "codec_and_playback_ok": playable,
            "f4_repro_carrier": bool(triggered and playable),
        },
        "honest_notes": [
            "样本是人工重排的 fMP4 片段（前 ~3.9MB），不是完整真实 m4a 音频；"
            "若 demuxer 打不开，不能据此判播放器不支持 m4a。",
            "本探针不修改 UrlDataRequest、不修改 F3 矩阵 JSON；只做取证。",
            "F4 是否改 P0 Java 层，须以本探针是否能稳定复现竞态为前提，不未取证先改。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[PASS] 探针落盘 %s" % OUT.relative_to(ROOT))
    print("[INFO] moov 尾部 seek=%s / 可播=%s / 可作 F4 复现载体=%s"
          % (triggered, playable, result["verdict"]["f4_repro_carrier"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
