#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.71 守护 / F4 上线复跑的 W 分支判读工具（纯离线、零第三方依赖）。

背景：analysis/l3-f4-online-runbook.md 的「§61.70 补充执行层细化」要求上线
复跑后「按真实 .rodata 格式串扫 W 级日志」，但被指定复跑的
tools/l3_f4_vfile_commit_probe.py 只统计 commit 时序，没有任何 W 分支判读。
本工具补上这一环：

* 从 libNMCommonCache.so 的 .rodata 按 7 个地址读出**真实格式串**（纯 stdlib
  解析 ELF64 section header，不依赖 capstone / pyelftools），用其中稳定的
  文本锚点扫描 logcat；不硬编码「level-5 关键词」等已被 §61.70 证伪的猜测词。
* --replay（默认）离线回放 analysis/l3-f4-logs/ 全部归档，与 §61.70 ③ 和
  执行层细化的结论做一致性核验；可实跑验证。
* --log <path> 对单份 logcat（例如探针在真机采集的日志）做上线判读，输出
  live_verdict。**本工具不连 adb、不采集日志、不假装真机复跑**；device_run
  恒为 false，真机复跑仍须用户先让设备上线。

判读语义（§61.70 + runbook 补充表）：7 条 W 级格式串全部是错误 / 边界分支
告警，正常顺序写入不打印。因此「0 命中」是分支未触发的有效负结果，不是
日志被门控挡住；只有 UnExpect New Offsetcommted / Write buffer len 等出现，
才把「代次错配 / file_len 门控」由推断升级为一手实证。

产出：回放写 analysis/l3-f4-wbranch-recheck.json（固化证据），--log 单日志
判读写 analysis/l3-f4-wbranch-live-verdict.json（不覆盖前者）。F4 仍
BLOCKED；all_pass 仅表示「离线一致性核验通过」，绝不表示 F4 验收通过。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SO = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libNMCommonCache.so"
LOGDIR = ROOT / "analysis" / "l3-f4-logs"
OUT = ROOT / "analysis" / "l3-f4-wbranch-recheck.json"
LIVE_OUT = ROOT / "analysis" / "l3-f4-wbranch-live-verdict.json"
REPORT = ROOT / "analysis" / "l3-f4-wbranch-recheck-report.md"
PROBE = ROOT / "tools" / "l3_f4_vfile_commit_probe.py"

# (rodata 地址, 从真实格式串派生的稳定锚点, 触发条件, 返回, 分类)
W_BRANCHES = (
    (0x3CC73, "::OnData() offset:",
     "代次匹配且 except_offset > offset 或 offset+size > end", "仅日志", "error"),
    (0x3CCC9, "UnExpect New Offsetcommted",
     "commit != offset", "仅日志", "error"),
    (0x3CF90, "NMSlicesFile Write buffer len:",
     "len==0 / offset 非 64B 对齐 / offset+len > file_len", "-102", "error"),
    (0x3CFCE, "Finish EOF.",
     "offset == file_len", "-1", "error"),
    (0x3D062, "File Not Exsit",
     "切片文件对象缺失", "继续", "error"),
    (0x3D017, "Fail. code:",
     "底层 slice write 返回负值", "-98", "error"),
    (0x3D0AE, "NMSlicesFile Write all finish",
     "全文件写满 + flush 完成（一次性）", "0", "success"),
)

RUN1 = "vfile-commit-20261111-004310.txt"
RUN2 = "vfile-commit-20261111-004603.txt"
FINISH_LOG = "ksab-flac-remote.txt"

RE_GEN = re.compile(r"MakeRangeRequestAsync\((\d+)\)")
RE_INST = re.compile(r"NMNetSource\(1-(\d+)\)")
RE_COMMIT = re.compile(r"commit :\s*(\d+)")
RE_READ2 = re.compile(r"readed_len:0,\s*result:-2")
RE_103 = re.compile(r"Finish Async:-103")
RE_WRITE = re.compile(r"Write start play")
RE_ONDATA = re.compile(r"onData id=")
RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WLINE = re.compile(r"\bW/NMCache\b")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def elf64_rodata(data: bytes) -> dict:
    """纯 stdlib 解析 ELF64 section header，返回 .rodata 的 addr/offset/size。"""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError("不是 ELF64 文件")
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]

    def sh(i: int) -> dict:
        off = e_shoff + i * e_shentsize
        name, _typ, _flags, addr, offset, size = struct.unpack_from(
            "<IIQQQQ", data, off)
        return {"name": name, "addr": addr, "offset": offset, "size": size}

    strtab = sh(e_shstrndx)

    def sname(ent: dict) -> str:
        base = strtab["offset"] + ent["name"]
        end = data.find(b"\x00", base)
        return data[base:end].decode("ascii", "replace")

    for i in range(e_shnum):
        sec = sh(i)
        if sname(sec) == ".rodata":
            return sec
    raise ValueError("未找到 .rodata")


