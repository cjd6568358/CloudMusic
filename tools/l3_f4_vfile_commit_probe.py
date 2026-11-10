#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.62 / F4 后续：normal mode vfile `commit` 时序的真机纯观测（零代码改动）。

背景（§61.61 已锁定）：自洽 fMP4 × REMOTE 无 codec 的第二卡点是
「vfile 流式落盘跟不上 fMP4 跳跃式 probe 读」：`commit=368640` 时
demuxer 要读 `offset=605007` → `ReadFromFileNoLock readed_len:0 result:-2`。
但当时挂账未区分两种机理：
  (A) 落盘**速度/进度不足** —— 给足时间 commit 会增长并最终追上 605007；
  (B) commit / Range **记账语义不允许回填跳跃区间** —— 无论等多久
      commit 都停在原值，`force:true` 重试也拿不到目标字节。

本脚本不改任何代码、不改 P0 基线、不覆盖既有证据，只在设备在场时：
  用 P0 基线 APK + 自洽样本 `f3_m4a_complete.m4a` 跑一格 REMOTE，
  流式抓 60 s 全量 logcat，解析 commit 轨迹 / 跳跃读 / 重试 / codec，
  回答「commit 是否随等待增长」并如实登记结论。

用法：
  python tools/l3_f4_vfile_commit_probe.py --serial <ip:port> [--wait 60]

恢复：finally 无条件清理 adb reverse、删除临时的 app 私有 wifiOnly 设置、
关闭 PC http.server、停止 logcat 子进程；设备系统设置不变。
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
ADB = ROOT / "tools" / "bin" / "adb.exe"
FIXTURES = ROOT / "analysis" / "l3-f3-fixtures"
LOG_DIR = ROOT / "analysis" / "l3-f4-logs"
OUT = ROOT / "analysis" / "l3-f4-vfile-commit-probe.json"

PKG = "com.example.cloudmusic"
ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
EXTRA_URL = "com.example.cloudmusic.extra.SOURCE_URL"
PORT = 8765
SETTINGS_FILE = "files/datastore/cloudmusic_settings.pb"
JUMP_OFFSET = 605007
BASELINE_COMMIT = 368640

RE_WAIT_DETAIL = re.compile(
    r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+).*?"
    r"WaitRequest cur offset:\s*(\d+),\s*cur end :\s*(\d+),\s*"
    r"expect offset :\s*(\d+),\s*end :\s*(\d+),\s*commit :\s*(\d+)")
RE_WAIT_FORCE = re.compile(
    r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+).*?"
    r"WaitRequest offset:(\d+), length:(\d+), signal_size:(\d+), force:(\w+)")
RE_READ = re.compile(
    r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+).*?"
    r"ReadFromFileNoLock\s+Local File Read expect len:\d+,\s*"
    r"offset:(\d+),\s*readed_len:(\d+),\s*result:(-?\d+)")
RE_START = re.compile(r"Start id=(\d+) offset=(\d+) length=(\d+)")
RE_ONEND = re.compile(r"onEnd id=(\d+)")
RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(\d+)")
RE_STATUS = re.compile(r"Play status (\d+)")


def adb(serial: str, *args: str, timeout: int = 90) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(ADB), "-s", serial, *args],
        capture_output=True, timeout=timeout)


