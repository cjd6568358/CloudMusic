#!/usr/bin/env python3
"""P2 · 播放页资源 ID 反查与递归闭包（docs/06 §3 / 03-10 §3）。

本机无 jadx / Android SDK，但 apktool 解包产物齐全。关键实证发现：
`analysis/decoded/res/layout*/*.xml` 的根元素普遍带
    android:tag="layout/<原始资源名>_0"
即**文件名虽混淆，原始语义名仍保存在 tag 里**。因此无需完全依赖
「从 smali 抠 0x7f 常量 → 反查 public.xml」，可以直接用 tag 建立
「语义名 → 混淆文件名 → public.xml id」的映射，再按 XML 引用做递归闭包。

流程（对应 06 §3 ①~⑥）：
  ① 解析 public.xml，建立 (type,name) → id / id → (type,name)
  ② 扫描 layout*/ 的 android:tag，筛出播放页入口
  ③ 从入口 XML 抽 `@type/name`、`@+id/name`、`?attr/name` 引用
  ④ 对 layout/drawable/anim/... 的单文件资源继续解析，values/* 里按 name 展开
  ⑤ BFS 直到闭包稳定，产出 JSON + CSV（写 analysis/，不动 docs/assets-mapping.csv）
  ⑥ 控制台摘要

⑦ 导出到 :ui-legacy + ⑧ 登记 assets-mapping.csv + ⑨ 编译验证仍待 Android SDK，
   本脚本只产「可执行清单」，保持 assets-mapping.csv 空表语义与 C4 PASS。

用法：
    python tools/player_resource_audit.py
    python tools/player_resource_audit.py --entries activity_player_audio,fragment_mi_miniplayer
"""
from __future__ import annotations

import argparse
import csv
import glob
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict, deque
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "analysis" / "decoded" / "res"
PUBLIC_XML = RES / "values" / "public.xml"
DEFAULT_JSON = ROOT / "analysis" / "player-resource-closure.json"
DEFAULT_CSV = ROOT / "analysis" / "player-resource-closure.csv"

# 播放页主入口（语义名，来自 android:tag）；默认覆盖唱片/唱针/横屏/迷你条等场景
DEFAULT_ENTRIES = (
    "activity_player_audio", "activity_new_player_land", "activity_new_player_vehicle",
    "layout_player_landscape_vinyl", "layout_land_vinyl", "layout_player_backgroud",
    "layout_item_land_vinyl", "fragment_mi_miniplayer", "view_vinyl_records",
)

PUBLIC_RE = re.compile(r'<public\s+type="([^"]+)"\s+name="([^"]+)"\s+id="(0x[0-9a-fA-F]+)"')
TAG_RE = re.compile(r'android:tag="layout/([^"]+)"')
# @drawable/ga6、@+id/disk、@dimen/aui；@android:* 因含冒号不会命中
REF_RE = re.compile(r'@\+?(?P<type>[A-Za-z][A-Za-z0-9_]*)/(?P<name>[A-Za-z0-9_.$]+)')
ATTR_REF_RE = re.compile(r'\?(?:attr/)?(?P<name>[A-Za-z0-9_.$]+)')
SEMANTIC_SUFFIX = re.compile(r"_\d+$")

# 这些类型不需要导出素材文件（id 是控件标识，attr 是主题属性），但仍登记以便闭包完整
NO_EXPORT_TYPES = {"id", "attr", "styleable", "plurals"}
VALUES_TYPES = {"color", "dimen", "style", "string", "bool", "integer", "array", "attr", "id"}


def norm(name: str) -> str:
    """资源名可能带 `_0` 之类的 apktool 后缀，统一去掉用于语义展示。"""
    return SEMANTIC_SUFFIX.sub("", name)


def load_public():
    by_name: dict[tuple[str, str], str] = {}
    by_id: dict[str, tuple[str, str]] = {}
    text = PUBLIC_XML.read_text(encoding="utf-8", errors="replace")
    for typ, name, rid in PUBLIC_RE.findall(text):
        by_name[(typ, name)] = rid
        by_id[rid.lower()] = (typ, name)
    return by_name, by_id


