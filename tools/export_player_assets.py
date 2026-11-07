#!/usr/bin/env python3
"""⑦ 播放页竞品素材导出（docs/06 §3 / 14 §6；用户 2026-11-07 确认 D-145 C4 口径）。

输入：analysis/player-resource-closure.json 的 resources（export_needed=True，61 项）。
输出：
  - app/src/main/assets/legacy-player/**  素材本体，文件名统一 legacy_ 前缀
  - docs/assets-mapping.csv               每导出一项一行，replaced=false
  - analysis/player-assets-export.json    导出统计（供核对与长效守护）

**落点为何是 Android assets 而非 :ui-legacy/res（docs/12 §47.2）**：9 个 layout 共
引用 146 项资源（@id/* 定义、自定义 app: attr、自定义 View 类），L3 舱体与
R 引用批量改写尚未建设；直接进 res 会让 aapt2 缺定义而破坏主工程编译。
assets 随 APK 打包但不参与资源编译，可先保真入库、保持 19 模块零回归，
后续 L3 转 res 时再按映射表批量改写引用。

纯 Python 零依赖；幂等（同名覆盖写，不删除目标目录其它文件）。
"""
from __future__ import annotations

import csv
import json
import shutil
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLOSURE = ROOT / "analysis" / "player-resource-closure.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
MAPPING = ROOT / "docs" / "assets-mapping.csv"
REPORT = ROOT / "analysis" / "player-assets-export.json"

VALUES_TYPES = {"color", "dimen", "string", "style", "bool", "integer", "array"}
PLURAL = {
    "color": "colors", "dimen": "dimens", "string": "strings", "style": "styles",
    "bool": "bools", "integer": "integers", "array": "arrays",
}
FIELDS = [
    "legacy_name", "type", "resource_id", "semantic_name",
    "used_by", "notes", "replaced",
]


def resolve(raw: str) -> Path:
    p = Path(raw)
    return p if p.is_absolute() else ROOT / p


def qualifier_of(src: Path) -> str:
    """analysis/decoded/res/<qualifier>/<file> → <qualifier>。"""
    return src.parent.name


def main() -> int:
    data = json.loads(CLOSURE.read_text(encoding="utf-8"))
    items = [r for r in data["resources"] if r.get("export_needed")]
    if not items:
        print("[FAIL] 闭包中没有 export_needed 项")
        return 1

    OUT.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    missing: list[str] = []
    values_entries = 0

    # ---- values 类（color / dimen / string …）：按 (源文件, type) 抽取条目并改名 ----
    wanted: dict[tuple[Path, str], set[str]] = {}
    for r in items:
        if r["type"] in VALUES_TYPES:
            for raw in r["files"]:
                wanted.setdefault((resolve(raw), r["type"]), set()).add(r["name"])

    for (src, rtype), names in sorted(
        wanted.items(), key=lambda kv: (str(kv[0][0]), kv[0][1])
    ):
        if not src.is_file():
            missing.append(str(src))
            continue
        try:
            root = ET.parse(src).getroot()
        except ET.ParseError as exc:
            missing.append(f"{src} (parse: {exc})")
            continue
        picked = []
        for el in root:
            tag = el.tag.split("}")[-1]
            hit = tag == rtype or (tag == "item" and el.get("type") == rtype)
            if hit and el.get("name") in names:
                el.set("name", "legacy_" + (el.get("name") or ""))
                picked.append(el)
        if not picked:
            continue
        dest_dir = OUT / qualifier_of(src)
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / f"legacy_{PLURAL.get(rtype, rtype)}.xml"
        new_root = ET.Element("resources")
        for el in picked:
            new_root.append(el)
        ET.ElementTree(new_root).write(dest, encoding="utf-8", xml_declaration=True)
        written.append(dest)
        values_entries += len(picked)

    # ---- 单文件类（drawable / layout / font / anim …）：复制 + legacy_ 前缀 ----
    for r in items:
        if r["type"] in VALUES_TYPES:
            continue
        for raw in r["files"]:
            src = resolve(raw)
            if not src.is_file():
                missing.append(str(src))
                continue
            suffix = (src.name[len(r["name"]):]
                      if src.name.startswith(r["name"]) else src.suffix)
            dest_dir = OUT / qualifier_of(src)
            dest_dir.mkdir(parents=True, exist_ok=True)
            dest = dest_dir / f"legacy_{r['name']}{suffix}"
            shutil.copyfile(src, dest)
            written.append(dest)

    # ---- ⑧ 映射表：每导出一项一行 replaced=false ----
    with MAPPING.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        for r in items:
            writer.writerow({
                "legacy_name": r["name"],
                "type": r["type"],
                "resource_id": r.get("resource_id", ""),
                "semantic_name": "legacy_" + r["name"],
                "used_by": "player_page_closure(14 §4.1)",
                "notes": f"{len(r['files'])} src file(s) -> assets/legacy-player; res rewrite pending L3",
                "replaced": "false",
            })

    total_bytes = sum(p.stat().st_size for p in written)
    report = {
        "method": "⑦ export by player-resource-closure.json (06 §3 / 14 §6)",
        "export_items": len(items),
        "by_type": dict(sorted(Counter(r["type"] for r in items).items())),
        "files_written": len(written),
        "bytes_total": total_bytes,
        "values_entries": values_entries,
        "missing_sources": missing,
        "target_root": "app/src/main/assets/legacy-player",
        "mapping_rows": len(items),
        "mapping_all_replaced_false": True,
        "legacyPrefix": True,
        "assetMappingChanged": True,
        "resRewritePending": True,
        "c4Note": "dev WARN / release FAIL（用户 2026-11-07 确认 D-145）",
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"⑦ 导出 {len(items)} 项 / {len(written)} 文件 / {total_bytes} 字节"
          f" / values 条目 {values_entries}")
    print(f"   落点 {OUT.relative_to(ROOT)}；映射表 {len(items)} 行 replaced=false")
    if missing:
        print(f"[WARN] {len(missing)} 个源文件缺失（已登记报告）：{missing[:5]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
