#!/usr/bin/env python3
"""G08（连续后台播放）可复跑采样器 · `12 §61.30` / `§61.31`。

判据来源：`03-06 §6.2 G08`「连续后台播放 20 分钟不被回收（PRD §10 验收 #5）」
与 `03-12 §9` 性能基线「PSS ≤180MB / crash-free ≥99.5% / 耗电 ≤8%/h」。
**2026-11-08 用户决策**：长稳验收时长由 2h 缩短为 20 分钟，理由是
「实际使用有问题再修改」；本工具 `TARGET_MINUTES = 20`。历史批次
（`12 §61.26~§61.30`）中的「2h」是当时口径，不再作为现行判据。

**诚实边界**：Android logcat 是滚动缓冲区，单次 `-d` 只覆盖最近若干分钟，
因此本工具报告的是「本次缓冲区窗口」而非全程累计。用 `--append` 把每次采样
追加到 JSONL，再用 `--summary` 汇总；中途若设备重启 / 缓冲区被冲掉，汇总会
显示窗口缺口（gap），不得据此宣称全程 PASS。进程退出原因以
`dumpsys activity exit-info` 的 `ApplicationExitInfo` 为准。

用法：
    python analysis/g08-longrun-sample.py --serial 192.168.1.11:37763 \
        --since 13:50:49 --append analysis/g08-longrun-samples.jsonl
    python analysis/g08-longrun-sample.py --summary analysis/g08-longrun-samples.jsonl \
        --since 13:50:49 --until 14:12:38
    python analysis/g08-longrun-sample.py --connect --adb /path/to/adb.exe

adb 定位（§61.31 修复）：优先 `--adb`，其次 PATH / 仓库 `tools/bin/adb.exe` /
`ANDROID_HOME` / 常见 SDK 目录。本机 adb 不在 PATH，旧版硬编码 `["adb", ...]`
会直接抛 `[WinError 2]`，使文档声称「可复跑」的采样实际跑不起来。
`--connect` 只做一次 `adb connect <serial>`（无线端点灭屏常掉线），不改动设备数据。

进程消失时读 ApplicationExitInfo，区分「用户清理 / 安装替换」
（reason=10 USER REQUESTED）与「崩溃 / 系统回收」（reason=4 APP CRASH /
reason=3 LOW MEMORY）：前者样本链中断但**不算产品回归**，后者才是缺陷。

退出码：0 = 本次窗口正常（进程存活且无阻断级异常）；1 = 进程已死 / 出现 -103 /
FATAL EXCEPTION；2 = adb 不可用。仅用于采样，**不修改设备现场**。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

PACKAGE = "com.example.cloudmusic"
REPO_ROOT = Path(__file__).resolve().parent.parent
ADB_BIN: str | None = None
TARGET_MINUTES = 20

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


def to_seconds(value: str | None) -> int | None:
    """HH:MM:SS → 当日秒数；跨日按 +24h 处理。"""
    if not value:
        return None
    try:
        h, m, s = [int(part) for part in value.split(":")]
        return h * 3600 + m * 60 + s
    except (ValueError, AttributeError):
        return None


def resolve_adb(explicit: str | None) -> str:
    """按优先级定位 adb 可执行文件，避免依赖 PATH（§61.31）。"""
    if explicit:
        candidates = [Path(explicit)]
    else:
        candidates = []
        from_path = shutil.which("adb")
        if from_path:
            candidates.append(Path(from_path))
        candidates += [
            REPO_ROOT / "tools" / "bin" / "adb.exe",
            REPO_ROOT / "tools" / "bin" / "adb",
        ]
        for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            root = os.environ.get(env_name)
            if root:
                base = Path(root) / "platform-tools"
                candidates += [base / "adb.exe", base / "adb"]
        candidates.append(
            Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        )
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise FileNotFoundError("未找到 adb：请配置 PATH / ANDROID_HOME，或用 --adb 指定路径")


def adb(serial: str, *args: str) -> str:
    # logcat 可能夹带厂商二进制日志（实测 0xc0 等非 UTF-8 字节），必须按字节读取后
    # 容错解码，否则 subprocess(text=True) 会抛 UnicodeDecodeError 并让 stdout 变 None。
    result = subprocess.run(
        [ADB_BIN or "adb", "-s", serial, *args], capture_output=True, timeout=60,
    )
    return result.stdout.decode("utf-8", errors="replace") if result.stdout else ""


def shell(serial: str, command: str) -> str:
    return adb(serial, "shell", command)


def last_exit(serial: str) -> dict | None:
    """读 ApplicationExitInfo 最新一条，判定进程为何消失（Android 11+）。"""
    raw = shell(serial, f"dumpsys activity exit-info {PACKAGE}")
    entries: list[dict] = []
    for line in raw.splitlines():
        if "ApplicationExitInfo #" in line:
            entries.append({})
            continue
        if not entries:
            continue
        current = entries[-1]
        text = line.strip()
        m = re.search(r"timestamp=([\d-]+\s[\d:.]+)\s+pid=(\d+)", text)
        if m:
            current["timestamp"], current["pid"] = m.group(1), m.group(2)
        m = re.search(r"reason=(\d+)\s+\(([^)]*)\)", text)
        if m:
            current["reason"] = f"{m.group(1)} {m.group(2)}"
        m = re.search(r"description=(.*?)\s+state=", text)
        if m:
            current["description"] = m.group(1).strip()
    return next((e for e in entries if e.get("timestamp")), None)


def sample(serial: str, since: str) -> dict:
    # ★ 口径修正：必须只抓本 App 进程的日志。全量 logcat 会混入其它进程事件
    # （实测误计入 Google Play 服务连 play.googleapis.com 的 ETIMEDOUT，uid 10225）。
    # §61.31：进程已不在时**不得**退化成全量 logcat，否则窗口统计口径失真。
    pid = shell(serial, f"pidof {PACKAGE}").strip()
    counts = {name: 0 for name in PATTERNS}
    first = last = None
    window: list[str] = []
    if pid:
        lines = adb(serial, "logcat", "-d", "-v", "time", "--pid", pid).splitlines()
        window = [ln for ln in lines if since is None or (m := TIME_RE.match(ln)) and m.group(2) >= since]
        counts = {name: sum(bool(p.search(ln)) for ln in window) for name, p in PATTERNS.items()}
        times = [m.group(2) for ln in window if (m := TIME_RE.match(ln))]
        first, last = (times[0], times[-1]) if times else (None, None)

    meminfo = shell(serial, f"dumpsys meminfo {PACKAGE}") if pid else ""
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
        # 进程消失时给出决定性原因，避免把「用户上划清理」误判成系统回收或 adb 故障。
        "last_exit": last_exit(serial) if not pid else None,
    }


def render(s: dict) -> str:
    c = s["counts"]
    exit_record = s.get("last_exit")
    exit_line = (
        f"| 最近一次退出 | {exit_record.get('timestamp', '?')} / pid {exit_record.get('pid', '?')} / "
        f"reason {exit_record.get('reason', '?')} / {exit_record.get('description', '?')} |"
        if exit_record else "| 最近一次退出 | —（进程存活，未查询）|"
    )
    return "\n".join([
        f"## G08 采样 · {s['sampled_at']}",
        "",
        "| 项 | 值 |",
        "|---|---|",
        f"| 窗口 | {s['window_first']} → {s['window_last']}（{s['window_lines']} 行 logcat）|",
        f"| 进程 pid | {s['pid'] or '**已退出**'}|",
        exit_line,
        f"| 灭屏 / Dozing | {'是' if s['screen_off'] else '否'}|",
        f"| App 在后台 | {'是' if s['background'] else '否（仍在前台）'}|",
        f"| TOTAL PSS | {s['pss_kb']} KB（{s['pss_kb'] / 1024:.1f} MiB）|" if s["pss_kb"] else "| TOTAL PSS | 读取失败 |",
        f"| 播放完成过渡 msg=2（仅 NEPlayer 行）| {c['transitions']} |",
        f"| prepare / Play status 4 | {c['prepared']} / {c['play_started']}（应与过渡数同量级）|",
        f"| -103 / msg=100 / FATAL | {c['probe_error_103']} / {c['engine_msg100']} / {c['fatal']} |",
        f"| ETIMEDOUT | {c['timeout']} |",
        f"| 判定 | {'ALIVE + CLEAN' if s['alive'] and s['clean'] else '**异常，需排查**'} |",
        "",
        f"> 注：仅为本轮 logcat 缓冲区窗口，不代表 {TARGET_MINUTES} 分钟全程；用 --append / --summary 累积。",
        "> 退出原因是 USER REQUESTED / FORCE STOP 时属人为清理，不是系统回收，也不算产品回归。",
        "",
    ])


def append_record(path: Path, s: dict) -> None:
    # Windows 下默认 newline=None 会把 \n 写成 \r\n；项目统一 LF。
    with path.open("a", encoding="utf-8", newline="\n") as fp:
        fp.write(json.dumps(s, ensure_ascii=False) + "\n")


def render_summary(path: Path, since: str | None, until: str | None) -> str:
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
        gap_note = "无（同起点窗口）"
    else:
        total_transitions = sum(r["counts"]["transitions"] for r in records)
        total_blocking = sum(r["blocking_events"] for r in records)
        count_note = "**窗口起点不同、存在 logcat 滚动 gap，仅粗略累加**"
        gap_note = "**有 gap，须用 events / exit-info 补证**"
    max_pss = max((r["pss_kb"] or 0) for r in records)
    windows = [(r["window_first"], r["window_last"]) for r in records]
    pids = {r["pid"] for r in records}
    pid_note = "pid 一致（未被回收）" if len(pids) == 1 and None not in pids else f"**pid 变化或进程退出：{pids}**"

    exits = [r["last_exit"] for r in records if r.get("last_exit")]
    exit_note = "未记录到进程退出事件"
    if exits:
        e = exits[-1]
        exit_note = (f"最近退出 {e.get('timestamp', '?')} / reason {e.get('reason', '?')} / "
                     f"{e.get('description', '?')}（USER REQUESTED 属人为清理，非系统回收）")

    start = since or records[0].get("since")
    end = until or (exits[-1].get("timestamp", " ").split()[1][:8] if exits else windows[-1][1])
    start_s, end_s = to_seconds(start), to_seconds(end)
    if start_s is not None and end_s is not None:
        span = end_s - start_s
        if span < 0:
            span += 24 * 3600
        span_text = f"{span // 60} 分 {span % 60} 秒（起点 {start} → 终点 {end}）"
        duration_ok = span >= TARGET_MINUTES * 60
    else:
        span_text = "无法计算（缺 --since / --until，且窗口无时间）"
        duration_ok = False

    return "\n".join([
        f"采样次数 {len(records)}；完成过渡 {total_transitions}（{count_note}）；"
        f"阻断级异常 {total_blocking}；最大 PSS {max_pss} KB（{max_pss / 1024:.1f} MiB）",
        f"覆盖窗口：{windows[0][0]} → {windows[-1][1]}（{gap_note}）",
        f"进程：{pid_note}；{exit_note}",
        f"目标：连续 {TARGET_MINUTES} 分钟不被回收 / PSS ≤ 180 MiB / crash-free ≥ 99.5%",
        f"跨度：{span_text}；达到 {TARGET_MINUTES} 分钟：{'是' if duration_ok else '否'}",
        f"**判 PASS 须同时满足：跨度 ≥{TARGET_MINUTES} 分钟、阻断级异常 0、pid 未变、"
        "窗口 gap 已由系统事件证据补足；本工具只汇总，不代替证据判定**",
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description=f"G08 {TARGET_MINUTES} 分钟后台长稳采样")
    parser.add_argument("--serial", default="192.168.1.11:37763")
    parser.add_argument("--since", default=None, help="只统计 HH:MM:SS 之后的日志（本次循环起点）")
    parser.add_argument("--until", default=None, help="进程终止时刻 HH:MM:SS（来自 exit-info），供 --summary 判跨度")
    parser.add_argument("--adb", default=None, help="显式指定 adb 可执行文件")
    parser.add_argument("--connect", action="store_true", help="先执行 adb connect <serial>")
    parser.add_argument("--append", type=Path, help="把本次采样追加到 JSONL")
    parser.add_argument("--summary", type=Path, help="只汇总既有 JSONL，不连设备")
    parser.add_argument("--out", type=Path, help="同时把 Markdown 报告写到文件")
    args = parser.parse_args()

    if args.summary:
        print(render_summary(args.summary, args.since, args.until))
        return 0

    global ADB_BIN
    try:
        ADB_BIN = resolve_adb(args.adb)
    except FileNotFoundError as exc:
        print(f"adb 不可用：{exc}", file=sys.stderr)
        return 2

    if args.connect:
        subprocess.run([ADB_BIN, "connect", args.serial], capture_output=True, timeout=30)

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
