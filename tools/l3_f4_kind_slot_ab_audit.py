#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.53 · F4 kind 槽 A/B 脚本审计（只读、纯标准库、不连 adb）。

校验 dry-run 计划与脚本安全设计：防提前改 P0、防伪造 A/B 结果、
防把未跑的连播判据写成 PASS、防基线漂移。
产出 analysis/l3-f4-kind-slot-ab-audit.json。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "tools" / "l3_f4_kind_slot_ab.py"
PLAN_JSON = ROOT / "analysis" / "l3-f4-kind-slot-ab-plan.json"
APPLY_JSON = ROOT / "analysis" / "l3-f4-kind-slot-ab.json"
REPORT = ROOT / "analysis" / "l3-f4-kind-slot-rootcause.md"
SRC = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
DELIVERY = ROOT / "docs/12-编码阶段第一批交付.md"
PLAN17 = ROOT / "docs/17-竞品模块清单与复刻规划.md"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
CSV = ROOT / "docs/assets-mapping.csv"
DOCS = ROOT / "docs"
RES_DIR = ROOT / "app/src/main/res"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
OUT = ROOT / "analysis" / "l3-f4-kind-slot-ab-audit.json"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def main() -> int:
    required = (TOOL, PLAN_JSON, REPORT, SRC, DELIVERY, PLAN17, OVERVIEW,
                SETTINGS, MANIFEST, CSV)
    missing = [str(p.relative_to(ROOT)).replace("\\", "/") for p in required
               if not p.is_file()]
    if missing:
        print("[FAIL] §61.53 审计缺少文件：%s" % missing)
        return 1

    tool = read(TOOL)
    plan = json.loads(PLAN_JSON.read_text(encoding="utf-8"))
    src = read(SRC)
    apply_result = json.loads(APPLY_JSON.read_text(encoding="utf-8")) \
        if APPLY_JSON.is_file() else None

    checks = {
        "dry_run_plan_exists": plan.get("anchor_checks") is not None
                                and all(plan["anchor_checks"].values()),
        "dry_run_zero_change": (
            'OLD_CALL' in tool and 'NEW_CALL' in tool
            and "args.apply" in tool
            and "if not args.apply:" in tool
        ),
        "variant_b_is_single_precise_change": (
            plan["variant_b_change"]["files_touched"] == 1
            and plan["variant_b_change"]["gradle_dependencies_added"] == 0
            and src.count('OnDataRequestEnd(requestId, error ?: "")') == 1
        ),
        "p0_baseline_untouched": (
            'OnDataRequestEnd(requestId, error ?: "")' in src
            and "repeat(20)" in src
            and "delayed 2s or immediate" in src
            and "NATIVE_DONE_KIND_FLUSH" not in src
        ),
        "unconditional_restore": (
            "finally:" in tool and "shutil.copyfile(backup, SRC)" in tool
            and "gradle(\":app:assembleDebug\")" in tool
        ),
        "primary_criterion_declared": "Open codec for aac" in plan["primary_criterion"]
                                      and "Write start play" in plan["primary_criterion"],
        "falsification_declared": ("id>=3" in plan["falsification"]
                                   and "同 offset" in plan["falsification"]),
        "regression_declared": "5 格" in plan["regression_criterion"],
        "auto_advance_not_faked": any(
            "NOT RUN" in s for s in plan["safety"])
            and (apply_result is None
                 or apply_result.get("auto_advance_3_tracks", "").startswith("NOT RUN")),
        # 12 §61.59：A/B 已执行，守护改为验收「结果如实登记」。
        "ab_executed_and_honest": (
            apply_result is not None
            and apply_result.get("primary_criterion_met") is False
            and apply_result.get("p0_restored") is True
            and apply_result.get("regression_all_pass") is True
            and "NOT RUN" in apply_result.get("auto_advance_3_tracks", "")),
        "honest_boundaries": all(s in read(REPORT) for s in (
            "高置信假设，待真机 A/B 验证", "不是已证实的根因")),
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
        "docs_written_back": ("61.53" in read(DELIVERY) and "8.13" in read(PLAN17)),
        # 防凭据落盘：只匹配键名 / 赋值特征，绝不在工具里写任何明文口令。
        "no_credentials_in_evidence": (
            not any(k in tool + read(PLAN_JSON) for k in (
                '"password"', '"token"', '"username"',
                "OPENLIST_PASSWORD=", "Authorization:"))
            and not re.search(
                r"(?i)password\s*=\s*['\"][A-Za-z0-9_!@#$%^&*]{6,}['\"]", tool)
        ),
    }

    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.53 / F4 kind 槽 A/B 脚本审计（只读、纯标准库、不连 adb）",
        "generated_by": "tools/l3_f4_kind_slot_ab_audit.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "status": {
            "script_ready": True,
            "dry_run_executed": True,
            "ab_executed": apply_result is not None,
            "f4_status": "BLOCKED（A/B 已执行，主判据未达成，待第二卡点定位）",
            "ab_verdict": "PARTIAL：环节 B 部分成立（消除重复请求）、非充分原因（仍无 codec）",
            "one_liner": "python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply",
        },
        "honest_notes": [
            "本批零生产代码改动、零设备操作；脚本默认 dry-run，--apply 才动 P0。",
            "--apply 的 try/finally 无条件恢复基线并重编译重装。",
            "自动进曲 3 首连播、seek、ENDED 未实现/未测，不能据此收口 F4。",
            "A3 仍 G09 PARTIAL、舱不冻结；F4 后仍需 F5 才回 P2→P3→P4。",
            "§61.59：A/B 已执行两次（25s/45s）+ 基线对照；主判据均未达成，不得写成 PASS 或 F4 已解决。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] 脚本就绪=%s / dry-run 已跑=%s / A/B 已跑=%s"
          % (data["status"]["script_ready"], data["status"]["dry_run_executed"],
             data["status"]["ab_executed"]))
    if failed:
        print("[FAIL] §61.53 审计未通过：%s" % failed)
        return 1
    print("[PASS] §61.53 审计：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
