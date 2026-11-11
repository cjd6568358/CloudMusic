#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.76 / F4 · `-103` 产生链与播放侧状态机反汇编（纯离线）。

承接 §61.74（Write start play 4 道门控）与 §61.75（真机终局裁决：
主判据无一 PASS、播放未出声）。本工具把「读侧 -103 → 播放侧门控」
的上游链补齐，回答 F4 最靠前的修复点在哪里。

结论（静态二进制证据，inference）：
* libNMCommonCache.so 中 `-103` 是**硬编码立即数** `mov w25, #-0x67`
  （0xa85c / 0xa9c4），写入 `[this+0x1a0]`（0xa8b4 / 0xa9e4）；
  写入 B 仅在 `[this+0x198]`（挂起异步读）非零时发生（0xa9d0/cbz）。
* `Finish Async` 回调经写线程任务队列 `0x9fc0 bl 0x16468` 投递；
  `0xa008 ldr w20,[x19,#0x1a0]` 读出错误码，`0xa010~0xa018` 清零
  `+0x188/+0x190/+0x198`，`0xa040 bl 0x3138c` 打印
  `NMBufferSourceImp::Read((%lld-%lld) Finish Async:%d`，
  `0xa064 mov w0,w20` + `0xa07c ret` **把 -103 原样返回播放侧**。
* libncmaudioplayer.so 中 `[+0x20c]` 是 **paused 标志**
  （`.rodata @0xe190a` = `AudioTrack(%d): Paused`）：构造清零 0x65198，
  置 1 于 0x657d4 / 0x67478（并 `vtable+0x150(w1=2)`），
  清零于 0x67314（`w1=1`）。`[+0x1e8]` 是 AudioTrack 对象指针，
  设置于 0x572d4 / 0x583bc，释放清零于 0x4cea8 / 0x4d630 /
  0x55b5c / 0x65cd0 / 0x66810。
* ⇒ §61.74 的门控 1/2（对象为空/已释放）与门控 3（已 paused）
  在上游有明确写入点；但**不能断定本次运行命中哪一道**（需动态插桩）。

零 P0 源码改动、不连 adb、纯离线、历史证据只读交叉。
产出 analysis/l3-f4-minus103-chain.{json,report.md}。
F4 仍 BLOCKED —— 静态证据等级提升 ≠ 修复 ≠ 验证。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
CACHE = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
AUDIO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libncmaudioplayer.so"
OUT = A / "l3-f4-minus103-chain.json"
REPORT = A / "l3-f4-minus103-chain-report.md"
GATE = A / "l3-f4-play-gate-disasm.json"
FINAL = A / "l3-f4-acceptance-final.json"

FORBIDDEN = ("F4 已" + "解决", "m4a 已" + "支持", "全格式已" + "支持",
             "level-5 已" + "开启", "播放已" + "成功", "推断已" + "升级为实证")

FMT_FINISH = 0x3B8F3
FMT_PAUSED = 0xE190A
FMT_FINISH_TEXT = "NMBufferSourceImp::Read((%lld-%lld) Finish Async:%d"
FMT_PAUSED_TEXT = "AudioTrack(%d): Paused"

ANCHORS = {
    "minus103_imm_A": 0xA85C, "minus103_imm_B": 0xA9C4,
    "write_1a0_A": 0xA8B4, "write_1a0_B": 0xA9E4,
    "pending_read_gate": 0xA9D0, "pending_read_cbz": 0xA9D4,
    "queue_dispatch": 0x9FC0, "read_status": 0xA008,
    "clear_188": 0xA010, "clear_190": 0xA014, "clear_198": 0xA018,
    "g_level_getter_call": 0xA01C, "fmt_ref": 0xA034,
    "log_call": 0xA040, "return_minus103": 0xA064, "ret": 0xA07C,
}


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def jload(p: Path) -> dict:
    try:
        return json.loads(read(p))
    except (ValueError, TypeError):
        return {}


def load(p: Path):
    data = p.read_bytes()
    e = ELFFile(open(str(p), "rb"))
    t = next(s for s in e.iter_sections() if s.name == ".text")
    r = next(s for s in e.iter_sections() if s.name == ".rodata")
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    return data, t, r, list(md.disasm(t.data(), t["sh_addr"]))


def rstr(data, r, va, n=100) -> str:
    off = r["sh_offset"] + (va - r["sh_addr"])
    return data[off:off + n].split(b"\x00")[0].decode("utf-8", "replace")


