#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.100 · 仓库同步后的「文档 ↔ 代码」对齐守护（纯离线只读）。

背景：2026-11-13 拉取 9375a247..1485ce0f 后发现，远程 11-12/11-13 的
B2 M2~M5、音效、容器 Extractor 与 reverse-engine Z 组工作只回写了
docs/12 与 backlog 的一部分，09/16/17 与本地长期记忆仍停在 §61.83，
且生产源码注释保留了 M1 时期的过期口径、最新提交错挂 §61.99。

本工具把「全量回写清单」变成可复跑的机器判据：
  1. 七个回写锚点全部在位（12 / 09 §2·§7 / 16 §5.3·§8 / 17 §8.x·末尾 /
     backlog 顶部 / 当日日志 / MEMORY.md）；
  2. 三处源码注释漂移已修正且带 §61.100 对齐标记；
  3. F4 双轨口径（B2 PASS 与 A 路线历史档案 BLOCKED）各自保留限定语；
  4. 章节编号断链、环境差异（NDK 未配置）、反伪证禁语如实登记。

纪律：纯 stdlib、只读源码与文档、不连 adb、不联网、不改历史正文与证据 JSON；
默认把结果写到 analysis/b2-realign-2026-11-13.{json,report.md}（新文件，
不覆盖任何历史证据）；--check 只打印不落盘。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_JSON = ROOT / "analysis" / "b2-realign-2026-11-13.json"
OUT_MD = ROOT / "analysis" / "b2-realign-2026-11-13.report.md"

D12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
D09 = ROOT / "docs" / "09-文档闭环总览.md"
D16 = ROOT / "docs" / "16-架构与任务拆分复核报告.md"
D17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
BACKLOG = ROOT / "analysis" / "task-backlog-2026-11-07.md"
MEMORY = ROOT / ".workbuddy-ai" / "memory" / "MEMORY.md"
DAILY = ROOT / ".workbuddy-ai" / "memory" / "2026-11-13.md"
BUILD_FILE = ROOT / "engine-opensource" / "build.gradle.kts"
ADAPTER = ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com" / \
    "example" / "cloudmusic" / "engineopensource" / "Media3EngineAdapter.kt"
RE_README = ROOT / "reverse-engine" / "README.md"


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return ""


