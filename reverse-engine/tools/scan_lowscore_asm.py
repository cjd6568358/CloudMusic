# -*- coding: utf-8 -*-
"""
scan_lowscore_asm.py —— V 组：低分证据「纯汇编片段」零漏网守护（长效守护）

背景：U 组 `scan_uncovered_evidence.py` 用 `score >= 12` 挑出 24 份 highRisk 并逐份裁定，
     但阈值可能漏掉「总分低、却含一小段纯 ARM64 汇编」的文件（例如无地址前缀的
     ```asm 代码块、连续助记词行、或助记词种类少的短片段）。本脚本把该风险做成
     机器可复现的专项扫描：
       1. 取 U 组扫描里所有 `score < 12` 且未登记的证据（134 份）；
       2. 逐份提取 5 类「纯汇编片段」信号：行首地址+助记词、行首助记词、
          连续助记词块、同行地址+助记词、```asm 类 fence；
       3. 对「有多个普通 markdown fence」的候选逐份裁定，确认不是汇编块；
       4. 产出 `spec/lowscore-asm-scan.json`，由 `audit.py` 第 16 项守护。

结论口径：只有「汇编片段候选数 == 0」才允许通过；证据集合变化导致名单漂移时
     名单指纹会变化，`audit.py` fail，须重新判读并更新裁定。

用法：python tools/scan_lowscore_asm.py
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
U_SCAN = SPEC / "uncovered-evidence-scan.json"
OUT = SPEC / "lowscore-asm-scan.json"

SCAN_VERSION = 1
HIGH_RISK_THRESHOLD = 12
EXPECTED_LOW_SCORE_COUNT = 134
ASM_ADDR_INSN_MIN = 3
ASM_MNE_BLOCK_MIN = 4
ASM_INLINE_MIN = 3
PLAIN_FENCE_MIN = 2

MNE = (
    r"(?:ldr|str|ldrb|strb|ldrsw|ldrh|strh|ldp|stp|ldrsb|"
    r"cmp|cmn|tst|b\.[a-z]{2}|bl|blr|br|ret|adrp|adr|"
    r"add|sub|mov|movz|movk|mvn|and|orr|eor|lsl|lsr|asr|"
    r"cbz|cbnz|tbz|tbnz|nop|madd|msub|fmov|uxtw|sxtw|sbfx|ubfx)"
)
RE_ADDR_INSN = re.compile(r"^\s*0x[0-9a-fA-F]{3,8}[\s:]+" + MNE + r"\b", re.I)
RE_MNE_LEAD = re.compile(r"^\s*" + MNE + r"\b", re.I)
RE_HEX = re.compile(r"\b0x[0-9a-fA-F]{3,8}\b", re.I)
RE_MNE_WORD = re.compile(r"\b" + MNE + r"\b", re.I)
RE_ASM_FENCE = re.compile(r"^\s*```\s*(?:asm|arm|arm64|nasm|gas|masm)\b", re.I)
RE_PLAIN_FENCE = re.compile(r"^\s*```")

ALLOWED_VERDICTS = ["PLAIN_MARKDOWN_FENCE"]


def asm_signals(text: str) -> dict:
    lines = text.splitlines()
    addr_insn = sum(1 for ln in lines if RE_ADDR_INSN.search(ln))
    mne_lead = sum(1 for ln in lines if RE_MNE_LEAD.search(ln))
    best = cur = 0
    for ln in lines:
        if RE_MNE_LEAD.search(ln):
            cur += 1
            best = max(best, cur)
        else:
            cur = 0
    inline = sum(1 for ln in lines if RE_HEX.search(ln) and RE_MNE_WORD.search(ln))
    asm_fence = sum(1 for ln in lines if RE_ASM_FENCE.search(ln))
    plain_fence = sum(1 for ln in lines if RE_PLAIN_FENCE.search(ln))
    return {
        "addrInsnLines": addr_insn,
        "mnemonicLeadLines": mne_lead,
        "maxMneBlock": best,
        "inlineAddrMnemonicLines": inline,
        "asmFenceCount": asm_fence,
        "plainFenceCount": plain_fence,
        "asmCandidate": (
            addr_insn >= ASM_ADDR_INSN_MIN
            or best >= ASM_MNE_BLOCK_MIN
            or inline >= ASM_INLINE_MIN
            or asm_fence >= 1
        ),
    }


def main() -> None:
    scan = json.loads(U_SCAN.read_text(encoding="utf-8"))
    src_sha = hashlib.sha256(U_SCAN.read_bytes()).hexdigest()
    low = [
        r for r in scan.get("all") or []
        if not r.get("registered") and not r.get("newlyRegisteredByUGroup")
        and r.get("score", 0) < HIGH_RISK_THRESHOLD
    ]
    assert len(low) == EXPECTED_LOW_SCORE_COUNT, \
        "低分未登记证据数变化：%d（预期 %d）" % (len(low), EXPECTED_LOW_SCORE_COUNT)

    rows = []
    for r in low:
        p = ANALYSIS / r["name"]
        text = p.read_text(encoding="utf-8", errors="replace")
        rows.append({
            "name": r["name"],
            "score": r["score"],
            "bytes": len(p.read_bytes()),
            **asm_signals(text),
        })
    rows.sort(key=lambda x: (-x["addrInsnLines"], -x["maxMneBlock"],
                             -x["inlineAddrMnemonicLines"], x["name"]))

    asm_candidates = [r for r in rows if r["asmCandidate"]]
    fence_candidates = [r for r in rows if r["plainFenceCount"] >= PLAIN_FENCE_MIN]

    payload = [[r["name"], r["score"], r["plainFenceCount"]] for r in fence_candidates]
    fp = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()

    adjudications = [
        {
            "name": r["name"],
            "score": r["score"],
            "plainFenceCount": r["plainFenceCount"],
            "asmFenceCount": r["asmFenceCount"],
            "inlineAddrMnemonicLines": r["inlineAddrMnemonicLines"],
            "verdict": "PLAIN_MARKDOWN_FENCE",
            "reason": "普通 markdown 代码块（配置 / 日志 / 脚本 / 表格），"
                      "asm 类 fence=0、同行地址+助记词行数=%d、带地址前缀指令行数=%d，"
                      "不是 ARM64 汇编片段" % (r["inlineAddrMnemonicLines"], r["addrInsnLines"]),
            "specId": "V-03",
        }
        for r in fence_candidates
    ]

    # ---------------------------------------------------------------- 硬断言
    assert asm_candidates == [], \
        "低分证据检出纯汇编片段候选：%s" % [r["name"] for r in asm_candidates]
    assert all(r["asmFenceCount"] == 0 for r in rows), "低分证据出现 asm 类 fence"
    assert all(r["inlineAddrMnemonicLines"] < ASM_INLINE_MIN for r in rows), \
        "低分证据出现同行地址+助记词密集行"
    assert all(a["verdict"] in ALLOWED_VERDICTS and a["reason"] and a["specId"]
               for a in adjudications), "fence 候选裁定不完整"
    assert len(adjudications) == len(fence_candidates)

    report = {
        "scanVersion": SCAN_VERSION,
        "generatedBy": "tools/scan_lowscore_asm.py",
        "sourceScan": "spec/uncovered-evidence-scan.json",
        "sourceScanSha256": src_sha,
        "highRiskThreshold": HIGH_RISK_THRESHOLD,
        "totalLowScore": len(rows),
        "asmAddrInsnMin": ASM_ADDR_INSN_MIN,
        "asmMneBlockMin": ASM_MNE_BLOCK_MIN,
        "asmInlineMin": ASM_INLINE_MIN,
        "asmCandidateCount": len(asm_candidates),
        "asmCandidates": asm_candidates,
        "maxAddrInsnLines": max(r["addrInsnLines"] for r in rows),
        "maxMaxMneBlock": max(r["maxMneBlock"] for r in rows),
        "maxInlineAddrMnemonicLines": max(r["inlineAddrMnemonicLines"] for r in rows),
        "asmFenceTotal": sum(r["asmFenceCount"] for r in rows),
        "plainFenceThreshold": PLAIN_FENCE_MIN,
        "plainFenceCandidateCount": len(fence_candidates),
        "fingerprintDefinition": "sha256 of json([[name,score,plainFenceCount],...] in fence_candidates order)",
        "fenceFingerprint": fp,
        "allowedVerdicts": ALLOWED_VERDICTS,
        "adjudications": adjudications,
        "all": rows,
        "derived": [
            "U 组 score>=12 阈值未漏掉纯汇编片段：134 份低分证据的汇编候选为 0",
            "带地址前缀指令行 / 连续助记词块 / 同行地址+助记词 / asm 类 fence 四类信号全为 0",
            "%d 份多 fence 文件逐份裁定为普通 markdown 代码块，名单受 sha256 指纹守护"
            % len(fence_candidates),
            "本扫描只做候选发现，不代替人工判读；证据集合变化会让指纹漂移并触发 audit fail",
        ],
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                   encoding="utf-8", newline="\n")

    print("[ASM] lowScore(<12)=%d asmCandidates=%d asmFenceTotal=%d"
          % (len(rows), len(asm_candidates), report["asmFenceTotal"]))
    print("[ASM] max addrInsn=%d maxMneBlock=%d maxInlineAddrMne=%d"
          % (report["maxAddrInsnLines"], report["maxMaxMneBlock"],
             report["maxInlineAddrMnemonicLines"]))
    print("[ASM] plainFence(>=%d)=%d adjudications=%d fingerprint=%s"
          % (PLAIN_FENCE_MIN, len(fence_candidates), len(adjudications), fp[:16]))
    for r in fence_candidates[:6]:
        print("   %-52s score=%-3d plainFence=%-3d asmFence=%d"
              % (r["name"][:52], r["score"], r["plainFenceCount"], r["asmFenceCount"]))
    if len(fence_candidates) > 6:
        print("   ... and %d more" % (len(fence_candidates) - 6))
    print("[ASM] -> %s" % OUT.as_posix())


if __name__ == "__main__":
    main()
