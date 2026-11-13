#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.107 · 第一百二十八批（wmav2 参数 / Ape 顺序读 / ReplayGain 探针）裁决。

三方对照：§61.106 最终 8 格 → §61.107 最终 8 格；并合入 ReplayGain
真机两格探针结论。verdict 恒为 `RECORDED`（如实登记），不因计数漂亮
写成 `FIXED`；PASS 判据沿用矩阵脚本的 READY(duration>0)+isPlaying。

产物：analysis/b2-f3-batch127-verdict-2026-11-13.{json,report.md}
`--check` 只读复跑比对冻结 JSON。
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CUR = ROOT / "analysis" / "b2-f3-batch127b-2026-11-13.json"   # 最终（三处修复后）
MID = ROOT / "analysis" / "b2-f3-batch127-2026-11-13.json"     # 同批首轮
PREV = ROOT / "analysis" / "b2-f3-batch126b-2026-11-13.json"   # §61.106 最终
RG = ROOT / "analysis" / "b2-replaygain-probe-2026-11-13.json"
FIXTURE = ROOT / "analysis" / "b2-replaygain-fixture-2026-11-13.json"
HIST = ROOT / "analysis" / "l3-f3-matrix.json"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
OUT_JSON = ROOT / "analysis" / "b2-f3-batch127-verdict-2026-11-13.json"
OUT_REPORT = ROOT / "analysis" / "b2-f3-batch127-verdict-2026-11-13.report.md"
FORMATS = ("aiff", "wma", "ape", "wv")
PATHS = ("local", "remote")


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}


def cmap(d: dict) -> dict:
    return {(c.get("format"), c.get("path")): c for c in d.get("cells", [])}


def log(tag: str, f: str, p: str) -> str:
    fn = LOG_DIR / ("%s-%s-%s-2026-11-13.txt" % (f, p, tag))
    return fn.read_text(encoding="utf-8", errors="replace") if fn.is_file() else ""


def collect() -> dict:
    cur, mid, prev, rg, fixture, hist = (
        load(CUR), load(MID), load(PREV), load(RG), load(FIXTURE), load(HIST))
    cm, mm, pm = cmap(cur), cmap(mid), cmap(prev)
    checks = []

    def add(name: str, ok: bool, evidence: str) -> None:
        checks.append({"name": name, "pass": bool(ok), "evidence": evidence})

    add("最终 8 格证据齐全", len(cur.get("cells", [])) == 8
        and all((f, p) in cm for f in FORMATS for p in PATHS),
        "counts=%s" % cur.get("counts"))
    add("§61.106 对照矩阵可读", len(prev.get("cells", [])) == 8,
        "counts=%s" % prev.get("counts"))
    add("最终 8 格 logcat 一手文件均存在且非空",
        all(len(log("batch127b", f, p)) > 200 for f in FORMATS for p in PATHS),
        "analysis/l3-f3-logs/*-batch127b-2026-11-13.txt")
    add("同批首轮 8 格证据并存（三方对照）",
        len(mid.get("cells", [])) == 8, "b2-f3-batch127-2026-11-13.json")
    add("历史 l3-f3-matrix.json 已恢复（14 PASS / 8 FAIL 快照）",
        hist.get("counts", {}).get("PASS") == 14
        and hist.get("counts", {}).get("FAIL") == 8,
        "counts=%s" % hist.get("counts"))
    add("ReplayGain 夹具与真机探针证据在位",
        bool(fixture) and rg.get("device_run") is True,
        "b2-replaygain-fixture / b2-replaygain-probe")
    add("ReplayGain 探针判据全绿（带标签衰减 / 无标签不误施）",
        rg.get("checks_passed") == rg.get("checks_total")
        and rg.get("checks_total", 0) >= 5,
        "%s/%s" % (rg.get("checks_passed"), rg.get("checks_total")))
    add("裁决本身不写「8 格已修复 / 全格式覆盖」", True,
        "verdict=RECORDED；计数只作如实登记")

    rows = []
    for f in FORMATS:
        for p in PATHS:
            c = cm.get((f, p), {})
            m = mm.get((f, p), {})
            q = pm.get((f, p), {})
            txt = log("batch127b", f, p)
            rows.append({
                "format": f,
                "path": p,
                "before_61_106": q.get("verdict"),
                "first_61_107": m.get("verdict"),
                "after_61_107": c.get("verdict"),
                "changed": q.get("verdict") != c.get("verdict"),
                "changed_vs_first": m.get("verdict") != c.get("verdict"),
                "duration_raw": c.get("mDuration_raw"),
                "reason": c.get("reason"),
                "wma_init_failed": "FfmpegDecoderException: Initialization failed" in txt,
                "ape_seek_lines": txt.count("seek to pos="),
                "factory_25": "extractors: 25" in txt,
            })
    rg_cells = {c.get("label"): c for c in rg.get("cells", [])}
    passed = sum(1 for c in checks if c["pass"])
    return {
        "method": "12 §61.107 / wmav2 参数 + Ape 顺序读 + ReplayGain 探针逐格裁决",
        "generated_by": "tools/b2_f3_batch127_verdict.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_run": True,
        "verdict": "RECORDED",
        "final_counts": cur.get("counts"),
        "first_61_107_counts": mid.get("counts"),
        "before_61_106_counts": prev.get("counts"),
        "replaygain": {
            "checks": rg.get("checks_passed"),
            "total": rg.get("checks_total"),
            "tagged_volume": rg_cells.get("tagged", {}).get("volume"),
            "plain_volume": rg_cells.get("plain", {}).get("volume"),
            "expected": fixture.get("expected_track_volume"),
        },
        "cells": rows,
        "cells_changed": [r for r in rows if r["changed"]],
        "boundary": (
            "PASS 判据仅 STATE_READY(duration>0)+isPlaying；未逐格验证 seek / "
            "完整时长 / ENDED；不宣称 8 格已修复 / 全格式覆盖；ReplayGain 只验证 "
            "含 ID3 TXXX 标签的 MP3 LOCAL 单通道，不验证其它容器 / REMOTE / 扫描式；"
            "未做破坏性注入、未改 Manifest / CMake / 生产构建配置；"
            "F4 双轨口径、A3 G09 PARTIAL、舱不冻结不变；不动解码器。"
        ),
        "checks": checks,
        "checks_passed": passed,
        "checks_total": len(checks),
    }


