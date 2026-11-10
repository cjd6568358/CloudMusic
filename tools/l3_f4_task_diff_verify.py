#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.55 / F4 · kind 槽两个 task 的行为差异复核（离线、只读）。

承接 §61.54：已确认 `OnNetSourceDataDone(kind,...)` 的
`kind==2 → PreloadDoneTaskOnWriteThread`（GOT 0x4bed0）、
其余 → `OnFlushNetData`（GOT 0x4bee8）。本工具继续回答：
**这两个 task 分别做什么**，误传 kind=2 到底有多严重，以及
§61.53 A/B 变体「恒传 1L」是不是引入了一条未验证的新路径。

只读；复用 tools/l3_f4_disasm_verify.py 的 ELF/capstone 封装（本机已有）。
产出 analysis/l3-f4-task-diff.json。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import l3_f4_disasm_verify as dv  # noqa: E402  只读模块，import 不执行 main

OUT = ROOT / "analysis" / "l3-f4-task-diff.json"
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"

FUNCS = {
    "preload_write": {
        "name": "_ZN17NMBufferSourceImp28PreloadDoneTaskOnWriteThreadEv",
        "addr": 0xB86C, "size": 288,
    },
    "preload_render": {
        "name": "_ZN17NMBufferSourceImp29PreloadDoneTaskOnRenderThreadEb",
        "addr": 0xADBC, "size": 420,
    },
    "flush": {
        "name": "_ZN17NMBufferSourceImp14OnFlushNetDataEv",
        "addr": 0xC068, "size": 88,
    },
}
GOT_RENDER = 0x4BF28
QUEUE = 0x16468


def disasm_text(addr: int, size: int) -> tuple[str, list[str]]:
    lines = dv.disassemble(dv.SO, addr, size)
    return "\n".join(lines), lines