def rodata_strings() -> dict:
    data = SO.read_bytes()
    sec = elf64_rodata(data)
    out = {}
    for addr, anchor, trigger, ret, kind in W_BRANCHES:
        off = sec["offset"] + (addr - sec["addr"])
        end = data.find(b"\x00", off)
        raw = data[off:end].decode("utf-8", "replace")
        out["0x%X" % addr] = {
            "raw_format_string": raw,
            "anchor": anchor,
            "anchor_in_real_string": anchor in raw,
            "trigger": trigger,
            "native_return": ret,
            "category": kind,
        }
    return {"sha256": hashlib.sha256(data).hexdigest(), "branches": out}


def analyse(text: str, ro: dict) -> dict:
    w_hits = {}
    for addr, meta in ro["branches"].items():
        w_hits[addr] = text.count(meta["anchor"])
    gens = [int(x) for x in RE_GEN.findall(text)]
    insts = [int(x) for x in RE_INST.findall(text)]
    commits = [int(x) for x in RE_COMMIT.findall(text)]
    return {
        "lines": len(text.splitlines()),
        "w_level_lines": len(RE_WLINE.findall(text)),
        "w_branch_hits": w_hits,
        "w_error_total": sum(v for a, v in w_hits.items()
                             if ro["branches"][a]["category"] == "error"),
        "w_success_finish": w_hits.get("0x3D0AE", 0),
        "generation_sequence": gens,
        "generation_strictly_increasing": bool(gens)
        and gens == list(range(gens[0], gens[0] + len(gens))),
        "instance_sequence_max": max(insts) if insts else 0,
        "commit_values": sorted(set(commits)),
        "commit_frozen": len(set(commits)) == 1,
        "read_result_minus_2": len(RE_READ2.findall(text)),
        "finish_async_minus_103": len(RE_103.findall(text)),
        "java_on_data": len(RE_ONDATA.findall(text)),
        "write_start_play": len(RE_WRITE.findall(text)),
        "open_codec": RE_CODEC.findall(text),
    }


def live_verdict(a: dict) -> str:
    """只判读已有 logcat；出现 W 分支日志 ≠ F4 修复或验收通过。"""
    hits = a["w_branch_hits"]
    if hits.get("0x3CCC9", 0) > 0:
        return "GENERATION_MISMATCH_OBSERVED（commit != offset 的 W 级一手日志出现；须人工复核后再决定是否升级推断）"
    if hits.get("0x3CF90", 0) > 0 or any(
            hits.get(k, 0) > 0 for k in ("0x3CFCE", "0x3D062", "0x3D017")):
        return "FILE_LEN_GATE_OBSERVED（NMSlicesFile Write 边界分支日志出现；须人工复核）"
    if a["w_success_finish"] > 0:
        return "NORMAL_PATH_ONLY_W_CONFIRMED（默认 g_level=0 下 W 级可输出；错误分支未触发）"
    return "NO_W_BRANCH_TRIGGERED_EFFECTIVE_NEGATIVE（有效负结果：分支未触发，不是日志被门控）"


