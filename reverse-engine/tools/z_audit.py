#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z 组语义残留审计：找出「解码成功但语义桶不对」的指令。

两类残留：

  1. dropout —— `z_isa.decode` 返回 None（运行时会落到 when(op) 兜底分支）。
  2. vector-leak —— 操作数里有向量寄存器，却被映射进**只处理通用寄存器**的标量 op
     （例如 `add v0.4s, v1.4s, v2.4s` 被映射成 ADD_REG，运行期会去读 x1/x2）。
     这类不会触发 dropout，是**静默错**，必须单独统计。
  3. operand-loss —— capstone 解析出的某个寄存器操作数，在解码元组
     `(rd, rn, rm, imm, imm2)` 里完全没有落点。`stp x29,x30,[sp,#-0x10]!` 的
     基址曾被写死成 -1（等价于访问地址 0+偏移），`stlxr` 的值寄存器、`tbl`
     的索引寄存器同理。这类不报错、不错桶，是**最难发现的一种静默错**——
     是差分验证（`tools/z_diff.py`，Unicorn 作真值）把它抓出来的。

判据以代码可达性为准（与 z_transpile.py 同一套发现逻辑）。

用法：
  python tools/z_audit.py
产出：
  spec/z-audit.json
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import z_isa  # noqa: E402
import z_transpile as ZT  # noqa: E402

RE = Path(__file__).resolve().parent.parent
OUT = RE / "spec" / "z-audit.json"

# 多寄存器结构访存：寄存器列表是连续的一段，解码只带首号 + 个数
MULTI_REG = set("ld1 ld1r ld2 ld3 ld4 st1 st2 st3 st4".split())
# 累加器寄存器借 imm2 槽位传递的族
IMM2_REG = set("madd msub smaddl umaddl smull umull "
               "fmadd fmsub fnmadd fnmsub".split())
# 表查找：表寄存器连续、索引在 rm
TABLE_REG = set(["tbl", "tbx"])

# 只处理通用寄存器 / 立即数的标量 op —— 见到向量操作数就是错桶。
SCALAR_ONLY = set("""
UNKNOWN NOP MOV_IMM MOVK
ADD_IMM ADD_REG ADDS_IMM ADDS_REG SUB_IMM SUB_REG SUBS_IMM SUBS_REG
CMP_IMM CMP_REG CMN_IMM CMN_REG NEG NEGS
MUL MADD MSUB MNEG SMULL UMULL SMADDL UMADDL SMULH UMULH SDIV UDIV
ADR ADRP
AND_IMM AND_REG ANDS_IMM ANDS_REG ORR_IMM ORR_REG EOR_IMM EOR_REG
ORN_REG BIC_REG BICS_REG MVN TST_IMM TST_REG
LSL_IMM LSL_REG LSR_IMM LSR_REG ASR_IMM ASR_REG ROR_IMM ROR_REG
EXTR UBFM SBFM BFM SXTB SXTH SXTW UXTB UXTH CLZ RBIT REV REV16 REV32
CSEL CSINC CSINV CSNEG CSET CSETM CINC CINV CNEG
CCMP_IMM CCMP_REG CCMN_IMM CCMN_REG
B B_COND CBZ CBNZ TBZ TBNZ BL BLR BR RET BRK
PRFM CLREX MRS MSR SVC
""".split())

# 明确按向量处理、允许出现向量操作数的 op
VECTOR_AWARE = set("""
MOV_RR MOV_VEC MOVI_VEC MVNI_VEC DUP_VEC EXT_VEC TBL_VEC CNT_VEC
ADDV_VEC UADDLV_VEC SMAXV_VEC FMAX_VEC FMIN_VEC
REV64_VEC REV32_VEC REV16_VEC UZP1_VEC UZP2_VEC ZIP1_VEC ZIP2_VEC
TRN1_VEC TRN2_VEC SSHLL_VEC USHLL_VEC XTN_VEC XTN2_VEC SHRN_VEC SHRN2_VEC
SMAX_VEC SMIN_VEC CMEQ_VEC CMEQ_ZERO_VEC BIT_VEC BIF_VEC BSL_VEC
USHR_VEC SSHR_VEC SHL_VEC USRA_VEC SSRA_VEC
FADD_VEC FSUB_VEC FMUL_VEC FDIV_VEC FMLA_VEC FMLS_VEC FADDP_VEC
FCMGT_VEC FCMGE_VEC FCMLT_VEC FCVTL_VEC FCVTN_VEC FABS_VEC FNEG_VEC
ADD_VEC_I SUB_VEC_I MUL_VEC_I AND_VEC ORR_VEC EOR_VEC BIC_VEC ORN_VEC
LD ST LDP STP LDX STX LD1 LD1R LD2 LD3 LD4 ST1 ST2 ST3 ST4
""".split())

# 浮点标量：操作数是 S/D/H 寄存器，允许
FP_SCALAR = set("""
FMOV_RR FMOV_IMM FADD FSUB FMUL FDIV FNEG FABS FSQRT FMAX FMIN FMAXNM FMINNM
FABD FMADD FMSUB FNMADD FNMSUB FNMUL FCMP FCMPE FCCMP FCVT FCVTL FCVTL2
FCVTN FCVTN2 SCVTF UCVTF FCVTZS FCVTZU FCVTPU FCVTPS FCVTMS FCVTAS FCSEL FRINTM
""".split())


