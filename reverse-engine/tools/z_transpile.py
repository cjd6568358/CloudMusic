#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z 组 —— 竞品播放内核**代码口径**全量逐行翻译器。

与 Y 组的判据差别：

  Y 组以 `.dynsym` 的 STT_FUNC 为函数清单（1241 个 / 90525 条指令），实测只覆盖
  4 个 so 的 `.text` 指令的 27%——因为 so 已被 strip（无 `.symtab`），大量 static /
  匿名命名空间函数、模板实例、thunk 没有导出符号。

  Z 组改用**代码可达性**：从 dynsym 函数与 `.data.rel.ro`/`.data`/`.got`/
  `.init_array` 里的代码指针出发递归下降，`bl` 目标自动成为新入口；再把仍未被覆盖
  的连续区段按「是否被 adr/adrp 引用」分类为字面量池或补充代码段。最终给出
  `.text` 的完整字节账：翻译 / 字面量池 / 不可解码，三者相加等于全节。

生成物：
  kt/src/NmCodeNN.kt             可达指令的逐行翻译（64 条/块，一条指令一行）
  kt/src/NmFuncs.kt              每个函数入口一个可调用包装
  kt/src/NmTranslatedCatalog.kt  按 pc 区间二分查找
  spec/z-manifest.json           字节账 / 逐块指令数 / so 指纹 / op 直方图

用法：
  python tools/z_transpile.py            # 全量生成
  python tools/z_transpile.py --report   # 只打印记账
