#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.74 / F4 · 播放写起点「Write start play」日志门控反汇编（纯离线）。

承接 §61.73：三轮真机都有 `Write start play` 0 次，冷/暖轮却已
`Open codec for aac` + `doPrepareAsync ... mDuration 2933`。本工具回答
「这个日志 0 次到底能不能当作『没有写 AudioTrack』的充分证据」。

做法：capstone 5.0.7 + pyelftools 反汇编 libncmaudioplayer.so，
* 定位 .rodata 的 `AudioTrack(%d): Write start play nErr:%d`（0xe180a）
  与 tag `NMAudioPlayer`（0xdd1b3）；
* 全 .text 扫描 adrp + (add|ldr) 页内引用，确认该格式串**唯一**引用点；
* 反汇编所在函数窗口 0x66e44~0x66fa8，提取到达打印点前的**4 道静默
  早退门控**与打印后的 nErr / 重试 / -3 回调链；
* 与 §61.73 三轮真机日志、下线前 media_session / audio 只读补证交叉。

**结论性质是 inference（静态二进制证据）**：日志点存在门控 ⇒
「0 次」不是「没有写 AudioTrack」的充分证据；但结合 MediaSession
state=2(PAUSED) / metadata:null / 无本 app AudioTrack / -103，
§61.73「播放未出声」的独立结论仍然成立。不得据此写「播放其实成功」
或任何提前收口结论。F4 仍 BLOCKED。

纯离线、不连 adb、不改设备现场、零生产改动。
产出 analysis/l3-f4-play-gate-disasm.json 与
analysis/l3-f4-play-gate-report.md。
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
SO = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libncmaudioplayer.so"
OUT = A / "l3-f4-play-gate-disasm.json"
REPORT = A / "l3-f4-play-gate-report.md"
ROUNDS_AUDIT = A / "l3-f4-live-rounds-audit.json"
PLAYBACK_STATE = A / "l3-f4-live-playback-state-20261111.txt"

FMT_ADDR = 0xE180A
TAG_ADDR = 0xDD1B3
FMT_TEXT = "AudioTrack(%d): Write start play nErr:%d"
WINDOW = (0x66E44, 0x66FA8)
EXPECT_REF = (0x66EEC, 0x66EF4)
LOG_CALL = 0x66F00