def build_layout_tags():
    """返回 {语义名: [layout 文件相对路径]}。"""
    tags: dict[str, list[str]] = defaultdict(list)
    for path in glob.glob(str(RES / "layout*" / "*.xml")):
        p = Path(path)
        try:
            head = p.read_text(encoding="utf-8", errors="replace")[:4000]
        except OSError:
            continue
        m = TAG_RE.search(head)
        if m:
            tags[norm(m.group(1))].append(p.relative_to(ROOT).as_posix())
    return tags


def build_file_index():
    """(type, name) → 实际资源文件列表（含各 qualifier 变体）。"""
    index: dict[tuple[str, str], list[str]] = defaultdict(list)
    for path in glob.glob(str(RES / "*" / "*")):
        p = Path(path)
        if not p.is_file():
            continue
        parent = p.parent.name
        typ = parent.split("-", 1)[0]
        stem = p.name.split(".", 1)[0]
        index[(typ, stem)].append(p.relative_to(ROOT).as_posix())
    return index


def build_values_index():
    """values 类资源：key → 引用集合，以及 key → 定义所在文件。"""
    values: dict[tuple[str, str], set[tuple[str, str]]] = defaultdict(set)
    where: dict[tuple[str, str], list[str]] = defaultdict(list)
    for path in glob.glob(str(RES / "values*" / "*.xml")):
        p = Path(path)
        try:
            root = ET.parse(p).getroot()
        except (ET.ParseError, OSError):
            continue
        for node in root.iter():
            name = node.attrib.get("name")
            if not name:
                continue
            tag = node.tag.split("}")[-1]
            if tag in ("item", "public", "eat-comment", "declare-styleable"):
                continue
            typ = "attr" if tag == "attr" else tag
            if typ not in VALUES_TYPES and typ not in ("color", "dimen", "style", "string", "bool", "integer", "array"):
                continue
            blob = " ".join(
                [str(v) for v in node.attrib.values()] + [node.text or ""] +
                [" ".join(str(v) for v in child.attrib.values()) + " " + (child.text or "")
                 for child in node.iter() if child is not node]
            )
            refs = {(m.group("type"), m.group("name")) for m in REF_RE.finditer(blob)}
            parent = node.attrib.get("parent", "")
            refs.update((m.group("type"), m.group("name")) for m in REF_RE.finditer(parent))
            values[(typ, name)].update(refs)
            where[(typ, name)].append(p.relative_to(ROOT).as_posix())
    return values, where


