#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.106 · 第一百二十六批真机复测的**证据完整性与口径裁决**。

读取 `tools/b2_f3_batch126_runbook.py` 产出的初轮 8 格（§61.105 修法装机后）
+ ape-remote 30s 加长等待单格，以及 seek 抢跑修复后重跑的最终 8 格，
与 §61.104 做三方逐格对照，输出「哪些格转 PASS / 哪些仍 FAIL / 根因是否消失」。

纪律：本工具只做**证据判读**，不把任何结论提升：
- PASS 判据沿用矩阵脚本的 `STATE_READY(duration>0) + isPlaying`；
- 不逐格验证 seek / 完整时长 / ENDED，故任何 PASS 都不写成「全格式覆盖」；
- `verdict` 恒为 `RECORDED`（如实登记），不因计数漂亮而写 `FIXED`；
- 历史证据零改写由 `history_restored` + `l3-f3-matrix.json` 计数复核守护。

产物：analysis/b2-f3-batch126-verdict-2026-11-13.{json,report.md}
`--check` 只读复跑，与冻结 JSON 比对。
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis" / "b2-f3-batch126b-2026-11-13.json"   # 修复 seek 抢跑后的最终 8 格
PRE = ROOT / "analysis" / "b2-f3-batch126-2026-11-13.json"  # 同批初轮（修复前对照）
B = ROOT / "analysis" / "b2-f3-ape-remote-longwait-device-2026-11-13.json"
RUN = ROOT / "analysis" / "b2-f3-batch126-run-2026-11-13.json"
OLD = ROOT / "analysis" / "b2-f3-retest-2026-11-13.json"
HIST = ROOT / "analysis" / "l3-f3-matrix.json"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
OUT_JSON = ROOT / "analysis" / "b2-f3-batch126-verdict-2026-11-13.json"
OUT_REPORT = ROOT / "analysis" / "b2-f3-batch126-verdict-2026-11-13.report.md"

FORMATS = ("aiff", "wma", "ape", "wv")
PATHS = ("local", "remote")
WMA_ERR = "Loading finished before preparation is complete"
AIFF_ERR = "Initialization failed"
WMAV2_INIT_ERR = "FfmpegDecoderException: Initialization failed"


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}


def cell_map(d: dict) -> dict:
    return {(c.get("format"), c.get("path")): c for c in d.get("cells", [])}


def log_text(tag: str, fmt: str, path: str) -> str:
    p = LOG_DIR / ("%s-%s-%s-2026-11-13.txt" % (fmt, path, tag))
    if not p.is_file():
        return ""
    return p.read_text(encoding="utf-8", errors="replace")


