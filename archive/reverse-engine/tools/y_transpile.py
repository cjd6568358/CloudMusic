#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Y 组 —— 竞品 1241 个函数「逐行」ARM64 → Kotlin 转译器（零函数遗漏）。

⚠️ **已废弃（DEPRECATED），请勿执行**：Y 组已被 Z 组取代（`tools/z_transpile.py`）。
   ① 判据换代：Y 组以 `.dynsym` STT_FUNC 为函数清单，实测只覆盖 4 个 so `.text`
      指令的 27%（so 已 strip 无 `.symtab`）；Z 组改按**代码可达性**取全集，
      拿到 6326 块 / 7486 入口 / 388064 条指令，覆盖率 90.0%/95.7%/97.0%/92.7%。
   ② 产出冲突：本脚本会生成 `NmOpcodes.kt` / `NmArm64Runtime.kt` /
      `NmTranslated00..31.kt`，这批文件已从工作区删除，由 `NmOp.kt` /
      `NmArm64.kt` / `NmMachine.kt` / `NmCode00..158.kt` 取代；重跑会重新造出
      与被删文件同名的旧产物，与现行编译链冲突。
   ③ 保留原因：`spec/y-manifest.json` / `spec/y-opcode-coverage.json` 是本脚本的
      历史产出，作为「Y→Z 判据换代」的对照证据保留；本文件与两个 JSON 一并冻结。
   现行逐行翻译入口见 `tools/z_transpile.py` + `tools/z_audit.py`。

对 4 个竞品自研 so 的每个 .dynsym STT_FUNC，用 capstone 逐条反汇编，
把**每条指令**转成一行 Kotlin `st.step(NmIns(...))`；生成物是 1241 个
可编译、可调用的 Kotlin 函数，函数体逐行对应原指令序列。

产出：
  kt/src/NmOpcodes.kt             助记词表（121 种）
  kt/src/NmTranslatedNN.kt        逐行函数体（按 40 个函数分文件）
  kt/src/NmTranslatedCatalog.kt   函数注册表 + 守恒常量
  spec/y-manifest.json            源指纹 / 每函数指令数 / 守恒总数
  spec/y-opcode-coverage.json     助记词 → 语义类别 → 条数

