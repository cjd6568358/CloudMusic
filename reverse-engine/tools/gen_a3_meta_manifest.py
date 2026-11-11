# -*- coding: utf-8 -*-
"""
gen_a3_meta_manifest.py —— U 组元数据生成器

从 7 份「A3 阶段反汇编 + §61.54~61.77 机读反汇编 JSON」全量生成：
  - kt/src/NmDisasmMetaCatalog.kt  （脱敏 Kotlin 常量表，生成物勿手改）
  - spec/a3-meta-manifest.json     （每份源 sha256 + Kotlin 常量期望值，防回潮）

背景：这 7 份此前在 COVERAGE 第 9 节被归为 C 类，但
  (1) 2 份 A3 md 是对竞品 so 的 capstone 指令级反汇编，坐实未翻译的控制流（U-01~U-07）；
  (2) 5 份机读 JSON 是同名 -report.md 的机读源，含 report 未覆盖的独有锚点 / checks 全量数据。
本批把它们改列为 A 类逻辑锚点 2 份 + B 类全量数据源 5 份，并统一受 sha256 守护。

硬约束：生成的 Kotlin 零 `com.netease` / `com/netease`。
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

RE = Path(__file__).resolve().parents[1]
PROJ = RE.parent
KT_DIR = RE / "kt" / "src"
SPEC = RE / "spec"
KT_OUT = KT_DIR / "NmDisasmMetaCatalog.kt"
MANIFEST_OUT = SPEC / "a3-meta-manifest.json"

SOURCES = [
    ("A3_STAGE_C", "analysis/a3-stage-c-analysis-2026-11-07.md", "md"),
    ("A3_PHASE_AB", "analysis/a3-phase-ab-evidence-2026-11-07.md", "md"),
    ("MINUS103", "analysis/l3-f4-minus103-chain.json", "json"),
    ("PLAY_GATE", "analysis/l3-f4-play-gate-disasm.json", "json"),
    ("LEVEL5_SEMANTICS", "analysis/l3-f4-level5-gate-semantics-audit.json", "json"),
    ("LOGLEVEL_PROBE", "analysis/l3-f4-loglevel-gate-probe.json", "json"),
    ("JNI_ARG", "analysis/l3-f4-jni-arg.json", "json"),
]

RE_HEX = re.compile(r"\b0x[0-9a-fA-F]{3,6}\b")


def as_int(v) -> int | None:
    if isinstance(v, bool):
        return int(v)
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        s = v.strip().lower()
        if s.startswith("0x"):
            try:
                return int(s, 16)
            except ValueError:
                return None
        if s.lstrip("-").isdigit():
            return int(s)
    return None


def main() -> None:
    source_rows = []
    str_consts: dict[str, str] = {}
    int_consts: dict[str, int] = {}

    for key, rel, kind in SOURCES:
        p = PROJ / rel
        assert p.is_file(), "证据源缺失：%s" % rel
        raw = p.read_bytes()
        text = raw.decode("utf-8", errors="replace")
        sha = hashlib.sha256(raw).hexdigest()
        hex_addrs = sorted({m.group(0).lower() for m in RE_HEX.finditer(text)})

        row = {
            "key": key,
            "path": rel,
            "kind": kind,
            "sha256": sha,
            "bytes": len(raw),
            "hexAddrCount": len(hex_addrs),
        }
        str_consts["%s_SHA256" % key] = sha
        int_consts["%s_BYTES" % key] = len(raw)
        int_consts["%s_HEX_ADDR_COUNT" % key] = len(hex_addrs)

        if kind == "json":
            obj = json.loads(text)
            checks = obj.get("checks")
            anchors = obj.get("anchors")
            lists = [k for k, v in obj.items() if isinstance(v, list)]
            row.update({
                "topLevelKeys": len(obj),
                "checksTotal": as_int(obj.get("checks_total")) if obj.get("checks_total") is not None
                else (len(checks) if isinstance(checks, (dict, list)) else 0),
                "allPass": bool(obj.get("all_pass")),
                "anchorCount": len(anchors) if isinstance(anchors, (dict, list)) else 0,
                "listFieldCount": len(lists),
                "f4Status": str(obj.get("f4_status", "")),
                "deviceRun": bool(obj.get("device_run", False)),
            })
            int_consts["%s_TOP_LEVEL_KEYS" % key] = len(obj)
            int_consts["%s_CHECKS_TOTAL" % key] = row["checksTotal"]
            int_consts["%s_ANCHOR_COUNT" % key] = row["anchorCount"]
            int_consts["%s_LIST_FIELD_COUNT" % key] = len(lists)
            int_consts["%s_ALL_PASS" % key] = 1 if row["allPass"] else 0
            int_consts["%s_DEVICE_RUN" % key] = 1 if row["deviceRun"] else 0
        else:
            row.update({
                "lineCount": len(text.splitlines()),
                "fenceCount": text.count("```"),
                "asmLineCount": sum(
                    1 for ln in text.splitlines()
                    if re.match(r"^\s*0x[0-9a-fA-F]{3,6}\s+[a-z]", ln)
                ),
            })
            int_consts["%s_LINE_COUNT" % key] = row["lineCount"]
            int_consts["%s_FENCE_COUNT" % key] = row["fenceCount"]
            int_consts["%s_ASM_LINE_COUNT" % key] = row["asmLineCount"]

        source_rows.append(row)

    # ---------------------------------------------------------------- Kotlin
    lines = [
        "@file:Suppress(\"unused\")",
        "package cloudmusic.reverse.kernel",
        "",
        "/**",
        " * 【生成物，勿手改】U 组：A3 阶段反汇编 + 机读反汇编 JSON 的全量元数据登记。",
        " * 由 tools/gen_a3_meta_manifest.py 从 7 份一手证据生成，",
        " * 受 spec/a3-meta-manifest.json 的源 sha256 守护，audit.py 第 13/14 项每次比对。",
        " *",
        " * @spec U-08：5 份机读反汇编 JSON 的独有元数据全量登记（源 sha256 守护）。",
        " * 口径：本 catalog 只做数据全量登记，不新增控制流翻译；",
        " * A3 两份 md 坐实的控制流见 NmA3Supplement.kt（U-01~U-07）。",
        " */",
        "object NmDisasmMetaCatalog {",
        "    const val SOURCE_COUNT: Int = %d" % len(SOURCES),
        "",
    ]
    for key, _rel, _kind in SOURCES:
        lines.append("    // ---- %s ----" % key)
        lines.append("    const val %s_SHA256: String = \"%s\"" % (key, str_consts["%s_SHA256" % key]))
        for name, val in int_consts.items():
            if name.startswith(key + "_"):
                lines.append("    const val %s: Int = %d" % (name, val))
        lines.append("")
    lines.append("}")
    lines.append("")
    kt_text = "\n".join(lines)

    for banned in ("com.netease", "com/netease"):
        assert banned not in kt_text, "生成 Kotlin 命中禁项：%s" % banned

    KT_OUT.write_text(kt_text, encoding="utf-8", newline="\n")

    # -------------------------------------------------------------- manifest
    manifest = {
        "generatedBy": "tools/gen_a3_meta_manifest.py",
        "sourceCount": len(SOURCES),
        "sources": source_rows,
        "kotlinStringConstants": str_consts,
        "kotlinIntConstants": int_consts,
        "specIds": ["U-08", "U-09"],
        "derived": [
            "2 份 A3 md = A 类逻辑锚点（U-01~U-07 控制流翻译在 NmA3Supplement.kt）",
            "5 份机读 JSON = B 类全量数据源，核心控制流已在 A/B/G/P 各组翻译，本组只登记独有元数据",
            "同名 -report.md 已登记时，机读 .json 不再按「同源产物不重复登记」处理，统一受 sha256 守护",
            "scan_uncovered_evidence.py 的 24 份 highRisk 裁定见 spec/uncovered-evidence-adjudications.json",
        ],
    }
    MANIFEST_OUT.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8", newline="\n",
    )

    print("[GEN-A3] %s" % KT_OUT.as_posix())
    print("[GEN-A3] %s" % MANIFEST_OUT.as_posix())
    print("[GEN-A3] sources=%d strConsts=%d intConsts=%d"
          % (len(SOURCES), len(str_consts), len(int_consts)))
    for r in source_rows:
        print("  %-18s %-6s bytes=%-6d hexAddr=%-3d checks=%s anchors=%s"
              % (r["key"], r["kind"], r["bytes"], r["hexAddrCount"],
                 r.get("checksTotal", "-"), r.get("anchorCount", "-")))
    print("[GEN-A3] sanitize assertion: OK (zero com.netease / com/netease)")


if __name__ == "__main__":
    main()
