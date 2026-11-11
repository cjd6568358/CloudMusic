# -*- coding: utf-8 -*-
"""
R 组生成器：把 analysis/l3-f4-read-gap-chain.json（§61.77 一手指令级反汇编）
全量登记为 kt/src/NmReadGapCatalog.kt，并生成 spec/read-gap-manifest.json
以源 JSON sha256 + Kotlin 常量期望值防回潮。

用法：python tools/gen_read_gap_manifest.py
禁止手改生成的 .kt / .json；改证据源后重跑本脚本即可。
"""
from pathlib import Path
import hashlib
import json

TOOLS = Path(__file__).resolve().parent
RE = TOOLS.parent
PROJ = RE.parent
SRC = PROJ / "analysis" / "l3-f4-read-gap-chain.json"
OUT_KT = RE / "kt" / "src" / "NmReadGapCatalog.kt"
OUT_MANIFEST = RE / "spec" / "read-gap-manifest.json"

raw = SRC.read_bytes()
data = json.loads(raw.decode("utf-8"))
src_sha = hashlib.sha256(raw).hexdigest()


def as_int(value):
    if isinstance(value, str) and value.lower().startswith("0x"):
        return int(value, 16)
    return int(value)


def hx(value):
    return "0x%X" % value


def kstr(value):
    return value.replace("\\", "\\\\").replace('"', '\\"')


anchors = data["anchors"]
rodata = data["rodata"]
pending = data["pending_198_writes"]
minus2 = data["hardcoded_minus2_points"]
fix = data["fix_anchors"]
checks = data["checks"]
checks_pass = sum(1 for v in checks.values() if v is True)

# 每个指令锚点一个可机读常量（全量，不节选）
anchor_consts = {}
for name, info in anchors.items():
    anchor_consts["A_" + name.upper() + "_ADDR"] = as_int(info["addr"])

scalar = {
    "RODATA_ADDR": as_int(rodata["addr"]),
    "RODATA_REF_ADRP_ADDR": as_int(rodata["references"][0][0]),
    "RODATA_REF_ADD_ADDR": as_int(rodata["references"][0][1]),
    "ANCHOR_COUNT": len(anchors),
}
scalar.update(anchor_consts)
scalar["PENDING_198_WRITE_COUNT"] = len(pending)
scalar["HARDCODED_MINUS2_POINT_COUNT"] = len(minus2)
scalar["FIX_ANCHOR_COUNT"] = len(fix)
scalar["CHECKS_TOTAL"] = int(data["checks_total"])
scalar["CHECKS_PASS"] = checks_pass

L = []
L.append('@file:Suppress("unused")')
L.append("package cloudmusic.reverse.kernel")
L.append("")
L.append("/**")
L.append(" * §61.77 F4 读侧空白链一手反汇编证据 —— 全量登记（R 组）。")
L.append(" * 证据源：analysis/l3-f4-read-gap-chain.json（31/31 checks, all_pass=true）。")
L.append(" * 由 tools/gen_read_gap_manifest.py 自动生成，勿手改；")
L.append(" * 受 spec/read-gap-manifest.json 的源 JSON sha256 + 常量期望值守护。")
L.append(" * 口径：静态二进制证据 ≠ 修复 ≠ 真机验证；F4 仍 BLOCKED。")
L.append(" */")
L.append("object NmReadGapCatalog {")
L.append("")
L.append("    /** @spec R-01 .rodata 格式串地址（唯一引用点 0xC454/0xC45C） */")
L.append("    const val RODATA_ADDR: Int = %s" % hx(scalar["RODATA_ADDR"]))
L.append("    const val RODATA_TEXT: String = \"%s\"" % kstr(rodata["text"]))
L.append("    const val RODATA_UNIQUE: Boolean = %s" % ("true" if rodata["unique"] else "false"))
L.append("    const val RODATA_REF_ADRP_ADDR: Int = %s" % hx(scalar["RODATA_REF_ADRP_ADDR"]))
L.append("    const val RODATA_REF_ADD_ADDR: Int = %s" % hx(scalar["RODATA_REF_ADD_ADDR"]))
L.append("")
L.append("    /** @spec R-06 24 个指令锚点地址（与源 JSON anchors 一一对应） */")
L.append("    const val ANCHOR_COUNT: Int = %d" % scalar["ANCHOR_COUNT"])
for name, value in anchor_consts.items():
    L.append("    const val %s: Int = %s" % (name, hx(value)))
