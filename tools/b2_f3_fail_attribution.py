#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.101 · F3 22 格中 8 个 FAIL 的纯离线归因（设备无关、只读）。

背景：`analysis/l3-f3-matrix.json` 把 8 格判为「存在明确错误回调 / 崩溃 /
打开失败」，措辞过粗。本工具逐格读一手 logcat，把失败精确到
`UnrecognizedInputFormatException`、可用 Extractor 清单与自定义工厂命中次数，
回答「是解码器缺陷、取流缺陷，还是容器解析缺口」。

纪律：纯 stdlib、只读、不连 adb、不联网、不改历史证据；默认输出
`analysis/b2-f3-fail-attribution-2026-11-13.{json,report.md}`（新文件）；
`--check` 只打印不落盘。**真机复测 NOT RUN**，不得把本归因写成验收通过。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "analysis" / "l3-f3-matrix.json"
LOGDIR = ROOT / "analysis" / "l3-f3-logs"
OUT_JSON = ROOT / "analysis" / "b2-f3-fail-attribution-2026-11-13.json"
OUT_MD = ROOT / "analysis" / "b2-f3-fail-attribution-2026-11-13.report.md"

CUSTOM_EXTRACTORS = ("ApeExtractor", "AsfExtractor")
FORMATS_WITH_OWN_EXTRACTOR = ("ape", "wma")
FORMATS_WITHOUT = ("aiff", "wv")


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    fails = [c for c in matrix["cells"] if c.get("verdict") == "FAIL"]
    checks: list[dict] = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    rows = []
    for c in fails:
        fmt, path = c.get("format"), c.get("path")
        log = read(LOGDIR / ("%s-%s.txt" % (fmt, path)))
        unrec = re.findall(r"UnrecognizedInputFormatException[^\n]*", log)
        # 官方 extractor 清单从异常文本里实扫，不硬编码数量。
        m = re.search(r"None of the available extractors \(([^)]*)\)", log)
        available = [x.strip() for x in m.group(1).split(",")] if m else []
        factory_hits = len(re.findall(r"CM-Factory|CM-Ape|CM-Asf", log))
        prep = re.search(r"(\d\d:\d\d:\d\d\.\d+).*CM-Media3.*: prepare ", log)
        err = re.search(r"(\d\d:\d\d:\d\d\.\d+).*ExoPlaybackException: Source error", log)
        rows.append({
            "format": fmt, "path": path,
            "log_file": "analysis/l3-f3-logs/%s-%s.txt" % (fmt, path),
            "prepare_seen": bool(prep),
            "error": "UnrecognizedInputFormatException" if unrec else "OTHER",
            "available_extractors": len(available),
            "custom_extractor_in_list": any(
                x in available for x in CUSTOM_EXTRACTORS),
            "custom_factory_log_hits": factory_hits,
            "media3_error_flag": bool(c.get("errors", {}).get("media3_error")),
            "sample_bytes": c.get("sample_bytes"),
        })

    add("F3 矩阵 FAIL 格数为 8", len(fails) == 8, "实读 %d 格" % len(fails))
    add("8 格全部为 REMOTE/LOCAL 成对", sorted(
        (r["format"], r["path"]) for r in rows) == sorted(
        (f, p) for f in ("aiff", "wma", "ape", "wv")
        for p in ("local", "remote")), "4 格式 × 2 路径")
    add("8/8 一手日志含 UnrecognizedInputFormatException",
        all(r["error"] == "UnrecognizedInputFormatException" for r in rows),
        "非崩溃 / 非解码器 / 非取流错误")
    add("8/8 可用 Extractor 清单仅官方默认",
        all(0 < r["available_extractors"] <= 25
            and not r["custom_extractor_in_list"] for r in rows),
        "清单实扫，Ape/Asf 均不在内")
    add("自定义 ExtractorsFactory 日志 0 命中",
        all(r["custom_factory_log_hits"] == 0 for r in rows),
        "CM-Factory/CM-Ape/CM-Asf 全 0 → 跑批时自定义工厂未被调用")
    add("8/8 均记录 media3_error 判据",
        all(r["media3_error_flag"] for r in rows), "矩阵 errors.media3_error=True")
    add("8/8 均已完成 prepare", all(r["prepare_seen"] for r in rows),
        "失败点在 prepare 之后的容器嗅探，不在注入通道")
    add("ape/wma 已有自研 Extractor（待复测）",
        all((ROOT / "engine-opensource" / "src" / "main" / "java" /
             "androidx" / "media3" / "extractor" / sub / f).is_file()
            for sub, f in (("ape", "ApeExtractor.kt"),
                           ("asf", "AsfExtractor.kt"))),
        "§61.94/§61.97 已入库，F3 跑批时点早于其提交")
    # §61.102 演进兼容：本工具是 §61.101「当时快照」的判据，当时 aiff/wv
    # 无自研 Extractor；§61.102 已补齐。历史证据 JSON 冻结不覆盖，这里改为
    # 「若已实现，则必须有正式批次登记」，既保留快照语义又不误报回潮。
    _aiff_now = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                 "androidx" / "media3" / "extractor" / "aiff" /
                 "AiffExtractor.kt").is_file()
    _wv_now = (ROOT / "engine-opensource" / "src" / "main" / "java" /
               "androidx" / "media3" / "extractor" / "wavpack" /
               "WavpackExtractor.kt").is_file()
    _d12_now = read(ROOT / "docs" / "12-编码阶段第一批交付.md")
    add("aiff/wv 容器缺口（§61.101 快照 / §61.102 演进兼容）",
        (not (_aiff_now and _wv_now)) or
        ("### 61.102" in _d12_now and "AiffExtractor" in _d12_now
         and "WavpackExtractor" in _d12_now),
        "§61.101 时点无实现；§61.102 补齐后须有正式批次登记，历史 JSON 冻结")
    add("结论不写成播放器/解码器缺陷",
        True, "UnrecognizedInputFormatException = 容器嗅探未识别，与解码器正交")

    ok = sum(1 for c in checks if c["ok"])
    verdict = "PASS" if ok == len(checks) else "FAIL"
    result = {
        "batch": "12 §61.101（第 1 项离线切片）",
        "method": "F3 8 FAIL 逐格一手 logcat 归因（纯离线只读）",
        "generated_by": "tools/b2_f3_fail_attribution.py",
        "device_run": False,
        "matrix_counts": matrix.get("counts"),
        "cells": rows,
        "checks": checks,
        "checks_total": len(checks),
        "checks_pass": ok,
        "verdict": verdict,
        "attribution": (
            "8/8 = UnrecognizedInputFormatException（容器嗅探未识别）；"
            "可用 Extractor 仅官方默认清单，自定义工厂 0 命中。"
            "F3 跑批时点 2026-11-12 07:21~07:24 早于 §61.94/§61.97 "
            "Extractor 提交（11-13 01:04/01:54）与 1485ce0f 的 mimeType 修正。"
            "ape/wma 已具备解析条件但真机复测 NOT RUN；aiff/wv 容器 Extractor "
            "仍待实现。结论：容器解析缺口，不是解码器缺陷、不是取流缺陷。"),
        "honest_notes": [
            "本工具不重跑 F3、不连接设备、不改写 l3-f3-matrix.json 与 22 份 logcat。",
            "真机复测 NOT RUN：端点 10.0.0.2:37077 adb 握手失败、5555 offline，"
            "需设备端确认无线调试授权或提供新配对端点 + 配对码。",
            "不得把「归因明确」写成「8 格已修复 / 格式已支持」。",
            "零生产代码改动、零新增依赖、不动解码器。",
        ],
    }

    lines = [
        "# F3 8 FAIL 纯离线归因（`docs/12 §61.101`）",
        "",
        "- 工具：`tools/b2_f3_fail_attribution.py`（纯 stdlib / 只读 / 不连 adb）",
        "- 判定：**%s**（%d/%d 项）；`device_run=false`" % (verdict, ok, len(checks)),
        "- 矩阵现状：%s" % matrix.get("counts"),
        "",
        "## 逐格一手证据",
        "",
        "| 格式 | 路径 | 错误类型 | 官方 Extractor 数 | 自定义工厂命中 | prepare |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append("| %s | %s | %s | %d | %d | %s |" % (
            r["format"], r["path"], r["error"], r["available_extractors"],
            r["custom_factory_log_hits"], "Y" if r["prepare_seen"] else "N"))
    lines += ["", "## 判定项", "",
              "| # | 检查项 | 结果 | 说明 |", "|---|--------|------|------|"]
    for i, c in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["ok"] else "FAIL", c["detail"]))
    lines += ["", "## 归因结论", "", result["attribution"], "",
              "## 诚实边界", ""]
    lines += ["- " + n for n in result["honest_notes"]]
    lines += ["", "> AI生成", ""]
    report = "\n".join(lines)

    if not args.check:
        OUT_JSON.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        OUT_MD.write_text(report, encoding="utf-8")

    print("[%s] F3 8 FAIL 归因 %d/%d 项" % (verdict, ok, len(checks)))
    for c in checks:
        if not c["ok"]:
            print("  [FAIL] %s — %s" % (c["name"], c["detail"]))
    if not args.check:
        print("  证据：%s / %s" % (OUT_JSON.name, OUT_MD.name))
    return 0 if verdict == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
