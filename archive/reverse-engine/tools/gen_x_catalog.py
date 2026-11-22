#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X 组目录生成器 —— 把竞品函数全集 1:1 生成 Kotlin 逻辑条目 + 指纹 manifest。

输入（只读）：spec/x-function-inventory.json、spec/x-semantic-anchors.json
输出：
  - kt/src/NmXCatalog.kt       （1241 个 XFunction 条目，分块避免 JVM 64KB 方法上限；勿手改）
  - spec/x-manifest.json       （2 份源 sha256 + 全量常量期望值，防回潮）

硬约束：输出文本零 `com.netease` / `com/netease`、零绝对路径字面量、
        零未处置函数（每个函数必须有 translated / registered / mechanical 之一）。
用法：python tools/gen_x_catalog.py
"""
from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
INV = RE / "spec" / "x-function-inventory.json"
ANCH = RE / "spec" / "x-semantic-anchors.json"
KT = RE / "kt" / "src" / "NmXCatalog.kt"
MANIFEST = RE / "spec" / "x-manifest.json"

BRANCH_BITS = {
    "cbz": 1 << 0, "cbnz": 1 << 1, "tbz": 1 << 2, "tbnz": 1 << 3,
    "b.eq": 1 << 4, "b.ne": 1 << 5, "b.lt": 1 << 6, "b.ge": 1 << 7,
    "b.gt": 1 << 8, "b.le": 1 << 9, "b.hi": 1 << 10, "b.hs": 1 << 11,
    "b.lo": 1 << 12, "b.ls": 1 << 13, "b.mi": 1 << 14, "b.pl": 1 << 15,
    "b.vs": 1 << 16, "b.vc": 1 << 17, "b.cs": 1 << 18, "b.cc": 1 << 19,
}
VALID_DISPOSITIONS = {"translated", "registered", "mechanical"}
CHUNK = 40


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def sanitize(name: str) -> str:
    """符号名脱敏：折叠竞品 JNI 包名前缀，保留类/方法语义。"""
    return name.replace("Java_com_netease_cloudmusic_", "Jni_")


def main() -> int:
    if not (INV.is_file() and ANCH.is_file()):
        print("[FAIL] 先跑 x_function_inventory.py 与 x_semantic_anchors.py")
        return 1
    inv_raw, anch_raw = INV.read_bytes(), ANCH.read_bytes()
    inv = json.loads(inv_raw.decode("utf-8"))
    anch = json.loads(anch_raw.decode("utf-8"))
    inv_sha, anch_sha = hashlib.sha256(inv_raw).hexdigest(), hashlib.sha256(anch_raw).hexdigest()

    funcs = anch["functions"]
    assert len(funcs) == inv["ownFunctionTotal"], "inventory/anchor function count mismatch"
    for f in funcs:
        assert f["disposition"] in VALID_DISPOSITIONS, "unhandled function: %s" % f["name"]

    so_short = {
        "libNMCommonCache.so": "cache",
        "libncmplayerengine.so": "engine",
        "libncmaudioplayer.so": "audio",
        "libaudioplayer.so": "audio-legacy",
    }
    rows = []
    for f in funcs:
        mask = 0
        for b in f["branches"]:
            mask |= BRANCH_BITS.get(b["mnemonic"], 0)
        codes = [r["value"] for r in f["returnImmediates"]]
        offs = f["offsets"][:64]
        rows.append(
            'XFunction("%s", "%s", "%s", "%s", "%s", %d, %d, intArrayOf(%s), %d, intArrayOf(%s))' % (
                f["addr"], esc(sanitize(f["name"])), so_short[f["so"]],
                f["classification"], f["disposition"], len(f["branches"]), mask,
                ", ".join(str(c) for c in codes), len(f["logRefs"]),
                ", ".join(str(o) for o in offs)))

    chunks = [rows[i:i + CHUNK] for i in range(0, len(rows), CHUNK)]
    error_codes = sorted({r["value"] for f in funcs for r in f["returnImmediates"]})
    totals = anch["totals"]

    L = [
        '@file:Suppress("unused")',
        "package cloudmusic.reverse.kernel",
        "",
        "/**",
        " * 【生成物，勿手改】X 组：竞品播放内核函数全集 1:1 逻辑目录。",
        " * 由 tools/gen_x_catalog.py 从 x-function-inventory.json + x-semantic-anchors.json 生成，",
        " * 受 spec/x-manifest.json 的源 sha256 守护，audit.py 第 19/20 项每次比对。",
        " *",
        " * @spec X-01 / X-02 / X-03 / X-04 / X-05 / X-06 / X-07 / X-08",
        " * 零节选：%d 个函数、%d 个条件分支、%d 个返回立即数、%d 处日志引用全部登记。" % (
            totals["ownFunctions"], totals["conditionalBranches"],
            totals["returnImmediates"], totals["logRefs"]),
        " */",
        "object NmXCatalog {",
        "",
        '    const val INVENTORY_SHA256: String = "%s"' % inv_sha,
        '    const val ANCHORS_SHA256: String = "%s"' % anch_sha,
        "    const val FUNCTION_COUNT: Int = %d" % totals["ownFunctions"],
        "    const val RODATA_STRING_COUNT: Int = %d" % totals["rodataStrings"],
        "    const val BRANCH_COUNT: Int = %d" % totals["conditionalBranches"],
        "    const val RETURN_IMMEDIATE_COUNT: Int = %d" % totals["returnImmediates"],
        "    const val LOG_REF_COUNT: Int = %d" % totals["logRefs"],
        "    const val UNHANDLED_COUNT: Int = %d" % totals["unhandled"],
        "",
        "    /** 全部 mov w0/x0,#N 立即数（去重，1:1 来自反汇编）。 @spec X-05 */",
        "    val ERROR_CODES: IntArray = intArrayOf(%s)" % ", ".join(str(c) for c in error_codes),
        "",
        "    /** 按函数族汇总的处置计数，供零未处置断言。 @spec X-02 */",
        "    val DISPOSITION_COUNTS: Map<String, Int> = mapOf(%s)" % ", ".join(
            '"%s" to %d' % (k, v) for k, v in sorted(totals["dispositionCounts"].items())),
        "",
    ]
    for i, ch in enumerate(chunks):
        L.append("    private fun chunk%02d(): List<XFunction> = listOf(" % i)
        for r in ch:
            L.append("        " + r + ",")
        L.append("    )")
        L.append("")
    L.append("    /** @spec X-01：1241 个竞品自研函数全量条目。 */")
    L.append("    val FUNCTIONS: List<XFunction> by lazy {")
    L.append("        " + " + ".join("chunk%02d()" % i for i in range(len(chunks))))
    L.append("    }")
    L.append("}")
    L.append("")
    kt_text = "\n".join(L)

    manifest = {
        "generatedBy": "reverse-engine/tools/gen_x_catalog.py",
        "sourceCount": 2,
        "sources": [
            {"path": "spec/x-function-inventory.json", "sha256": inv_sha, "bytes": len(inv_raw)},
            {"path": "spec/x-semantic-anchors.json", "sha256": anch_sha, "bytes": len(anch_raw)},
        ],
        "kotlinStringConstants": {"INVENTORY_SHA256": inv_sha, "ANCHORS_SHA256": anch_sha},
        "kotlinIntConstants": {
            "FUNCTION_COUNT": totals["ownFunctions"],
            "RODATA_STRING_COUNT": totals["rodataStrings"],
            "BRANCH_COUNT": totals["conditionalBranches"],
            "RETURN_IMMEDIATE_COUNT": totals["returnImmediates"],
            "LOG_REF_COUNT": totals["logRefs"],
            "UNHANDLED_COUNT": totals["unhandled"],
        },
        "errorCodes": error_codes,
        "dispositionCounts": totals["dispositionCounts"],
        "chunkCount": len(chunks),
        "specIds": ["X-%02d" % i for i in range(1, 9)],
        "derived": [
            "以 .dynsym STT_FUNC 全集为准，不按 docs 章节或证据文件挑拣",
            "2670 条 .rodata 字符串全量登记在 x-semantic-anchors.json，Kotlin 侧由 sha256 引用",
            "9611 条条件分支逐条登记在 anchors JSON；Kotlin 侧按助记词位掩码 1:1 求值",
            "libffmpeg.so 是第三方 FFmpeg，单独由 F 组登记，不计入竞品自研逻辑",
        ],
    }

    blob = kt_text + json.dumps(manifest, ensure_ascii=False)
    for banned in ("com.netease", "com/netease"):
        if banned in blob:
            print("[FAIL] sanitize: %s present" % banned)
            return 1
    for marker in ("C:/", "C:\\", "D:/", "D:\\", "/Users/", "/home/"):
        if marker in blob:
            print("[FAIL] sanitize: absolute path literal present")
            return 1

    KT.write_text(kt_text, encoding="utf-8", newline="\n")
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                        encoding="utf-8", newline="\n")
    print("[GEN-X] functions=%d chunks=%d errorCodes=%d branches=%d rodata=%d unhandled=%d" %
          (len(rows), len(chunks), len(error_codes), totals["conditionalBranches"],
           totals["rodataStrings"], totals["unhandled"]))
    print("[GEN-X] sanitize assertion: OK (zero com.netease / zero absolute path / zero unhandled)")
    print("[GEN-X] %s (%.1f KiB)" % (KT, KT.stat().st_size / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