GATES = (
    ("gate_1_audio_track_ptr_null", 0x66E70, 0x66E74, 0x66F50,
     "[this+0x1e8] 为空 → 返回 -1，不打印"),
    ("gate_2_audio_track_ptr_null_second", 0x66EA8, 0x66EAC, 0x66F40,
     "[this+0x1e8] 二次判空 → 收尾 -1，不打印"),
    ("gate_3_state_byte_nonzero", 0x66EB0, 0x66EB4, 0x66F5C,
     "[this+0x20c] 非零 → 跳过打印，直接进入后续路径"),
    ("gate_4_helper_returns_3", 0x66EC8, 0x66ECC, 0x66F5C,
     "bl 0x13d80 返回 3 → 跳过打印"),
)
# 拼接 marker，避免「检查用的字面串本身就出现在被检查的源码里」导致恒 false。
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
    failed = d["failed_checks"]
    lines = [
        "# F4 · `Write start play` 日志门控反汇编（§61.74，2026-11-11）",
        "",
        "> **性质**：纯离线静态二进制证据（capstone 5.0.7 + pyelftools），",
        "> 不连 adb、不改设备现场、零生产改动。结论是 **inference**，",
        "> 不是 F4 修复、不是真机验证。**F4 仍 BLOCKED**。",
        "",
        "## 1. 问题",
        "",
        "`§61.73` 三轮真机：`Write start play` **0 次**，但冷/暖轮已有",
        "`Open codec for aac` 与 `doPrepareAsync ... mDuration 2933`。",
        "F4 一直把 `Write start play` 当「播放写 AudioTrack 开始」的判据，",
        "需确认该日志 0 次能否单独推出「未写 AudioTrack」。",
        "",
        "## 2. 静态证据",
        "",
        "- so：`libncmaudioplayer.so` sha256 `%s…`（%d B）。" % (
            d["so_sha256"][:12], d["so_bytes"]),
        "- `.rodata @0xe180a` = `%s`；tag `@0xdd1b3` = `NMAudioPlayer`。" % FMT_TEXT,
        "- 全 `.text` 扫描 `adrp + (add|ldr)` 页内引用：该格式串**唯一**引用点",
        "  `0x66eec/0x66ef4`，打印调用 `0x66f00 bl 0x5c18c`（函数窗口",
        "  `0x66e44~0x66fa8`）。",
        "- **到达打印点前有 4 道静默早退门控**：",
        "",
        "| # | 指令 | 目标 | 语义 |",
        "|---|---|---|---|",
    ]
    for i, (name, a1, a2, tgt, desc) in enumerate(GATES, 1):
        lines.append("| %d | `0x%x / 0x%x` | `0x%x` | %s |" % (
            i, a1, a2, tgt, desc))
    lines.extend([
        "",
        "只有 4 道全部放行，`0x66edc bl 0x14500` 的返回值才作为 `nErr`",
        "在 `0x66f00` 打印；`nErr==0` 时 `0x66f58` 清零 `[this+0x354]`",
        "重试计数，`nErr!=0` 且计数 >9 时 `0x66f28` 经 `[this+0xd8]`",
        "vtable+0x20 回调传 **-3**。",
        "",
        "## 3. 与 §61.73 真机证据交叉",
        "",
    ])
    for r in d["log_cross"]["rounds"]:
        lines.append("- %s：`Write start play` %d 次、codec=%s、`-103` %s、"
                     "commit=%s" % (r["key"], r["write_start_play"],
                                    ",".join(r["open_codec"]) or "无",
                                    r["has_103"], r["commit_values"]))
    lines.extend([
        "- 下线前只读补证：`MediaSession state=2 (PAUSED)` / `position=0` /",
        "  `metadata: null`；`dumpsys audio` 无本 app 的媒体 AudioTrack。",
        "",
        "## 4. 结论（inference，须人工复核）",
        "",
        "**`Write start play` 0 次不是「没有写 AudioTrack」的充分证据** ——",
        "该日志点自身有 4 道静默早退，`[this+0x1e8]` / `[this+0x20c]` /",
        "`0x13d80` 返回值任一命中都不打印。因此：",
        "① 不能仅凭该日志 0 次断定播放链没走写线程；",
        "② 更不能反过来推断「播放其实成功了」—— `MediaSession PAUSED`、",
        "  `metadata:null`、无本 app AudioTrack、`-103` 在场是**独立的一手",
        "  证据**，§61.73「播放未出声 / 未进入 PLAYING」结论仍然成立；",
        "③ **F4 判据口径需修正**：后续验收不单独依赖 `Write start play`，",
        "  应并用 `MediaSession state=3(PLAYING)` + 本 app AudioTrack",
        "  `state:started` + 自动进曲 3 首无 `-103` + seek / ENDED。",
        "- 下一步可验证锚点：`[this+0x1e8]`（AudioTrack 对象）、",
        "  `[this+0x20c]`（状态字节）、`0x13d80` 返回值、`[this+0x354]`",
        "  （重试计数）。**均需真机或动态插桩，本批未做**。",
        "",
        "## 5. 实跑核验与边界",
        "",
        "- checks：**%d 项 / 全 PASS %s**；失败项：%s" % (
            d["checks_total"], d["all_pass"],
            ", ".join(failed) if failed else "无"),
        "- `§61.71` 固化回放证据、`§61.73` 三轮审计证据**零改写**；",
        "  本工具只读它们做交叉，不重跑、不覆盖。",
        "- **F4 仍 BLOCKED**：`-103` 判据未通过、播放未进入 PLAYING、",
        "  自动进曲 3 首 / seek / ENDED 仍 NOT RUN、F3 14 格未改写；",
        "  A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。",
        "- 零 P0 源码改动、未重编译 / 未重装、so 二进制原样、未改用户网络；",
        "  §61.67~§61.73 历史正文与证据 JSON 一字未改。",
        "- 静态证据等级提升 ≠ 修复 ≠ 验证；不写任何提前收口结论。",
        "",
        "> AI生成",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    data = SO.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    e = ELFFile(open(str(SO), "rb"))
    secs = list(e.iter_sections())
    text = next(s for s in secs if s.name == ".text")
    rodata = next(s for s in secs if s.name == ".rodata")

    def rstr(va: int, n: int = 96) -> str:
        off = rodata["sh_offset"] + (va - rodata["sh_addr"])
        return data[off:off + n].split(b"\x00")[0].decode("utf-8", "replace")

    fmt = rstr(FMT_ADDR)
    tag = rstr(TAG_ADDR)
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    ins = list(md.disasm(text.data(), text["sh_addr"]))
    by_addr = {x.address: x for x in ins}

    # 全 .text 扫描 adrp + (add|ldr) 页内引用，找格式串引用点。
    refs = []
    for i, x in enumerate(ins):
        if x.mnemonic != "adrp" or len(x.operands) < 2:
            continue
        page, reg = x.operands[1].imm, x.operands[0].reg
        for y in ins[i + 1:i + 5]:
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
            else:
                continue
            if abs(tgt - FMT_ADDR) <= 0x10:
                refs.append((hex(x.address), hex(y.address), hex(tgt)))

    def op(addr: int) -> str:
        x = by_addr.get(addr)
        return x.mnemonic if x else ""

    def ops(addr: int) -> str:
        x = by_addr.get(addr)
        return x.op_str if x else ""

    gates = {}
    for name, a1, a2, tgt, desc in GATES:
        gates[name] = {
            "load_at": hex(a1), "branch_at": hex(a2),
            "branch_target": hex(tgt), "description": desc,
            "load_mnemonic": op(a1), "branch_mnemonic": op(a2),
            "branch_operand": ops(a2),
        }

    audit = jload(ROUNDS_AUDIT)
    rounds = []
    for r in audit.get("rounds", []):
        rounds.append({
            "key": r["key"], "write_start_play": r["write_start_play"],
            "open_codec": r["open_codec"], "has_103": r["has_103"],
            "commit_values": r["commit_values"],
        })
    pb = read(PLAYBACK_STATE)
    log_cross = {
        "rounds": rounds,
        "media_session_paused": "state=PlaybackState {state=2" in pb,
        "metadata_null": "metadata: null" in pb,
        "no_app_audio_track": "com.example.cloudmusic" not in pb.split(
            "--- dumpsys audio")[1] if "--- dumpsys audio" in pb else False,
    }

    checks = {
        "so_present_and_sha_recorded": len(sha) == 64 and len(data) > 100000,
        "rodata_write_fmt_exact": fmt == FMT_TEXT,
        "rodata_tag_nmaudioplayer": tag == "NMAudioPlayer",
        "write_fmt_unique_reference": (
            len(refs) == 1 and refs[0][0] == hex(EXPECT_REF[0])
            and refs[0][1] == hex(EXPECT_REF[1])),
        "log_call_present_at_0x66f00": (
            op(LOG_CALL) == "bl" and "0x5c18c" in ops(LOG_CALL)),
        "gate_1_audio_track_ptr_null": (
            op(0x66E74) == "cbz" and "0x66f50" in ops(0x66E74)),
        "gate_2_audio_track_ptr_null_second": (
            op(0x66EAC) == "cbz" and "0x66f40" in ops(0x66EAC)),
        "gate_3_state_byte_nonzero": (
            op(0x66EB4) == "cbnz" and "0x66f5c" in ops(0x66EB4)),
        "gate_4_helper_returns_3": (
            op(0x66EC8) == "cmp" and "#3" in ops(0x66EC8)
            and op(0x66ECC) == "b.eq" and "0x66f5c" in ops(0x66ECC)),
        "nerr_from_call_0x14500": (
            op(0x66EDC) == "bl" and "0x14500" in ops(0x66EDC)
            and op(0x66EE4) == "mov"),
        "retry_counter_and_minus3_callback": (
            op(0x66F10) == "cmp" and "#9" in ops(0x66F10)
            and op(0x66F34) == "mov" and "#-3" in ops(0x66F34)),
        "three_rounds_zero_write_start_play": (
            len(rounds) == 3 and all(r["write_start_play"] == 0 for r in rounds)),
        "cold_warm_have_codec_and_103": (
            all(r["open_codec"] for r in rounds[:2])
            and all(r["has_103"] for r in rounds[:2])),
        "media_session_paused_and_no_app_track": (
            log_cross["media_session_paused"]
            and log_cross["metadata_null"]
            and log_cross["no_app_audio_track"]),
        "f4_still_blocked_in_audit": audit.get("f4_status") == "BLOCKED",
        "no_false_claim_in_this_source": all(
            s not in read(Path(__file__)) for s in FORBIDDEN),
    }
    failed = [k for k, v in checks.items() if not v]

    d = {
        "method": ("12 §61.74 / F4 `Write start play` 日志门控反汇编"
                   "（纯离线、capstone + pyelftools、零生产改动）"),
        "generated_by": "tools/l3_f4_play_gate_disasm.py",
        "mode": "offline_disasm",
        "device_run": False,
        "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
        "so_sha256": sha,
        "so_bytes": len(data),
        "dependencies": ["capstone 5.0.7", "pyelftools"],
        "rodata": {"fmt_addr": hex(FMT_ADDR), "fmt": fmt,
                   "tag_addr": hex(TAG_ADDR), "tag": tag},
        "references": refs,
        "function_window": [hex(WINDOW[0]), hex(WINDOW[1])],
        "log_call": hex(LOG_CALL),
        "gates": gates,
        "log_cross": log_cross,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "f4_status": "BLOCKED",
        "verdict": (
            "INFERENCE：`Write start play` 打印点前有 4 道静默早退门控，"
            "0 次不是「没有写 AudioTrack」的充分证据；但结合 MediaSession "
            "PAUSED / metadata:null / 无本 app AudioTrack / -103，"
            "§61.73「播放未出声」的独立结论仍成立。F4 判据需并用播放态证据。"),
        "next_anchors": ["[this+0x1e8]", "[this+0x20c]",
                         "0x13d80 返回值", "[this+0x354] 重试计数"],
        "truthfulness": (
            "静态二进制证据等级提升 ≠ 修复 ≠ 验证；未做动态插桩或新真机 A/B，"
            "无法断定本次运行命中哪一道门控；真机复跑 NOT RUN（设备已可下线）；"
            "接线回滚仍待用户拍板；F4 仍 BLOCKED。"),
        "honest_notes": [
            "不连 adb、不改设备现场、零 P0 源码改动、so 二进制原样。",
            "§61.71 固化回放证据与 §61.73 三轮审计证据只读交叉、零改写。",
            "A3 仍 G09 PARTIAL、舱不冻结、engine_compat_test 不回填。",
            "不得把「日志点有门控」偷换成播放成功或 F4 收口结论。",
        ],
    }
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(build_report(d), encoding="utf-8")
    print("[INFO] 输出 %s / %s" % (OUT.name, REPORT.name))
    print("[INFO] checks %d 项 / all_pass=%s / F4=%s"
          % (len(checks), not failed, d["f4_status"]))
    if failed:
        print("[FAIL] §61.74 反汇编核验未通过：%s" % failed)
        return 1
    print("[PASS] §61.74 `Write start play` 4 道静默早退门控定位完成；"
          "0 次非充分证据、播放未出声独立结论仍成立；F4 仍 BLOCKED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
