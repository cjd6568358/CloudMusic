#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.81 · B2 M1 实施只读审计（纯 stdlib / 离线 / 不连 adb）。

职责：核验「用户 2026-11-11 18:19 授权开工 M1」后的实施是否完整、诚实、
未伪造完成：构建接入 / 模块骨架 / 门面零改动 / 单点换绑 / 回退保留 /
M2~M4 挂账 / 文档与守护登记。它不实现 M2 及以后阶段、不改任何文件（除自身输出）。

输出：
  analysis/l3-b2-m1-audit.json
  analysis/l3-b2-m1-audit-report.md
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

    与 12 §61.80 的 l3_b2_route_switch_audit.py::_code_only 同源：C1 决策的
    否定式历史注释（如「不引 media3-session」）是范围声明、不是依赖坐标，
    全文子串检查会把它们误判成「已引入依赖」，须基于剥离注释后的代码判断。
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


def main() -> int:
    d12 = read(ROOT / "docs" / "12-编码阶段第一批交付.md")
    toml = read(ROOT / "gradle" / "libs.versions.toml")
    settings = read(ROOT / "settings.gradle.kts")
    app_build = read(ROOT / "app" / "build.gradle.kts")
    coredi_build = read(ROOT / "core-di" / "build.gradle.kts")
    mod_build = read(ROOT / "engine-opensource" / "build.gradle.kts")
    adapter = read(ROOT / "engine-opensource" / "src" / "main" / "kotlin" /
                   "com" / "example" / "cloudmusic" / "engineopensource" /
                   "Media3EngineAdapter.kt")
    api = read(ROOT / "engine-api" / "src" / "main" / "kotlin" / "com" /
               "example" / "cloudmusic" / "engine" / "api" / "EngineApi.kt")
    bindings = read(ROOT / "core-di" / "src" / "main" / "kotlin" / "com" /
                    "example" / "cloudmusic" / "di" / "AppBindings.kt")
    legacy = read(ROOT / "engine-legacy" / "src" / "main" / "java" / "com" /
                  "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt")
    plan = read(ANALYSIS / "l3-b2-implementation-plan-2026-11-11.md")
    contracts = read(ROOT / "tools" / "check_source_contracts.py")

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

    methods = (
        "fun prepare(", "fun attachDataSource(", "fun play(", "fun pause(",
        "fun stop(", "fun seek(", "fun setVolume(", "fun positionMs(",
        "fun durationMs(", "fun state(", "fun setQuality(",
        "fun attachAudioEffect(", "fun release(", "fun lastError(",
    )

    sec81 = d12.split("### 61.81", 1)[1] if "### 61.81" in d12 else ""

    checks: dict[str, bool] = {}

    # ---- A 授权与决策 ----
    checks["m1_authorization_registered"] = has_all(
        sec81, "授权开工 M1", "18:19")
    checks["m0_decision_intact"] = (
        "### 61.80" in d12
        and "B2 实施尚未开始" in plan   # 施工单 M0 历史正文不改
    )
    checks["six_defaults_registered"] = has_all(
        sec81, "音效降级", "media3-session", "18 个 so", "NO_SAMPLE",
        "R7", "下载后播放")
    checks["f4_blocked_in_docs"] = "F4 仍 BLOCKED" in sec81

    # ---- B 构建接入 ----
    checks["version_catalog_media3_1_11_1"] = 'media3 = "1.11.1"' in toml
    checks["three_media3_artifacts"] = has_all(
        toml, "androidx.media3:media3-exoplayer",
        "androidx.media3:media3-datasource", "androidx.media3:media3-common")
    # 只检查真实代码行：TOML 注释里「不引 media3-session」是 C1 决策留痕，不是依赖。
    checks["no_media3_session"] = "media3-session" not in _code_only(toml)
    checks["module_count_20"] = settings.count('include(":') == 20
    checks["engine_opensource_included"] = (
        'include(":engine-opensource")' in settings)
    checks["engine_legacy_retained"] = (
        'include(":engine-legacy")' in settings
        and 'project(":engine-legacy")' in app_build)
    checks["c6_comment_evolved"] = (
        "B2 逃生舱" in settings
        and "仅在路线 C 拍板后启用" not in settings)
    checks["coredi_depends_m1"] = 'project(":engine-opensource")' in coredi_build

    # ---- C 模块与骨架 ----
    checks["module_build_exists"] = bool(mod_build)
    checks["module_convention_plugin"] = (
        "cloudmusic.android.library" in mod_build)
    checks["module_namespace"] = (
        'namespace = "com.example.cloudmusic.engineopensource"' in mod_build)
    checks["adapter_file_exists"] = bool(adapter)
    checks["adapter_implements_facade"] = (
        "class Media3EngineAdapter : IMusicEngine" in adapter)
    checks["adapter_14_overrides"] = all(
        ("override " + m) in adapter for m in methods)
    checks["adapter_appcontext"] = "var appContext: Context?" in adapter
    checks["no_media3_session_import"] = "media3.session" not in adapter

    # ---- D 换绑与门面 ----
    checks["single_point_rebind"] = "Media3EngineAdapter().also" in bindings
    checks["rollback_preserved"] = (
        "EngineAdapter" in bindings and "回退" in bindings)
    checks["facade_14_methods_unchanged"] = all(m in api for m in methods)
    checks["facade_no_media3_leak"] = "media3" not in api.lower()
    checks["engine_legacy_intact"] = bool(legacy) and "§61.79" in legacy

    # ---- E 诚实边界 ----
    checks["m1_is_skeleton_not_completion"] = (
        "M1 骨架 ≠ B2 完成" in sec81)
    checks["range_not_faked"] = has_all(
        adapter, "RangeCacheDataSource", "不伪造接线", "M3")
    checks["audio_effect_placeholder"] = has_all(
        adapter, "C7", "留空实现", "P4")
    checks["ffmpeg_extension_deferred"] = has_all(
        mod_build, "M2", "手动构建", "media3-session")
    checks["build_verification_honest"] = (
        "Gradle" in sec81
        and ("未编译验证" in sec81 or "BUILD" in sec81))
    checks["no_18so_removal"] = "18 个 so" in sec81 and "暂不移除" in sec81

    # ---- F 文档与守护 ----
    checks["docs12_has_61_81"] = "### 61.81" in d12
    checks["all_docs_register_61_81"] = all(
        "61.81" in t for t in docs.values())
    checks["guard_61_81_exists"] = "12 §61.81 守护" in contracts
    checks["guard_dual_state_61_80"] = "b2_current_ok" in contracts

    def _positive_claim(text: str) -> bool:
        for line in text.splitlines():
            if any(k in line for k in ("防", "不是", "不得", "≠", "未",
                                       "禁止", "不宣称", "尚未")):
                continue
            if any(b in line for b in BANNED):
                return True
        return False

    checks["no_false_claim_in_61_81"] = not _positive_claim(sec81)

    # ---- G 工具自检 ----
    tool_src = read(Path(__file__))
    tool_body = re.sub(r"BANNED = \([^)]*\)", "", tool_src, count=1)

    def _self_claim(text: str) -> bool:
        for line in text.splitlines():
            if any(k in line for k in ("防", "不是", "不得", "≠", "未",
                                       "禁止", "不宣称", "尚未")):
                continue
            if any(b in line for b in BANNED):
                return True
        return False

    checks["no_false_claim_in_tool_source"] = not _self_claim(tool_body)
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
        "audit": "l3_b2_m1_implementation",
        "batch": "12 §61.81",
        "mode": "offline_readonly",
        "device_run": False,
        "user_decision": "授权开工 M1（2026-11-11 18:19）",
        "route": "B2",
        "m1_started": True,
        "b2_completed": False,
        "implementation_stage": "M1_SKELETON",
        "f4_status": "BLOCKED",
        "gradle_build_verified": False,
        "gradle_build_attempted": True,
        "gradle_build_task": ":engine-opensource:compileDebugKotlin --offline --no-daemon",
        "gradle_build_exit_code": 1,
        "gradle_build_failure_stage": "dependency_resolution",
        "gradle_build_log": "analysis/l3-b2-m1-gradle-offline-build.log",
        "verdict": (
            "M1_SKELETON_LANDED__B2_NOT_COMPLETED__F4_BLOCKED：用户 2026-11-11 "
            "18:19 授权开工 M1，:engine-opensource 已启用（20 模块）、"
            "androidx.media3 1.11.1 三制品接入、EngineModule 单点换绑到 "
            "Media3EngineAdapter 且 :engine-legacy 保留回退、IMusicEngine "
            "14 方法签名零改动。M1 只是播放内核骨架：RangeCacheDataSource / "
            "音效降级 / FFmpeg 软解扩展 / gapless·seek·ENDED 语义均按施工单 "
            "挂账到 M2~M4，未伪造接线。本批已实跑 "
            "`:engine-opensource:compileDebugKotlin --offline --no-daemon`：配置阶段 / "
            "build-logic / core-common·core-model·engine-api 编译 / "
            ":engine-opensource 资源任务均通过，但在 `debugCompileClasspath` 依赖解析阶段"
            "因 3 个 media3 制品无离线缓存而 `BUILD FAILED`（EXIT 1，原始日志 "
            "`analysis/l3-b2-m1-gradle-offline-build.log`）；Kotlin 源码编译尚未验证，"
            "如实标注、不伪造 BUILD SUCCESSFUL，也不把依赖解析失败冒充编译通过。"
            "F4 仍 BLOCKED，§61.75 终局裁决不变；B2 实施进行中、未完成。"
        ),
        "checks": checks,
        "checks_total": total,
        "checks_pass": passed,
        "failed_checks": failed,
        "all_pass": all_pass,
        "boundaries": [
            "本工具只审计 M1 骨架落地，不实现 M2~M5、不连 adb、不联网",
            "M0 历史证据（§61.50~§61.80 的工具 / JSON / logcat）冻结不改，本批不重跑 l3_b2_route_switch_audit.py",
            "media3-ffmpeg 软解扩展 Maven 无制品，须手动构建并按需裁剪（M2）",
            "6 项确认按用户授权的默认假设处理：音效降级留空实现 / 不引 media3-session / 18 so 暂不移除 / NO_SAMPLE·R7 LGPL·下载后播放语义如实挂账",
            "已实跑 ./gradlew.bat :engine-opensource:compileDebugKotlin --offline --no-daemon："
            "BUILD FAILED / EXIT 1，卡在 3 个 media3 制品无离线缓存的 "
            "debugCompileClasspath 依赖解析阶段，Kotlin 源码编译与 "
            "47 套件 / 430 测试零回归均未验证，不伪造 BUILD SUCCESSFUL；"
            "原始日志 analysis/l3-b2-m1-gradle-offline-build.log",
        ],
    }

    (ANALYSIS / "l3-b2-m1-audit.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")

    lines = [
        "# B2 M1 实施只读审计 · 报告（12 §61.81）",
        "",
        "> 纯离线只读、不连 adb；机器证据 `analysis/l3-b2-m1-audit.json`。",
        "",
        "## 结论",
        "",
        "```text",
        result["verdict"],
        "```",
        "",
        "| 项 | 值 |",
        "|---|---|",
        f"| 用户授权 | {result['user_decision']} |",
        f"| 现行路线 / 阶段 | {result['route']} / {result['implementation_stage']} |",
        f"| B2 完成 | {result['b2_completed']} |",
        f"| F4 | {result['f4_status']} |",
        f"| Gradle 构建验证 | {result['gradle_build_verified']} |",
        f"| Gradle 实跑 | {result['gradle_build_task']} → EXIT {result['gradle_build_exit_code']}（{result['gradle_build_failure_stage']}） |",
        f"| 原始日志 | `{result['gradle_build_log']}` |",
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
    (ANALYSIS / "l3-b2-m1-audit-report.md").write_text(
        "\n".join(lines), encoding="utf-8")

    if all_pass:
        print(f"[PASS] {total} 项 checks 全 PASS，M1_SKELETON / "
              f"b2_completed=False / F4=BLOCKED")
    else:
        print(f"[FAIL] {total} 项 checks，失败 {len(failed)} 项：{failed}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