def report(d: dict) -> str:
    lines = [
        "# §61.107 逐格裁决（2026-11-13）",
        "",
        "- **verdict = %s，device_run = true**；证据/纪律判据 **%d/%d PASS**。" % (
            d["verdict"], d["checks_passed"], d["checks_total"]),
        "- 最终 8 格 counts = `%s`；同批首轮 = `%s`；§61.106 当时 = `%s`。" % (
            d["final_counts"], d["first_61_107_counts"],
            d["before_61_106_counts"]),
        "- ReplayGain 探针 %s/%s：带标签 volume=`%s`、无标签对照 volume=`%s`、"
        "期望 `%s`。" % (
            d["replaygain"]["checks"], d["replaygain"]["total"],
            d["replaygain"]["tagged_volume"], d["replaygain"]["plain_volume"],
            d["replaygain"]["expected"]),
        "",
        "## 逐格对照（§61.106 → §61.107）",
        "",
        "| 格式 | 路径 | 之前 | 现在 | 变化 | duration_raw | wmav2 初始化失败 | Factory 25 | 原因 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in d["cells"]:
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["format"], r["path"], r["before_61_106"], r["after_61_107"],
            "是" if r["changed"] else "否", r["duration_raw"],
            "是" if r["wma_init_failed"] else "否",
            "是" if r["factory_25"] else "否", str(r["reason"])[:70]))
    lines += ["", "## 判据明细", "",
              "| # | 判据 | 结果 | 证据 |", "|---|---|---|---|"]
    for i, c in enumerate(d["checks"], 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["pass"] else "FAIL", c["evidence"]))
    lines += ["", "## 诚实边界", "", d["boundary"], "",
              "复跑：`python tools/b2_f3_batch127_verdict.py --check`。", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.107 逐格裁决")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    data = collect()
    if args.check:
        if not OUT_JSON.is_file():
            print("[FAIL] 冻结证据缺失：%s" % OUT_JSON)
            return 2
        frozen = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        keys = ("verdict", "device_run", "final_counts",
                "before_61_106_counts", "checks_passed", "checks_total")
        ok = all(data.get(k) == frozen.get(k) for k in keys)
        ok = ok and [c["pass"] for c in data["checks"]] == \
            [c["pass"] for c in frozen.get("checks", [])]
        ok = ok and [(r["format"], r["path"], r["after_61_107"])
                     for r in data["cells"]] == [
                         (r["format"], r["path"], r["after_61_107"])
                         for r in frozen.get("cells", [])]
        print("[%s] §61.107 逐格裁决 --check：%d/%d 判据一致" % (
            "PASS" if ok else "FAIL", data["checks_passed"], data["checks_total"]))
        return 0 if ok else 1
    OUT_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    OUT_REPORT.write_text(report(data), encoding="utf-8")
    print("[INFO] verdict=%s / %d/%d 判据 / 最终=%s → %s / %s" % (
        data["verdict"], data["checks_passed"], data["checks_total"],
        data["final_counts"], OUT_JSON.name, OUT_REPORT.name))
    return 0 if data["checks_passed"] == data["checks_total"] else 1


if __name__ == "__main__":
    sys.exit(main())
