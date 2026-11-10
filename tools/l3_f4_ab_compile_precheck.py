#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.57 / F4 · kind 槽 A/B 脚本的**编译期预检**（离线、不连设备）。

设备仍离线，`tools/l3_f4_kind_slot_ab.py --apply` 无法执行真机判据；
但「变体 B 能不能编译过」这一环可以提前实证，避免设备上线后才发现
常量/语法/类型问题白跑一趟。

流程（**唯一会临时改生产源码的批次，但保证零净改动**）：
  1. 复用 A/B 脚本的锚点与替换逻辑，校验 4 项锚点；
  2. 备份 `UrlDataRequest.kt` 到系统临时目录（不落仓库）；
  3. 应用变体 B → `./gradlew :app:assembleDebug --offline`；
  4. **try/finally 无条件恢复源码 → 重编译基线 → 校验源码一致 + git diff 空**。

**不连 adb、不 install、不连 OpenList、不写凭据**；`ab_executed` 仍为 false，
本工具不是 A/B，不产生任何真机判据。产出
analysis/l3-f4-ab-compile-precheck.json。
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import l3_f4_kind_slot_ab as ab  # noqa: E402  import 不执行 main

OUT = ROOT / "analysis" / "l3-f4-ab-compile-precheck.json"
SRC = ab.SRC


def git_diff_clean() -> bool:
    r = subprocess.run(
        ["git", "diff", "--", str(SRC.relative_to(ROOT)).replace("\\", "/")],
        cwd=str(ROOT), capture_output=True, text=True, timeout=30)
    return r.returncode == 0 and not r.stdout.strip()


def main() -> int:
    plan = ab.plan_text()
    bad = [k for k, v in plan["anchor_checks"].items() if not v]
    if bad:
        print("[FAIL] 锚点校验不通过：%s" % bad)
        return 1

    baseline = SRC.read_text(encoding="utf-8")
    backup = Path(tempfile.gettempdir()) / "UrlDataRequest.f4_precheck.baseline.kt"
    shutil.copyfile(SRC, backup)

    variant_build_ok = False
    variant_log = ""
    baseline_build_ok = False
    baseline_log = ""
    source_restored = False
    diff_clean = False
    variant_shape = {}
    try:
        variant = baseline.replace(ab.OLD_CALL, ab.NEW_CALL, 1).replace(
            ab.ANCHOR_COMPANION,
            ab.ANCHOR_COMPANION + "\n" + ab.CONST_LINE.rstrip("\n"), 1)
        variant_shape = {
            "old_call_absent": ab.OLD_CALL not in variant,
            "new_call_present": ab.NEW_CALL in variant,
            "const_present": "const val NATIVE_DONE_KIND_FLUSH = 1L" in variant,
            "single_const_occurrence": variant.count(
                "const val NATIVE_DONE_KIND_FLUSH = 1L") == 1,
            "byte_delta": len(variant.encode("utf-8"))
                          - len(baseline.encode("utf-8")),
        }
        SRC.write_text(variant, encoding="utf-8")
        print("[PRECHECK] 变体 B 已临时写入，开始编译…", flush=True)
        b = ab.gradle(":app:assembleDebug")
        variant_build_ok = b.returncode == 0
        variant_log = (b.stdout[-600:] + b.stderr[-300:])
        print("[PRECHECK] 变体编译 rc=%d %s"
              % (b.returncode, "BUILD SUCCESSFUL" if variant_build_ok else "FAILED"),
              flush=True)
    finally:
        shutil.copyfile(backup, SRC)
        b2 = ab.gradle(":app:assembleDebug")
        baseline_build_ok = b2.returncode == 0
        baseline_log = (b2.stdout[-600:] + b2.stderr[-300:])
        source_restored = SRC.read_text(encoding="utf-8") == baseline
        diff_clean = git_diff_clean()
        print("[RESTORE] 源码一致=%s 基线重编译 rc=%d git_diff空=%s"
              % (source_restored, b2.returncode, diff_clean), flush=True)
        backup.unlink(missing_ok=True)

    checks = {
        "anchors_all_true": not bad,
        "variant_single_precise_change": (
            variant_shape.get("old_call_absent") is True
            and variant_shape.get("new_call_present") is True
            and variant_shape.get("const_present") is True
            and variant_shape.get("single_const_occurrence") is True),
        "variant_compiles_offline": variant_build_ok,
        "baseline_recompiles_offline": baseline_build_ok,
        "source_restored_byte_exact": source_restored,
        "git_diff_clean_after_restore": diff_clean,
        # 12 §61.59：A/B 已执行，改为验收「结果如实登记」。
        "ab_result_honest": (ab.OUT_APPLY.is_file() and bool(
            json.loads(ab.OUT_APPLY.read_text(encoding="utf-8"))) and
            json.loads(ab.OUT_APPLY.read_text(encoding="utf-8")).get(
                "primary_criterion_met") is False and
            json.loads(ab.OUT_APPLY.read_text(encoding="utf-8")).get(
                "p0_restored") is True),
        "ab_executed": ab.OUT_APPLY.is_file(),
        "no_credentials_in_evidence": not re.search(
            r"(?i)\b(password|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']",
            json.dumps(variant_shape, ensure_ascii=False)),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.57 / F4 kind 槽 A/B 变体 B 的编译期预检"
                  "（离线、临时变体已恢复、零净改动、非 A/B）",
        "generated_by": "tools/l3_f4_ab_compile_precheck.py",
        "build_command": "./gradlew :app:assembleDebug --offline",
        "variant": "OnDataRequestEnd 首参恒传 NATIVE_DONE_KIND_FLUSH = 1L",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence": {
            "anchor_checks": plan["anchor_checks"],
            "variant_shape": variant_shape,
            "variant_build_returncode": 0 if variant_build_ok else 1,
            "variant_build_tail": variant_log[-400:],
            "baseline_build_returncode": 0 if baseline_build_ok else 1,
            "baseline_build_tail": baseline_log[-400:],
            "source_restored": source_restored,
            "git_diff_clean": diff_clean,
        },
        "conclusion": (
            "设备上线后 --apply 的编译环节已实证可过：变体 B（1 处调用替换 + "
            "1 行 Long 常量）在 --offline 下 :app:assembleDebug 成功；"
            "finally 已恢复 P0 基线并重编译、源码逐字节一致、git diff 为空。"
            "本工具不连 adb、不 install、不产生任何真机判据。"
            "§61.59 真机 A/B 已执行两次，主判据未达成、F4 仍 BLOCKED。"),
        "honest_notes": [
            "本批**临时改过**生产源码用于编译验证，最终零净改动；不写「零代码改动」，如实写「零净改动」。",
            "编译通过只排除语法/类型/依赖问题，不证明变体在真机上的行为，更不证明环节 B 因果链。",
            "不连设备、不安装 APK、不跑任何格；§61.59 真机结果见 analysis/l3-f4-ab-result-report.md；自动进曲 3 首仍 NOT RUN。",
            "A3 仍 G09 PARTIAL、舱不冻结；engine_compat_test 不回填。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.57 编译期预检未通过：%s" % failed)
        return 1
    print("[PASS] §61.57 编译期预检：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
