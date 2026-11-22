# -*- coding: utf-8 -*-
"""
scan_chapter_matrix.py —— V 组：`docs/12 §61.x` 章节矩阵机器可复现自证（长效守护）

背景：U 组证明「C 类里可能藏着竞品反汇编」，但只对 `analysis/` 根目录 178 份证据做过
     L1 信号再清点；`spec/COVERAGE.md` 第 8 节的章节矩阵一直是**手工表格**，
     并行工作流追加 §61.87 后才发现上一版表格只登记 72 个编号、漏了
     §61.5~§61.18（14 章）与 §61.87，共 15 章。本脚本把该矩阵做成机器自证：
       1. 动态切分 docs/12 的 §61.x 全章节，逐章复用 U 组 L1 指令级信号判据；
       2. 动态解析 COVERAGE 第 8 节表格得到「章节号 → A/B/C/NONE」处置；
       3. 检出 unmapped（docs/12 有标题但矩阵未登记）、C 类高 L1 候选、
          章节总数 / 处置分布漂移；
       4. 产出 `spec/chapter-matrix-scan.json`，由 `audit.py` 第 15 项守护。

判据与 `scan_uncovered_evidence.py` 同源（直接 import 其 signals_of，不复制公式）。
只有「反汇编能证明的控制流 / 数据流 / 门控 / 错误码 / 日志语义」才进 A 类；
真机现象、假设、脚本、生产工程实现、另一条路线一律进 C 类并写明理由。

注意：本脚本只做**候选发现与口径自证**，不代替人工判读。
     若 docs/12 新增章节或 COVERAGE 第 8 节表格变动，硬断言会 fail，
     提醒补登记并重新生成 manifest —— 这正是防「手工矩阵悄悄过期」的设计意图。

用法：python tools/scan_chapter_matrix.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import re
from pathlib import Path

RE = Path(__file__).resolve().parents[1]
PROJ = RE.parent
DOC = PROJ / "docs" / "12-编码阶段第一批交付.md"
COVERAGE = RE / "spec" / "COVERAGE.md"
U_SCAN = Path(__file__).with_name("scan_uncovered_evidence.py")
OUT = RE / "spec" / "chapter-matrix-scan.json"

SCAN_VERSION = 1
MIN_TOTAL_CHAPTERS = 84           # 下界（本批基线），只增不减；不写死上界
EXPECTED_MISSING = [19, 23, 24, 84]
EXPECTED_A_CHAPTERS = [54, 55, 56, 65, 67, 70, 74, 76, 77]
EXPECTED_B_CHAPTERS = [83]
NEWLY_REGISTERED_BY_V = list(range(5, 19)) + [87, 88]  # 本批补登的 16 章

# 与 scan_uncovered_evidence.py 的 RE_MNEMONIC 同源（行首 / 地址前缀版）
MNE = (
    r"(?:ldr|str|ldrb|strb|ldrsw|ldrh|strh|ldp|stp|ldrsb|"
    r"cmp|cmn|tst|b\.[a-z]{2}|bl|blr|br|ret|adrp|adr|"
    r"add|sub|mov|movz|movk|mvn|and|orr|eor|lsl|lsr|asr|"
    r"cbz|cbnz|tbz|tbnz|nop|madd|msub|fmov|uxtw|sxtw|sbfx|ubfx)"
)
RE_ADDR_INSN = re.compile(r"^\s*0x[0-9a-fA-F]{3,8}[\s:]+" + MNE + r"\b", re.I)
RE_MNE_LEAD = re.compile(r"^\s*" + MNE + r"\b", re.I)
RE_ASM_FENCE = re.compile(r"^\s*```\s*(?:asm|arm|arm64|nasm|gas|masm)\b", re.I)
RE_PLAIN_FENCE = re.compile(r"^\s*```")
RE_TITLE = re.compile(r"^#{1,4} 61\.(\d+) (.*)")
RE_SPLIT = re.compile(r"(?=^#{1,4} 61\.[0-9]+ )", re.M)

# C 类里「含 native 锚点但不翻译」的逐份人工裁定（V-02）。
# 判读依据：§61.5 自标「未定论」，§61.12 把根因改判为 CNCMFFDecoder / FFmpeg demuxer
# 内部行为并明确「静态分析已到边界，需运行时调试」，§61.13 又推翻「文件太短」假说；
# §61.8 主体是项目自身 Java 侧 nativeEvents 修复，Close 链是 vtable 槽位调用序列、无新门控。
ANCHOR_ADJUDICATIONS = {
    5: {
        "verdict": "HYPOTHESIS_NOT_STATICALLY_PROVEN",
        "reason": "§61.5 的 AVIO 虚表布局错位是阶段性假设（章节自标「未定论」）："
                  "含 read_cb 0x55970 / seek_cb 0x55a30 反汇编与虚表槽 +0x20/+0x28/+0x50，"
                  "但 §61.12 定论把根因改判为 CNCMFFDecoder / FFmpeg demuxer 内部行为并注明"
                  "「静态分析已到边界，需运行时调试」，§61.13 又推翻「文件太短」假说。"
                  "按「只有反汇编能证明的控制流才进 A 类」判据不翻译，锚点如实登记。",
        "specId": "V-02",
    },
    8: {
        "verdict": "JAVA_SIDE_FIX_VTABLE_CALL_CHAIN",
        "reason": "§61.8 主体是项目自身 Java 侧 EngineAdapter.nativeEvents() / StateListener 修复；"
                  "其中 0x9b4c → 0x13070 → slot+0x40 的 imp.Close → NMVirtualFile::Close 链是"
                  "vtable 槽位调用序列（无新门控条件），S 组已覆盖 NMSlicesFile 落盘门控，"
                  "不新增控制流翻译，锚点如实登记。",
        "specId": "V-02",
    },
}
ALLOWED_VERDICTS = [
    "HYPOTHESIS_NOT_STATICALLY_PROVEN",
    "JAVA_SIDE_FIX_VTABLE_CALL_CHAIN",
]


def load_u_signals():
    """直接复用 U 组扫描器的 signals_of，保证 L1 判据真同源、不复制公式。"""
    spec = importlib.util.spec_from_file_location("scan_uncovered_evidence", U_SCAN)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def chapter_signals(text: str, u) -> dict:
    lines = text.splitlines()
    sig = u.signals_of(text.encode("utf-8", errors="replace"))
    addr_insn = sum(1 for ln in lines if RE_ADDR_INSN.search(ln))
    mne_lead = sum(1 for ln in lines if RE_MNE_LEAD.search(ln))
    best = cur = 0
    for ln in lines:
        if RE_MNE_LEAD.search(ln):
            cur += 1
            best = max(best, cur)
        else:
            cur = 0
    return {
        "l1Score": sig["score"],
        "l1HighRisk": sig["highRisk"],
        "mnemonicKinds": len(sig["mnemonicKinds"]),
        "addrCount": sig["addrCount"],
        "offsetCount": sig["offsetCount"],
        "branchCount": sig["branchCount"],
        "addrInsnLines": addr_insn,
        "mnemonicLeadLines": mne_lead,
        "maxMneBlock": best,
        "asmFenceCount": sum(1 for ln in lines if RE_ASM_FENCE.search(ln)),
        "plainFenceCount": sum(1 for ln in lines if RE_PLAIN_FENCE.search(ln)),
    }


def parse_dispositions(cov_text: str) -> tuple[dict, int, str]:
    """解析 COVERAGE 第 8 节表格 → {章节号: A/B/C/NONE}、表格行数、第 8 节指纹。"""
    sec = cov_text.split("\n## 8.")[1].split("\n## 9.")[0]
    fp = hashlib.sha256(sec.encode("utf-8")).hexdigest()
    disp: dict[int, str] = {}
    rows = 0
    for ln in sec.splitlines():
        if not ln.startswith("|"):
            continue
        cols = [c.strip() for c in ln.strip().strip("|").split("|")]
        if len(cols) < 3:
            continue
        first = cols[0]
        lab = re.sub(r"\*", "", cols[2]).strip()
        cat = ("A" if lab.startswith("A") else
               "B" if lab.startswith("B") else
               "C" if lab.startswith("C") else
               "NONE" if lab.startswith("—") else "?")
        nums = [int(x) for x in re.findall(r"61\.(\d+)", first)]
        if not nums:
            continue
        rows += 1
        covered = set(nums)
        for a, b in re.findall(r"61\.(\d+)\s*[~～-]\s*(?:§)?61\.(\d+)", first):
            covered.update(range(int(a), int(b) + 1))
        for n in covered:
            disp[n] = cat
    return disp, rows, fp


def main() -> None:
    u = load_u_signals()
    doc_text = DOC.read_text(encoding="utf-8")
    cov_text = COVERAGE.read_text(encoding="utf-8")
    disp, table_rows, section8_fp = parse_dispositions(cov_text)

    chapters = []
    for part in RE_SPLIT.split(doc_text):
        m = RE_TITLE.match(part)
        if not m:
            continue
        n = int(m.group(1))
        s = chapter_signals(part, u)
        chapters.append({
            "number": n,
            "title": m.group(2).strip(),
            "disposition": disp.get(n, "UNMAPPED"),
            "newlyRegisteredByVGroup": n in NEWLY_REGISTERED_BY_V,
            "hasNativeAnchors": n in ANCHOR_ADJUDICATIONS,
            **s,
        })
    chapters.sort(key=lambda r: r["number"])
    present = {c["number"] for c in chapters}
    numbered_max = max(int(x) for x in re.findall(r"^#{1,4} 61\.(\d+) ", doc_text, re.M))
    missing = [n for n in range(1, numbered_max + 1) if n not in present]

    unmapped = [c["number"] for c in chapters if c["disposition"] == "UNMAPPED"]
    # 「藏反汇编」判据用指令行 / 连续助记词块 / asm fence，不用 U 组 L1 分数：
    # C 类里 §61.68/61.69/71 的高分来自「引用已登记反汇编结论」的观测 / 工具文档，不是一手证据。
    c_high = [
        c["number"] for c in chapters
        if c["disposition"] == "C" and (
            c["addrInsnLines"] >= 1 or c["maxMneBlock"] >= 3
            or c["asmFenceCount"] >= 1
        )
    ]
    c_l1_high = [c["number"] for c in chapters
                 if c["disposition"] == "C" and c["l1HighRisk"]]
    c_anchors = sorted(ANCHOR_ADJUDICATIONS)
    a_chapters = sorted(c["number"] for c in chapters if c["disposition"] == "A")
    b_chapters = sorted(c["number"] for c in chapters if c["disposition"] == "B")
    chapter_counts = {
        k: sum(1 for c in chapters if c["disposition"] == k)
        for k in ("A", "B", "C")
    }
    table_counts = {
        k: list(disp.values()).count(k) for k in ("A", "B", "C", "NONE", "?")
    }

    adjudications = []
    for n in c_anchors:
        a = ANCHOR_ADJUDICATIONS.get(n)
        adjudications.append({
            "number": n,
            "verdict": a["verdict"] if a else "UNADJUDICATED",
            "reason": a["reason"] if a else "",
            "specId": a["specId"] if a else "",
        })

    # ---------------------------------------------------------------- 硬断言
    assert len(chapters) >= MIN_TOTAL_CHAPTERS, \
        "docs/12 有标题章节数少于本批基线：%d（下界 %d）" % (len(chapters), MIN_TOTAL_CHAPTERS)
    assert missing == EXPECTED_MISSING, "无标题编号集合变化：%s" % missing
    assert unmapped == [], "章节矩阵仍有未登记章节：%s" % unmapped
    assert c_high == [], "C 类章节检出指令级反汇编候选：%s" % c_high
    assert a_chapters == EXPECTED_A_CHAPTERS, \
        "A 类章节集合变化：%s" % a_chapters
    assert b_chapters == EXPECTED_B_CHAPTERS, \
        "B 类章节集合变化：%s" % b_chapters
    assert all(disp.get(n) == "C" for n in c_anchors), \
        "人工裁定的锚点章节不再属于 C 类：%s" % c_anchors
    assert all(a["verdict"] in ALLOWED_VERDICTS and a["reason"] and a["specId"]
               for a in adjudications), "C 类 native 锚点章节裁定不完整"
    assert all(disp.get(n) == "NONE" for n in EXPECTED_MISSING), \
        "无标题编号未在矩阵登记为 NONE"

    report = {
        "scanVersion": SCAN_VERSION,
        "generatedBy": "tools/scan_chapter_matrix.py",
        "sourceDoc": "docs/12-编码阶段第一批交付.md",
        "sourceDocSha256": hashlib.sha256(doc_text.encode("utf-8")).hexdigest(),
        "coverageSection8Sha256": section8_fp,
        "numberedMax": numbered_max,
        "totalChapters": len(chapters),
        "missingNumbers": missing,
        "tableRows": table_rows,
        "tableMappedNumberCount": len(disp),
        "tableDispositionCounts": table_counts,
        "chapterDispositionCounts": chapter_counts,
        "unmapped": unmapped,
        "cClassHighL1Candidates": c_high,
        "cClassL1HighScoreChapters": c_l1_high,
        "cClassAnchorChapters": c_anchors,
        "aClassChapters": a_chapters,
        "bClassChapters": b_chapters,
        "maxCClassAddrInsnLines": max(
            (c["addrInsnLines"] for c in chapters if c["disposition"] == "C"), default=0),
        "maxCClassMaxMneBlock": max(
            (c["maxMneBlock"] for c in chapters if c["disposition"] == "C"), default=0),
        "newlyRegisteredByVGroup": NEWLY_REGISTERED_BY_V,
        "newlyRegisteredCount": len(NEWLY_REGISTERED_BY_V),
        "allowedVerdicts": ALLOWED_VERDICTS,
        "anchorAdjudications": adjudications,
        "chapters": chapters,
        "derived": [
            "章节矩阵由手工表格升级为机器可复现自证：动态切分 + U 组同源 L1 信号 + 处置交叉 + unmapped 断言",
            "不写死总章节数：只断言下界 / unmapped=0 / missing 固定 / A、B 类集合固定 / C 类零指令级反汇编",
            "V 组补登 16 章 = §61.5~§61.18（14 章，上一版漏登）+ §61.87 / §61.88（并行工作流新增）",
            "C 类带地址前缀的指令行数为 0；唯一含指令级反汇编的 §61.54 / §61.56 已判 A 类（D 组 / P 组）",
            "§61.5 / §61.8 含 native 锚点但按「假设未坐实 / Java 侧 vtable 调用链」逐份裁定不翻译",
            "C 类高 L1 分数章节（§61.68/69/71）是引用已登记结论的观测/工具文档，不作「藏反汇编」候选",
            "docs/12 新增章节若未先补登记，unmapped 断言立即 fail，强制补登记并重新生成 manifest",
        ],
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                   encoding="utf-8", newline="\n")

    print("[CHAP] docs/12 chapters=%d numberedMax=%d missing=%s tableRows=%d mapped=%d"
          % (len(chapters), numbered_max, missing, table_rows, len(disp)))
    print("[CHAP] chapter disposition=%s  table disposition=%s"
          % (chapter_counts, {k: v for k, v in table_counts.items() if v}))
    print("[CHAP] unmapped=%s  cClassHighL1=%s  cClassAnchorChapters=%s"
          % (unmapped, c_high, c_anchors))
    print("[CHAP] newlyRegisteredByVGroup=%d -> %s"
          % (len(NEWLY_REGISTERED_BY_V), NEWLY_REGISTERED_BY_V))
    print("[CHAP] anchorAdjudications=%d (all adjudicated=%s)"
          % (len(adjudications), all(a["verdict"] in ALLOWED_VERDICTS for a in adjudications)))
    print("[CHAP] top L1 signals:")
    for c in sorted(chapters, key=lambda x: -x["l1Score"])[:6]:
        print("   §61.%-3d %-3s score=%-3d addr=%-3d addrInsn=%-2d maxBlk=%-2d  %s"
              % (c["number"], c["disposition"], c["l1Score"], c["addrCount"],
                 c["addrInsnLines"], c["maxMneBlock"], c["title"][:36]))
    print("[CHAP] -> %s" % OUT.as_posix())


if __name__ == "__main__":
    main()
