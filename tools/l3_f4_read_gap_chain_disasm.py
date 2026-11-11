#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.77 / F4 · 读侧空白链补齐：`result:-2` 产生点与挂起异步读（纯离线）。

承接 §61.76（把 F4 修复点前移到 `[+0x198]` 挂起异步读写入点与
`result:-2` 传递链）。本工具用 capstone 5.0.7 + pyelftools 反汇编
libNMCommonCache.so，把 §61.65/§61.67（代次错配 → commit 冻结）与
§61.76（-103 返回播放侧）之间的空白补齐：

* `ReadFromFileNoLock ... result:%d` 格式串 `@0x3bee8`，**唯一引用**
  `0xc454/0xc45c`，`0xc434 blr [vtable+0x28]` 的返回值 w23 即 result。
* `0xc6f8 bl 0x10198`（§61.65 的 `WaitRequest`）返回值分支：
  w0==1 → `0xc728 mov w0,#-2`；w0==2 → -0x6a；其它 → `0xc730 mov w0,#-0x67`；
  w0==0 → `0xc718` 走 `0x10b50` 成功回读。**`result:-2` 由此产生**。
* `0xa9bc mov w25,#-2` 与 `0xa9c4 mov w25,#-0x67` 是**两条硬编码分支**，
  共用 `0xa9e4 str w25,[+0x1a0]`；写入仅在 `0xa9d0 ldr x8,[+0x198]` +
  `0xa9d4 cbz` 判定**挂起异步读非零**时发生。
* `[+0x198]` 登记/清零共 6 处：`0x9f44`（构造异步读任务）、
  `0x10b20`（WaitRequest 附近）、`0xa018`（Finish Async 回调清零）、
  `0x100c8` / `0x106c0` / `0x1095c`。
* `0x12dc8 mov x0,#-2` 是 §61.67 已定位的 `NMSlicesFile::Write`
  `offset+len <= file_len` 越界门控的另一处 -2 返回。

完整静态链：代次错配(`0x10798`) → 整包丢弃 → `commit` 冻结(`0x10888`)
→ `WaitRequest` 读空 → `result:-2`(`0xc728`) → 登记挂起异步读(`[+0x198]`)
→ 完成回调写 -2/-103 到 `[+0x1a0]` → `Finish Async` 返回 -103
→ 播放侧 paused / AudioTrack 释放 → 无音频输出。

**性质是 inference（静态二进制证据）**，未做动态插桩，不改写真机结论。
零 P0 源码改动、不连 adb、真机取证已于 §61.75 完结。
产出 analysis/l3-f4-read-gap-chain.{json,report.md}。F4 仍 BLOCKED。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
OUT = A / "l3-f4-read-gap-chain.json"
REPORT = A / "l3-f4-read-gap-chain-report.md"
M103 = A / "l3-f4-minus103-chain.json"
FINAL = A / "l3-f4-acceptance-final.json"

FMT_READ = 0x3BEE8
FMT_READ_TEXT = ("NMBufferSourceImp(%lld)::ReadFromFileNoLock  Local File Read "
                 "expect len:%u, offset:%lld, readed_len:%u, result:%d")

# 地址 -> (期望助记符, 操作数中必须包含的串)
ANCHORS = {
    "fmt_ref_adrp": (0xC454, "adrp", "0x3b000"),
    "fmt_ref_add": (0xC45C, "add", "0xee8"),
    "read_result_call": (0xC434, "blr", "x8"),
    "read_log_call": (0xC470, "bl", "0x3138c"),
    "minus2_branch": (0xC478, "cmn", "#2"),
    "wait_request_call": (0xC6F8, "bl", "0x10198"),
    "wait_ret1_cmp": (0xC700, "cmp", "#1"),
    "wait_ret1_beq": (0xC704, "b.eq", "0xc728"),
    "result_minus2": (0xC728, "mov", "#-2"),
    "result_minus106": (0xC710, "mov", "#-0x6a"),
    "result_minus103": (0xC730, "mov", "#-0x67"),
    "read_ret": (0xC73C, "ret", ""),
    "hardcoded_minus2": (0xA9BC, "mov", "#-2"),
    "hardcoded_minus103": (0xA9C4, "mov", "#-0x67"),
    "pending_read_load": (0xA9D0, "ldr", "0x198"),
    "pending_read_cbz": (0xA9D4, "cbz", "0xa9f8"),
    "write_1a0": (0xA9E4, "str", "0x1a0"),
    "pending_register": (0x9F44, "str", "0x198"),
    "pending_clear_finish": (0xA018, "str", "0x198"),
    "pending_wait_near": (0x10B20, "str", "0x198"),
    "slices_write_minus2": (0x12DC8, "mov", "#-2"),
    "generation_cmp": (0x10794, "cmp", ""),
    "generation_bne": (0x10798, "b.ne", "0x108e0"),
    "commit_store": (0x10888, "str", ""),
}

