#!/usr/bin/env python3
"""G08（连续后台播放 2h）可复跑采样器 · `12 §61.30`。

判据来源：`03-06 §6.2 G08`「连续后台播放 2h 不被回收（PRD §10 验收 #5）」与
`03-12 §9` 性能基线「后台播放 2h 内存 ≤180MB / crash-free ≥99.5% / 耗电 ≤8%/h」。

**诚实边界**：Android logcat 是滚动缓冲区，单次 `-d` 只覆盖最近若干分钟，
因此本工具报告的是「本次缓冲区窗口」而非全程累计。用 `--append` 把每次采样
追加到 JSONL，再用 `--summary` 汇总，才能覆盖 2h 全程；中途若设备重启 /
缓冲区被冲掉，汇总会显示窗口缺口（gap），不得据此宣称 2h PASS。

用法：
    python analysis/g08-longrun-sample.py --serial 192.168.1.11:37763 \
        --since 13:50:49 --append analysis/g08-longrun-samples.jsonl
    python analysis/g08-longrun-sample.py --summary analysis/g08-longrun-samples.jsonl

退出码：0 = 本次窗口正常（进程存活且无阻断级异常）；1 = 进程已死 / 出现 -103 /
FATAL EXCEPTION；2 = adb 不可用。仅用于采样，**不修改设备现场**。
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

PACKAGE = "com.example.cloudmusic"

# 每首播完一次完成通知 → 自动进曲一次过渡（G05 实证：msg=2 → ENDED → next()）。
# ★ 口径修正（13:57 首次采样发现）：同一个 msg=2 会打两行
# （NEPlayer: PlayerNotifyEvent 与 NCMPlayerEngine: onEngineEvent），必须只数前者，
# 否则过渡数虚胖一倍；与 Play status 4 次数交叉校验。
PATTERNS = {
    "transitions": re.compile(r"PlayerNotifyEvent msg=2 arg1=0"),
    "prepared": re.compile(r"prepare trackId="),
    "play_started": re.compile(r"Play status 4"),
    "probe_error_103": re.compile(r"-103"),
    "engine_msg100": re.compile(r"msg=100 arg1="),
    "fatal": re.compile(r"FATAL EXCEPTION"),
    "timeout": re.compile(r"ETIMEDOUT|SocketTimeoutException"),
}
TIME_RE = re.compile(r"^(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.\d{3}")


def adb(serial: str, *args: str) -> str:
    # logcat 可能夹带厂商二进制日志（实测 0xc0 等非 UTF-8 字节），必须按字节读取后
    # 容错解码，否则 subprocess(text=True) 会抛 UnicodeDecodeError 并让 stdout 变 None。
    result = subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True, timeout=60,
    )
    return result.stdout.decode("utf-8", errors="replace") if result.stdout else ""


def shell(serial: str, command: str) -> str:
    return adb(serial, "shell", command)


def sample(serial: str, since: str) -> dict:
    # ★ 口径修正：必须只抓本 App 进程的日志。全量 logcat 会混入其它进程事件
    # （实测误计入 Google Play 服务连 play.googleapis.com 的 ETIMEDOUT，uid 10225）。
    pid = shell(serial, f"pidof {PACKAGE}").strip()
    logcat_args = ["logcat", "-d", "-v", "time"]
    if pid:
        logcat_args += ["--pid", pid]
    lines = adb(serial, *logcat_args).splitlines()
    window = [ln for ln in lines if since is None or (m := TIME_RE.match(ln)) and m.group(2) >= since]

    counts = {name: sum(bool(p.search(ln)) for ln in window) for name, p in PATTERNS.items()}
    times = [m.group(2) for ln in window if (m := TIME_RE.match(ln))]
    first, last = (times[0], times[-1]) if times else (None, None)

    meminfo = shell(serial, f"dumpsys meminfo {PACKAGE}")
    pss_match = re.search(r"TOTAL PSS:\s+(\d+)", meminfo)
    pss_kb = int(pss_match.group(1)) if pss_match else None
    power = shell(serial, "dumpsys power")
    dozing = "mWakefulness=Dozing" in power or "mWakefulness=Asleep" in power
    # G08 判据是「后台播放」：用窗口焦点判断 App 是否已退到后台（FGS 不依赖前台）。
    focus_lines = [
        ln for ln in shell(serial, "dumpsys window").splitlines()
        if "mCurrentFocus=" in ln or "mFocusedApp=" in ln
    ]
    foreground = any(PACKAGE in ln for ln in focus_lines)

    blocking = counts["probe_error_103"] + counts["fatal"] + counts["engine_msg100"]
    return {
        "sampled_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "since": since,
        "window_first": first,
        "window_last": last,
        "window_lines": len(window),
        "pid": pid or None,
        "pss_kb": pss_kb,
        "screen_off": dozing,
        "background": not foreground,
        "counts": counts,
        "blocking_events": blocking,
        "alive": bool(pid) and last is not None,
        "clean": blocking == 0,
    }


def render(s: dict) -> str:
    c = s["counts"]
    return "\n".join([
        f"## G08 采样 · {s['sampled_at']}",
        "",
        "| 项 | 值 |",
        "|---|---|",
        f"| 窗口 | {s['window_first']} → {s['window_last']}（{s['window_lines']} 行 logcat）|",
        f"| 进程 pid | {s['pid'] or '**已退出**'}|",
        f"| 灭屏 / Dozing | {'是' if s['screen_off'] else '否'}|",
        f"| App 在后台 | {'是' if s['background'] else '否（仍在前台）'}|",
        f"| TOTAL PSS | {s['pss_kb']} KB（{s['pss_kb'] / 1024:.1f} MiB）|" if s["pss_kb"] else "| TOTAL PSS | 读取失败 |",
        f"| 播放完成过渡 msg=2（仅 NEPlayer 行）| {c['transitions']} |",
        f"| prepare / Play status 4 | {c['prepared']} / {c['play_started']}（应与过渡数同量级）|",
        f"| -103 / msg=100 / FATAL | {c['probe_error_103']} / {c['engine_msg100']} / {c['fatal']} |",
        f"| ETIMEDOUT | {c['timeout']} |",
        f"| 判定 | {'ALIVE + CLEAN' if s['alive'] and s['clean'] else '**异常，需排查**'} |",
        "",
        "> 注：仅为本轮 logcat 缓冲区窗口，不代表 2h 全程；用 --append / --summary 累积。",
        "",
    ])


def append_record(path: Path, s: dict) -> None:
    # Windows 下默认 newline=None 会把 \n 写成 \r\n；项目统一 LF。
    with path.open("a", encoding="utf-8", newline="\n") as fp:
        fp.write(json.dumps(s, ensure_ascii=False) + "\n")


def render_summary(path: Path) -> str:
    if not path.is_file():
        return "尚无采样记录（先跑一次 --append）"
    records = [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    if not records:
        return "无采样记录"
    # ★ 口径修正：多次采样若共用同一窗口起点（logcat 缓冲区未滚动），窗口是**重叠**的，
    # 过渡数/异常数不能相加 —— 取最后一次的窗口累计值即可；只有窗口起点不同（发生过
    # 滚动）才需要累加，并显式标注 gap，避免把一次播放数成多次。
    same_origin = len({r["window_first"] for r in records}) == 1
    if same_origin:
        total_transitions = records[-1]["counts"]["transitions"]
        total_blocking = max(r["blocking_events"] for r in records)
        count_note = "窗口起点相同，取最后一次累计值，不重复累加"
    else:
        total_transitions = sum(r["counts"]["transitions"] for r in records)
        total_blocking = sum(r["blocking_events"] for r in records)
        count_note = "**窗口起点不同、存在 logcat 滚动 gap，仅粗略累加**"
    max_pss = max((r["pss_kb"] or 0) for r in records)
    windows = [(r["window_first"], r["window_last"]) for r in records]
    pids = {r["pid"] for r in records}
    pid_note = "pid 一致（未被回收）" if len(pids) == 1 and None not in pids else f"**pid 变化或进程退出：{pids}**"
    return "\n".join([
        f"采样次数 {len(records)}；完成过渡 {total_transitions}（{count_note}）；"
        f"阻断级异常 {total_blocking}；最大 PSS {max_pss} KB（{max_pss / 1024:.1f} MiB）",
        f"覆盖窗口：{windows[0][0]} → {windows[-1][1]}（各窗口间可能有 gap，需人工核对）",
        f"进程：{pid_note}",
        f"目标：连续 2h 不被回收 / PSS ≤ 180 MiB / crash-free ≥ 99.5%",
        "**结论须在起止跨度 ≥120 分钟且窗口连续、异常为 0、进程 pid 未变时才能判 PASS**",
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description="G08 2h 后台长稳采样")
    parser.add_argument("--serial", default="192.168.1.11:37763")
    parser.add_argument("--since", default=None, help="只统计 HH:MM:SS 之后的日志（本次循环起点）")
    parser.add_argument("--append", type=Path, help="把本次采样追加到 JSONL")
    parser.add_argument("--summary", type=Path, help="只汇总既有 JSONL，不连设备")
    parser.add_argument("--out", type=Path, help="同时把 Markdown 报告写到文件")
    args = parser.parse_args()

    if args.summary:
        print(render_summary(args.summary))
        return 0

    try:
        s = sample(args.serial, args.since)
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as exc:
        print(f"adb 不可用或超时：{exc}", file=sys.stderr)
        return 2

    report = render(s)
    print(report)
    if args.out:
        args.out.write_text(report, encoding="utf-8")
    if args.append:
        append_record(args.append, s)
    return 0 if s["alive"] and s["clean"] else 1


if __name__ == "__main__":
    sys.exit(main())
