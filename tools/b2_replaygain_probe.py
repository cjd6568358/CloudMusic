#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.107 / ReplayGain B2 接线的**真机两格对照探针**。

带标签格：`f3_mp3_rg.mp3`（ID3v2.3 TXXX 含 REPLAYGAIN_*，由
`tools/b2_replaygain_fixture.py` 生成）；对照格：原始 `f3_mp3.mp3`（无增益标签）。
两者都用与 F3 矩阵相同的 LOCAL 通道（ACTION_PLAY + EXTRA_LOCAL_PATH →
app 私有 files/music/，零权限），不跑矩阵脚本、不覆盖任何历史证据。

判读 `CM-Media3` 的 `replaygain tags=... gain=... volume=...` 一手日志：
- 带标签格应出现 `gain=-6.54 dB`、`volume≈0.471`（10^(-6.54/20)）；
- 对照格应出现 `gain=none`、`volume=1.000`；
- 两格都需 `state=READY` + `state=PLAYING`。

产物：analysis/b2-replaygain-probe-2026-11-13.{json,report.md}
      analysis/l3-f3-logs/replaygain-{tagged,plain}-2026-11-13.txt
`--check` 只读复跑比对冻结 JSON。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

import l3_f3_matrix_run as m

ROOT = Path(__file__).resolve().parent.parent
FIX = ROOT / "analysis" / "l3-f3-fixtures"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
TAGGED = FIX / "f3_mp3_rg.mp3"
PLAIN = FIX / "f3_mp3.mp3"
OUT_JSON = ROOT / "analysis" / "b2-replaygain-probe-2026-11-13.json"
OUT_REPORT = ROOT / "analysis" / "b2-replaygain-probe-2026-11-13.report.md"
EXPECTED_VOLUME = 10 ** (-6.54 / 20)


def run_cell(serial: str, src: Path, label: str, wait_s: float) -> dict:
    m.adb(serial, "shell", "am", "force-stop", m.PKG)
    m.adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % m.PKG)
    time.sleep(1.4)
    m.adb(serial, "logcat", "-c")
    pushed = m.push_local(serial, src, src.name)
    r = m.adb(serial, "shell", "am", "startservice", "-a", m.ACTION_PLAY,
              "-c", "android.intent.category.DEFAULT",
              "--es", m.EXTRA_LOCAL, m.PRIVATE_DIR + "/" + src.name)
    time.sleep(wait_s)
    log = m.adb(serial, "logcat", "-d", "-v", "time").stdout.decode("utf-8", "replace")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    (LOG_DIR / ("replaygain-%s-2026-11-13.txt" % label)).write_text(
        log, encoding="utf-8")
    lines = [l for l in log.splitlines() if "CM-Media3" in l]
    rg = [l for l in lines if "replaygain" in l]
    ready = [l for l in lines if "state=READY" in l]
    playing = [l for l in lines if "state=PLAYING" in l]
    errors = [l for l in lines if "engine_error=" in l or "error=" in l]
    vol = None
    gain = None
    if rg:
        mg = re.search(r"volume=([0-9.]+)", rg[-1])
        if mg:
            vol = float(mg.group(1))
        gain = "none" if "gain=none" in rg[-1] else "present"
    return {
        "label": label,
        "source": src.name,
        "sample_bytes": src.stat().st_size,
        "pushed_ok": pushed,
        "startservice": r.stdout.decode("utf-8", "replace").strip()[:160],
        "replaygain_lines": rg[-3:],
        "replaygain_seen": bool(rg),
        "gain": gain,
        "volume": vol,
        "ready_seen": bool(ready),
        "playing_seen": bool(playing),
        "ready_line": ready[-1] if ready else None,
        "error_lines": errors[:3],
        "log_file": "analysis/l3-f3-logs/replaygain-%s-2026-11-13.txt" % label,
    }


