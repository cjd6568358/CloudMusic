#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.104 · F3 8 格真机复测裁决（读已落盘证据，纯离线）。

用户显式授权「装机」后，卸载旧 app → 装含 FFmpeg 静态库的新 APK →
只重跑 aiff/wma/ape/wv × local/remote 八格。历史 logcat 与
l3-f3-matrix.json 先备份、跑完 git restore 恢复，新结果另存独立文件。

本工具逐格读新 logcat，把「FAIL」细化到具体异常类别，输出
analysis/b2-f3-retest-verdict-2026-11-13.{json,report.md}。
不连 adb、不重跑矩阵、不改历史证据。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "analysis" / "b2-f3-retest-2026-11-13.json"
LOGS = ROOT / "analysis" / "l3-f3-logs"
HISTORY = ROOT / "analysis" / "l3-f3-matrix.json"
OUT_JSON = ROOT / "analysis" / "b2-f3-retest-verdict-2026-11-13.json"
OUT_MD = ROOT / "analysis" / "b2-f3-retest-verdict-2026-11-13.report.md"
STAMP = "2026-11-13"


def classify(text: str, verdict: str) -> tuple[str, str]:
    if verdict == "PASS":
        return "READY_AND_PLAYING", "STATE_READY + isPlaying（软/硬解渲染出声）"
    if "FfmpegDecoderException: Initialization failed" in text or \
            "ERROR_CODE_DECODER_INIT_FAILED" in text:
        return "DECODER_INIT_FAILED", (
            "MIME 映射与 renderer 接管成功（format_supported=YES），"
            "但 ffmpeg pcm_s16be decoder avcodec_open2 初始化失败")
    if "Loading finished before preparation is complete" in text:
        return "EXTRACTOR_PREPARATION_INCOMPLETE", (
            "AsfExtractor sniff 命中，但 seekMap/endTracks 未完成即 EOF，"
            "Media3 判容器 malformed")
    if "UnrecognizedInputFormatException" in text:
        return "UNRECOGNIZED_CONTAINER", "容器未被任何 Extractor 识别"
    if "state=READY" not in text:
        return "NO_READY_IN_WINDOW", (
            "无明确错误回调，但等待窗口内未见 STATE_READY（疑似拉流/缓存时序）")
    return "OTHER", "未归类，需人工看日志"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    data = json.loads(MATRIX.read_text(encoding="utf-8"))
    cells = data["cells"]
    rows = []
    for c in cells:
        f, p = c["format"], c["path"]
        fp = LOGS / ("%s-%s-retest-%s.txt" % (f, p, STAMP))
        text = fp.read_text(encoding="utf-8", errors="replace") if fp.is_file() else ""
        kind, why = classify(text, c["verdict"])
        rows.append({
            "format": f, "path": p, "verdict": c["verdict"],
            "root_cause": kind, "explain": why,
            "has_log": bool(text), "log_file": str(fp.relative_to(ROOT)),
            "ready_seen": "state=READY" in text,
            "playing_seen": "state=PLAYING" in text,
            "custom_factory_hits": text.count("CM-Factory"),
        })

    hist = json.loads(HISTORY.read_text(encoding="utf-8"))
    counts = data.get("counts", {})
    checks: list[dict] = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    add("8 格全部有一手 logcat", len(rows) == 8 and all(r["has_log"] for r in rows),
        "*-retest-%s.txt" % STAMP)
    add("复测计数 3 PASS / 5 FAIL",
        counts.get("PASS") == 3 and counts.get("FAIL") == 5, str(counts))
    add("wv local+remote 均 PASS 且有 READY/PLAYING",
        all(r["verdict"] == "PASS" and r["ready_seen"] and r["playing_seen"]
            for r in rows if r["format"] == "wv"),
        "WavpackExtractor 真机闭环")
    add("ape-local PASS（§61.94c 修正有效）",
        next(r["verdict"] for r in rows
             if r["format"] == "ape" and r["path"] == "local") == "PASS",
        "ApeExtractor 逐帧 seek 日志在场")
    add("aiff ×2 同一根因 DECODER_INIT_FAILED",
        all(r["root_cause"] == "DECODER_INIT_FAILED"
            for r in rows if r["format"] == "aiff"),
        "pcm_s16be 初始化失败，非容器解析缺陷")
    add("wma ×2 同一根因 EXTRACTOR_PREPARATION_INCOMPLETE",
        all(r["root_cause"] == "EXTRACTOR_PREPARATION_INCOMPLETE"
            for r in rows if r["format"] == "wma"),
        "AsfExtractor 未下发 seekMap/endTracks 即 EOF")
    add("ape-remote 无错误但窗口内未 READY",
        next(r["root_cause"] for r in rows
             if r["format"] == "ape" and r["path"] == "remote") ==
        "NO_READY_IN_WINDOW",
        "需更长等待或排查 CacheDataSource 时序")
    add("8 格自定义工厂均被调用",
        all(r["custom_factory_hits"] >= 2 for r in rows),
        "CM-Factory createExtractors + extractors: 25")
    add("历史 F3 矩阵零改写（git restore 生效）",
        hist.get("counts", {}).get("PASS") == 14
        and hist.get("counts", {}).get("FAIL") == 8,
        "14 PASS / 8 FAIL 当时快照不变")
    add("反伪证：不宣称 8 格已修复",
        counts.get("FAIL", 0) > 0, "5 格仍 FAIL，如实登记")

    ok = sum(1 for c in checks if c["ok"])
    verdict = "PASS" if ok == len(checks) else "FAIL"
    result = {
        "batch": "12 §61.104",
        "method": "F3 8 格真机复测裁决（读已落盘一手 logcat，纯离线）",
        "generated_by": "tools/b2_f3_retest_verdict.py",
        "device_run": True,
        "serial": data.get("serial"),
        "apk_installed": "lastUpdateTime=2026-11-13 13:49:46",
        "counts": counts,
        "cells": rows,
        "checks": checks,
        "checks_total": len(checks),
        "checks_pass": ok,
        "verdict": verdict,
        "honest_notes": [
            "历史 8 格 FAIL → 本轮 3 PASS / 5 FAIL；不得写成「容器缺口已修复」。",
            "PASS 判据为 STATE_READY(duration>0) + isPlaying，未逐格验证 seek / 完整时长 / ENDED。",
            "aiff 的 format_supported=YES 说明 MIME 映射生效，失败点在 FFmpeg decoder 初始化。",
            "ape-remote 无明确错误，不能判定为代码缺陷，也不能判定为通过。",
            "历史 logcat 与 l3-f3-matrix.json 已 git restore 恢复；新结果另存独立文件。",
        ],
    }
    lines = [
        "# F3 8 格真机复测裁决（`docs/12 §61.104`）", "",
        "- 工具：`tools/b2_f3_retest_verdict.py`（纯 stdlib / 只读）",
        "- 判定：**%s**（%d/%d 项）；`device_run=true`，serial=%s" %
        (verdict, ok, len(checks), data.get("serial")),
        "- 计数：**%s**（历史 8 FAIL → 本轮 3 PASS / 5 FAIL）" % counts, "",
        "| 格式 | 路径 | 结论 | 根因 | READY | PLAYING |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            r["format"], r["path"], r["verdict"], r["root_cause"],
            "Y" if r["ready_seen"] else "N", "Y" if r["playing_seen"] else "N"))
    lines += ["", "## 判定项", "",
              "| # | 检查项 | 结果 | 说明 |", "|---|--------|------|------|"]
    for i, c in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["ok"] else "FAIL", c["detail"]))
    lines += ["", "## 诚实边界", ""] + ["- " + n for n in result["honest_notes"]]
    lines += ["", "> AI生成", ""]
    report = "\n".join(lines)

    if not args.check:
        OUT_JSON.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        OUT_MD.write_text(report, encoding="utf-8")

    print("[%s] 12 §61.104 复测裁决 %d/%d 项；3 PASS / 5 FAIL" %
          (verdict, ok, len(checks)))
    for c in checks:
        if not c["ok"]:
            print("  [FAIL] %s — %s" % (c["name"], c["detail"]))
    if not args.check:
        print("  证据：%s / %s" % (OUT_JSON.name, OUT_MD.name))
    return 0 if verdict == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