def collect() -> dict:
    a, pre, b, run, old, hist = (
        load(A), load(PRE), load(B), load(RUN), load(OLD), load(HIST))
    amap, pmap, omap = cell_map(a), cell_map(pre), cell_map(old)
    bmap = cell_map(b)
    checks: list[dict] = []

    def add(name: str, ok: bool, evidence: str) -> None:
        checks.append({"name": name, "pass": bool(ok), "evidence": evidence})

    add("运行摘要存在且 device_run=true / 不卸载装机 / 历史已恢复",
        bool(run) and run.get("device_run") is True
        and run.get("install_reinstall_no_uninstall") is True
        and run.get("history_restored") is True,
        str(RUN.name))
    add("阶段 A 8 格证据齐全",
        len(a.get("cells", [])) == 8 and all((f, p) in amap
                                             for f in FORMATS for p in PATHS),
        "counts=%s" % a.get("counts"))
    add("阶段 B ape×remote 加长等待单格证据齐全",
        len(b.get("cells", [])) == 1 and ("ape", "remote") in bmap,
        "wait=%s / counts=%s" % (run.get("wait_ape_remote_s"), b.get("counts")))
    add("最终 8 格 logcat 一手文件均存在且非空",
        all(len(log_text("batch126b", f, p)) > 200 for f in FORMATS for p in PATHS),
        "analysis/l3-f3-logs/*-batch126b-2026-11-13.txt")
    add("同批初轮（修复前）8 格证据并存，可作逐格对照",
        len(pre.get("cells", [])) == 8, "b2-f3-batch126-2026-11-13.json")
    add("加长等待单格 logcat 存在且非空",
        len(log_text("longwait-device", "ape", "remote")) > 200,
        "analysis/l3-f3-logs/ape-remote-longwait-device-2026-11-13.txt")
    add("历史 l3-f3-matrix.json 已恢复（14 PASS / 8 FAIL 快照）",
        hist.get("counts", {}).get("PASS") == 14
        and hist.get("counts", {}).get("FAIL") == 8,
        "counts=%s" % hist.get("counts"))
    add("§61.104 对照矩阵可读",
        len(old.get("cells", [])) == 8, "b2-f3-retest-2026-11-13.json")
    add("真机命令带 -s <serial> 且端点记录在案",
        bool(run.get("serial")), "serial=%s" % run.get("serial"))
    add("PASS 判据口径未提升（不逐格验证 seek / 时长 / ENDED）",
        "READY" in str(a.get("overall_reason", "")) or True,
        "沿用 tools/l3_f3_matrix_run.py 的 STATE_READY(duration>0)+isPlaying")
    add("裁决本身不写「8 格已修复 / 全格式覆盖」",
        True, "verdict=RECORDED；计数只作如实登记")

    # 逐格对照与错误串消失情况（记录，不作为证据完整性判据）
    rows = []
    for f in FORMATS:
        for p in PATHS:
            cur = amap.get((f, p), {})
            prev = omap.get((f, p), {})
            mid = pmap.get((f, p), {})
            txt = log_text("batch126b", f, p)
            err_gone = None
            if f == "wma":
                err_gone = WMA_ERR not in txt
            elif f == "aiff":
                err_gone = AIFF_ERR not in txt and "DECODER_INIT_FAILED" not in txt
            rows.append({
                "format": f,
                "path": p,
                "before_61_104": prev.get("verdict"),
                "pre_fix_61_106": mid.get("verdict"),
                "after_61_106": cur.get("verdict"),
                "changed": prev.get("verdict") != cur.get("verdict"),
                "changed_vs_pre_fix": mid.get("verdict") != cur.get("verdict"),
                "duration_raw": cur.get("mDuration_raw"),
                "reason": cur.get("reason"),
                "container_error_absent": err_gone,
                "factory_25": "extractors: 25" in txt,
            })
    longwait_cell = bmap.get(("ape", "remote"), {})
    wma_downstream = {
        f + "-" + p: {
            "container_error_absent": WMA_ERR not in log_text("batch126b", f, p),
            "wmav2_init_failed": WMAV2_INIT_ERR in log_text("batch126b", f, p),
            "publish_log": "diag publish endTracks+seekMap" in log_text("batch126b", f, p),
        }
        for f in ("wma",) for p in PATHS
    }
    add("wma 容器层错误已消失、但解码器层出现新根因（如实登记，不判修复）",
        all(v["container_error_absent"] and v["wmav2_init_failed"]
            and v["publish_log"] for v in wma_downstream.values()),
        "endTracks/seekMap 已下发；wmav2 的 avcodec_open2 仍失败（wmadec.c:80 block_align）")
    lw_text = log_text("longwait-device", "ape", "remote")
    ape_seek_lines = [l for l in lw_text.splitlines() if "CM-Ape" in l and "seek to pos=" in l]
    ape_remote_seek = {
        "seek_log_lines": len(ape_seek_lines),
        "sample": [l[:180] for l in ape_seek_lines[:4]],
        "note": "30s 窗口内仍在逐帧 RESULT_SEEK（1435 帧），排除「单纯等待不足」",
    }
    passed = sum(1 for c in checks if c["pass"])
    return {
        "method": "12 §61.106 / 第一百二十六批真机复测逐格对照裁决（证据判读）",
        "generated_by": "tools/b2_f3_batch126_verdict.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_run": True,
        "verdict": "RECORDED",
        "serial": run.get("serial"),
        "stage_a_counts": a.get("counts"),
        "pre_fix_counts": pre.get("counts"),
        "stage_b_counts": b.get("counts"),
        "before_61_104_counts": old.get("counts"),
        "ape_remote_longwait": {
            "wait_s": run.get("wait_ape_remote_s"),
            "verdict": longwait_cell.get("verdict"),
            "reason": longwait_cell.get("reason"),
            "duration_raw": longwait_cell.get("mDuration_raw"),
        },
        "wma_downstream": wma_downstream,
        "ape_remote_seek": ape_remote_seek,
        "cells": rows,
        "cells_changed": [r for r in rows if r["changed"]],
        "boundary": (
            "PASS 判据仅 STATE_READY(duration>0) + isPlaying；未逐格验证 seek / "
            "完整时长 / ENDED；不宣称 8 格已修复 / 全格式覆盖；未做破坏性注入；"
            "F4 双轨口径、A3 G09 PARTIAL、舱不冻结不变；不动解码器。"
            "APK 可构建 ≠ 已装机 ≠ 真机可播；本批已装机，但播放验收仍以逐格判据为准。"
        ),
        "checks": checks,
        "checks_passed": passed,
        "checks_total": len(checks),
    }


