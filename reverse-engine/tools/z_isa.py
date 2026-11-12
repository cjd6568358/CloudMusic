#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z 组 —— ARM64 指令全字段解码器（capstone detail 口径，零正则猜操作数）。

与 Y 组 tools/y_transpile.py 的差别（Y 组只用 mnemonic + 正则抓寄存器，
丢掉了访问宽度 / 条件码 / 移位 / 扩展 / pre-post 写回，导致 cset 一律按 NE
求值、ldrb 按 8 字节读写、bl 不真调用）：

  * 操作数全部来自 capstone `insn.operands`（REG / IMM / MEM / FP / SYS）；
  * `insn.cc` 给出**真实条件码**，不再从助记词后缀猜；
  * `insn.writeback` / `insn.post_index` 给出 pre/post-index 写回；
  * `operand.shift` / `operand.ext` 给出移位与扩展；
  * `mem.base` / `mem.index` / `mem.disp` 分开保存，支持寄存器偏移寻址；
  * NEON 排列（`.8b` / `.4s` / `.2d` …）从 op_str 取，因为 capstone 不暴露。

每条指令解码为一条 [NmIns] 记录（见 kt/src/NmArm64.kt 的同名 data class）：

    (addr, op, rd, rn, rm, imm, imm2, f, c)

f / c 是打包标志位，布局见下面的 F_* / C_* 常量；两个 Int 足以表达
全部标量 + NEON 语义，不需要额外的 side table。

用法（被 tools/z_transpile.py import，也可单独跑自检）：
    python tools/z_isa.py            # 打印每个助记词的解码形态与语义 op