def collect(serial: str, wait_s: float) -> dict:
    tagged = run_cell(serial, TAGGED, "tagged", wait_s)
    plain = run_cell(serial, PLAIN, "plain", wait_s)
    checks = []

    def add(name: str, ok: bool, evidence: str) -> None:
        checks.append({"name": name, "pass": bool(ok), "evidence": evidence})

    add("两格均 READY(duration>0) + PLAYING",
        tagged["ready_seen"] and tagged["playing_seen"] and
        plain["ready_seen"] and plain["playing_seen"],
        "tagged=%s/%s plain=%s/%s" % (tagged["ready_seen"], tagged["playing_seen"],
                                      plain["ready_seen"], plain["playing_seen"]))
    add("带标签格出现 replaygain 日志且增益非 none",
        tagged["replaygain_seen"] and tagged["gain"] == "present",
        str(tagged["replaygain_lines"][-1:])[:200])
    add("带标签格音量 ≈ %.4f（10^(-6.54/20)）" % EXPECTED_VOLUME,
        tagged["volume"] is not None and
        abs(tagged["volume"] - EXPECTED_VOLUME) < 0.01,
        "volume=%s" % tagged["volume"])
    add("无标签对照格保持 volume=1.000（不误施增益）",
        plain["volume"] is None or abs(plain["volume"] - 1.0) < 0.01,
        "gain=%s volume=%s" % (plain["gain"], plain["volume"]))
    add("两格均无播放错误回调",
        not tagged["error_lines"] and not plain["error_lines"],
        "tagged=%d plain=%d" % (len(tagged["error_lines"]), len(plain["error_lines"])))
    passed = sum(1 for c in checks if c["pass"])
    return {
        "method": "12 §61.107 / ReplayGain B2 接线真机两格对照",
        "generated_by": "tools/b2_replaygain_probe.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_run": True,
        "serial": serial,
        "wait_s": wait_s,
        "expected_track_volume": round(EXPECTED_VOLUME, 4),
        "cells": [tagged, plain],
        "checks": checks,
        "checks_passed": passed,
        "checks_total": len(checks),
        "boundary": ("只验证含 ID3 TXXX REPLAYGAIN_* 标签的 MP3 LOCAL 通道；"
                     "不验证其它容器 / REMOTE / 扫描式 ReplayGain，不宣称全格式生效；"
                     "未改 Manifest / CMake / 生产构建配置，未做破坏性注入。"),
    }


def report(d: dict) -> str:
    lines = [
        "# §61.107 ReplayGain 真机两格对照（2026-11-13）",
        "",
        "- device_run = true，端点 `%s`，每格等待 %ss，判据 **%d/%d PASS**。" % (
            d["serial"], d["wait_s"], d["checks_passed"], d["checks_total"]),
        "- 期望 TRACK 音量 = `10^(-6.54/20)` ≈ `%.4f`。" % d["expected_track_volume"],
        "",
        "| 格 | 样本 | READY | PLAYING | replaygain 日志 | gain | volume |",
        "|---|---|---|---|---|---|---|",
    ]
    for c in d["cells"]:
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            c["label"], c["source"], c["ready_seen"], c["playing_seen"],
            c["replaygain_seen"], c["gain"], c["volume"]))
    lines += ["", "## 一手日志", ""]
    for c in d["cells"]:
        lines.append("- **%s**：`%s`" % (c["label"],
                                        c["replaygain_lines"][-1] if c["replaygain_lines"] else "<无>"))
    lines += ["", "## 判据明细", "",
              "| # | 判据 | 结果 | 证据 |", "|---|---|---|---|"]
    for i, c in enumerate(d["checks"], 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["pass"] else "FAIL", c["evidence"]))
    lines += ["", "## 诚实边界", "", d["boundary"], ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.107 ReplayGain 真机探针")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--wait", type=float, default=8.0)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        if not OUT_JSON.is_file():
            print("[FAIL] 冻结证据缺失")
            return 2
        frozen = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        ok = (frozen.get("device_run") is True
              and frozen.get("checks_passed") == frozen.get("checks_total")
              and all(c.get("pass") for c in frozen.get("checks", []))
              and abs(frozen.get("expected_track_volume", 0) - EXPECTED_VOLUME) < 1e-4
              and len(frozen.get("cells", [])) == 2)
        print("[%s] §61.107 ReplayGain 探针 --check：冻结证据完整（%d/%d）" % (
            "PASS" if ok else "FAIL", frozen.get("checks_passed", 0),
            frozen.get("checks_total", 0)))
        return 0 if ok else 1
    if not args.execute:
        print("[DRY-RUN] 加 --execute 真跑（设备在线后）。")
        return 0
    if not (TAGGED.is_file() and PLAIN.is_file()):
        print("[FAIL] 缺夹具，先跑 tools/b2_replaygain_fixture.py")
        return 1
    state = m.adb(args.serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1
    d = collect(args.serial, args.wait)
    OUT_JSON.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    OUT_REPORT.write_text(report(d), encoding="utf-8")
    print("[INFO] ReplayGain 探针 %d/%d 判据 PASS → %s / %s" % (
        d["checks_passed"], d["checks_total"], OUT_JSON.name, OUT_REPORT.name))
    for c in d["cells"]:
        print("  %-6s gain=%s volume=%s ready=%s playing=%s" % (
            c["label"], c["gain"], c["volume"], c["ready_seen"], c["playing_seen"]))
    return 0 if d["checks_passed"] == d["checks_total"] else 1


if __name__ == "__main__":
    sys.exit(main())