def main() -> int:
    if not dv.SO.is_file():
        print("[FAIL] 缺少 %s" % dv.SO)
        return 1
    info = dv.elf_info(dv.SO)
    relocs, syms = info["relocs"], info["symbols"]
    texts = {}
    for key, meta in FUNCS.items():
        texts[key] = disasm_text(meta["addr"], meta["size"])

    w, wl = texts["preload_write"]
    r, rl = texts["preload_render"]
    f, fl = texts["flush"]
    done, dl = disasm_text(dv.FUNC_ADDR, dv.FUNC_SIZE)

    got_render = relocs.get(GOT_RENDER, {})
    r59 = DELIVERY12.read_text(encoding="utf-8", errors="replace")

    def has(pattern: str, text: str) -> bool:
        """不依赖 capstone 的对齐空格宽度（复用模块的 %-9s 与 route1 的 <10 不同）。"""
        return re.search(pattern, text) is not None

    def count_posts(text: str) -> int:
        return len(re.findall(r"\bbl\s+#0x16468", text))

    checks = {
        # §61.54 的前提在本工具里独立复算一次（不盲信上一批 JSON）
        "done_has_cmp_w1_2": has(r"\bcmp\s+w1, #2", done),
        "done_posts_both_slots_to_same_queue": (
            count_posts(done) == 2 and "#0xed0" in done and "#0xee8" in done),
        "got_kind2_is_preload_write": (
            relocs.get(dv.GOT_KIND2, {}).get("symbol") == FUNCS["preload_write"]["name"]),
        "got_other_is_flush": (
            relocs.get(dv.GOT_OTHER, {}).get("symbol") == FUNCS["flush"]["name"]),
        # 核心新证据：kind==2 的 task 会级联投递渲染线程「预加载完成」
        "got_render_is_preload_done_render": (
            got_render.get("symbol") == FUNCS["preload_render"]["name"]
            and got_render.get("symbol_addr") == FUNCS["preload_render"]["addr"]),
        "preload_write_loads_render_got": "#0xf28" in w,
        "preload_write_sets_bool_true": (
            has(r"\bmov\s+w10, #1", w) and has(r"\bstrb\s+w10", w)),
        "preload_write_posts_cascade_task": count_posts(w) == 1,
        "render_task_is_substantial": (
            len(rl) >= 80 and has(r"\bbl\s+#0x10580", r)),
        # 对照：flush 是轻量尾调用，不投递任何新任务
        "flush_is_lightweight": (
            len(fl) <= 25
            and count_posts(f) == 0
            and has(r"\bbr\s+x1", f)),
        "flush_checks_flag_at_0xb8": has(r"\bldrb\s+w8, \[x0, #0xb8\]", f),
        "flush_tail_calls_vtable_0x40": has(r"\bldr\s+x1, \[x8, #0x40\]", f),
        # §59.3 E9 历史校准：1L 是已真机验证的正常数据包 kind；
        # kind=2 的尾段 push 会丢数据。故「恒传 1L」不是引入新路径。
        "docs59_3_kind1_calibrated": (
            "NATIVE_DATA_KIND_NORMAL = 1L" in r59
            and "Send Packet Invalid data error" in r59),
        "queue_post_is_shared_address": (
            count_posts(done) == 2 and count_posts(w) == 1
            and hex(QUEUE) == "0x16468"),
    }
    failed = [k for k, val in checks.items() if not val]
    data = {
        "method": "12 §61.55 / F4 kind 槽两个 task 的行为差异复核（离线、只读）",
        "generated_by": "tools/l3_f4_task_diff_verify.py",
        "so": str(dv.SO.relative_to(ROOT)).replace("\\", "/"),
        "functions": {
            k: {"name": v["name"], "addr": hex(v["addr"]), "size": v["size"],
                "instructions": len(texts[k][1])}
            for k, v in FUNCS.items()
        },
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence": {
            "got_render_relocation": {
                hex(GOT_RENDER): {
                    "symbol": got_render.get("symbol", ""),
                    "symbol_addr": hex(got_render.get("symbol_addr", 0)),
                    "reloc_type": got_render.get("type", 0),
                }},
            "preload_write_key_instructions": [
                l for l in wl if any(k in l for k in
                                     ("#0xf28", "w10, #1", "strb", "#0x16468",
                                      "0xd23c", "blr"))],
            "flush_full": fl,
            "render_key_calls": [
                l for l in rl if any(k in l for k in ("bl ", "blr", "br "))],
            "historical_calibration": (
                "docs/12 §59.3 E9：kind=1 的 push 全部落内存缓存、32768B 送达解码器；"
                "kind=2 的尾段 push 数据丢失 → Send Packet Invalid data error。"
                "数据包已校准 NATIVE_DATA_KIND_NORMAL=1L，End 回调从未校准。"),
        },
        "conclusion": (
            "kind==2 触发的是一条重的级联完成链：PreloadDoneTaskOnWriteThread"
            "（288B）构造对象、置 bool=true、把 PreloadDoneTaskOnRenderThread"
            "（GOT 0x4bf28 → 0xadbc, 420B）再投递到同一个 0x16468 队列，"
            "即向渲染线程宣告『预加载完成』；kind!=2 的 OnFlushNetData 只有 88B，"
            "检查 this+0xb8 标志后经 vtable+0x40 尾调用刷数据，不投递任何新任务。"
            "两者严重不对称，误传 kind=2 会让数据不足的流被宣告完成，"
            "与 §59.3 E9『kind=2 尾段 push 数据丢失』互相印证。"
            "同时 §59.3 已把数据包 kind 真机校准为 1L，故 §61.53 A/B 的"
            "『恒传 1L』是对齐到已验证的正常值、不是引入未验证新路径，"
            "变体风险显著降低。但『requestId==2 导致 fMP4 卡点』的因果链"
            "仍无真机证据，F4 仍 BLOCKED。"),
        "honest_notes": [
            "本工具只读 so 与 docs，不连 adb、不改任何生产代码、零新增依赖。",
            "静态反汇编能证明『两个 task 做什么』，不能证明 Java 侧传了什么或 fMP4 卡点的因果。",
            "§59.2 exp6 反例、竞品同样传 requestId 却可用、ape×remote 与 aiff 仍不解释。",
            "OnFlushNetData 在 this+0xb8 为 0 时会走日志/错误分支；但 1L 是 §59.3 已校准的数据包值，正常播放每天都在走，非新路径。",
            "A/B 尚未执行；自动进曲 3 首 / seek / ENDED 如实 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.55 复核未通过：%s" % failed)
        return 1
    print("[PASS] §61.55 复核：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
