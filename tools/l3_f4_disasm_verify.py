#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.54 / F4 · `OnNetSourceDataDone` kind 分支的一手二进制复核（离线）。

承接 `analysis/l3-f4-kind-slot-rootcause.md`（§61.52）的假设：
`OnDataRequestEnd` 首参是 native `OnNetSourceDataDone(int kind, ...)` 的 kind 槽，
`kind==2` 走 `PreloadDoneTaskOnWriteThread`，其余走 `OnFlushNetData`。
§61.52 当时只引用 `docs/12 §59.2` 的文字记录；本工具直接反汇编
`libNMCommonCache.so` 并解析 .rela.dyn 的 GOT 重定位，把该前提升级为
**一手二进制证据**。

只读、纯标准库 + capstone + pyelftools（本机已具备），不连 adb、不改任何文件。
产出 analysis/l3-f4-disasm-verify.json。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

ROOT = Path(__file__).resolve().parent.parent
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
OUT = ROOT / "analysis" / "l3-f4-disasm-verify.json"

FUNC = "_ZN17NMBufferSourceImp19OnNetSourceDataDoneEi"
GOT_KIND2 = 0x4BED0
GOT_OTHER = 0x4BEE8
QUEUE_CALL = 0x16468
FUNC_ADDR = 0xBEE8
FUNC_SIZE = 384
SYM_PRELOAD_DONE = "_ZN17NMBufferSourceImp28PreloadDoneTaskOnWriteThreadEv"
SYM_FLUSH = "_ZN17NMBufferSourceImp14OnFlushNetDataEv"
SYM_PRELOAD_ADDR = 0xB86C
SYM_FLUSH_ADDR = 0xC068
BRANCH_TARGET = 0xBFA0
QUEUE_SITES = (0xBF7C, 0xC004)
RELOC_GLOB_DAT = 1025  # R_AARCH64_GLOB_DAT


def elf_info(path: Path) -> dict:
    with path.open("rb") as f:
        elf = ELFFile(f)
        symtab = elf.get_section_by_name(".symtab")
        dynsym = elf.get_section_by_name(".dynsym")
        symbols = {}
        for sec in (symtab, dynsym):
            if sec is None:
                continue
            for s in sec.iter_symbols():
                if s["st_value"]:
                    symbols.setdefault(s.name, s["st_value"])
        relocs = {}
        for sec in elf.iter_sections():
            if not isinstance(sec, RelocationSection):
                continue
            symtab_for = elf.get_section(sec["sh_link"])
            for r in sec.iter_relocations():
                off = r["r_offset"]
                sym_idx = r["r_info_sym"]
                name = symtab_for.get_symbol(sym_idx).name if symtab_for else ""
                addend = r["r_addend"] if r.is_RELA() else 0
                if off not in relocs or name:
                    relocs[off] = {
                        "symbol": name,
                        "addend": addend,
                        "type": r["r_info_type"],
                        "symbol_addr": (symtab_for.get_symbol(sym_idx)["st_value"]
                                       if symtab_for else 0),
                    }
        return {"symbols": symbols, "relocs": relocs}


def disassemble(path: Path, addr: int, size: int) -> list[str]:
    data = path.read_bytes()
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    # .symtab 的 st_value 是 vaddr；本 so 的 .text vaddr == 文件 offset（段 0 映射）
    return ["0x%x  %-9s %s" % (i.address, i.mnemonic, i.op_str)
            for i in md.disasm(data[addr:addr + size], addr)]


