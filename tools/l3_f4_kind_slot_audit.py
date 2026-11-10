#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.52 / F4 卡点根因定位 · 离线证据审计（只读、纯标准库、不连 adb）。

钉死「OnDataRequestEnd 的 kind 槽」假设的证据链，防伪造 F4 完成、
防把假设写成已证实根因、防 P0 代码被提前改动、防基线漂移。
产出 analysis/l3-f4-kind-slot-audit.json。
"""
from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "analysis" / "l3-f4-kind-slot-rootcause.md"
OUT = ROOT / "analysis" / "l3-f4-kind-slot-audit.json"
DELIVERY = ROOT / "docs/12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs/17-竞品模块清单与复刻规划.md"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
URL_REQ = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
F3_LOGS = ROOT / "analysis" / "l3-f3-logs"
F4_LOGS = ROOT / "analysis" / "l3-f4-logs"
AB_A = ROOT / "analysis" / "l3-f4-ab-a.json"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
CSV = ROOT / "docs/assets-mapping.csv"
DOCS = ROOT / "docs"
RES_DIR = ROOT / "app/src/main/res"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def ts(line: str) -> datetime | None:
    m = re.match(r"(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})", line)
    if not m:
        return None
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f")


def end_then_start_gap(path: Path) -> float | None:
    """onEnd id=2 之后第一条 Start 的时间差（毫秒）；负/缺失返回 None。"""
    t_end = None
    for line in read(path).splitlines():
        if "CM-DataReq" not in line:
            continue
        if t_end is None and re.search(r"onEnd id=2 error=null", line):
            t_end = ts(line)
        elif t_end is not None and "Start id=" in line:
            t_start = ts(line)
            if t_start:
                return (t_start - t_end).total_seconds() * 1000
    return None


def f3_remote_summary() -> dict:
    out = {}
    for p in sorted(F3_LOGS.glob("*-remote.txt")):
        t = read(p)
        out[p.stem.replace("-remote", "")] = {
            "starts": [s[0] for s in re.findall(r"Start id=(\d+) offset=\d+ length=\d+", t)],
            "ends": re.findall(r"onEnd id=(\d+) error=\S+", t),
            "codec": (re.findall(r"Open codec for (\S+)", t) or [None])[-1],
            "write": "Write start play nErr:0" in t,
        }
    return out


def main() -> int:
    required = (REPORT, DELIVERY, PLAN17, OVERVIEW, URL_REQ, AB_A,
                SETTINGS, MANIFEST, CSV)
    missing = [str(p.relative_to(ROOT)).replace("\\", "/") for p in required
               if not p.is_file()]
    if missing:
        print("[FAIL] §61.52 审计缺少文件：%s" % missing)
        return 1

    report = read(REPORT)
    code = read(URL_REQ)
    d12 = read(DELIVERY)
    f3 = f3_remote_summary()
    ab = json.loads(AB_A.read_text(encoding="utf-8"))
    gaps = {
        "baseline_25s": end_then_start_gap(F4_LOGS / "fast-remote-25s.txt"),
        "variant_a": end_then_start_gap(F4_LOGS / "ab-a-remote.txt"),
    }
    recheck = read(F4_LOGS / "recheck-m4a-remote.txt")
    chain = re.findall(r"onEnd id=(\d+) error=\S+", recheck)

    checks = {
        "native_kind_semantics_documented": (
            "OnNetSourceDataDone" in d12
            and "PreloadDoneTaskOnWriteThread" in d12
            and "OnFlushNetData" in d12
            and "kind==2" in d12
        ),
        "source_still_passes_requestid": (
            "OnDataRequestEnd(requestId, error ?: \"\")" in code
            and "repeat(20)" in code
            and "NATIVE_DONE_KIND_FLUSH" not in code
        ),
        "data_packet_kind_already_calibrated": "NATIVE_DATA_KIND_NORMAL = 1L" in code,
        "baseline_gap_1ms": gaps["baseline_25s"] is not None
                            and 0 <= gaps["baseline_25s"] <= 100,
        "variant_a_gap_2ms": gaps["variant_a"] is not None
                             and 0 <= gaps["variant_a"] <= 100,
        # 连接失败路径里 id=1 与 id=2 的 onEnd 交错，故只校验递增重试链存在。
        "recheck_retry_chain": len(chain) >= 4 and all(
            x in chain for x in ("2", "3", "4", "5")),
        "f3_crosscheck_multi_range_ok": all(
            f3.get(x, {}).get("codec") is not None
            and f3.get(x, {}).get("write") is True
            and f3.get(x, {}).get("ends") == ["1"]
            for x in ("flac", "wav")
        ),
        "fmp4_is_only_end_id2_case": (
            "2" in (re.findall(r"onEnd id=(\d+) error=\S+",
                               read(F4_LOGS / "fast-remote-25s.txt")))
        ),
        "ab_excludes_delay": (
            ab.get("codec") is None and ab.get("write") is False
            and ab.get("variant") == "A-immediate"
        ),
        "hypothesis_not_claimed_as_proven": all(s in report for s in (
            "高置信假设，待真机 A/B 验证", "不是已证实的根因", "不据此收口 F4",
            "可证伪预测", "不强行推广",
        )),
        "ape_and_aiff_excluded": (
            "不解释 `ape×REMOTE`" in report and "不解释 `aiff` 两格" in report
        ),
        "no_code_change": (
            "OnDataRequestEnd(requestId, error ?: \"\")" in code
            and "AB-A immediate" not in code
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
        "docs_written_back": ("61.52" in d12 and "8.12" in read(PLAN17)),
        # 防凭据落盘：只匹配键名 / 赋值特征，绝不在工具里写任何明文口令。
        "no_credentials_in_evidence": (
            not any(k in report for k in ('"password"', '"token"', '"username"',
                                          "OPENLIST_PASSWORD=", "Authorization:"))
            and not re.search(
                r"(?i)password\s*=\s*['\"][A-Za-z0-9_!@#$%^&*]{6,}['\"]",
                read(ROOT / "tools" / "l3_f3_fixture_gen.py"))
        ),
    }

    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.52 / F4 卡点根因离线证据审计（只读、纯标准库、不连 adb）",
        "generated_by": "tools/l3_f4_kind_slot_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "hypothesis": {
            "statement": ("OnDataRequestEnd 首参是 OnNetSourceDataDone 的 kind 槽；"
                          "传 requestId 使 id=2 被解释为完成标记 kind=2 → "
                          "PreloadDoneTaskOnWriteThread → 读侧立即重发同一区间"),
            "confidence": "高置信假设（静态反汇编 + 日志时序相关性），未经真机 A/B",
            "falsifiable_prediction": ("把首参恒传 1L（flush 语义）后，"
                                       "f3_m4a_fast.m4a × REMOTE 应出现 Open codec for aac"),
            "candidate_fix": "新增 NATIVE_DONE_KIND_FLUSH = 1L；requestId 仅用于日志",
            "f4_status": "BLOCKED（假设未验证、代码未改）",
        },
        "evidence": {
            "end_to_start_gap_ms": gaps,
            "recheck_retry_chain_ids": chain[:6],
            "f3_remote_summary": f3,
        },
        "unblock_condition": ("不再需要用户提供样本：设备重新上线后做一次 "
                              "OnDataRequestEnd kind 槽 A/B，并复跑 F3 14 格 + "
                              "自动进曲 3 首判据"),
        "honest_notes": [
            "本批零代码改动、零设备操作（设备已离线）；不把假设写成已证实根因。",
            "不解释 ape×remote（无 onEnd id=2）与 aiff 两格（openSource 2），不强行推广。",
            "§59.2 记 exp6 两条路径读侧均正常收尾，是假设的已知反例，须由 A/B 裁决。",
            "A3 仍 G09 PARTIAL、舱不冻结；F4 后仍需 F5 才回 P2→P3→P4。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] onEnd id=2 → Start 间隔：基线 %s ms / 变体A %s ms；重试链 %s"
          % (gaps["baseline_25s"], gaps["variant_a"], chain[:5]))
    if failed:
        print("[FAIL] §61.52 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.52 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
