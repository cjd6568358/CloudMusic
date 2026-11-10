#!/usr/bin/env python3
"""T-L L3 技术接入前置实证：解析 assets 暂存的 9 个竞品播放页 layout。

纯 Python 零依赖。输出 analysis/legacy-layout-audit.json，回答三个问题：

1. 每个 layout 引用了哪些 `@type/name` 资源 / `app:*` 自定义属性 / `com.netease.*`
   自定义 View；
2. 这些引用能否被 51 个已导出素材与 docs/assets-mapping.csv 的 61 行覆盖，
   「R 引用改写」到底要改多少处、有哪些缺口；
3. 全库自研源码对 `legacy-player` 暂存区究竟有几个生产引用方（§61.38 的
   「零引用方」定性），以及 `:ui-legacy` 舱体在构建脚本里的落点状态。

本脚本只读、不写任何生产源码 / 资源 / 构建文件，仅产一份分析 JSON。
依据：12 §47（素材导出）/ §61.36（分发合规审计）/ §61.37（D-146 个人自用）
/ 14 §4.1（播放页资源闭包）/ docs/06。

用法：
    python tools/legacy_layout_audit.py
    python tools/legacy_layout_audit.py --out analysis/legacy-layout-audit.json
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "legacy-player" / "layout"
STAGED_ROOT = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
MAPPING_CSV = ROOT / "docs" / "assets-mapping.csv"
CLOSURE_JSON = ROOT / "analysis" / "player-resource-closure.json"
SETTINGS = ROOT / "settings.gradle.kts"
LEGACY_PREFIX = "legacy_"

# `@drawable/bn5`、`?attr/foo`、`@id/miContainer`、`@+id/foo`
REF_RE = re.compile(r"([@?+]?@|[?])([A-Za-z][A-Za-z0-9_]*)/([A-Za-z0-9_.]+)")
# `@android:color/white` 之类系统资源
SYS_REF_RE = re.compile(r"@android:([A-Za-z][A-Za-z0-9_]*)/([A-Za-z0-9_.]+)")
# `app:a5l="parent"` 之类混淆后的自定义 attr
APP_ATTR_RE = re.compile(r"\bapp:([A-Za-z_][A-Za-z0-9_]*)\s*=")
NETEASE_CLASS_RE = re.compile(r"com\.netease[A-Za-z0-9_.$]*")
NULL_REF_RE = re.compile(r"(?<![\w])@null\b")

# csv 中登记的资源类型；id 由 layout 自身 / public.xml 定义，不在导出项内
EXPORTABLE_TYPES = {"color", "dimen", "drawable", "font", "layout", "string"}
VALUES_SUBDIR_PREFIX = "values"


def _rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def _strip_legacy(stem: str) -> str:
    return stem[len(LEGACY_PREFIX):] if stem.startswith(LEGACY_PREFIX) else stem


def parse_layout(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    degraded = False
    tags: list[str] = []
    try:
        root = ET.fromstring(text)
        tags = [el.tag for el in root.iter()]
    except ET.ParseError:
        degraded = True
        tags = [t.lstrip("/") for t in re.findall(r"<\s*([A-Za-z_][A-Za-z0-9_.:]*)\b", text)]

    def classify(tag: str) -> str:
        t = tag.split("}")[-1]  # 去掉 ET 的 {namespace} 前缀
        if t.startswith("com.netease"):
            return "netease"
        if t.startswith("androidx.") or t.startswith("com.google."):
            return "androidx"
        if t.startswith("android.widget.") or t.startswith("android.view."):
            return "framework"
        if "." not in t:
            return "framework"
        return "other"

    buckets: dict[str, list[str]] = defaultdict(list)
    for tag in tags:
        buckets[classify(tag)].append(tag.split("}")[-1])

    refs: list[dict] = []
    for sigil, typ, name in REF_RE.findall(text):
        refs.append({"sigil": sigil, "type": typ, "name": name})
    for typ, name in SYS_REF_RE.findall(text):
        refs.append({"sigil": "@android:", "type": typ, "name": name})

    netease_all = NETEASE_CLASS_RE.findall(text)
    return {
        "file": _rel(path),
        "name": path.stem,
        "well_formed_xml": not degraded,
        "element_count": len(tags),
        "tag_classes": {
            k: {"count": len(v), "unique": sorted(set(v))}
            for k, v in sorted(buckets.items())
        },
        "netease_class_occurrences_raw": len(netease_all),
        "netease_classes": sorted(set(netease_all)),
        "app_attrs": sorted(set(APP_ATTR_RE.findall(text))),
        "resource_refs": refs,
        "null_ref_count": len(NULL_REF_RE.findall(text)),
        "plus_id_ref_count": len(re.findall(r"@\+id/", text)),
    }


def load_mapping() -> list[dict]:
    with MAPPING_CSV.open(encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh))


def staged_resource_names() -> dict:
    """暂存区实际能提供的资源定义，键为 (type, 原始名)。

    drawable*/font/ 目录按文件 stem 去 `legacy_` 前缀；values*/ 目录解析 XML
    内 `<color|dimen|string name="legacy_xxx">`，因为颜色 / 尺寸 / 字符串是
    聚合在同一个 values 文件里的，不能按文件名匹配。
    """
    by_type: dict[str, set[str]] = defaultdict(set)
    files_by_dir: dict[str, int] = defaultdict(int)
    for p in STAGED_ROOT.rglob("*"):
        if not p.is_file():
            continue
        parent = p.parent.name
        files_by_dir[parent] += 1
        typ = parent.split("-")[0]
        if typ.startswith(VALUES_SUBDIR_PREFIX):
            try:
                node = ET.fromstring(p.read_text(encoding="utf-8"))
            except ET.ParseError:
                continue
            for child in node:
                child_type = child.tag.split("}")[-1]
                name = child.get("name")
                if child_type in EXPORTABLE_TYPES and name:
                    by_type[child_type].add(_strip_legacy(name))
        elif typ in EXPORTABLE_TYPES:
            # `legacy_csp.9.png` 的 Path.stem 是 `legacy_csp.9`，九宫格后缀
            # 不属于资源名；统一按首个点之前的部分取资源名。
            by_type[typ].add(_strip_legacy(p.name.split(".")[0]))
    return {
        "by_type": {k: sorted(v) for k, v in sorted(by_type.items())},
        "files_by_dir": dict(sorted(files_by_dir.items())),
    }


def production_refs() -> dict:
    """统计自研源码 / 构建脚本对暂存区与 :ui-legacy 的引用方。"""
    hits: list[dict] = []
    for pattern in ("*.kt", "*.java", "*.xml", "*.kts", "*.gradle"):
        for p in ROOT.rglob(pattern):
            if not p.is_file():
                continue
            rp = p.relative_to(ROOT).as_posix()
            if rp.startswith("app/src/main/assets/legacy-player/"):
                continue
            if any(seg in rp for seg in ("/build/", "/.gradle/", "analysis/", ".workbuddy/")):
                continue
            try:
                text = p.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            found = [n for n in ("legacy-player", "R.layout.legacy_", ":ui-legacy")
                     if n in text]
            if found:
                hits.append({"file": rp, "matches": found})
    source_refs = [h for h in hits if "legacy-player" in h["matches"]
                   or "R.layout.legacy_" in h["matches"]]
    ui_legacy_refs = [h for h in hits if ":ui-legacy" in h["matches"]]
    return {
        "staged_asset_production_ref_count": len(source_refs),
        "staged_asset_production_refs": sorted(source_refs, key=lambda h: h["file"]),
        "ui_legacy_mentions": sorted(ui_legacy_refs, key=lambda h: h["file"]),
        "ui_legacy_include_active": 'include(":ui-legacy")' in SETTINGS.read_text(encoding="utf-8"),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default="analysis/legacy-layout-audit.json")
    args = ap.parse_args()

    if not LAYOUT_DIR.is_dir():
        print(f"[FAIL] 暂存 layout 目录不存在：{LAYOUT_DIR}", file=sys.stderr)
        return 1

    rows = load_mapping()
    mapping_keys = {(r["type"], r["legacy_name"]) for r in rows}
    closure = json.loads(CLOSURE_JSON.read_text(encoding="utf-8"))
    closure_keys = {(r["type"], r["name"]) for r in closure["resources"]}
    staged = staged_resource_names()
    staged_keys = {(t, n) for t, names in staged["by_type"].items() for n in names}

    layouts = []
    for path in sorted(LAYOUT_DIR.glob("*.xml")):
        item = parse_layout(path)
        export_refs = [r for r in item["resource_refs"] if r["type"] in EXPORTABLE_TYPES]
        item["exportable_refs"] = sorted({f'{r["type"]}/{r["name"]}' for r in export_refs})
        item["exportable_ref_count"] = len(export_refs)
        item["refs_in_mapping"] = sorted(
            {f'{r["type"]}/{r["name"]}' for r in export_refs
             if (r["type"], r["name"]) in mapping_keys}
        )
        item["refs_missing_in_mapping"] = sorted(
            {f'{r["type"]}/{r["name"]}' for r in export_refs
             if (r["type"], r["name"]) not in mapping_keys}
        )
        item["refs_in_closure"] = sorted(
            {f'{r["type"]}/{r["name"]}' for r in export_refs
             if (r["type"], r["name"]) in closure_keys}
        )
        item["refs_with_staged_definition"] = sorted(
            {f'{r["type"]}/{r["name"]}' for r in export_refs
             if (r["type"], r["name"]) in staged_keys}
        )
        item["refs_missing_staged_definition"] = sorted(
            {f'{r["type"]}/{r["name"]}' for r in export_refs
             if (r["type"], r["name"]) not in staged_keys}
        )
        item["id_ref_count"] = sum(1 for r in item["resource_refs"] if r["type"] == "id")
        item["attr_ref_count"] = sum(1 for r in item["resource_refs"] if r["type"] == "attr")
        item["system_ref_count"] = sum(
            1 for r in item["resource_refs"] if r["sigil"] == "@android:"
        )
        layouts.append(item)

    def union(field: str) -> set[str]:
        return {x for item in layouts for x in item[field]}

    all_netease = sorted({c for item in layouts for c in item["netease_classes"]})
    all_app_attrs = sorted({a for item in layouts for a in item["app_attrs"]})
    ref_by_type: dict[str, set[str]] = defaultdict(set)
    for item in layouts:
        for r in item["resource_refs"]:
            ref_by_type[r["type"]].add(r["name"])

    branded = [i["name"] for i in layouts if i["netease_classes"]]
    plain = [i["name"] for i in layouts if not i["netease_classes"]]
    prod = production_refs()

    t = {
        "layout_count": len(layouts),
        "well_formed_xml": sum(1 for i in layouts if i["well_formed_xml"]),
        "element_count": sum(i["element_count"] for i in layouts),
        "netease_class_occurrences_raw": sum(
            i["netease_class_occurrences_raw"] for i in layouts
        ),
        "netease_class_unique": len(all_netease),
        "app_attr_unique": len(all_app_attrs),
        "resource_ref_occurrences": sum(len(i["resource_refs"]) for i in layouts),
        "exportable_ref_occurrences": sum(i["exportable_ref_count"] for i in layouts),
        "exportable_ref_unique": len(union("exportable_refs")),
        "refs_in_mapping_unique": len(union("refs_in_mapping")),
        "refs_missing_in_mapping_unique": len(union("refs_missing_in_mapping")),
        "refs_in_closure_unique": len(union("refs_in_closure")),
        "refs_with_staged_definition_unique": len(union("refs_with_staged_definition")),
        "refs_missing_staged_definition_unique": len(union("refs_missing_staged_definition")),
        "id_ref_occurrences": sum(i["id_ref_count"] for i in layouts),
        "plus_id_ref_occurrences": sum(i["plus_id_ref_count"] for i in layouts),
        "attr_ref_occurrences": sum(i["attr_ref_count"] for i in layouts),
        "system_ref_occurrences": sum(i["system_ref_count"] for i in layouts),
        "null_ref_occurrences": sum(i["null_ref_count"] for i in layouts),
    }

    report = {
        "method": "T-L L3 技术接入前置实证：layout 引用图 + 暂存资源定义覆盖核对 + 生产引用方统计",
        "generated_by": "tools/legacy_layout_audit.py",
        "inputs": {
            "layout_dir": _rel(LAYOUT_DIR),
            "mapping_csv": _rel(MAPPING_CSV),
            "closure_json": _rel(CLOSURE_JSON),
            "mapping_rows": len(rows),
            "closure_count": closure["closure_count"],
            "staged_file_count": sum(staged["files_by_dir"].values()),
            "staged_files_by_dir": staged["files_by_dir"],
            "staged_definitions_by_type": {
                k: len(v) for k, v in staged["by_type"].items()
            },
        },
        "totals": t,
        "layout_partition": {"branded_layouts": branded, "plain_layouts": plain},
        "netease_classes_unique": all_netease,
        "app_attrs_unique": all_app_attrs,
        "resource_refs_unique_by_type": {
            k: sorted(v) for k, v in sorted(ref_by_type.items())
        },
        "missing_in_mapping_unique": sorted(union("refs_missing_in_mapping")),
        "missing_staged_definition_unique": sorted(
            union("refs_missing_staged_definition")
        ),
        "production_refs": prod,
        "layouts": layouts,
    }

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"[OK] {out.relative_to(ROOT).as_posix()}")
    print(f"     layout {t['layout_count']} 个（良构 XML {t['well_formed_xml']}），"
          f"元素 {t['element_count']} 个")
    print(f"     com.netease.* 类名出现 {t['netease_class_occurrences_raw']} 处 / "
          f"去重 {t['netease_class_unique']} 类；app:* attr {t['app_attr_unique']} 个")
    print(f"     资源引用 {t['resource_ref_occurrences']} 处，其中可导出类型 "
          f"{t['exportable_ref_occurrences']} 处 / {t['exportable_ref_unique']} 唯一")
    print(f"     映射表覆盖 {t['refs_in_mapping_unique']} 唯一项、缺口 "
          f"{t['refs_missing_in_mapping_unique']}；闭包覆盖 {t['refs_in_closure_unique']}；"
          f"暂存区有实际定义 {t['refs_with_staged_definition_unique']}、缺 "
          f"{t['refs_missing_staged_definition_unique']}")
    print(f"     @id {t['id_ref_occurrences']}（@+id 定义 {t['plus_id_ref_occurrences']}）/ "
          f"?attr {t['attr_ref_occurrences']} / @android: {t['system_ref_occurrences']} / "
          f"@null {t['null_ref_occurrences']}")
    print(f"     生产引用方 {prod['staged_asset_production_ref_count']} 个；"
          f":ui-legacy include 生效 = {prod['ui_legacy_include_active']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
