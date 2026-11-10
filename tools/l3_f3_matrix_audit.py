#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.49 / F3 · 双路径矩阵证据审计（只读、纯标准库、可复跑、不连 adb）。

校验 `analysis/l3-f3-fixtures.json` / `analysis/l3-f3-matrix.json` /
`analysis/l3-f3-matrix-report.md` / 14 份原始 logcat 与源码锚点：
防伪造 PASS、防把 NO_SAMPLE 写成 PASS、防凭据落盘、防发布基线漂移。
产出 analysis/l3-f3-audit.json，供 check_source_contracts.py §61.49 守护读取。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "analysis" / "l3-f3-fixtures.json"
MATRIX = ROOT / "analysis" / "l3-f3-matrix.json"
REPORT = ROOT / "analysis" / "l3-f3-matrix-report.md"
AUDIT = ROOT / "analysis" / "l3-f3-audit.json"
LOG_DIR = ROOT / "analysis" / "l3-f3-logs"
PLAN = ROOT / "analysis" / "tl-l3-f3-matrix-plan-2026-11-09.md"

SAMPLES = {"mp3", "wma", "flac", "wav", "ape", "ogg", "aiff"}
NO_SAMPLE = {"m4a", "aac", "opus", "wv", "tak", "dsf", "dff"}
EXPECTED_COUNTS = {"PASS": 11, "FAIL": 3}
EXPECTED_FAILS = {("ape", "remote"), ("aiff", "local"), ("aiff", "remote")}

