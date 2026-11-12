#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z 组差分验证 —— 用 Unicorn（QEMU TCG 内核）当 ARM64 参照真值。

为什么需要这一层：
  `z_transpile.py` 的判据只回答「有没有漏翻译」，`z_audit.py` 只回答
  「op 分桶有没有张冠李戴」。两者都不回答「翻译出来的语义对不对」。
  向量错桶（`add v0.16b` 落进标量桶）正是「已翻译、可执行、但语义错」的样本，
  当时靠人工规则才抓出来。本工具换一条正交通道：

    把同一段机器码分别喂给
      ① Unicorn（独立实现的 ARM64 模拟器，QEMU TCG 后端）
      ② 本目录 Kotlin 逐行翻译 + NmArm64 解释器
    在同一初始状态下跑同样的指令数，逐个比对架构状态。

比对字段（架构状态全量）：
  x0..x29 / SP / NZCV / v0..v31（128 位逐位）/ 栈区内存 FNV-1a 摘要

口径（双方必须完全一致，否则比对无意义）：
  x0..x29 = 0，SP = 0x2000_8000，x30 = RET_SENTINEL
  v0..v31 = 0，FPCR = FPSR = 0，内存全零
  so 的全部 PT_LOAD 段按 RWX 映射（照加载器语义），栈区 128 KiB 另映射

停机约定（关键）：
  RET_SENTINEL 未映射 ⇒ `ret` 在 Unicorn 上表现为取指 fault，天然停机。
  逐条单步执行，fault 时 effN = 已成功执行的指令数；Kotlin 侧用同一 effN
  作预算，于是两边都停在「第 effN+1 条」之前。
  这样即使某条指令访问了 Unicorn 未映射的地址（我们的稀疏内存不 fault），
  该指令也不会在任一侧被执行，比对仍然是干净的。

用法：
  python tools/z_diff.py --pilot              # 4 域各 4 个入口，先验口径
  python tools/z_diff.py                      # 4 域各 60 个入口，全量差分
  python tools/z_diff.py --per-domain 120 --max-insn 300
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
ROOT = RE.parent
JNI = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
KT = RE / "kt" / "src"
OUT = RE / "spec" / "z-diff.json"
CLASSES = RE / "build" / "classes"

SO = [("libNMCommonCache.so", "cache"), ("libncmplayerengine.so", "engine"),
      ("libncmaudioplayer.so", "audio"), ("libaudioplayer.so", "audio-legacy")]

SP_INIT = 0x2000_8000
SCRATCH = 0x2000_0000
SCRATCH_SIZE = 0x2_0000
RET_SENTINEL = (-0x2152_FFFF_0000_0000) & 0xFFFF_FFFF_FFFF_FFFF

FNV_OFF = 0xCBF2_9CE4_8422_2325
FNV_PRIME = 0x100_0000_01B3
M64 = 0xFFFF_FFFF_FFFF_FFFF


def _uc_version() -> str:
    try:
        import unicorn  # noqa: F401
        return getattr(unicorn, "__version__", "?")
    except Exception:  # noqa: BLE001
        return "?"


def fnv(seed: int, b: int) -> int:
    return ((seed ^ (b & 0xFF)) * FNV_PRIME) & M64


def digest(blob: bytes) -> int:
    h = FNV_OFF
    for b in blob:
        h = fnv(h, b)
    return h


def elf_load_segments(data: bytes):
    """返回 [(vaddr, filesz, memsz, flags, offset)]，只取 PT_LOAD。"""
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    segs = []
    for i in range(phnum):
        o = phoff + i * phentsize
        p_type, p_flags, p_off, p_vaddr, _pa, p_filesz, p_memsz, _al = \
            struct.unpack_from("<IIQQQQQQ", data, o)
        if p_type == 1 and p_memsz:
            segs.append((p_vaddr, p_filesz, p_memsz, p_flags, p_off))
    return segs


