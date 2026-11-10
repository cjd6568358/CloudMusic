#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.70 只读审计：libNMCommonCache.so 日志门控语义纠正。

纯离线（不连设备、不改网络、不改任何生产源码）。依赖 capstone + pyelftools，
仅读取 engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so 与
analysis/l3-f4-logs/ 下既有 logcat，再核对文档口径，输出：

    analysis/l3-f4-level5-gate-semantics-audit.json

核心命题（第九十七批）：
  getter 0x37e20 = (g_level > N)，N 与同块 logfn 0x3138c 首参 M 恒等，
  M 即消息优先级（3=D / 4=I / 5=W / 6=E）；g_level 默认 0（.bss 零初值 +
  全库唯一 setter + .init_array 无写），故 I/D/W/E 全部默认已输出。
  §61.68 两轮 "level-5 关键词全 0" 不是日志未开启，而是 W 级分支未触发；
  §61.69 的 SetLoggerCallback(..., level=5) 会抑制 I/D、对 W 级零增益。
"""
from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SO = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so"
LOGDIR = ROOT / "analysis/l3-f4-logs"
OUT = ROOT / "analysis/l3-f4-level5-gate-semantics-audit.json"

GETTER, LOGFN, LEVEL_GLOBAL = 0x37E20, 0x3138C, 0x4C388
SETTER, JNI_SETLOGGER, PLT_LOG = 0x37E34, 0x32D64, 0x8A70
TAG_ADDR, METHOD_ADDR, SIG_ADDR, FMT_ADDR = 0x3B5F0, 0x3EAC2, 0x3EAC9, 0x3C000 + 0xA4F

F4_GETTERS = (0x107BC, 0x10804, 0x1282C, 0x12874, 0x12AC8, 0x12AFC, 0x12C48)
PRIO4_LOGS = (0x10224, 0x10274, 0x102EC, 0x10528)
PRIO5_LOGS = (0x107F0, 0x1083C, 0x1285C, 0x12A08, 0x12A3C, 0x12AE4, 0x12B28)
F4_GATE_STRINGS = (
    "NMSlicesFile Write buffer len", "NMSlicesFile::Read buffer len",
    "OnWriteNetData SKIP", "OnWriteNetData TRIM", "::OnData() offset",
    "UnExpect New Offsetcommted", "file_len:",
)
LOG1 = "vfile-commit-20261111-004310.txt"
LOG2 = "vfile-commit-20261111-004603.txt"


def read(rel: str) -> str:
    p = ROOT / rel
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def elf_facts():
    from elftools.elf.elffile import ELFFile
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

    with SO.open("rb") as fp:
        elf = ELFFile(fp)
        tsec = elf.get_section_by_name(".text")
        rsec = elf.get_section_by_name(".rodata")
        tdata, taddr = tsec.data(), tsec["sh_addr"]
        rdata, raddr = rsec.data(), rsec["sh_addr"]
        dynsec = elf.get_section_by_name(".dynsym")
        rela = elf.get_section_by_name(".rela.plt")
        pltsec = elf.get_section_by_name(".plt")
        plt_data = pltsec.data() if pltsec is not None else b""
        plt_addr = pltsec["sh_addr"] if pltsec is not None else 0
        inits = elf.get_section_by_name(".init_array")
        init_data = inits.data() if inits is not None else b""
        rela_by_got = {}
        if rela is not None:
            for r in rela.iter_relocations():
                rela_by_got[r["r_offset"]] = dynsec.get_symbol(r["r_info_sym"]).name
        bss_owner = None
        for sec in elf.iter_sections():
            if sec["sh_addr"] <= LEVEL_GLOBAL < sec["sh_addr"] + sec["sh_size"]:
                bss_owner = (sec.name, sec["sh_type"])

    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    ins = list(md.disasm(tdata, taddr))
    byaddr = {i.address: i for i in ins}

    def cstr(addr):
        off = addr - raddr
        if not (0 <= off < len(rdata)):
            return None
        end = rdata.find(b"\x00", off)
        s = rdata[off:end if end >= 0 else off + 120]
        if not s or any(c < 32 or c > 126 for c in s):
            return None
        return s.decode("ascii")

    def imm_w0(i):
        m = re.match(r"w0, #(0x[0-9a-f]+|\d+)$", i.op_str.strip())
        if not m:
            return None
        v = m.group(1)
        return int(v, 16) if v.startswith("0x") else int(v)

    # PLT stub -> GOT slot -> import symbol
    got_slot = None
    poff = PLT_LOG - plt_addr
    for si in md.disasm(plt_data[poff:poff + 16], PLT_LOG):
        if si.mnemonic == "adrp":
            page = int(re.search(r"#(0x[0-9a-f]+)", si.op_str).group(1), 16)
        m = re.match(r"x17, \[x16, #(0x[0-9a-f]+)\]", si.op_str.strip())
        if si.mnemonic == "ldr" and m and "x17" in si.op_str:
            got_slot = page + int(m.group(1), 16)
    plt_symbol = rela_by_got.get(got_slot)

    # getter / setter / tail call
    getter_body = " ".join("%s %s" % (byaddr[a].mnemonic, byaddr[a].op_str)
                          for a in range(GETTER, GETTER + 0x10, 4) if a in byaddr)
    setter_body = " ".join("%s %s" % (byaddr[a].mnemonic, byaddr[a].op_str)
                          for a in range(SETTER, SETTER + 0x10, 4) if a in byaddr)
    tail = byaddr.get(0x32D88)
    tail_ok = bool(tail and tail.mnemonic == "b" and "#0x37e34" in tail.op_str)

    # all writes to 0x4c388 (direct #0x388 form and base+offset ADD form)
    writers = []
    for n, i in enumerate(ins):
        if i.mnemonic == "adrp" and "#0x4c000" in i.op_str:
            reg = i.op_str.split(",")[0].strip()
            for j in ins[n + 1:n + 10]:
                if j.mnemonic == "adrp":
                    break
                if "#0x388" in j.op_str and reg in j.op_str:
                    if j.mnemonic in ("str", "stur", "stp"):
                        writers.append((j.address, j.mnemonic, j.op_str))
                    break
                m = re.match(r"%s, %s, #(0x[0-9a-f]+)" % (reg, reg), j.op_str.strip())
                if m and int(m.group(1), 16) == 0x388:
                    writers.append((j.address, "ADD-form", j.op_str))
                    break

    # .init_array ctors: any 0x388 access in their bodies
    init_level_hits = []
    for o in range(0, len(init_data), 8):
        fn = int.from_bytes(init_data[o:o + 8], "little")
        if fn not in byaddr:
            continue
        n0 = next(k for k, i in enumerate(ins) if i.address == fn)
        for i in ins[n0:n0 + 120]:
            if i.mnemonic == "ret":
                break
            if "#0x388" in i.op_str or "0x4c388" in i.op_str:
                init_level_hits.append((i.address, i.op_str))

    # getter call sites: (N, M, branch)
    getter_calls = [n for n, i in enumerate(ins)
                    if i.mnemonic in ("bl", "b") and i.op_str.strip() == "#0x%x" % GETTER]
    logfn_calls = [n for n, i in enumerate(ins)
                   if i.mnemonic in ("bl", "b") and i.op_str.strip() == "#0x%x" % LOGFN]
    rows = []
    for n in getter_calls:
        N = None
        for j in range(max(0, n - 8), n):
            if ins[j].mnemonic == "mov" and ins[j].op_str.startswith("w0"):
                v = imm_w0(ins[j])
                if v is not None:
                    N = v
        br = ""
        for j in range(n + 1, min(n + 4, len(ins))):
            if ins[j].mnemonic in ("tbz", "tbnz", "cbz", "cbnz"):
                br = ins[j].mnemonic
                break
        M = None
        for j in range(n + 1, min(n + 40, len(ins))):
            if ins[j].mnemonic in ("bl", "b") and ins[j].op_str.strip() == "#0x%x" % LOGFN:
                for k in range(max(n + 1, j - 12), j):
                    if ins[k].mnemonic == "mov" and ins[k].op_str.startswith("w0"):
                        v = imm_w0(ins[k])
                        if v is not None:
                            M = v
                break
        rows.append({"addr": ins[n].address, "N": N, "M": M, "branch": br})
    rowmap = {r["addr"]: r for r in rows}

    # logfn call sites: prio + format string
    logmap = {}
    for n in logfn_calls:
        M, fmt = None, None
        for k in range(max(0, n - 14), n):
            if ins[k].mnemonic == "mov" and ins[k].op_str.startswith("w0"):
                v = imm_w0(ins[k])
                if v is not None:
                    M = v
            if ins[k].mnemonic == "adrp" and ("#0x3c000" in ins[k].op_str
                                             or "#0x3d000" in ins[k].op_str):
                reg = ins[k].op_str.split(",")[0].strip()
                page = int(re.search(r"#(0x[0-9a-f]+)", ins[k].op_str).group(1), 16)
                for q in range(k + 1, min(k + 8, n)):
                    m2 = re.match(r"%s, %s, #(0x[0-9a-f]+)" % (reg, reg),
                                  ins[q].op_str.strip())
                    if m2:
                        s = cstr(page + int(m2.group(1), 16))
                        if s:
                            fmt = s
                        break
        logmap[ins[n].address] = {"prio": M, "fmt": fmt}

    return {
        "so_size": SO.stat().st_size,
        "so_sha256": hashlib.sha256(SO.read_bytes()).hexdigest(),
        "bss_owner": bss_owner,
        "getter_body": getter_body,
        "setter_body": setter_body,
        "tail_ok": tail_ok,
        "plt_symbol": plt_symbol,
        "got_slot": got_slot,
        "tag": cstr(TAG_ADDR),
        "method": cstr(METHOD_ADDR),
        "sig": cstr(SIG_ADDR),
        "fmt_pct_s": cstr(FMT_ADDR),
        "writers": writers,
        "init_level_hits": init_level_hits,
        "getter_count": len(getter_calls),
        "logfn_count": len(logfn_calls),
        "rows": rows,
        "rowmap": rowmap,
        "logmap": logmap,
    }


def logcat_facts():
    totals = Counter()
    priorities = Counter()
    gate_hits = Counter()
    files = sorted(LOGDIR.glob("*.txt"))
    w_write_all_finish = 0
    per_file = {}
    for fp in files:
        t = fp.read_text(encoding="utf-8", errors="replace")
        pr = Counter(re.findall(r" ([VDIWEF])/NMCache", t))
        priorities.update(pr)
        hits = {k: t.count(k) for k in F4_GATE_STRINGS}
        for k, v in hits.items():
            gate_hits[k] += v
        for k in ("MakeRangeRequestAsync", "WaitRequest", "Finish Async:-103"):
            totals[k] += t.count(k)
        w = len(re.findall(r"W/NMCache.*NMSlicesFile Write all finish", t))
        w_write_all_finish += w
        per_file[fp.name] = {"lines": len(t.splitlines()), "prio": dict(pr),
                             "w_write_all_finish": w,
                             "gate_hits": {k: v for k, v in hits.items() if v}}
    return {"file_count": len(files), "priorities": dict(priorities),
            "totals": dict(totals), "gate_hits": dict(gate_hits),
            "w_write_all_finish": w_write_all_finish,
            "per_file": per_file,
            "log1_present": (LOGDIR / LOG1).is_file(),
            "log2_present": (LOGDIR / LOG2).is_file()}


def main():
    e = elf_facts()
    lc = logcat_facts()

    d12 = read("docs/12-编码阶段第一批交付.md")
    d09 = read("docs/09-文档闭环总览.md")
    d16 = read("docs/16-架构与任务拆分复核报告.md")
    d17 = read("docs/17-竞品模块清单与复刻规划.md")
    bl = read("analysis/task-backlog-2026-11-07.md")
    rb = read("analysis/l3-f4-online-runbook.md")
    rep = read("analysis/l3-f4-level5-gate-semantics-report.md")
    ea = read("engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt")

    def jall(rel):
        try:
            return json.loads((ROOT / rel).read_text(encoding="utf-8"))
        except Exception:
            return {}

    g68 = jall("analysis/l3-f4-generation-gate-live-audit.json")
    g69 = jall("analysis/l3-f4-level5-wiring-audit.json")

    nm_pairs = Counter((r["N"], r["M"]) for r in e["rows"])
    identical = sum(v for (n, m), v in nm_pairs.items() if n is not None and n == m)
    ratio = identical / max(1, e["getter_count"])
    branches = Counter(r["branch"] for r in e["rows"])

    f4_rows = [e["rowmap"].get(a) for a in F4_GETTERS]
    f4_all_l5 = all(r and r["N"] == 5 for r in f4_rows)
    prio4_ok = all(e["logmap"].get(a, {}).get("prio") == 4 for a in PRIO4_LOGS)
    prio5_ok = all(e["logmap"].get(a, {}).get("prio") == 5 for a in PRIO5_LOGS)

    gate_all_zero = all(lc["gate_hits"].get(k, 0) == 0 for k in F4_GATE_STRINGS)

    checks = {
        # --- binary (10) ---
        "so_sha256_recorded": len(e["so_sha256"]) == 64,
        "level_global_in_bss_nobits": (e["bss_owner"] is not None
                                       and e["bss_owner"][0] == ".bss"
                                       and "NOBITS" in str(e["bss_owner"][1])),
        "level_global_single_writer": (len(e["writers"]) == 1
                                       and e["writers"][0][0] == SETTER + 4),
        "init_array_no_level_write": len(e["init_level_hits"]) == 0,
        "getter_is_strict_gt": ("cmp" in e["getter_body"] and "cset" in e["getter_body"]
                                and "gt" in e["getter_body"]),
        "jni_tailcalls_setter": e["tail_ok"],
        "plt_0x8a70_is_android_log_print": e["plt_symbol"] == "__android_log_print",
        "tag_is_nmcache": e["tag"] == "NMCache",
        "logger_method_is_biinfo": e["method"] == "BIInfo",
        "logger_sig_is_int_string_void": e["sig"] == "(ILjava/lang/String;)V",
        # --- semantics (6) ---
        "call_site_counts": e["getter_count"] >= 170 and e["logfn_count"] >= 150,
        "nm_identity_ratio_ge_093": ratio >= 0.93,
        "branch_types_tbnz_and_tbz": branches.get("tbnz", 0) > 100 and branches.get("tbz", 0) > 20,
        "f4_regions_all_level5": f4_all_l5,
        "waitrequest_makerange_prio4": prio4_ok,
        "ondata_slicesfile_prio5": prio5_ok,
        # --- logcat (5) ---
        "w_nmcache_write_all_finish_exists": lc["w_write_all_finish"] >= 1,
        "f4_gate_strings_all_zero": gate_all_zero,
        "i_and_d_priorities_present": (lc["priorities"].get("I", 0) > 0
                                       and lc["priorities"].get("D", 0) > 0),
        "two_round_logs_present": lc["log1_present"] and lc["log2_present"],
        "i_level_evidence_present": (lc["totals"].get("MakeRangeRequestAsync", 0) > 0
                                     and lc["totals"].get("WaitRequest", 0) > 0),
        # --- docs (7) ---
        "d12_section_6170": "### 61.70" in d12,
        "d12_errata_for_6168_6169": d12.count("§61.70 勘误") >= 2,
        "d17_section_830": "### 8.30" in d17,
        "d09_d16_batch97": "61.70" in d09 and "61.70" in d16,
        "backlog_batch97": "61.70" in bl,
        "runbook_6170": "61.70" in rb and "完全离线" in rb,
        "report_present": len(rep) > 1000,
        # --- honesty (6) ---
        "f4_still_blocked": "F4 仍 BLOCKED" in d12 and "F4 仍 BLOCKED" in d17,
        "no_false_claims": not any(s in rep for s in (
            "F4 已解决", "F4 已修复", "level-5 已开启", "level-5 已解锁", "全格式已支持")),
        "level5_harmful_registered": ("抑制" in rep and "无效" in rep
                                      and "I/D" in rep and "零增益" in rep),
        "rollback_pending_user": ("回滚" in d12 and "待用户拍板" in d12),
        "wiring_still_in_place": ("SetLoggerCallback(INMLogger(), level)" in ea
                                  and "coerceIn(0, 5)" in ea),
        "history_audits_intact": (g68.get("all_pass") is True
                                  and g69.get("all_pass") is True),
    }

    groups = {
        "binary": [k for k in checks if k in (
            "so_sha256_recorded", "level_global_in_bss_nobits", "level_global_single_writer",
            "init_array_no_level_write", "getter_is_strict_gt", "jni_tailcalls_setter",
            "plt_0x8a70_is_android_log_print", "tag_is_nmcache",
            "logger_method_is_biinfo", "logger_sig_is_int_string_void")],
        "semantics": [k for k in checks if k in (
            "call_site_counts", "nm_identity_ratio_ge_093", "branch_types_tbnz_and_tbz",
            "f4_regions_all_level5", "waitrequest_makerange_prio4", "ondata_slicesfile_prio5")],
        "logcat": [k for k in checks if k in (
            "w_nmcache_write_all_finish_exists", "f4_gate_strings_all_zero",
            "i_and_d_priorities_present", "two_round_logs_present", "i_level_evidence_present")],
        "docs": [k for k in checks if k in (
            "d12_section_6170", "d12_errata_for_6168_6169", "d17_section_830",
            "d09_d16_batch97", "backlog_batch97", "runbook_6170", "report_present")],
        "honesty": [k for k in checks if k in (
            "f4_still_blocked", "no_false_claims", "level5_harmful_registered",
            "rollback_pending_user", "wiring_still_in_place", "history_audits_intact")],
    }
    group_counts = {g: (sum(1 for k in ks if checks[k]), len(ks))
                    for g, ks in groups.items()}
    failed = [k for k, v in checks.items() if not v]

    payload = {
        "batch": "docs/12 §61.70 / 第九十七批",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "mode": "pure-offline / read-only / no-device / no-network-change / no-P0-edit",
        "artifacts": {
            "so_relpath": str(SO.relative_to(ROOT)).replace("\\", "/"),
            "so_size": e["so_size"], "so_sha256": e["so_sha256"],
            "log_files_scanned": lc["file_count"],
        },
        "findings": {
            "level_global": hex(LEVEL_GLOBAL),
            "bss_owner": list(e["bss_owner"]) if e["bss_owner"] else None,
            "level_global_writers": [[hex(a), m, o] for a, m, o in e["writers"]],
            "init_array_level_writes": len(e["init_level_hits"]),
            "default_g_level": 0,
            "getter": hex(GETTER), "getter_body": e["getter_body"],
            "setter": hex(SETTER), "setter_body": e["setter_body"],
            "jni_tailcall_to_setter": e["tail_ok"],
            "plt_stub": hex(PLT_LOG), "plt_got_slot": hex(e["got_slot"]) if e["got_slot"] else None,
            "plt_import": e["plt_symbol"], "log_tag": e["tag"],
            "log_format": e["fmt_pct_s"],
            "logger_method": e["method"], "logger_sig": e["sig"],
            "getter_call_sites": e["getter_count"], "logfn_call_sites": e["logfn_count"],
            "nm_identical": identical, "nm_identity_ratio": round(ratio, 4),
            "nm_pair_distribution": [{"N": n, "M": m, "count": v}
                                     for (n, m), v in nm_pairs.most_common()],
            "branch_distribution": dict(branches),
            "f4_getters": [{"addr": hex(a), "N": e["rowmap"][a]["N"],
                            "M": e["rowmap"][a]["M"], "branch": e["rowmap"][a]["branch"]}
                           for a in F4_GETTERS if a in e["rowmap"]],
            "prio4_log_sites": [{"addr": hex(a), "fmt": e["logmap"].get(a, {}).get("fmt")}
                                for a in PRIO4_LOGS],
            "prio5_log_sites": [{"addr": hex(a), "fmt": e["logmap"].get(a, {}).get("fmt")}
                                for a in PRIO5_LOGS],
            "gate_semantics": "log emitted iff g_level <= N; default 0 => all D/I/W/E emitted; "
                              "level=5 suppresses D(3)/I(4) and keeps W(5)/E(6); "
                              "level>=6 suppresses W and alters tbz early-return error codes",
        },
        "logcat_evidence": {
            "priorities": lc["priorities"], "totals": lc["totals"],
            "f4_gate_string_hits": lc["gate_hits"],
            "w_nmcache_write_all_finish": lc["w_write_all_finish"],
            "two_round_files": [LOG1, LOG2],
            "per_file": lc["per_file"],
        },
        "conclusions": [
            "默认 g_level=0，libNMCommonCache.so 的 I/D/W/E 日志全部已输出，不存在需要额外开启的 level-5 开关",
            "§61.68 两轮 F4 门控关键词全 0 是有效负结果，但归因应为 W 级分支未触发，而非 level-5 未开启",
            "§61.69 的 SetLoggerCallback(INMLogger(), 5) 对 F4 目标 W 级日志零增益，且会抑制 I/D 级证据",
            "INMLogger 空类缺少 BIInfo(ILjava/lang/String;)V，native GetMethodID 必然失败并可能留下 JNI pending exception",
            "F4 仍 BLOCKED：断点性质从等设备开日志改为构造能触发 W 级分支的输入，或接受负结果重新定性",
        ],
        "groups": groups, "group_counts": group_counts,
        "checks": checks, "checks_total": len(checks),
        "checks_pass": len(checks) - len(failed),
        "all_pass": not failed, "failed_checks": failed,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[%s] 12 §61.70 门控语义审计：%d/%d PASS -> %s" %
          ("PASS" if not failed else "FAIL", len(checks) - len(failed),
           len(checks), OUT.relative_to(ROOT)))
    if failed:
        for k in failed:
            print("  FAIL:", k)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
