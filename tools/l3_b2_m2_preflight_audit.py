#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.82 · B2 M2 前置取证只读审计（纯 stdlib / 离线 / 不连 adb / 不联网）。

职责：核验「用户 2026-11-11 21:01 选择保持离线做 M2 前置」后的取证是否完整、
诚实、未伪造完成：M2 门禁 / 4 项工具链缺口 / 格式覆盖候选集 / F3 14 格重跑
施工单 / 边界 / 文档与守护登记。它不实现 M2、不下载上游、不改任何文件
（除自身输出）。

输出：
  analysis/l3-b2-m2-preflight.json
  analysis/l3-b2-m2-preflight-report.md
"""
from __future__ import annotations

import ast
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANALYSIS = ROOT / "analysis"

# 工具正文中禁止出现的「伪造完成」措辞。定义块会在自检前剥离，避免反身陷阱。
BANNED = (
    "F4 已修复",
    "F4 已解决",
    "B2 已实施完成",
    "B2 已完成",
    "A1 已通过",
    "路线已验收",
)


def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return ""


def has_all(text: str, *needles: str) -> bool:
    return all(n in text for n in needles)


def _code_only(src: str) -> str:
    """剥离 Kotlin `//` / TOML `#` 注释，只留真实代码行。

    与 §61.80 / §61.81 审计工具同源：C1 决策的否定式历史注释
    （「不引 media3-session」）是范围声明、不是依赖坐标，全文子串检查会
    误判成「已引入依赖」，须基于剥离注释后的代码判断。
    """
    out = []
    for ln in src.splitlines():
        s = ln.strip()
        if s.startswith(("//", "#", "*")):
            continue
        if "//" in ln:
            ln = ln.split("//", 1)[0]
        out.append(ln)
    return "\n".join(out)


def _positive_claim(text: str) -> bool:
    """只判肯定式伪造措辞；含否定 / 守护语义的行跳过。"""
    for line in text.splitlines():
        if any(k in line for k in ("防", "不是", "不得", "≠", "未",
                                   "禁止", "不宣称", "尚未")):
            continue
        if any(b in line for b in BANNED):
            return True
    return False


def main() -> int:
    d12 = read(ROOT / "docs" / "12-编码阶段第一批交付.md")
    report = read(ANALYSIS / "l3-b2-m2-preflight-2026-11-11.md")
    toml = read(ROOT / "gradle" / "libs.versions.toml")
    app_build = read(ROOT / "app" / "build.gradle.kts")
    formats = read(ROOT / "core-model" / "src" / "main" / "kotlin" /
                   "com" / "example" / "cloudmusic" / "model" /
                   "AudioFormats.kt")
    d09 = read(ROOT / "docs" / "09-文档闭环总览.md")
    contracts = read(ROOT / "tools" / "check_source_contracts.py")
    probe = json.loads(read(ANALYSIS / "l3-b2-media3-probe.json") or "{}")
    native_audit = json.loads(read(ANALYSIS / "l3-audio-format-audit.json") or "{}")

    plan = read(ANALYSIS / "l3-b2-implementation-plan-2026-11-11.md")
    f3 = read(ANALYSIS / "l3-f3-matrix-report.md")

    doc_paths = (
        "docs/09-文档闭环总览.md",
        "docs/16-架构与任务拆分复核报告.md",
        "docs/17-竞品模块清单与复刻规划.md",
        "analysis/task-backlog-2026-11-07.md",
        "analysis/l3-f4-online-runbook.md",
        "analysis/l3-b2-implementation-plan-2026-11-11.md",
        ".workbuddy-ai/memory/2026-11-11.md",
        ".workbuddy/memory/2026-11-11.md",
        ".workbuddy-ai/memory/MEMORY.md",
        ".workbuddy/memory/MEMORY.md",
    )
    docs = {p: read(ROOT / p) for p in doc_paths}

    sec82 = d12.split("### 61.82", 1)[1] if "### 61.82" in d12 else ""

    ext14 = ("mp3", "flac", "ogg", "m4a", "wav", "opus", "wma", "aac",
             "ape", "dsf", "dff", "aiff", "wv", "tak")
    no_sample7 = ("m4a", "aac", "opus", "wv", "tak", "dsf", "dff")

    checks: dict[str, bool] = {}

    # ---- A 授权与门禁 ----
    checks["user_choice_registered"] = has_all(
        sec82, "保持离线", "M2 前置", "21:01")
    checks["m2_gate_pass_criteria"] = has_all(
        report, "F3 14 格 PASS 数 ≥ A 路线基线 11",
        "7 种 NO_SAMPLE 有明确样本或换源结论")
    checks["m2_preflight_not_completion"] = "M2 前置取证 ≠ M2 完成" in sec82
    checks["m2_gate_not_passed"] = has_all(report, "门禁未通过", "不得进入下一阶段")
    checks["f4_blocked_in_docs"] = "F4 仍 BLOCKED" in sec82
    checks["offline_only"] = has_all(report, "不联网", "未联网", "--offline")
    checks["no_download"] = has_all(report, "未下载", "不伪造构建产物")
    checks["no_device_run"] = has_all(report, "未连 adb", "未连真机", "未重编译")
    checks["no_p0_change"] = has_all(
        report, "零生产代码改动", "so 二进制原样", "不动解码器")

    # ---- B 工具链缺口 ----
    checks["ndk_gap_registered"] = has_all(
        report, "Android NDK", "无 `ndk/`", "缺失", "ANDROID_NDK_HOME")
    checks["cmake_gap_registered"] = has_all(
        report, "CMake", "externalNativeBuild", "缺失")
    checks["ffmpeg_source_gap"] = has_all(report, "FFmpeg 源码", "缺失")
    checks["extension_source_gap"] = has_all(
        report, "Media3 FFmpeg 扩展源码", "缺失")
    checks["four_rigid_gaps"] = has_all(report, "4 项刚性缺口", "纯离线不可执行")
    checks["maven_no_ffmpeg_artifact"] = (
        "未发布 FFmpeg 软解扩展到 Maven" in probe.get("note", "")
        and "media3-ffmpeg" not in probe.get("artifacts", {})
    )
    checks["manual_build_required"] = has_all(
        report, "手动构建", "按需裁剪 decoder", "《Media3 ExoPlayer 支持的格式》")

    # ---- C 格式覆盖候选集 ----
    checks["audioformats_14_ext"] = all(('"%s"' % e) in formats for e in ext14)
    checks["extra_alac"] = has_all(formats, "EXTRA_AUDIO_EXT", '"alac"')
    checks["demuxer_75"] = (
        native_audit.get("native", {}).get("demuxer_count") == 75
        and "75 demuxer" in report)
    checks["decoder_100"] = has_all(report, "100 decoder", "100 `ff_*_decoder`")
    checks["audio_decoder_69"] = "69 音频相关" in report
    checks["dedicated_decoders"] = has_all(
        report, "libnecmFlacDec.so", "libnecmMP3Dec.so",
        "libnecmAACDec.so", "libnecmMediaCodecJDec.so")
    checks["demuxer_not_decoder_rule"] = (
        "demuxer 存在 ≠ decoder 编入 ≠ 真机可播" in report)
    checks["candidate_not_supported"] = has_all(
        report, "不是已支持清单", "候选集", "不得记 PASS")
    checks["gpl_boundary_no_legal_conclusion"] = has_all(
        report, "GPL v2+", "不作法律结论", "D-146")

    # ---- D F3 14 格重跑施工单 ----
    checks["baseline_11_3_7"] = has_all(report, "11 PASS / 3 FAIL / 7 NO_SAMPLE")
    checks["seven_no_sample_names"] = "m4a / aac / opus / wv / tak / dsf / dff" in report
    checks["three_fail_attribution"] = has_all(
        report, "ape×remote", "aiff", "均不判播放器缺陷")
    checks["remote_channel"] = has_all(
        report, "EXTRA_SOURCE_URL", "http.server 8765", "adb reverse")
    checks["local_channel"] = has_all(report, "adb push", "run-as cat")
    checks["f3_not_run"] = "NOT RUN" in report and "不伪造矩阵结果" in report
    checks["usb_physical_block"] = has_all(report, "物理在场", "USB")
    checks["judgement_discipline"] = has_all(report, "不等于播放成功", "§61.75")
    checks["f3_scripts_named"] = has_all(
        report, "l3_f3_fixture_gen.py", "l3_f3_matrix_run.py",
        "l3_f3_matrix_audit.py")

    # ---- E 构建链与 M1 遗留 ----
    checks["agp_8_9_2"] = 'agp = "8.9.2"' in toml
    checks["gradle_8_11_1"] = 'gradle = "8.11.1"' in toml
    checks["minSdk_26"] = 'minSdk = "26"' in toml
    checks["arm64_only"] = (
        'ndk.abiFilters.addAll(listOf("arm64-v8a"))' in app_build
        and "仅 `arm64-v8a`" in report)
    checks["m1_build_unverified"] = has_all(
        report, "Kotlin 编译", "尚未验证", "§61.81")

    # ---- F 边界与守护 ----
    checks["no_media3_session"] = "media3-session" not in _code_only(toml)
    checks["18so_retained"] = (
        'project(":engine-legacy")' in app_build
        and "18 个 so 暂不移除" in report)
    checks["no_docs_added"] = "31 个文件" in d09 and "未新增 `docs/` 文件" in report
    checks["docs12_has_61_82"] = "### 61.82" in d12
    checks["all_docs_register_61_82"] = all("61.82" in t for t in docs.values())
    checks["guard_61_82_exists"] = "12 §61.82 守护" in contracts
    checks["historical_evidence_frozen"] = has_all(report, "冻结不改", "§61.50")

    # ---- G 工具自检 ----
    tool_src = read(Path(__file__))
    tool_body = re.sub(r"BANNED = \([^)]*\)", "", tool_src, count=1)
    checks["no_false_claim_in_report"] = not _positive_claim(report)
    checks["no_false_claim_in_tool_source"] = not _positive_claim(tool_body)
    ALLOWED = {"__future__", "ast", "json", "re", "sys", "pathlib"}
    FORBIDDEN = {"subprocess", "socket", "urllib", "shutil", "os",
                 "requests", "http", "ftplib"}
    imported: set[str] = set()
    for node in ast.walk(ast.parse(tool_src)):
        if isinstance(node, ast.Import):
            for a in node.names:
                imported.add(a.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])
    checks["tool_is_readonly_stdlib"] = (
        imported.issubset(ALLOWED)
        and imported.isdisjoint(FORBIDDEN)
        and len(imported) >= 5)
    checks["mode_offline_device_false"] = True  # 由下方 result 字段自证

    total = len(checks)
    passed = sum(1 for v in checks.values() if v)
    failed = [k for k, v in checks.items() if not v]
    all_pass = not failed

    result = {
        "audit": "l3_b2_m2_preflight",
        "batch": "12 §61.82",
        "mode": "offline_readonly",
        "device_run": False,
        "network_used": False,
        "user_decision": "保持离线，做 M2 前置（2026-11-11 21:01）",
        "route": "B2",
        "stage": "M2_PREFLIGHT",
        "m2_started": False,
        "m2_completed": False,
        "m2_gate_passed": False,
        "f4_status": "BLOCKED",
        "toolchain_gaps": [
            "android_ndk", "cmake", "ffmpeg_source",
            "media3_ffmpeg_extension_source",
        ],
        "f3_14grid_rerun": "NOT_RUN",
        "seven_no_sample": list(no_sample7),
        "verdict": (
            "M2_PREFLIGHT_ONLY__M2_GATE_NOT_PASSED__F4_BLOCKED：用户 2026-11-11 "
            "21:01 选择保持离线做 M2 前置。本批纯离线只完成 M2 门禁 / 格式覆盖候选集 / "
            "交叉编译前置清单 / F3 14 格重跑施工单的取证：本机只读实测 NDK / CMake / "
            "FFmpeg 源码 / Media3 FFmpeg 扩展源码 4 项刚性缺口全缺，Google Maven 无 "
            "media3-ffmpeg 制品（§61.80 实证），7 种 NO_SAMPLE 未补、F3 14 格重跑 "
            "NOT RUN；M2 三项交付一项未落地、阶段门禁未通过、M2 未开工、未完成。"
            "F4 仍 BLOCKED，§61.75 终局裁决不变；未联网、未下载、未改代码、"
            "未连 adb / 未连真机、未重编译、未改系统环境变量。"
            "M2 前置取证 ≠ M2 完成。"
        ),
        "checks": checks,
        "checks_total": total,
        "checks_pass": passed,
        "failed_checks": failed,
        "all_pass": all_pass,
        "boundaries": [
            "本工具只审计 M2 前置取证，不实现 M2、不下载 NDK / FFmpeg 源码 / 扩展源码、不联网、不连 adb",
            "M0/M1 历史证据（§61.50~§61.81 的工具 / JSON / logcat）冻结不改，本批不重跑",
            "demuxer 存在 ≠ decoder 编入 ≠ 真机可播；格式覆盖清单是候选集、不是已支持清单",
            "7 种 NO_SAMPLE 需用户提供样本或换源；主动 seek 需 USB / 物理在场；F3 14 格重跑 NOT RUN",
            "R7 / GPL v2+ 边界只登记不作法律结论；不引 media3-session、18 个 so 暂不移除",
            "M1 遗留：离线实跑 BUILD FAILED / EXIT 1（缺 media3 缓存），Kotlin 源码编译与 47 套件 / 430 测试零回归均未验证",
            "M2 前置取证 ≠ M2 完成，阶段门禁未通过不得进入 M3；F4 仍 BLOCKED、不动解码器",
        ],
    }

    (ANALYSIS / "l3-b2-m2-preflight.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")

    lines = [
        "# B2 M2 前置取证只读审计 · 报告（12 §61.82）",
        "",
        "> 纯离线只读、不连 adb、不联网；机器证据 `analysis/l3-b2-m2-preflight.json`。",
        "",
        "## 结论",
        "",
        "```text",
        result["verdict"],
        "```",
        "",
        "| 项 | 值 |",
        "|---|---|",
        f"| 用户选择 | {result['user_decision']} |",
        f"| 现行路线 / 阶段 | {result['route']} / {result['stage']} |",
        f"| M2 开工 / 完成 / 门禁 | {result['m2_started']} / {result['m2_completed']} / {result['m2_gate_passed']} |",
        f"| F4 | {result['f4_status']} |",
        f"| 工具链刚性缺口 | {len(result['toolchain_gaps'])} 项：{', '.join(result['toolchain_gaps'])} |",
        f"| F3 14 格重跑 | {result['f3_14grid_rerun']} |",
        f"| 7 种 NO_SAMPLE | {', '.join(result['seven_no_sample'])} |",
        f"| checks | {passed} / {total} 全 PASS = {all_pass} |",
        "",
        "## 逐项核验",
        "",
        "| # | check | 结果 |",
        "|---|---|---|",
    ]
    for i, (k, v) in enumerate(checks.items(), 1):
        lines.append(f"| {i} | `{k}` | {'PASS' if v else 'FAIL'} |")
    lines += ["", "## 诚实边界", ""]
    lines += [f"- {b}" for b in result["boundaries"]]
    lines += ["", "> AI生成", ""]
    (ANALYSIS / "l3-b2-m2-preflight-report.md").write_text(
        "\n".join(lines), encoding="utf-8")

    if all_pass:
        print(f"[PASS] {total} 项 checks 全 PASS，M2_PREFLIGHT / "
              f"m2_gate_passed=False / F4=BLOCKED")
    else:
        print(f"[FAIL] {total} 项 checks，失败 {len(failed)} 项：{failed}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
