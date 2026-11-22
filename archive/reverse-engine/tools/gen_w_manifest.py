#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
W 组元数据生成器 —— 从一手反汇编证据 JSON 生成：
  - kt/src/NmWDisasmCatalog.kt   （21 项 Int 常量 + 2 项 String 指纹，生成物，勿手改）
  - spec/w-disasm-manifest.json  （源 sha256 / bytes + 常量期望值，防回潮）

数据源：tools/w_disasm_evidence.py 的产出（只读，不改）。
硬约束：输出文本零 `com.netease` / `com/netease`、零绝对路径字面量。

用法：python tools/gen_w_manifest.py
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
EV = RE / "spec" / "w-disasm-evidence.json"
KT_OUT = RE / "kt" / "src" / "NmWDisasmCatalog.kt"
MANIFEST_OUT = RE / "spec" / "w-disasm-manifest.json"

SPEC_IDS = ["W-%02d" % i for i in range(1, 15)]

DERIVED = [
    "S-16 定案：终止片索引 upper = end/size + (end%size!=0)，循环为半开区间 [qStart, upper)",
    "旧实现 qStart..end/size 在 end 整除 size 时多写一片；NmSlicesFile.write 已按 0x128A0 cinc 修正",
    "B-20 定案：[+0xB8]!=0 且 result<0 且 result!=-2 → 清标志 + 返回 -6",
    "B-30 定案：0x12DC8 的 -2 由 0x12CEC b.lt 在请求长度 <1 触发，位于读侧窗口",
    "日志门控本体 0x37E20 = (g_level > level) ? 1 : 0，bit0=1 表示抑制",
    "分片对象步长 232B（0x128C4 mov w9,#0xe8 / 0x128DC mul / 0x128F8 add）",
    "本组不改变 F4 仍 BLOCKED 口径，不实现 B2 / M2 / M3",
]


def hx(v: int) -> str:
    return "0x%x" % v


def main() -> int:
    if not EV.is_file():
        print("[FAIL] 缺少一手证据，先跑 tools/w_disasm_evidence.py")
        return 1
    raw = EV.read_bytes()
    ev = json.loads(raw.decode("utf-8"))
    if not ev.get("all_pass"):
        print("[FAIL] 一手证据存在未通过锚点，拒绝生成")
        return 1
    ev_sha = hashlib.sha256(raw).hexdigest()
    ints: dict[str, int] = dict(ev["findings"])
    strs = {
        "EVIDENCE_SHA256": ev_sha,
        "SO_SHA256": ev["so"]["sha256"],
    }

    lines = [
        "@file:Suppress(\"unused\")",
        "package cloudmusic.reverse.kernel",
        "",
        "/**",
        " * 【生成物，勿手改】W 组：竞品播放内核 1:1 指令级补译常量表。",
        " * 由 tools/gen_w_manifest.py 从 spec/w-disasm-evidence.json 生成，",
        " * 受 spec/w-disasm-manifest.json 的源 sha256 守护，audit.py 第 17/18 项每次比对。",
        " *",
        " * 证据：libNMCommonCache.so arm64-v8a 全 .text capstone 反汇编",
        " *       （8 窗口 / 238 指令 / 28 锚点全通过）。",
        " *",
        " * @spec " + " / ".join(SPEC_IDS),
        " * 口径：只登记逐指令坐实的控制流 / 门控 / 错误码；不推断未坐实的分支语义。",
        " */",
        "object NmWDisasmCatalog {",
        "",
        "    // ---- 一手证据指纹 ----",
        "    const val EVIDENCE_SHA256: String = \"%s\"" % ev_sha,
        "    const val SO_SHA256: String = \"%s\"" % ev["so"]["sha256"],
        "",
        "    // ---- 反汇编锚点地址 / 定案常量（Int）----",
    ]
    for name, value in ints.items():
        lines.append("    const val %s: Int = %s" % (name, hx(value) if value >= 0 else str(value)))
    lines += [
        "",
        "    /** W 组规格编号全集，供契约测试与审计核对。 */",
        "    val SPEC_IDS: List<String> = listOf(%s)" % ", ".join('"%s"' % x for x in SPEC_IDS),
        "}",
        "",
    ]
    kt_text = "\n".join(lines)

    manifest = {
        "generatedBy": "reverse-engine/tools/gen_w_manifest.py",
        "sourceCount": 1,
        "sources": [
            {"path": "spec/w-disasm-evidence.json", "sha256": ev_sha, "bytes": len(raw)},
        ],
        "so": {"path": ev["so"]["path"], "sha256": ev["so"]["sha256"], "bytes": ev["so"]["bytes"]},
        "kotlinIntConstants": ints,
        "kotlinStringConstants": strs,
        "specIds": SPEC_IDS,
        "windowCount": len(ev["windows"]),
        "instructionCount": sum(w["instructionCount"] for w in ev["windows"]),
        "anchorCount": len(ev["anchors"]),
        "derived": DERIVED,
    }

    for banned in ("com.netease", "com/netease"):
        if banned in kt_text or banned in json.dumps(manifest, ensure_ascii=False):
            print("[FAIL] sanitize assertion: %s present" % banned)
            return 1
    for marker in ("C:/", "C:\\", "D:/", "D:\\", "/Users/", "/home/"):
        if marker in kt_text or marker in json.dumps(manifest, ensure_ascii=False):
            print("[FAIL] sanitize assertion: absolute path literal present")
            return 1

    KT_OUT.write_text(kt_text, encoding="utf-8", newline="\n")
    MANIFEST_OUT.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print("[GEN-W] evidence sha256=%s intConsts=%d strConsts=%d specIds=%d" %
          (ev_sha[:16], len(ints), len(strs), len(SPEC_IDS)))
    print("[GEN-W] sanitize assertion: OK (zero com.netease / zero absolute path)")
    print("[GEN-W] %s" % KT_OUT)
    print("[GEN-W] %s" % MANIFEST_OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
