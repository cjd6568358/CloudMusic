#!/usr/bin/env python3
"""T-L L3 施工单阶段 A/B 前置：把 33 个混淆 attr 与 101 个 id 精确还原。

纯 Python 零依赖、只读生产文件。依据 `12 §61.38` 的 layout 引用图，从竞品反编译
资源表 `analysis/decoded/res/values/attrs.xml` 与 `public.xml` 里解析出：

- 33 个 `app:*` 混淆 attr 的**完整定义**（format + enum/flag 子项 + public id）；
- 101 个 `@id/` 引用的**public.xml 条目与 resource_id**；
- 据此生成可直接用于接 `res/` 的候选 `ids.xml` / `attrs.xml`。

**候选文件只写到 `analysis/l3-probe/`，不写进 `app/src/main/res/`**，不改变任何
生产源码 / 资源 / 构建输入。输出 `analysis/legacy-layout-resolve.json`。

用法：
    python tools/legacy_layout_resolve.py
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIT_JSON = ROOT / "analysis" / "legacy-layout-audit.json"
ATTRS_XML = ROOT / "analysis" / "decoded" / "res" / "values" / "attrs.xml"
PUBLIC_XML = ROOT / "analysis" / "decoded" / "res" / "values" / "public.xml"
PROBE_DIR = ROOT / "analysis" / "l3-probe"
OUT_JSON = ROOT / "analysis" / "legacy-layout-resolve.json"


def _rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def load_targets() -> tuple[list[str], list[str]]:
    audit = json.loads(AUDIT_JSON.read_text(encoding="utf-8"))
    return (sorted(audit["app_attrs_unique"]),
            sorted(audit["resource_refs_unique_by_type"]["id"]))


def parse_attr_defs(targets: list[str]) -> dict[str, dict]:
    root = ET.parse(ATTRS_XML).getroot()
    wanted = set(targets)
    found: dict[str, dict] = {}
    for child in root:
        if child.tag != "attr" or child.get("name") not in wanted:
            continue
        name = child.get("name")
        kids = [k.tag for k in child]
        # 保留 enum/flag 子项；element.tail 会带原文件缩进，序列化后统一清理
        xml = ET.tostring(child, encoding="unicode").strip()
        found[name] = {
            "name": name,
            "format": child.get("format") or "(none)",
            "child_tags": kids,
            "child_count": len(kids),
            "xml": xml,
        }
    return found


def parse_public() -> dict[str, dict[str, str]]:
    pub: dict[str, dict[str, str]] = {}
    for _event, el in ET.iterparse(PUBLIC_XML, events=("end",)):
        if el.tag == "public":
            pub.setdefault(el.get("type"), {})[el.get("name")] = el.get("id")
        el.clear()
    return pub


def build_ids_xml(names: list[str]) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    lines += [f'    <item type="id" name="{n}" />' for n in names]
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def build_attrs_xml(defs: dict[str, dict], order: list[str]) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name in order:
        d = defs[name]
        for line in d["xml"].splitlines():
            lines.append("    " + line.strip())
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default="analysis/legacy-layout-resolve.json")
    args = ap.parse_args()

    for p in (AUDIT_JSON, ATTRS_XML, PUBLIC_XML):
        if not p.is_file():
            print(f"[FAIL] 缺少输入：{p}", file=sys.stderr)
            return 1

    attrs, ids = load_targets()
    defs = parse_attr_defs(attrs)
    pub = parse_public()
    pub_attrs = pub.get("attr", {})
    pub_ids = pub.get("id", {})

    missing_attrs = [a for a in attrs if a not in defs]
    missing_ids = [n for n in ids if n not in pub_ids]
    missing_pub_attr_id = [a for a in attrs if a not in pub_attrs]

    id_entries = [{"name": n, "resource_id": pub_ids.get(n)} for n in ids]
    attr_entries = []
    for a in attrs:
        d = defs.get(a)
        attr_entries.append({
            "name": a,
            "format": d["format"] if d else None,
            "child_tags": d["child_tags"] if d else [],
            "public_id": pub_attrs.get(a),
            "defined": d is not None,
        })

    ids_xml = build_ids_xml(ids)
    attrs_xml = build_attrs_xml({k: v for k, v in defs.items()}, attrs)

    PROBE_DIR.mkdir(parents=True, exist_ok=True)
    (PROBE_DIR / "ids.xml").write_text(ids_xml, encoding="utf-8")
    (PROBE_DIR / "attrs.xml").write_text(attrs_xml, encoding="utf-8")

    report = {
        "method": "T-L L3 阶段 A/B 前置：从竞品 attrs.xml / public.xml 精确还原混淆 attr 与 id",
        "generated_by": "tools/legacy_layout_resolve.py",
        "inputs": {
            "audit_json": _rel(AUDIT_JSON),
            "attrs_xml": _rel(ATTRS_XML),
            "public_xml": _rel(PUBLIC_XML),
            "attrs_xml_total_attr_defs": sum(
                1 for c in ET.parse(ATTRS_XML).getroot() if c.tag == "attr"
            ),
            "public_xml_id_entries": len(pub_ids),
            "public_xml_attr_entries": len(pub_attrs),
        },
        "targets": {"attr_count": len(attrs), "id_count": len(ids)},
        "coverage": {
            "attr_defined": len(attrs) - len(missing_attrs),
            "attr_missing": missing_attrs,
            "attr_with_public_id": len(attrs) - len(missing_pub_attr_id),
            "attr_missing_public_id": missing_pub_attr_id,
            "id_in_public_xml": len(ids) - len(missing_ids),
            "id_missing": missing_ids,
        },
        "attr_format_distribution": {
            f: sum(1 for e in attr_entries if e["format"] == f)
            for f in sorted({e["format"] for e in attr_entries}, key=str)
        },
        "attr_with_enum_flag_children": sum(
            1 for e in attr_entries if e["child_tags"]
        ),
        "attr_entries": attr_entries,
        "id_entries": id_entries,
        "generated_files": {
            "ids_xml": _rel(PROBE_DIR / "ids.xml"),
            "attrs_xml": _rel(PROBE_DIR / "attrs.xml"),
            "ids_xml_line_count": len(ids_xml.splitlines()),
            "attrs_xml_line_count": len(attrs_xml.splitlines()),
        },
        "conclusion": (
            "33/33 attr 与 101/101 id 在竞品资源表中均有精确定义；"
            "阶段 A/B 为机械可搬，不需要猜测语义。"
        ) if not (missing_attrs or missing_ids) else "存在缺口，需人工判定",
    }

    out = ROOT / args.out
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    c = report["coverage"]
    print(f"[OK] {out.relative_to(ROOT).as_posix()}")
    print(f"     attr 定义 {c['attr_defined']}/{len(attrs)}（缺失 {len(missing_attrs)}），"
          f"public id {c['attr_with_public_id']}/{len(attrs)}")
    print(f"     id 在 public.xml {c['id_in_public_xml']}/{len(ids)}"
          f"（缺失 {len(missing_ids)}）")
    print(f"     attr format 分布 {report['attr_format_distribution']}；"
          f"带 enum/flag 子项 {report['attr_with_enum_flag_children']} 个")
    print(f"     候选资源：{_rel(PROBE_DIR / 'ids.xml')} / "
          f"{_rel(PROBE_DIR / 'attrs.xml')}（仅 analysis，未进生产 res）")
    print(f"     结论：{report['conclusion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
