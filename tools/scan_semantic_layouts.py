"""§61.137 全页面竞品 UI 重建 · 步骤 1：扫描 decoded/res/layout*/ 全量 android:tag 语义名。

方法依据 docs/14 §2：apktool 解包 layout 根元素保留 android:tag="layout/<原始语义名>_0"，
去掉尾部 _N 即原始语义名。本脚本对全部 layout qualifier 目录建
「语义名 → [混淆文件]」倒排索引，并按页面关键词筛出候选清单。

用法：python -I scan_semantic_layouts.py <decoded_res_dir> <out_json>
只读 decoded 资源，不导出任何竞品素材到 app 工程（C4 纪律：映射/规格提取≠素材复制）。
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

TAG_RE = re.compile(r"^layout/(.+?)(?:_(\d+))?$")


NS_TAG = "{http://schemas.android.com/apk/res/android}tag"


def first_semantic_tag(path):
    """首个带 android:tag="layout/<语义名>_N" 的元素属性值。

    DataBinding 布局根是 <layout> 包装（tag 在真根子元素上），
    merge 根同理，所以不能只看根元素——从解析流里找第一个匹配即止。
    """
    try:
        for _, elem in ET.iterparse(path, events=("start",)):
            t = elem.get(NS_TAG) or ""
            if TAG_RE.match(t):
                return t
        return ""
    except Exception as e:  # 个别 apktool 产物可能空/坏
        return f"__ERR__ {e}"


def main(decoded_res, out_json):
    index = {}          # semantic -> {file, dir, raw_tag}
    errs = []
    notagged = 0
    total = 0
    for d in sorted(os.listdir(decoded_res)):
        if d != "layout" and not d.startswith("layout-"):
            continue
        layout_dir = os.path.join(decoded_res, d)
        if not os.path.isdir(layout_dir):
            continue
        for fn in sorted(os.listdir(layout_dir)):
            if not fn.endswith(".xml"):
                continue
            total += 1
            p = os.path.join(layout_dir, fn)
            tag = first_semantic_tag(p)
            if tag.startswith("__ERR__"):
                errs.append({"file": f"{d}/{fn}", "error": tag})
                continue
            m = TAG_RE.match(tag)
            if not m:
                notagged += 1
                continue
            semantic = m.group(1)
            index.setdefault(semantic, []).append(f"{d}/{fn[:-4]}")
    out = {
        "decoded_res": decoded_res,
        "total_layout_files": total,
        "semantic_named": sum(len(v) for v in index.values()),
        "unique_semantic_names": len(index),
        "no_root_tag_files": notagged,
        "errors": errs[:50],
        "index": dict(sorted(index.items())),
    }
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(
        f"total={total} semantic_files={sum(len(v) for v in index.values())} "
        f"unique_names={len(index)} no_tag={notagged} errors={len(errs)}"
    )


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
