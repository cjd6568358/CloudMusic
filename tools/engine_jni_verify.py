#!/usr/bin/env python3
"""T-L L1 步骤 2 核对：jadx 真实源码 ↔ JNI 签名索引（12 §34）。

输入（均为仓库内产物）：
- `analysis/engine-jni-classes.json`：`tools/engine_jni_class_index.py` 生成的
  19 类 / 12 精确 JNI 类 / 162 native 方法声明索引（来自 dex 描述符）。
- `analysis/jadx-jni-classes/**/*.java`：jadx 1.5.1 定向反编译出的 12 个精确 JNI 类
  真实 Java 源码（只保留这 12 个文件，避免把整个 dex 反编译结果入库）。

输出 `analysis/jni-verify-report.json`：逐类比对 native 方法名集合，列出
「索引有 / 源码缺」与「源码有 / 索引缺」。差异不阻断（jadx 可能因混淆或
反编译失败改名），但必须显式登记，供 `12 §10.2` 步骤 3 的编译→补 slot 循环使用。

用法：
    python tools/engine_jni_verify.py            # 写报告 + 打印摘要
    python tools/engine_jni_verify.py --strict    # 有任何差异即返回 1
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "analysis" / "engine-jni-classes.json"
SRC_ROOT = ROOT / "analysis" / "jadx-jni-classes"
OUT = ROOT / "analysis" / "jni-verify-report.json"

# `public/private/static/final/synchronized native <ret> <name>(...)`
NATIVE_RE = re.compile(
    r"\bnative\b[^;{}()]*?\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(",
    re.MULTILINE,
)


def source_for(class_name: str) -> Path | None:
    """内部类（$）与主类同文件；按主类名定位 jadx 输出的 .java。"""
    outer = class_name.split("$", 1)[0]
    return SRC_ROOT / (outer.replace(".", "/") + ".java")


def main() -> int:
    parser = argparse.ArgumentParser(description="核对 jadx 源码与 JNI 签名索引")
    parser.add_argument("--strict", action="store_true", help="有差异即返回 1")
    args = parser.parse_args()

    if not INDEX.is_file():
        print(f"FAIL 缺索引 {INDEX}，请先跑 tools/engine_jni_class_index.py")
        return 1
    index = json.loads(INDEX.read_text(encoding="utf-8"))

    rows = []
    for entry in index["classes"]:
        if not entry["preciseJniClass"]:
            continue
        expected_list = [m["name"] for m in entry["nativeMethods"]]
        expected = set(expected_list)
        path = source_for(entry["class"])
        if not path.is_file():
            rows.append({
                "class": entry["class"],
                "source": path.relative_to(ROOT).as_posix(),
                "present": False,
                "expectedCount": len(expected_list),
                "actualCount": 0,
                "missingInSource": sorted(expected),
                "extraInSource": [],
            })
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        actual_list = NATIVE_RE.findall(text)
        actual = set(actual_list)
        rows.append({
            "class": entry["class"],
            "source": path.relative_to(ROOT).as_posix(),
            "present": True,
            "expectedCount": len(expected_list),
            "actualCount": len(actual_list),
            "missingInSource": sorted(expected - actual),
            "extraInSource": sorted(actual - expected),
        })

    missing_files = sum(1 for r in rows if not r["present"])
    diff_rows = [r for r in rows if r["missingInSource"] or r["extraInSource"]]
    dex_total = sum(r["expectedCount"] for r in rows)
    source_total = sum(r["actualCount"] for r in rows)
    exports = index.get("jniSymbolCount", 0)
    report = {
        "source": "analysis/engine-jni-classes.json + analysis/jadx-jni-classes/**/*.java",
        "preciseClassCount": len(rows),
        "sourceFilesPresent": len(rows) - missing_files,
        "sourceFilesMissing": missing_files,
        "classesWithDiff": len(diff_rows),
        # 12 §34 实证：dex 侧审计索引漏记 NMCommonCache 5 个类的 48 个 native 方法；
        # jadx 真实源码补齐后总数 210，与 13 §7.4 的 dex 侧基线精确吻合。
        "dexAuditNativeTotal": dex_total,
        "sourceNativeTotal": source_total,
        "jadxSupplementCount": source_total - dex_total,
        "jniExportCount": exports,
        "deltaVsJniExports": source_total - exports,
        "matchesDexBaseline210": source_total == 210,
        "deltaKnown": index.get("deltaKnown", []),
        "note": "差异需逐项核对：engine-api-audit.json 只记录了 JNI 符号对照涉及的方法，"
                "NMCommonCache 系列不完整；jadx 源码是更完整的真相。不自动改写索引，"
                "按 12 §10.4 登记簿逐个处置。",
        "rows": rows,
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"精确 JNI 类 {len(rows)} 个 | 源码在位 {len(rows) - missing_files} 个 | "
          f"缺失 {missing_files} 个 | 有差异 {len(diff_rows)} 个")
    print(f"native：dex 审计索引 {dex_total} → jadx 源码 {source_total} "
          f"（补充 {source_total - dex_total}） | JNI 导出 {exports} | "
          f"差 {source_total - exports} | 与 13 §7.4 的 210 基线吻合："
          f"{'YES' if report['matchesDexBaseline210'] else 'NO'}")
    for r in rows:
        flag = "OK" if r["present"] and not r["missingInSource"] and not r["extraInSource"] else "DIFF"
        print(f"  [{flag:4s}] {r['class']:95s} "
              f"native 索引 {r['expectedCount']:3d} / 源码 {r['actualCount']:3d}")
        if r["missingInSource"]:
            print(f"          源码缺: {r['missingInSource'][:6]}")
        if r["extraInSource"]:
            print(f"          索引缺: {r['extraInSource'][:6]}")
    print(f"\n已写出 {OUT.relative_to(ROOT).as_posix()}")
    if args.strict and (missing_files or diff_rows):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