def has_vec_operand(ins) -> bool:
    """capstone 的 op.vec 对 `v0.16b` 返回 None，不能用来判定；
    必须走 z_isa 自己的寄存器分类（Reg.kind ∈ V/S/D/H/B/Q）。"""
    return any(r.vec for r in z_isa._reg_ops(ins))


def reg_index(name: str) -> int:
    if name in ("sp", "wsp"):
        return 31
    if name in ("xzr", "wzr"):
        return 32
    if name == "fp":
        return 29
    if name == "lr":
        return 30
    if name and name[0] in "xwvqsdhb" and name[1:].isdigit():
        return int(name[1:])
    return -1


def operand_loss(ins, dec) -> list:
    """返回解码元组没覆盖到的寄存器名；空列表 = 操作数守恒。"""
    _op, rd, rn, rm, _imm, imm2, _f, _c = dec
    m = ins.mnemonic
    if m in ("prfm", "prfum"):
        return []  # 预取提示，无架构副作用，基址不必进解码元组
    allowed = {x for x in (rd, rn, rm) if x >= 0}
    if m in IMM2_REG and 0 <= imm2 <= 31:
        allowed.add(int(imm2))
    if m in MULTI_REG:
        # {v0, v1, ...} 连续；起始号在 rd
        n = len([r for r in z_isa._reg_ops(ins) if r.vec])
        allowed |= {rd + k for k in range(max(1, n))}
        if _f & z_isa.F_POSTREG:
            allowed.add(int(_imm))  # post-index 寄存器借 imm 槽位
    if m in TABLE_REG:
        n = len([r for r in z_isa._reg_ops(ins) if r.vec]) - 1
        allowed |= {rn + k for k in range(max(1, n))}
        allowed.add(rm)

    lost = []
    for o in ins.operands:
        if o.type == 1:  # ARM64_OP_REG
            cand = [o.reg]
        elif o.type == 3:  # ARM64_OP_MEM
            cand = [x for x in (o.mem.base, o.mem.index) if x not in (-1, 0)]
        else:
            continue
        for r in cand:
            nm = ins.reg_name(r)
            if nm and reg_index(nm) not in allowed and nm not in lost:
                lost.append(nm)
    return lost


def main() -> int:
    opnames = {v: k for k, v in z_isa.OPS.items()}
    report = {}
    total_leak = 0
    total_drop = 0
    total_loss = 0

    for so_name, domain in ZT.SO:
        info = ZT.discover(ZT.JNI / so_name, domain)
        reach = sorted(info["reachable"])
        leak = Counter()
        leak_pair = Counter()
        leak_addr = defaultdict(list)
        undec = Counter()
        loss = Counter()
        loss_sample = defaultdict(list)
        for a in reach:
            ins = info["ins"][a]
            d = z_isa.decode(ins)
            if d is None:
                undec[ins.mnemonic] += 1
                total_drop += 1
                continue
            lost = operand_loss(ins, d)
            if lost:
                loss["%s missing=%s" % (ins.mnemonic, ",".join(lost))] += 1
                total_loss += 1
                if len(loss_sample[ins.mnemonic]) < 5:
                    loss_sample[ins.mnemonic].append("%s  %s" % (hex(a), ins.op_str))
            name = opnames[d[0]]
            if name in VECTOR_AWARE or name in FP_SCALAR:
                continue
            if has_vec_operand(ins):
                leak[ins.mnemonic] += 1
                leak_pair["%s -> %s" % (ins.mnemonic, name)] += 1
                if len(leak_addr[ins.mnemonic]) < 6:
                    leak_addr[ins.mnemonic].append("%s  %s" % (hex(a), ins.op_str))
                total_leak += 1
        report[so_name] = {
            "domain": domain,
            "reachable": len(reach),
            "dropout": dict(undec),
            "dropoutTotal": sum(undec.values()),
            "vectorLeakTotal": sum(leak.values()),
            "vectorLeakByMnemonic": dict(leak.most_common()),
            "vectorLeakByOp": dict(leak_pair.most_common()),
            "vectorLeakSamples": {k: v for k, v in leak_addr.items()},
            "operandLossTotal": sum(loss.values()),
            "operandLossByKind": dict(loss.most_common()),
            "operandLossSamples": {k: v for k, v in loss_sample.items()},
        }
        print("[AUDIT] %-24s reach=%-7d dropout=%-4d vectorLeak=%-6d operandLoss=%-4d %s"
              % (so_name, len(reach), sum(undec.values()), sum(leak.values()),
                 sum(loss.values()),
                 ", ".join("%s:%d" % kv for kv in leak.most_common(6))))

    report["summary"] = {
        "tool": "reverse-engine/tools/z_audit.py",
        "criterion": "reachable instructions whose decoded op bucket cannot represent "
                     "the operands, or whose operand registers have no decode slot",
        "dropoutTotal": total_drop,
        "vectorLeakTotal": total_leak,
        "operandLossTotal": total_loss,
        "clean": total_drop == 0 and total_leak == 0 and total_loss == 0,
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                   encoding="utf-8", newline="\n")
    print("[AUDIT] dropout=%d vectorLeak=%d operandLoss=%d clean=%s -> %s"
          % (total_drop, total_leak, total_loss,
             report["summary"]["clean"], OUT.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