def elf_text_range(data: bytes):
    """返回 (.text 起, 止)。比对只在 .text 内进行。

    为什么必须限制：`.text` 里的尾调用会 `b` 到 `.plt` 桩（adrp/ldr/add/br），
    桩再 `br x17` 跳到外部符号。我们的翻译集只覆盖 `.text`，而 Unicorn 在
    `.plt` 有映射、会继续把桩当代码跑下去，于是两边步数必然对不上。
    把「离开 .text」定为共同停机点后，比较才落在同一语义边界内。
    """
    off = struct.unpack_from("<Q", data, 0x28)[0]
    esz, num, strndx = struct.unpack_from("<HHH", data, 0x3A)
    secs = [struct.unpack_from("<IIQQQQ", data, off + i * esz)
            for i in range(num)]
    sh_off = secs[strndx][4]
    for nm, _t, _f, addr, _o2, sz in secs:
        e = data.find(b"\x00", sh_off + nm)
        if data[sh_off + nm:e] == b".text":
            return addr, addr + sz
    raise RuntimeError("no .text")


ENTRY_RE = re.compile(
    r'fun nf_([a-z_0-9]+)\(st: NmArmState\): Long = '
    r'NmMachine\(st\)\.call\("([a-z\-]+)", (\d+)L\)')


def load_entries():
    """从生成的 NmFuncs*.kt 取 (domain, entry) 全集，避免重跑转译。"""
    out = {}
    for p in sorted(KT.glob("NmFuncs*.kt")):
        for ln in p.read_text(encoding="utf-8").splitlines():
            m = ENTRY_RE.search(ln)
            if m:
                out.setdefault(m.group(2), []).append(int(m.group(3)))
    for k in out:
        out[k] = sorted(set(out[k]))
    return out


