#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.80 · B2 路线切换只读审计（纯 stdlib / 离线 / 不连 adb）。

职责：核验「用户 2026-11-11 17:44 拍板切换 B2」这一**决策落地**是否完整、
诚实、未伪造完成。它不实现 B2、不引入依赖、不换绑、不改任何文件（除自身输出）。

核验分组：
  A 决策与授权 / B B2 定义一致 / C 架构冲突登记 / D 前置实证
  E 门面与换绑点 / F 施工单完整性 / G F4 状态与防伪造
  H 文档与守护 / I 工具自检

输出：
  analysis/l3-b2-route-switch-audit.json
  analysis/l3-b2-route-switch-audit-report.md
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


def jload(p: Path) -> dict:
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeDecodeError):
        return {}


def has_all(text: str, *needles: str) -> bool:
    return all(n in text for n in needles)


def main() -> int:
    plan = read(ANALYSIS / "l3-b2-implementation-plan-2026-11-11.md")
    eval_doc = read(ANALYSIS / "l3-playback-route-evaluation-2026-11-11.md")
    probe = jload(ANALYSIS / "l3-b2-media3-probe.json")
    a1_verdict = jload(ANALYSIS / "l3-f4-a1-bypass-verdict.json")
    acceptance = jload(ANALYSIS / "l3-f4-acceptance-final.json")
    bypass = jload(ANALYSIS / "l3-f4-bypass-stream-audit.json")
    rollback = jload(ANALYSIS / "l3-f4-wiring-rollback-audit.json")
    hist_wiring = jload(ANALYSIS / "l3-f4-level5-wiring-audit.json")

    d12 = read(ROOT / "docs" / "12-编码阶段第一批交付.md")
    d09 = read(ROOT / "docs" / "09-文档闭环总览.md")
    d16 = read(ROOT / "docs" / "16-架构与任务拆分复核报告.md")
    d17 = read(ROOT / "docs" / "17-竞品模块清单与复刻规划.md")
    d02 = read(ROOT / "docs" / "02-产品架构文档.md")
    backlog = read(ANALYSIS / "task-backlog-2026-11-07.md")
    runbook = read(ANALYSIS / "l3-f4-online-runbook.md")

    settings = read(ROOT / "settings.gradle.kts")
    toml = read(ROOT / "gradle" / "libs.versions.toml")
    engine_api = read(ROOT / "engine-api" / "src" / "main" / "kotlin" / "com" /
                      "example" / "cloudmusic" / "engine" / "api" / "EngineApi.kt")
    bindings = read(ROOT / "core-di" / "src" / "main" / "kotlin" / "com" /
                    "example" / "cloudmusic" / "di" / "AppBindings.kt")
    adapter = read(ROOT / "engine-legacy" / "src" / "main" / "java" / "com" /
                   "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt")
    contracts = read(ROOT / "tools" / "check_source_contracts.py")

    mem_main = read(ROOT / ".workbuddy-ai" / "memory" / "2026-11-11.md")
    mem_copy = read(ROOT / ".workbuddy" / "memory" / "2026-11-11.md")
    mem_long = read(ROOT / ".workbuddy-ai" / "memory" / "MEMORY.md")
    mem_long_copy = read(ROOT / ".workbuddy" / "memory" / "MEMORY.md")

    build_files = "\n".join(
        read(p) for p in ROOT.rglob("build.gradle.kts")
    )

    checks: dict[str, bool] = {}

    # ---- A 决策与授权 ----
    decision_docs = "\n".join((d12, d09, d16, d17, backlog, runbook, plan,
                               mem_main, mem_copy, mem_long, mem_long_copy))
    checks["user_decision_registered"] = "切换 B2" in decision_docs
    checks["decision_timestamp_registered"] = "17:44" in decision_docs
    checks["a_route_stopped"] = has_all(plan, "A 路线停止", "停止 A")
    checks["no_a2_binary_patch"] = "不追加 A2 二进制补丁" in plan and "A2 二进制补丁" in eval_doc
    checks["a1_timebox_failed"] = (
        a1_verdict.get("timebox_passed") is False
        and "NO_SUSTAINED_PLAYBACK" in a1_verdict.get("verdict", "")
    )
    checks["decision_is_route_not_fix"] = has_all(
        plan, "换路线 ≠ 修复", "不是 F4 修复")
    checks["f4_evidence_becomes_history"] = (
        "历史证据" in plan or "历史档案" in plan)

    # ---- B B2 定义一致 ----
    checks["b2_definition_matches_evaluation"] = has_all(
        plan, "Media3/ExoPlayer", "FFmpeg 软解扩展", "自研缓存/Range",
        "门面不变")
    checks["evaluation_doc_intact"] = has_all(
        eval_doc, "放弃 `libncmplayerengine`", "Media3/ExoPlayer",
        "自研缓存/Range 重建播放内核", "上层 `IMusicEngine` 门面不变")
    checks["escape_hatch_in_settings"] = ":engine-opensource" in settings
    checks["escape_hatch_in_docs02"] = has_all(
        d02, ":engine-opensource", "上层零改动即可切换")
    checks["b1_b3_not_chosen"] = has_all(plan, "B1 不做", "B3 不做")
    checks["not_decoder_swap"] = has_all(plan, "不是「改 FFmpeg 解码」", "与解码器正交")

    # ---- C 架构冲突登记（7 条）----
    checks["c1_media_session_scope_limited"] = has_all(
        plan, "不引入 `media3-session` 作为主导", "只在播放内核层")
    checks["c2_decision26_registered"] = has_all(
        plan, "#26", "媒体会话层不引入；播放内核层按 B2 引入")
    checks["c3_p0_binding_evolution"] = has_all(
        plan, "禁止改 `EngineModule` 绑定", "单点换绑", "门面签名零改动")
    checks["c4_zero_new_dependency_evolution"] = has_all(
        plan, "零新增依赖", "实施批次起", "§61.42/§61.43")
    checks["c5_so_removal_deferred"] = has_all(
        plan, "移除属后续实施批次", "本批 so 二进制原样")
    checks["c6_settings_comment_evolution"] = has_all(
        plan, "路线 C", "B2 拍板后启用", "本批不改构建文件")
    checks["c7_audio_effect_downgrade"] = has_all(
        plan, "音效降级", "P4 长期挂账", "attachAudioEffect")

    # ---- D 前置实证 ----
    arts = probe.get("artifacts", {})
    checks["media3_probe_all_http_ok"] = (
        len(arts) >= 5
        and all(v.get("http_ok") is True for v in arts.values())
        and probe.get("errors") == []
    )
    checks["media3_selected_version_1_11_1"] = (
        probe.get("selected_version") == "1.11.1"
        and arts.get("media3-exoplayer", {}).get("latest_stable") == "1.11.1"
        and arts.get("media3-exoplayer", {}).get("total_versions") == 77
    )
    checks["ffmpeg_extension_manual_build"] = has_all(
        probe.get("note", ""), "手动构建", "按需裁剪")
    checks["gradle_baseline_compatible"] = has_all(
        toml, 'agp = "8.9.2"', 'compileSdk = "35"', 'minSdk = "26"',
        'kotlin = "2.1.0"', 'java = "17"')
    # 只检查「真实代码 / 配置行」：剥离 Kotlin(//) 与 TOML(#) 注释，避免
    # app/build.gradle.kts 里「不引入 Media3 / androidx.media」这类 C1 决策的
    # 否定式历史注释被子串检查误判为「已引入 Media3 依赖」。
    def _code_only(src: str) -> str:
        out = []
        for ln in src.splitlines():
            s = ln.strip()
            if s.startswith(("//", "#", "*")):
                continue
            if "//" in ln:
                ln = ln.split("//", 1)[0]
            out.append(ln)
        return "\n".join(out)

    toml_code = _code_only(toml)
    settings_code = _code_only(settings)
    build_code = _code_only(build_files)
    checks["no_media3_dependency_yet"] = (
        "media3" not in toml_code.lower()
        and "media3" not in settings_code.lower()
        and "media3" not in build_code.lower()
    )
    checks["plan_cites_probe"] = "l3-b2-media3-probe.json" in plan

    # ---- E 门面与换绑点 ----
    methods = (
        "fun prepare(", "fun attachDataSource(", "fun play(", "fun pause(",
        "fun stop(", "fun seek(", "fun setVolume(", "fun positionMs(",
        "fun durationMs(", "fun state(", "fun setQuality(",
        "fun attachAudioEffect(", "fun release(", "fun lastError(",
    )
    checks["engine_api_14_methods_intact"] = all(m in engine_api for m in methods)
    checks["engine_source_config_fields_intact"] = has_all(
        engine_api, "val trackId:", "val mimeHint:", "val expectedLength:",
        "val url:", "val localPath:", "val preferHardwareDecoder:",
        "val bufferMs:", "val autoSkipOnError:")
    checks["engine_module_still_engine_adapter"] = (
        "EngineAdapter().also" in bindings
        and "object EngineModule" in bindings)
    checks["engine_adapter_rollback_intact"] = (
        "§61.79" in adapter
        and "SetLoggerCallback(INMLogger()" not in adapter
        and "NMVirtualFileManager(it)" in adapter)

    # ---- F 施工单完整性 ----
    checks["plan_has_m0_to_m5"] = all(
        ("**M%d" % i) in plan or ("| **M%d" % i) in plan for i in range(6))
    checks["plan_has_method_mapping"] = has_all(
        plan, "prepare(source, config)", "attachDataSource(handle, ds)",
        "attachAudioEffect(handle, effectId)")
    checks["plan_has_range_design"] = has_all(
        plan, "RangeCacheDataSource", "不做私有 vfile")
    checks["plan_has_criteria_and_rollback"] = has_all(
        plan, "通过判据", "回退", "阶段门禁")
    checks["plan_has_user_confirmations"] = has_all(
        plan, "需用户后续确认", "音效降级范围", "NO_SAMPLE", "R7 / LGPL 重编",
        "media3-session")
    checks["plan_has_honest_boundary"] = has_all(
        plan, "F4 仍 BLOCKED", "B2 实施尚未开始", "零生产代码改动")

    # ---- G F4 状态与防伪造 ----
    checks["f4_still_blocked"] = (
        acceptance.get("f4_status") == "BLOCKED"
        and a1_verdict.get("f4_status") == "BLOCKED"
        and bypass.get("f4_status") == "BLOCKED"
        and rollback.get("f4_status") == "BLOCKED"
        and "F4 仍 BLOCKED" in d12
    )
    checks["a1_verdict_intact"] = (
        a1_verdict.get("checks_total") == 34
        and a1_verdict.get("all_pass") is True
        and a1_verdict.get("route_recommendation") == "B2_PENDING_USER_DECISION"
    )
    checks["rollback_state_intact"] = (
        rollback.get("rollback_state") == "ROLLED_BACK"
        and rollback.get("all_pass") is True
        and hist_wiring.get("all_pass") is True
    )
    # 只检查本批 §61.80 章节 + 施工单；守护描述 / 否定句按行跳过，避免把
    # 「防把点火写成 F4 已修复」这类历史守护文字误判为伪造完成。
    b2_section = d12.split("### 61.80", 1)[1] if "### 61.80" in d12 else ""
    claim_scope = "\n".join((b2_section, plan))

    def _false_claim(text: str) -> bool:
        for line in text.splitlines():
            if any(k in line for k in ("防", "不是", "不得", "≠", "未", "禁止", "不宣称")):
                continue
            if any(b in line for b in BANNED):
                return True
        return False

    checks["no_false_claim_in_docs"] = not _false_claim(claim_scope)
    checks["route_switch_not_marked_as_fix"] = (
        "不是 F4 修复" in b2_section)
    checks["production_code_unchanged_this_batch"] = (
        "media3" not in toml.lower()
        and 'include(":engine-opensource")' not in settings
        and "EngineAdapter().also" in bindings
    )

    # ---- H 文档与守护 ----
    checks["docs12_has_61_80"] = "### 61.80" in d12
    checks["docs17_has_8_40"] = "### 8.40" in d17
    checks["all_docs_register_61_80"] = all(
        "61.80" in t for t in (d09, d16, d17, backlog, runbook, eval_doc))
    checks["memory_registered"] = all(
        "61.80" in t for t in (mem_main, mem_copy, mem_long, mem_long_copy))
    checks["contracts_has_61_80_guard"] = "12 §61.80 守护" in contracts

    # ---- I 工具自检 ----
    tool_src = read(Path(__file__))
    # (1) 禁止词自检：剥离 BANNED 定义块（它本身就是被检查的禁用词清单，
    #     属合法出现），再按行跳过含否定 / 守护语义的行（历史守护注释、
    #     verdict 否定句），只把「肯定式」伪造完成措辞判为失败。
    #     这样检查代码里引用的禁用词字面串不会造成反身陷阱。
    tool_body = re.sub(r"BANNED = \([^)]*\)", "", tool_src, count=1)

    def _false_claim_lines(text: str) -> bool:
        for line in text.splitlines():
            if any(k in line for k in ("防", "不是", "不得", "≠", "未",
                                       "禁止", "不宣称", "尚未")):
                continue
            if any(b in line for b in BANNED):
                return True
        return False

    checks["no_false_claim_in_tool_source"] = not _false_claim_lines(tool_body)

    # (2) 只读 / 纯 stdlib 自检：改用 AST 解析真实 import 语句，而不是在
    #     源码文本里搜 "import subprocess" 这类字面串（那样检查代码本身
    #     就会命中自己，属反身陷阱）。白名单外或命中禁用模块即失败。
    ALLOWED_STDLIB = {"__future__", "ast", "json", "re", "sys", "pathlib"}
    FORBIDDEN_IMPORTS = {"subprocess", "socket", "urllib", "shutil",
                         "os", "requests", "http", "ftplib"}
    imported: set[str] = set()
    for node in ast.walk(ast.parse(tool_src)):
        if isinstance(node, ast.Import):
            for a in node.names:
                imported.add(a.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])
    checks["tool_is_readonly_stdlib"] = (
        imported.issubset(ALLOWED_STDLIB)
        and imported.isdisjoint(FORBIDDEN_IMPORTS)
        and len(imported) >= 5
    )

    total = len(checks)
    passed = sum(1 for v in checks.values() if v)
    failed = [k for k, v in checks.items() if not v]
    all_pass = not failed

    result = {
        "audit": "l3_b2_route_switch",
        "batch": "12 §61.80",
        "mode": "offline_readonly",
        "device_run": False,
        "user_decision": "切换 B2（2026-11-11 17:44）",
        "route": "B2",
        "route_switched": True,
        "a_route": "STOPPED__NO_A2_BINARY_PATCH",
        "implementation_started": False,
        "implementation_completed": False,
        "f4_status": "BLOCKED",
        "verdict": (
            "B2_ROUTE_SWITCH_DECISION_LANDED__IMPLEMENTATION_NOT_STARTED："
            "用户拍板后 A 路线停止（不追加 A2 二进制补丁），B2 成为现行技术路线；"
            "已产出实施施工单、Media3 1.11.1 依赖可达性实证、7 条架构决策冲突登记、"
            "§61.80 契约守护与全量文档闭环。本批零生产代码改动、零新增依赖、"
            "零模块启用、未换绑、未重编译重装、so 二进制原样。"
            "「切换 B2」是路线决策，不是 F4 修复、不是 B2 实施完成；"
            "§61.75 终局裁决不变，F4 仍 BLOCKED，实施从 M1 起分批推进。"
        ),
        "checks": checks,
        "checks_total": total,
        "checks_pass": passed,
        "failed_checks": failed,
        "all_pass": all_pass,
        "boundaries": [
            "本工具只审计决策落地，不实现 B2、不连 adb、不联网（联网实证由 l3-b2-media3-probe.json 单独固化）",
            "历史 F4 证据（§61.50~§61.79）转为历史档案，一字不改",
            "音效降级 / 7 种 NO_SAMPLE / R7 LGPL / 下载后播放语义 / media3-session 引入仍需用户后续确认",
            "工期为估算区间 + 置信度（中），不是承诺；法律部分不作结论",
        ],
    }

    out_json = ANALYSIS / "l3-b2-route-switch-audit.json"
    out_json.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# B2 路线切换只读审计 · 报告（12 §61.80）",
        "",
        "> 纯离线只读、不连 adb；机器证据 "
        "`analysis/l3-b2-route-switch-audit.json`。",
        "",
        "## 结论",
        "",
        "```text",
        result["verdict"],
        "```",
        "",
        "| 项 | 值 |",
        "|---|---|",
        f"| 用户拍板 | {result['user_decision']} |",
        f"| 现行路线 | {result['route']} |",
        f"| A 路线 | {result['a_route']} |",
        f"| B2 实施 | started={result['implementation_started']} / "
        f"completed={result['implementation_completed']} |",
        f"| F4 | {result['f4_status']} |",
        f"| checks | {passed} / {total} 全 PASS = {all_pass} |",
        "",
        "## 逐项核验",
        "",
        "| # | check | 结果 |",
        "|---|---|---|",
    ]
    for i, (k, v) in enumerate(checks.items(), 1):
        lines.append(f"| {i} | `{k}` | {'PASS' if v else 'FAIL'} |")
    lines += [
        "",
        "## 诚实边界",
        "",
    ]
    lines += [f"- {b}" for b in result["boundaries"]]
    lines += [
        "",
        "> AI生成",
        "",
    ]
    (ANALYSIS / "l3-b2-route-switch-audit-report.md").write_text(
        "\n".join(lines), encoding="utf-8")

    if all_pass:
        print(f"[PASS] {total} 项 checks 全 PASS，route=B2 / "
              f"implementation_started=False / F4=BLOCKED")
    else:
        print(f"[FAIL] {total} 项 checks，失败 {len(failed)} 项：{failed}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
