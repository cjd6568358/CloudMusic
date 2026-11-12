#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X 组 —— 竞品播放内核「函数全集」穷举取证（不漏过任何一个竞品逻辑）。

以真实 so 的 .dynsym STT_FUNC 为准（不是 docs 章节、不是证据文件清单），
对 4 个竞品自研 so 的每个函数做 capstone 反汇编并提取控制流特征：
  * 条件分支数 / 调用数 / 负立即数错误码 / .rodata 日志字符串引用 / 结构体偏移
  * 分类：logic（含竞品控制流）/ thunk / trivial / runtime / data_or_linear
产出 spec/x-function-inventory.json，绑定各 so 的 sha256 与函数总数。

libffmpeg.so 是第三方 FFmpeg（2659 个导出函数），单独登记为 third-party，
不混入竞品自研逻辑；其 100 decoder / 75 demuxer 已由 F 组全量登记。

用法：python tools/x_function_inventory.py
"""
from __future__ import annotations

import hashlib
import json
import struct
import sys
from collections import Counter
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

RE = Path(__file__).resolve().parent.parent
ROOT = RE.parent
JNI = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
OUT = RE / "spec" / "x-function-inventory.json"

OWN_SO = [
    ("libNMCommonCache.so", "cache"),
    ("libncmplayerengine.so", "engine"),
    ("libncmaudioplayer.so", "audio"),
    ("libaudioplayer.so", "audio-legacy"),
]
THIRD_PARTY = ["libffmpeg.so"]

BRANCH_MNEMONICS = {"b.eq", "b.ne", "b.lt", "b.le", "b.gt", "b.ge", "b.hi", "b.hs",
                    "b.lo", "b.ls", "b.mi", "b.pl", "b.vs", "b.vc", "b.cs", "b.cc",
                    "cbz", "cbnz", "tbz", "tbnz"}
CALL_MNEMONICS = {"bl", "blr"}
RUNTIME_MARKERS = ("std::__ndk1", "__cxa", "operator new", "operator delete", "_ZSt",
                   "__clang", "__gxx", "llvm.", "abort", "__stack_chk", "memcpy",
                   "memset", "strlen", "malloc", "free", "_ZNSt", "pthread_")


def elf_sections(data: bytes) -> list[dict]:
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        nm, _typ, _fl, addr, off, size, link, _info, _al, _ent = struct.unpack_from(
            "<IIQQQQIIQQ", data, o)
        out.append({"name": nm, "addr": addr, "offset": off, "size": size, "link": link})
    shstr = out[e_shstrndx]
    for s in out:
        end = data.find(b"\x00", shstr["offset"] + s["name"])
        s["sname"] = data[shstr["offset"] + s["name"]:end].decode("utf-8", "replace")
    return out


def dyn_functions(data: bytes, secs: list[dict]) -> list[tuple[str, int, int]]:
    sec = next(s for s in secs if s["sname"] == ".dynsym")
    dstr = secs[sec["link"]]
    out = []
    for o in range(sec["offset"], sec["offset"] + sec["size"], 24):
        n, info, _other, _shndx, val, sz = struct.unpack_from("<IBBHQQ", data, o)
        if val and (info & 0xF) == 2:  # STT_FUNC
            end = data.find(b"\x00", dstr["offset"] + n)
            out.append((data[dstr["offset"] + n:end].decode("utf-8", "replace"), val, sz))
    return sorted(out, key=lambda x: x[1])


def cstr(data: bytes, addr: int, sec: dict, limit: int = 110) -> str:
    if not (sec["addr"] <= addr < sec["addr"] + sec["size"]):
        return ""
    off = sec["offset"] + (addr - sec["addr"])
    end = data.find(b"\x00", off, off + 512)
    if end < 0:
        return ""
    raw = data[off:end]
    if not raw or any(b < 9 for b in raw):
        return ""
    return raw.decode("utf-8", "replace")[:limit]


def classify(name: str, size: int, insns: list, branches: int, calls: int,
             strings: list[str], neg_imm: list[int]) -> str:
    if any(m in name for m in RUNTIME_MARKERS):
        return "runtime"
    if size <= 16 and len(insns) <= 3 and any(i[1] in ("b", "br") for i in insns):
        return "thunk"
    if branches == 0 and len(insns) <= 8 and not strings:
        return "trivial"
    if branches > 0 or strings or neg_imm or calls >= 2:
        return "logic"
    return "data_or_linear"


def main() -> int:
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    inventory, so_meta, counters = [], {}, Counter()

    for fname, domain in OWN_SO:
        p = JNI / fname
        if not p.is_file():
            print("[FAIL] missing %s" % fname)
            return 1
        data = p.read_bytes()
        secs = elf_sections(data)
        text = next(s for s in secs if s["sname"] == ".text")
        rodata = next((s for s in secs if s["sname"] == ".rodata"), None)
        funcs = dyn_functions(data, secs)

        # 全 .text 反汇编一次，按地址索引
        code = data[text["offset"]:text["offset"] + text["size"]]
        all_ins = {i.address: (i.mnemonic, i.op_str, i.operands)
                   for i in md.disasm(code, text["addr"])}

        so_meta[fname] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "domain": domain,
            "textAddr": hex(text["addr"]),
            "textSize": hex(text["size"]),
            "functionCount": len(funcs),
        }

        for name, addr, size in funcs:
            lo, hi = addr, addr + min(size, 0x10000)
            insns = [(a, all_ins[a][0], all_ins[a][1]) for a in range(lo, hi, 4) if a in all_ins]
            branches = sum(1 for _a, m, _o in insns if m in BRANCH_MNEMONICS)
            calls = sum(1 for _a, m, _o in insns if m in CALL_MNEMONICS)
            neg_imm, offsets, strings, page = [], set(), [], {}
            for a, m, o in insns:
                if m == "adrp" and len(all_ins[a][2]) >= 2:
                    try:
                        page[o.split(",")[0].strip()] = all_ins[a][2][1].imm
                    except (ValueError, IndexError, AttributeError):
                        pass
                elif m == "add" and "," in o:
                    parts = [x.strip() for x in o.split(",")]
                    if len(parts) >= 3 and parts[0] in page and parts[2].startswith("#"):
                        try:
                            target = page[parts[0]] + int(parts[2][1:], 0)
                            if rodata:
                                s = cstr(data, target, rodata)
                                if s and s not in strings:
                                    strings.append(s)
                        except (ValueError, KeyError):
                            pass
                if m in ("ldr", "str", "ldrb", "strb", "ldrsw") and "[" in o and "#" in o:
                    try:
                        imm = o.split("#")[-1].replace("]", "").strip()
                        offsets.add(int(imm, 0))
                    except ValueError:
                        pass
                if m == "mov" and "#-" in o:
                    try:
                        neg_imm.append(int(o.split("#")[-1].strip(), 0))
                    except ValueError:
                        pass

            cls = classify(name, size, insns, branches, calls, strings, neg_imm)
            counters[cls] += 1
            inventory.append({
                "so": fname, "domain": domain, "name": name,
                "addr": hex(addr), "size": size,
                "insnCount": len(insns), "branches": branches, "calls": calls,
                "stringRefs": strings[:3], "offsets": sorted(offsets)[:8],
                "negativeImmediates": sorted(set(neg_imm))[:8],
                "classification": cls,
            })

    third = []
    for fname in THIRD_PARTY:
        p = JNI / fname
        if p.is_file():
            d = p.read_bytes()
            secs = elf_sections(d)
            third.append({"so": fname, "sha256": hashlib.sha256(d).hexdigest(),
                          "bytes": len(d), "functionCount": len(dyn_functions(d, secs)),
                          "disposition": "third-party FFmpeg; 100 decoder / 75 demuxer 已由 F 组全量登记"})

    report = {
        "tool": "reverse-engine/tools/x_function_inventory.py",
        "method": "ELF64 .dynsym STT_FUNC enumeration + capstone ARM64 disassembly per function",
        "mode": "offline, read-only, no adb",
        "soMeta": so_meta,
        "ownFunctionTotal": len(inventory),
        "classificationCounts": dict(counters),
        "thirdParty": third,
        "functions": inventory,
        "dispositionRule": (
            "logic = 含竞品控制流，须 1:1 翻译或逐条给出可复核的处置理由；"
            "thunk / trivial / runtime / data_or_linear 逐条登记机器判据，不允许出现未处置函数。"
        ),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                   encoding="utf-8", newline="\n")
    print("[X-INV] ownFunctions=%d classes=%s" % (len(inventory), dict(counters)))
    for k, v in so_meta.items():
        print("[X-INV]   %-26s funcs=%-5d sha=%s" % (k, v["functionCount"], v["sha256"][:16]))
    print("[X-INV] %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