def head() -> str:
    try:
        return subprocess.check_output(
            ["git", "log", "-1", "--format=%h"], cwd=ROOT,
            stderr=subprocess.DEVNULL, timeout=10,
        ).decode("ascii", "replace").strip()
    except (OSError, subprocess.SubprocessError):
        return "UNKNOWN"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只打印判定，不写文件")
    args = ap.parse_args()

    d12, d09, d16, d17 = (read(x) for x in (D12, D09, D16, D17))
    backlog, memory, daily = read(BACKLOG), read(MEMORY), read(DAILY)
    build_f, adapter, re_readme = read(BUILD_FILE), read(ADAPTER), read(RE_README)

    # 只取 §61.100 正式批次正文，避免命中历史章节里的同名词。
    m = re.search(r"### 61\.100 .*?(?=\n### 61\.|\Z)", d12, re.S)
    batch = m.group(0) if m else ""
    # §61.104 演进兼容：backlog 按批次倒序追加，任何固定行数窗口都会随
    # 后续批次失效（45 → 120 → …）。改为全文在位判据：只要 §61.100 段
    # 仍在 backlog 中即算回写链未断。历史证据 JSON 冻结不覆盖。
    backlog_top = backlog

    checks: list[dict] = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    # --- 1. 全量回写锚点 -------------------------------------------------
    add("docs/12 §61.100 正式批次在位", bool(batch),
        "### 61.100 章节存在" if batch else "缺失")
    add("docs/12 §61.100 登记同步区间", "9375a247" in batch and "1485ce0f" in batch,
        "拉取前后 HEAD 双端点登记")
    add("09 §2 现行口径速查已回写", "61.100" in d09[d09.find("## 2."):d09.find("## 3.")],
        "§2 速查表含本批")
    add("09 §7 结论已回写", "61.100" in d09[d09.find("## 7."):],
        "§7 结论含本批")
    add("16 §5.3 四线任务表已回写", "61.100" in d16[d16.find("### 5.3"):d16.find("## 6.")],
        "§5.3 含本批状态注")
    add("16 §8 验证记录已回写", "61.100" in d16[d16.find("## 8."):],
        "§8 含本批命令与结果")
    add("17 §8.x 播放内核线已回写", "8.44" in d17 and "61.100" in d17,
        "§8.44 与 §61.100 引用在位")
    # §61.104 演进兼容：docs/17 追加 §8.47/§8.48 后，固定末尾窗口不再含
    # §61.100 段；改为全文在位判据，语义仍是「回写链未断」。
    add("17 现行断点已回写", "61.100" in d17,
        "全文含 §61.100（§61.104 演进兼容）")
    add("backlog 已回写", "61.100" in backlog_top,
        "全文含第一百二十一批（§61.104 演进兼容）")
    add("当日工作日志已回写", "61.100" in daily,
        ".workbuddy-ai/memory/2026-11-13.md")
    add("长期记忆现行断点已回写", "61.100" in memory,
        "MEMORY.md §0 现行断点")

    # --- 2. 源码注释漂移修正 ---------------------------------------------
    stale_build = "attachAudioEffect 留空实现" in build_f
    stale_adapter = "按 C7 默认假设留空实现" in adapter
    wrong_ref = "// §61.99：不透传 mimeType" in adapter
    add("build.gradle.kts 旧 M1 口径已清除", not stale_build,
        "不得再声称 attachAudioEffect 留空（§61.93 已实现音效 P0）")
    add("Media3EngineAdapter KDoc 旧 C7 口径已清除", not stale_adapter,
        "不得再声称音效/质量接口留空")
    add("§61.99 错挂引用已清除", not wrong_ref,
        "§61.99 文档讲 LoudnessEvaluator，非 mimeType 修正")
    add("三处修正均带 §61.100 对齐标记",
        all("61.100" in x for x in (build_f, adapter, d12)),
        "对齐标记可追溯")

    # --- 3. F4 双轨口径 ---------------------------------------------------
    add("B2 线 F4 验收 PASS 在位", "F4-B2 正式验收 = PASS" in d12,
        "docs/12 §61.92 判据不回退")
    add("A 路线历史档案 BLOCKED 在位", "F4 仍 BLOCKED" in re_readme,
        "reverse-engine 复现的是 A 路线阻塞链，口径不冲突")
    add("§61.100 明示双轨口径", "双轨" in batch and "历史档案" in batch,
        "B2 PASS ≠ A 路线已修复")
    banned = ["全格式已支持", "level-5 已开启", "F4 已解决", "m4a 已支持"]
    hit = [b for b in banned if b in batch]
    add("反伪证禁语未回潮", not hit, "禁语命中=%s" % (hit or "[]"))

    # --- 4. 差异登记完整性 -------------------------------------------------
    for sec in ("61.84", "61.91", "61.93", "61.94", "61.97"):
        add("章节编号断链登记 %s" % sec, sec in batch,
            "被引用但无独立章节，历史正文零改写")
    add("NDK 构建环境差异如实登记",
        "NDK not configured" in batch and "noAndroid" in batch,
        "全量 test 在本机 BUILD FAILED，纯 JVM 基线另记")
    add("reverse-engine Z 组交叉引用登记", "Z-11" in batch and "8348" in batch,
        "逆向线与 B2 线并列登记，不并入 F4 判据")
    add("最新未登记提交已补记", "1485ce0f" in batch,
        "提交信息仅为 '1'，含 Extractor 修正与 Z-11 覆盖率")

    ok = sum(1 for c in checks if c["ok"])
    verdict = "PASS" if ok == len(checks) else "FAIL"
    result = {
        "batch": "12 §61.100",
        "method": "仓库同步后的文档 ↔ 代码差异对齐（纯离线只读审计）",
        "generated_by": "tools/b2_realign_audit.py",
        "head": head(),
        "checks": checks,
        "checks_total": len(checks),
        "checks_pass": ok,
        "verdict": verdict,
        "honest_notes": [
            "本工具只校验「回写链与口径」在位，不校验真机可播、不校验编译。",
            "docs/12 §61.84/91/93/94/97 的编号断链为历史事实，只登记、不补写历史正文。",
            "本机 SDK 缺 NDK，全量 ./gradlew test --offline 配置阶段 BUILD FAILED；"
            "-Pcloudmusic.noAndroid=true 的纯 JVM 结果另记，均不伪造 BUILD SUCCESSFUL。",
            "零生产行为改动；源码侧只改注释口径，不动解码器 / 不动依赖 / 不连 adb / 未联网。",
        ],
    }

    lines = [
        "# docs/12 §61.100 · 文档 ↔ 代码差异对齐审计",
        "",
        "- 工具：`tools/b2_realign_audit.py`（纯 stdlib / 只读 / 不联网 / 不连 adb）",
        "- HEAD：`%s`" % result["head"],
        "- 判定：**%s**（%d/%d 项）" % (verdict, ok, len(checks)),
        "",
        "| # | 检查项 | 结果 | 说明 |",
        "|---|--------|------|------|",
    ]
    for i, c in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["ok"] else "FAIL", c["detail"]))
    lines += ["", "## 诚实边界", ""]
    lines += ["- " + n for n in result["honest_notes"]]
    lines += ["", "> AI生成", ""]
    report = "\n".join(lines)

    if not args.check:
        OUT_JSON.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        OUT_MD.write_text(report, encoding="utf-8")

    bad = [c for c in checks if not c["ok"]]
    print("[%s] 12 §61.100 对齐审计 %d/%d 项" % (verdict, ok, len(checks)))
    for c in bad:
        print("  [FAIL] %s — %s" % (c["name"], c["detail"]))
    if not args.check:
        print("  证据：%s / %s" % (OUT_JSON.name, OUT_MD.name))
    return 0 if verdict == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