def refs_of_text(text: str):
    out: set[tuple[str, str]] = set()
    for m in REF_RE.finditer(text):
        out.add((m.group("type"), m.group("name")))
    for m in ATTR_REF_RE.finditer(text):
        out.add(("attr", m.group("name")))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="P2 · 播放页资源 ID 反查与递归闭包")
    ap.add_argument("--entries", default=",".join(DEFAULT_ENTRIES),
                    help="逗号分隔的入口语义名（android:tag 值去掉 _0 后缀）")
    ap.add_argument("--json", default=str(DEFAULT_JSON))
    ap.add_argument("--csv", default=str(DEFAULT_CSV))
    args = ap.parse_args()

    if not PUBLIC_XML.is_file():
        print(f"[FAIL] 缺 {PUBLIC_XML}")
        return 1

    by_name, by_id = load_public()
    tags = build_layout_tags()
    file_index = build_file_index()
    values_index, values_where = build_values_index()
    print(f"public.xml: {len(by_name)} 条；layout tag 语义名 {len(tags)} 个；"
          f"资源文件索引 {len(file_index)} 组；values 定义 {len(values_index)} 组")

    entries = [e.strip() for e in args.entries.split(",") if e.strip()]
    queue: deque[tuple[tuple[str, str], str]] = deque()
    seen: dict[tuple[str, str], dict] = {}
    edges: set[tuple[str, str]] = set()

    for semantic in entries:
        paths = tags.get(semantic, [])
        if not paths:
            print(f"[WARN] 入口 layout 未找到 tag：{semantic}")
            continue
        for path in paths:
            name = Path(path).stem
            key = ("layout", name)
            seen[key] = {
                "type": "layout", "name": name,
                "resource_id": by_name.get(key, ""),
                "semantic_name": semantic,
                "files": [path], "origin": "entry",
                "export_needed": True,
            }
            queue.append((key, path))

    # BFS：单文件资源解析内容；values 类资源查定义索引
    while queue:
        key, path = queue.popleft()
        typ, name = key
        text = ""
        if path:
            try:
                text = (ROOT / path).read_text(encoding="utf-8", errors="replace")
            except OSError:
                text = ""
        refs = refs_of_text(text)
        if typ in VALUES_TYPES:
            refs.update(values_index.get((typ, name), set()))

        for ref in sorted(refs):
            rtype, rname = ref
            if ref not in seen:
                rid = by_name.get(ref, "")
                files = file_index.get(ref, [])
                # values 类资源定义在 values*/ 聚合文件里，用解析阶段记录的位置
                if rtype in VALUES_TYPES and not files:
                    files = values_where.get(ref, [])
                seen[ref] = {
                    "type": rtype, "name": rname,
                    "resource_id": rid,
                    "semantic_name": "",
                    "files": sorted(set(files)),
                    "origin": "reference",
                    "export_needed": rtype not in NO_EXPORT_TYPES,
                }
                if rtype in VALUES_TYPES:
                    queue.append((ref, ""))  # 引用已在 values_index 展开
                else:
                    for f in files:
                        if f.lower().endswith(".xml"):
                            queue.append((ref, f))
            edges.add((f"{typ}/{name}", f"{rtype}/{rname}"))

    # 输出
    payload = {
        "method": "P2 播放页资源反查：android:tag 语义名 + public.xml id + XML 引用 BFS 闭包",
        "entries": entries,
        "public_xml_entries": len(by_name),
        "layout_semantic_tags": {k: v for k, v in sorted(tags.items())
                                 if re.search(r"(player|vinyl|disc)", k, re.I)},
        "closure_count": len(seen),
        "by_type": dict(sorted(
            ((t, sum(1 for k in seen if k[0] == t)) for t in {k[0] for k in seen}),
            key=lambda x: -x[1])),
        "unresolved_in_public_xml": sorted(
            f"{t}/{n}" for (t, n), v in seen.items() if not v["resource_id"]),
        "resources": [seen[k] for k in sorted(seen, key=lambda x: (x[0], x[1]))],
        "edge_count": len(edges),
        "edges": [{"from": a, "to": b} for a, b in sorted(edges)],
    }
    Path(args.json).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    with open(args.csv, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["type", "name", "resource_id", "semantic_name", "files",
                    "origin", "export_needed"])
        for k in sorted(seen, key=lambda x: (x[0], x[1])):
            v = seen[k]
            w.writerow([v["type"], v["name"], v["resource_id"], v["semantic_name"],
                        ";".join(v["files"])[:300], v["origin"], v["export_needed"]])

    print(f"\n入口 {len(entries)} 个 → 闭包 {len(seen)} 项资源 / {len(edges)} 条引用边")
    print("按类型：" + ", ".join(f"{k}={v}" for k, v in payload["by_type"].items()))
    missing = payload["unresolved_in_public_xml"]
    print(f"public.xml 未登记（系统资源 / 动态引用）：{len(missing)} 项，例：{missing[:5]}")
    print("\n=== 播放页语义名 → 混淆文件（节选） ===")
    for k in entries:
        print(f"  {k:38s} {', '.join(tags.get(k, ['<未找到>']))}")
    print(f"\nJSON -> {args.json}\nCSV  -> {args.csv}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
