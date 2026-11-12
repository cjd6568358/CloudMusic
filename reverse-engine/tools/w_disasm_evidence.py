#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
W 组一手反汇编取证 —— 竞品播放内核 libNMCommonCache.so 的 1:1 指令级补译。

与 §61.65~§61.77 历史工具同口径（capstone ARM64 全 .text 反汇编），但**不依赖
pyelftools**：ELF64 节头用标准库 struct 解析，便于任何环境复跑。只读 so、只写
本目录下的 spec/w-disasm-evidence.json，不连 adb、不改生产代码、不写「F4 已解决」。

窗口（全部 addr 为 .text 虚拟地址，本 so 的 sh_addr == sh_offset）：
  W1 0x12884~0x128D0  NMSlicesFile::Write 分片索引（S-16 定案）
  W2 0x128D0~0x12940  NMSlicesFile::Write 分片循环与 232B 对象步长
  W3 0xC3F4 ~0xC4F8   ReadFromFileNoLock 门控（B-20 定案）
  W4 0x12CC0~0x12CF8  Read 分片窗口 + 0x12DC8 的 -2（B-30 定案）
  W5 0x12D00~0x12DF0  Read 分片读循环 / -98
  W6 0x37E20~0x37E34  日志门控本体（g_level > level → 抑制）
  W7 0x12808~0x12828  Write 入口四门控
  W8 0x12BD0~0x12C6C  Read 侧 EOF / 长度门控