def build_cases(per_domain: int, max_insn: int, kmax: int = 5000):
    entries = load_entries()
    cases = []
    for _f, dom in SO:
        addrs = entries.get(dom, [])
        if not addrs:
            continue
        n = min(per_domain, len(addrs))
        step = max(1, len(addrs) // n)
        for a in addrs[::step][:n]:
            cases.append({"domain": dom, "entry": a, "maxInsn": max_insn, "kmax": kmax})
    return cases


# ---------------- Unicorn 侧 ----------------

def q_split(val) -> tuple:
    """Unicorn 2.x 的 Q 寄存器读写都是 128 位整数（旧版给 bytes），两种都接。"""
    if isinstance(val, (bytes, bytearray)):
        b = bytes(val)
        return int.from_bytes(b[0:8], "little"), int.from_bytes(b[8:16], "little")
    return val & M64, (val >> 64) & M64


def unicorn_selftest(uc, consts) -> None:
    """确认 X0..X28 / Q0..Q31 寄存器编号连续，否则后面的整体比对全是垃圾。"""
    x0, q0, x5, q3 = consts
    uc.reg_write(x0 + 5, 0x1122_3344_5566_7788)
    assert uc.reg_read(x5) == 0x1122_3344_5566_7788, "X 寄存器编号不连续"
    probe = (0x0102_0304_0506_0708 << 64) | 0x1112_1314_1516_1718
    uc.reg_write(q0 + 3, probe)
    assert uc.reg_read(q3) == probe, "Q 寄存器编号不连续"
    uc.reg_write(x0 + 5, 0)
    uc.reg_write(q0 + 3, 0)


def run_unicorn(so_path: Path, cases: list) -> list:
    from unicorn import (Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_PROT_ALL, UcError)
    from unicorn.arm64_const import (
        UC_ARM64_REG_X0, UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP,
        UC_ARM64_REG_NZCV, UC_ARM64_REG_Q0, UC_ARM64_REG_PC)
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

    # 外部依赖边界：系统寄存器读取（tpidr_el0 / cntvct_el0 等）与系统调用在
    # 真机上由内核提供，裸模拟器里没有对应状态。我们的解释器对它们是**刻意的
    # 确定性桩值**（见 Z-08），拿 Unicorn 的 0/宿主计数器去比没有意义，
    # 因此在执行到这类指令之前就停下，把它记成边界而不是差异。
    BOUNDARY = ("mrs", "msr", "svc", "hvc", "smc")
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)

    data = so_path.read_bytes()
    segs = elf_load_segments(data)
    tlo, thi = elf_text_range(data)
    out = []
    for c in cases:
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for vaddr, filesz, memsz, _fl, off in segs:
            base = vaddr & ~0xFFF
            end = (vaddr + memsz + 0xFFF) & ~0xFFF
            uc.mem_map(base, end - base, UC_PROT_ALL)
            uc.mem_write(vaddr, data[off:off + filesz])
        uc.mem_map(SCRATCH, SCRATCH_SIZE, UC_PROT_ALL)

        unicorn_selftest(uc, (UC_ARM64_REG_X0, UC_ARM64_REG_Q0,
                              UC_ARM64_REG_X0 + 5, UC_ARM64_REG_Q0 + 3))
        for i in range(29):
            uc.reg_write(UC_ARM64_REG_X0 + i, 0)
        uc.reg_write(UC_ARM64_REG_X29, 0)
        # 与 Kotlin 侧同口径：x30 = 哨兵（未映射），于是 `ret` 在 Unicorn 上
        # 表现为取指 fault，成为共同停机点；同时保证 stp x29,x30 存进去的值一致。
        uc.reg_write(UC_ARM64_REG_X30, RET_SENTINEL)
        uc.reg_write(UC_ARM64_REG_SP, SP_INIT)
        uc.reg_write(UC_ARM64_REG_NZCV, 0)
        for i in range(32):
            uc.reg_write(UC_ARM64_REG_Q0 + i, 0)

        pc = c["entry"]
        executed = 0
        stop = "count"
        pcs = []
        while executed < c["maxInsn"]:
            if not (tlo <= pc < thi):
                stop = "left-text:pc=%#x" % pc
                break
            here = next(md.disasm(data[pc:pc + 4], pc), None)
            if here is not None and here.mnemonic in BOUNDARY:
                stop = "boundary:%s" % here.mnemonic
                break
            try:
                uc.emu_start(pc, 0xFFFF_FFFF_FFFF_FFFF, 0, 1)
            except UcError as exc:
                stop = "fault:%s" % exc
                break
            if c.get("trace"):
                pcs.append(pc)
            executed += 1
            pc = uc.reg_read(UC_ARM64_REG_PC)
            if executed >= c["kmax"]:
                stop = "kmax"
                break

        xs = [uc.reg_read(UC_ARM64_REG_X0 + i) for i in range(29)]
        xs.append(uc.reg_read(UC_ARM64_REG_X29))
        sp = uc.reg_read(UC_ARM64_REG_SP)
        nzcv = uc.reg_read(UC_ARM64_REG_NZCV)
        flags = (((nzcv >> 31) & 1) | (((nzcv >> 30) & 1) << 1)
                 | (((nzcv >> 29) & 1) << 2) | (((nzcv >> 28) & 1) << 3))
        vs = []
        for i in range(32):
            lo, hi = q_split(uc.reg_read(UC_ARM64_REG_Q0 + i))
            vs.append(lo)
            vs.append(hi)
        mem = uc.mem_read(SCRATCH, SCRATCH_SIZE)
        out.append({
            "executed": executed, "stop": stop, "x": xs, "sp": sp,
            "flags": flags, "v": vs, "mem": digest(bytes(mem)),
            "lastPc": pc, "pcs": pcs,
        })
    return out


# ---------------- Kotlin 侧 ----------------