"""
from __future__ import annotations

import re
import sys
from collections import Counter, OrderedDict

from capstone import CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, Cs
from capstone.arm64 import (
    ARM64_OP_FP, ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG, ARM64_OP_SYS,
)

# ---------------------------------------------------------------------------
# 语义 op 表。顺序即 Kotlin 侧 NmOp 的常量值，双方必须一致（由 audit 守护）。
# ---------------------------------------------------------------------------
OPS = OrderedDict()


def _op(name: str) -> int:
    if name not in OPS:
        OPS[name] = len(OPS)
    return OPS[name]


for _n in """
UNKNOWN NOP
MOV_IMM MOV_RR MOVK MOV_VEC
ADD_IMM ADD_REG ADDS_IMM ADDS_REG SUB_IMM SUB_REG SUBS_IMM SUBS_REG
CMP_IMM CMP_REG CMN_IMM CMN_REG NEG NEGS
MUL MADD MSUB MNEG SMULL UMULL SMADDL UMADDL SMULH UMULH SDIV UDIV
ADR ADRP FRINTM
AND_IMM AND_REG ANDS_IMM ANDS_REG ORR_IMM ORR_REG EOR_IMM EOR_REG
ORN_REG BIC_REG BICS_REG MVN TST_IMM TST_REG
LSL_IMM LSL_REG LSR_IMM LSR_REG ASR_IMM ASR_REG ROR_IMM ROR_REG EXTR
UBFX SBFX BFI BFXIL UBFIZ SBFIZ SXTB SXTH SXTW UXTB UXTH
CLZ RBIT REV REV16 REV32
CSEL CSINC CSINV CSNEG CSET CSETM CINC CINV CNEG
CCMP_IMM CCMP_REG CCMN_IMM CCMN_REG
B B_COND CBZ CBNZ TBZ TBNZ BL BLR BR RET BRK
LD ST LDP STP LDX STX
LD1 LD1R LD2 LD3 LD4 ST1 ST2 ST3 ST4
PRFM CLREX MRS MSR SVC
FMOV_RR FMOV_IMM FADD FSUB FMUL FDIV FNEG FABS FSQRT FMAX FMIN FMAXNM FMINNM FABD
FMADD FMSUB FNMADD FNMSUB FNMUL FCMP FCMPE FCCMP
FCVT FCVTL FCVTL2 FCVTN FCVTN2
SCVTF UCVTF FCVTZS FCVTZU FCVTPU FCVTPS FCVTMS FCVTAS FCSEL
MOVI_VEC MVNI_VEC DUP_VEC EXT_VEC TBL_VEC CNT_VEC
ADDV_VEC UADDLV_VEC SMAXV_VEC FMAX_VEC FMIN_VEC
REV64_VEC REV32_VEC REV16_VEC UZP1_VEC UZP2_VEC ZIP1_VEC ZIP2_VEC TRN1_VEC TRN2_VEC
SSHLL_VEC USHLL_VEC XTN_VEC XTN2_VEC SHRN_VEC SHRN2_VEC
SMAX_VEC SMIN_VEC CMEQ_VEC CMEQ_ZERO_VEC BIT_VEC BIF_VEC BSL_VEC
USHR_VEC SSHR_VEC SHL_VEC USRA_VEC SSRA_VEC
FADD_VEC FSUB_VEC FMUL_VEC FDIV_VEC FMLA_VEC FMLS_VEC FADDP_VEC
FCMGT_VEC FCMGE_VEC FCMLT_VEC FABS_VEC FNEG_VEC
ADD_VEC_I SUB_VEC_I MUL_VEC_I AND_VEC ORR_VEC EOR_VEC BIC_VEC ORN_VEC
""".split():
    _op(_n)

OP_COUNT = len(OPS)


def write_nmop(path) -> int:
    """把 OPS 顺序固化成 kt/src/NmOp.kt（数值即 op 编码，必须与运行时一致）。"""
    lines = ['@file:Suppress("unused")',
             "package cloudmusic.reverse.kernel", "",
             "/** 【生成物，勿手改】Z 组：%d 个语义 op（与 tools/z_isa.py 的 OPS 顺序一致）。 @spec Z-02 */"
             % OP_COUNT,
             "object NmOp {"]
    for name, v in OPS.items():
        lines.append("    const val %s: Int = %d" % (name, v))
    lines.append("")
    lines.append("    const val COUNT: Int = %d" % OP_COUNT)
    lines.append("    val NAMES: List<String> = listOf(%s)"
                 % ", ".join('"%s"' % n for n in OPS))
    lines.append('    fun name(op: Int): String = if (op in NAMES.indices) NAMES[op] else "?"')
    lines.append("}")
    lines.append("")
    p = __import__("pathlib").Path(path)
    p.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    return OP_COUNT


# --- 标志位布局（与 kt/src/NmArm64.kt 的 NmF 常量一一对应） ---
W8, W4_ = 0, 1
W2, W1 = 2, 3
F_SIGNED = 1 << 2
F_WB = 1 << 3
F_POST = 1 << 4
F_RDV = 1 << 5
F_RNV = 1 << 6
F_RMV = 1 << 7
F_SETS = 1 << 8
F_RDSP = 1 << 9
F_INDEX = 1 << 10
F_ACQ = 1 << 11
F_ARR_SHIFT = 12
F_128 = 1 << 16          # Q 寄存器：16 字节访问

C_COND = 0x0F
C_SH_SHIFT = 4
C_SHAM_SHIFT = 6
C_EXT_SHIFT = 12
C_LANE_SHIFT = 15
C_LANE_MASK = 0x0F
C_HASLANE = 1 << 19
C_RC_SHIFT = 20          # 多寄存器结构访存的寄存器个数
C_SRCW_SHIFT = 24        # 浮点源操作数宽度（FCVT 等）

# 排列编码（NEON）
ARR = {
    "": 0, "8b": 1, "16b": 2, "4h": 3, "8h": 4, "2s": 5, "4s": 6, "2d": 7,
    "1d": 8, "1s": 9, "1h": 10, "1b": 11, "8s": 12, "4b": 13, "2h": 14, "16s": 15,
}

# 系统寄存器
SYSREG = {
    "tpidr_el0": 1, "cntvct_el0": 2, "cntfrq_el0": 3, "fpcr": 4, "fpsr": 5,
    "nzcv": 6, "daif": 7, "tpidrro_el0": 8, "ctr_el0": 9, "dczid_el0": 10,
    "midr_el1": 11, "cntpct_el0": 12, "pmccntr_el0": 13,
}

EXT = {"uxtb": 1, "uxth": 2, "uxtw": 3, "uxtx": 4, "sxtb": 5, "sxth": 6,
       "sxtw": 7, "sxtx": 8}

SHIFT = {"lsl": 0, "lsr": 1, "asr": 2, "ror": 3}

# 条件码（capstone ARM64_CC_*：1=EQ 2=NE 3=HS 4=LO 5=MI 6=PL 7=VS 8=VC
# 9=HI 10=LS 11=GE 12=LT 13=GT 14=LE 15=AL）
COND_NAMES = {0: "", 1: "eq", 2: "ne", 3: "hs", 4: "lo", 5: "mi", 6: "pl",
              7: "vs", 8: "vc", 9: "hi", 10: "ls", 11: "ge", 12: "lt",
              13: "gt", 14: "le", 15: "al"}

VREG_RE = re.compile(r"\bv(\d+)(?:\.(\d*\w+))?(?:\[(\d+)\])?")
SCALAR_V_RE = re.compile(r"\b([bhsdq])(\d+)(?:\[(\d+)\])?")


def _flags(width=W8, signed=False, wb=False, post=False, rdv=False, rnv=False,
           rmv=False, sets=False, rdsp=False, index=False, acq=False, arr=0,
           is128=False):
    v = width
    if signed:
        v |= F_SIGNED
    if wb:
        v |= F_WB
    if post:
        v |= F_POST
    if rdv:
        v |= F_RDV
    if rnv:
        v |= F_RNV
    if rmv:
        v |= F_RMV
    if sets:
        v |= F_SETS
    if rdsp:
        v |= F_RDSP
    if index:
        v |= F_INDEX
    if acq:
        v |= F_ACQ
    if is128:
        v |= F_128
    v |= (arr & 0xF) << F_ARR_SHIFT
    return v


def _aux(cond=0, sh=0, sham=0, ext=0, lane=0, rc=0, srcw=0):
    v = (cond & 0xF) | ((sh & 3) << C_SH_SHIFT) | ((sham & 0x3F) << C_SHAM_SHIFT)
    v |= (ext & 7) << C_EXT_SHIFT
    v |= (lane & C_LANE_MASK) << C_LANE_SHIFT
    v |= (rc & 0xF) << C_RC_SHIFT
    v |= (srcw & 3) << C_SRCW_SHIFT
    return v


class Reg:
    """寄存器引用：kind ∈ X / W / V / S / D / H / B / Q / SP / ZR。"""

    __slots__ = ("kind", "idx")

    def __init__(self, kind: str, idx: int):
        self.kind = kind
        self.idx = idx

    @property
    def vec(self) -> bool:
        return self.kind in ("V", "S", "D", "H", "B", "Q")

    @property
    def width(self) -> int:
        return {"X": W8, "W": W4_, "S": W4_, "D": W8, "H": W2, "B": W1,
                "Q": W8, "V": W8}.get(self.kind, W8)

    def __repr__(self):
        return "%s%d" % (self.kind, self.idx)


def reg_of(ins, capstone_reg: int) -> Reg:
    name = ins.reg_name(capstone_reg)
    if name in ("fp",):
        return Reg("X", 29)
    if name in ("lr",):
        return Reg("X", 30)
    if name in ("sp", "wsp"):
        return Reg("SP", 31)
    if name in ("xzr", "wzr"):
        return Reg("ZR", 32)
    if name in ("xzr",):
        return Reg("ZR", 32)
    m = re.fullmatch(r"x(\d+)", name)
    if m:
        return Reg("X", int(m.group(1)))
    m = re.fullmatch(r"w(\d+)", name)
    if m:
        return Reg("W", int(m.group(1)))
    m = re.fullmatch(r"([vshdqbm])(\d+)", name)
    if m:
        return Reg({"v": "V", "s": "S", "h": "H", "d": "D", "q": "Q",
                    "b": "B", "m": "B"}[m.group(1)], int(m.group(2)))
    return Reg("ZR", 32)


def arrangements(ins) -> list:
    """按出现顺序返回 op_str 中的 NEON 排列码（含 lane 号）。"""
    out = []
    for m in VREG_RE.finditer(ins.op_str):
        arr = (m.group(2) or "").replace(".", "")
        lane = m.group(3)
        out.append((ARR.get(arr, 0), int(lane) if lane is not None else -1))
    return out


def _imm_ops(ins):
    return [o.imm for o in ins.operands if o.type == ARM64_OP_IMM]


def _reg_ops(ins):
    return [reg_of(ins, o.reg) for o in ins.operands if o.type == ARM64_OP_REG]


def _mem(ins):
    for o in ins.operands:
        if o.type == ARM64_OP_MEM:
            return o
    return None


def _shift_of(op) -> tuple:
    return (SHIFT.get(str(op.shift.type).lower(), 0) if op.shift.type else 0,
            op.shift.value or 0)


# ---------------------------------------------------------------------------
# 解码主体
# ---------------------------------------------------------------------------
def decode(ins) -> tuple | None:
    """返回 (op, rd, rn, rm, imm, imm2, f, c)；无法解码返回 None。"""
    m = ins.mnemonic
    ops = ins.operands
    regs = _reg_ops(ins)
    imms = _imm_ops(ins)
    mem = _mem(ins)
    cond = ins.cc or 0
    if ins.cc == 15:
        cond = 0
    arrs = arrangements(ins)

    def r(i, default=-1):
        return regs[i].idx if len(regs) > i else default

    def width_of(i):
        return regs[i].width if len(regs) > i else W8

    def vec_of(i):
        return F_RDV if (len(regs) > i and regs[i].vec) else 0

    def arr_of(i, default=0):
        return arrs[i][0] if len(arrs) > i else default

    def lane_of(i):
        if len(arrs) > i and arrs[i][1] >= 0:
            return arrs[i][1]
        return 0

    # ---------------- 空操作 / 提示 ----------------
    if m in ("nop", "yield", "sev", "wfe", "wfi", "isb", "dsb", "dmb"):
        return (_op("NOP"), -1, -1, -1, 0, 0, 0, _aux())
    if m in ("prfm", "prfum"):
        return (_op("PRFM"), -1, -1, -1, 0, 0, 0, _aux())
    if m == "clrex":
        return (_op("CLREX"), -1, -1, -1, 0, 0, 0, _aux())
    if m == "brk":
        return (_op("BRK"), -1, -1, -1, imms[0] if imms else 0, 0, 0, _aux())

    # ---------------- 系统寄存器 ----------------
    if m == "mrs":
        nm = ins.op_str.split(",")[-1].strip()
        return (_op("MRS"), r(0), -1, -1, SYSREG.get(nm, 0), 0, 0, _aux())
    if m == "msr":
        nm = ins.op_str.split(",")[0].strip()
        return (_op("MSR"), -1, -1, -1, SYSREG.get(nm, 0), 0, 0, _aux())
    if m == "svc":
        return (_op("SVC"), -1, -1, -1, imms[0] if imms else 0, 0, 0, _aux())

    # ---------------- 分支 ----------------
    if m == "b" or m.startswith("b."):
        tgt = imms[-1]
        if m != "b" and cond:
            return (_op("B_COND"), -1, -1, -1, tgt, 0, 0, _aux(cond=cond))
        return (_op("B"), -1, -1, -1, tgt, 0, 0, _aux())
    if m in ("cbz", "cbnz"):
        return (_op("CBZ" if m == "cbz" else "CBNZ"), r(0), -1, -1,
                imms[-1], 0, width_of(0), _aux())
    if m in ("tbz", "tbnz"):
        return (_op("TBZ" if m == "tbz" else "TBNZ"), r(0), -1, -1,
                imms[0], imms[-1], width_of(0), _aux())
    if m == "bl":
        return (_op("BL"), -1, -1, -1, imms[-1], 0, 0, _aux())
    if m == "blr":
        return (_op("BLR"), -1, r(0), -1, 0, 0, 0, _aux())
    if m == "br":
        return (_op("BR"), -1, r(0), -1, 0, 0, 0, _aux())
    if m in ("ret", "retab"):
        return (_op("RET"), -1, r(0, 30), -1, 0, 0, 0, _aux())

    # ---------------- 地址生成 ----------------
    if m in ("adr", "adrp"):
        return (_op("ADR" if m == "adr" else "ADRP"), r(0), -1, -1,
                imms[-1], 0, 0, _aux())

    # ---------------- 条件选择 ----------------
    if m in ("csel", "csinc", "csinv", "csneg"):
        op = {"csel": "CSEL", "csinc": "CSINC", "csinv": "CSINV",
              "csneg": "CSNEG"}[m]
        return (_op(op), r(0), r(1), r(2), 0, 0,
                width_of(0) | vec_of(0), _aux(cond=cond))
    if m in ("cset", "csetm"):
        return (_op("CSET" if m == "cset" else "CSETM"), r(0), -1, -1, 0, 0,
                width_of(0), _aux(cond=cond))
    if m in ("cinc", "cinv", "cneg"):
        return (_op({"cinc": "CINC", "cinv": "CINV", "cneg": "CNEG"}[m]),
                r(0), r(1), -1, 0, 0, width_of(1), _aux(cond=cond))
    if m in ("ccmp", "ccmn"):
        op = "CCMP" if m == "ccmp" else "CCMN"
        if ops[1].type == ARM64_OP_IMM:
            sh, sham = _shift_of(ops[1])
            return (_op(op + "_IMM"), r(0), -1, -1, imms[0], imms[1],
                    width_of(0), _aux(cond=cond, sh=sh, sham=sham))
        return (_op(op + "_REG"), r(0), -1, r(1), imms[0], 0,
                width_of(0), _aux(cond=cond))

    # ---------------- 访存 ----------------
    PAIR_L = {"ldp": ("LDP", False), "ldnp": ("LDP", False),
              "ldpsw": ("LDP", True)}
    PAIR_S = {"stp": ("STP", False), "stnp": ("STP", False)}
    if m in PAIR_L or m in PAIR_S:
        name, signed = (PAIR_L[m] if m in PAIR_L else PAIR_S[m])
        # 操作数：rd, rd2, [mem], #post
        rd, rd2 = regs[0], regs[1]
        width = rd.width
        post = 0
        if ops[-1].type == ARM64_OP_IMM:
            post = imms[-1]
        base_disp = mem.mem.disp
        f = _flags(width=width, signed=signed,
                   wb=(bool(ins.writeback) or bool(ins.post_index)),
                   post=bool(ins.post_index), rdv=rd.vec, arr=arr_of(0),
                   is128=(rd.kind == "Q"))
        imm = base_disp
        imm2 = post if ins.post_index else (base_disp if ins.writeback else 0)
        # rd2 借 rm 槽位传，rd/rm 同为向量时由 f 的 RDV 位统一解释
        return (_op(name), rd.idx, -1, rd2.idx, imm, imm2, f, _aux())

    if m in ("ld1", "ld1r", "ld2", "ld3", "ld4"):
        # 结构加载：{v..}, [base], #post
        nregs = len(regs) - 0
        base = mem.mem.base
        post = imms[-1] if (ops[-1].type == ARM64_OP_IMM) else 0
        arr = arr_of(0)
        f = _flags(wb=bool(ins.post_index), post=bool(ins.post_index),
                   rdv=True, arr=arr)
        return (_op({"ld1": "LD1", "ld1r": "LD1R", "ld2": "LD2",
                     "ld3": "LD3", "ld4": "LD4"}[m]),
                regs[0].idx, reg_of(ins, base).idx, -1,
                post, mem.mem.disp, f, _aux(rc=len(regs)))
    if m in ("st1", "st2", "st3", "st4"):
        base = mem.mem.base
        post = imms[-1] if (ops[-1].type == ARM64_OP_IMM) else 0
        f = _flags(wb=bool(ins.post_index), post=bool(ins.post_index),
                   rdv=True, arr=arr_of(0))
        return (_op({"st1": "ST1", "st2": "ST2", "st3": "ST3",
                     "st4": "ST4"}[m]),
                regs[0].idx, reg_of(ins, base).idx, -1,
                post, mem.mem.disp, f, _aux(rc=len(regs)))

    LOAD_WIDTH = {
        "ldr": (W8, False), "ldur": (W8, False), "ldar": (W8, False),
        "ldxr": (W8, False), "ldaxr": (W8, False),
        "ldrb": (W1, False), "ldurb": (W1, False), "ldarb": (W1, False),
        "ldxrb": (W1, False), "ldaxrb": (W1, False),
        "ldrh": (W2, False), "ldurh": (W2, False), "ldarh": (W2, False),
        "ldrsb": (W1, True), "ldursb": (W1, True),
        "ldrsh": (W2, True), "ldursh": (W2, True),
        "ldrsw": (W4_, True), "ldursw": (W4_, True),
    }
    STORE_WIDTH = {
        "str": W8, "stur": W8, "stlr": W8,
        "strb": W1, "sturb": W1, "stlrb": W1,
        "strh": W2, "sturh": W2, "stlrh": W2,
        "stxr": W8, "stlxr": W8, "stxrb": W1, "stlxrb": W1,
        "stxrh": W2, "stlxrh": W2,
    }
    if m in LOAD_WIDTH:
        if mem is None:
            return None
        width, signed = LOAD_WIDTH[m]
        rd = regs[0]
        exclusive = m.startswith("ldx") or m.startswith("ldax")
        acq = m.startswith("ldar") or m.startswith("ldax")
        imm, imm2, index = mem.mem.disp, 0, False
        f = _flags(width=width, signed=signed, rdv=rd.vec, acq=acq,
                   wb=bool(ins.writeback) or bool(ins.post_index),
                   post=bool(ins.post_index), is128=(rd.kind == "Q"))
        if ins.post_index:
            imm = mem.mem.disp
            imm2 = imms[-1] if imms else 0
        elif ins.writeback:
            imm = mem.mem.disp
            imm2 = mem.mem.disp
        if mem.mem.index:
            index = True
            sh, sham = _shift_of(ops[-1] if ops[-1].type != ARM64_OP_MEM else mem)
            return (_op("LDX" if exclusive else "LD"),
                    rd.idx, reg_of(ins, mem.mem.base).idx,
                    reg_of(ins, mem.mem.index).idx,
                    mem.mem.disp, imm2, f | F_INDEX | F_RMV, _aux(sh=sh, sham=sham))
        return (_op("LDX" if exclusive else "LD"),
                rd.idx, reg_of(ins, mem.mem.base).idx, -1, imm, imm2, f, _aux())
    if m in STORE_WIDTH:
        if mem is None:
            return None
        width = STORE_WIDTH[m]
        rs = regs[0]
        exclusive = m.startswith("stx")
        rel = m.startswith("stlr") or m.startswith("stlx")
        imm, imm2 = mem.mem.disp, 0
        f = _flags(width=width, rdv=rs.vec, acq=rel,
                   wb=bool(ins.writeback) or bool(ins.post_index),
                   post=bool(ins.post_index), is128=(rs.kind == "Q"))
        if ins.post_index:
            imm = mem.mem.disp
            imm2 = imms[-1] if imms else 0
        elif ins.writeback:
            imm = mem.mem.disp
            imm2 = mem.mem.disp
        if mem.mem.index:
            sh, sham = _shift_of(mem)
            return (_op("STX" if exclusive else "ST"),
                    rs.idx, reg_of(ins, mem.mem.base).idx,
                    reg_of(ins, mem.mem.index).idx,
                    mem.mem.disp, imm2, f | F_INDEX | F_RMV, _aux(sh=sh, sham=sham))
        # stxr 的 src 在 regs[1]，状态写到 regs[0]
        if exclusive:
            return (_op("STX"), regs[0].idx, reg_of(ins, mem.mem.base).idx,
                    regs[1].idx, imm, imm2, f, _aux())
        return (_op("ST"), rs.idx, reg_of(ins, mem.mem.base).idx, -1,
                imm, imm2, f, _aux())

    # ---------------- 数据传送 ----------------
    if m == "movk":
        sh, sham = _shift_of(ops[1])
        return (_op("MOVK"), r(0), -1, -1, imms[0], 0, width_of(0),
                _aux(sham=sham))
    if m in ("mov", "movz"):
        if ops[1].type == ARM64_OP_IMM:
            return (_op("MOV_IMM"), r(0), -1, -1, imms[0], 0, width_of(0), _aux())
        if regs[0].vec or regs[1].vec:
            return (_op("MOV_VEC"), r(0), r(1), -1, 0, 0,
                    _flags(rdv=regs[0].vec, rnv=regs[1].vec, arr=arr_of(0)),
                    _aux(lane=lane_of(0)))
        return (_op("MOV_RR"), r(0), r(1), -1, 0, 0, width_of(1), _aux())

    # ---------------- 算术 ----------------
    if m in ("add", "adds", "sub", "subs"):
        if regs and regs[0].vec:
            # NEON 整数向量加减：按车道宽度逐元素，不带进位标志
            return (_op("ADD_VEC_I" if m.startswith("add") else "SUB_VEC_I"),
                    r(0), r(1), r(2), 0, 0,
                    _flags(width=width_of(0), rdv=True, rnv=True, rmv=True,
                           arr=arr_of(0)), _aux())
        if ops[2].type == ARM64_OP_IMM:
            sh, sham = _shift_of(ops[2])
            base = "ADD" if m.startswith("add") else "SUB"
            name = base + "_IMM" if not m.endswith("s") else base + "S_IMM"
            f = _flags(width=width_of(0), sets=m.endswith("s"),
                       rdsp=(regs[0].kind == "SP"))
            return (_op(name), r(0), r(1), -1, imms[0], 0, f, _aux(sh=sh, sham=sham))
        sh, sham = _shift_of(ops[2])
        ext = EXT.get(str(ops[2].ext).lower(), 0) if ops[2].ext else 0
        base = "ADD" if m.startswith("add") else "SUB"
        name = base + "_REG" if not m.endswith("s") else base + "S_REG"
        f = _flags(width=width_of(0), sets=m.endswith("s"),
                   rdsp=(regs[0].kind == "SP"))
        return (_op(name), r(0), r(1), r(2), 0, 0, f,
                _aux(sh=sh, sham=sham, ext=ext))
    if m in ("cmp", "cmn"):
        base = "CMP" if m == "cmp" else "CMN"
        if ops[1].type == ARM64_OP_IMM:
            sh, sham = _shift_of(ops[1])
            return (_op(base + "_IMM"), r(0), -1, -1, imms[0], 0,
                    width_of(0), _aux(sh=sh, sham=sham))
        sh, sham = _shift_of(ops[1])
        ext = EXT.get(str(ops[1].ext).lower(), 0) if ops[1].ext else 0
        return (_op(base + "_REG"), r(0), -1, r(1), 0, 0, width_of(0),
                _aux(sh=sh, sham=sham, ext=ext))
    if m in ("neg", "negs"):
        return (_op("NEG" if m == "neg" else "NEGS"), r(0), r(1), -1, 0, 0,
                width_of(0) | (F_SETS if m == "negs" else 0), _aux())
    if m in ("mul", "mneg"):
        if regs and regs[0].vec:
            return (_op("MUL_VEC_I"), r(0), r(1), r(2), 0, 0,
                    _flags(width=width_of(0), rdv=True, rnv=True, rmv=True,
                           arr=arr_of(0)), _aux())
        return (_op("MUL" if m == "mul" else "MNEG"), r(0), r(1), r(2), 0, 0,
                width_of(0), _aux())
    if m in ("madd", "msub"):
        # rd = ra + rn*rm  /  rd = ra - rn*rm  —— 第 4 操作数是加数
        return (_op("MADD" if m == "madd" else "MSUB"), r(0), r(1), r(2),
                0, r(3) if len(regs) > 3 else 31, width_of(0), _aux())
    if m in ("smull", "umull", "smaddl", "umaddl", "smsubl", "umsubl"):
        name = {"smull": "SMULL", "umull": "UMULL", "smaddl": "SMADDL",
                "umaddl": "UMADDL", "smsubl": "SMULL", "umsubl": "UMULL"}[m]
        return (_op(name), r(0), r(1), r(2), 0,
                r(3) if len(regs) > 3 else 31, width_of(1), _aux())
    if m in ("smulh", "umulh"):
        return (_op("SMULH" if m == "smulh" else "UMULH"), r(0), r(1), r(2),
                0, 0, 0, _aux())
    if m in ("sdiv", "udiv"):
        return (_op("SDIV" if m == "sdiv" else "UDIV"), r(0), r(1), r(2), 0, 0,
                width_of(0), _aux())

    # ---------------- 逻辑 ----------------
    if m in ("and", "ands", "orr", "eor", "orn", "bic", "bics", "eon"):
        if regs and regs[0].vec:
            nm = {"and": "AND_VEC", "ands": "AND_VEC", "orr": "ORR_VEC",
                  "eor": "EOR_VEC", "orn": "ORN_VEC", "eon": "EOR_VEC",
                  "bic": "BIC_VEC", "bics": "BIC_VEC"}[m]
            return (_op(nm), r(0), r(1), r(2), 0, 0,
                    _flags(width=width_of(0), rdv=True, rnv=True, rmv=True,
                           arr=arr_of(0)), _aux())
        if m in ("orn", "eon"):
            return (_op("ORN_REG" if m == "orn" else "EOR_REG"), r(0), r(1), r(2),
                    0, 0, width_of(0) | F_RMV, _aux())
        if ops[2].type == ARM64_OP_IMM:
            base = {"and": "AND", "ands": "ANDS", "orr": "ORR",
                    "eor": "EOR", "bic": "BIC", "bics": "BICS"}[m]
            name = base + "_IMM" if base in ("AND", "ANDS", "ORR", "EOR") else base + "_REG"
            return (_op(name), r(0), r(1), -1, imms[-1], 0,
                    width_of(0) | (F_SETS if m.endswith("s") else 0), _aux())
        base = {"and": "AND", "ands": "ANDS", "orr": "ORR", "eor": "EOR",
                "bic": "BIC", "bics": "BICS"}[m]
        name = base + "_REG"
        sh, sham = _shift_of(ops[2])
        return (_op(name), r(0), r(1), r(2), 0, 0,
                width_of(0) | (F_SETS if m.endswith("s") else 0),
                _aux(sh=sh, sham=sham))
    if m == "mvn":
        return (_op("MVN"), r(0), r(1), -1, 0, 0, width_of(0), _aux())
    if m == "umov":
        # w15, v1.b[0]  —— 向量按元素序号取出到通用寄存器
        return (_op("MOV_RR"), r(0), r(1), -1, 0, 0,
                _flags(width=width_of(0), rnv=True, arr=arr_of(1)),
                _aux(lane=lane_of(1)))
    if m == "udf":
        return (_op("BRK"), -1, -1, -1, imms[0] if imms else 0, 0, 0, _aux())
    if m in ("tst", "cmn"):
        if ops[1].type == ARM64_OP_IMM:
            return (_op("TST_IMM"), r(0), -1, -1, imms[0], 0, width_of(0), _aux())
        return (_op("TST_REG"), r(0), -1, r(1), 0, 0, width_of(0), _aux())
    if m in ("lsl", "lsr", "asr", "ror"):
        name = m.upper()
        if ops[2].type == ARM64_OP_IMM:
            return (_op(name + "_IMM"), r(0), r(1), -1, imms[0], 0,
                    width_of(0), _aux())
        return (_op(name + "_REG"), r(0), r(1), r(2), 0, 0, width_of(0), _aux())
    if m == "extr":
        return (_op("EXTR"), r(0), r(1), r(2), imms[-1], 0, width_of(0), _aux())
    if m in ("ubfx", "sbfx", "bfi", "bfxil", "ubfiz", "sbfiz"):
        # 别名语义：lsb + width（不做 UBFM 的 immr/imms 反解，避免与真实位域编码混淆）
        lsb, width_bits = imms[0], imms[1]
        op = {"ubfx": "UBFX", "sbfx": "SBFX", "bfi": "BFI",
              "bfxil": "BFXIL", "ubfiz": "UBFIZ", "sbfiz": "SBFIZ"}[m]
        return (_op(op), r(0), r(1), -1, lsb, width_bits,
                width_of(1) | (F_SIGNED if m == "sbfx" else 0), _aux())

    if m in ("sxtb", "sxth", "sxtw", "uxtb", "uxth", "uxtw", "sxtx", "uxtx"):
        name = m.upper()
        return (_op(name), r(0), r(1), -1, 0, 0,
                width_of(1) | (F_SIGNED if m.startswith("s") else 0), _aux())
    if m == "clz":
        return (_op("CLZ"), r(0), r(1), -1, 0, 0, width_of(0), _aux())
    if m == "rbit":
        return (_op("RBIT"), r(0), r(1), -1, 0, 0, width_of(0), _aux())
    if m in ("rev", "rev16", "rev32"):
        return (_op({"rev": "REV", "rev16": "REV16", "rev32": "REV32"}[m]),
                r(0), r(1), -1, 0, 0, width_of(0), _aux())

    # ---------------- 标量浮点 ----------------
    if m == "fmov":
        if ops[1].type == ARM64_OP_IMM:
            return (_op("FMOV_IMM"), r(0), -1, -1, imms[0], 0,
                    _flags(width=regs[0].width, rdv=regs[0].vec), _aux())
        if ops[1].type == ARM64_OP_FP:
            # fmov d0, #1.0 —— capstone 给浮点字面值，这里只保留可表示的整数值
            val = ops[1].fp
            ival = int(val) if float(val).is_integer() else 0
            return (_op("FMOV_IMM"), r(0), -1, -1, ival, 0,
                    _flags(width=regs[0].width, rdv=regs[0].vec), _aux())
        return (_op("FMOV_RR"), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=len(regs) > 1 and regs[1].vec), _aux())
    SCALAR_FP2 = {"fneg": "FNEG", "fabs": "FABS", "fsqrt": "FSQRT",
                  "frintm": "FRINTM", "frintp": "FRINTM", "frintz": "FRINTM"}
    if m in SCALAR_FP2:
        return (_op(SCALAR_FP2[m]), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=regs[1].vec), _aux())
    SCALAR_FP3 = {"fadd": "FADD", "fsub": "FSUB", "fmul": "FMUL",
                  "fdiv": "FDIV", "fmax": "FMAX", "fmin": "FMIN",
                  "fmaxnm": "FMAXNM", "fminnm": "FMINNM", "fabd": "FABD",
                  "fnmul": "FNMUL"}
    if m in SCALAR_FP3:
        name = SCALAR_FP3[m]
        if len(regs) == 2 and ops[1].type != ARM64_OP_REG:
            # 向量形式交给 SIMD 分支
            pass
        return (_op(name), r(0), r(1), r(2), 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=regs[1].vec, rmv=regs[2].vec if len(regs) > 2 else False),
                _aux(lane=lane_of(2)))
    if m in ("fmadd", "fmsub", "fnmadd", "fnmsub"):
        return (_op({"fmadd": "FMADD", "fmsub": "FMSUB",
                     "fnmadd": "FNMADD", "fnmsub": "FNMSUB"}[m]),
                r(0), r(1), r(2), 0, r(3) if len(regs) > 3 else 31,
                _flags(width=regs[0].width, rdv=regs[0].vec), _aux())
    if m in ("fcmp", "fcmpe"):
        return (_op("FCMP" if m == "fcmp" else "FCMPE"), r(0), -1, r(1),
                imms[0] if imms else 0, 0,
                _flags(width=regs[0].width, rnv=len(regs) > 1 and regs[1].vec),
                _aux())
    if m == "fccmp":
        return (_op("FCCMP"), r(0), r(1), -1, imms[0] if imms else 0, 0,
                _flags(width=regs[0].width), _aux(cond=cond))
    if m == "fcsel":
        return (_op("FCSEL"), r(0), r(1), r(2), 0, 0,
                _flags(width=regs[0].width), _aux(cond=cond))
    if m == "fcvt":
        return (_op("FCVT"), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=regs[1].vec), _aux(srcw=regs[1].width))
    if m in ("fcvtl", "fcvtl2"):
        return (_op(m.upper()), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux(lane=lane_of(0)))
    if m in ("fcvtn", "fcvtn2"):
        return (_op(m.upper()), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux(lane=lane_of(0)))
    if m in ("scvtf", "ucvtf"):
        return (_op("SCVTF" if m == "scvtf" else "UCVTF"), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=regs[1].vec), _aux())
    if m in ("fcvtzs", "fcvtzu", "fcvtpu", "fcvtps", "fcvtms", "fcvtas"):
        op = {"fcvtzs": "FCVTZS", "fcvtzu": "FCVTZU", "fcvtpu": "FCVTPU",
              "fcvtps": "FCVTPS", "fcvtms": "FCVTMS", "fcvtas": "FCVTAS"}[m]
        return (_op(op), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=regs[0].vec,
                       rnv=regs[1].vec), _aux())

    # ---------------- NEON 逐元素 ----------------
    if m in ("fadd", "fsub", "fmul", "fdiv", "fmla", "fmls", "fmax", "fmin"):
        return (_op({"fadd": "FADD_VEC", "fsub": "FSUB_VEC", "fmul": "FMUL_VEC",
                     "fdiv": "FDIV_VEC", "fmla": "FMLA_VEC", "fmls": "FMLS_VEC",
                     "fmax": "FMAX_VEC", "fmin": "FMIN_VEC"}[m]),
                r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)),
                _aux(lane=lane_of(2)))
    if m == "faddp":
        return (_op("FADDP_VEC"), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux())
    if m in ("fcmgt", "fcmge", "fcmlt", "cmeq"):
        return (_op({"fcmgt": "FCMGT_VEC", "fcmge": "FCMGE_VEC",
                     "fcmlt": "FCMLT_VEC", "cmeq": "CMEQ_VEC"}[m]),
                r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m in ("smax", "smin"):
        return (_op("SMAX_VEC" if m == "smax" else "SMIN_VEC"), r(0), r(1), r(2),
                0, 0, _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m in ("addv", "uaddlv", "smaxv"):
        return (_op({"addv": "ADDV_VEC", "uaddlv": "UADDLV_VEC",
                     "smaxv": "SMAXV_VEC"}[m]), r(0), r(1), -1, 0, 0,
                _flags(width=regs[0].width, rdv=False, rnv=True, arr=arr_of(1)),
                _aux())
    if m in ("rev64", "rev32", "rev16"):
        return (_op(m.upper() + "_VEC"), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux())
    if m in ("zip1", "zip2", "uzp1", "uzp2", "trn1", "trn2"):
        return (_op(m.upper() + "_VEC"), r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m == "ext":
        return (_op("EXT_VEC"), r(0), r(1), r(2), imms[-1], 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m == "tbl":
        return (_op("TBL_VEC"), r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)),
                _aux(rc=len(regs) - 1))
    if m in ("sshll", "sshll2", "ushll", "ushll2"):
        return (_op("SSHLL_VEC" if m.startswith("s") else "USHLL_VEC"),
                r(0), r(1), -1, imms[-1], 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)),
                _aux(lane=lane_of(0)))
    if m in ("xtn", "xtn2", "shrn", "shrn2"):
        return (_op(m.upper() + "_VEC"), r(0), r(1), -1,
                imms[-1] if imms else 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux())
    if m == "dup":
        return (_op("DUP_VEC"), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=len(regs) > 1 and regs[1].vec,
                       arr=arr_of(0)), _aux(lane=lane_of(0)))
    if m == "movi":
        return (_op("MOVI_VEC"), r(0), -1, -1, imms[0], 0,
                _flags(rdv=regs[0].vec, arr=arr_of(0)), _aux())
    if m == "mvni":
        return (_op("MVNI_VEC"), r(0), -1, -1, imms[0], 0,
                _flags(rdv=regs[0].vec, arr=arr_of(0)), _aux())
    if m in ("ushr", "sshr", "usra", "ssra", "shl"):
        return (_op(m.upper() + "_VEC"), r(0), r(1), -1, imms[-1], 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux())
    if m == "bit":
        return (_op("BIT_VEC"), r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m in ("bif", "bsl"):
        return (_op(m.upper() + "_VEC"), r(0), r(1), r(2), 0, 0,
                _flags(rdv=True, rnv=True, rmv=True, arr=arr_of(0)), _aux())
    if m == "cnt":
        return (_op("CNT_VEC"), r(0), r(1), -1, 0, 0,
                _flags(rdv=True, rnv=True, arr=arr_of(0)), _aux())

    return None


# ---------------------------------------------------------------------------
# 自检：打印每个助记词的解码形态
# ---------------------------------------------------------------------------
def self_check(so_paths) -> int:
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    seen = {}
    unhandled = Counter()
    for p in so_paths:
        data = p.read_bytes()
        import struct
        off = struct.unpack_from("<Q", data, 0x28)[0]
        esz, num, strndx = struct.unpack_from("<HHH", data, 0x3A)
        shs = []
        for i in range(num):
            o = off + i * esz
            nm, _t, _f, addr, o2, sz, link, _i, _a, _e = struct.unpack_from(
                "<IIQQQQIIQQ", data, o)
            shs.append(dict(name=nm, addr=addr, off=o2, size=sz, link=link))
        st = shs[strndx]
        for s in shs:
            e = data.find(b"\x00", st["off"] + s["name"])
            s["sname"] = data[st["off"] + s["name"]:e].decode()
        t = next(s for s in shs if s["sname"] == ".text")
        for i in md.disasm(data[t["off"]:t["off"] + t["size"]], t["addr"]):
            if i.mnemonic in seen:
                continue
            d = decode(i)
            seen[i.mnemonic] = (i, d)
            if d is None:
                unhandled[i.mnemonic] += 1
    names = {v: k for k, v in OPS.items()}
    for mn in sorted(seen):
        i, d = seen[mn]
        if d is None:
            print("UNHANDLED %-10s %s" % (mn, i.op_str))
            continue
        op, rd, rn, rm, imm, imm2, f, c = d
        print("%-10s -> %-14s rd=%-3d rn=%-3d rm=%-3d imm=%-8d f=%#06x c=%#06x   | %s"
              % (mn, names[op], rd, rn, rm, imm, f, c, i.op_str))
    print("\nunhandled=%d ops=%d" % (len(unhandled), OP_COUNT))
    return 1 if unhandled else 0


if __name__ == "__main__":
    from pathlib import Path
    JNI = Path(__file__).resolve().parent.parent.parent / "engine-legacy/src/main/jniLibs/arm64-v8a"
    sos = [JNI / n for n in ("libNMCommonCache.so", "libncmplayerengine.so",
                             "libncmaudioplayer.so", "libaudioplayer.so")]
    sys.exit(self_check(sos))
