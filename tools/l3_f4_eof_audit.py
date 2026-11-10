#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.50 / F4 · 根因实证与阻塞状态审计（只读、纯标准库、可复跑、不连 adb）。

防伪造 F4 完成、防 2s 等待被悄悄改掉、防把 F3 的 ape×remote 误当复现载体、
防凭据落盘、防发布基线漂移。产出 analysis/l3-f4-eof-audit.json，
供 check_source_contracts.py §61.50 守护读取。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "analysis" / "l3-f4-eof-probe.json"
PLAN = ROOT / "analysis" / "tl-l3-f4-eof-plan-2026-11-09.md"
LOG_DIR = ROOT / "analysis" / "l3-f4-logs"
SAMPLE = ROOT / "analysis" / "l3-f3-fixtures" / "f3_m4a.m4a"
OUT = ROOT / "analysis" / "l3-f4-eof-audit.json"

URL_DATA_REQUEST = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
ENGINE_ADAPTER = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
CSV = ROOT / "docs/assets-mapping.csv"
DOCS = ROOT / "docs"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
DELIVERY = ROOT / "docs/12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs/17-竞品模块清单与复刻规划.md"
RES_DIR = ROOT / "app/src/main/res"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
F3_MATRIX = ROOT / "analysis" / "l3-f3-matrix.json"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def main() -> int:
    required = (PROBE, PLAN, SAMPLE, URL_DATA_REQUEST, ENGINE_ADAPTER,
                SETTINGS, MANIFEST, CSV, OVERVIEW, DELIVERY, PLAN17, F3_MATRIX)
    missing = [str(p.relative_to(ROOT)).replace("\\", "/") for p in required
               if not p.is_file()]
    if missing:
        print("[FAIL] §61.50 审计缺少文件：%s" % missing)
        return 1

    probe = json.loads(PROBE.read_text(encoding="utf-8"))
    plan = PLAN.read_text(encoding="utf-8")
    code = read(URL_DATA_REQUEST)
    logs = {k: read(LOG_DIR / ("m4a-%s.txt" % k)) for k in ("local", "remote")}
    matrix = json.loads(F3_MATRIX.read_text(encoding="utf-8"))

    checks = {
        "probe_carrier_is_false": (
            probe["verdict"]["f4_repro_carrier"] is False
            and probe["verdict"]["moov_tail_seek_triggered"] is False
            and probe["verdict"]["codec_and_playback_ok"] is False
        ),
        "probe_two_cells": len(probe["cells"]) == 2
                           and {c["path"] for c in probe["cells"]} == {"local", "remote"},
        "probe_logs_present": all(v for v in logs.values()),
        "probe_no_race_evidence": all(
            ("openSource 2" in v and "-103" not in v and "Open codec for" not in v)
            for v in logs.values()
        ),
        "sample_moov_at_end": (
            SAMPLE.is_file()
            and SAMPLE.stat().st_size == probe["sample_bytes"]
            and probe["moov_offset"] < probe["sample_bytes"]
            and SAMPLE.read_bytes()[probe["moov_offset"] + 4:
                                    probe["moov_offset"] + 8] == b"moov"
        ),
        "p0_eof_wait_unchanged": all(s in code for s in (
            "repeat(20)", "Thread.sleep(100)", "if (cancelled) return",
            "delayed 2s or immediate",
        )),
        "no_p0_change_claimed": "F4 判 **BLOCKED**" in plan
                                and "P0 引擎舱代码零改动" in plan,
        "root_cause_documented": all(s in plan for s in (
            "OnWriteNetData", "normal mode", "~50ms", "5ms/轮", "-103",
            "faststart m4a", "Java 侧拿不到确定性完成信号",
        )),
        "ape_remote_not_a_carrier": (
            "不是 F4 竞态的复现载体" in plan
            and "ape×remote" in plan
            and matrix["overall_verdict"] == "PARTIAL"
        ),
        "candidate_plans_are_hypotheses": "待真机 A/B 的假设，不是已证实的修复" in plan,
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
        "docs_written_back": ("61.50" in read(DELIVERY) and "8.10" in read(PLAN17)),
        "no_credentials_in_evidence": not any(
            k in (read(PROBE) + read(PLAN) + read(OUT))
            for k in ('"password"', '"token"', '"username"', "OPENLIST_PASSWORD=")
        ),
    }

    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.50 / F4 根因实证与阻塞状态审计（只读、纯标准库、不连 adb）",
        "generated_by": "tools/l3_f4_eof_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "f4_status": "BLOCKED",
        "f4_status_reason": (
            "根因已钉死（normal mode 下 OnWriteNetData 写线程异步落 vfile ~50ms，"
            "Java 侧无落盘完成信号、P0 二进制不可改），但缺真实 moov-at-end 样本："
            "OpenList 无 m4a/aac、本机无编码器且外网不通、历史 G01 用 faststart 绕开竞态、"
            "F3 的 ape×remote 只是同走 onEnd 延迟路径、人工重排 fMP4 探针未触发尾部 seek。"
            "按先实证后改 P0 的纪律不施工，不伪造完成。"
        ),
        "root_cause": {
            "code": "UrlDataRequest.sink.onEnd 成功 EOF 固定 2s（20×100ms）分片等待",
            "hidden_race": "立即 onEnd → ReadFromFileNoLock 0 → ~5ms/轮重试烧完预算 → -103",
            "why": "native normal mode 的 OnWriteNetData GOT+lambda 异步任务落 vfile 约 50ms",
            "java_signal": "JNI 只有 Begin/Data/End/Start/Stop，无落盘完成回调",
            "p0_constraint": "native so 原样复用不可改 → 严格事件驱动根治不可达",
        },
        "unblock_conditions": [
            "提供 1 个 faststart=false（moov 在末尾）的真实 .m4a/.mp4 音频样本（几 MB 即可）",
            "或恢复外网 / 提供可用 ffmpeg 二进制以生成该样本",
            "或用户明确接受「只做自适应等待改良、不追求根治」（候选 3）",
        ],
        "verification_criteria": "自动进曲连续 3 首无 -103、无释放后回调；onEnd 不再固定 2s 盲等",
        "honest_notes": [
            "本批 P0 引擎舱零改动：UrlDataRequest / RangeFetcher / EngineAdapter / 20 so / 指纹全部原样。",
            "f3_m4a.m4a 是 F4 探针专用人工重排 fMP4 片段，未纳入 F3 矩阵样本清单。",
            "F3 的 ape×remote FAIL 根因是截断片段缺 APE 尾部 seek table，不是 F4 竞态复现。",
            "A3 仍 G09 PARTIAL、舱不冻结；F4 后仍需 F5 才回 P2→P3→P4。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] F4 status=%s / 探针复现载体=%s" %
          (data["f4_status"], probe["verdict"]["f4_repro_carrier"]))
    if failed:
        print("[FAIL] §61.50 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.50 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
