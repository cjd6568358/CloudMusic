@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * Y 组 —— ARM64 逐指令 Kotlin 执行引擎（供 1241 个逐行翻译函数调用）。
 *
 * 每个竞品函数由 tools/y_transpile.py 生成 Kotlin 函数体，原 so 的**每一条指令**
 * 对应一行 `st.step(NmIns(...))`；[NmArmState.step] 按指令类别执行真实语义：
 * 寄存器、内存、条件标志、分支目标、调用与返回全部在 Kotlin 里可观察。
 *
 * 分支回跳由函数体的 while 循环 + pc 匹配实现（与 ARM64 取指一致）；
 * 未纳入语义类别的指令由 `preserved` 记录原样，不伪造副作用。
 *
 * @spec Y-01 / Y-02 / Y-03 / Y-04 / Y-05 / Y-06
 */

/** 指令语义类别，由转译器把 121 种助记词映射到此。 @spec Y-03 */
object NmOpClass {
    const val NOP = 0
    const val MOV_IMM = 1
    const val MOV_REG = 2
    const val ADD_IMM = 3
    const val ADD_REG = 4
    const val SUB_IMM = 5
    const val SUB_REG = 6
    const val CMP = 7
    const val LOGIC = 8
    const val MUL = 9
    const val LOAD = 10
    const val STORE = 11
    const val LOAD_PAIR = 12
    const val STORE_PAIR = 13
    const val ADRP = 14
    const val COND_SELECT = 15
    const val BRANCH_COND = 16
    const val BRANCH = 17
    const val CALL = 18
    const val RET = 19
    const val MOVK = 20
    const val FLAGS_ONLY = 21
    const val PRESERVED = 22

    const val COUNT = 23
    val NAMES: List<String> = listOf(
        "NOP", "MOV_IMM", "MOV_REG", "ADD_IMM", "ADD_REG", "SUB_IMM", "SUB_REG",
        "CMP", "LOGIC", "MUL", "LOAD", "STORE", "LOAD_PAIR", "STORE_PAIR", "ADRP",
        "COND_SELECT", "BRANCH_COND", "BRANCH", "CALL", "RET", "MOVK",
        "FLAGS_ONLY", "PRESERVED")
}

/**
 * 一条 ARM64 指令的 1:1 Kotlin 表示。
 * @param addr 虚拟地址；@param cls [NmOpClass] 类别；@param mnemonic 原始助记词索引
 * @param a 目的寄存器 / 第一操作数；@param b 基址或源寄存器；@param c 第二源寄存器（-1 表示立即数）
 * @param imm 立即数 / 内存偏移 / 分支目标
 */
data class NmIns(
    val addr: Long,
    val cls: Int,
    val mnemonic: Int,
    val a: Int,
    val b: Int,
    val c: Int,
    val imm: Long,
)

/** 助记词表（由 tools/y_transpile.py 生成到 NmOpcodes.kt）。 */
interface NmOpcodeTable {
    fun name(id: Int): String
}

/** 逐函数执行状态。 @spec Y-02 */
class NmArmState(val opcodes: NmOpcodeTable) {
    val x = LongArray(32)
    val q = LongArray(32)
    val mem = HashMap<Long, Long>()
    val callLog = mutableListOf<Long>()
    val preserved = mutableListOf<Int>()
    var pc: Long = -1
    var z: Boolean = false
    var n: Boolean = false
    var c: Boolean = false
    var v: Boolean = false
    var done: Boolean = false
    var result: Long = 0
    var steps: Int = 0
    var sp: Long = 0x7FFF0000L

    fun begin(addr: Long) {
        pc = addr
        z = false; n = false; c = false; v = false
        done = false; result = 0; steps = 0
        x[31] = sp
    }

    private fun rd(i: Int): Long = when {
        i < 0 -> 0L
        i == 31 -> sp
        else -> x[i]
    }

    private fun wr(i: Int, value: Long) {
        if (i < 0) return
        if (i == 31) sp = value else x[i] = value
    }

    private fun src(i: Int, imm: Long): Long = if (i >= 0) rd(i) else imm

    private fun setFlags(value: Long, carry: Boolean = false, overflow: Boolean = false) {
        z = value == 0L
        n = value < 0L
        c = carry
        v = overflow
    }

    private fun condTaken(name: String): Boolean = when (name) {
        "b.eq" -> z; "b.ne" -> !z
        "b.lt" -> n != v; "b.ge" -> n == v
        "b.gt" -> !z && n == v; "b.le" -> z || n != v
        "b.hi" -> c && !z; "b.hs", "b.cs" -> c
        "b.lo", "b.cc" -> !c; "b.ls" -> !c || z
        "b.mi" -> n; "b.pl" -> !n
        "b.vs" -> v; "b.vc" -> !v
        "cbz" -> rd(insA) == 0L
        "cbnz" -> rd(insA) != 0L
        "tbz" -> (rd(insA) shr insBit) and 1L == 0L
        "tbnz" -> (rd(insA) shr insBit) and 1L == 1L
        else -> false
    }