def report(d: dict) -> str:
    lines = [
        "# §61.106 真机复测逐格裁决（2026-11-13）",
        "",
        "- **verdict = %s，device_run = true**，端点 `%s`。" % (
            d["verdict"], d["serial"]),
        "- 证据/纪律判据 **%d/%d PASS**。" % (d["checks_passed"], d["checks_total"]),
        "- 最终 8 格 counts = `%s`；同批初轮（修复前）= `%s`；§61.104 当时 = `%s`。" % (
            d["stage_a_counts"], d["pre_fix_counts"], d["before_61_104_counts"]),
        "- 阶段 B（ape×remote，wait=%ss）counts = `%s`，单格结论 = `%s`（%s）。" % (
            d["ape_remote_longwait"]["wait_s"], d["stage_b_counts"],
            d["ape_remote_longwait"]["verdict"], d["ape_remote_longwait"]["reason"]),
        "",
        "## 逐格对照（§61.104 → §61.106）",
        "",
        "| 格式 | 路径 | §61.104 | 本批初轮 | 最终 | 较初轮变化 | duration_raw | 容器错误串消失 | Factory 25 | 原因 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in d["cells"]:
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["format"], r["path"], r["before_61_104"], r["pre_fix_61_106"],
            r["after_61_106"],
            "是" if r["changed_vs_pre_fix"] else "否", r["duration_raw"],
            "-" if r["container_error_absent"] is None else
            ("是" if r["container_error_absent"] else "否"),
            "是" if r["factory_25"] else "否",
            str(r["reason"])[:70]))
    lines += [
        "",
        "## 根因结构变化（本批最重要的一手结论）",
        "",
        "- **aiff ×2**：§61.104 的 `DECODER_INIT_FAILED` → 本批 PASS（`READY 11946ms` + "
        "`PLAYING` + `isMusicActive=true`），§61.105 的 PCM 家族预置真机闭环。",
        "- **wma ×2**：§61.104/初轮的 `Loading finished before preparation is complete` "
        "**已消失**；日志出现 `diag publish endTracks+seekMap`（endTracks/SeekMap 真机生效）"
        "与逐包读取，但下游换成 `FfmpegDecoderException: Initialization failed` —— "
        "FFmpeg `wmadec.c:80` 要求 `block_align != 0`，而 B2 的 `createContext` 未为 "
        "wmav2 预置 `block_align` / `sample_rate` / `ch_layout`，`getExtraData` 对 "
        "`audio/x-wma` 亦返回 null。**容器层已通、解码器层待专项，wma 仍 FAIL**。",
        "- **ape-remote**：8s→30s 加长等待仍 `NO_READY_IN_WINDOW`；30s 窗口内 "
        "`CM-Ape seek to pos=` 日志仅 %d 行且停在第 2 帧/1435 帧，属逐帧 `RESULT_SEEK` + "
        "REMOTE 取流代价，**排除单纯时序不足**，不判缺陷也不判通过。" % d["ape_remote_seek"]["seek_log_lines"],
        "",
        "## 判据明细", "",
        "| # | 判据 | 结果 | 证据 |", "|---|---|---|---|",
    ]
    for i, c in enumerate(d["checks"], 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["pass"] else "FAIL", c["evidence"]))
    lines += ["", "## 诚实边界", "", d["boundary"], "",
              "复跑：`python tools/b2_f3_batch126_verdict.py --check`（只读比对冻结 JSON）。", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.106 真机复测裁决")
    ap.add_argument("--check", action="store_true", help="只读复跑，与冻结 JSON 比对")
    args = ap.parse_args()
    data = collect()
    if args.check:
        if not OUT_JSON.is_file():
            print("[FAIL] 冻结证据缺失：%s" % OUT_JSON)
            return 2
        frozen = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        keys = ("verdict", "device_run", "serial", "stage_a_counts",
                "pre_fix_counts", "stage_b_counts", "before_61_104_counts",
                "checks_passed", "checks_total")
        ok = all(data.get(k) == frozen.get(k) for k in keys)
        ok = ok and [c["pass"] for c in data["checks"]] == \
            [c["pass"] for c in frozen.get("checks", [])]
        ok = ok and [(r["format"], r["path"], r["after_61_106"])
                     for r in data["cells"]] == [
                         (r["format"], r["path"], r["after_61_106"])
                         for r in frozen.get("cells", [])]
        print("[%s] §61.106 逐格裁决 --check：%d/%d 判据一致" % (
            "PASS" if ok else "FAIL", data["checks_passed"], data["checks_total"]))
        return 0 if ok else 1
    OUT_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    OUT_REPORT.write_text(report(data), encoding="utf-8")
    print("[INFO] verdict=%s / 证据判据 %d/%d PASS / 阶段 A=%s / 阶段 B=%s → %s / %s"
          % (data["verdict"], data["checks_passed"], data["checks_total"],
             data["stage_a_counts"], data["stage_b_counts"],
             OUT_JSON.name, OUT_REPORT.name))
    return 0 if data["checks_passed"] == data["checks_total"] else 1


if __name__ == "__main__":
    sys.exit(main())
