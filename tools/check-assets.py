#!/usr/bin/env python3
"""C4 · 素材替换检查（docs/03-12 §3 C4 / 06 §4 / PRD §10 #11）。

空表（仅表头）= 当前无素材导出，是准确的合规状态，按 06 §4 判为 PASS。
有行时每行 replaced 必须为 true，否则阻断 release。
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, Report, add_mode_arg  # noqa: E402

MAPPING = ROOT / "docs" / "assets-mapping.csv"


def main() -> int:
    parser = argparse.ArgumentParser(description="C4 · 素材映射表 replaced 检查")
    add_mode_arg(parser)
    args = parser.parse_args()
    report = Report("C4", "素材全量替换（assets-mapping.csv）", args.mode)

    if not MAPPING.is_file():
        report.fail("docs/assets-mapping.csv 缺失（06 §4 已建立该表）")
        return report.emit()

    with MAPPING.open("r", encoding="utf-8-sig", newline="") as fh:
        rows = list(csv.DictReader(fh))

    if not rows:
        report.ok("映射表为空表 = 尚无竞品素材导出，状态正确（06 §4）")
        return report.emit()

    fields = rows[0].keys()
    if "replaced" not in fields or "legacy_name" not in fields:
        report.fail(f"映射表字段不符：{list(fields)}")
        return report.emit()

    pending = [r.get("legacy_name", "?") for r in rows
               if str(r.get("replaced", "")).strip().lower() != "true"]
    if pending:
        report.fail(f"{len(pending)} 项素材未替换：{pending[:10]}")
    else:
        report.ok(f"{len(rows)} 项素材全部 replaced=true")
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