def run_kotlin(cases: list, eff: list, jvm_cp: str, java: str) -> list:
    lines = []
    for c, e in zip(cases, eff):
        flag = "\t1" if c.get("trace") else ""
        lines.append("%s\t%d\t%d\t%d%s" % (c["domain"], c["entry"], e, SP_INIT, flag))
    with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False,
                                     encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
        case_path = f.name
    so_args = ";".join("%s=%s" % (dom, (JNI / fname).as_posix())
                       for fname, dom in SO)
    cmd = [java, "-Dfile.encoding=UTF-8", "-cp", jvm_cp,
           "cloudmusic.reverse.kernel.NmDiffDump", case_path, so_args]
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    Path(case_path).unlink(missing_ok=True)
    if proc.returncode != 0:
        raise RuntimeError("kotlin harness failed:\n%s\n%s"
                           % (proc.stdout[-3000:], proc.stderr[-3000:]))
    rows = []
    pending_trace = None
    for ln in proc.stdout.splitlines():
        if ln.startswith("T\t"):
            body = ln[2:]
            pending_trace = [int(t) for t in body.split(",")] if body else []
            continue
        if not ln.startswith("R\t"):
            continue
        f = ln.split("\t")
        rows.append({
            "steps": int(f[1]),
            "sp": int(f[2]) & M64, "flags": int(f[3]),
            "x": [int(t) & M64 for t in f[4].split(",")],
            "v": [int(t) & M64 for t in f[5].split(",")],
            "mem": int(f[6]) & M64,
            "unresolved": int(f[7]), "budget": int(f[8]),
            "pcs": pending_trace or [],
        })
        pending_trace = None
    if len(rows) != len(cases):
        raise RuntimeError("harness returned %d rows for %d cases"
                           % (len(rows), len(cases)))
    return rows


def signed(v: int) -> int:
    return v - (1 << 64) if v >= (1 << 63) else v


