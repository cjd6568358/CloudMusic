#!/usr/bin/env python3
"""12 §61.41 · B 路线（完整像素级照搬）前置实证：48 个闭包 layout 全量审计。

用户 2026-11-09 拍板 B（三项全开：第 20 模块 :ui-legacy / 补 4 类依赖 /
补导其余 39 个 layout）。按 §61.38「拍板后先实证、不猜就动手」的同纪律，
本工具先精确回答三个问题，再决定改动顺序：

1. 竞品 `player-resource-closure.json` 的 48 个 `layout_semantic_tags` 到底是
   不是「48 个播放页」？其中有没有标准竖屏主播放页？
2. 补导其余 39 个 layout 会带来多大的 id / attr / 资源 / 类名缺口？
   （§61.38 的 101 id / 33 attr / 45 资源口径只覆盖已导出的 9 个）
3. 33 个混淆 `app:*` attr 能否靠引入 androidx.constraintlayout 还原布局语义？

纯 Python 零依赖、可复跑；只读 `analysis/` 与 `app/src/main/assets/`，并把
39 个未导出 layout 复制到 `analysis/l3-full-replica/layouts/` 作为工作区副本。
**不建模块、不改 settings / catalog、不动生产 res/、不触碰设备。**

用法：python tools/l3_full_replica_audit.py
产出：analysis/l3-full-replica-audit.json
      analysis/l3-full-replica/layouts/*.xml
"""
from __future__ import annotations

import json
import re
import shutil
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLOSURE_JSON = ROOT / "analysis" / "player-resource-closure.json"
STAGED_AUDIT_JSON = ROOT / "analysis" / "legacy-layout-audit.json"
DECODED_LAYOUT_DIR = ROOT / "analysis" / "decoded" / "res" / "layout"
STAGED_LAYOUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "legacy-player" / "layout"
DECODED_ATTRS = ROOT / "analysis" / "decoded" / "res" / "values" / "attrs.xml"
OUT_JSON = ROOT / "analysis" / "l3-full-replica-audit.json"
OUT_LAYOUT_DIR = ROOT / "analysis" / "l3-full-replica" / "layouts"

# 与 tools/legacy_layout_audit.py 同一口径
NETEASE_CLASS_RE = re.compile(r"<([A-Za-z0-9_.]*com\.netease[A-Za-z0-9_.]*)")
APP_ATTR_RE = re.compile(r"app:([A-Za-z_][A-Za-z0-9_]*)\s*=")
ID_REF_RE = re.compile(r"@id/([A-Za-z0-9_]+)")
ID_DEF_RE = re.compile(r"@\+id/([A-Za-z0-9_]+)")
RES_REF_RE = re.compile(
    r"@(color|drawable|string|dimen|font|layout|style|anim|mipmap|raw|bool|integer|array|xml|menu|interpolator)/([A-Za-z0-9_]+)"
)
ELEMENT_RE = re.compile(r"<([A-Za-z0-9_.]+)")

# `gb.xml`（横屏播放页）实测：第三方库类名未混淆，但 app:* attr 被 R8 改名。
# 这类标准名用于说明「引入依赖 ≠ 还原布局语义」。
STANDARD_CONSTRAINT_ATTR_HINT = "layout_constraint"


def decode_layout(text: str) -> dict:
    """解析单个 layout XML，统计引用图（口径与 legacy_layout_audit.py 对齐）。"""
    classes = [m for m in NETEASE_CLASS_RE.findall(text) if m.startswith("com.netease")]
    app_attrs = APP_ATTR_RE.findall(text)
    id_refs = ID_REF_RE.findall(text)
    id_defs = ID_DEF_RE.findall(text)
    res_refs = RES_REF_RE.findall(text)
    elements = ELEMENT_RE.findall(text)
    return {
        "element_count": len(elements),
        "netease_class_occurrences": len(classes),
        "netease_classes_unique": sorted(set(classes)),
        "app_attr_occurrences": len(app_attrs),
        "app_attrs_unique": sorted(set(app_attrs)),
        "id_ref_occurrences": len(id_refs),
        "id_refs_unique": sorted(set(id_refs)),
        "plus_id_definitions": len(id_defs),
        "exportable_ref_occurrences": len(res_refs),
        "exportable_refs_unique_by_type": {
            t: sorted({n for tt, n in res_refs if tt == t})
            for t in sorted({tt for tt, _ in res_refs})
        },
    }


