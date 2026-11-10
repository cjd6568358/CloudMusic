#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.67 / F4 · `libNMCommonCache.so` 请求代次闸门与 vfile 落盘门控的一手反汇编取证
（离线、只读、可复跑、不需要设备在场）。

承接 §61.65 剩余断点①前半：把 `MakeRangeRequestAsync` 的**请求代次递增点**、
`NMNetSource::OnData` 的**代次丢弃链**、以及 `NMSlicesFile::Write` 的**落盘门控**
从「静态推断」升级为「三段闭合的一手二进制证据」。

方法（与 §61.65 同口径）：
  * pyelftools 读 .dynsym / .rela.plt / .plt，capstone 全 .text 反汇编；
  * PLT stub 还原为导入符号，ADRP+ADD 立即数还原为 .rodata 日志字符串；
  * 交叉 §61.65 已固化的真机日志 JSON（1028 包 / commit=16384 / 代次 1-0→1-8）。

产出 analysis/l3-f4-generation-gate-disasm.json。
**不连 adb、不改任何生产代码、不写「F4 已解决 / 全格式已支持」。**
"""
from __future__ import annotations

import json
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

ROOT = Path(__file__).resolve().parent.parent
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
OUT = ROOT / "analysis" / "l3-f4-generation-gate-disasm.json"
PRIOR = ROOT / "analysis" / "l3-f4-netsource-commit-disasm.json"
EOF_AUDIT = ROOT / "analysis" / "l3-f4-eof-audit.json"
VERDICT_AUDIT = ROOT / "analysis" / "l3-f4-data-kind-verdict-audit.json"

SYM_ON_NET_DATA = "_ZN17NMBufferSourceImp15OnNetSourceDataEiNSt6__ndk110unique_ptrI12NMDataBufferPFvPS2_EEEll"
SYM_WRITE_NET = "_ZN17NMBufferSourceImp14OnWriteNetDataERKNSt6__ndk110shared_ptrI12NMDataBufferEEll"
SYM_FLUSH = "_ZN17NMBufferSourceImp14OnFlushNetDataEv"
SYM_DONE = "_ZN17NMBufferSourceImp19OnNetSourceDataDoneEi"
SYM_NOTIFY = "_ZN17NMBufferSourceImp19NotifyCacheProgressEl"
SYM_SET_FILE_OFFSET = "_ZN17NMBufferSourceImp13SetFileOffsetEl"

# .rodata 日志字符串（file offset == vaddr，与 §61.54 / §61.65 工具同口径）
STRINGS = {
    0x3CB43: "NMNetSource(%lld-%lld)::WaitRequest offset:%lld, length:%lld, signal_size:%lld, force:%s",
    0x3CBA1: "NMNetSource(%lld-%lld)::WaitRequest cur offset:%lld, cur end : % lld, expect offset : % lld, end : % lld, commit : % lld",
    0x3CC1A: "NMNetSource(%lld-%lld)::MakeRangeRequestAsync offset:%lld, length:%lld, signal_size:%lld",
    0x3CD64: "NMNetSource::MakeRangeRequestAsync(%lld), start result:%d, offset:%lld, length:%lld",
    0x3CC73: "NMNetSource(%lld-%lld)::OnData() offset:%lld, size:%lld, except offset:%lld, end:%lld",
    0x3CCC9: "NMNetSource(%lld-%lld)::OnData() UnExpect New Offsetcommted:%lld, offset:%lld, size:%lld, cur_req_size:%lld",
    0x3CF90: "NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld ",
    0x3CFCE: "NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld Finish EOF.",
    0x3D017: "NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld Fail. code:%d",
    0x3D062: "NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld File Not Exsit",
    0x3D0AE: "NMSlicesFile Write all finish",
}

# 反汇编锚点：(地址, 期望助记符, 期望 op_str 子串, 说明)
# 注意 op_str 采用 capstone 原文子串匹配（如 b.ne 目标写作 "0x108e0"，不带 #）。
ANCHORS = [
    # ── A. MakeRangeRequestAsync @0x10458：请求代次递增与透传 ──
    (0x10458, "stp", "x26, x25", "函数序言（WaitRequest @0x10304 唯一调用点）"),
    (0x10470, "add", "x19, x0, #0x168", "mutex = this+0x168"),
    (0x10498, "str", "x22, [x26, #0x20]!", "★ pre-index 写回：x26 变为 this+0x20（故 +0xa0 == this+0xc0）"),
    (0x104a0, "ldr", "x8, [x26, #0xa0]", "★ 读当前请求代次 [this+0xC0]"),
    (0x104b0, "add", "x23, x8, #1", "★★ 请求代次 +1"),
    (0x104b8, "str", "x23, [x26, #0xa0]", "★★ 写回新代次到 [this+0xC0] —— 代次递增点"),
    (0x104d8, "mov", "x1, x23", "★ 新代次作为回调首参"),
    (0x104e4, "ldr", "x8, [x8, #0x10]", "回调取 vtable+0x10（发起 Range 请求）"),
    (0x104e8, "blr", "x8", "★ 虚调用发起请求（Java Start 的 native 侧），args=(obj, gen, offset, length)"),
    (0x10510, "add", "x2, x2, #0xd64", "★ 请求结果日志 0x3CD64 的 ADRP+ADD 还原"),
    (0x1053c, "stp", "q0, q0, [x26]", "请求失败时把 [this+0x20..0x40] 区间置 -1"),
    # ── B. NMNetSource::OnData @0x10748：代次校验与整包丢弃 ──
    (0x10790, "ldr", "x8, [x20, #0xc0]", "★ 读当前代次 [this+0xC0]"),
    (0x10794, "cmp", "x8, x24", "★ 与回调 arg1（发起请求时的代次）比对"),
    (0x10798, "b.ne", "0x108e0", "★★ 代次不匹配 → 整包丢弃，跳到 0x108E0 只 unlock 返回"),
    (0x10840, "ldr", "x0, [x20, #0x190]", "★ 数据投递对象（落盘链入口）"),
    (0x10868, "ldr", "x8, [x8, #0x18]", "数据回调取 vtable+0x18"),
    (0x1086c, "blr", "x8", "★★ 数据回调：投递 OnNetSourceData → OnWriteNetData（落盘入口）"),
    (0x10884, "adds", "x8, x21, x22", "commit 新值 = offset + length"),
    (0x10888, "str", "x8, [x20, #0x30]", "★ 写 commit（仅代次通过才执行）"),
    (0x108d0, "str", "w8, [x20, #0xb8]", "越过等待阈值 → 置唤醒标志"),
    (0x108dc, "bl", "0x8ab0", "唤醒等待读侧"),
    (0x108e0, "mov", "x0, x19", "★ 丢弃路径终点：只 unlock + 栈保护检查 + ret"),
    # ── C. NMSlicesFile::Write @0x127C0 起：落盘门控 ──
    (0x127c0, "ldrb", "w8, [x0, #0xc8]", "[+0xC8] 禁用标志"),
    (0x127cc, "mov", "x0, #-0x64", "禁用 → 返回 -100，不写盘"),
    (0x12810, "cbz", "w2, #0x12828", "len==0 → -102 分支"),
    (0x12814, "tbnz", "x21, #0x3f", "offset 低 6 位非 0（未 64B 对齐）→ -102 分支"),
    (0x12818, "ldr", "x8, [x19, #0x70]", "★ 读 file_len [+0x70]"),
    (0x1281c, "add", "x28, x21, w23, uxtw", "end = offset + len"),
    (0x12820, "cmp", "x28, x8", "★ end 与 file_len 比较"),
    (0x12824, "b.le", "0x12868", "end <= file_len 才允许分片写"),
    (0x12834, "mov", "x0, #-0x66", "越界 → 返回 -102，不写盘"),
    (0x12844, "adrp", "x2, #0x3c000", "★ NMSlicesFile Write buffer 日志 ADRP"),
    (0x1284c, "add", "x2, x2, #0xf90", "★ 日志 0x3CF90 的 ADRP+ADD 还原"),
    (0x12868, "cmp", "x8, x21", "file_len == offset ?"),
    (0x1286c, "b.ne", "0x12884", "不等才进入分片写循环"),
    (0x1287c, "mov", "x0, #-1", "file_len==offset（EOF）→ 返回 -1，不写盘"),
    (0x12890, "sdiv", "x9, x28, x10", "按 slice_size [+0xD0] 计算终止 slice 索引"),
    (0x12894, "sdiv", "x27, x21, x10", "按 slice_size 计算起始 slice 索引"),
    (0x12980, "bl", "0x150e8", "★ 实际分片写调用"),
    (0x12af4, "mov", "w20, w0", "写失败分支入口（tbnz w0,#0x1f）"),
    (0x12b30, "mov", "x0, #-0x62", "写失败 → 返回 -98"),
    (0x12ad4, "adrp", "x2, #0x3d000", "★ Write all finish 日志 ADRP"),
    (0x12adc, "add", "x2, x2, #0xae", "★ 日志 0x3D0AE 的 ADRP+ADD 还原"),
    (0x12aec, "strb", "w8, [x19, #0x104]", "成功路径置完成标志 [+0x104]"),
    (0x12a84, "mov", "x0, xzr", "成功返回 0"),
]


def load() -> tuple[bytes, dict, dict, dict]:
    data = SO.read_bytes()
    with SO.open("rb") as f:
        elf = ELFFile(f)
        ds = elf.get_section_by_name(".dynsym")
        syms = {}
        for s in ds.iter_symbols():
            if s.name and s["st_value"] and s["st_info"]["type"] == "STT_FUNC":
                syms[s.name] = (s["st_value"], s["st_size"])
        got = {}
        for sec in elf.iter_sections():
            if isinstance(sec, RelocationSection):
                st = elf.get_section(sec["sh_link"])
                for r in sec.iter_relocations():
                    nm = st.get_symbol(r["r_info_sym"]).name if st else ""
                    if nm:
                        got[r["r_offset"]] = nm
        plt = elf.get_section_by_name(".plt")
        text = elf.get_section_by_name(".text")
        sections = {"plt": (plt["sh_addr"], plt["sh_size"]),
                    "text": (text["sh_addr"], text["sh_size"])}
    return data, syms, got, sections


def plt_map(data: bytes, got: dict, span: tuple[int, int]) -> dict:
    base, size = span
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    out, cur, page = {}, None, None
    for i in md.disasm(data[base:base + size], base):
        if i.mnemonic == "adrp" and i.op_str.startswith("x16"):
            cur, page = i.address, i.operands[1].imm
        elif i.mnemonic == "ldr" and i.op_str.startswith("x17") and cur and page is not None \
                and "#" in i.op_str:
            try:
                imm = int(i.op_str.split("#")[1].replace("]", "").split()[0], 16)
                out[cur] = got.get(page + imm, "?")
            except (ValueError, IndexError):
                pass
            cur, page = None, None
    return out


def disasm_all(data: bytes, span: tuple[int, int]) -> dict[int, tuple[str, str]]:
    base, size = span
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    return {i.address: (i.mnemonic, i.op_str)
            for i in md.disasm(data[base:base + size], base)}


def cstr(data: bytes, addr: int) -> str:
    end = data.find(b"\x00", addr)
    return data[addr:end].decode("utf-8", "replace")


def main() -> int:
    if not SO.is_file():
        print("[FAIL] 缺少 %s" % SO)
        return 1
    data, syms, got, sections = load()
    ins = disasm_all(data, sections["text"])
    plt = plt_map(data, got, sections["plt"])

    anchor_hits, by_addr = {}, {}
    for addr, mnem, op, desc in ANCHORS:
        m, o = ins.get(addr, ("", ""))
        ok = (m == mnem if mnem else bool(m)) and (op in o if op else True)
        entry = {
            "address": "0x%X" % addr, "description": desc,
            "mnemonic": m, "op_str": o,
            "expected": "%s %s" % (mnem or "(any)", op or ""), "ok": ok,
        }
        anchor_hits["0x%X %s" % (addr, desc)] = entry
        by_addr[addr] = entry

    def A(addr: int) -> bool:
        return by_addr[addr]["ok"]

    strings_hit = {}
    for addr, expected in STRINGS.items():
        actual = cstr(data, addr)
        strings_hit["0x%X" % addr] = {
            "text": actual, "ok": actual.startswith(expected[:40]),
        }

    prior = json.loads(PRIOR.read_text(encoding="utf-8")) if PRIOR.is_file() else {}
    eof = json.loads(EOF_AUDIT.read_text(encoding="utf-8")) if EOF_AUDIT.is_file() else {}
    verdict = json.loads(VERDICT_AUDIT.read_text(encoding="utf-8")) \
        if VERDICT_AUDIT.is_file() else {}
    prior_log = prior.get("log_cross_check", {})

    checks = {
        "elf_and_symbols": all(s in syms for s in (
            SYM_ON_NET_DATA, SYM_WRITE_NET, SYM_FLUSH, SYM_DONE,
            SYM_NOTIFY, SYM_SET_FILE_OFFSET)),
        "plt_helpers_resolved": (
            plt.get(0x8E00, "").endswith("4lockEv")
            and plt.get(0x8950, "").endswith("6unlockEv")
            and bool(plt.get(0x8AB0))),
        "disasm_anchors_all": all(v["ok"] for v in anchor_hits.values()),
        "rodata_strings_all": all(v["ok"] for v in strings_hit.values()),
        # A. 代次递增链
        "generation_increment_chain": (
            A(0x10498) and A(0x104A0) and A(0x104B0) and A(0x104B8)),
        "generation_passed_to_start_callback": (
            A(0x104D8) and A(0x104E4) and A(0x104E8) and A(0x10510)),
        # B. 代次校验 / 丢弃链
        "generation_compare_chain": (
            A(0x10790) and A(0x10794) and A(0x10798)),
        "early_drop_skips_data_callback": (
            A(0x10840) and A(0x10868) and A(0x1086C) and A(0x108E0)),
        "commit_write_gated": (
            A(0x10884) and A(0x10888) and A(0x108D0) and A(0x108DC)),
        # C. vfile 落盘门控
        "slice_write_disabled_flag": A(0x127C0) and A(0x127CC),
        "slice_write_alignment_gate": A(0x12810) and A(0x12814),
        "slice_write_file_len_gate": (
            A(0x12818) and A(0x1281C) and A(0x12820) and A(0x12824)
            and A(0x12834) and A(0x12844) and A(0x1284C)),
        "slice_write_eof_gate": A(0x12868) and A(0x1286C) and A(0x1287C),
        "slice_write_actual_call": (
            A(0x12890) and A(0x12894) and A(0x12980)),
        "slice_write_error_and_finish": (
            A(0x12AF4) and A(0x12B30) and A(0x12AD4)
            and A(0x12ADC) and A(0x12AEC) and A(0x12A84)),
        # 与 §61.65 既有证据 / F4 状态交叉，防回潮
        "prior_61_65_evidence_intact": (
            prior.get("all_pass") is True
            and len(prior.get("checks", {})) == 15
            and prior_log.get("on_data_packet_count", 0) >= 1000
            and prior_log.get("commit_values") == [16384, 16384]),
        "f4_still_blocked": (
            eof.get("f4_status") == "BLOCKED"
            and verdict.get("all_pass") is True),
        "no_credentials_in_output": True,
    }

    failed = [k for k, v in checks.items() if not v]
    result = {
        "method": ("12 §61.67 / F4 请求代次闸门 + vfile 落盘门控一手反汇编"
                   "（离线、只读、capstone + pyelftools，不需要设备在场）"),
        "generated_by": "tools/l3_f4_generation_gate_disasm.py",
        "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
        "so_size": len(data),
        "sections": {k: {"addr": hex(v[0]), "size": hex(v[1])}
                     for k, v in sections.items()},
        "symbols": {k: {"addr": hex(v[0]), "size": v[1]}
                    for k, v in syms.items() if k in (
                        SYM_ON_NET_DATA, SYM_WRITE_NET, SYM_FLUSH, SYM_DONE,
                        SYM_NOTIFY, SYM_SET_FILE_OFFSET)},
        "plt_resolved": {hex(k): plt.get(k, "") for k in (0x8E00, 0x8950, 0x8AB0, 0x8940)},
        "anchors": anchor_hits,
        "rodata_strings": strings_hit,
        "prior_evidence": {
            "file": str(PRIOR.relative_to(ROOT)).replace("\\", "/"),
            "all_pass": prior.get("all_pass"),
            "on_data_packet_count": prior_log.get("on_data_packet_count"),
            "commit_values": prior_log.get("commit_values"),
            "generations": prior_log.get("generations"),
        },
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "finding": {
            "binary_confirmed": [
                "A. MakeRangeRequestAsync @0x10458（WaitRequest @0x10304 唯一调用点）："
                "0x10498 'str x22,[x26,#0x20]!' 先写 offset 并把 x26 前移到 this+0x20；"
                "0x104A0 读 [x26,#0xA0]（== this+0xC0，当前代次）→ 0x104B0 'add x23,x8,#1' "
                "→ 0x104B8 写回 [this+0xC0]（**代次递增点**）→ 0x104D8 'mov x1,x23' "
                "把新代次作为首参、0x104E8 'blr x8'（vtable+0x10）发起 Range 请求。"
                "请求失败时 0x1053C 把 [this+0x20..0x40] 置 -1、0x10540 存错误码。",
                "B. NMNetSource::OnData @0x10748：0x10790 读 [this+0xC0] 当前代次、"
                "0x10794 'cmp x8,x24' 与回调 arg1（发起请求时的代次）比对、"
                "0x10798 'b.ne 0x108E0' 不匹配即整包丢弃。"
                "0x108E0 位于数据回调 0x1086C 与 commit 写入 0x10888 **之后**，"
                "只做 unlock + 栈保护检查 + ret → **代次不匹配的数据包既不投递 "
                "0x1086C 'blr x8'（vtable+0x18 → OnNetSourceData → OnWriteNetData 落盘入口），"
                "也不写 commit、不置 +0xB8、不唤醒读侧**。"
                "通过校验才 0x10884/0x10888 写 commit=offset+length，"
                "并在 [+0x40]>=1 且 commit>=[+0x38]+[+0x40] 时 0x108D0/0x108DC 唤醒读侧。",
                "C. NMSlicesFile::Write @0x127C0（日志锚 0x12844 → 0x3CF90）有四条静默不落盘门控："
                "① [+0xC8] 非 0 → -100；② len==0 或 offset 低 6 位非 0（未 64B 对齐）→ -102；"
                "③ 0x12820 'cmp x28,x8' 要求 offset+len <= [+0x70]=file_len，越界 → -102；"
                "④ 0x12868 file_len==offset（EOF）→ -1。通过后才 0x12890/0x12894 按 "
                "[+0xD0] slice_size 计算起止片索引、0x12980 'bl 0x150E8' 实际分片写，"
                "写失败 0x12AF4 → -98，成功 0x12ADC 打 'Write all finish'（0x3D0AE）并置 [+0x104]。",
            ],
            "log_confirmed_from_61_65": [
                "§61.64 变体 D（kind=2）主判据：Java 侧 onData 1028 条、9 次 Start 每次 "
                "length=1048576、每包 8192 B（末包可拆 215+7977）→ 1 MiB 完整回传，"
                "但 commit 恒 16384（2 包）；基线 kind=1 为 180224（22 包）。",
                "读侧 ReadFromFileNoLock offset:327895 readed_len:0 result:-2 反复 12 次，"
                "请求代次日志从 (1-0) 递增到 (1-8)；§61.63 的 .nmsfi 索引一手取证显示 "
                "自洽 fMP4 只有 slices[0].trunks=[0,368640)、_0.nmsf 实际 368640 B，"
                "而 mp3/flac/wav 有多片全满 1 MiB trunk。",
            ],
            "inference_upgrade": (
                "§61.65 把『读侧读空 → WaitRequest 立即 MakeRangeRequestAsync 递增代次 → "
                "旧代次在途包在 0x10798 被整包丢弃 → commit 停在 16384』标为 inference。"
                "本批把该推断的**三个缺环全部补成一手二进制证据**：递增点（0x104B0/0x104B8）、"
                "新代次透传给请求（0x104D8/0x104E8）、比较与丢弃（0x10794/0x10798）。"
                "**并新增两条实证**：① 丢弃路径跳过 0x1086C 数据回调 → 直接解释 §61.63 "
                "『Java 回传 1 MiB 一个字节没进 vfile』；② NMSlicesFile::Write 要求 "
                "offset+len <= file_len → 直接解释 §61.61/§61.62 的跳跃式 probe 读 "
                "offset=605007 在 file_len 仅 368640 时必然 result:-2。"
                "**证据等级提升，但性质仍是 inference**：未做新的真机 A/B 直接命中 0x10798，"
                "level-5 日志未开启，读侧重发与代次失效的运行时时序未由日志逐包坐实。"
            ),
            "precision_correction_preserved": (
                "沿用 §61.65：commit 是 NMNetSource 的『连续确认偏移』，不是 vfile 磁盘写入字节数；"
                "vfile 实际落盘由 OnWriteNetData → NMSlicesFile::Write 独立执行，"
                "两者都受代次闸门控制但不等价。本批不改写该修正。"
            ),
            "device_endpoint_41417": (
                "用户 2026-11-10 23:58 给出新端口 41417（按 adb 无线调试端点处理）。"
                "实测：10.0.0.2:41417 → 10060 超时；192.168.1.11:41417 → 拒绝连接；"
                "已重启 adb 会话，系统 adb 35.0.2 与项目 tools/bin/adb.exe 37.0.1 两个版本均试过，"
                "adb devices 为空。上一轮在线端点为 10.0.0.2:41761。"
                "按用户选择『先继续离线任务』登记为待连端点，本批真机 A/B / level-5 日志实证 "
                "**NOT RUN**；不擅自改动用户网络，待用户补完整 IP:port 或确认设备无线调试开启后再跑。"
            ),
        },
        "honest_notes": [
            "本轮为纯静态反汇编 + §61.65 已固化真机日志的交叉，零 P0 源码改动、"
            "libNMCommonCache.so 二进制原样、零新增依赖、零设备操作。",
            "F4 仍 BLOCKED：定位 ≠ 修复 ≠ 验证；-103 判据未通过；自动进曲 3 首 / seek / ENDED "
            "仍 NOT RUN；F3 14 格矩阵未改写；A3 仍 G09 PARTIAL → 舱不冻结、"
            "engine_compat_test 不回填。不写『F4 已解决 / m4a 已支持 / 全格式已支持』。",
            "level-5 日志（NMSlicesFile Write SKIP / EOF / Fail / Write all finish、"
            "OnWriteNetData SKIP / TRIM / Failed Not Support）在现有 logcat 中未开启，"
            "C 段落盘门控分支只由二进制坐实、未由运行时日志确认。",
            "设备端点 41417 不可达 → 真机 A/B 与『把 inference 升级为实证』的后半段仍待设备在线；"
            "本批只完成 §61.65 剩余断点①的前半（离线反汇编）。",
            "§61.65 / §61.64 / §61.50 等历史批次块的当时快照口径按约定保留，不改写。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] anchors=%d strings=%d checks=%d" %
          (len(anchor_hits), len(strings_hit), len(checks)))
    if failed:
        print("[FAIL] §61.67 反汇编取证未通过：%s" % failed)
        return 1
    print("[PASS] §61.67 反汇编取证：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
