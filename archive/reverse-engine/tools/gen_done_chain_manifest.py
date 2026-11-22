#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 §61.54 / §61.55 两份一手反汇编 JSON 生成 D 组元数据指纹 manifest。

背景：D 组的地址 / size / 指令数常量硬编码在 `NmBufferSourceImp.kt` companion object，
此前不像 C / F 两份清单那样有源 JSON 指纹守护。本生成器把源 JSON 的 sha256 + 逐项
期望值写进 `spec/done-chain-manifest.json`，由 `tools/audit.py` 每次比对：
  - 源 JSON 变了却没重新生成 -> 指纹不匹配 -> audit fail
  - 有人手改 Kotlin 常量      -> 常量与 manifest 不符 -> audit fail

口径：生成器只从源 JSON 提取期望值，不读 Kotlin；Kotlin 一致性由 audit.py 校验。
用法：python tools/gen_done_chain_manifest.py
"""

import hashlib
import json
import re
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
ANALYSIS = RE.parent / "analysis"
OUT = RE / "spec" / "done-chain-manifest.json"

SRC_VERIFY = "l3-f4-disasm-verify.json"
SRC_DIFF = "l3-f4-task-diff.json"


def load(name: str):
    p = ANALYSIS / name
    if not p.exists():
        raise SystemExit(f"[FATAL] source missing: {p}")
    data = json.loads(p.read_text(encoding="utf-8"))
    return data, hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_size


def as_int(v) -> int:
    """源 JSON 里的地址既可能是 '0xbee8' 字符串，也可能是整数。"""
    if isinstance(v, str):
        return int(v, 16) if v.lower().startswith("0x") else int(v)
    return int(v)


def main() -> int:
    verify, verify_sha, verify_bytes = load(SRC_VERIFY)
    diff, diff_sha, diff_bytes = load(SRC_DIFF)

    fn = verify["function"]
    ev = verify["evidence"]
    relocs = ev["got_relocations"]
    checks_verify = verify["checks"]
    funcs = diff["functions"]
    checks_diff = diff["checks"]
    render_got = next(iter(diff["evidence"]["got_render_relocation"]))

    # --- 硬断言：保证「派生值」的口径有据可查，防止静态推断被写成已核对 ---
    assert "0x4bed0" in relocs and "0x4bee8" in relocs, "GOT slots missing"
    assert bool(checks_verify.get("both_post_to_same_queue")), "shared queue not confirmed"
    assert int(ev.get("queue_bl_0x16468_count", -1)) == 2, "Done window post count changed"
    assert bool(checks_diff.get("preload_write_posts_cascade_task")), "cascade post not confirmed"
    assert int(checks_verify.get("kind2_slot_is_0x4bed0_other_is_0x4bee8", 0)) in (True, 1)
    assert int(len(diff["evidence"]["flush_full"])) == int(funcs["flush"]["instructions"]), \
        "flush_full length != flush.instructions"

    done_window_posts = int(ev["queue_bl_0x16468_count"])
    constants = {
        # §61.54：OnNetSourceDataDone 函数窗口
        "DONE_FUNC_ADDR": as_int(fn["addr"]),
        "DONE_FUNC_SIZE": int(fn["size"]),
        "GOT_KIND2": as_int("0x4bed0"),
        "GOT_OTHER": as_int("0x4bee8"),
        "DONE_WINDOW_QUEUE_POSTS": done_window_posts,
        # §61.55：三个 task 的 size / 指令数
        "PRELOAD_WRITE_ADDR": as_int(funcs["preload_write"]["addr"]),
        "PRELOAD_WRITE_SIZE": int(funcs["preload_write"]["size"]),
        "PRELOAD_WRITE_INSNS": int(funcs["preload_write"]["instructions"]),
        "FLUSH_ADDR": as_int(funcs["flush"]["addr"]),
        "FLUSH_SIZE": int(funcs["flush"]["size"]),
        "FLUSH_INSNS": int(funcs["flush"]["instructions"]),
        "PRELOAD_RENDER_ADDR": as_int(funcs["preload_render"]["addr"]),
        "PRELOAD_RENDER_SIZE": int(funcs["preload_render"]["size"]),
        "PRELOAD_RENDER_INSNS": int(funcs["preload_render"]["instructions"]),
        "GOT_RENDER": as_int(render_got),
        # 队列与任务对象：从 evidence 字符串交叉核对目标地址
        "QUEUE_POST_ADDR": 0x16468,
        "QUEUE_POST_CALL_SITES": done_window_posts + 1,  # +1 = 0xb924 级联投递
        "TASK_OBJECT_SIZE": 0x60,
        # checks 统计
        "TASK_DIFF_CHECKS_TOTAL": len(checks_diff),
        "TASK_DIFF_CHECKS_PASS": sum(1 for v in checks_diff.values() if v is True),
    }

    # 交叉核对 0x16468 确实出现在一手 evidence 里（防止硬编码地址脱离证据）
    blob = json.dumps(ev, ensure_ascii=False)
    assert re.search(r"0x16468", blob, re.IGNORECASE), "0x16468 not found in verify.evidence"

    manifest = {
        "tool": "reverse-engine/tools/gen_done_chain_manifest.py",
        "purpose": "§61.54/§61.55 D 组元数据防回潮：源 JSON sha256 + Kotlin 常量期望值",
        "sources": {
            SRC_VERIFY: {
                "sha256": verify_sha,
                "bytes": verify_bytes,
                "checksTotal": len(checks_verify),
                "checksPass": sum(1 for v in checks_verify.values() if v is True),
                "allPass": bool(verify.get("all_pass")),
                "function": fn.get("name"),
            },
            SRC_DIFF: {
                "sha256": diff_sha,
                "bytes": diff_bytes,
                "checksTotal": len(checks_diff),
                "checksPass": sum(1 for v in checks_diff.values() if v is True),
                "allPass": bool(diff.get("all_pass")),
                "functions": {k: v.get("name") for k, v in funcs.items()},
            },
        },
        "kotlinConstants": constants,
        "derived": {
            "QUEUE_POST_CALL_SITES": (
                "3 = Done 函数窗口内 2 处（verify.evidence.queue_bl_0x16468_count=2，"
                "调用点 0xbf7c / 0xc004）+ PreloadDoneTaskOnWriteThread 级联 1 处（0xb924）。"
                "源 JSON 的 2 是单函数窗口计数；Kotlin 的 3 是「本批证据涉及的投递点总数」，两者不矛盾。"
            ),
            "DONE_WINDOW_QUEUE_POSTS": "直接取自 verify.evidence.queue_bl_0x16468_count。",
            "TASK_OBJECT_SIZE": (
                "0x60 来自 analysis/l3-f4-disasm-verify-report.md 的任务对象对照（报告级 L2），"
                "不是源 JSON 字段；已在 LOGIC-SPEC D-04 标注等级 L2。"
            ),
            "QUEUE_POST_ADDR": "0x16468；由 verify.evidence 内的指令串交叉核对存在性。",
        },
        "specIds": [f"D-{i:02d}" for i in range(1, 13)],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print(f"[GEN-DONE] {OUT.name}")
    print(f"  sources: {SRC_VERIFY} {verify_bytes}B / {SRC_DIFF} {diff_bytes}B")
    print(f"  checks: verify {manifest['sources'][SRC_VERIFY]['checksPass']}/{manifest['sources'][SRC_VERIFY]['checksTotal']}"
          f" | diff {manifest['sources'][SRC_DIFF]['checksPass']}/{manifest['sources'][SRC_DIFF]['checksTotal']}")
    print(f"  kotlinConstants: {len(constants)} 项；derived: {len(manifest['derived'])} 条口径说明")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
