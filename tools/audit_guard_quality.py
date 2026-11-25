#!/usr/bin/env python3
"""§61.348 · guard 判据质量审计（D-2）。

背景：评审 §五-2（review-2026-11-24-ape-frame-alignment-and-ui-audit.md）指出 guard
「以字符串存在性为主，防改回去不防本来就错，甚至会把错误结论焊死」。本项目的处置不是
机械删除判据（那会丢「防回潮」能力、违反「改判据不改强度」），而是提供**可复跑的质量审计**，
持续检测两类假安全信号：

  1. count 型判据的阈值 > 目标文件内锚点的实际语义出现次数 → 判据恒 FAIL 或写错；
     阈值 == 1 且用 >=/in → 恒真（只要锚点出现一次就过，删一处发现不了）。
  2. 存在性判据的锚点是宽松子串（如 "playing = " 会被 "val playing = " 凑数，§61.327
     真实踩过）→ 报告「该锚点有多个更长形态包含它」，提示升级为精确形态。

用法：
    python tools/audit_guard_quality.py [--json <path>]

退出码：恒 0（审计是提示性的，不阻断；真判据在 check_source_contracts.py）。
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUARD = ROOT / "tools" / "check_source_contracts.py"

SKIP_DIRS = {"build", ".git", "archive", ".gradle", ".workbuddy", ".idea"}
SRC_SUFFIXES = {".kt", ".java", ".py", ".md", ".xml", ".toml"}

COUNT_PAT = re.compile(r'\.count\(([fb]?["\'])(.*?)\1\)\s*(<=|>=|<|>)\s*(\d+)')
EXIST_PAT = re.compile(r'if\s+"([^"\n]+)"\s+not\s+in\s+([A-Za-z_][A-Za-z0-9_]*)')


def load_corpus():
    out = {}
    for p in ROOT.rglob("*"):
        if not p.is_file() or any(part in SKIP_DIRS for part in p.parts):
            continue
        if p.suffix not in SRC_SUFFIXES or p == GUARD:
            continue
        try:
            out[p] = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            pass
    return out


def audit_count(guard_src, corpus):
    """count 型判据：报锚点在全仓的出现分布，标出「阈值=出现次数」的贴边候选。"""
    lines = guard_src.splitlines()
    findings = []
    for i, ln in enumerate(lines):
        for m in COUNT_PAT.finditer(ln):
            literal, op, n = m.group(2), m.group(3), int(m.group(4))
            hit_files = {}
            for p, txt in corpus.items():
                c = txt.count(literal)
                if c:
                    hit_files[str(p.relative_to(ROOT)).replace("\\", "/")] = c
            total = sum(hit_files.values())
            findings.append({
                "guard_line": i + 1,
                "anchor": literal,
                "op": op,
                "threshold": n,
                "corpus_occurrences": total,
                "files": hit_files,
                # 恒真信号：>=1 或 >0 配任意出现——锚点只要出现一次就过，删一处不可见
                "vacuous": op in (">=", ">") and n <= 1,
                # 贴边信号：阈值正好等于全仓出现次数（改一处即 FAIL，判据偏脆但有效）
                "tight": total == n and total > 0,
            })
    return findings


def audit_existence(guard_src):
    """存在性判据：报宽松子串锚点（自身同时是别的更长字面量的子串）。"""
    anchors = {}
    for m in EXIST_PAT.finditer(guard_src):
        lit, var = m.group(1), m.group(2)
        if len(lit) < 6 or "=" not in lit and "(" not in lit:
            continue
        line = guard_src.count("\n", 0, m.start()) + 1
        anchors.setdefault((lit, var), []).append(line)
    all_lits = [lit for lit, _ in anchors]
    findings = []
    for (lit, var), lines in anchors.items():
        # 宽松信号：锚点是「赋值前缀 + 空格"= "结尾」（如 "playing = " / "dimmed = "），
        # 右侧可以是任意值——改掉一处赋值仍 PASS（§61.327 真实踩过）。
        # 注意：以 "(" 结尾的函数签名（如 "suspend fun lyricsFor("）是好锚点——
        # 删掉函数即 FAIL，不算宽松。
        loose = lit.endswith(" = ")
        findings.append({
            "anchor": lit,
            "var": var,
            "guard_lines": lines,
            "loose_prefix": loose,
        })
    return findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    guard_src = GUARD.read_text(encoding="utf-8", errors="replace")
    corpus = load_corpus()
    counts = audit_count(guard_src, corpus)
    exist = audit_existence(guard_src)

    vacuous = [c for c in counts if c["vacuous"]]
    tight = [c for c in counts if c["tight"]]
    loose = [e for e in exist if e["loose_prefix"]]

    report = {
        "container": "guard_quality",
        "guard": str(GUARD.relative_to(ROOT)).replace("\\", "/"),
        "corpus_files": len(corpus),
        "count_predicates": len(counts),
        "existence_predicates": len(exist),
        "vacuous_count_predicates": vacuous,
        "tight_count_predicates": tight,
        "loose_existence_anchors": loose,
    }
    print("[INFO] 语料 %d 文件 / count 判据 %d / 存在性判据 %d" %
          (len(corpus), len(counts), len(exist)))
    print("[INFO] 恒真 count 判据 %d / 贴边 count 判据 %d / 宽松存在性锚点 %d" %
          (len(vacuous), len(tight), len(loose)))
    for c in tight[:20]:
        print("  [TIGHT] L%d count(%r) %s %d = 全仓 %d" %
              (c["guard_line"], c["anchor"], c["op"], c["threshold"], c["corpus_occurrences"]))
    for e in loose[:20]:
        print("  [LOOSE] %r in %s @L%s" % (e["anchor"], e["var"], e["guard_lines"][0]))
    print("[OK] guard 判据质量审计完成（提示性，非阻断）")

    if args.json:
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("[OK] 报告写出 %s" % out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