ENGINE_ADAPTER = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
AUDIO_FORMATS = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"
SERVICE = ROOT / "app/src/main/kotlin/com/example/cloudmusic/PlaybackService.kt"
COMPOSITION = ROOT / "core-di/src/main/kotlin/com/example/cloudmusic/di/playback/PlaybackComposition.kt"
ENGINE_GRADLE = ROOT / "engine-legacy/build.gradle.kts"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
CSV = ROOT / "docs/assets-mapping.csv"
DOCS = ROOT / "docs"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
RES_DIR = ROOT / "app/src/main/res"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def main() -> int:
    missing = [str(p.relative_to(ROOT)).replace("\\", "/")
               for p in (FIXTURES, MATRIX, REPORT, PLAN, ENGINE_ADAPTER,
                         AUDIO_FORMATS, SERVICE, COMPOSITION, ENGINE_GRADLE,
                         SETTINGS, MANIFEST, CSV, OVERVIEW)
               if not p.is_file()]
    if missing:
        print("[FAIL] §61.49 审计缺少文件：%s" % missing)
        return 1

    fixtures = json.loads(FIXTURES.read_text(encoding="utf-8"))
    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    report = REPORT.read_text(encoding="utf-8")
    cells = matrix["cells"]

    log_present = {}
    log_evidence = {}
    for c in cells:
        p = ROOT / c["log_file"]
        log_present[c["log_file"]] = p.is_file()
        text = read(p)
        log_evidence[(c["format"], c["path"])] = {
            "has_prepare": "prepare trackId=" in text,
            "has_extension": ("resourceInfo extension=%s " % c["format"]) in text,
            "has_codec": "Open codec for " in text,
            "has_write_start": "Write start play nErr:0" in text,
            "path_marker": (("getLocalPath path=" in text and "CreateLocalSource" in text)
                            if c["path"] == "local" else
                            ("getUrl url=http://127.0.0.1:8765/" in text
                             and "createDataRequestHandler" in text)),
        }

    pass_cells = [c for c in cells if c["verdict"] == "PASS"]
    fail_cells = [c for c in cells if c["verdict"] == "FAIL"]
    counts = matrix["counts"]

    checks = {
        "sample_set_exact": set(fixtures["formats"]) == SAMPLES,
        "no_sample_exact": set(fixtures["no_sample_formats"]) == NO_SAMPLE
                           and set(matrix["no_sample_formats"]) == NO_SAMPLE,
        "cell_count_14": len(cells) == 14,
        "both_paths_per_format": all(
            {(c["format"], c["path"]) for c in cells
             if c["format"] == f} == {(f, "local"), (f, "remote")}
            for f in SAMPLES
        ),
        "counts_not_forged": counts == EXPECTED_COUNTS,
        "fail_cells_exact": {(c["format"], c["path"]) for c in fail_cells} == EXPECTED_FAILS,
        "extension_chain_all_real": (
            matrix["f2b_extension_chain"]["extension_ok_all"] is True
            and all(c["extension_ok"] and c["extension"] == c["format"] for c in cells)
        ),
        "pass_cells_backed_by_logs": all(
            all(log_evidence[(c["format"], c["path"])][k]
                for k in ("has_prepare", "has_extension", "has_codec",
                          "has_write_start", "path_marker"))
            for c in pass_cells
        ),
        "fail_cells_not_silently_dropped": all(
            log_evidence[(c["format"], c["path"])]["has_prepare"] for c in fail_cells
        ),
        "all_logs_present": all(log_present.values()),
        "no_sample_cells_count": len(matrix["no_sample_cells"]) == len(NO_SAMPLE) * 2
                                 and all(x["verdict"] == "NO_SAMPLE"
                                         for x in matrix["no_sample_cells"]),
        "test_precondition_restored": (
            matrix["test_precondition"]["device_settings_unchanged"] is True
            and matrix["test_precondition"]["restored"].startswith("跑完删除")
        ),
        "source_anchors": (
            "AudioFormats.extOfSource(" in read(ENGINE_ADAPTER)
            and "?: source.mimeHint.orEmpty()" not in read(ENGINE_ADAPTER)
            and "fun extOfSource(url: String?, localPath: String?, mimeHint: String?): String"
                in read(AUDIO_FORMATS)
            and 'const val EXTRA_SOURCE_URL = "com.example.cloudmusic.extra.SOURCE_URL"'
                in read(SERVICE)
            and "fun playUrl(url: String)" in read(COMPOSITION)
            and 'private const val REMOTE_FIXTURE_STORAGE_ID = "debug-remote-fixture"'
                in read(COMPOSITION)
            and 'implementation(project(":core-model"))' in read(ENGINE_GRADLE)
        ),
        "baseline_unchanged": (
            len(re.findall(r'^\s*include\(":', read(SETTINGS), re.MULTILINE)) == 19
            and len(re.findall(r"<uses-permission\b", read(MANIFEST))) == 11
            and len(list(SO_DIR.glob("*.so"))) == 18
            and len(list(DOCS.rglob("*.md"))) == 29
            and "31 个文件" in read(OVERVIEW)
            and len([l for l in CSV.read_text(encoding="utf-8").splitlines()[1:]
                     if l.strip()]) == 61
            and re.search(r'^\s*include\(":ui-legacy"\)', read(SETTINGS),
                          re.MULTILINE) is None
            and not RES_DIR.is_dir()
        ),
        "report_honest": all(s in report for s in (
            "11 PASS / 3 FAIL", "NO_SAMPLE", "PARTIAL", "不掩盖", "mDuration_raw",
            "不得写「全格式已支持」", "归因",
        )),
        "overall_is_partial": matrix["overall_verdict"] == "PARTIAL",
        # 防凭据落盘：只匹配键名 / 赋值特征，绝不在本工具里写任何明文口令。
        "no_credentials_in_evidence": (
            not any(k in (read(FIXTURES) + read(MATRIX) + read(REPORT))
                    for k in ('"password"', '"token"', '"username"',
                              "OPENLIST_PASSWORD=", "Authorization:"))
            and not re.search(
                r"(?i)password\s*=\s*['\"][A-Za-z0-9_!@#$%^&*]{6,}['\"]",
                read(ROOT / "tools" / "l3_f3_fixture_gen.py"))
        ),
    }

    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.49 / F3 双路径矩阵证据审计（只读、纯标准库、不连 adb）",
        "generated_by": "tools/l3_f3_matrix_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "matrix_summary": {
            "cells": len(cells),
            "counts": counts,
            "formats": sorted(SAMPLES),
            "no_sample_formats": sorted(NO_SAMPLE),
            "extension_ok_all": matrix["f2b_extension_chain"]["extension_ok_all"],
            "elapsed_s": matrix["elapsed_s"],
            "serial": matrix["serial"],
        },
        "failure_attribution": {
            "ape/remote": ("4 MiB 截断片段缺 APE 尾部 seek table；日志 Start id=2 "
                           "offset=4194272 length=32 → onEnd delayed 2s，未进解码链；"
                           "同片段 LOCAL 路径 PASS → 归因样本截断 + REMOTE 取流，"
                           "不判播放器缺陷；需完整 228 MB 样本复核。与 F4 的 "
                           "m4a EOF 2s 掩盖同源。"),
            "aiff/local": ("openSource 2 / Play status 9，native demuxer 未打开；"
                           "样本为自造极简 AIFF 容器且未经第三方解码器校验 → "
                           "无法区分「样本不可解」与「aiff demuxer/decoder 未编入」，"
                           "需真实 .aiff 样本复核。"),
            "aiff/remote": "同 aiff/local；extension=aiff 正确，失败发生在 demuxer 阶段。",
        },
        "honest_notes": [
            "F3 整体判 PARTIAL：7 种格式无样本、3 格 FAIL、截断片段不验证完整时长 / seek / ENDED。",
            "PASS 判据只到「Open codec + Write start play nErr:0 + 零错误回调」，不等于 G01 全量验收。",
            "测试前置 network.wifiOnly=false 仅跑批期间生效，跑完删除 app 私有设置文件恢复默认。",
            "P0 native 舱零改动；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    AUDIT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                     encoding="utf-8")
    print("[INFO] 输出 %s" % AUDIT.relative_to(ROOT))
    print("[INFO] 14 格 / %s / extension_ok_all=%s" %
          (counts, data["matrix_summary"]["extension_ok_all"]))
    if failed:
        print("[FAIL] §61.49 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.49 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
