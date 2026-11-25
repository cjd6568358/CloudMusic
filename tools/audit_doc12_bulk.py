#!/usr/bin/env python3
"""§61.350 · docs/12 体积治理（D-3）。

评审 §五-3：docs/12 已 1.8 万行，维护账本的成本挤掉验证成本，建议「正文只留决策 +
判据，过程叙述迁出独立文件」。但 docs/12 是 guard 的真相源（92 处判据读全文），
任何被 guard 引用的文本一旦迁走就会让判据失效——机械搬迁不可行。

本工具做两件事（提示性，不阻断）：
  1. 统计 docs/12 按 `##` 大块的体积分布，定位膨胀源，让「维护成本」可量化。
  2. 逐块判定「可安全迁出」：块内所有行的「实词短语」都不出现在 guard 源码里
     （保守近似：命中任一 guard 引用的章节号/长数字/长中文短语即标记为不可迁）。
     输出可迁出候选供人工复核——不自动改文档。

用法：
    python tools/audit_doc12_bulk.py [--json <path>]

退出码恒 0（审计提示性）。
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "12-编码阶段第一批交付.md"
GUARD = ROOT / "tools" / "check_source_contracts.py"

SKIP_TOKENS = {"PASS", "FAIL", "OK", "NOT", "RUN", "THE", "AND"}


def guard_text():
    return GUARD.read_text(encoding="utf-8", errors="replace")


def split_blocks(lines):
    blocks = []
    cur = None
    for i, ln in enumerate(lines):
        if re.match(r"^##\s+", ln):
            if cur:
                blocks.append(cur)
            cur = {"title": ln.strip(), "start": i + 1, "lines": []}
        elif cur:
            cur["lines"].append(ln)
    if cur:
        blocks.append(cur)
    return blocks


def extract_tokens(text):
    """抽取 guard 可能锚定的实词：§61.x 章节号、长数字、长中文短语、英文标识符。"""
    toks = set()
    toks.update(re.findall(r"61\.\d+", text))
    toks.update(re.findall(r"\b\d{4,}\b", text))
    toks.update(re.findall(r"[\u4e00-\u9fff]{5,}", text))
    toks.update(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]{6,}\b", text))
    return toks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    lines = DOC.read_text(encoding="utf-8").splitlines()
    gtxt = guard_text()
    g_tokens = extract_tokens(gtxt) - SKIP_TOKENS
    blocks = split_blocks(lines)

    detail = []
    for b in blocks:
        body = "\n".join(b["lines"])
        b_tokens = extract_tokens(body)
        # 也把标题号算入（guard 可能钉 "### 61.x"）
        title_tokens = extract_tokens(b["title"])
        overlap = (b_tokens | title_tokens) & g_tokens
        detail.append({
            "title": b["title"][:90],
            "start_line": b["start"],
            "lines": len(b["lines"]),
            "guard_overlap_count": len(overlap),
            "guard_overlap_sample": sorted(overlap)[:8],
        })

    total = len(lines)
    top = sorted(detail, key=lambda d: d["lines"], reverse=True)[:12]
    safe = sorted((d for d in detail if d["guard_overlap_count"] == 0 and d["lines"] >= 15),
                  key=lambda d: d["lines"], reverse=True)[:20]

    print("[INFO] docs/12: %d 行 / %d 个 ## 大块" % (total, len(blocks)))
    print("[INFO] guard 实词交集为空且 >=15 行的大块（可迁出候选）: %d" % len(safe))
    print("=== 体积 Top 12（guard 交集）===")
    for d in top:
        print("  %5d行 @L%-6d overlap=%-3d %s" %
              (d["lines"], d["start_line"], d["guard_overlap_count"], d["title"][:60]))
    print("=== 可安全迁出候选 Top 20 ===")
    for d in safe:
        print("  %5d行 @L%-6d %s" % (d["lines"], d["start_line"], d["title"][:60]))
    print("[OK] docs/12 体积审计完成（提示性，非阻断）")

    if args.json:
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({
            "container": "doc12_bulk",
            "doc_lines": total,
            "block_count": len(blocks),
            "top_blocks": top,
            "safe_to_extract": safe,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print("[OK] 报告写出 %s" % out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