产出 spec/w-disasm-evidence.json；常量期望值由 gen_w_manifest.py 抽取。
用法：python tools/w_disasm_evidence.py
"""
from __future__ import annotations

import hashlib
import json
import struct
import sys
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

RE = Path(__file__).resolve().parent.parent
ROOT = RE.parent
SO_REL = "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
SO = ROOT / SO_REL
OUT = RE / "spec" / "w-disasm-evidence.json"

WINDOWS = [
    ("W1", "Write 分片索引（S-16 定案）", 0x12884, 0x128D0),
    ("W2", "Write 分片循环 / 232B 步长", 0x128D0, 0x12940),
    ("W3", "ReadFromFileNoLock 门控（B-20 定案）", 0xC3F4, 0xC4F8),
    ("W4", "Read 分片窗口 / -2 引用（B-30 定案）", 0x12CC0, 0x12CF8),
    ("W5", "Read 分片读循环 / -98", 0x12D00, 0x12DF0),
    ("W6", "日志门控本体 0x37E20", 0x37E20, 0x37E34),
    ("W7", "Write 入口四门控", 0x12808, 0x12828),
    ("W8", "Read 侧 EOF / 长度门控", 0x12BD0, 0x12C6C),
]

# (编号, 地址, 期望助记符, 期望 op_str 子串, 判读)
ANCHORS = [
    ("W-01", 0x37E2C, "cset", "w0, gt", "g_level > level → 抑制（bit0=1）"),
    ("W-02", 0x1281C, "add", "x28, x21, w23, uxtw", "end = offset + len"),
    ("W-03", 0x12890, "sdiv", "x9, x28, x10", "q_end = end / slice_size"),
    ("W-03", 0x12894, "sdiv", "x27, x21, x10", "q_start = offset / slice_size"),
    ("W-04", 0x12898, "msub", "x10, x9, x10, x28", "rem = end - q_end*slice_size"),
    ("W-04", 0x128A0, "cinc", "x11, x9, ne", "upper = q_end + (rem != 0)"),
    ("W-04", 0x128A4, "cset", "w10, ne", "remFlag = rem != 0"),
    ("W-05", 0x128AC, "b.ge", "0x12a54", "q_start >= upper → 零分片直接完成"),
    ("W-06", 0x128B0, "sub", "x8, x11, #1", "last = upper - 1"),
    ("W-06", 0x128D8, "sub", "x8, x8, x27", "count = upper - q_start"),
    ("W-07", 0x128C4, "mov", "w9, #0xe8", "分片对象步长 232 字节"),
    ("W-07", 0x128DC, "mul", "x23, x27, x9", "首片对象偏移 = q_start * 232"),
    ("W-08", 0xC40C, "cbz", "w8, #0xc4bc", "[+0xB8]==0 → 直接 -6"),
    ("W-09", 0xC474, "tbz", "w23, #0x1f", "result >= 0 → 不清除标志"),
    ("W-09", 0xC478, "cmn", "w23, #2", "result == -2 → 不清除标志"),
    ("W-10", 0xC480, "mov", "w0, #5", "W(5) 级日志门控"),
    ("W-10", 0xC4B8, "strb", "wzr, [x19, #0xb8]", "★ 清 [+0xB8]"),
    ("W-10", 0xC4BC, "mov", "w0, #-6", "★ 返回 -6"),
    ("W-11", 0xC4C8, "cbz", "w8, #0xc4e0", "readed==0 → -3"),
    ("W-11", 0xC4D8, "csetm", "w0, ge", "readed+offset >= [+0x80] → 0/-1"),
    ("W-12", 0x12CE0, "cinc", "x11, x8, ne", "读侧窗口同样向上取整"),
    ("W-12", 0x12CE4, "cmp", "x12, #1", "★ 请求长度与 1 比较"),
    ("W-12", 0x12CEC, "b.lt", "0x12dc8", "★ len < 1 → 0x12DC8 返回 -2"),
    ("W-12", 0x12DC8, "mov", "x0, #-2", "★ B-30 的 -2 立即数"),
    ("W-13", 0x12DAC, "tbnz", "w0, #0x1f", "分片读失败 → -98"),
    ("W-13", 0x12DE0, "mov", "x0, #-0x62", "★ 返回 -98"),
    ("W-14", 0x12810, "cbz", "w2", "len==0 → -102 分支"),
    ("W-14", 0x12814, "tbnz", "x21, #0x3f", "offset 低 6 位非 0 → -102 分支"),
]

# 从一手指令判读出的定案常量（由 gen_w_manifest.py 写入 Kotlin catalog + manifest）
FINDINGS = {
    "SLICE_OBJECT_STRIDE": 232,          # 0x128C4 mov w9,#0xe8
    "LOG_GATE_FUNC_ADDR": 0x37E20,       # 0x37E20 getter
    "LOG_GATE_SET_ADDR": 0x37E34,        # 0x37E34 setter
    "WRITE_INDEX_SDIV_ADDR": 0x12890,    # q_end
    "WRITE_CINC_ADDR": 0x128A0,          # upper
    "WRITE_EMPTY_BRANCH_ADDR": 0x128AC,  # b.ge 0x12A54
    "READ_CLEAR_FLAG_ADDR": 0xC4B8,      # strb wzr,[+0xB8]
    "READ_MINUS2_ADDR": 0x12DC8,         # mov x0,#-2
    "READ_MINUS2_BRANCH_ADDR": 0x12CEC,  # b.lt 0x12DC8
    "CODE_GATE_CLEAR_RETURN": -6,        # 0xC4BC mov w0,#-6
    "CODE_ZERO_READED": -3,              # 0xC4E0 mov w0,#-3
    "CODE_ZERO_LENGTH": -2,              # 0x12DC8 mov x0,#-2
    "CODE_SLICE_READ_FAILED": -98,       # 0x12DE0 mov x0,#-0x62
    "CODE_EOF": -1,                      # 0x1287C / 0x12C64
    "CODE_INVALID_RANGE": -102,          # 0x12860 mov x0,#-0x66
    "CODE_EOF_DIAG": -99,                # 0x12C50 mov x0,#-0x63
    "LEVEL_I_READ": 3,                   # 0xC43C mov w0,#3
    "LEVEL_W_READ": 5,                   # 0xC480 mov w0,#5
    "LEVEL_W_EOF": 5,                    # 0x12C44 mov w0,#5
    "LEVEL_I_EOF": 4,                    # 0x12C58 mov w0,#4
    "ALIGN_MASK": 63,                    # 0x12814 tbnz x21,#0x3f (低 6 位)
}


def elf_sections(data: bytes) -> list[dict]:
    """标准库解析 ELF64 节头（不依赖 pyelftools）。"""
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    out = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        nm, _typ, _fl, addr, off, size, _link, _info, _al, _ent = struct.unpack_from(
            "<IIQQQQIIQQ", data, o)
        out.append({"name": nm, "addr": addr, "offset": off, "size": size})
    shstr = out[e_shstrndx]
    for s in out:
        end = data.find(b"\x00", shstr["offset"] + s["name"])
        s["sname"] = data[shstr["offset"] + s["name"]:end].decode("utf-8", "replace")
    return out


def disasm(data: bytes, sec: dict) -> dict[int, tuple[str, str]]:
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    code = data[sec["offset"]:sec["offset"] + sec["size"]]
    return {i.address: (i.mnemonic, i.op_str)
            for i in md.disasm(code, sec["addr"])}


def hx(v: int) -> str:
    return "0x%x" % v


def main() -> int:
    if not SO.is_file():
        print("[FAIL] 缺少竞品 so: %s" % SO_REL)
        return 1
    raw = SO.read_bytes()
    sections = elf_sections(raw)
    text = next(s for s in sections if s["sname"] == ".text")
    ins = disasm(raw, text)

    windows = []
    for wid, label, lo, hi in WINDOWS:
        rows = [{"addr": hx(a), "mnemonic": ins[a][0], "op_str": ins[a][1]}
                for a in range(lo, hi, 4) if a in ins]
        windows.append({"id": wid, "label": label, "start": hx(lo), "end": hx(hi),
                        "instructionCount": len(rows), "instructions": rows})

    anchors, checks = [], {}
    for sid, addr, mnemonic, fragment, note in ANCHORS:
        hit = ins.get(addr)
        ok = bool(hit) and hit[0] == mnemonic and fragment in hit[1]
        anchors.append({"specId": sid, "addr": hx(addr), "mnemonic": mnemonic,
                        "fragment": fragment, "note": note, "actual": "%s %s" % hit if hit else "",
                        "ok": ok})
        checks["%s@%s" % (sid, hx(addr))] = ok

    all_pass = all(checks.values()) and all(w["instructionCount"] > 0 for w in windows)
    report = {
        "tool": "reverse-engine/tools/w_disasm_evidence.py",
        "method": "ELF64 section headers (stdlib struct) + capstone 5 ARM64 full .text disasm",
        "mode": "offline, read-only, no adb",
        "so": {"path": SO_REL, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)},
        "text": {"addr": hx(text["addr"]), "offset": hx(text["offset"]),
                 "size": hx(text["size"]), "addressEqualsOffset": text["addr"] == text["offset"]},
        "windows": windows,
        "anchors": anchors,
        "findings": FINDINGS,
        "checks": checks,
        "checksTotal": len(checks),
        "all_pass": all_pass,
        "conclusion": (
            "S-16 定案：终止片索引不是 end/slice_size 直除，而是 0x12898 msub + "
            "0x128A0 cinc + 0x128A4 cset 得到的 upper = end/size + (end%size!=0)，"
            "循环为半开区间 [offset/size, upper)，分片对象步长 232B。"
            "B-20 定案：[+0xB8]!=0 且 result<0 且 result!=-2 时清 [+0xB8] 并返回 -6；"
            "[+0xB8]==0 直接 -6；result>=0 或 ==-2 走边界判定。"
            "B-30 定案：0x12DC8 的 -2 由 0x12CEC b.lt 在请求长度 <1 时触发，位于读侧窗口，"
            "不是写侧越界门控（修正 §61.77 报告级口径）。"
            "日志门控本体 0x37E20 = (g_level > level) ? 1 : 0，bit0=1 表示抑制。"
        ),
        "honestNotes": [
            "只登记可逐指令坐实的控制流 / 门控 / 错误码；0x12C44/0x12C58 的 EOF 日志分支返回码语义仍按报告级保留，不推断。",
            "不改变 F4 仍 BLOCKED 口径；本目录不实现 B2 / M2 / M3。",
        ],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print("[W-DISASM] windows=%d anchors=%d checks=%d all_pass=%s" %
          (len(windows), len(anchors), len(checks), all_pass))
    print("[W-DISASM] so sha256=%s" % report["so"]["sha256"][:16])
    print("[W-DISASM] %s" % OUT)
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