def main() -> int:
    if not SO.is_file():
        print("[FAIL] 缺少 %s" % SO)
        return 1
    info = elf_info(SO)
    syms, relocs = info["symbols"], info["relocs"]
    func_addr, func_size = None, None
    # 再从 .symtab 取 size（上面 elf_info 只存了 value）
    with SO.open("rb") as f:
        elf = ELFFile(f)
        # 发布 so 已被 strip：没有 .symtab，只有 .dynsym（256 个动态符号）。
        st = (elf.get_section_by_name(".symtab")
              or elf.get_section_by_name(".dynsym"))
        for s in st.iter_symbols():
            if s.name == FUNC:
                func_addr = s["st_value"]
                func_size = s["st_size"]
                break
    if func_addr is None:
        print("[FAIL] 找不到符号 %s" % FUNC)
        return 1

    ins = disassemble(SO, func_addr, func_size)
    text = "\n".join(ins)
    cmp_line = next((l for l in ins if re.search(r"cmp\s+w1, #2", l)), None)
    branch_line = next((l for l in ins if re.search(r"b\.ne", l)), None)
    got2_ref = next((l for l in ins if "#0xed0" in l), None)
    got1_ref = next((l for l in ins if "#0xee8" in l), None)
    queue_calls = len(re.findall(r"bl\s+#0x16468", text))

    r2 = relocs.get(GOT_KIND2, {})
    r1 = relocs.get(GOT_OTHER, {})
    resolved = {}
    for label, r in (("kind2", r2), ("other", r1)):
        name = r.get("symbol", "")
        if not name and r.get("addend"):
            name = next((n for n, v in syms.items() if v == r["addend"]), "")
        resolved[label] = {
            "symbol": name,
            "addend": r.get("addend", 0),
            "reloc_type": r.get("type", 0),
            "symbol_addr": r.get("symbol_addr", 0),
        }

    queue_sites = [int(l.split()[0], 16) for l in ins
                   if re.search(r"bl\s+#0x16468", l)]
    checks = {
        "func_symbol_found": func_addr == FUNC_ADDR and func_size == FUNC_SIZE,
        "cmp_kind_against_2": cmp_line is not None and "0xbf08" in cmp_line,
        "conditional_branch_to_flush_path": (
            branch_line is not None and hex(BRANCH_TARGET) in branch_line),
        "two_got_loads_present": got2_ref is not None and got1_ref is not None,
        "both_post_to_same_queue": (
            queue_calls == 2 and queue_sites == list(QUEUE_SITES)),
        "got_kind2_resolves_to_preload_done": (
            resolved["kind2"]["symbol"] == SYM_PRELOAD_DONE
            and resolved["kind2"]["symbol_addr"] == SYM_PRELOAD_ADDR),
        "got_other_resolves_to_flush": (
            resolved["other"]["symbol"] == SYM_FLUSH
            and resolved["other"]["symbol_addr"] == SYM_FLUSH_ADDR),
        "both_got_relocs_are_glob_dat": (
            resolved["kind2"]["reloc_type"] == RELOC_GLOB_DAT
            and resolved["other"]["reloc_type"] == RELOC_GLOB_DAT),
        "task_executed_by_virtual_dispatch": "blr" in text and "x8" in text,
        "kind2_slot_is_0x4bed0_other_is_0x4bee8": (
            "#0xed0" in (got2_ref or "") and "#0xee8" in (got1_ref or "")),
    }
    failed = [k for k, v in checks.items() if not v]
    data = {
        "method": "12 §61.54 / F4 kind 槽假设的 native 侧一手二进制复核（离线、只读）",
        "generated_by": "tools/l3_f4_disasm_verify.py",
        "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
        "function": {"name": FUNC, "addr": hex(func_addr), "size": func_size},
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence": {
            "cmp_kind_line": cmp_line,
            "branch_line": branch_line,
            "got_kind2_load": got2_ref,
            "got_other_load": got1_ref,
            "queue_bl_0x16468_count": queue_calls,
            "queue_call_sites": [hex(a) for a in queue_sites],
            "got_relocations": {
                hex(GOT_KIND2): resolved["kind2"],
                hex(GOT_OTHER): resolved["other"],
            },
            "key_instructions": [l for l in ins
                                 if any(k in l for k in ("cmp", "b.ne", "0xed0",
                                                         "0xee8", "0x16468",
                                                         "blr"))],
            "section_note": "发布 so 已 strip：无 .symtab，符号与 size 取自 .dynsym；.text vaddr==file offset（0x8ed0）",
        },
        "conclusion": (
            "libNMCommonCache.so 的 OnNetSourceDataDone 首参 w1 与 #2 比较："
            "kind==2 从 GOT 0x4bed0 取 PreloadDoneTaskOnWriteThread，"
            "其余从 GOT 0x4bee8 取 OnFlushNetData，两者都投递 0x16468 队列。"
            "§59.2 的文字记录经一手反汇编复核成立，§61.52 kind 槽假设的 "
            "native 侧前提由此加固；但假设本身（id=2 导致 fMP4 卡点）"
            "仍需设备上线做 A/B 才能证实。"),
        "honest_notes": [
            "本工具只复核 native 分支语义，不证明 Java 侧传 requestId 与 fMP4 卡点的因果。",
            "§59.2 的 kind==2 / 其余 二分语义由一手反汇编 + GOT GLOB_DAT 重定位双重确认；但这是必要条件，不是充分条件。",
            "§59.2 exp6 已知反例（两条路径读侧均正常收尾）仍未解释；竞品同样传 requestId 却可用，原因未实证。",
            "纯离线、只读、不连 adb、不改任何生产代码；零新增依赖（capstone/pyelftools 为本机已有）。",
            "A3 仍 G09 PARTIAL、舱不冻结；F4 仍 BLOCKED。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] cmp=%s / branch=%s / GOT2=%s / GOT1=%s / queue=%d"
          % (bool(cmp_line), bool(branch_line), got2_ref, got1_ref, queue_calls))
    print("[INFO] GOT relocs: %s" % data["evidence"]["got_relocations"])
    if failed:
        print("[FAIL] §61.54 复核未通过：%s" % failed)
        return 1
    print("[PASS] §61.54 复核：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
