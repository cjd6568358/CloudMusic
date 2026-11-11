# -*- coding: utf-8 -*-
"""
gen_chapter_matrix_manifest.py —— V 组元数据生成器

从两份 V 组扫描 JSON 全量生成：
  - kt/src/NmChapterMatrixCatalog.kt   （生成物，勿手改）
  - spec/chapter-matrix-manifest.json  （2 份扫描 JSON sha256 + Kotlin 常量期望值，防回潮）

数据来源：
  * spec/chapter-matrix-scan.json —— tools/scan_chapter_matrix.py 的章节矩阵自证快照
  * spec/lowscore-asm-scan.json   —— tools/scan_lowscore_asm.py 的低分证据零漏网快照
  * §61.87 / §61.88 —— B2 路线事实口径（人工登记，注明来源，不折算成竞品逻辑结论）

口径：本 catalog 只做**覆盖审计元数据**登记，不新增竞品控制流翻译；
     总章节数不写死上界（并行工作流会持续追加 docs/12 章节），
     由扫描器的 `unmapped=0` 断言强制补登记、由本生成器把快照刷成常量。

硬约束：生成的 Kotlin 零 `com.netease` / `com/netease`、零绝对路径字面量。

用法：python tools/gen_chapter_matrix_manifest.py
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

RE = Path(__file__).resolve().parents[1]
SPEC = RE / "spec"
KT_OUT = RE / "kt" / "src" / "NmChapterMatrixCatalog.kt"
MANIFEST_OUT = SPEC / "chapter-matrix-manifest.json"

CHAPTER_SCAN = SPEC / "chapter-matrix-scan.json"
LOW_SCAN = SPEC / "lowscore-asm-scan.json"

# §61.87 / §61.88 的 B2 路线事实（人工登记，非竞品 native 逻辑结论）
B2_FACTS = {
    "F3_B2_PASS": 14,
    "F3_B2_FAIL": 8,
    "F3_B2_NO_SAMPLE": 3,
    "M2_PASS_BASELINE_A": 11,
}
B2_BOOL_FACTS = {
    "B2_M2_GATE_PASSED": True,
    "B2_M3_GATE_PASSED": False,
}
B2_STR_FACTS = {
    "F4_STATUS": "BLOCKED",
}


def main() -> None:
    chap_raw = CHAPTER_SCAN.read_bytes()
    low_raw = LOW_SCAN.read_bytes()
    chap_sha = hashlib.sha256(chap_raw).hexdigest()
    low_sha = hashlib.sha256(low_raw).hexdigest()
    chap = json.loads(chap_raw.decode("utf-8"))
    low = json.loads(low_raw.decode("utf-8"))

    a_chapters = sorted(chap.get("aClassChapters") or [])
    b_chapters = sorted(chap.get("bClassChapters") or [])
    chapters = chap.get("chapters") or []
    top_a = max(
        (c for c in chapters if c.get("disposition") == "A"),
        key=lambda c: c.get("l1Score", 0),
    )["number"] if any(c.get("disposition") == "A" for c in chapters) else 0
    disp = chap.get("chapterDispositionCounts") or {}
    table_disp = chap.get("tableDispositionCounts") or {}

    int_consts: dict[str, int] = {
        "TOTAL_CHAPTERS": int(chap["totalChapters"]),
        "NUMBERED_MAX": int(chap["numberedMax"]),
        "MISSING_NUMBER_COUNT": len(chap.get("missingNumbers") or []),
        "TABLE_ROWS": int(chap["tableRows"]),
        "TABLE_MAPPED_NUMBER_COUNT": int(chap["tableMappedNumberCount"]),
        "A_CLASS_COUNT": int(disp.get("A", 0)),
        "B_CLASS_COUNT": int(disp.get("B", 0)),
        "C_CLASS_COUNT": int(disp.get("C", 0)),
        "NONE_CLASS_COUNT": int(table_disp.get("NONE", 0)),
        "UNMAPPED_COUNT": len(chap.get("unmapped") or []),
        "C_CLASS_HIGH_L1_COUNT": len(chap.get("cClassHighL1Candidates") or []),
        "C_CLASS_L1_HIGH_SCORE_CHAPTER_COUNT": len(chap.get("cClassL1HighScoreChapters") or []),
        "C_CLASS_ANCHOR_CHAPTER_COUNT": len(chap.get("cClassAnchorChapters") or []),
        "ANCHOR_ADJUDICATION_COUNT": len(chap.get("anchorAdjudications") or []),
        "NEWLY_REGISTERED_BY_V_COUNT": int(chap["newlyRegisteredCount"]),
        "MAX_C_CLASS_ADDR_INSN_LINES": int(chap["maxCClassAddrInsnLines"]),
        "MAX_C_CLASS_MAX_MNE_BLOCK": int(chap["maxCClassMaxMneBlock"]),
        "A_CLASS_FIRST": a_chapters[0] if a_chapters else 0,
        "B_CLASS_FIRST": b_chapters[0] if b_chapters else 0,
        "TOP_L1_A_CLASS_CHAPTER": int(top_a),
        "LOW_SCORE_TOTAL": int(low["totalLowScore"]),
        "ASM_CANDIDATE_COUNT": int(low["asmCandidateCount"]),
        "ASM_FENCE_TOTAL": int(low["asmFenceTotal"]),
        "MAX_ADDR_INSN_LINES": int(low["maxAddrInsnLines"]),
        "MAX_MAX_MNE_BLOCK": int(low["maxMaxMneBlock"]),
        "MAX_INLINE_ADDR_MNEMONIC_LINES": int(low["maxInlineAddrMnemonicLines"]),
        "PLAIN_FENCE_CANDIDATE_COUNT": int(low["plainFenceCandidateCount"]),
        "PLAIN_FENCE_ADJUDICATION_COUNT": len(low.get("adjudications") or []),
        **B2_FACTS,
    }
    str_consts: dict[str, str] = {
        "CHAPTER_SCAN_SHA256": chap_sha,
        "LOW_SCORE_SCAN_SHA256": low_sha,
        "FENCE_FINGERPRINT": str(low["fenceFingerprint"]),
        "LATEST_CHAPTER": "61.%d" % int(chap["numberedMax"]),
        **B2_STR_FACTS,
    }
    bool_consts: dict[str, bool] = dict(B2_BOOL_FACTS)

    # ---------------------------------------------------------------- Kotlin
    lines = [
        "@file:Suppress(\"unused\")",
        "package cloudmusic.reverse.kernel",
        "",
        "/**",
        " * 【生成物，勿手改】V 组：章节矩阵机器自证 + 低分证据零漏网 + §61.87/§61.88 口径快照。",
        " * 由 tools/gen_chapter_matrix_manifest.py 从 2 份扫描 JSON 生成，",
        " * 受 spec/chapter-matrix-manifest.json 的源 sha256 守护，audit.py 第 15/16 项每次比对。",
        " *",
        " * @spec V-01 / V-02 / V-03 / V-04 / V-05 / V-06",
        " * 口径：本 catalog 只做覆盖审计元数据登记，不新增竞品控制流翻译；",
        " * 总章节数不写死上界，章节矩阵防漂移由 tools/scan_chapter_matrix.py 的",
        " * unmapped=0 断言守护；B2 事实只登记、不折算成竞品 native 结论。",
        " */",
        "object NmChapterMatrixCatalog {",
        "",
        "    // ---- 扫描快照 Int 常量（章节矩阵 + 低分证据零漏网）----",
    ]
    for name, val in int_consts.items():
        if name in B2_FACTS:
            continue
        lines.append("    const val %s: Int = %d" % (name, val))
    lines.append("")
    lines.append("    // ---- 上面的 Int 常量已含 LOW_* / ASM_* / PLAIN_* 低分零漏网快照 ----")
    lines.append("    // ---- 源指纹 / 名单指纹 ----")
    for name in ("CHAPTER_SCAN_SHA256", "LOW_SCORE_SCAN_SHA256", "FENCE_FINGERPRINT"):
        lines.append("    const val %s: String = \"%s\"" % (name, str_consts[name]))
    lines.append("")
    lines.append("    // ---- §61.87 / §61.88 B2 路线事实（另一条路线，只登记）----")
    for name, val in B2_FACTS.items():
        lines.append("    const val %s: Int = %d" % (name, val))
    for name, val in B2_BOOL_FACTS.items():
        lines.append("    const val %s: Boolean = %s" % (name, "true" if val else "false"))
    for name in ("F4_STATUS", "LATEST_CHAPTER"):
        lines.append("    const val %s: String = \"%s\"" % (name, str_consts[name]))
    lines.append("}")
    lines.append("")
    kt_text = "\n".join(lines)

    for banned in ("com.netease", "com/netease"):
        assert banned not in kt_text, "生成 Kotlin 命中禁项：%s" % banned
    assert not any(x in kt_text for x in (":\\", ":/Users", ":/home")), \
        "生成 Kotlin 命中绝对路径字面量"

    KT_OUT.write_text(kt_text, encoding="utf-8", newline="\n")

    # -------------------------------------------------------------- manifest
    manifest = {
        "generatedBy": "tools/gen_chapter_matrix_manifest.py",
        "sourceCount": 2,
        "sources": [
            {"path": "spec/chapter-matrix-scan.json", "sha256": chap_sha, "bytes": len(chap_raw)},
            {"path": "spec/lowscore-asm-scan.json", "sha256": low_sha, "bytes": len(low_raw)},
        ],
        "kotlinIntConstants": int_consts,
        "kotlinStringConstants": str_consts,
        "kotlinBooleanConstants": bool_consts,
        "specIds": ["V-01", "V-02", "V-03", "V-04", "V-05", "V-06"],
        "derived": [
            "V 组把 COVERAGE 第 8 节手工章节矩阵升级为机器可复现自证（unmapped=0 + A/B 类集合固定）",
            "不写死总章节数上界：并行工作流新增 docs/12 章节须先补登记，否则 scan_chapter_matrix.py fail",
            "V 组补登 16 章：§61.5~§61.18（14 章）+ §61.87 / §61.88（2 章）",
            "§61.5 / §61.8 含 native 锚点但按「假设未坐实 / Java 侧 vtable 调用链」逐份裁定不翻译",
            "134 份 score<12 低分证据的纯汇编片段候选为 0；29 份多 fence 文件逐份裁定为普通 markdown 块",
            "B2 的 F3 22 格 / M2 PASS / M3 接线只登记，不改变 F4 仍 BLOCKED 与本目录不实现 M2/M3 的口径",
            "NmFormatCatalog.VERDICT 的 M2_GATE_NOT_PASSED / NOT RUN 是 §61.83 源证据 1:1 登记，源未变故保留",
        ],
    }
    MANIFEST_OUT.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8", newline="\n",
    )

    print("[GEN-V] %s" % KT_OUT.as_posix())
    print("[GEN-V] %s" % MANIFEST_OUT.as_posix())
    print("[GEN-V] sources=2 intConsts=%d strConsts=%d boolConsts=%d"
          % (len(int_consts), len(str_consts), len(bool_consts)))
    print("[GEN-V] chapters=%d numberedMax=%d A=%d B=%d C=%d unmapped=%d newlyRegistered=%d"
          % (int_consts["TOTAL_CHAPTERS"], int_consts["NUMBERED_MAX"],
             int_consts["A_CLASS_COUNT"], int_consts["B_CLASS_COUNT"],
             int_consts["C_CLASS_COUNT"], int_consts["UNMAPPED_COUNT"],
             int_consts["NEWLY_REGISTERED_BY_V_COUNT"]))
    print("[GEN-V] lowScore=%d asmCandidates=%d asmFence=%d fenceCandidates=%d"
          % (int_consts["LOW_SCORE_TOTAL"], int_consts["ASM_CANDIDATE_COUNT"],
             int_consts["ASM_FENCE_TOTAL"], int_consts["PLAIN_FENCE_CANDIDATE_COUNT"]))
    print("[GEN-V] sanitize assertion: OK (zero com.netease / com/netease / absolute path)")


if __name__ == "__main__":
    main()