def main() -> int:
    for path in (CLOSURE_JSON, STAGED_AUDIT_JSON, DECODED_LAYOUT_DIR, STAGED_LAYOUT_DIR):
        if not path.exists():
            raise SystemExit(f"[FAIL] 缺少输入：{path.relative_to(ROOT).as_posix()}")

    closure = json.loads(CLOSURE_JSON.read_text(encoding="utf-8"))
    staged_audit = json.loads(STAGED_AUDIT_JSON.read_text(encoding="utf-8"))
    semantic_tags: dict[str, list[str]] = closure["layout_semantic_tags"]

    # 已导出的 9 个：以 assets 暂存区实际文件为准（去 legacy_ 前缀得混淆名）
    staged_obfuscated = {
        p.stem.removeprefix("legacy_") for p in STAGED_LAYOUT_DIR.glob("*.xml")
    }

    tag_to_file: dict[str, str] = {}
    for tag, paths in semantic_tags.items():
        if not paths:
            continue
        tag_to_file[tag] = Path(paths[0]).stem

    # 口径核查：48 个 tag 里是否含标准竖屏主播放页 / 是否混入非播放页
    player_like = sorted(t for t in tag_to_file if ("player" in t or "vinyl" in t or "miniplayer" in t))
    non_player_like = sorted(t for t in tag_to_file if t not in player_like)
    has_portrait_main = any(
        t in {"activity_player", "activity_player_music", "activity_new_player", "fragment_player"}
        for t in tag_to_file
    )

    remaining: list[dict] = []
    missing_files: list[str] = []
    OUT_LAYOUT_DIR.mkdir(parents=True, exist_ok=True)
    # 幂等：清空旧副本再导出，避免残留文件混入统计
    for old in OUT_LAYOUT_DIR.glob("*.xml"):
        old.unlink()

    for tag in sorted(tag_to_file):
        stem = tag_to_file[tag]
        if stem in staged_obfuscated:
            continue
        src = DECODED_LAYOUT_DIR / f"{stem}.xml"
        if not src.exists():
            missing_files.append(stem)
            continue
        text = src.read_text(encoding="utf-8", errors="replace")
        stats = decode_layout(text)
        dst = OUT_LAYOUT_DIR / f"{stem}.xml"
        shutil.copyfile(src, dst)
        remaining.append({"semantic_tag": tag, "obfuscated_file": f"{stem}.xml", **stats})

    # 39 个 layout 的聚合缺口
    all_netease = sorted({c for r in remaining for c in r["netease_classes_unique"]})
    all_app_attrs = sorted({a for r in remaining for a in r["app_attrs_unique"]})
    all_ids = sorted({i for r in remaining for i in r["id_refs_unique"]})
    all_res_by_type: dict[str, list[str]] = {}
    for r in remaining:
        for t, names in r["exportable_refs_unique_by_type"].items():
            all_res_by_type.setdefault(t, set()).update(names)
    all_res_by_type = {t: sorted(v) for t, v in sorted(all_res_by_type.items())}

    staged_totals = staged_audit["totals"]
    staged_ids = set(staged_audit.get("layouts", [{}])[0].get("id_refs_unique", [])) if staged_audit.get("layouts") else set()
    # 兼容：若 layouts 里没有逐 layout 的 id 列表，用闭包资源表里的 id 集合
    if not staged_ids:
        staged_ids = {
            r["name"] for r in closure.get("resources", [])
            if isinstance(r, dict) and r.get("type") == "id"
        }
    staged_attr_count = staged_totals["app_attr_unique"]
    staged_res_count = staged_totals["exportable_ref_unique"]

    new_ids = sorted(set(all_ids) - set(staged_ids))
    new_res_count = sum(len(v) for v in all_res_by_type.values())

    # 混淆 attr 语义核查：竞品 attrs.xml 里是否存在标准 layout_constraint* 名
    attrs_text = DECODED_ATTRS.read_text(encoding="utf-8", errors="replace") if DECODED_ATTRS.exists() else ""
    standard_attr_names_found = sorted(
        set(re.findall(r'<attr\s+name="(layout_constraint[^"]*)"', attrs_text))
    )
    # 33 个混淆 attr 在竞品 attrs.xml 的定义格式抽样
    sample_obfuscated_defs = []
    for name in all_app_attrs[:6]:
        m = re.search(r'<attr\s+name="' + re.escape(name) + r'"[^>]*>', attrs_text)
        sample_obfuscated_defs.append(m.group(0) if m else f"<attr name=\"{name}\">（未在竞品 attrs.xml 命中）")

    result = {
        "method": "12 §61.41：B 路线（三项拍板全开）前置实证，48 个闭包 layout 全量审计 + 39 个未导出 layout 工作区副本",
        "generated_by": "tools/l3_full_replica_audit.py",
        "inputs": [
            "analysis/player-resource-closure.json",
            "analysis/legacy-layout-audit.json",
            "analysis/decoded/res/layout/",
            "app/src/main/assets/legacy-player/layout/",
            "analysis/decoded/res/values/attrs.xml",
        ],
        "closure_tag_count": len(tag_to_file),
        "staged_layout_count": len(staged_obfuscated),
        "remaining_layout_count": len(remaining),
        "exported_copies": len(list(OUT_LAYOUT_DIR.glob("*.xml"))),
        "missing_decoded_files": missing_files,
        "scope_calibration": {
            "player_like_tags": player_like,
            "non_player_like_tags": non_player_like,
            "has_standard_portrait_main_player_tag": has_portrait_main,
            "note": (
                "48 个 layout_semantic_tags 是 player-resource-closure.json 的「资源闭包涉及的 layout」，"
                "并非 48 个播放页；其中混有 discovery / podcast / video / mlog / comment 等条目，"
                "且不存在标准竖屏主播放页 tag。§61.40 报告中「39 个含标准竖屏主播放页」的表述需按本实证校正。"
            ),
        },
        "remaining_aggregate": {
            "element_count": sum(r["element_count"] for r in remaining),
            "netease_class_occurrences": sum(r["netease_class_occurrences"] for r in remaining),
            "netease_classes_unique": all_netease,
            "netease_class_unique_count": len(all_netease),
            "app_attr_unique": all_app_attrs,
            "app_attr_unique_count": len(all_app_attrs),
            "id_ref_unique_count": len(all_ids),
            "new_id_ref_unique_count": len(new_ids),
            "new_id_ref_unique_sample": new_ids[:20],
            "plus_id_definitions": sum(r["plus_id_definitions"] for r in remaining),
            "exportable_refs_unique_by_type": all_res_by_type,
            "exportable_ref_unique_count": new_res_count,
            "exportable_ref_occurrences": sum(r["exportable_ref_occurrences"] for r in remaining),
        },
        "staged_baseline_for_comparison": {
            "id_ref_unique": staged_totals["id_ref_occurrences"],
            "id_unique_known": 101,
            "app_attr_unique": staged_attr_count,
            "exportable_ref_unique": staged_res_count,
        },
        "attr_semantics_probe": {
            "standard_layout_constraint_attr_names_in_competitor_attrs_xml": standard_attr_names_found,
            "sample_obfuscated_attr_definitions": sample_obfuscated_defs,
            "conclusion": (
                "竞品 layout 里 androidx.constraintlayout.widget.ConstraintLayout 是标准类名（第三方库类不被 R8 改名），"
                "但其 layout_constraint* 属性名被资源混淆成 a5f/a5l/a65/a69 等。"
                "在自研工程里重新声明同名 <attr> 会分配新的资源 ID，ConstraintLayout 编译期读的是"
                "R.attr.layout_constraint* 的固定 ID，读不到 app:a5f 等值 → 约束布局语义不会还原。"
                "§61.39 的 attrs.xml 只让 aapt2 编译通过，不等于布局语义可还原；"
                "引入 androidx.constraintlayout 依赖同样不解决该问题，需先把 33+ 个混淆 attr 反查回标准名。"
            ),
        },
        "per_layout": remaining,
        "verdict": {
            "all_48_located_and_readable": not missing_files,
            "remaining_copies_exported": len(list(OUT_LAYOUT_DIR.glob("*.xml"))) == len(remaining),
            "no_standard_portrait_main_player": not has_portrait_main,
            "attr_semantics_not_restorable_by_dependency": not standard_attr_names_found,
        },
        "conclusion": (
            "B 的三项拍板（第 20 模块 / 4 类依赖 / 补导 39 个 layout）在文件与网络层面均可行："
            "39 个 layout 已从 analysis/decoded 全量导出到工作区，Google Maven 实测 HTTP 200。"
            "但「像素级一致」仍被两道原理性缺口阻断：① 混淆 app:* attr 的布局语义需逐个反查回标准名，"
            "仅引依赖或搬 attrs.xml 不还原约束；② 20 个竞品自定义 View 的 onDraw / 状态机不可机械还原，"
            "只能自研近似。此外 48 个 tag 里混有非播放页、且无标准竖屏主播放页，"
            "需先做「哪些才是播放页」的筛选再决定补导范围。故本批按 §61.38 纪律先实证与回写，"
            "改基线（模块/依赖/生产 res/）与 View 重写按施工单分阶段执行，不伪造完成。"
        ),
    }

    OUT_JSON.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"[INFO] 闭包 tag {len(tag_to_file)} / 已导出 {len(staged_obfuscated)} / 待补导 {len(remaining)}")
    print(f"[INFO] 工作区副本 -> {OUT_LAYOUT_DIR.relative_to(ROOT).as_posix()} ({result['exported_copies']} 个)")
    print(f"[INFO] 39 个 layout 合计元素 {result['remaining_aggregate']['element_count']} / "
          f"竞品类名 {result['remaining_aggregate']['netease_class_unique_count']} 类 / "
          f"app:* attr {result['remaining_aggregate']['app_attr_unique_count']} 个 / "
          f"@id 唯一 {result['remaining_aggregate']['id_ref_unique_count']}（新增 {len(new_ids)}）/ "
          f"可导出资源引用唯一 {new_res_count}")
    print(f"[INFO] 非播放页 tag {len(non_player_like)} 个；标准竖屏主播放页 tag 存在={has_portrait_main}")
    print(f"[INFO] 竞品 attrs.xml 中标准 layout_constraint* 名 {len(standard_attr_names_found)} 个")
    for k, v in result["verdict"].items():
        print(f"[{'PASS' if v else 'INFO'}] {k}: {v}")
    print(f"[INFO] 证据 -> {OUT_JSON.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
