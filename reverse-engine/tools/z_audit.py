#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Z 组语义残留审计：找出「解码成功但语义桶不对」的指令。

两类残留：

  1. dropout —— `z_isa.decode` 返回 None（运行时会落到 when(op) 兜底分支）。
  2. vector-leak —— 操作数里有向量寄存器，却被映射进**只处理通用寄存器**的标量 op
     （例如 `add v0.4s, v1.4s, v2.4s` 被映射成 ADD_REG，运行期会去读 x1/x2）。
     这类不会触发 dropout，是**静默错**，必须单独统计。

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


def main() -> int:
    opnames = {v: k for k, v in z_isa.OPS.items()}
    report = {}
    total_leak = 0
    total_drop = 0

    for so_name, domain in ZT.SO:
        info = ZT.discover(ZT.JNI / so_name, domain)
        reach = sorted(info["reachable"])
        leak = Counter()
        leak_pair = Counter()
        leak_addr = defaultdict(list)
        undec = Counter()
        for a in reach:
            ins = info["ins"][a]
            d = z_isa.decode(ins)
            if d is None:
                undec[ins.mnemonic] += 1
                total_drop += 1
                continue
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
        }
        print("[AUDIT] %-24s reach=%-7d dropout=%-4d vectorLeak=%-6d %s"
              % (so_name, len(reach), sum(undec.values()), sum(leak.values()),
                 ", ".join("%s:%d" % kv for kv in leak.most_common(8))))

    report["summary"] = {
        "tool": "reverse-engine/tools/z_audit.py",
        "criterion": "reachable instructions whose decoded op bucket cannot represent the operands",
        "dropoutTotal": total_drop,
        "vectorLeakTotal": total_leak,
        "clean": total_drop == 0 and total_leak == 0,
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                   encoding="utf-8", newline="\n")
    print("[AUDIT] dropout=%d vectorLeak=%d clean=%s -> %s"
          % (total_drop, total_leak, report["summary"]["clean"], OUT.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