def diff_one(e, g, first_bad_insn=6) -> list:
    """比较同一停点上的两份状态，返回差异描述列表（空 = 一致）。"""
    diff = []
    if g["steps"] != e["executed"]:
        diff.append("steps %d != %d" % (g["steps"], e["executed"]))
    for i, (a, b) in enumerate(zip(e["x"], g["x"])):
        if a != b:
            diff.append("x%d %#x != %#x" % (i, a, b & M64))
    if e["sp"] != g["sp"]:
        diff.append("sp %#x != %#x" % (e["sp"], g["sp"] & M64))
    if e["flags"] != g["flags"]:
        diff.append("nzcv %d != %d" % (e["flags"], g["flags"]))
    vbad = [i for i, (a, b) in enumerate(zip(e["v"], g["v"])) if a != b]
    if vbad:
        diff.append("v[%s] %#x != %#x" % (
            ",".join(str(i // 2) for i in vbad[:4]),
            e["v"][vbad[0]], g["v"][vbad[0]] & M64))
    if e["mem"] != g["mem"]:
        diff.append("mem %#x != %#x" % (e["mem"], g["mem"]))
    return diff[:first_bad_insn]


def compare(cases, exp, got, first_bad_insn=6) -> dict:
    details = []
    ok = 0
    for idx, (c, e, g) in enumerate(zip(cases, exp, got)):
        diff = diff_one(e, g, first_bad_insn)
        rec = {
            "idx": idx, "domain": c["domain"], "entry": c["entry"],
            "executed": e["executed"], "stop": e["stop"],
            "kotlinUnresolved": g["unresolved"], "kotlinBudget": g["budget"],
            "ok": not diff, "diff": diff,
        }
        details.append(rec)
        if not diff:
            ok += 1
    return {"total": len(cases), "ok": ok, "details": details}


def disasm_at(so_path: Path, addr: int, count: int = 1) -> list:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    data = so_path.read_bytes()
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    return ["%#x  %-8s %s" % (i.address, i.mnemonic, i.op_str)
            for i in md.disasm(data[addr:addr + 4 * count], addr)]


def trace_mismatches(bad, cases, jvm_cp, java):
    """对失败用例再跑一遍带 pc 轨迹的对比，定位第一条走岔的指令。"""
    if not bad:
        return {}
    by_dom = {}
    for idx, _d in bad:
        c = cases[idx]
        by_dom.setdefault(c["domain"], []).append(dict(c, trace=True))
    out = {}
    for fname, dom in SO:
        sub = by_dom.get(dom)
        if not sub:
            continue
        exp = run_unicorn(JNI / fname, sub)
        got = run_kotlin(sub, [e["executed"] for e in exp], jvm_cp, java)
        for c, e, g in zip(sub, exp, got):
            first = None
            for k, (a, b) in enumerate(zip(e["pcs"], g["pcs"])):
                if a != b:
                    first = k
                    break
            # 真正走岔的通常是**前一条**（分支条件判错），所以窗口给前 4 条 + 分歧点
            win = []
            if first is not None and first > 0:
                for k in range(max(0, first - 4), first):
                    win.extend(disasm_at(JNI / fname, e["pcs"][k]))
            info = {
                "traceLenOracle": len(e["pcs"]), "traceLenKotlin": len(g["pcs"]),
                "firstDivergence": first,
                "oraclePc": e["pcs"][first] if first is not None else None,
                "kotlinPc": g["pcs"][first] if first is not None else None,
                "asm": (disasm_at(JNI / fname, e["pcs"][first], 2)
                        if first is not None else
                        disasm_at(JNI / fname, e["pcs"][-1] if e["pcs"] else c["entry"])),
                "window": win,
                "oracleTail": [hex(x) for x in e["pcs"][-4:]],
                "kotlinTail": [hex(x) for x in g["pcs"][-4:]],
            }
            out[(c["domain"], c["entry"])] = info
    return out


def _stop_breakdown(details: list) -> dict:
    """按停机原因归类计数：`count` 是预算内正常跑完，其余都是边界/外部情形。"""
    out = {}
    for d in details:
        k = (d.get("stop") or "?").split(":")[0]
        out[k] = out.get(k, 0) + 1
    return dict(sorted(out.items()))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pilot", action="store_true")
    ap.add_argument("--per-domain", type=int, default=60)
    ap.add_argument("--max-insn", type=int, default=200)
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    per = 4 if args.pilot else args.per_domain
    maxi = 120 if args.pilot else args.max_insn

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build as buildmod  # noqa: E402

    java = buildmod.resolve_java()
    jars = buildmod.build_jars()
    jvm_cp = buildmod.sep().join(
        [str(CLASSES), str(jars["stdlib"]), str(jars["coroutines"])])
    if not CLASSES.exists():
        print("[DIFF] build/classes missing; run tools/build.py first")
        return 2

    cases = build_cases(per, maxi)
    total_entries = sum(len(v) for v in load_entries().values())
    print("[DIFF] cases=%d (perDomain=%d maxInsn=%d, 入口总数 %d)"
          % (len(cases), per, maxi, total_entries))
    sys.stdout.flush()

    # Oracle 侧按域串行，每域单独计时并立即刷出：全量跑动辄上千例，
    # 不给中途反馈就无法判断是「真在算」还是「卡死了」。
    t0 = time.time()
    exp_all = []
    for fname, dom in SO:
        sub = [c for c in cases if c["domain"] == dom]
        if not sub:
            continue
        t_dom = time.time()
        got_dom = run_unicorn(JNI / fname, sub)
        exp_all.extend(got_dom)
        n_insn = sum(e["executed"] for e in got_dom)
        print("[DIFF] oracle %-12s %4d 例 / %6d 条  %6.1fs (累计 %.1fs)"
              % (dom, len(sub), n_insn, time.time() - t_dom,
                 time.time() - t0))
        sys.stdout.flush()
    eff = [e["executed"] for e in exp_all]
    t_k = time.time()
    got = run_kotlin(cases, eff, jvm_cp, java)
    print("[DIFF] kotlin 侧 %d 例 %6.1fs (累计 %.1fs)"
          % (len(cases), time.time() - t_k, time.time() - t0))
    sys.stdout.flush()

    rep = compare(cases, exp_all, got)

    # Kotlin 停在「未翻译目标」时（`bl` 进 .plt 桩或外部符号），Unicorn 有映射会
    # 继续把桩当代码跑，两边步数天然不等、末态不可比。这不是语义差异：把 Oracle
    # 的预算收到 Kotlin 实际执行的条数再比一次，比到**共同可执行区间的末尾**为止。
    # 只有逐字段全等才改判，否则维持不一致。@spec Z-10
    reconciled = 0
    short = [(i, d) for i, d in enumerate(rep["details"])
             if not d["ok"] and d["kotlinUnresolved"] > 0
             and 0 < got[i]["steps"] < exp_all[i]["executed"]]
    if short:
        by_dom = {}
        for k, (i, _d) in enumerate(short):
            by_dom.setdefault(cases[i]["domain"], []).append(k)
        reexp = [None] * len(short)
        for fname, dom in SO:
            ks = by_dom.get(dom)
            if not ks:
                continue
            sub = [dict(cases[short[k][0]], maxInsn=got[short[k][0]]["steps"])
                   for k in ks]
            for k, e in zip(ks, run_unicorn(JNI / fname, sub)):
                reexp[k] = e
        fixed = 0
        for k, (i, d) in enumerate(short):
            e = reexp[k]
            if e is None:
                continue
            diff = diff_one(e, got[i])
            if not diff:
                d["ok"] = True
                d["externalStop"] = True
                d["executed"] = got[i]["steps"]
                d["diff"] = []
                rep["ok"] += 1
                fixed += 1
        if fixed:
            reconciled = fixed
            print("[DIFF] external-stop reconciled: %d (比到共同可执行区间末尾)"
                  % fixed)

    per_dom = {}
    for d in rep["details"]:
        s = per_dom.setdefault(d["domain"], {"ok": 0, "total": 0, "fault": 0})
        s["total"] += 1
        s["ok"] += 1 if d["ok"] else 0
        s["fault"] += 1 if d["stop"].startswith("fault") else 0
    for d, s in per_dom.items():
        print("[DIFF] %-12s ok=%d/%d  (fault-stop %d)"
              % (d, s["ok"], s["total"], s["fault"]))
    insn = sum(d["executed"] for d in rep["details"])
    print("[DIFF] instructions compared = %d, cases ok = %d/%d"
          % (insn, rep["ok"], rep["total"]))
    bad = [(d["idx"], d) for d in rep["details"] if not d["ok"]]
    traces = trace_mismatches(bad, cases, jvm_cp, java)
    for d in rep["details"]:
        if d["ok"]:
            continue
        t = traces.get((d["domain"], d["entry"])) or {}
        print("  [MISMATCH] %s @%#x after %d insn: %s"
              % (d["domain"], d["entry"], d["executed"], "; ".join(d["diff"])))
        if t.get("firstDivergence") is not None:
            print("      first divergence at #%d: oracle pc=%#x kotlin pc=%#x  |  %s"
                  % (t["firstDivergence"], t["oraclePc"], t["kotlinPc"],
                     t["asm"][0] if t["asm"] else ""))
        elif t:
            print("      no pc divergence (state-only): oracle tail %s / kotlin tail %s"
                  % (t["oracleTail"], t["kotlinTail"]))

    for d in rep["details"]:
        t = traces.get((d["domain"], d["entry"]))
        if t:
            d["trace"] = t
    report = {
        "tool": "reverse-engine/tools/z_diff.py",
        "oracle": "unicorn %s (QEMU TCG)" % _uc_version(),
        "method": "同一机器码分别由 Unicorn 与 Kotlin 解释器执行相同指令数，"
                  "比对 x0..x29 / SP / NZCV / v0..v31 / 栈区内存 FNV 摘要",
        "harness": {
            "regs": "x0..x29 = 0, v0..v31 = 0, fpcr/fpsr = 0",
            "sp": SP_INIT,
            "memory": "so 的 PT_LOAD 全段按加载器语义灌入 + 128 KiB 栈区全零",
            "stop": "离开 .text（尾调用进 .plt 桩即视为外部边界）或访存 fault；"
                    "effN = 已执行指令数，Kotlin 用同一 effN 作预算",
        },
        "sampling": {"perDomain": per, "maxInsn": maxi},
        "instructionsCompared": insn,
        "cases": rep["total"],
        "ok": rep["ok"],
        "perDomain": per_dom,
        "stopBreakdown": _stop_breakdown(rep["details"]),
        "externalStopReconciled": reconciled,
        "mismatches": [d for d in rep["details"] if not d["ok"]],
        "details": rep["details"],
    }
    Path(args.out).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    print("[DIFF] -> %s" % args.out)
    return 0 if rep["ok"] == rep["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