def set_network_gate(serial: str, allow_mobile: bool) -> None:
    if not allow_mobile:
        adb(serial, "shell", "run-as %s sh -c 'rm -f %s'" % (PKG, SETTINGS_FILE))
        return
    adb(serial, "shell", "am", "force-stop", PKG)
    adb(serial, "shell", "run-as %s sh -c 'mkdir -p files/datastore'" % PKG)
    payload = ROOT / "analysis" / "_vfile_settings.tmp"
    payload.write_bytes(b'{"network":{"wifiOnly":false}}')
    tmp = "/data/local/tmp/cm_vfile_settings.json"
    adb(serial, "push", str(payload), tmp)
    adb(serial, "shell",
        "cat %s | run-as %s sh -c 'cat > %s'" % (tmp, PKG, SETTINGS_FILE))
    adb(serial, "shell", "rm -f %s" % tmp)
    payload.unlink(missing_ok=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 vfile commit 时序真机纯观测")
    ap.add_argument("--serial", default="10.0.0.2:39737")
    ap.add_argument("--wait", type=float, default=60.0, help="观测窗口秒数")
    # 12 §61.63：刚 install -r 后首次冷启动 JNI_OnLoad 可能晚 ~2s，
    # 默认 1.5s 注入会落在初始化未完成窗口（变体 D 首跑三格 prepare 后停摆）。
    # 默认值保持历史行为不变，仅新实验显式加长。
    ap.add_argument("--warmup", type=float, default=1.5,
                    help="am start 后到注入前的等待秒数")
    ap.add_argument("--sample", default="f3_m4a_complete.m4a")
    # 12 §61.63：清缓存对照实验用独立输出，避免覆盖 §61.62 的历史证据。
    ap.add_argument("--out", default="", help="证据 JSON 输出路径（缺省=§61.62 原路径）")
    args = ap.parse_args()
    out_path = ROOT / args.out if args.out else OUT

    src = FIXTURES / args.sample
    if not (ADB.is_file() and src.is_file()):
        print("[FAIL] adb 或样本缺失：%s / %s" % (ADB, src))
        return 1
    state = adb(args.serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / ("vfile-commit-%s.txt" % stamp)

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

    reverse_before = adb(args.serial, "reverse", "--list").stdout.decode(
        "utf-8", "replace").strip()
    set_network_gate(args.serial, True)
    adb(args.serial, "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT)

    # 先清缓冲，再启动流式 logcat（完整时间序列，不做重复 dump）。
    adb(args.serial, "logcat", "-c")
    log_file = log_path.open("wb")
    proc = subprocess.Popen(
        [str(ADB), "-s", args.serial, "logcat", "-v", "time"],
        stdout=log_file, stderr=subprocess.DEVNULL)
    started = time.time()
    verdict = "INCOMPLETE"
    try:
        adb(args.serial, "shell", "am", "force-stop", PKG)
        adb(args.serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
        time.sleep(args.warmup)
        url = "http://127.0.0.1:%d/%s" % (PORT, args.sample)
        inj = adb(args.serial, "shell", "am", "startservice",
                  "-a", ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                  "--es", EXTRA_URL, url)
        print("[INFO] 注入 %s → 观测 %.0fs" % (url, args.wait), flush=True)
        deadline = started + args.wait
        while time.time() < deadline:
            time.sleep(min(10.0, max(0.0, deadline - time.time())))
            print("  ... %.0fs / %.0fs" % (time.time() - started, args.wait),
                  flush=True)
        verdict = "OBSERVED"
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=10)
        except subprocess.SubprocessError:
            proc.kill()
        log_file.close()
        adb(args.serial, "reverse", "--remove", "tcp:%d" % PORT)
        set_network_gate(args.serial, False)
        httpd.shutdown()

    text = log_path.read_text(encoding="utf-8", errors="replace")
    wait_details = [
        {"ts": m[0], "cur_offset": int(m[1]), "cur_end": int(m[2]),
         "expect_offset": int(m[3]), "end": int(m[4]), "commit": int(m[5])}
        for m in RE_WAIT_DETAIL.findall(text)]
    waits_force = [
        {"ts": m[0], "offset": int(m[1]), "length": int(m[2]),
         "signal_size": int(m[3]), "force": m[4]}
        for m in RE_WAIT_FORCE.findall(text)]
    reads = [
        {"ts": m[0], "offset": int(m[1]), "readed_len": int(m[2]),
         "result": int(m[3])}
        for m in RE_READ.findall(text)]
    commit_values = [w["commit"] for w in wait_details]
    jump_fails = [r for r in reads if r["offset"] == JUMP_OFFSET
                  and r["readed_len"] == 0 and r["result"] == -2]
    reads_above_baseline = [r for r in reads
                            if r["readed_len"] > 0 and r["offset"] > BASELINE_COMMIT]
    codecs = RE_CODEC.findall(text)
    writes = RE_WRITE.findall(text)
    statuses = RE_STATUS.findall(text)
    starts = [{"id": int(a), "offset": int(b), "length": int(c)}
              for a, b, c in RE_START.findall(text)]
    on_ends = RE_ONEND.findall(text)

    commit_grew = len(set(commit_values)) > 1
    commit_reached_jump = any(c >= JUMP_OFFSET for c in commit_values)
    if codecs:
        mechanism = "UNEXPECTED_CODEC_OPENED（需复核样本/环境，不据此收口 F4）"
    elif commit_reached_jump:
        mechanism = "A：commit 追上跳跃点（落盘进度/速度问题）"
    elif commit_grew:
        mechanism = "A 倾向：commit 随等待增长但未追上 605007（落盘进度/速度不足）"
    elif commit_values and not commit_grew:
        mechanism = ("B：commit 在 %.0fs 内恒定不变、force 重试仍拿不到 605007 数据"
                     " → 指向 commit / Range 记账语义不允许回填跳跃区间，"
                     "而非单纯落盘速度不足" % args.wait)
    else:
        mechanism = "INCONCLUSIVE（未捕获 WaitRequest commit 明细，需复核日志）"

    result = {
        "method": "12 §61.62 / F4 vfile commit 时序真机纯观测（零代码改动、不覆盖既有证据）",
        "generated_by": "tools/l3_f4_vfile_commit_probe.py",
        "serial": args.serial,
        "sample": args.sample,
        "sample_bytes": src.stat().st_size,
        "wait_seconds": args.wait,
        "elapsed_s": round(time.time() - started, 1),
        "log_file": str(log_path.relative_to(ROOT)).replace("\\", "/"),
        "log_lines": len(text.splitlines()),
        "verdict": verdict,
        "evidence": {
            "wait_request_commit_details": wait_details,
            "commit_values": commit_values,
            "commit_distinct": sorted(set(commit_values)),
            "commit_grew": commit_grew,
            "commit_max": max(commit_values) if commit_values else None,
            "commit_reached_jump_605007": commit_reached_jump,
            "wait_request_force_lines": waits_force,
            "read_lines_total": len(reads),
            "jump_read_failures": jump_fails,
            "jump_read_failure_count": len(jump_fails),
            "successful_reads_above_baseline_commit": reads_above_baseline,
            "starts": starts,
            "on_end_ids": on_ends,
            "open_codec": codecs,
            "write_start_play": writes,
            "play_status": statuses,
            "has_103": "-103" in text,
        },
        "mechanism_verdict": mechanism,
        "honest_notes": [
            "本脚本只做观测，不实施也不验证任何修复；F4 仍 BLOCKED。",
            "P0 引擎舱零改动：未改 UrlDataRequest.kt / 未重编译 / 未重装 APK。",
            "临时 wifiOnly 前置、adb reverse、PC http.server、logcat 子进程均已在 finally 恢复。",
            "样本是「自洽 fMP4」而非完整真实非 fMP4 的 .m4a；LOCAL PASS ≠ m4a 已支持。",
            "自动进曲 3 首 / seek / ENDED 仍未测；A3 仍 G09 PARTIAL、舱不冻结。",
            "机制判读基于 commit 是否随等待增长；具体 native 函数与字段仍需反汇编定位。",
        ],
        "restore": {
            "reverse_before": reverse_before,
            "reverse_removed": True,
            "wifi_gate_file_removed": True,
            "http_server_stopped": True,
            "logcat_process_stopped": True,
            "device_settings_unchanged": True,
        },
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print("[INFO] 输出 %s" % out_path.relative_to(ROOT))
    print("[INFO] 日志 %s（%d 行）" % (result["log_file"], result["log_lines"]))
    print("[INFO] commit 序列：%s / 跳跃读失败 %d 次 / codec=%s"
          % (commit_values, len(jump_fails), codecs))
    print("[INFO] 机理判读：%s" % mechanism)
    return 0 if verdict == "OBSERVED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