"""
from __future__ import annotations

import base64
import hashlib
import json
import struct
import sys
from collections import Counter, OrderedDict
from pathlib import Path

from capstone import CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, Cs

sys.path.insert(0, str(Path(__file__).resolve().parent))
import z_isa  # noqa: E402

RE = Path(__file__).resolve().parent.parent
ROOT = RE.parent
JNI = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
KT = RE / "kt" / "src"
OUT_MANIFEST = RE / "spec" / "z-manifest.json"

SO = [("libNMCommonCache.so", "cache"), ("libncmplayerengine.so", "engine"),
      ("libncmaudioplayer.so", "audio"), ("libaudioplayer.so", "audio-legacy")]

BLOCK = 64
BLOCKS_PER_FILE = 40
# 分派函数每块的 when 分支数：3200+ 分支的 tableswitch 逼近 64KB 方法上限，
# 取 600 留足余量（实测单块 ~8.4KB 字节码）。
DISPATCH_CHUNK = 600
# 单个包装类的顶层函数数：7486 个包装挤在一个类里会顶到常量池 65535 上限。
WRAP_PER_FILE = 500

TERMINATORS = ("ret", "br", "retab")


def elf_sections(data: bytes):
    off = struct.unpack_from("<Q", data, 0x28)[0]
    esz, num, strndx = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(num):
        o = off + i * esz
        nm, typ, _f, addr, o2, sz, link, _i, _a, _e = struct.unpack_from(
            "<IIQQQQIIQQ", data, o)
        out.append({"name": nm, "type": typ, "addr": addr, "off": o2,
                    "size": sz, "link": link})
    st = out[strndx]
    for s in out:
        e = data.find(b"\x00", st["off"] + s["name"])
        s["sname"] = data[st["off"] + s["name"]:e].decode("utf-8", "replace")
    return out


def dyn_funcs(data: bytes, secs):
    d = next((s for s in secs if s["sname"] == ".dynsym"), None)
    if not d:
        return []
    dstr = secs[d["link"]]
    out = []
    for o in range(d["off"], d["off"] + d["size"], 24):
        n, info, _o, _sh, val, sz = struct.unpack_from("<IBBHQQ", data, o)
        if val and (info & 0xF) == 2:
            e = data.find(b"\x00", dstr["off"] + n)
            out.append((data[dstr["off"] + n:e].decode("utf-8", "replace"), val, sz))
    return out


def code_pointers(data: bytes, secs, lo: int, hi: int):
    out = set()
    for sn in (".data.rel.ro", ".data", ".got", ".init_array", ".init"):
        for s in secs:
            if s["sname"] != sn or s["size"] == 0:
                continue
            blob = data[s["off"]:s["off"] + s["size"]]
            for k in range(0, max(0, len(blob) - 7), 8):
                v = struct.unpack_from("<Q", blob, k)[0]
                if lo <= v < hi and v % 4 == 0:
                    out.add(v)
    return out


def descend(start, ins, reachable, entries, lo, hi, origin):
    work = [start]
    while work:
        x = work.pop()
        while x in ins and x not in reachable:
            reachable.add(x)
            i = ins[x]
            m = i.mnemonic
            if m in TERMINATORS:
                break
            if m == "b":
                t = i.operands[0].imm
                if lo <= t < hi:
                    work.append(t)
                break
            if m == "bl":
                t = i.operands[0].imm
                if lo <= t < hi:
                    if t not in entries:
                        entries[t] = origin
                    work.append(t)
                x += 4
                continue
            if m.startswith("b.") or m in ("cbz", "cbnz", "tbz", "tbnz"):
                t = [o.imm for o in i.operands if o.type == 2][-1]
                if lo <= t < hi:
                    work.append(t)
                x += 4
                continue
            x += 4


def discover(so_path: Path, domain: str) -> dict:
    data = so_path.read_bytes()
    secs = elf_sections(data)
    text = next(s for s in secs if s["sname"] == ".text")
    lo, hi = text["addr"], text["addr"] + text["size"]
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    ins = {i.address: i for i in md.disasm(data[text["off"]:text["off"] + text["size"]], lo)}
    dec, bad = {}, 0
    for a, i in ins.items():
        d = z_isa.decode(i)
        if d is None:
            bad += 1
        else:
            dec[a] = d

    sym = dyn_funcs(data, secs)
    symset = {a for _n, a, _z in sym}
    seeds = {a for a in symset if a in ins}
    seeds |= code_pointers(data, secs, lo, hi)

    entries = OrderedDict()
    for a in sorted(seeds):
        entries[a] = "symbol" if a in symset else "data-ptr"

    reachable = set()
    processed = set()
    while True:
        pending = [a for a in entries if a not in processed]
        if not pending:
            break
        for a in pending:
            processed.add(a)
            descend(a, ins, reachable, entries, lo, hi, "call-target")

    adrp_targets = set()
    for a in reachable:
        i = ins[a]
        if i.mnemonic in ("adr", "adrp") and i.operands and i.operands[-1].type == 2:
            adrp_targets.add(i.operands[-1].imm)

    def referenced(addr):
        return any(addr <= t < addr + 4096 for t in adrp_targets)

    addrs_all = sorted(ins)
    runs, cur = [], None
    for a in addrs_all:
        if a in reachable:
            if cur:
                runs.append(cur)
                cur = None
            continue
        if cur and a == cur[1] + 4:
            cur = (cur[0], a)
        else:
            if cur:
                runs.append(cur)
            cur = (a, a)
    if cur:
        runs.append(cur)

    literal, code_gaps = [], []
    for a, b in runs:
        (literal if referenced(a) else code_gaps).append((a, b))

    gap_set = set()
    for a, b in code_gaps:
        gap_set.update(range(a, b + 1, 4))
    for a in sorted(gap_set):
        if a in reachable:
            continue
        entries.setdefault(a, "sweep-entry")
        descend(a, ins, reachable, entries, lo, hi, "sweep")

    undec_reach = sorted(a for a in reachable if a not in dec)
    reachable.difference_update(undec_reach)

    literal_ins = sum(1 for a in addrs_all if a not in reachable and
                      any(x <= a <= y for x, y in literal))

    return {
        "domain": domain, "so": so_path.name, "secs": secs, "text": text,
        "lo": lo, "hi": hi, "ins": ins, "dec": dec, "bad": bad,
        "undecReach": undec_reach, "entries": entries, "reachable": reachable,
        "literal": literal, "swept": code_gaps, "literalIns": literal_ins,
        "symbolCount": len(sym), "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data), "textIns": len(ins),
    }


def blocks_of(addrs):
    out, cur = [], []
    for a in sorted(addrs):
        if cur and a != cur[-1] + 4:
            out.append(cur)
            cur = []
        cur.append(a)
        if len(cur) >= BLOCK:
            out.append(cur)
            cur = []
    if cur:
        out.append(cur)
    return out


def emit_block(fn, addrs, dec):
    lo, hi = addrs[0], addrs[-1]
    lines = [
        "fun NmArmState.%s() {" % fn,
        "    val B = %dL" % lo,
        "    var g = 0",
        "    while (pc in B..%dL && g++ < NmArmState.FUNC_STEP_CAP) {" % hi,
        "        val p = pc",
        # 这一拍有没有真的执行过指令，用 steps 的增量判断：块内分支跳回**块入口**
        # 时 `pc == p` 同样成立，若只看 `pc == p` 就会把入口那条指令当成「没人匹配」
        # 而强行 +4 跳过它（实测 audio @0x1dfd4 的 tbz 回跳就这样吃掉了
        # `subs x22,x22,#0x88`）。@spec Z-10
        "        val s0 = steps",
        "        if (p in B..%dL) {" % hi,
    ]
    def lits(x):
        # Kotlin 里 -9223372036854775808L 会被当成「一元负号 + 超范围字面量」而报错，
        # 必须写成 (-9223372036854775807L - 1L)。
        if x == -(2 ** 63):
            return "(-9223372036854775807L - 1L)"
        return "%dL" % x

    for a in addrs:
        op, rd, rn, rm, imm, imm2, f, cc = dec[a]
        args = "B+%dL,%d,%d,%d,%d,%s" % (a - lo, op, rd, rn, rm, lits(imm))
        if imm2 != 0 or f != 0 or cc != 0:
            args += ",%s" % lits(imm2)
            if f != 0 or cc != 0:
                args += ",0x%x" % f
                if cc != 0:
                    args += ",0x%x" % cc
        lines.append("            i(%s)" % args)
    lines += [
        "            if (steps == s0 && pc == p) pc = p + 4",
        "        } else pc = p + 4",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    report_only = "--report" in sys.argv
    opnames = {v: k for k, v in z_isa.OPS.items()}
    if not report_only:
        n = z_isa.write_nmop(KT / "NmOp.kt")
        print("[Z] NmOp.kt ops=%d" % n)

    infos, all_blocks, all_entries = [], [], []
    op_hist = Counter()
    total_ins = 0
    so_meta = {}

    for so_name, domain in SO:
        info = discover(JNI / so_name, domain)
        infos.append(info)
        reach = info["reachable"]
        total_ins += len(reach)
        for a in reach:
            op_hist[opnames[info["dec"][a][0]]] += 1
        blks = blocks_of(reach)
        for b in blks:
            fn = "nb_%s_%x" % (domain.replace("-", "_"), b[0])
            all_blocks.append((domain, b[0], b[-1], fn, b))
        for a, origin in info["entries"].items():
            all_entries.append((domain, a, origin))
        so_meta[so_name] = {
            "domain": domain, "sha256": info["sha256"], "bytes": info["bytes"],
            "textAddr": hex(info["lo"]), "textSize": info["text"]["size"],
            "textInstructions": info["textIns"],
            "translatedInstructions": len(reach),
            "literalPoolInstructions": info["literalIns"],
            "undecodableInReach": len(info["undecReach"]),
            "dynsymFunctions": info["symbolCount"],
            "entryCount": len(info["entries"]),
            "blockCount": len(blks),
            "entryOrigins": dict(Counter(info["entries"].values())),
        }
        print("[Z] %-24s text=%-7d translated=%-7d literal=%-6d undec=%-3d "
              "entries=%-5d blocks=%-5d"
              % (so_name, info["textIns"], len(reach), info["literalIns"],
                 len(info["undecReach"]), len(info["entries"]), len(blks)))

    if report_only:
        print("[Z] total translated=%d entries=%d blocks=%d ops=%d"
              % (total_ins, len(all_entries), len(all_blocks), len(op_hist)))
        return 0

    for stale in KT.glob("NmCode*.kt"):
        stale.unlink()
    for p in KT.glob("NmTranslated*.kt"):
        p.unlink()
    for name in ("NmOpcodes.kt", "NmArm64Runtime.kt"):
        p = KT / name
        if p.exists():
            p.unlink()

    header = ['@file:Suppress("unused", "UNUSED_PARAMETER")',
              "package cloudmusic.reverse.kernel", "",
              "/** 【生成物】Z 组：可达指令的逐行翻译（一条指令一行）。 @spec Z-01 */", ""]

    by_domain = {i["domain"]: i for i in infos}
    file_no, buf, written = 0, [], []
    for domain, lo, hi, fn, addrs in all_blocks:
        buf.append(emit_block(fn, addrs, by_domain[domain]["dec"]))
        if len(buf) >= BLOCKS_PER_FILE:
            name = "NmCode%02d.kt" % file_no
            (KT / name).write_text("\n".join(header + buf), encoding="utf-8", newline="\n")
            written.append(name)
            file_no += 1
            buf = []
    if buf:
        name = "NmCode%02d.kt" % file_no
        (KT / name).write_text("\n".join(header + buf), encoding="utf-8", newline="\n")
        written.append(name)
        file_no += 1

    # ---------------- 入口包装（按 WRAP_PER_FILE 分文件，避开常量池上限） ----------------
    for stale in KT.glob("NmFuncs*.kt"):
        stale.unlink()
    for stale in KT.glob("NmBlocks*.kt"):
        stale.unlink()
    wrap_files = []
    for chunk_no in range(0, max(1, (len(all_entries) + WRAP_PER_FILE - 1) // WRAP_PER_FILE)):
        part = all_entries[chunk_no * WRAP_PER_FILE:(chunk_no + 1) * WRAP_PER_FILE]
        if not part:
            break
        name = "NmFuncs%02d.kt" % chunk_no
        body = ['@file:Suppress("unused")', "package cloudmusic.reverse.kernel", "",
                "/** 【生成物】Z 组：函数入口可调用包装 第 %d 批（共 %d 个入口）。 */"
                % (chunk_no, len(all_entries)), ""]
        for domain, a, origin in part:
            d = domain.replace("-", "_")
            body.append('fun nf_%s_%x(st: NmArmState): Long = '
                        'NmMachine(st).call("%s", %dL)   // %s' % (d, a, domain, a, origin))
        (KT / name).write_text("\n".join(body) + "\n", encoding="utf-8", newline="\n")
        wrap_files.append(name)

    # ---------------- 索引：base64(每块 4 字节 lo + 1 字节 span) ----------------
    # 不用 longArrayOf 字面量：13000 个字面常量会让 <clinit> 超过 JVM 64KB 方法上限
    # （已用 build/zchk 最小样例实证 Method too large: <clinit>）。
    domains = ["cache", "engine", "audio", "audio_legacy"]
    packed, base_of, count_of = {}, {}, {}
    for d in domains:
        subset = sorted([(lo, hi) for dom, lo, hi, _fn, _a in all_blocks
                         if dom.replace("-", "_") == d])
        raw = bytearray()
        for lo, hi in subset:
            span = (hi - lo) // 4 + 1
            assert 1 <= span <= BLOCK, (d, lo, span)
            raw += struct.pack(">IB", lo, span)
        packed[d] = base64.b64encode(bytes(raw)).decode("ascii")
        count_of[d] = len(subset)
    acc = 0
    for d in domains:
        base_of[d] = acc
        acc += count_of[d]

    # 入口地址索引：每域 base64(4 字节地址/条)
    epack, ecount = {}, {}
    for d in domains:
        addrs = sorted({a for dom, a, _o in all_entries if dom.replace("-", "_") == d})
        epack[d] = base64.b64encode(b"".join(struct.pack(">I", a) for a in addrs)).decode("ascii")
        ecount[d] = len(addrs)
    assert sum(ecount.values()) == len(all_entries), (ecount, len(all_entries))

    cat = ['@file:Suppress("unused")', "package cloudmusic.reverse.kernel", "",
           "/** 【生成物】Z 组：%d 个代码块 / %d 个函数入口索引。 @spec Z-03 @spec Z-04"
           % (len(all_blocks), len(all_entries)),
           " *",
           " * 索引以 base64 字符串常量携带（每块 4 字节 lo + 1 字节 span），",
           " * 不用 longArrayOf 字面量——字面量形式会让 <clinit> 撞上 JVM 64KB 方法上限。",
           " */",
           "object NmTranslatedCatalog {",
           "    const val BLOCK_COUNT: Int = %d" % len(all_blocks),
           "    const val ENTRY_COUNT: Int = %d" % len(all_entries),
           "    const val INSTRUCTION_COUNT: Int = %d" % total_ins,
           ""]
    for d in domains:
        cat.append("    const val %s_BLOCKS: Int = %d" % (d.upper(), count_of[d]))
    cat.append("    const val %s_BASE: Int = %d" % (domains[0].upper(), base_of[domains[0]]))
    for d in domains[1:]:
        cat.append("    const val %s_BASE: Int = %d" % (d.upper(), base_of[d]))
    cat.append("")
    for d in domains:
        cat.append("    private const val IDX_%s: String =" % d.upper())
        lit = packed[d]
        for k in range(0, len(lit), 96):
            cat.append('        "%s" +' % lit[k:k + 96])
        cat[-1] = cat[-1][:-2]
        cat.append("")
    for d in domains:
        cat.append("    const val %s_ENTRIES: Int = %d" % (d.upper(), ecount[d]))
    cat.append("")
    for d in domains:
        cat.append("    private const val EN_%s: String =" % d.upper())
        lit = epack[d]
        for k in range(0, len(lit), 96):
            cat.append('        "%s" +' % lit[k:k + 96])
        cat[-1] = cat[-1][:-2]
        cat.append("")
    cat.append('''    private fun b64v(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /** base64 -> 每块一个 Long：`lo shl 8 or span`。 */
    private fun pack(s: String): LongArray {
        val raw = ByteArray(s.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        var o = 0
        for (ch in s) {
            val v = b64v(ch)
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                raw[o++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        val n = o / 5
        val out = LongArray(n)
        for (k in 0 until n) {
            val p = k * 5
            val lo = ((raw[p].toLong() and 0xFF) shl 24) or
                ((raw[p + 1].toLong() and 0xFF) shl 16) or
                ((raw[p + 2].toLong() and 0xFF) shl 8) or
                (raw[p + 3].toLong() and 0xFF)
            out[k] = (lo shl 8) or (raw[p + 4].toLong() and 0xFF)
        }
        return out
    }''')
    for d in domains:
        cat.append("    private val idx%s: LongArray by lazy { pack(IDX_%s) }" % (d, d.upper()))
    cat.append("")
    cat.append('''    private fun arrOf(idx: Int): LongArray? = when {
        idx < 0 || idx >= BLOCK_COUNT -> null
        idx < %s -> idxcache
        idx < %s -> idxengine
        idx < %s -> idxaudio
        else -> idxaudio_legacy
    }

    private fun baseOf(idx: Int): Int = when {
        idx < %s -> CACHE_BASE
        idx < %s -> ENGINE_BASE
        idx < %s -> AUDIO_BASE
        else -> AUDIO_LEGACY_BASE
    }

    /** 全局块下标的起始地址；越界返回 -1。 */
    fun blockLo(idx: Int): Long {
        val a = arrOf(idx) ?: return -1L
        return a[idx - baseOf(idx)] ushr 8
    }

    /** 全局块下标的指令槽数；越界返回 0。 */
    fun blockSpan(idx: Int): Long {
        val a = arrOf(idx) ?: return 0L
        return a[idx - baseOf(idx)] and 0xFF
    }
''' % (domains[1].upper() + "_BASE", domains[2].upper() + "_BASE",
       domains[3].upper() + "_BASE",
       domains[1].upper() + "_BASE", domains[2].upper() + "_BASE",
       domains[3].upper() + "_BASE"))
    cat.append('''    /** 返回全局块下标（0..BLOCK_COUNT-1），不在任何块内返回 -1。 */
    fun blockIndexOf(so: String, pc: Long): Int {
        val idx: LongArray
        val base: Int
        when (so) {
            "cache" -> { idx = idxcache; base = CACHE_BASE }
            "engine" -> { idx = idxengine; base = ENGINE_BASE }
            "audio" -> { idx = idxaudio; base = AUDIO_BASE }
            "audio_legacy", "audio-legacy" -> { idx = idxaudio_legacy; base = AUDIO_LEGACY_BASE }
            else -> return -1
        }
        var a = 0
        var b = idx.size - 1
        while (a <= b) {
            val m = (a + b) ushr 1
            val lo = idx[m] ushr 8
            if (pc < lo) {
                b = m - 1
            } else {
                val span = idx[m] and 0xFF
                if (pc > lo + span * 4 - 4) a = m + 1 else return base + m
            }
        }
        return -1
    }''')
    cat.extend([
        "",
        "    private fun base64Bytes(s: String): ByteArray {",
        "        val raw = ByteArray(s.length * 3 / 4 + 3)",
        "        var acc = 0",
        "        var bits = 0",
        "        var o = 0",
        "        for (ch in s) {",
        "            val v = b64v(ch)",
        "            if (v < 0) continue",
        "            acc = (acc shl 6) or v",
        "            bits += 6",
        "            if (bits >= 8) {",
        "                bits -= 8",
        "                raw[o++] = ((acc ushr bits) and 0xFF).toByte()",
        "            }",
        "        }",
        "        return raw.copyOf(o)",
        "    }",
        "",
        "    /** 入口地址表（升序）。未知 so 返回空数组。 */",
        "    fun entryAddrs(so: String): LongArray {",
        "        val bytes = base64Bytes(",
        "            when (so) {",
        "                \"cache\" -> EN_CACHE",
        "                \"engine\" -> EN_ENGINE",
        "                \"audio\" -> EN_AUDIO",
        "                \"audio_legacy\", \"audio-legacy\" -> EN_AUDIO_LEGACY",
        "                else -> return LongArray(0)",
        "            }",
        "        )",
        "        val n = bytes.size / 4",
        "        val out = LongArray(n)",
        "        for (k in 0 until n) {",
        "            val p = k * 4",
        "            out[k] = ((bytes[p].toLong() and 0xFF) shl 24) or",
        "                ((bytes[p + 1].toLong() and 0xFF) shl 16) or",
        "                ((bytes[p + 2].toLong() and 0xFF) shl 8) or",
        "                (bytes[p + 3].toLong() and 0xFF)",
        "        }",
        "        return out",
        "    }",
        "",
        "    /** 四个域的规范名，顺序即块下标区间顺序。 */",
        "    val DOMAINS: List<String> = listOf(\"cache\", \"engine\", \"audio\", \"audio-legacy\")",
        "",
        "    fun blocksOf(so: String): Int = when (so) {",
        "        \"cache\" -> CACHE_BLOCKS",
        "        \"engine\" -> ENGINE_BLOCKS",
        "        \"audio\" -> AUDIO_BLOCKS",
        "        \"audio_legacy\", \"audio-legacy\" -> AUDIO_LEGACY_BLOCKS",
        "        else -> 0",
        "    }",
        "",
        "    fun entriesOf(so: String): Int = when (so) {",
        "        \"cache\" -> CACHE_ENTRIES",
        "        \"engine\" -> ENGINE_ENTRIES",
        "        \"audio\" -> AUDIO_ENTRIES",
        "        \"audio_legacy\", \"audio-legacy\" -> AUDIO_LEGACY_ENTRIES",
        "        else -> 0",
        "    }",
    ])
    cat.append("}")
    (KT / "NmTranslatedCatalog.kt").write_text("\n".join(cat) + "\n",
                                               encoding="utf-8", newline="\n")

    # ---------------- 分派：按全局块下标 tableswitch 到块函数 ----------------
    blk = ['@file:Suppress("unused")', "package cloudmusic.reverse.kernel", "",
           "/** 【生成物】Z 组：%d 个代码块的 tableswitch 分派。 @spec Z-08" % len(all_blocks),
           " *",
           " * 不用 `arrayOf<(NmArmState) -> Unit>(::nb_xxx, ...)`：6326 个函数引用会为",
           " * 每个引用生成一个类，且 6326 个 Long 常量同样撞 <clinit> 上限。",
           " * 每个分派函数 %d 个分支，实测 ~8.4KB 字节码，远低于 64KB。" % DISPATCH_CHUNK,
           " */",
           "object NmBlocks {"]
    chunk_fns = []
    for ci in range(0, (len(all_blocks) + DISPATCH_CHUNK - 1) // DISPATCH_CHUNK):
        part = all_blocks[ci * DISPATCH_CHUNK:(ci + 1) * DISPATCH_CHUNK]
        fn = "d%02d" % ci
        chunk_fns.append((fn, len(part)))
        blk.append("    private fun %s(i: Int, st: NmArmState) {" % fn)
        blk.append("        when (i) {")
        for k, (_d, _lo, _hi, bfn, _a) in enumerate(part):
            blk.append("            %d -> st.%s()" % (k, bfn))
        blk.append("            else -> {}")
        blk.append("        }")
        blk.append("    }")
        blk.append("")
    blk.append("    fun exec(idx: Int, st: NmArmState) {")
    blk.append("        if (idx < 0 || idx >= NmTranslatedCatalog.BLOCK_COUNT) return")
    blk.append("        val c = idx / %d" % DISPATCH_CHUNK)
    blk.append("        val i = idx - c * %d" % DISPATCH_CHUNK)
    blk.append("        when (c) {")
    for k, (fn, _n) in enumerate(chunk_fns):
        blk.append("            %d -> %s(i, st)" % (k, fn))
    blk.append("            else -> {}")
    blk.append("        }")
    blk.append("    }")
    blk.append("}")
    (KT / "NmBlocks.kt").write_text("\n".join(blk) + "\n", encoding="utf-8", newline="\n")

    manifest = {
        "tool": "reverse-engine/tools/z_transpile.py",
        "method": "code-reachability discovery (dynsym + data pointers + recursive descent "
                  "+ gap classification) -> one Kotlin i(...) line per instruction",
        "mode": "offline, read-only, no adb",
        "criterion": "code coverage of .text, not symbol table",
        "soMeta": so_meta,
        "blockCount": len(all_blocks),
        "entryCount": len(all_entries),
        "instructionCount": total_ins,
        "opcodeHistogram": dict(sorted(op_hist.items(), key=lambda x: -x[1])),
        "textAccounting": {
            "textInstructions": sum(v["textInstructions"] for v in so_meta.values()),
            "translated": total_ins,
            "literalPool": sum(v["literalPoolInstructions"] for v in so_meta.values()),
            "undecodableInReach": sum(v["undecodableInReach"] for v in so_meta.values()),
        },
        "generatedFiles": written + wrap_files + ["NmBlocks.kt", "NmTranslatedCatalog.kt", "NmOp.kt"],
        # Z-09（Unicorn 语义差分）与 Z-10（差分暴露的语义缺陷修正）不是本脚本
        # 产出的，但同属 Z 组口径，manifest 一并登记，避免编号集合被读成 Z-01~Z-08。
        "specIds": ["Z-%02d" % i for i in range(1, 11)],
        "jvmLimits": {
            "clinit64k": "索引不用 longArrayOf 字面量：13000 个 Long 常量会让 <clinit> "
                         "超过 JVM 64KB 方法上限（build/zchk 最小样例实证 Method too large）。"
                         "改用 base64 字符串常量 + 运行期 pack()。",
            "dispatchSwitch": "块分派用每函数 %d 分支的 tableswitch，不用 %d 个函数引用数组。"
                              % (DISPATCH_CHUNK, len(all_blocks)),
            "wrapperSplit": "7486 个入口包装按每文件 %d 个拆分，避开单类常量池 65535 上限。"
                            % WRAP_PER_FILE,
        },
        "honestNotes": [
            "函数清单以代码可达性为准，不以符号表为准；so 已 strip，无 .symtab。",
            ".text 中被 adr/adrp 引用且不可达的连续区段判为字面量池，不计为代码。",
            "跨函数调用由 NmMachine 按 pc 区间分派，bl/ret/尾调用/间接跳转均真实执行。",
            "MRS 读计数器/TLS 属进程外部状态，用确定性桩值并在 NmExternal 登记。",
            "PLT 到 libc/libc++ 的外部调用目标不在 .text，执行到边界即停止并登记。",
            "F4 仍 BLOCKED；本目录不实现 B2 / M2 / M3。",
        ],
    }
    OUT_MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2),
                            encoding="utf-8", newline="\n")

    size = sum((KT / n).stat().st_size for n in written + wrap_files +
               ["NmBlocks.kt", "NmTranslatedCatalog.kt"])
    print("[Z] blocks=%d entries=%d instructions=%d files=%d size=%.1f MiB"
          % (len(all_blocks), len(all_entries), total_ins,
             len(written) + len(wrap_files) + 2, size / 1048576))
    print("[Z] dispatchChunks=%d wrapFiles=%d indexBase64=%s"
          % (len(chunk_fns), len(wrap_files),
             " ".join("%s=%d/%dB" % (d, count_of[d], len(packed[d])) for d in domains)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