用法：python tools/y_transpile.py
"""
from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
from collections import Counter
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

RE = Path(__file__).resolve().parent.parent
ROOT = RE.parent
JNI = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
KT = RE / "kt" / "src"
OUT_MANIFEST = RE / "spec" / "y-manifest.json"
OUT_COVERAGE = RE / "spec" / "y-opcode-coverage.json"

SO = [("libNMCommonCache.so", "cache"), ("libncmplayerengine.so", "engine"),
      ("libncmaudioplayer.so", "audio"), ("libaudioplayer.so", "audio-legacy")]
FUNCS_PER_FILE = 40
LINES_PER_PART = 700

# 语义类别（与 NmOpClass 一致）
C = dict(NOP=0, MOV_IMM=1, MOV_REG=2, ADD_IMM=3, ADD_REG=4, SUB_IMM=5, SUB_REG=6,
         CMP=7, LOGIC=8, MUL=9, LOAD=10, STORE=11, LOAD_PAIR=12, STORE_PAIR=13,
         ADRP=14, COND_SELECT=15, BRANCH_COND=16, BRANCH=17, CALL=18, RET=19,
         MOVK=20, FLAGS_ONLY=21, PRESERVED=22)

BRANCH_CONDS = {"b.eq", "b.ne", "b.lt", "b.le", "b.gt", "b.ge", "b.hi", "b.hs",
                "b.lo", "b.ls", "b.mi", "b.pl", "b.vs", "b.vc", "b.cs", "b.cc",
                "cbz", "cbnz", "tbz", "tbnz"}
LOADS = {"ldr", "ldrb", "ldur", "ldursb", "ldursw", "ldrsw", "ldrh", "ldar",
         "ldaxr", "ldrb", "ldrsb", "ldrsh", "ldurh"}
STORES = {"str", "strb", "stur", "sturb", "strh", "sturh", "stlr", "stlxr"}
LOGIC = {"and", "ands", "orr", "or", "eor", "eon", "lsl", "lsr", "asr", "bic", "bics", "mvn", "not"}
MULS = {"mul", "madd", "msub", "mneg", "sdiv", "udiv", "smull", "umull",
        "smaddl", "umaddl", "smsubl", "umsubl", "neg", "negs"}
SEL = {"csel", "cset", "csetm", "csinc", "csinv", "csneg", "cinc", "cneg"}
PAIR_L = {"ldp", "ldnp"}
PAIR_S = {"stp", "stnp"}
PRESERVED = {"nop", "dmb", "dsb", "isb", "mrs", "msr", "svc", "hlt", "yield", "sev",
             "wfe", "wfi", "clrex", "prfm", "prfum", "fmov", "fcvt", "fcmp", "fadd",
             "fsub", "fmul", "fdiv", "fsqrt", "scvtf", "fcvtzs", "fcvtzu", "movi",
             "ushr", "shl", "sshr", "addv", "dup", "ext", "tbl", "ins", "umov",
             "smov", "rev", "rev16", "rbit", "clz", "cls", "ccmp", "ccmn", "pacia",
             "pacib", "autia", "autib", "xpaci", "braa", "brab", "blraa", "retab",
             "ldxr", "stxr", "ldarh", "ldrb", "sturb", "cas", "casp", "swp", "ldadd",
             "bfm", "sbfm", "ubfm", "sxtb", "sxth", "sxtw", "uxtb", "uxth", "uxtw",
             "extr", "ror", "crc32", "aes", "sha1", "pmull", "abs", "sqadd", "cnt",
             "zip1", "zip2", "uzp1", "uzp2", "trn1", "trn2", "st1", "ld1", "st2",
             "ld2", "st3", "ld3", "st4", "ld4", "mov", "addp", "fmax", "fmin"}


def cls_of(m: str, op: str):
    if m in BRANCH_CONDS:
        return C["BRANCH_COND"]
    if m == "b":
        return C["BRANCH"]
    if m in ("bl", "blr"):
        return C["CALL"]
    if m in ("ret", "br", "retab"):
        return C["RET"]
    if m in PAIR_L:
        return C["LOAD_PAIR"]
    if m in PAIR_S:
        return C["STORE_PAIR"]
    if m in LOADS:
        return C["LOAD"]
    if m in STORES:
        return C["STORE"]
    if m in LOGIC:
        return C["LOGIC"]
    if m in MULS:
        return C["MUL"]
    if m in SEL:
        return C["COND_SELECT"]
    if m == "adrp":
        return C["ADRP"]
    if m == "movk":
        return C["MOVK"]
    if m in ("cmp", "cmn", "subs"):
        return C["CMP"]
    if m in ("tst", "tst", "ands"):
        return C["FLAGS_ONLY"]
    if m in ("add", "adds"):
        return C["ADD_REG"] if re.search(r",\s*[xw]\d+", op.split(",")[-1]) and "#" not in op.split(",")[-1] else C["ADD_IMM"]
    if m in ("sub", "subs"):
        return C["SUB_REG"] if "#" not in op.split(",")[-1] else C["SUB_IMM"]
    if m in ("mov", "movz"):
        return C["MOV_IMM"] if "#" in op else C["MOV_REG"]
    return C["PRESERVED"]


def elf_sections(data: bytes):
    off = struct.unpack_from("<Q", data, 0x28)[0]
    esz, num, strndx = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(num):
        o = off + i * esz
        nm, _t, _f, addr, o2, sz, link, _i, _a, _e = struct.unpack_from("<IIQQQQIIQQ", data, o)
        out.append({"name": nm, "addr": addr, "off": o2, "size": sz, "link": link})
    st = out[strndx]
    for s in out:
        e = data.find(b"\x00", st["off"] + s["name"])
        s["sname"] = data[st["off"] + s["name"]:e].decode("utf-8", "replace")
    return out


def dyn_functions(data: bytes, secs):
    d = next(s for s in secs if s["sname"] == ".dynsym")
    dstr = secs[d["link"]]
    out = []
    for o in range(d["off"], d["off"] + d["size"], 24):
        n, info, _o2, _sh, val, sz = struct.unpack_from("<IBBHQQ", data, o)
        if val and (info & 0xF) == 2:
            e = data.find(b"\x00", dstr["off"] + n)
            out.append((data[dstr["off"] + n:e].decode("utf-8", "replace"), val, sz))
    return sorted(out, key=lambda x: x[1])


REG = re.compile(r"\b([xwqdvsbh])(\d+)\b|\b(sp|lr|xzr|wzr|zr)\b")
IMM = re.compile(r"#(-?0[xX][0-9a-fA-F]+|-?\d+)")


def parse_regs(op: str):
    out = []
    for m in REG.finditer(op):
        if m.group(3):
            out.append({"sp": 31, "lr": 30}.get(m.group(3), -1))
        else:
            out.append(int(m.group(2)))
    return out


def parse_imm(op: str):
    out = []
    for m in IMM.finditer(op):
        try:
            out.append(int(m.group(1), 0))
        except ValueError:
            pass
    return out


def to_s64(v: int) -> int:
    """capstone 给的无符号 64 位位掩码需折算成 Kotlin Long 有符号值。"""
    v &= 0xFFFFFFFFFFFFFFFF
    return v - (1 << 64) if v >= (1 << 63) else v


def encode(m: str, op: str, cls: int):
    regs, imms = parse_regs(op), parse_imm(op)
    a = regs[0] if regs else -1
    b = regs[1] if len(regs) > 1 else -1
    c = regs[2] if len(regs) > 2 else -1
    imm = 0
    if cls == C["BRANCH_COND"]:
        if m in ("tbz", "tbnz"):
            b = imms[0] if imms else 0
            imm = imms[-1] if len(imms) > 1 else 0
        else:
            imm = imms[-1] if imms else 0
    elif cls in (C["BRANCH"], C["CALL"]):
        imm = imms[-1] if imms else 0
    elif cls in (C["LOAD"], C["STORE"], C["LOAD_PAIR"], C["STORE_PAIR"]):
        imm = imms[-1] if imms else 0
        if cls in (C["LOAD_PAIR"], C["STORE_PAIR"]) and len(regs) > 2:
            a, c, b = regs[0], regs[1], regs[2]
    elif cls in (C["ADD_IMM"], C["SUB_IMM"], C["MOV_IMM"], C["MOVK"], C["ADRP"],
                 C["LOGIC"], C["MUL"], C["CMP"], C["FLAGS_ONLY"], C["COND_SELECT"]):
        imm = imms[-1] if imms else 0
    return a, b, c, to_s64(imm)


def main() -> int:
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    all_funcs, mnemonic_set, coverage, so_meta = [], set(), Counter(), {}
    mn_cls = {}

    for so_name, domain in SO:
        data = (JNI / so_name).read_bytes()
        secs = elf_sections(data)
        text = next(s for s in secs if s["sname"] == ".text")
        funcs = dyn_functions(data, secs)
        code = data[text["off"]:text["off"] + text["size"]]
        ins = {i.address: (i.mnemonic, i.op_str) for i in md.disasm(code, text["addr"])}
        so_meta[so_name] = {"sha256": hashlib.sha256(data).hexdigest(),
                            "bytes": len(data), "domain": domain, "functionCount": len(funcs)}
        for name, addr, size in funcs:
            rows = []
            for a in range(addr, addr + min(size, 0x10000), 4):
                if a not in ins:
                    continue
                m, op = ins[a]
                mnemonic_set.add(m)
                cls = cls_of(m, op)
                pa, pb, pc, imm = encode(m, op, cls)
                rows.append((a, cls, m, pa, pb, pc, imm))
                coverage[m] += 1
                mn_cls.setdefault(m, cls)
            all_funcs.append({"so": so_name, "domain": domain, "name": name,
                              "addr": addr, "size": size, "rows": rows})

    mnemonics = sorted(mnemonic_set)
    mid = {m: i for i, m in enumerate(mnemonics)}
    total_ins = sum(len(f["rows"]) for f in all_funcs)
    cls_counts = Counter(r[1] for f in all_funcs for r in f["rows"])
    print("[Y] functions=%d instructions=%d mnemonics=%d classes=%s" %
          (len(all_funcs), total_ins, len(mnemonics), dict(sorted(cls_counts.items()))))

    # ---- 生成 NmOpcodes.kt ----
    op_lines = [
        '@file:Suppress("unused")', "package cloudmusic.reverse.kernel", "",
        "/** 【生成物，勿手改】Y 组：%d 种 ARM64 助记词表。 */" % len(mnemonics),
        "object NmOpcodes : NmOpcodeTable {",
        "    const val COUNT: Int = %d" % len(mnemonics),
        "    private val NAMES = arrayOf(%s)" % ", ".join('"%s"' % m for m in mnemonics),
        "    override fun name(id: Int): String = if (id in NAMES.indices) NAMES[id] else \"?\"",
        "    fun idOf(name: String): Int = NAMES.indexOf(name)",
        "}", "",
    ]
    (KT / "NmOpcodes.kt").write_text("\n".join(op_lines), encoding="utf-8", newline="\n")

    # ---- 生成逐行函数体（分文件 + 大函数拆 part） ----
    for stale in KT.glob("NmTranslated*.kt"):
        stale.unlink()          # 避免上一次生成的文件残留

    def emit_lines(f, rows, mid):
        out = []
        for (a, cls, m, pa, pb, pc, imm) in rows:
            imm_s = "Long.MIN_VALUE" if imm == -(1 << 63) else "%dL" % imm
            out.append("        st.step(NmIns(%dL, %d, %d, %d, %d, %d, %s))" %
                       (a, cls, mid[m], pa, pb, pc, imm_s))
        return out

    files, file_idx, chunk = [], 0, []
    catalog_entries = []
    name_count = {}
    for f in all_funcs:
        domain, addr = f["domain"], f["addr"]
        base = "nm_%s_%x" % (domain.replace("-", "_"), addr)
        k = name_count.get(base, 0)
        fname = base if k == 0 else "%s_%d" % (base, k)   # 同址别名符号唯一化
        name_count[base] = k + 1
        catalog_entries.append((domain, addr, fname, len(f["rows"])))
        rows = f["rows"]
        body = []   # 文件头由写文件处统一加一次，避免 @file: 注解重复
        parts = []
        for i in range(0, len(rows), LINES_PER_PART):
            parts.append(rows[i:i + LINES_PER_PART])
        if len(parts) == 1:
            body += [
                "/** 1:1 逐行翻译 %s @0x%x（%d 条指令）。 */" % (f["name"][:60], addr, len(rows)),
                "fun %s(st: NmArmState): Long {" % fname,
                "    st.begin(%dL)" % addr,
                "    var round = 0",
                "    while (!st.done && round < 64) {",
                "        round++",
            ] + emit_lines(f, rows, mid) + ["    }", "    return st.result", "}", ""]
        else:
            for pi, part in enumerate(parts):
                body += ["private fun %s_p%d(st: NmArmState) {" % (fname, pi)] \
                        + emit_lines(f, part, mid) + ["}", ""]
            body += [
                "/** 1:1 逐行翻译 %s @0x%x（%d 条指令，%d 个 part）。 */" %
                (f["name"][:60], addr, len(rows), len(parts)),
                "fun %s(st: NmArmState): Long {" % fname,
                "    st.begin(%dL)" % addr,
                "    var round = 0",
                "    while (!st.done && round < 64) {",
                "        round++",
            ] + ["        %s_p%d(st)" % (fname, pi) for pi in range(len(parts))] \
              + ["    }", "    return st.result", "}", ""]
        chunk.append("\n".join(body))
        HEADER = ['@file:Suppress("unused")', "package cloudmusic.reverse.kernel", ""]
        if len(chunk) >= FUNCS_PER_FILE:
            path = KT / ("NmTranslated%02d.kt" % file_idx)
            path.write_text("\n".join(HEADER + chunk), encoding="utf-8", newline="\n")
            files.append(path.name)
            chunk, file_idx = [], file_idx + 1
    if chunk:
        path = KT / ("NmTranslated%02d.kt" % file_idx)
        path.write_text("\n".join(HEADER + chunk), encoding="utf-8", newline="\n")
        files.append(path.name)

    # ---- 生成 catalog ----
    by_domain = {}
    for d, a, fn, n in catalog_entries:
        by_domain.setdefault(d, []).append((a, fn))
    cat = ['@file:Suppress("unused")', "package cloudmusic.reverse.kernel", "",
           "/** 【生成物，勿手改】Y 组：%d 个逐行翻译函数的注册表。 */" % len(catalog_entries),
           "object NmTranslatedCatalog {",
           "    const val FUNCTION_COUNT: Int = %d" % len(catalog_entries),
           "    const val INSTRUCTION_COUNT: Int = %d" % total_ins,
           "    const val OPCODE_COUNT: Int = %d" % len(mnemonics), "",
           "    fun resolve(so: String, addr: Long): ((NmArmState) -> Long)? = when (so) {"]
    for d in ("cache", "engine", "audio", "audio-legacy"):
        cat.append('        "%s" -> when (addr) {' % d)
        seen_addr = set()
        for a, fn in by_domain.get(d, []):
            if a in seen_addr:      # 同址别名只注册一次，避免 when 重复分支
                continue
            seen_addr.add(a)
            cat.append("            %dL -> ::%s" % (a, fn))
        cat += ["            else -> null", "        }"]
    cat += ["        else -> null", "    }", "",
            "    val KEYS: List<Pair<String, Long>> = listOf(",
            ", ".join('"%s" to %dL' % (d, a) for d, a, _fn, _n in catalog_entries[:0]) or "",
            "    )", "}", ""]
    # KEYS 单独按分块函数拼接，避免超长单行
    key_chunks = []
    for i in range(0, len(catalog_entries), 200):
        part = catalog_entries[i:i + 200]
        key_chunks.append("    private fun keys%02d() = listOf(%s)" %
                          (i // 200, ", ".join('"%s" to %dL' % (d, a) for d, a, _f, _n in part)))
    cat.insert(cat.index("    val KEYS: List<Pair<String, Long>> = listOf("), "\n".join(key_chunks))
    cat = [c for c in cat if c not in ("    val KEYS: List<Pair<String, Long>> = listOf(", "    )")]
    cat.insert(cat.index("}"), "    val KEYS: List<Pair<String, Long>> by lazy { %s }" %
               " + ".join("keys%02d()" % (i // 200) for i in range(0, len(catalog_entries), 200)))
    (KT / "NmTranslatedCatalog.kt").write_text("\n".join(cat) + "\n",
                                               encoding="utf-8", newline="\n")

    # ---- manifest ----
    manifest = {
        "tool": "reverse-engine/tools/y_transpile.py",
        "method": "capstone ARM64 per-function disassembly -> one Kotlin st.step(NmIns(...)) line per instruction",
        "mode": "offline, read-only, no adb",
        "soMeta": so_meta,
        "functionCount": len(all_funcs),
        "instructionCount": total_ins,
        "opcodeCount": len(mnemonics),
        "classCounts": {str(k): v for k, v in sorted(cls_counts.items())},
        "generatedFiles": files + ["NmOpcodes.kt", "NmTranslatedCatalog.kt"],
        "perFunction": [{"so": f["domain"], "addr": hex(f["addr"]),
                         "name": f["name"], "instructions": len(f["rows"])}
                        for f in all_funcs],
        "specIds": ["Y-%02d" % i for i in range(1, 7)],
        "honestNotes": [
            "每个函数一个 Kotlin fun，函数体逐行对应原指令；PRESERVED 类指令由运行时保真记录、不伪造副作用。",
            "分支回跳由 while(round) + pc 匹配实现，round 上限 64 防止不收敛。",
            "libffmpeg.so 属第三方 FFmpeg，不计入竞品自研函数。",
            "F4 仍 BLOCKED；本目录不实现 B2 / M2 / M3。",
        ],
    }
    OUT_MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                            encoding="utf-8", newline="\n")
    OUT_COVERAGE.write_text(json.dumps({
        "opcodes": mnemonics,
        "counts": dict(coverage),
        "classOf": {m: mn_cls.get(m, 22) for m in mnemonics},
    }, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print("[Y] generated %d Kotlin files, %.1f MiB total" %
          (len(files) + 2, sum((KT / n).stat().st_size for n in files + ["NmOpcodes.kt", "NmTranslatedCatalog.kt"]) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
