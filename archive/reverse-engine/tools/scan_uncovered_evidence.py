# -*- coding: utf-8 -*-
"""
scan_uncovered_evidence.py —— C 类证据再清点扫描器（长效守护）

目的：对 `analysis/` 根目录全部 `*.json` / `*.md` 逐份提取「L1 指令级反汇编信号」，
     找出可能被误归为 C 类（明确不翻译）的一手 native 控制逻辑证据。
     已登记证据源从 `spec/LOGIC-SPEC.md` 第 2 节表格**动态解析**，不硬编码清单，
     文档增删证据源时扫描口径自动跟随。

信号类别（均为静态文本信号，不做行为推断）：
  1. ARM64 助记词种类
  2. 函数 / 指令地址 0xNNNN（4~6 位十六进制，去重）
  3. 结构体偏移 [this+0x..] / [+0x..] / [xN+0x..]
  4. 条件 / 无条件分支目标（b.ne / bl / cbz / br ... 0xNNNN）
  5. 机读反汇编结构字段（anchors / checks_total / disasm / rodata / fix_anchors ...）
  6. native 立即数错误码（-2 / -98 / -100 / -102 / -103 / -106 / 0x67 / 0x6a）
  7. ELF 段 / 重定位引用（.rodata / .text / .bss / .init_array / GOT / PLT / vtable）

产出：`spec/uncovered-evidence-scan.json`（机读）+ stdout 摘要
守护：`audit.py` 第 12 项检查 highRisk 候选是否全部在裁定表中登记。

注意：本脚本只做**候选发现**，不代替人工判读。高分只表示「文本像反汇编证据」，
     可能是引用已登记证据的人类可读报告，是否属于新控制流由裁定表决定。
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

RE = Path(__file__).resolve().parents[1]
PROJ = RE.parent
ANALYSIS = PROJ / "analysis"
SPEC = RE / "spec"
OUT = SPEC / "uncovered-evidence-scan.json"

SCAN_VERSION = 1
HIGH_RISK_THRESHOLD = 12

# ------------------------------------------------------------------ 信号定义
RE_MNEMONIC = re.compile(
    r"\b(?:ldr|str|ldrb|strb|ldrsw|ldrh|strh|ldp|stp|ldrb|ldrsb|"
    r"cmp|cmn|tst|"
    r"b\.(?:ne|eq|lt|gt|le|ge|hi|ls|cs|cc|mi|pl|al|vs|vc|lo|hs)|"
    r"bl|blr|br|ret|adrp|adr|"
    r"add|sub|mov|movz|movk|mvn|and|orr|eor|lsl|lsr|asr|"
    r"cbz|cbnz|tbz|tbnz|nop|madd|msub|fmov|uxtw|sxtw|sbfx|ubfx)\b"
)
RE_ADDR = re.compile(r"\b0x[0-9a-fA-F]{4,6}\b")
RE_OFFSET = re.compile(r"\[(?:this|x\d+|sp|w\d+)\+0x[0-9a-fA-F]+\]|\[\+0x[0-9a-fA-F]+\]")
RE_BRANCH = re.compile(
    r"\b(?:bl|blr|br|b\.[a-z]{2}|cbz|cbnz|tbz|tbnz)\s+0x[0-9a-fA-F]{3,6}\b"
)
RE_ERRCODE = re.compile(r"(?<!\d)-(?:2|98|100|102|103|106)\b|\b0x6[7a]\b")

DISASM_FIELDS = (
    "anchors", "checks_total", "checks_pass", "all_pass", "disasm",
    "instructions", "instruction_count", "opcode", "rodata", "fix_anchors",
    "hardcoded_minus", "pending_", "generation_cmp", "commit_store",
    "result_minus", "wait_request_call", "queue_bl_", "symbol_addr",
)
SECTION_REFS = (
    ".rodata", ".text", ".bss", ".init_array", "GOT", "PLT", "vtable", "vtable+0x",
)


def registered_sources() -> set[str]:
    """从 LOGIC-SPEC 第 2 节表格动态解析已登记的 analysis/ 文件名。"""
    text = (SPEC / "LOGIC-SPEC.md").read_text(encoding="utf-8")
    m = re.search(r"^## 2\..*?(?=^## 3\.)", text, re.S | re.M)
    assert m, "LOGIC-SPEC 第 2 节未找到"
    names = set(re.findall(r"`analysis/([^`]+?)`", m.group(0)))
    assert len(names) >= 18, "已登记证据源解析异常：仅 %d 份" % len(names)
    return names


def newly_registered_u_group() -> set[str]:
    """U 组本批从 C 类提升为已登记的 7 份证据（从 a3-meta-manifest.json 动态读取）。"""
    p = SPEC / "a3-meta-manifest.json"
    if not p.is_file():
        return set()
    m = json.loads(p.read_text(encoding="utf-8"))
    return {Path(s["path"]).name for s in (m.get("sources") or [])}


def signals_of(raw: bytes) -> dict:
    text = raw.decode("utf-8", errors="replace")
    low = text.lower()

    mnemonic_kinds = sorted(set(m.group(0) for m in RE_MNEMONIC.finditer(text)))
    addrs = sorted(set(m.group(0).lower() for m in RE_ADDR.finditer(text)))
    offsets = sorted(set(m.group(0).lower() for m in RE_OFFSET.finditer(text)))
    branches = sorted(set(m.group(0).lower() for m in RE_BRANCH.finditer(text)))
    errcodes = sorted(set(m.group(0).lower() for m in RE_ERRCODE.finditer(text)))
    fields = sorted({f for f in DISASM_FIELDS if f.lower() in low})
    sections = sorted({s for s in SECTION_REFS if s.lower() in low})

    score = (
        3 * len(mnemonic_kinds)
        + min(len(addrs), 12)
        + 4 * len(branches)
        + 3 * len(offsets)
        + 3 * len(fields)
        + 2 * len(errcodes)
        + 2 * len(sections)
    )
    return {
        "score": score,
        "highRisk": score >= HIGH_RISK_THRESHOLD,
        "mnemonicKinds": mnemonic_kinds,
        "addrCount": len(addrs),
        "addrSample": addrs[:12],
        "offsetCount": len(offsets),
        "offsetSample": offsets[:8],
        "branchCount": len(branches),
        "branchSample": branches[:8],
        "errcodes": errcodes,
        "disasmFields": fields,
        "sectionRefs": sections,
    }


def main() -> None:
    registered_all = registered_sources()
    u_new = newly_registered_u_group()
    # 再清点以「U 组提升前的 20 份」为基线口径，保证 24 份 highRisk 与裁定表稳定可复现；
    # U 组本批新登记的 7 份仍留在扫描全集里，单独标 newlyRegisteredByUGroup。
    baseline = registered_all - u_new
    files = sorted(
        p for p in ANALYSIS.glob("*")
        if p.is_file() and p.suffix.lower() in (".json", ".md")
    )
    assert len(files) == 178, "analysis 根目录证据文件数变化：%d（预期 178）" % len(files)

    rows = []
    for p in files:
        raw = p.read_bytes()
        sig = signals_of(raw)
        rows.append({
            "name": p.name,
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "registered": p.name in baseline,
            "newlyRegisteredByUGroup": p.name in u_new,
            **sig,
        })

    uncovered = [r for r in rows if not r["registered"]]
    high = sorted(
        (r for r in uncovered if r["highRisk"]),
        key=lambda r: (-r["score"], r["name"]),
    )
    assert len(uncovered) == 158, "未登记证据数变化：%d（预期 158）" % len(uncovered)

    report = {
        "scanVersion": SCAN_VERSION,
        "analysisRoot": "analysis",
        "totalFiles": len(files),
        "registeredCount": len(registered_all),
        "registered": sorted(registered_all),
        "baselineRegisteredCount": len(baseline),
        "baselineRegistered": sorted(baseline),
        "newlyRegisteredByUGroup": sorted(u_new),
        "newlyRegisteredCount": len(u_new),
        "uncoveredCount": len(uncovered),
        "currentUnregisteredCount": len(rows) - len(registered_all),
        "highRiskThreshold": HIGH_RISK_THRESHOLD,
        "highRiskCount": len(high),
        "highRisk": high,
        "all": rows,
    }
    OUT.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8", newline="\n",
    )

    print("[SCAN] analysis root files=%d registered=%d (baseline=%d, newlyRegisteredByUGroup=%d) "
          "uncovered=%d currentUnregistered=%d"
          % (len(files), len(registered_all), len(baseline), len(u_new), len(uncovered),
             len(rows) - len(registered_all)))
    print("[SCAN] highRisk(score>=%d)=%d -> %s"
          % (HIGH_RISK_THRESHOLD, len(high), OUT.as_posix()))
    print("")
    print("%-52s %5s  %-4s %-4s %-4s %-4s" %
          ("file", "score", "mne", "addr", "br", "off"))
    print("-" * 84)
    for r in high[:30]:
        print("%-52s %5d  %-4d %-4d %-4d %-4d" %
              (r["name"][:52], r["score"], len(r["mnemonicKinds"]),
               r["addrCount"], r["branchCount"], r["offsetCount"]))
    if len(high) > 30:
        print("... and %d more" % (len(high) - 30))
    print("")
    print("[SCAN] top fields/sections (first 8 highRisk):")
    for r in high[:8]:
        print("  %-48s fields=%s sects=%s" %
              (r["name"][:48], ",".join(r["disasmFields"]) or "-",
               ",".join(r["sectionRefs"]) or "-"))


if __name__ == "__main__":
    main()