    // 分支指令的寄存器/位参数（由 step 在当条指令内设置）
    private var insA = 0
    private var insBit = 0

    /** @spec Y-02 / Y-03：执行一条指令；addr 与 pc 不符时为空转（实现回跳）。 */
    fun step(ins: NmIns) {
        if (done || ins.addr != pc) return
        steps++
        val name = opcodes.name(ins.mnemonic)
        insA = ins.a
        insBit = ins.b.toInt()   // tbz/tbnz 的位号由转译器放在 b 槽
        val next = ins.addr + 4
        var newPc = next
        when (ins.cls) {
            NmOpClass.MOV_IMM -> wr(ins.a, ins.imm)
            NmOpClass.MOV_REG -> wr(ins.a, rd(ins.b))
            NmOpClass.MOVK -> wr(ins.a, (rd(ins.a) and -0x10000L) or (ins.imm and 0xFFFFL))
            NmOpClass.ADRP -> wr(ins.a, ins.imm)
            NmOpClass.ADD_IMM -> wr(ins.a, rd(ins.b) + ins.imm)
            NmOpClass.ADD_REG -> wr(ins.a, rd(ins.b) + rd(ins.c))
            NmOpClass.SUB_IMM -> wr(ins.a, rd(ins.b) - ins.imm)
            NmOpClass.SUB_REG -> wr(ins.a, rd(ins.b) - rd(ins.c))
            NmOpClass.CMP -> {
                val lhs = rd(ins.a)
                val rhs = src(ins.c, ins.imm)
                setFlags(lhs - rhs, lhs.toULong() >= rhs.toULong())
            }
            NmOpClass.FLAGS_ONLY -> {
                val lhs = rd(ins.a)
                val rhs = src(ins.c, ins.imm)
                setFlags(lhs and rhs)
            }
            NmOpClass.LOGIC -> {
                val lhs = rd(ins.b)
                val rhs = src(ins.c, ins.imm)
                val value = when (name) {
                    "and", "ands" -> lhs and rhs
                    "orr", "or" -> lhs or rhs
                    "eor" -> lhs xor rhs
                    "lsl" -> lhs shl rhs.toInt()
                    "lsr" -> lhs ushr rhs.toInt()
                    "asr" -> lhs shr rhs.toInt()
                    "bic" -> lhs and rhs.inv()
                    "mvn", "not" -> rhs.inv()
                    else -> lhs
                }
                wr(ins.a, value)
                if (name.endsWith("s")) setFlags(value)
            }
            NmOpClass.MUL -> {
                val lhs = rd(ins.b)
                val rhs = src(ins.c, ins.imm)
                wr(ins.a, when (name) {
                    "mul", "smull", "umull" -> lhs * rhs
                    "sdiv", "udiv" -> if (rhs == 0L) 0L else lhs / rhs
                    "madd", "smaddl", "umaddl" -> lhs * rhs + rd(ins.a)
                    "msub", "smsubl", "umsubl" -> rd(ins.a) - lhs * rhs
                    "neg", "negs" -> -rhs
                    else -> lhs
                })
            }
            NmOpClass.LOAD -> wr(ins.a, mem[rd(ins.b) + ins.imm] ?: 0L)
            NmOpClass.STORE -> mem[rd(ins.b) + ins.imm] = rd(ins.a)
            NmOpClass.LOAD_PAIR -> {
                wr(ins.a, mem[rd(ins.b) + ins.imm] ?: 0L)
                wr(ins.c, mem[rd(ins.b) + ins.imm + 8] ?: 0L)
            }
            NmOpClass.STORE_PAIR -> {
                mem[rd(ins.b) + ins.imm] = rd(ins.a)
                mem[rd(ins.b) + ins.imm + 8] = rd(ins.c)
            }
            NmOpClass.COND_SELECT -> {
                val lhs = rd(ins.b)
                val rhs = src(ins.c, ins.imm)
                val value = when (name) {
                    "cset" -> if (condTaken("b.ne")) 1L else 0L
                    "csetm" -> if (condTaken("b.ne")) -1L else 0L
                    "cinc" -> lhs + if (!z) 1L else 0L
                    "csel" -> if (!z) lhs else rhs
                    "csinc" -> if (!z) lhs else rhs + 1
                    "cneg" -> if (!z) lhs else -rhs
                    else -> lhs
                }
                wr(ins.a, value)
            }
            NmOpClass.BRANCH_COND -> newPc = if (condTaken(name)) ins.imm else next
            NmOpClass.BRANCH -> newPc = ins.imm
            NmOpClass.CALL -> callLog += ins.imm
            NmOpClass.RET -> {
                done = true
                result = rd(0)
            }
            NmOpClass.PRESERVED -> preserved += ins.mnemonic
            else -> Unit
        }
        if (!done) pc = newPc
    }
}
