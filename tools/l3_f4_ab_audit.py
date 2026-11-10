#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.51 / F4 A/B · 证据审计（只读、纯标准库、不连 adb）。

校验 faststart fMP4 对照样本、变体 A 真机结果、基线回退复验：
防伪造 F4 完成、防 P0 取流代码残留 A/B 变体、防把 F3 矩阵悄悄改写、
防凭据落盘、防发布基线漂移。产出 analysis/l3-f4-ab-audit.json。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "analysis" / "l3-f4-ab-report.md"
PROBE = ROOT / "analysis" / "l3-f4-faststart-probe.json"
AB_A = ROOT / "analysis" / "l3-f4-ab-a.json"
RECHECK = ROOT / "analysis" / "l3-f4-baseline-recheck.json"
FINAL = ROOT / "analysis" / "l3-f4-m4a-final.json"
F3_MATRIX = ROOT / "analysis" / "l3-f3-matrix.json"
F4_AUDIT = ROOT / "analysis" / "l3-f4-eof-audit.json"
LOG_DIR = ROOT / "analysis" / "l3-f4-logs"
FAST = ROOT / "analysis" / "l3-f3-fixtures" / "f3_m4a_fast.m4a"
MOOV_END = ROOT / "analysis" / "l3-f3-fixtures" / "f3_m4a.m4a"
OUT = ROOT / "analysis" / "l3-f4-ab-audit.json"

URL_DATA_REQUEST = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
CSV = ROOT / "docs/assets-mapping.csv"
DOCS = ROOT / "docs"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
DELIVERY = ROOT / "docs/12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs/17-竞品模块清单与复刻规划.md"
RES_DIR = ROOT / "app/src/main/res"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8"))


def main() -> int:
    required = (REPORT, PROBE, AB_A, RECHECK, FINAL, F3_MATRIX, F4_AUDIT,
                FAST, MOOV_END, URL_DATA_REQUEST, SETTINGS, MANIFEST, CSV,
                OVERVIEW, DELIVERY, PLAN17)
    missing = [str(p.relative_to(ROOT)).replace("\\", "/") for p in required
               if not p.is_file()]
    if missing:
        print("[FAIL] §61.51 审计缺少文件：%s" % missing)
        return 1

    ab = load(AB_A)
    final = load(FINAL)
    recheck = load(RECHECK)
    f3 = load(F3_MATRIX)
    f4 = load(F4_AUDIT)
    code = read(URL_DATA_REQUEST)
    report = read(REPORT)

    local = next((c for c in final["cells"] if c["path"] == "local"), {})
    remote = next((c for c in final["cells"] if c["path"] == "remote"), {})
    ab_starts = ab.get("starts", [])
    baseline_starts = [s for s in read(LOG_DIR / "fast-remote-25s.txt").splitlines()
                       if "Start id=" in s]

    checks = {
        "paired_samples_exist": (
            FAST.is_file() and MOOV_END.is_file()
            and FAST.stat().st_size == MOOV_END.stat().st_size == 3918787
        ),
        "final_local_pass_aac": (
            local.get("push_ok") is True
            and local.get("extension") == "m4a"
            and local.get("codec") == "aac"
            and local.get("openSource") == "0"
            and local.get("write") is True
        ),
        "final_remote_fail_no_codec": (
            remote.get("extension") == "m4a"
            and remote.get("codec") is None
            and remote.get("write") is False
            and remote.get("m103") is False
        ),
        "remote_repeated_start_reproduced": (
            any("offset=327895" in s for s in ab_starts)
            and len([s for s in ab_starts if "offset=327895" in s]) >= 2
        ),
        "variant_a_no_codec": ab.get("codec") is None and ab.get("write") is False,
        "variant_a_log_marker": all("AB-A immediate" in s
                                    for s in ab.get("onEnd", []) if s),
        "ab_negative_conclusion": "候选①" in report and "证伪" in report,
        "p0_code_restored": (
            all(s in code for s in ("repeat(20)", "Thread.sleep(100)",
                                    "delayed 2s or immediate"))
            and "AB-A immediate" not in code
        ),
        "baseline_recheck_no_regression": (
            len(recheck["cells"]) == 5
            and all(c["verdict"] == "PASS" for c in recheck["cells"]
                    if c["label"] in ("mp3-local", "mp3-remote", "wma-remote"))
        ),
        "f3_matrix_untouched": (
            len(f3["cells"]) == 14
            and f3["counts"] == {"PASS": 11, "FAIL": 3}
            and len(f3["no_sample_formats"]) == 7
            and f3["overall_verdict"] == "PARTIAL"
        ),
        "f4_still_blocked": f4["f4_status"] == "BLOCKED",
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
        "docs_written_back": ("61.51" in read(DELIVERY) and "8.11" in read(PLAN17)),
        # 防凭据落盘：只匹配键名 / 赋值特征，绝不在工具里写任何明文口令。
        "no_credentials_in_evidence": (
            not any(k in (read(REPORT) + read(AB_A) + read(FINAL) + read(RECHECK))
                    for k in ('"password"', '"token"', '"username"',
                              "OPENLIST_PASSWORD=", "Authorization:"))
            and not re.search(
                r"(?i)password\s*=\s*['\"][A-Za-z0-9_!@#$%^&*]{6,}['\"]",
                read(ROOT / "tools" / "l3_f3_fixture_gen.py"))
        ),
    }

    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.51 / F4 A/B 证据审计（只读、纯标准库、不连 adb）",
        "generated_by": "tools/l3_f4_ab_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "summary": {
            "faststart_local": "PASS / Open codec for aac / openSource 0 / Write start nErr:0",
            "faststart_remote": "FAIL / 无 codec / Start id=3 offset=327895 重复 / 无 -103",
            "variant_a": "去掉 2s 等待后结果与基线相同 → 候选①证伪",
            "p0": "UrlDataRequest 已回退基线、重新编译安装、5 格复验零回归",
            "f3_matrix": "14 格 / 11 PASS / 3 FAIL / 7 NO_SAMPLE 未被改写",
            "f4_status": "BLOCKED（证据升级，但仍未完成）",
        },
        "honest_notes": [
            "两个 m4a 样本是人工截取/重排的 fMP4 片段，不是完整真实 m4a 音频；"
            "LOCAL 能解出 aac 不等于「m4a 已支持」。",
            "本批未改任何生产代码；A/B 变体临时存在并已回退。",
            "设备离线后本工具不连 adb；真机证据均为离线前采集的原始 logcat。",
            "A3 仍 G09 PARTIAL、舱不冻结；F4 之后仍需 F5 才回 P2→P3→P4。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] LOCAL=%s / REMOTE=%s / 变体A证伪=%s / F4=%s"
          % (checks["final_local_pass_aac"], checks["final_remote_fail_no_codec"],
             checks["ab_negative_conclusion"], data["summary"]["f4_status"]))
    if failed:
        print("[FAIL] §61.51 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.51 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