L.append("")
L.append("    /** @spec R-04 [+0x198] 挂起异步读的 6 处写入点 */")
L.append("    const val PENDING_198_WRITE_COUNT: Int = %d" % scalar["PENDING_198_WRITE_COUNT"])
L.append("    val PENDING_198_WRITES: List<Int> = listOf(%s)" % ", ".join(hx(as_int(x)) for x in pending))
L.append("")
L.append("    /** @spec R-05 -2 的 7 处硬编码立即数点（含 0x12DC8 NMSlicesFile::Write 越界） */")
L.append("    const val HARDCODED_MINUS2_POINT_COUNT: Int = %d" % scalar["HARDCODED_MINUS2_POINT_COUNT"])
L.append("    val HARDCODED_MINUS2_POINTS: List<Int> = listOf(%s)" % ", ".join(hx(as_int(x)) for x in minus2))
L.append("")
L.append("    /** @spec R-07 上线复跑优先锚点 / 自洽性统计 */")
L.append("    const val FIX_ANCHOR_COUNT: Int = %d" % scalar["FIX_ANCHOR_COUNT"])
L.append("    val FIX_ANCHORS: List<String> = listOf(%s)" % ", ".join('"%s"' % kstr(x) for x in fix))
L.append("    const val CHECKS_TOTAL: Int = %d" % scalar["CHECKS_TOTAL"])
L.append("    const val CHECKS_PASS: Int = %d" % scalar["CHECKS_PASS"])
L.append("    const val ALL_PASS: Boolean = %s" % ("true" if data["all_pass"] else "false"))
L.append("    const val F4_STATUS: String = \"%s\"" % kstr(data["f4_status"]))
L.append("}")
L.append("")
kt_text = "\n".join(L)

# C2 静态守护：生成的 Kotlin 不得出现竞品包名
for banned in ("com.netease", "com/netease"):
    assert banned not in kt_text, "%s contains forbidden literal: %s" % (OUT_KT.name, banned)

OUT_KT.write_text(kt_text, encoding="utf-8", newline="\n")

manifest = {
    "tool": "reverse-engine/tools/gen_read_gap_manifest.py",
    "source": {
        "path": "analysis/l3-f4-read-gap-chain.json",
        "sha256": src_sha,
        "bytes": len(raw),
        "so": data["so"],
        "soSha256": data["so_sha256"],
        "mode": data["mode"],
        "deviceRun": data["device_run"],
        "checksTotal": int(data["checks_total"]),
        "checksPass": checks_pass,
        "allPass": bool(data["all_pass"]),
        "f4Status": data["f4_status"],
        "verdict": data["verdict"],
    },
    "kotlinConstants": scalar,
    "listConstants": {
        "PENDING_198_WRITES": [hx(as_int(x)) for x in pending],
        "HARDCODED_MINUS2_POINTS": [hx(as_int(x)) for x in minus2],
        "FIX_ANCHORS": list(fix),
        "RODATA_TEXT": rodata["text"],
    },
    "specIds": ["R-01", "R-02", "R-03", "R-04", "R-05", "R-06", "R-07", "R-08"],
    "derived": [
        "CHECKS_PASS 由 checks dict 中 True 的项数派生，不硬编码",
        "ANCHOR_COUNT 由 anchors dict 长度派生",
        "PENDING_198_WRITE_COUNT / HARDCODED_MINUS2_POINT_COUNT / FIX_ANCHOR_COUNT 均由列表长度派生",
        "清单类数据全量登记（不节选），但只登记反汇编能证明的地址/立即数/格式串，不推断触发条件",
    ],
}
OUT_MANIFEST.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
    newline="\n",
)

print("[GEN-READGAP] %s" % OUT_KT.name)
print("  source: %s %dB sha256=%s..." % (SRC.name, len(raw), src_sha[:16]))
print("  rodata: %s unique=%s" % (hx(scalar["RODATA_ADDR"]), rodata["unique"]))
print("  anchors=%d pending198=%d minus2points=%d fix=%d" % (
    scalar["ANCHOR_COUNT"], scalar["PENDING_198_WRITE_COUNT"],
    scalar["HARDCODED_MINUS2_POINT_COUNT"], scalar["FIX_ANCHOR_COUNT"]))
print("  checks %d/%d all_pass=%s | F4=%s" % (
    scalar["CHECKS_TOTAL"], scalar["CHECKS_PASS"], data["all_pass"], data["f4_status"]))
print("  kotlinConstants: %d 项" % len(scalar))
print("[GEN-READGAP] %s" % OUT_MANIFEST.name)