def build_report(d: dict) -> str:
    lines = [
        "# F4 · `-103` 产生链与播放侧状态机反汇编（§61.76，2026-11-11）",
        "",
        "> **性质**：纯离线静态二进制证据（capstone 5.0.7 + pyelftools）。",
        "> 不连 adb、不改设备现场、零 P0 源码改动。结论是 **inference**，",
        "> **真机取证已于 §61.75 完结**，本批只做离线定位。",
        "> **F4 仍 BLOCKED**。",
        "",
        "## 1. 问题",
        "",
        "`§61.75` 终局裁决：F4 主判据无一 PASS，播放未出声。",
        "`§61.74` 找到 `Write start play` 打印点前 4 道静默早退门控，",
        "但没追这些状态位是谁写的、也没追 `-103` 从哪来。本批补上游链。",
        "",
        "## 2. 读侧：`-103` 是硬编码，不是计算结果",
        "",
        "- `libNMCommonCache.so` sha256 `%s…`；" % d["cache_sha256"][:12],
        "  `.rodata @0x3b8f3` = `%s`。" % FMT_FINISH_TEXT,
        "- **`-103` = `mov w25, #-0x67`（0x67 = 103）硬编码立即数**，",
        "  出现在 `0xa85c` / `0xa9c4`，写入 `[this+0x1a0]` 于",
        "  `0xa8b4` / `0xa9e4`。写入 B 仅在 `0xa9d0 ldr x8,[x19,#0x198]`",
        "  + `0xa9d4 cbz` 判定**挂起异步读请求非零**时发生。",
        "- `Finish Async` 回调由写线程任务队列投递（`0x9fc0 bl 0x16468`）；",
        "  `0xa008 ldr w20,[x19,#0x1a0]` 读出错误码，`0xa010~0xa018`",
        "  清零 `+0x188/+0x190/+0x198`，`0xa01c bl 0x37e20` 是 §61.70",
        "  已证实的 `g_level` getter（默认 0 → 日志输出），",
        "  `0xa040 bl 0x3138c` 打印，**`0xa064 mov w0,w20` + `0xa07c ret`",
        "  把 -103 原样返回播放侧**。",
        "- 勘误：初扫容差把 `0x3b927`（`NMBufferSourceImp::Seek(...)`）",
        "  误算成 `Finish Async` 第二引用点，已排除；该格式串**唯一**引用",
        "  是 `0xa02c/0xa034`。",
        "",
        "## 3. 播放侧：门控状态位有明确写入点",
        "",
        "- `libncmaudioplayer.so` sha256 `%s…`；" % d["audio_sha256"][:12],
        "  `.rodata @0xe190a` = `%s` → 坐实 **`[+0x20c]` = paused 标志**。" % FMT_PAUSED_TEXT,
        "| 地址 | 动作 | 语义 |",
        "|---|---|---|",
        "| `0x65198` | `strb wzr,[x19,#0x20c]` | 构造时清零 |",
        "| `0x657d4` / `0x67478` | `strb w8(=1)` | 置 paused，随后 `vtable+0x150(w1=2)` |",
        "| `0x67314` | `strb wzr` | 清 paused，随后 `vtable+0x150(w1=1)` |",
        "",
        "- `[+0x1e8]` = AudioTrack 对象指针：设置 `0x572d4` / `0x583bc`；",
        "  释放清零 `0x4cea8` / `0x4d630` / `0x55b5c` / `0x65cd0` /",
        "  `0x66810`（后者紧跟 `bl 0x14470` + `blr [vtable+0xb0]`）。",
        "- 对应 `§61.74` 门控：**1/2 = 对象为空或已释放**；",
        "  **3 = 已 paused**；4 = `bl 0x13d80` 返回 3。",
        "",
        "## 4. 与真机证据交叉",
        "",
    ]
    for k, v in d["cross"].items():
        lines.append("- %s：%s" % (k, v))
    lines.extend([
        "",
        "## 5. 结论与修复方向（inference，须动态插桩确认）",
        "",
        "- 链条：**挂起异步读 `[+0x198] != 0` → 硬编码 -103 写 `+0x1a0`",
        "  → 经 `0x16468` 队列投递 `Finish Async` → 回调把 -103 返回播放侧",
        "  → 播放侧 paused 置位 / AudioTrack 释放 → 门控 1/2/3 静默早退",
        "  → `Write start play` 不打印、无音频输出**。",
        "- **不能断定本次运行命中哪一道门控**，也不能把静态链写成",
        "  已验证的因果；`§61.75` 真机终局结论不变。",
        "- **F4 最靠前的修复点前移**：不再是「怎么触发 W 分支」或",
        "  「日志级别」，而是**读侧为什么会留下挂起异步读并写入 -103**",
        "  （接 `§61.65/§61.67` 的代次错配 → `commit` 冻结 →",
        "  `ReadFromFileNoLock result:-2`）。下一步应定位 `+0x198` 挂起",
        "  请求的写入点与 `result:-2` 的传递链。",
        "- 真机取证已完结，本批**不需要设备**；后续仅 F4 修复后回归需要。",
        "",
        "## 6. 实跑核验与边界",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(d["failed_checks"]) or "无"),
        "- `§61.74` 门控证据、`§61.75` 终局裁决证据**只读交叉、零改写**。",
        "- **F4 仍 BLOCKED**：主判据无一 PASS、播放未进入 PLAYING、",
        "  `-103` 仍在场、自动进曲 3 首 / seek / ENDED 未通过；",
        "  A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。",
        "- 零 P0 源码改动、未重编译 / 未重装、so 二进制原样、未改用户网络；",
        "  §61.67~§61.75 历史正文与证据 JSON 一字未改。",
        "- 不写任何提前收口结论；证据等级提升 ≠ 修复 ≠ 验证。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    cd, ct, cr, ci = load(CACHE)
    ad, at, ar, ai = load(AUDIO)
    cache_by = {x.address: x for x in ci}
    audio_by = {x.address: x for x in ai}

    def cop(a):
        x = cache_by.get(a)
        return (x.mnemonic, x.op_str) if x else ("", "")

    def aop(a):
        x = audio_by.get(a)
        return (x.mnemonic, x.op_str) if x else ("", "")

    finish_txt = rstr(cd, cr, FMT_FINISH)
    paused_txt = rstr(ad, ar, FMT_PAUSED)

    # -103 立即数与 [+0x1a0] 写入点（全 .text 扫描）
    imm103 = []
    writes_1a0 = []
    for x in ci:
        for o in x.operands:
            if o.type == ARM64_OP_IMM and o.imm in (-103, -0x67):
                imm103.append(hex(x.address))
                break
        for o in x.operands:
            if (o.type == ARM64_OP_MEM and o.mem.disp == 0x1A0
                    and x.mnemonic.startswith(("str", "stur"))
                    and "[sp" not in x.op_str):
                writes_1a0.append(hex(x.address))
                break
    paused_writes = []
    track_clears = []
    track_sets = []
    for x in ai:
        for o in x.operands:
            if o.type != ARM64_OP_MEM:
                continue
            if o.mem.disp == 0x20C and x.mnemonic.startswith(("strb", "str")):
                paused_writes.append(hex(x.address))
            elif (o.mem.disp == 0x1E8
                  and x.mnemonic.startswith(("str", "stur"))
                  and "[sp" not in x.op_str):
                if "xzr" in x.op_str or "wzr" in x.op_str:
                    track_clears.append(hex(x.address))
                else:
                    track_sets.append(hex(x.address))
            break

    gate = jload(GATE)
    final = jload(FINAL)
    cross = {
        "§61.75 终局裁决": "主判据无一 PASS / F4=%s" % final.get("f4_status"),
        "§61.74 门控": "%d 道，all_pass=%s" % (
            len(gate.get("gates", {})), gate.get("all_pass")),
    }

    expect = {
        "minus103_imm_A": ("mov", "-0x67"),
        "minus103_imm_B": ("mov", "-0x67"),
        "write_1a0_A": ("str", "0x1a0"),
        "write_1a0_B": ("str", "0x1a0"),
        "pending_read_gate": ("ldr", "0x198"),
        "pending_read_cbz": ("cbz", "0xa9f8"),
        "queue_dispatch": ("bl", "0x16468"),
        "read_status": ("ldr", "0x1a0"),
        "clear_188": ("str", "0x188"),
        "clear_190": ("str", "0x190"),
        "clear_198": ("str", "0x198"),
        "g_level_getter_call": ("bl", "0x37e20"),
        "fmt_ref": ("add", "0x8f3"),
        "log_call": ("bl", "0x3138c"),
        "return_minus103": ("mov", "w20"),
        "ret": ("ret", ""),
    }
    anchor_checks = {}
    for name, (mn, operand) in expect.items():
        got_mn, got_op = cop(ANCHORS[name])
        anchor_checks["anchor_" + name] = (
            got_mn == mn and operand in got_op)
    checks = dict(anchor_checks)
    checks.update({
        "finish_fmt_string_exact": finish_txt == FMT_FINISH_TEXT,
        "paused_fmt_string_exact": paused_txt == FMT_PAUSED_TEXT,
        "minus103_imm_count_two": len(imm103) >= 2
        and "0xa85c" in imm103 and "0xa9c4" in imm103,
        "write_1a0_points_present": all(
            a in writes_1a0 for a in ("0xa8b4", "0xa9e4")),
        "paused_writes_four": len(paused_writes) == 4
        and "0x65198" in paused_writes and "0x657d4" in paused_writes,
        "audio_track_clear_and_set": bool(track_clears) and bool(track_sets)
        and "0x66810" in track_clears,
        "gate_evidence_intact": gate.get("all_pass") is True
        and len(gate.get("gates", {})) == 4,
        "final_verdict_intact": final.get("no_criterion_pass") is True
        and final.get("f4_status") == "BLOCKED",
        "no_false_claim_in_source": all(
            s not in read(Path(__file__)) for s in FORBIDDEN),
    })
    failed = [k for k, v in checks.items() if not v]

    d = {
        "method": ("12 §61.76 / F4 -103 产生链与播放侧状态机反汇编"
                   "（纯离线、capstone + pyelftools、零生产改动）"),
        "generated_by": "tools/l3_f4_minus103_chain_disasm.py",
        "mode": "offline_disasm", "device_run": False,
        "cache_so": str(CACHE.relative_to(ROOT)).replace("\\", "/"),
        "cache_sha256": hashlib.sha256(cd).hexdigest(),
        "audio_so": str(AUDIO.relative_to(ROOT)).replace("\\", "/"),
        "audio_sha256": hashlib.sha256(ad).hexdigest(),
        "dependencies": ["capstone 5.0.7", "pyelftools"],
        "rodata": {"finish_addr": hex(FMT_FINISH), "finish": finish_txt,
                   "paused_addr": hex(FMT_PAUSED), "paused": paused_txt},
        "anchors": {k: {"addr": hex(v), "mnemonic": cop(v)[0],
                        "operands": cop(v)[1]} for k, v in ANCHORS.items()},
        "minus103_immediates": imm103,
        "writes_1a0": writes_1a0,
        "paused_writes": paused_writes,
        "audio_track_clears": track_clears,
        "audio_track_sets": track_sets,
        "cross": cross,
        "checks": checks, "checks_total": len(checks),
        "all_pass": not failed, "failed_checks": failed,
        "f4_status": "BLOCKED",
        "verdict": (
            "INFERENCE：-103 是硬编码立即数写 [+0x1a0]，由挂起异步读触发、"
            "经 0x16468 队列的 Finish Async 回调原样返回播放侧；播放侧 "
            "[+0x20c] 为 paused 标志、[+0x1e8] 为 AudioTrack 指针，"
            "二者有明确写入点，对应 §61.74 门控 1/2/3 的静默早退。"
            "不能断定本次运行命中哪一道，需动态插桩。"),
        "next_fix_anchor": (
            "定位 [+0x198] 挂起异步读的写入点与 result:-2 的传递链，"
            "接 §61.65/§61.67 的代次错配 → commit 冻结；这是比日志判据"
            "更靠前的 F4 修复点"),
        "truthfulness": (
            "静态二进制证据等级提升 ≠ 修复 ≠ 验证；未做动态插桩或新真机 A/B；"
            "真机取证已于 §61.75 完结，本批不需要设备；接线回滚仍待用户拍板；"
            "F4 仍 BLOCKED。"),
        "honest_notes": [
            "不连 adb、不改设备现场、零 P0 源码改动、so 二进制原样。",
            "§61.74 门控证据与 §61.75 终局裁决证据只读交叉、零改写。",
            "初扫容差误把 0x3b927(Seek) 当 Finish Async 第二引用，已排除。",
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
        print("[FAIL] §61.76 反汇编核验未通过：%s" % failed)
        return 1
    print("[PASS] §61.76 -103 硬编码链与播放侧 paused/AudioTrack 状态位定位完成；"
          "F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