def main() -> int:
    ap = argparse.ArgumentParser(
        description="F4 上线复跑 W 分支判读（纯离线，不连 adb）")
    ap.add_argument("--replay", action="store_true",
                    help="回放 analysis/l3-f4-logs/ 全部归档（默认开启）")
    ap.add_argument("--log", default="",
                    help="单份 logcat 的上线判读（如探针采集的日志）；本工具不采集")
    ap.add_argument("--out", default=None,
                    help="JSON 输出路径；默认：回放写 l3-f4-wbranch-recheck.json，"
                         "--log 单日志判读写 l3-f4-wbranch-live-verdict.json，"
                         "两者分离以免覆盖已固化的回放证据")
    args = ap.parse_args()

    if not SO.is_file():
        print("[FAIL] 缺少 %s" % SO)
        return 1
    ro = rodata_strings()
    logs = sorted(LOGDIR.glob("*.txt"))
    if not logs:
        print("[FAIL] 归档 logcat 目录为空：%s" % LOGDIR)
        return 1

    per_log = {}
    for p in logs:
        per_log[p.name] = analyse(read(p), ro)

    totals = {}
    for addr in ro["branches"]:
        totals[addr] = sum(v["w_branch_hits"][addr] for v in per_log.values())

    r1, r2 = per_log.get(RUN1, {}), per_log.get(RUN2, {})
    finish_in_default = per_log.get(FINISH_LOG, {}).get("w_success_finish", 0)
    probe_src = read(PROBE)
    tool_src = read(Path(__file__))
    report = read(REPORT)
    # 拼接 marker，避免「检查用的字面串本身就出现在被检查的源码里」导致恒 false。
    no_adb_markers = ("import sub" + "process", "sub" + "process.run",
                      "tools/bin/ad" + "b", "ad" + "b shell")

    checks = {
        "rodata_7_real_strings_extracted": (
            len(ro["branches"]) == 7
            and all(v["anchor_in_real_string"] for v in ro["branches"].values())
            and all(v["raw_format_string"] for v in ro["branches"].values())),
        "six_error_branch_strings_zero_in_archives": all(
            totals[a] == 0 for a, v in ro["branches"].items()
            if v["category"] == "error"),
        "write_all_finish_seen_in_default_config": finish_in_default >= 1,
        "w_finish_only_in_flac_archive": sum(
            1 for v in per_log.values() if v["w_success_finish"] > 0) == 1,
        "run1_generation_1_to_22": (
            r1.get("generation_sequence") == list(range(1, 23))),
        "run1_commit_frozen_16384": r1.get("commit_values") == [16384],
        "run1_has_minus_103": r1.get("finish_async_minus_103", 0) >= 1,
        "run1_no_write_start_play": r1.get("write_start_play", 1) == 0,
        "run2_commit_frozen_16384": r2.get("commit_values") == [16384],
        "probe_script_lacks_wbranch_reader": all(
            v["anchor"] not in probe_src for v in ro["branches"].values()),
        "tool_never_connects_adb": all(m not in tool_src for m in no_adb_markers),
        "report_records_f4_blocked": "F4 仍 BLOCKED" in report,
        "report_no_false_claim": not any(
            s in report for s in ("F4 已解决", "m4a 已支持", "全格式已支持",
                                  "level-5 已开启", "推断已升级为实证")),
    }
    failed = [k for k, v in checks.items() if not v]

    single = None
    if args.log:
        p = Path(args.log)
        if not p.is_absolute():
            p = ROOT / p
        if p.is_file():
            a = analyse(read(p), ro)
            single = {"file": str(p.relative_to(ROOT)).replace("\\", "/"),
                      "analysis": a, "live_verdict": live_verdict(a),
                      "device_run": False,
                      "note": "本工具只离线判读已有 logcat；真机采集由 "
                              "tools/l3_f4_vfile_commit_probe.py 完成，"
                              "且须用户先让设备上线。"}
        else:
            print("[FAIL] --log 文件不存在：%s" % p)
            return 1

    data = {
        "method": "12 §61.71 / F4 上线复跑 W 分支判读（纯离线、零第三方依赖、不连 adb）",
        "generated_by": "tools/l3_f4_wbranch_recheck.py",
        "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
        "so_sha256": ro["sha256"],
        "rodata_branches": ro["branches"],
        "mode": "replay" if not single else "single_log",
        "evidence_role": (
            "固化回放证据（--log 单日志判读另写 l3-f4-wbranch-live-verdict.json）"
            if not single else
            "单份 logcat 离线判读（不覆盖固化回放证据）"),
        "logs_scanned": len(logs),
        "archive_totals_by_branch": totals,
        "per_log": per_log,
        "single_log_verdict": single,
        "checks": checks,
        "checks_total": len(checks),
        "all_pass": not failed,
        "failed_checks": failed,
        "f4_status": "BLOCKED",
        "device_run": False,
        "truthfulness": (
            "all_pass 只表示离线一致性核验通过（真实 .rodata 串、归档 0 命中、"
            "代次 1→22 / commit 16384 与 §61.68 一致），不是 F4 验收通过；"
            "真机复跑（保持 g_level=0 + 主动 seek）仍 NOT RUN；"
            "接线回滚待用户拍板；设备仍完全离线。"),
        "usage_online": (
            "设备上线后：python tools/l3_f4_vfile_commit_probe.py "
            "--serial <新端点> --wait 60 --out analysis/l3-f4-live-online.json；"
            "再 python tools/l3_f4_wbranch_recheck.py --log "
            "analysis/l3-f4-logs/<该次 logcat>。保持 g_level=0、播放中主动 seek，"
            "不要再用 level-5 关键词或 nmcache_log_level 做 0/4/5 对照。"),
    }
    default_out = LIVE_OUT if single else OUT
    out_rel = args.out if args.out else str(default_out.relative_to(ROOT))
    out = ROOT / out_rel if not Path(out_rel).is_absolute() else Path(out_rel)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    try:
        shown_out = str(out.relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        # --out 允许绝对路径 / 仓库外路径（例如临时烟测），打印不能因此崩溃。
        shown_out = str(out).replace("\\", "/")
    print("[INFO] 输出 %s（归档 %d 份，checks %d 项）"
          % (shown_out, len(logs), len(checks)))
    if failed:
        print("[FAIL] §61.71 W 分支判读一致性未通过：%s" % failed)
        return 1
    print("[PASS] §61.71 W 分支判读：%d 项 checks 全 PASS；6 条错误分支格式串"
          "在 %d 份归档中 0 命中，Write all finish 默认配置下可见；F4 仍 BLOCKED"
          % (len(checks), len(logs)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