FORBIDDEN = ("F4 已" + "解决", "m4a 已" + "支持", "全格式已" + "支持",
             "level-5 已" + "开启", "播放已" + "成功", "推断已" + "升级为实证")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def build_report(d: dict) -> str:
    lines = [
        "# F4 · 读侧空白链补齐：`result:-2` 与挂起异步读（§61.77，2026-11-11）",
        "",
        "> **性质**：纯离线静态二进制证据（capstone 5.0.7 + pyelftools）。",
        "> 不连 adb、零 P0 源码改动、真机取证已于 §61.75 完结。",
        "> 结论是 **inference**。**F4 仍 BLOCKED**。",
        "",
        "## 1. 承接",
        "",
        "`§61.76` 把 F4 最靠前的修复点前移到 `[+0x198]` 挂起异步读的",
        "写入点与 `result:-2` 传递链。本批把这段空白补齐，",
        "把 `§61.65/§61.67`（代次错配 → commit 冻结）与 §61.76",
        "（-103 返回播放侧）串成完整静态链。",
        "",
        "## 2. `result:-2` 的精确产生点",
        "",
        "- so sha256 `%s…`；`.rodata @0x3bee8` = `%s`..." % (
            d["so_sha256"][:12], FMT_READ_TEXT[:60]),
        "  该格式串**唯一引用** `0xc454/0xc45c`；`0xc434 blr [vtable+0x28]`",
        "  的返回值 `w23` 就是日志里的 `result`。",
        "- **`0xc6f8 bl 0x10198`** 调用 `§61.65` 已定位的 `WaitRequest`，",
        "  返回值分支：`w0==0` → `0xc718` 成功回读；`w0==1` →",
        "  **`0xc728 mov w0,#-2`**；`w0==2` → `0xc710 mov w0,#-0x6a`；",
        "  其它 → `0xc730 mov w0,#-0x67`(-103)；`0xc73c ret`。",
        "- `0xc478 cmn w23,#2; b.eq 0xc4c4` 是打印后对 -2 的判定分支。",
        "- 另一处 -2：`0x12dc8 mov x0,#-2`，即 §61.67 已定位的",
        "  `NMSlicesFile::Write` 的 `offset+len <= file_len` 越界门控。",
        "",
        "## 3. `-2` / `-103` 双分支与挂起异步读",
        "",
        "- `0xa9bc mov w25,#-2` 与 `0xa9c4 mov w25,#-0x67` 是**两条硬编码",
        "  分支**，共用 `0xa9e4 str w25,[+0x1a0]`（§61.76 只登记了 -103）。",
        "- 写入前置：`0xa9d0 ldr x8,[x19,#0x198]` + `0xa9d4 cbz`，",
        "  仅**挂起异步读 `[+0x198]` 非零**才写 `[+0x1a0]`。",
        "- `[+0x198]` 登记 / 清零共 **6 处**：`0x9f44`（构造异步读任务）、",
        "  `0x10b20`（`WaitRequest` 附近）、`0xa018`（`Finish Async` 回调",
        "  清零）、`0x100c8` / `0x106c0` / `0x1095c`。",
        "",
        "## 4. 完整静态链（inference）",
        "",
        "代次错配 `0x10794 cmp` / `0x10798 b.ne 0x108e0`（整包丢弃）",
        "→ `commit` 冻结于 `0x10888` → `WaitRequest` 读空返回 1",
        "→ `0xc728` 产生 `result:-2` → 登记挂起异步读 `[+0x198]`",
        "→ 完成回调按分支写 -2 / -103 到 `[+0x1a0]`",
        "→ `Finish Async` 把 -103 原样返回播放侧（§61.76）",
        "→ paused 置位 / AudioTrack 释放 → §61.74 门控 1/2/3 静默早退",
        "→ 无 `Write start play`、无音频输出。",
        "",
        "**不能断定本次运行命中哪条分支**，静态链不等于已验证因果；",
        "`§61.75` 真机终局结论不变。",
        "",
        "## 5. 修复方向（比日志判据更靠前）",
        "",
        "- 三处可干预锚点：**`0x10798` 代次比较早退**",
        "  （旧代次在途包被整包丢弃）、**`0xc728` WaitRequest 读空返回 -2**",
        "  （触发重发并递增代次，形成自激循环）、**`0x12dc8` 越界 -2**",
        "  （`offset+len > file_len` 时静默不写）。",
        "- 均是 native 二进制内的分支，与 §61.59「native 级缺陷无法",
        "  Java 侧修复」一致；**真要修复需用户拍板技术路线**",
        "  （改 native / 绕过取流 / 换源），本批不擅自改动。",
        "- 真机取证已完结，后续仅 F4 修复后一次回归验证需要设备。",
        "",
        "## 6. 实跑核验与边界",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(d["failed_checks"]) or "无"),
        "- 交叉证据：§61.76 `-103` 链 `%s`；§61.75 终局裁决 `%s`。" % (
            d["cross"]["minus103_chain"], d["cross"]["final_verdict"]),
        "- **F4 仍 BLOCKED**：主判据无一 PASS、播放未进入 PLAYING、",
        "  `-103` 仍在场；A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。",
        "- 零 P0 源码改动、未连 adb、未重编译 / 未重装、so 二进制原样；",
        "  §61.67~§61.76 历史正文与证据 JSON 一字未改。",
        "- 静态证据等级提升 ≠ 修复 ≠ 验证；不写任何提前收口结论。",
        "",
        "> AI生成",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    data = SO.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    e = ELFFile(open(str(SO), "rb"))
    t = next(s for s in e.iter_sections() if s.name == ".text")
    r = next(s for s in e.iter_sections() if s.name == ".rodata")
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    ins = list(md.disasm(t.data(), t["sh_addr"]))
    by = {x.address: x for x in ins}

    def rs(va, n=180):
        off = r["sh_offset"] + (va - r["sh_addr"])
        return data[off:off + n].split(b"\x00")[0].decode("utf-8", "replace")

    fmt = rs(FMT_READ)
    # 实际扫描 adrp + (add|ldr) 页内引用，确认格式串引用唯一（不硬编码结论）。
    fmt_refs = []
    for i, x in enumerate(ins):
        if x.mnemonic != "adrp" or len(x.operands) < 2:
            continue
        page, reg = x.operands[1].imm, x.operands[0].reg
        for y in ins[i + 1:i + 5]:
            tgt = None
            if (y.mnemonic == "add" and len(y.operands) >= 3
                    and y.operands[1].type == ARM64_OP_REG
                    and y.operands[1].reg == reg
                    and y.operands[2].type == ARM64_OP_IMM):
                tgt = page + y.operands[2].imm
            elif (y.mnemonic in ("ldr", "ldrb", "ldur")
                  and len(y.operands) >= 2
                  and y.operands[1].type == ARM64_OP_MEM
                  and y.operands[1].mem.base == reg):
                tgt = page + y.operands[1].mem.disp
            if tgt == FMT_READ:
                fmt_refs.append([hex(x.address), hex(y.address), hex(tgt)])
    pending = []
    minus2s = []
    for x in ins:
        for o in x.operands:
            if (o.type == ARM64_OP_MEM and o.mem.disp == 0x198
                    and x.mnemonic.startswith(("str", "stur"))
                    and "[sp" not in x.op_str):
                pending.append(hex(x.address))
                break
        for o in x.operands:
            if (o.type == ARM64_OP_IMM and o.imm == -2
                    and x.mnemonic == "mov"):
                minus2s.append(hex(x.address))
                break

    anchor_checks = {}
    for name, (addr, mn, fragment) in ANCHORS.items():
        x = by.get(addr)
        anchor_checks["anchor_" + name] = bool(
            x and x.mnemonic == mn and fragment in x.op_str)
    checks = dict(anchor_checks)
    m103 = jload(M103)
    final = jload(FINAL)
    checks.update({
        "read_fmt_string_exact": fmt == FMT_READ_TEXT,
        "read_fmt_unique_reference": (
            len(fmt_refs) == 1 and fmt_refs[0][1] == "0xc45c"),
        "pending_writes_six": len(pending) == 6
        and all(a in pending for a in (
            "0x9f44", "0xa018", "0x100c8", "0x106c0", "0x1095c", "0x10b20")),
        "hardcoded_minus2_present": "0xa9bc" in minus2s
        and "0xc728" in minus2s and "0x12dc8" in minus2s,
        "minus103_chain_evidence_intact": (
            m103.get("all_pass") is True
            and m103.get("f4_status") == "BLOCKED"),
        "final_verdict_intact": (
            final.get("no_criterion_pass") is True
            and final.get("f4_status") == "BLOCKED"),
        "no_false_claim_in_source": all(
            s not in read(Path(__file__)) for s in FORBIDDEN),
    })
    failed = [k for k, v in checks.items() if not v]

    d = {
        "method": ("12 §61.77 / F4 读侧空白链补齐（纯离线、capstone + "
                   "pyelftools、零生产改动）"),
        "generated_by": "tools/l3_f4_read_gap_chain_disasm.py",
        "mode": "offline_disasm", "device_run": False,
        "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
        "so_sha256": sha,
        "dependencies": ["capstone 5.0.7", "pyelftools"],
        "rodata": {"addr": hex(FMT_READ), "text": fmt,
                   "references": fmt_refs, "unique": len(fmt_refs) == 1},
        "anchors": {k: {"addr": hex(v[0]), "mnemonic": v[1],
                        "fragment": v[2],
                        "actual": (by.get(v[0]).mnemonic + " " +
                                   by.get(v[0]).op_str) if by.get(v[0]) else ""}
                    for k, v in ANCHORS.items()},
        "pending_198_writes": pending,
        "hardcoded_minus2_points": minus2s,
        "cross": {
            "minus103_chain": "all_pass=%s / F4=%s" % (
                m103.get("all_pass"), m103.get("f4_status")),
            "final_verdict": "no_criterion_pass=%s / F4=%s" % (
                final.get("no_criterion_pass"), final.get("f4_status")),
        },
        "checks": checks, "checks_total": len(checks),
        "all_pass": not failed, "failed_checks": failed,
        "f4_status": "BLOCKED",
        "verdict": (
            "INFERENCE：`result:-2` 由 `0xc6f8 bl 0x10198`(WaitRequest) 返回 1 "
            "后于 `0xc728 mov w0,#-2` 产生；-2 与 -103 是 `0xa9bc`/`0xa9c4` "
            "两条硬编码分支，共用 `0xa9e4` 写 `[+0x1a0]`，且仅在挂起异步读 "
            "`[+0x198]` 非零时发生。与 §61.65/§61.67/§61.74/§61.76 串成完整静态链。"
            "不断定本次命中哪条分支，需动态插桩。"),
        "fix_anchors": ["0x10798 代次比较早退", "0xc728 WaitRequest 读空 -2",
                        "0x12dc8 NMSlicesFile::Write 越界 -2"],
        "truthfulness": (
            "静态二进制证据 ≠ 修复 ≠ 验证；未做动态插桩或新真机 A/B；"
            "真机取证已于 §61.75 完结，本批不需要设备；接线回滚仍待用户拍板；"
            "native 分支的修复路线需用户拍板，本批不擅自改动。F4 仍 BLOCKED。"),
        "honest_notes": [
            "不连 adb、不改设备现场、零 P0 源码改动、so 二进制原样。",
            "§61.76 / §61.75 证据只读交叉、零改写。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 不回填。",
        ],
    }
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / F4=%s"
          % (len(checks), not failed, d["f4_status"]))
    if failed:
        print("[FAIL] §61.77 反汇编核验未通过：%s" % failed)
        return 1
    print("[PASS] §61.77 读侧空白链补齐：result:-2 产生点、挂起异步读 "
          "6 个写入点与完整静态链定位完成；F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
