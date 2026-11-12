@file:Suppress("unused", "KotlinConstantConditions", "RemoveRedundantQualifierName")
package cloudmusic.reverse.kernel

/**
 * Z 组 —— 竞品播放内核 ARM64 指令的**语义完备** Kotlin 执行引擎。
 *
 * Y 组当初只按 23 个粗类别解释指令，丢掉了访问宽度（`ldrb` 按 8 字节读）、
 * 真实条件码（`cset` 一律按 NE）、移位/扩展（`add x0,x1,x2,lsl #3`）、
 * pre/post-index 写回与调用语义（`bl` 只记日志）。这些会让逐行翻译跑不出
 * 原函数的结果，本文件按 [NmOp] 的 206 个语义 op 逐一补齐：
 *
 *  * 整数：加减带进位/溢出、逻辑+移位、乘加/长乘/高位乘、位域四种别名、
 *    符号/零扩展、16 个条件码的条件选择与条件比较；
 *  * 访存：字节粒度内存，1/2/4/8/16 字节宽度，符号/零扩展加载，
 *    pre/post-index 写回，寄存器偏移（lsl / sxtw 等扩展），LDP/STP 成对，
 *    LDXR/STXR 独占（单线程模型），LDAR/STLR 获取释放；
 *  * 浮点：S/D/H 精度四则、取负绝对值开方、比较、精度转换、
 *    整数↔浮点（含各舍入方向）、FMADD 家族、FCSEL；
 *  * NEON：逐元素整数/浮点运算、LD1~LD4/ST1~ST4、ZIP/UZP/TRN、EXT/TBL、
 *    CNT/ADDV/UADDLV/SMAXV、REV64、SSHLL/XTN/SHRN、BIT/BIF/BSL；
 *  * 系统：MRS/MSR（未知外部状态用确定性桩并登记）、NOP/PRFM/CLREX、BRK/UDF 陷阱。
 *
 * 分支/调用/返回统一走 pc：`bl` 写 x30 并跳目标，`ret` 跳 x30，
 * 目标代码块由 [NmMachine] 按 pc 区间分派——跨函数调用、尾调用、间接跳转
 * 都能真正执行，不需要递归，也不会把 6326 个代码块塞进一个 JVM 方法。
 *
 * @spec Z-01 / Z-02 / Z-03 / Z-04 / Z-05 / Z-06 / Z-07 / Z-08
 */

/** 指令标志位（与 tools/z_isa.py 的 F_* 一一对应）。 @spec Z-02 */
object NmF {
    const val W8 = 0
    const val W4 = 1
    const val W2 = 2
    const val W1 = 3
    const val SIGNED = 1 shl 2
    const val WB = 1 shl 3
    const val POST = 1 shl 4
    const val RDV = 1 shl 5
    const val RNV = 1 shl 6
    const val RMV = 1 shl 7
    const val SETS = 1 shl 8
    const val RDSP = 1 shl 9
    const val INDEX = 1 shl 10
    const val ACQ = 1 shl 11
    const val ARR_SHIFT = 12
    const val S128 = 1 shl 16
    /** post-index 增量来自寄存器：`imm` 槽位存寄存器号（结构访存的 `[xN], xM` 形式）。 */
    const val POSTREG = 1 shl 17
}

/** 条件码（capstone ARM64_CC_*）。 */
object NmCC {
    const val EQ = 1
    const val NE = 2
    const val HS = 3
    const val LO = 4
    const val MI = 5
    const val PL = 6
    const val VS = 7
    const val VC = 8
    const val HI = 9
    const val LS = 10
    const val GE = 11
    const val LT = 12
    const val GT = 13
    const val LE = 14
    const val AL = 15
}

/** NZCV 位。 */
object NmFlag {
    const val N = 1
    const val Z = 2
    const val C = 4
    const val V = 8
}

/** NEON 排列编码。 */
object NmArr {
    const val NONE = 0
    const val B8 = 1
    const val B16 = 2
    const val H4 = 3
    const val H8 = 4
    const val S2 = 5
    const val S4 = 6
    const val D2 = 7
    const val D1 = 8
    const val S1 = 9
    const val H1 = 10
    const val B1 = 11
    const val S8 = 12
    const val B4 = 13
    const val H2 = 14
    const val S16 = 15
}

/**
 * 一条 ARM64 指令的完整解码结果。
 * @param addr 虚拟地址 @param op [NmOp] 语义 op
 * @param rd 目的寄存器 @param rn 第一源/基址 @param rm 第二源/索引
 * @param imm 立即数/内存位移/分支目标/移位位数/位域 lsb
 * @param imm2 写回量/第 4 操作数/位号/位域宽度
 * @param f 见 [NmF] @param c 条件码+移位+扩展+通道+寄存器个数+源宽度
 */
data class NmIns(
    val addr: Long,
    val op: Int,
    val rd: Int,
    val rn: Int,
    val rm: Int,
    val imm: Long,
    val imm2: Long,
    val f: Int,
    val c: Int,
)

/** 外部依赖登记（MRS 读计数器、svc、未翻译目标等）。 @spec Z-07 */
class NmExternal {
    val unresolvedTargets = HashMap<Long, Int>()
    val sysregReads = HashMap<Int, Int>()
    val syscalls = HashMap<Int, Int>()

    /** 踩到 `BRK` 的地址 → 次数。语料本身有 8 条 BRK（断言/trap 路径），
     *  抽样跑到它们属于**真实行为**，不是语义残留；这里按地址登记，便于
     *  契约测试断言「每处 trap 都能对上一条真实 BRK」。 @spec Z-11 */
    val trapTargets = HashMap<Long, Int>()

    /** 落到 `when(op)` 兜底分支的 op → 次数。非空即代表语义有残留，不得放行。 */
    val dropoutOps = HashMap<Int, Int>()

    /** 运行期实际执行到过的 op 位图 —— 用来给出「动态覆盖面」，不只靠静态分支表。 */
    val opSeen = BooleanArray(256)

    var traps = 0
    var budgetExceeded = 0
    var externalCalls = 0
    var dropouts = 0
}

/** 逐指令执行状态：寄存器/内存/标志全部在 Kotlin 侧可观察。 @spec Z-06 */
class NmArmState(val ext: NmExternal = NmExternal()) {
    val x = LongArray(31)
    val v = LongArray(64)

    var sp: Long = 0x7FFF_0000L
    var pc: Long = RET_SENTINEL
    var flags: Int = 0
    var steps: Long = 0
    var fpcr: Long = 0
    var fpsr: Long = 0

    /**
     * 指令数硬上限（绝对步数）。块内循环不受 NmMachine 的分派计数约束，
     * 靠这一条在 `i()` 里兜住，避免死循环把测试挂住。
     */
    var abortAt: Long = Long.MAX_VALUE

    /**
     * 差分调试用 pc 轨迹：非空时逐条记录**实际执行**的指令地址。
     * 默认 null（只多一次判空），用于和 Unicorn 侧逐条对轨迹，
     * 定位「第一条走岔的指令」而不是只看末态差异。 @spec Z-09
     */
    var pcTrace: ArrayList<Long>? = null

    private var exclAddr: Long = 0
    private var exclValid: Boolean = false
    private var sysTick: Long = 0

    val callLog = ArrayList<Long>(256)

    private val pages = HashMap<Long, ByteArray>()

    companion object {
        const val RET_SENTINEL: Long = -0x2152_FFFF_0000_0000L
        const val FUNC_STEP_CAP: Int = 4_000_000
        const val PAGE_MASK: Long = 4095L
        const val PAGE_BASE: Long = 4095L.inv()
    }

    // ---------------- 寄存器 ----------------
    fun ir(i: Int): Long = when {
        i < 0 -> 0L
        i == 31 -> sp
        i == 32 -> 0L
        else -> x[i]
    }

    fun iw(i: Int, value: Long, width: Int) {
        if (i < 0 || i == 32) return
        val v = value and maskOfWidth(width)
        if (i == 31) sp = v else x[i] = v
    }

    fun vlo(i: Int): Long = if (i in 0 until 32) v[i * 2] else 0L
    fun vhi(i: Int): Long = if (i in 0 until 32) v[i * 2 + 1] else 0L
    fun setV(i: Int, lo: Long, hi: Long) {
        if (i !in 0 until 32) return
        v[i * 2] = lo
        v[i * 2 + 1] = hi
    }

    // ---------------- 内存 ----------------
    fun rb(a: Long): Int {
        val p = pages[a and PAGE_BASE] ?: return 0
        return p[(a and PAGE_MASK).toInt()].toInt() and 0xFF
    }

    fun wb(a: Long, value: Int) {
        val p = pages.getOrPut(a and PAGE_BASE) { ByteArray(4096) }
        p[(a and PAGE_MASK).toInt()] = value.toByte()
    }

    fun load(a: Long, size: Int): Long {
        var r = 0L
        for (k in 0 until size) r = r or (rb(a + k).toLong() shl (8 * k))
        return r
    }

    fun store(a: Long, size: Int, value: Long) {
        for (k in 0 until size) wb(a + k, (value ushr (8 * k)).toInt())
    }

    // ---------------- 标志 ----------------
    private fun maskOfWidth(w: Int): Long = when (w) {
        NmF.W8 -> -1L
        NmF.W4 -> 0xFFFF_FFFFL
        NmF.W2 -> 0xFFFFL
        else -> 0xFFL
    }

    fun maskOfSize(size: Int): Long = when (size) {
        1 -> 0xFFL
        2 -> 0xFFFFL
        4 -> 0xFFFF_FFFFL
        else -> -1L
    }

    fun signExtend(value: Long, size: Int): Long = when (size) {
        1 -> value.toByte().toLong()
        2 -> value.toShort().toLong()
        4 -> value.toInt().toLong()
        else -> value
    }

    /**
     * 把低 [bits] 位按二进制补码符号扩展到 64 位。
     *
     * 位域指令（`sbfx` / `sbfiz`）的宽度是 1~64 的**任意值**，不是 1/2/4/8 的幂，
     * 所以不能复用 [signExtend]（size 语义是字节数）。早期写成 `signExtend(x, 1 + bits / 8)`，
     * `bits = 16` 时算出 size = 3，直接落进 `else -> value` 分支完全不扩展。
     * @spec Z-11
     */
    fun signExtendBits(value: Long, bits: Int): Long {
        if (bits <= 0 || bits >= 64) return value
        val sh = 64 - bits
        return (value shl sh) shr sh
    }

    fun setNZ(result: Long, width: Int) {
        val bits = if (width == NmF.W8) 64 else 32
        val v = if (bits == 64) result else result and 0xFFFF_FFFFL
        flags = (flags and (NmFlag.C or NmFlag.V)) or
            (if (v == 0L) NmFlag.Z else 0) or
            (if ((v ushr (bits - 1)) and 1L == 1L) NmFlag.N else 0)
    }

    fun setAddFlags(a: Long, b: Long, r: Long, isSub: Boolean, width: Int) {
        val bits = if (width == NmF.W8) 64 else 32
        val m = maskOfWidth(width)
        val aa = a and m
        val bb = b and m
        val rr = r and m
        val signBit = if (bits == 64) Long.MIN_VALUE else Int.MIN_VALUE.toLong()
        val n = if ((rr and signBit) != 0L) NmFlag.N else 0
        val z = if (rr == 0L) NmFlag.Z else 0
        val c: Int
        val vf: Int
        if (isSub) {
            c = if (aa.toULong() >= bb.toULong()) NmFlag.C else 0
            vf = if (((aa xor bb) and (aa xor rr)) and signBit != 0L) NmFlag.V else 0
        } else {
            val carry = ((aa and bb) or ((aa or bb) and rr.inv())) and signBit
            c = if (carry != 0L) NmFlag.C else 0
            vf = if (((aa xor rr) and (bb xor rr)) and signBit != 0L) NmFlag.V else 0
        }
        flags = n or z or c or vf
    }

    fun condTrue(cc: Int): Boolean {
        val n = flags and NmFlag.N != 0
        val z = flags and NmFlag.Z != 0
        val c = flags and NmFlag.C != 0
        val o = flags and NmFlag.V != 0
        return when (cc) {
            NmCC.EQ -> z
            NmCC.NE -> !z
            NmCC.HS -> c
            NmCC.LO -> !c
            NmCC.MI -> n
            NmCC.PL -> !n
            NmCC.VS -> o
            NmCC.VC -> !o
            NmCC.HI -> c && !z
            NmCC.LS -> !c || z
            NmCC.GE -> n == o
            NmCC.LT -> n != o
            NmCC.GT -> !z && n == o
            NmCC.LE -> z || n != o
            else -> true
        }
    }

    // ---------------- 解码字段 ----------------
    private fun widthOf(f: Int): Int = f and 3
    private fun isSigned(f: Int): Boolean = f and NmF.SIGNED != 0
    private fun isRdVec(f: Int): Boolean = f and NmF.RDV != 0
    private fun isRnVec(f: Int): Boolean = f and NmF.RNV != 0
    private fun arrOf(f: Int): Int = (f ushr NmF.ARR_SHIFT) and 0xF
    private fun is128(f: Int): Boolean = f and NmF.S128 != 0
    private fun condOf(c: Int): Int = c and 0xF
    private fun shiftOf(c: Int): Int = (c ushr 4) and 3
    private fun shamOf(c: Int): Int = (c ushr 6) and 0x3F
    private fun extOf(c: Int): Int = (c ushr 12) and 7
    private fun laneOf(c: Int): Int = (c ushr 15) and 0xF
    private fun regCount(c: Int): Int = ((c ushr 20) and 0xF).let { if (it <= 0) 1 else it }
    private fun srcWidthOf(c: Int): Int = (c ushr 24) and 3

    /** 元素宽度覆盖（`ldpsw` 取 4 字节但写 64 位寄存器）；0 = 用目的宽度。 */
    private fun auxEsz(c: Int): Int = (c ushr 26) and 3

    /** 第三操作数是否「按元素」形式（`fmul v0.4s, v1.4s, v2.s[1]` 的 `v2.s[1]`）。 */
    private fun elOf(c: Int): Int = (c ushr 19) and 1

    /** CCMP/FCCMP 的 `#nzcv` 立即数是 **ARM 的 NZCV 位序**（N=8,Z=4,C=2,V=1），
     * 与内部 [NmFlag] 编码（N=1,Z=2,C=4,V=8）正好**位序相反**。早期直接
     * `flags = imm`，`#4`（ARM 的 Z）被读成内部 C（实测 audio-legacy @0x188a4：
     * 条件为假时落 `flags = 4`，随后 `fcsel ..., gt` 走反）。@spec Z-11
     */
    private fun nzcvToFlags(n: Int): Int =
        ((n and 1) shl 3) or ((n and 2) shl 1) or ((n ushr 1) and 2) or ((n ushr 3) and 1)

    private fun extendOperand(value: Long, ext: Int): Long = when (ext) {
        1 -> value and 0xFFL
        2 -> value and 0xFFFFL
        3 -> value and 0xFFFF_FFFFL
        4 -> value
        5 -> value.toByte().toLong()
        6 -> value.toShort().toLong()
        7 -> value.toInt().toLong()
        else -> value
    }

    private fun shiftOperand(value: Long, sh: Int, amount: Int, width: Int): Long {
        if (amount == 0) return value
        val bits = if (width == NmF.W8) 64 else 32
        val x = if (bits == 64) value else value and 0xFFFF_FFFFL
        return when (sh) {
            0 -> x shl amount
            1 -> x ushr amount
            2 -> if (bits == 64) x shr amount else (x.toInt() shr amount).toLong()
            else -> java.lang.Long.rotateRight(x, amount)
        }
    }

    private fun ones(n: Int): Long = when {
        n <= 0 -> 0L
        n >= 64 -> -1L
        else -> (1L shl n) - 1L
    }

    // ---------------- 向量元素 ----------------
    fun elemSize(arr: Int): Int = when (arr) {
        NmArr.B8, NmArr.B16, NmArr.B1, NmArr.B4 -> 1
        NmArr.H4, NmArr.H8, NmArr.H1, NmArr.H2 -> 2
        NmArr.S2, NmArr.S4, NmArr.S1, NmArr.S8, NmArr.S16 -> 4
        else -> 8
    }

    fun elemCount(arr: Int): Int = when (arr) {
        NmArr.B8 -> 8
        NmArr.B16 -> 16
        NmArr.H4 -> 4
        NmArr.H8 -> 8
        NmArr.S2 -> 2
        NmArr.S4 -> 4
        NmArr.D2 -> 2
        NmArr.D1 -> 1
        NmArr.S1 -> 1
        NmArr.H1 -> 1
        NmArr.B1 -> 1
        NmArr.S8 -> 8
        NmArr.B4 -> 4
        NmArr.H2 -> 2
        NmArr.S16 -> 16
        else -> 2
    }

    fun vgetSz(reg: Int, size: Int, idx: Int): Long {
        val bit = idx * size * 8
        return if (bit < 64) (vlo(reg) ushr bit) and maskOfSize(size)
        else (vhi(reg) ushr (bit - 64)) and maskOfSize(size)
    }

    fun vsetSz(reg: Int, size: Int, idx: Int, value: Long) {
        if (reg !in 0 until 32) return
        val bit = idx * size * 8
        val m = maskOfSize(size)
        if (bit < 64) {
            val sh = bit
            v[reg * 2] = (v[reg * 2] and (m shl sh).inv()) or ((value and m) shl sh)
        } else {
            val sh = bit - 64
            v[reg * 2 + 1] = (v[reg * 2 + 1] and (m shl sh).inv()) or ((value and m) shl sh)
        }
    }

    fun vget(reg: Int, arr: Int, idx: Int): Long = vgetSz(reg, elemSize(arr), idx)
    fun vset(reg: Int, arr: Int, idx: Int, value: Long) =
        vsetSz(reg, elemSize(arr), idx, value)

    // ---------------- 浮点 ----------------
    private fun halfToFloat(h: Int): Float {
        val sign = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1F
        val man = h and 0x3FF
        val f: Float = when {
            exp == 0 && man == 0 -> 0.0f
            exp == 0 -> (man.toFloat() / 1024.0f) * 6.1035156E-5f
            exp == 31 && man == 0 -> Float.POSITIVE_INFINITY
            exp == 31 -> Float.NaN
            else -> (1.0f + man.toFloat() / 1024.0f) *
                Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
        return if (sign == 1) -f else f
    }

    private fun floatToHalf(f: Float): Int {
        if (f.isNaN()) return 0x7E00
        val bits = f.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        val man = bits and 0x7FFFFF
        return when {
            f == 0.0f -> sign
            exp <= 0 -> sign
            exp >= 31 -> sign or 0x7C00
            else -> sign or (exp shl 10) or (man ushr 13)
        }
    }

    fun fget(reg: Int, width: Int): Double = when (width) {
        NmF.W4 -> Float.fromBits(vlo(reg).toInt()).toDouble()
        NmF.W2 -> halfToFloat((vlo(reg) and 0xFFFFL).toInt()).toDouble()
        else -> Double.fromBits(vlo(reg))
    }

    fun fset(reg: Int, width: Int, value: Double) {
        when (width) {
            NmF.W4 -> setV(reg, value.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL, 0L)
            NmF.W2 -> setV(reg, floatToHalf(value.toFloat()).toLong() and 0xFFFFL, 0L)
            else -> setV(reg, value.toRawBits(), 0L)
        }
    }

    private fun fcmpFlags(a: Double, b: Double) {
        flags = if (a.isNaN() || b.isNaN()) NmFlag.C or NmFlag.V
        else if (a == b) NmFlag.Z or NmFlag.C
        else if (a < b) NmFlag.N
        else NmFlag.C
    }

    // ---------------- 执行 ----------------
    /** 执行一条指令；addr 与 pc 不符时为空转（代码块内跳过）。 @spec Z-06 */
    fun i(addr: Long, op: Int, rd: Int, rn: Int, rm: Int, imm: Long,
          imm2: Long = 0L, f: Int = 0, c: Int = 0) {
        if (addr != pc) return
        steps++
        if (steps > abortAt) {
            ext.budgetExceeded++
            pc = RET_SENTINEL
            return
        }
        if (op in ext.opSeen.indices) ext.opSeen[op] = true
        pcTrace?.add(addr)
        val next = addr + 4
        val width = widthOf(f)
        val arr = arrOf(f)
        val cond = condOf(c)
        when (op) {
            NmOp.NOP, NmOp.PRFM, NmOp.CLREX -> pc = next
            NmOp.BRK -> {
                ext.traps++
                ext.trapTargets[addr] = (ext.trapTargets[addr] ?: 0) + 1
                pc = RET_SENTINEL
            }

            NmOp.MRS -> {
                ext.sysregReads[imm.toInt()] = (ext.sysregReads[imm.toInt()] ?: 0) + 1
                val value = when (imm.toInt()) {
                    1 -> 0x7000_0000L
                    2, 12, 13 -> { sysTick += 1; sysTick }
                    3 -> 24_000_000L
                    4 -> fpcr
                    5 -> fpsr
                    6 -> (flags.toLong() shl 28)
                    else -> 0L
                }
                iw(rd, value, width)
                pc = next
            }
            NmOp.MSR -> {
                when (imm.toInt()) {
                    4 -> fpcr = ir(rn)
                    5 -> fpsr = ir(rn)
                    6 -> flags = ((ir(rn) ushr 28) and 0xF).toInt()
                }
                pc = next
            }
            NmOp.SVC -> {
                ext.syscalls[imm.toInt()] = (ext.syscalls[imm.toInt()] ?: 0) + 1
                pc = next
            }

            NmOp.B -> pc = imm
            NmOp.B_COND -> pc = if (condTrue(cond)) imm else next
            NmOp.CBZ -> pc = if (ir(rd) == 0L) imm else next
            NmOp.CBNZ -> pc = if (ir(rd) != 0L) imm else next
            NmOp.TBZ -> pc = if (((ir(rd) ushr imm.toInt()) and 1L) == 0L) imm2 else next
            NmOp.TBNZ -> pc = if (((ir(rd) ushr imm.toInt()) and 1L) == 1L) imm2 else next
            NmOp.BL, NmOp.BLR -> {
                val target = if (op == NmOp.BL) imm else ir(rn)
                if (callLog.size < 4096) callLog.add(target)
                x[30] = next
                pc = target
            }
            NmOp.BR -> pc = ir(rn)
            NmOp.RET -> pc = ir(rn)

            NmOp.ADR, NmOp.ADRP -> { iw(rd, imm, NmF.W8); pc = next }

            NmOp.MOV_IMM -> { iw(rd, imm, width); pc = next }
            NmOp.MOV_RR -> {
                if (isRnVec(f)) {
                    if (arr != NmArr.NONE && !isRdVec(f)) {
                        iw(rd, vget(rn, arr, laneOf(c)), width)
                    } else {
                        iw(rd, vlo(rn), width)
                    }
                } else iw(rd, ir(rn), width)
                pc = next
            }
            NmOp.MOV_VEC -> {
                // imm != 0：带 lane 的标量搬移，只搬一个元素到低端、高位清零
                // （`mov d0, v0.d[1]` / `mov x0, v1.d[0]`）。
                // imm == 0：整寄存器拷贝（`mov v0.16b, v1.16b`）。@spec Z-10
                if (imm != 0L) {
                    val got = vgetSz(rn, imm.toInt(), laneOf(c))
                    if (isRdVec(f)) setV(rd, got, 0L) else iw(rd, got, width)
                } else {
                    setV(rd, vlo(rn), vhi(rn))
                }
                pc = next
            }
            NmOp.MOV_VEC_INS -> {
                // `mov vD.T[k], xM` / `mov vD.T[k], vN.T[m]`：只改一条 lane，
                // 其它 lane 原样保留。imm = 元素字节数，imm2 = 源 lane，laneOf(c) = 目标 lane。
                // @spec Z-11
                val sz = imm.toInt()
                val src = if (isRnVec(f)) vgetSz(rn, sz, imm2.toInt()) else ir(rn)
                vsetSz(rd, sz, laneOf(c), src and maskOfSize(sz))
                pc = next
            }
            NmOp.MOVK -> {
                val sh = shamOf(c)
                iw(rd, (ir(rd) and (0xFFFFL shl sh).inv()) or ((imm and 0xFFFFL) shl sh), width)
                pc = next
            }

            NmOp.ADD_IMM, NmOp.ADDS_IMM, NmOp.SUB_IMM, NmOp.SUBS_IMM -> {
                val isSub = op == NmOp.SUB_IMM || op == NmOp.SUBS_IMM
                val rhs = shiftOperand(imm, shiftOf(c), shamOf(c), NmF.W8)
                val a = ir(rn)
                val r = if (isSub) a - rhs else a + rhs
                if (op == NmOp.ADDS_IMM || op == NmOp.SUBS_IMM) {
                    setAddFlags(a, rhs, r, isSub, width)
                }
                iw(rd, r, width); pc = next
            }
            NmOp.ADD_REG, NmOp.ADDS_REG, NmOp.SUB_REG, NmOp.SUBS_REG -> {
                val isSub = op == NmOp.SUB_REG || op == NmOp.SUBS_REG
                var b = extendOperand(ir(rm), extOf(c))
                b = shiftOperand(b, shiftOf(c), shamOf(c), width)
                val a = ir(rn)
                val r = if (isSub) a - b else a + b
                if (op == NmOp.ADDS_REG || op == NmOp.SUBS_REG) {
                    setAddFlags(a, b, r, isSub, width)
                }
                iw(rd, r, width); pc = next
            }
            NmOp.CMP_IMM, NmOp.CMN_IMM -> {
                val rhs = shiftOperand(imm, shiftOf(c), shamOf(c), NmF.W8)
                val a = ir(rd)
                val isCmn = op == NmOp.CMN_IMM
                val r = if (isCmn) a + rhs else a - rhs
                setAddFlags(a, rhs, r, !isCmn, width)
                pc = next
            }
            NmOp.CMP_REG, NmOp.CMN_REG -> {
                var b = extendOperand(ir(rm), extOf(c))
                b = shiftOperand(b, shiftOf(c), shamOf(c), width)
                val a = ir(rd)
                val isCmn = op == NmOp.CMN_REG
                val r = if (isCmn) a + b else a - b
                setAddFlags(a, b, r, !isCmn, width)
                pc = next
            }
            NmOp.NEG, NmOp.NEGS -> {
                val b = ir(rn)
                val r = -b
                if (op == NmOp.NEGS) setAddFlags(0L, b, r, true, width)
                iw(rd, r, width); pc = next
            }
            NmOp.MUL, NmOp.MNEG -> {
                val r = ir(rn) * ir(rm)
                iw(rd, if (op == NmOp.MNEG) -r else r, width); pc = next
            }
            NmOp.MADD, NmOp.MSUB -> {
                val acc = if (imm2 in 0..31) ir(imm2.toInt()) else 0L
                val p = ir(rn) * ir(rm)
                iw(rd, if (op == NmOp.MADD) acc + p else acc - p, width); pc = next
            }
            NmOp.SMULL, NmOp.UMULL, NmOp.SMADDL, NmOp.UMADDL -> {
                val signed = op == NmOp.SMULL || op == NmOp.SMADDL
                val a = if (signed) signExtend(ir(rn), 4) else ir(rn) and 0xFFFF_FFFFL
                val b = if (signed) signExtend(ir(rm), 4) else ir(rm) and 0xFFFF_FFFFL
                val p = a * b
                val acc = if (imm2 in 0..31) ir(imm2.toInt()) else 0L
                iw(rd, if (op == NmOp.SMULL || op == NmOp.UMULL) p else acc + p, NmF.W8)
                pc = next
            }
            NmOp.SMULH, NmOp.UMULH -> {
                // UMULH 是**无符号** 64×64 取高半。JVM 只有 signed 的
                // `Math.multiplyHigh`，无符号要按恒等式补两项：
                //   umulh(a,b) = mulhi(a,b) + (a<0 ? b : 0) + (b<0 ? a : 0)
                // 早期写成 `(a and b) < 0` 判一项，符号组合不同就少补/多补
                // （实测 audio @0xb2b74：0x18 被算成 0xFFFF_FFFF_FFFF_FFF8）。@spec Z-11
                val a = ir(rn)
                val b = ir(rm)
                val r = if (op == NmOp.SMULH) Math.multiplyHigh(a, b)
                else Math.multiplyHigh(a, b) +
                    (if (a < 0L) b else 0L) + (if (b < 0L) a else 0L)
                iw(rd, r, NmF.W8); pc = next
            }
            NmOp.SDIV -> {
                val a = ir(rn); val b = ir(rm)
                val r = when {
                    b == 0L -> 0L
                    a == Long.MIN_VALUE && b == -1L -> Long.MIN_VALUE
                    else -> a / b
                }
                iw(rd, r, width); pc = next
            }
            NmOp.UDIV -> {
                val b = ir(rm)
                iw(rd, if (b == 0L) 0L else java.lang.Long.divideUnsigned(ir(rn), b), width)
                pc = next
            }

            NmOp.AND_IMM, NmOp.ANDS_IMM, NmOp.ORR_IMM, NmOp.EOR_IMM -> {
                val a = ir(rn)
                val r = when (op) {
                    NmOp.AND_IMM, NmOp.ANDS_IMM -> a and imm
                    NmOp.ORR_IMM -> a or imm
                    else -> a xor imm
                }
                if (op == NmOp.ANDS_IMM) setNZ(r, width)
                iw(rd, r, width); pc = next
            }
            NmOp.AND_REG, NmOp.ANDS_REG, NmOp.ORR_REG, NmOp.EOR_REG,
            NmOp.BIC_REG, NmOp.BICS_REG, NmOp.ORN_REG -> {
                val b = shiftOperand(ir(rm), shiftOf(c), shamOf(c), width)
                val a = ir(rn)
                val r = when (op) {
                    NmOp.AND_REG, NmOp.ANDS_REG -> a and b
                    NmOp.ORR_REG -> a or b
                    NmOp.EOR_REG -> a xor b
                    NmOp.BIC_REG, NmOp.BICS_REG -> a and b.inv()
                    else -> a or b.inv()
                }
                if (op == NmOp.ANDS_REG || op == NmOp.BICS_REG) setNZ(r, width)
                iw(rd, r, width); pc = next
            }
            NmOp.MVN -> { iw(rd, ir(rn).inv(), width); pc = next }
            NmOp.TST_IMM -> {
                flags = if ((ir(rd) and imm) == 0L) NmFlag.Z else 0
                pc = next
            }
            NmOp.TST_REG -> {
                flags = if ((ir(rd) and ir(rm)) == 0L) NmFlag.Z else 0
                pc = next
            }
            NmOp.LSL_IMM, NmOp.LSR_IMM, NmOp.ASR_IMM, NmOp.ROR_IMM -> {
                val sh = when (op) {
                    NmOp.LSL_IMM -> 0
                    NmOp.LSR_IMM -> 1
                    NmOp.ASR_IMM -> 2
                    else -> 3
                }
                iw(rd, shiftOperand(ir(rn), sh, imm.toInt(), width), width); pc = next
            }
            NmOp.LSL_REG, NmOp.LSR_REG, NmOp.ASR_REG, NmOp.ROR_REG -> {
                val bits = if (width == NmF.W8) 64 else 32
                val sh = when (op) {
                    NmOp.LSL_REG -> 0
                    NmOp.LSR_REG -> 1
                    NmOp.ASR_REG -> 2
                    else -> 3
                }
                iw(rd, shiftOperand(ir(rn), sh, ir(rm).toInt() and (bits - 1), width), width)
                pc = next
            }
            NmOp.EXTR -> {
                val bits = if (width == NmF.W8) 64 else 32
                val lsb = imm.toInt() and (bits - 1)
                val hiV = ir(rn)
                val loV = ir(rm)
                val r = if (bits == 64) {
                    if (lsb == 0) loV else (loV ushr lsb) or (hiV shl (64 - lsb))
                } else {
                    val l = loV and 0xFFFF_FFFFL
                    val h = hiV and 0xFFFF_FFFFL
                    if (lsb == 0) l else ((l ushr lsb) or (h shl (32 - lsb))) and 0xFFFF_FFFFL
                }
                iw(rd, r, width); pc = next
            }

            NmOp.UBFX, NmOp.SBFX, NmOp.UBFIZ, NmOp.SBFIZ -> {
                // 提取（*BFX）先右移再取域；插入（*BFIZ）先取域再左移。有符号变体的扩展
                // 必须按**位宽**做（见 [signExtendBits]），不能按字节数。
                // 实测锚点：audio @0x88f50 `sbfiz x0, x27, #2, #16`，x27=0xd898 应得
                // 0xffff_ffff_ffff_6260；早期实现对 bits=16 不扩展，给 0x36260。@spec Z-11
                val lsb = imm.toInt()
                val bits = imm2.toInt()
                val insert = op == NmOp.UBFIZ || op == NmOp.SBFIZ
                var x = if (insert) ir(rn) and ones(bits)
                else (ir(rn) ushr lsb) and ones(bits)
                if (op == NmOp.SBFX || op == NmOp.SBFIZ) x = signExtendBits(x, bits)
                if (insert) x = x shl lsb
                iw(rd, x, width); pc = next
            }
            NmOp.BFI, NmOp.BFXIL -> {
                val lsb = imm.toInt()
                val bits = imm2.toInt()
                val src = if (op == NmOp.BFI) ir(rn) and ones(bits)
                else (ir(rn) ushr lsb) and ones(bits)
                val m = ones(bits) shl lsb
                iw(rd, (ir(rd) and m.inv()) or ((src and ones(bits)) shl lsb), width)
                pc = next
            }

            NmOp.SXTB, NmOp.SXTH, NmOp.SXTW, NmOp.UXTB, NmOp.UXTH -> {
                val src = ir(rn)
                val r = when (op) {
                    NmOp.SXTB -> src.toByte().toLong()
                    NmOp.SXTH -> src.toShort().toLong()
                    NmOp.SXTW -> src.toInt().toLong()
                    NmOp.UXTB -> src and 0xFFL
                    NmOp.UXTH -> src and 0xFFFFL
                    else -> src and 0xFFFF_FFFFL
                }
                iw(rd, r, width); pc = next
            }

            NmOp.CLZ -> {
                val r = if (width == NmF.W8)
                    java.lang.Long.numberOfLeadingZeros(ir(rn)).toLong()
                else Integer.numberOfLeadingZeros(ir(rn).toInt()).toLong()
                iw(rd, r, width); pc = next
            }
            NmOp.RBIT -> { iw(rd, java.lang.Long.reverse(ir(rn)), width); pc = next }
            NmOp.REV -> { iw(rd, java.lang.Long.reverseBytes(ir(rn)), width); pc = next }
            NmOp.REV16 -> {
                val v0 = ir(rn)
                var r = 0L
                for (k in 0 until 8) {
                    r = r or (((v0 ushr (k * 8)) and 0xFFL) shl ((k xor 1) * 8))
                }
                iw(rd, r, width); pc = next
            }
            NmOp.REV32 -> {
                val v0 = ir(rn)
                var r = 0L
                for (k in 0 until 4) {
                    val w = (v0 ushr (k * 16)) and 0xFFFFL
                    r = r or ((((w and 0xFFL) shl 8) or (w ushr 8)) shl (k * 16))
                }
                iw(rd, r, width); pc = next
            }

            NmOp.CSEL, NmOp.CSINC, NmOp.CSINV, NmOp.CSNEG -> {
                val taken = condTrue(cond)
                val n = ir(rn)
                val m = ir(rm)
                val r = when (op) {
                    NmOp.CSEL -> if (taken) n else m
                    NmOp.CSINC -> if (taken) n else m + 1
                    NmOp.CSINV -> if (taken) n else m.inv()
                    else -> if (taken) n else -m
                }
                iw(rd, r, width); pc = next
            }
            NmOp.CSET, NmOp.CSETM -> {
                iw(rd, if (condTrue(cond)) (if (op == NmOp.CSET) 1L else -1L) else 0L, width)
                pc = next
            }
            NmOp.CINC, NmOp.CINV, NmOp.CNEG -> {
                val n = ir(rn)
                val r = when (op) {
                    NmOp.CINC -> if (condTrue(cond)) n + 1 else n
                    NmOp.CINV -> if (condTrue(cond)) n.inv() else n
                    else -> if (condTrue(cond)) -n else n
                }
                iw(rd, r, width); pc = next
            }
            NmOp.CCMP_IMM, NmOp.CCMN_IMM, NmOp.CCMP_REG, NmOp.CCMN_REG -> {
                if (condTrue(cond)) {
                    val isCmn = op == NmOp.CCMN_IMM || op == NmOp.CCMN_REG
                    val b = if (op == NmOp.CCMP_IMM || op == NmOp.CCMN_IMM)
                        shiftOperand(imm, shiftOf(c), shamOf(c), NmF.W8) else ir(rm)
                    val a = ir(rd)
                    setAddFlags(a, b, if (isCmn) a + b else a - b, !isCmn, width)
                } else flags = nzcvToFlags(imm2.toInt() and 0xF)
                pc = next
            }

            NmOp.LD, NmOp.LDX -> {
                val size = if (is128(f)) 16 else elemSizeFromWidth(width)
                val ea = effectiveAddr(rn, rm, imm, f, c)
                if (size == 16) {
                    setV(rd, load(ea, 8), load(ea + 8, 8))
                } else {
                    val raw = load(ea, size)
                    val value = if (isSigned(f)) signExtend(raw, size) else raw
                    if (isRdVec(f)) setV(rd, value and maskOfSize(size), 0L)
                    else iw(rd, value, width)
                }
                if (op == NmOp.LDX) { exclAddr = ea; exclValid = true }
                writeback(rn, imm2, f)
                pc = next
            }
            NmOp.ST -> {
                val size = if (is128(f)) 16 else elemSizeFromWidth(width)
                val ea = effectiveAddr(rn, rm, imm, f, c)
                if (size == 16) {
                    store(ea, 8, vlo(rd)); store(ea + 8, 8, vhi(rd))
                } else {
                    store(ea, size, if (isRdVec(f)) vlo(rd) else ir(rd))
                }
                writeback(rn, imm2, f)
                pc = next
            }
            NmOp.STX -> {
                val size = elemSizeFromWidth(width)
                val ea = ir(rn) + imm
                val ok = exclValid && exclAddr == ea
                if (ok) store(ea, size, ir(rm))
                iw(rd, if (ok) 0L else 1L, NmF.W4)
                exclValid = false
                pc = next
            }
            NmOp.LDP, NmOp.STP -> {
                // `ldpsw` 的元素是 4 字节、目标是 64 位寄存器：宽度由 aux.esz 覆盖，
                // 否则按 dest 宽度当 8 字节读，一次读走两个字
                // （实测 audio @0x6493c / @0xbe054）。@spec Z-11
                val esz = auxEsz(c)
                val size = if (is128(f)) 16
                else elemSizeFromWidth(if (esz != 0) esz else width)
                val ea = effectiveAddr(rn, -1, imm, f, c)
                if (op == NmOp.LDP) {
                    if (size == 16) {
                        setV(rd, load(ea, 8), load(ea + 8, 8))
                        setV(rm, load(ea + 16, 8), load(ea + 24, 8))
                    } else {
                        val a = load(ea, size)
                        val b = load(ea + size, size)
                        if (isRdVec(f)) {
                            setV(rd, a, 0L); setV(rm, b, 0L)
                        } else {
                            iw(rd, if (isSigned(f)) signExtend(a, size) else a, width)
                            iw(rm, if (isSigned(f)) signExtend(b, size) else b, width)
                        }
                    }
                } else {
                    if (size == 16) {
                        store(ea, 8, vlo(rd)); store(ea + 8, 8, vhi(rd))
                        store(ea + 16, 8, vlo(rm)); store(ea + 24, 8, vhi(rm))
                    } else {
                        store(ea, size, if (isRdVec(f)) vlo(rd) else ir(rd))
                        store(ea + size, size, if (isRdVec(f)) vlo(rm) else ir(rm))
                    }
                }
                writeback(rn, imm2, f)
                pc = next
            }

            NmOp.LD1, NmOp.LD1R, NmOp.LD2, NmOp.LD3, NmOp.LD4,
            NmOp.ST1, NmOp.ST2, NmOp.ST3, NmOp.ST4 -> {
                val size = elemSize(arr)
                val count = elemCount(arr)
                val nregs = regCount(c)
                val stride = size * count
                var ea = ir(rn) + imm2
                val isLoad = op == NmOp.LD1 || op == NmOp.LD1R || op == NmOp.LD2 ||
                    op == NmOp.LD3 || op == NmOp.LD4
                // 结构加载**整条 128 位寄存器都会被写**：容器没填满的部分（`ld1 {v0.2s}`
                // 只填低 64 位）在硬件上是清零，不是保留旧值。早期只写排列码覆盖的
                // lane，高 64 位留着上一条指令的残留（实测 audio @0x78af8 的 v0.hi）。@spec Z-11
                if (op == NmOp.LD1R) {
                    val one = load(ea, size)
                    setV(rd, 0L, 0L)
                    for (e in 0 until count) vset(rd, arr, e, one)
                    ea += size
                } else if (isLoad) {
                    for (k in 0 until nregs) {
                        setV(rd + k, 0L, 0L)
                        for (e in 0 until count) {
                            vset(rd + k, arr, e, load(ea + e * size, size))
                        }
                        ea += stride
                    }
                } else {
                    for (k in 0 until nregs) {
                        for (e in 0 until count) {
                            store(ea + e * size, size, vget(rd + k, arr, e))
                        }
                        ea += stride
                    }
                }
                if (f and NmF.WB != 0) {
                    val delta = if (f and NmF.POSTREG != 0) ir(imm.toInt())
                    else if (f and NmF.POST != 0) imm else imm2
                    iw(rn, ir(rn) + delta, NmF.W8)
                }
                pc = next
            }

            NmOp.FMOV_RR -> {
                // fmov 在通用寄存器与浮点寄存器之间是**位拷贝**，不是数值转换。
                // 早期写成 `fset(rd, width, ir(rn).toDouble())`：`fmov s18, w8`
                // （w8 装的是某个 float 的位模式 0x3f3504f3）会把它当整数 1060240627
                // 转成 float，得到 0x4e7cd414（实测 audio-legacy @0x1c544）。@spec Z-10
                //
                // `imm` 非 0 表示带 lane 下标（值是元素字节数），这时**只能改/读那一条**：
                // `fmov v0.d[1], x7` 是插入（保留 lane0），`fmov x5, v1.d[1]` 是提取。
                // 早期一律按整寄存器低半处理，插入变成「写 lane0 + 清高位」、
                // 提取永远读 lane0（实测 audio @0xd7ef0 的 v0、@0xd8160 的 x5）。@spec Z-11
                val rdv = isRdVec(f)
                val rnv = isRnVec(f)
                val esz = imm.toInt()
                when {
                    rdv && esz != 0 -> vsetSz(rd, esz, laneOf(c),
                        (if (rnv) vgetSz(rn, esz, 0) else ir(rn)) and maskOfSize(esz))
                    rdv -> setV(rd, (if (rnv) vlo(rn) else ir(rn)) and maskOfWidth(width), 0L)
                    rnv && esz != 0 ->
                        iw(rd, vgetSz(rn, esz, laneOf(c)) and maskOfWidth(width), width)
                    rnv -> iw(rd, vlo(rn) and maskOfWidth(width), width)
                    else -> iw(rd, ir(rn), width)
                }
                pc = next
            }
            NmOp.FMOV_IMM -> {
                // imm 是**元素位模式**（不是数值），imm2 是元素字节数。
                // 带排列码时必须填满所有车道（`fmov v0.2d, #1.0`），标量写高位清零。
                // 早期实现 `fset(rd, width, imm.toDouble())`：只写 lane0，而且
                // 解码侧早把非整数值抹成 0。@spec Z-11
                val esz = imm2.toInt()
                if (arr != NmArr.NONE && isRdVec(f)) {
                    fillVector(rd, arr, imm and maskOfSize(esz))
                } else {
                    setV(rd, imm and maskOfWidth(width), 0L)
                }
                pc = next
            }
            NmOp.FADD, NmOp.FSUB, NmOp.FMUL, NmOp.FDIV, NmOp.FMAX, NmOp.FMIN,
            NmOp.FMAXNM, NmOp.FMINNM, NmOp.FABD -> {
                val a = fget(rn, width)
                val b = fget(rm, width)
                val r = nanFix(when (op) {
                    NmOp.FADD -> a + b
                    NmOp.FSUB -> a - b
                    NmOp.FMUL -> a * b
                    NmOp.FDIV -> a / b
                    NmOp.FMAX -> maxOf(a, b)
                    NmOp.FMIN -> minOf(a, b)
                    NmOp.FMAXNM -> if (a.isNaN()) b else if (b.isNaN()) a else maxOf(a, b)
                    NmOp.FMINNM -> if (a.isNaN()) b else if (b.isNaN()) a else minOf(a, b)
                    else -> Math.abs(a - b)
                }, a, b)
                fset(rd, width, r); pc = next
            }
            NmOp.FNEG -> { fset(rd, width, -fget(rn, width)); pc = next }
            NmOp.FABS -> { fset(rd, width, Math.abs(fget(rn, width))); pc = next }
            NmOp.FSQRT -> {
                val a = fget(rn, width)
                fset(rd, width, nanFix(Math.sqrt(a), a, a)); pc = next
            }
            NmOp.FRINTM -> { fset(rd, width, Math.floor(fget(rn, width))); pc = next }
            NmOp.FNMUL -> {
                val a = fget(rn, width)
                val b = fget(rm, width)
                fset(rd, width, nanFix(-(a * b), a, b)); pc = next
            }
            NmOp.FMADD, NmOp.FMSUB, NmOp.FNMADD, NmOp.FNMSUB -> {
                val a = fget(rn, width)
                val b = fget(rm, width)
                val acc = if (imm2 in 0..31) fget(imm2.toInt(), width) else 0.0
                val r = when (op) {
                    NmOp.FMADD -> acc + a * b
                    NmOp.FMSUB -> acc - a * b
                    NmOp.FNMADD -> -(acc + a * b)
                    // ARM 的 FNMSUB 是 **-Sa + (Sn*Sm)**，不是 -(Sa - Sn*Sm)。
                    // 两者实数值等价，但符号零不等价：Sa=-0.0、乘积=-0.0 时
                    // 前者 +0.0、后者 -0.0（实测 audio-legacy @0x1c27c，
                    // `fnmsub s19,s7,s9,s19` 且 s7=0x80000006、s9=+0.0）。@spec Z-10
                    else -> -acc + a * b
                }
                fset(rd, width, r); pc = next
            }
            NmOp.FCMP, NmOp.FCMPE -> {
                val a = fget(rn, width)
                val b = if (rm >= 0) fget(rm, width) else imm.toDouble()
                fcmpFlags(a, b); pc = next
            }
            NmOp.FCCMP -> {
                if (condTrue(cond)) fcmpFlags(fget(rn, width), fget(rm, width))
                else flags = nzcvToFlags(imm.toInt() and 0xF)
                pc = next
            }
            NmOp.FCSEL -> {
                fset(rd, width, if (condTrue(cond)) fget(rn, width) else fget(rm, width))
                pc = next
            }
            NmOp.FCVT -> {
                fset(rd, width, fget(rn, srcWidthOf(c))); pc = next
            }
            NmOp.SCVTF, NmOp.UCVTF -> {
                // 带排列码时是**逐车道**转换（`scvtf v0.2d, v0.2d`）；只按标量处理
                // 会把 lane1 整条丢掉（实测 audio-legacy @0x1bd34 的 v0 高半部分）。
                val signed = op == NmOp.SCVTF
                if (arr != NmArr.NONE && isRdVec(f)) {
                    val size = elemSize(arr)
                    val sw = if (size == 4) NmF.W4 else NmF.W8
                    val cnt = elemCount(arr)
                    for (e in 0 until cnt) {
                        val iv = vget(rn, arr, e)
                        val d = if (signed) signExtend(iv, size).toDouble()
                        else iv.toULong().toDouble()
                        setElemF(rd, arr, e, sw, d)
                    }
                } else {
                    // 标量形式的源也在**向量寄存器**里（`scvtf d0, d0` 把 d0 当 64 位整数），
                    // 早期一律读 `ir(rn)`（通用寄存器 x N），x0=0 时结果恒为 0.0
                    // （实测 cache @0x2cb68）。@spec Z-11
                    // 源宽度取自**源寄存器**（`scvtf s0, x9` 是 64 位整数、`s0, w9`
                    // 是 32 位）。早期拿目的宽度 `width` 猜源，64 位源被截成 32 位
                    // （实测 cache @0x2cd6c / engine @0x76610：x9=0x00010102464c457f
                    // 应转出 2.8e14，实际只转了低 32 位的 1.18e9）。@spec Z-11
                    val iv = if (isRnVec(f)) vlo(rn) else ir(rn)
                    val sw = if (isRnVec(f)) NmF.W8 else srcWidthOf(c)
                    val d = if (signed) {
                        signExtend(if (sw == NmF.W4) iv and 0xFFFF_FFFFL else iv,
                                   if (sw == NmF.W4) 4 else 8).toDouble()
                    } else if (sw == NmF.W4) (iv and 0xFFFF_FFFFL).toDouble()
                    else iv.toULong().toDouble()
                    fset(rd, width, d)
                }
                pc = next
            }
            NmOp.FCVTZS, NmOp.FCVTZU, NmOp.FCVTPU, NmOp.FCVTPS, NmOp.FCVTMS,
            NmOp.FCVTAS -> {
                // 同 SCVTF：带排列码时逐车道（`fcvtzs v0.2d, v0.2d`）。@spec Z-10
                if (arr != NmArr.NONE && isRdVec(f)) {
                    val size = elemSize(arr)
                    val sw = if (size == 4) NmF.W4 else NmF.W8
                    val cnt = elemCount(arr)
                    for (e in 0 until cnt) {
                        vset(rd, arr, e, fcvtzOf(op, elemF(rn, arr, e, sw), size * 8))
                    }
                } else {
                    iw(rd, fcvtzOf(op, fget(rn, srcWidthOf(c)),
                                   if (width == NmF.W4) 32 else 64), width)
                }
                pc = next
            }

            NmOp.MOVI_VEC -> {
                // 标量形式 `movi d0, #0x000000ffffffff` 没有排列码（arr = NONE），
                // 立即数就是整个 64 位寄存器；早期版本只看排列码逐车道填充，
                // 于是这条什么都没写（实测 audio-legacy @0xf034 的 v[0] 差异）。@spec Z-10
                if (arr == NmArr.NONE) setV(rd, imm, 0L) else fillVector(rd, arr, imm)
                pc = next
            }
            NmOp.MVNI_VEC -> {
                if (arr == NmArr.NONE) setV(rd, imm.inv(), 0L)
                else fillVector(rd, arr, imm.inv())
                pc = next
            }
            NmOp.DUP_VEC -> {
                val size = elemSize(arr)
                val src = if (isRnVec(f)) vgetSz(rn, size, laneOf(c)) else ir(rn)
                val cnt = elemCount(arr)
                // `dup v2.2s, ...` 是 64 位写，容器外的高 64 位清零（不是保留旧值）
                setV(rd, 0L, 0L)
                for (e in 0 until cnt) vset(rd, arr, e, src)
                pc = next
            }
            NmOp.ADD_VEC_I, NmOp.SUB_VEC_I, NmOp.MUL_VEC_I,
            NmOp.AND_VEC, NmOp.ORR_VEC, NmOp.EOR_VEC, NmOp.BIC_VEC, NmOp.ORN_VEC -> {
                // NEON 整数逐车道运算：按车道宽度回绕，不置 NZCV
                val size = elemSize(arr)
                val cnt = elemCount(arr)
                val m = maskOfSize(size)
                for (e in 0 until cnt) {
                    val a = vgetSz(rn, size, e) and m
                    val b = vgetSz(rm, size, e) and m
                    val r = when (op) {
                        NmOp.ADD_VEC_I -> (a + b) and m
                        NmOp.SUB_VEC_I -> (a - b) and m
                        NmOp.MUL_VEC_I -> (a * b) and m
                        NmOp.AND_VEC -> a and b
                        NmOp.ORR_VEC -> a or b
                        NmOp.EOR_VEC -> a xor b
                        NmOp.BIC_VEC -> a and b.inv()
                        else -> a or b.inv()
                    }
                    vsetSz(rd, size, e, r)
                }
                pc = next
            }
            NmOp.FADD_VEC, NmOp.FSUB_VEC, NmOp.FMUL_VEC, NmOp.FDIV_VEC,
            NmOp.FMAX_VEC, NmOp.FMIN_VEC, NmOp.FMLA_VEC, NmOp.FMLS_VEC,
            NmOp.FABD_VEC, NmOp.FMAXNM_VEC, NmOp.FMINNM_VEC, NmOp.FNMUL_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                val w = if (size == 4) NmF.W4 else NmF.W8
                // 按元素形式（`fmul v25.4s, v25.4s, v23.s[1]`）的乘数取自 rm 的
                // **一条 lane** 并广播到所有车道；早期完全忽略 lane 号，退化成
                // 逐车道相乘（实测 audio @0x78cb0）。@spec Z-11
                val bel = if (elOf(c) != 0) laneOf(c) else -1
                for (e in 0 until cnt) {
                    val a = elemF(rn, arr, e, w)
                    val b = elemF(rm, arr, if (bel >= 0) bel else e, w)
                    val acc = elemF(rd, arr, e, w)
                    val r = nanFix(when (op) {
                        NmOp.FADD_VEC -> a + b
                        NmOp.FSUB_VEC -> a - b
                        NmOp.FMUL_VEC -> a * b
                        NmOp.FDIV_VEC -> a / b
                        NmOp.FMAX_VEC -> maxOf(a, b)
                        NmOp.FMIN_VEC -> minOf(a, b)
                        NmOp.FMAXNM_VEC -> if (a.isNaN()) b else if (b.isNaN()) a else maxOf(a, b)
                        NmOp.FMINNM_VEC -> if (a.isNaN()) b else if (b.isNaN()) a else minOf(a, b)
                        NmOp.FABD_VEC -> Math.abs(a - b)
                        NmOp.FNMUL_VEC -> -(a * b)
                        NmOp.FMLA_VEC -> acc + a * b
                        else -> acc - a * b
                    }, a, b)
                    setElemF(rd, arr, e, w, r)
                }
                pc = next
            }
            NmOp.FADDP_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                val w = if (size == 4) NmF.W4 else NmF.W8
                var acc = 0.0
                for (e in 0 until cnt) acc += elemF(rn, arr, e, w)
                setElemF(rd, NmArr.D1, 0, w, acc)
                pc = next
            }
            NmOp.FCMGT_VEC, NmOp.FCMGE_VEC, NmOp.FCMLT_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                val w = if (size == 4) NmF.W4 else NmF.W8
                for (e in 0 until cnt) {
                    val a = elemF(rn, arr, e, w)
                    val b = elemF(rm, arr, e, w)
                    val t = when (op) {
                        NmOp.FCMGT_VEC -> a > b
                        NmOp.FCMGE_VEC -> a >= b
                        else -> a < b
                    }
                    vset(rd, arr, e, if (t) maskOfSize(size) else 0L)
                }
                pc = next
            }
            NmOp.CMEQ_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                for (e in 0 until cnt) {
                    val eq = vget(rn, arr, e) == vget(rm, arr, e)
                    vset(rd, arr, e, if (eq) maskOfSize(size) else 0L)
                }
                pc = next
            }
            NmOp.SMAX_VEC, NmOp.SMIN_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                for (e in 0 until cnt) {
                    val a = signExtend(vget(rn, arr, e), size)
                    val b = signExtend(vget(rm, arr, e), size)
                    vset(rd, arr, e, if (op == NmOp.SMAX_VEC) maxOf(a, b) else minOf(a, b))
                }
                pc = next
            }
            NmOp.ADDV_VEC -> {
                val cnt = elemCount(arr)
                var acc = 0L
                for (e in 0 until cnt) acc += vget(rn, arr, e)
                vsetSz(rd, elemSize(arr), 0, acc)
                pc = next
            }
            NmOp.UADDLV_VEC -> {
                val cnt = elemCount(arr)
                var acc = 0L
                for (e in 0 until cnt) acc += vget(rn, arr, e)
                vsetSz(rd, elemSize(arr) * 2, 0, acc)
                pc = next
            }
            NmOp.SMAXV_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                var acc = Long.MIN_VALUE
                for (e in 0 until cnt) {
                    val v0 = signExtend(vget(rn, arr, e), size)
                    if (v0 > acc) acc = v0
                }
                vsetSz(rd, size, 0, acc)
                pc = next
            }
            NmOp.CNT_VEC -> {
                val cnt = elemCount(arr)
                for (e in 0 until cnt) {
                    vset(rd, arr, e, Integer.bitCount(vget(rn, arr, e).toInt()).toLong())
                }
                pc = next
            }
            NmOp.REV64_VEC, NmOp.REV32_VEC, NmOp.REV16_VEC -> {
                // ARM 的 REV64/32/16 是「在 64/32/16 位**容器内反转元素顺序**」，
                // 元素宽度取排列码：`.8b` 才是整段字节反序，`.2s` 只是两条 32 位
                // 车道对调、元素内部字节不动，`.4s` 干脆是恒等。
                // 早期写成「按分组逐字节反序」，`.2s` 把元素内部也反转了
                // （实测 audio @0x78c04 的 v26）。@spec Z-11
                val esz = elemSize(arr)
                val csz = when (op) {
                    NmOp.REV64_VEC -> 8
                    NmOp.REV32_VEC -> 4
                    else -> 2
                }
                val total = elemCount(arr) * esz
                if (esz >= csz) {
                    if (total > 8) setV(rd, vlo(rn), vhi(rn)) else setV(rd, vlo(rn), 0L)
                } else {
                    val per = csz / esz
                    var nlo = 0L
                    var nhi = 0L
                    for (c in 0 until total / csz) {
                        for (e in 0 until per) {
                            val v = vgetSz(rn, esz, c * per + (per - 1 - e))
                            val shift = (c * per + e) * esz * 8
                            if (shift < 64) nlo = nlo or (v shl shift)
                            else nhi = nhi or (v shl (shift - 64))
                        }
                    }
                    setV(rd, nlo, nhi)
                }
                pc = next
            }
            NmOp.ZIP1_VEC, NmOp.ZIP2_VEC -> {
                val cnt = elemCount(arr)
                val half = cnt / 2
                for (e in 0 until cnt) {
                    val src = if (e % 2 == 0) rn else rm
                    val idx = e / 2 + if (op == NmOp.ZIP2_VEC) half else 0
                    vset(rd, arr, e, vget(src, arr, idx))
                }
                pc = next
            }
            NmOp.UZP1_VEC, NmOp.UZP2_VEC -> {
                val cnt = elemCount(arr)
                val half = cnt / 2
                val odd = if (op == NmOp.UZP2_VEC) 1 else 0
                for (e in 0 until cnt) {
                    val r = if (e < half) vget(rn, arr, e * 2 + odd)
                    else vget(rm, arr, (e - half) * 2 + odd)
                    vset(rd, arr, e, r)
                }
                pc = next
            }
            NmOp.TRN1_VEC, NmOp.TRN2_VEC -> {
                val cnt = elemCount(arr)
                val half = cnt / 2
                for (e in 0 until cnt) {
                    val reg = if (e % 2 == 0) rn else rm
                    val idx = e / 2 + if (op == NmOp.TRN2_VEC) half else 0
                    vset(rd, arr, e, vget(reg, arr, idx))
                }
                pc = next
            }
            NmOp.EXT_VEC -> {
                // `ext vD.<T>, vN.<T>, vM.<T>, #imm`：把 vN‖vM 按**排列宽度**拼接，
                // 再从字节偏移 imm 处取一个排列宽度的结果。`.8b` 排列时每个寄存器
                // 只贡献 8 字节、结果也只有 8 字节（高 64 位清零）；早期实现固定按
                // 16+16 拼接，`.8b` 下把 vM 放到了第 16 字节，取出来整段错位
                // （实测 audio-legacy @0x1d980 的 v16 分歧）。@spec Z-10
                // `.16b` 的每个源寄存器贡献 **16** 字节，高 64 位（vhi）必须参与；
                // 早期只从 vlo 取 n 个字节，`.16b` 的后半段全是 0，拼接整体错位
                // （实测 audio @0x78c6c `ext v21.16b, v23.16b, v23.16b, #4`）。@spec Z-11
                val n = if (arr == NmArr.B16) 16 else 8
                val total = 2 * n
                val src = ByteArray(total)
                for (k in 0 until n) {
                    src[k] = nByteAt(rn, k)
                    src[n + k] = nByteAt(rm, k)
                }
                val off = imm.toInt() % total
                var lo = 0L
                var hi = 0L
                for (k in 0 until n) {
                    val b = src[(off + k) % total].toLong() and 0xFF
                    if (k < 8) lo = lo or (b shl (8 * k))
                    else hi = hi or (b shl (8 * (k - 8)))
                }
                setV(rd, lo, hi)
                pc = next
            }
            NmOp.TBL_VEC -> {
                val tables = regCount(c)
                val idxLo = vlo(rm)
                val idxHi = vhi(rm)
                var lo = 0L
                var hi = 0L
                for (k in 0 until 16) {
                    val idx = ((if (k < 8) idxLo ushr (8 * k) else idxHi ushr (8 * (k - 8)))
                        and 0xFF).toInt()
                    val byte = if (idx >= tables * 16) 0L else {
                        val reg = rn + idx / 16
                        val off = idx % 16
                        if (off < 8) (vlo(reg) ushr (8 * off)) and 0xFF
                        else (vhi(reg) ushr (8 * (off - 8))) and 0xFF
                    }
                    if (k < 8) lo = lo or (byte shl (8 * k))
                    else hi = hi or (byte shl (8 * (k - 8)))
                }
                setV(rd, lo, hi)
                pc = next
            }
            NmOp.SSHLL_VEC, NmOp.USHLL_VEC -> {
                val size = elemSize(arr)
                val srcSize = size / 2
                val cnt = elemCount(arr)
                val sh = imm.toInt()
                for (e in 0 until cnt) {
                    var x = vgetSz(rn, srcSize, e)
                    if (op == NmOp.SSHLL_VEC) x = signExtend(x, srcSize)
                    vsetSz(rd, size, e, x shl sh)
                }
                pc = next
            }
            NmOp.XTN_VEC, NmOp.XTN2_VEC, NmOp.SHRN_VEC, NmOp.SHRN2_VEC -> {
                val size = elemSize(arr)
                val srcSize = size * 2
                val cnt = elemCount(arr)
                val sh = imm.toInt()
                for (e in 0 until cnt) {
                    val x = vgetSz(rn, srcSize, e)
                    val r = if (op == NmOp.XTN_VEC || op == NmOp.XTN2_VEC) x and maskOfSize(size)
                    else (x ushr sh) and maskOfSize(size)
                    vsetSz(rd, size, e, r)
                }
                pc = next
            }
            NmOp.USHR_VEC, NmOp.SSHR_VEC, NmOp.SHL_VEC, NmOp.USRA_VEC, NmOp.SSRA_VEC -> {
                val cnt = elemCount(arr)
                val size = elemSize(arr)
                val sh = imm.toInt()
                for (e in 0 until cnt) {
                    val x = vget(rn, arr, e)
                    val r = when (op) {
                        NmOp.USHR_VEC -> x ushr sh
                        NmOp.SSHR_VEC -> signExtend(x, size) shr sh
                        NmOp.SHL_VEC -> x shl sh
                        NmOp.USRA_VEC -> vget(rd, arr, e) + (x ushr sh)
                        else -> vget(rd, arr, e) + (signExtend(x, size) shr sh)
                    }
                    vset(rd, arr, e, r)
                }
                pc = next
            }
            NmOp.BIT_VEC, NmOp.BIF_VEC, NmOp.BSL_VEC -> {
                val aLo = vlo(rd); val aHi = vhi(rd)
                val bLo = vlo(rn); val bHi = vhi(rn)
                val mLo = vlo(rm); val mHi = vhi(rm)
                val lo: Long
                val hi: Long
                when (op) {
                    NmOp.BIT_VEC -> { lo = aLo xor ((aLo xor bLo) and mLo); hi = aHi xor ((aHi xor bHi) and mHi) }
                    NmOp.BIF_VEC -> { lo = aLo xor ((aLo xor bLo) and mLo.inv()); hi = aHi xor ((aHi xor bHi) and mHi.inv()) }
                    else -> { lo = (aLo and mLo) or (bLo and mLo.inv()); hi = (aHi and mHi) or (bHi and mHi.inv()) }
                }
                setV(rd, lo, hi)
                pc = next
            }
            NmOp.FCVTL, NmOp.FCVTL2 -> {
                // 加宽：H->S 或 S->D。fcvtl 取源低半，fcvtl2 取源高半（偏移 = 目的车道数）
                val size = elemSize(arr)
                val srcSize = size / 2
                val cnt = elemCount(arr)
                val off = if (op == NmOp.FCVTL2) cnt else 0
                for (e in 0 until cnt) {
                    val x = vgetSz(rn, srcSize, e + off)
                    if (srcSize == 4) {
                        vsetSz(rd, size, e, Float.fromBits(x.toInt()).toDouble().toRawBits())
                    } else {
                        vsetSz(rd, size, e,
                            Float.fromBits(halfToFloat(x.toInt()).toRawBits()).toRawBits().toLong() and 0xFFFF_FFFFL)
                    }
                }
                pc = next
            }
            NmOp.FCVTN, NmOp.FCVTN2 -> {
                // 收窄：D->S 或 S->H。fcvtn 写目的低半，fcvtn2 写目的高半（偏移 = 车道数）
                val size = elemSize(arr)
                val cnt = elemCount(arr)
                val off = if (op == NmOp.FCVTN2) cnt else 0
                for (e in 0 until cnt) {
                    val x = vgetSz(rn, size * 2, e)
                    if (size == 4) {
                        vsetSz(rd, size, e + off,
                            Double.fromBits(x).toFloat().toRawBits().toLong() and 0xFFFF_FFFFL)
                    } else {
                        vsetSz(rd, size, e + off, floatToHalf(Float.fromBits(x.toInt())).toLong())
                    }
                }
                pc = next
            }
            NmOp.CMEQ_ZERO_VEC -> {
                val size = elemSize(arr)
                val cnt = elemCount(arr)
                val m = maskOfSize(size)
                for (e in 0 until cnt) {
                    vsetSz(rd, size, e, if (vgetSz(rn, size, e) == 0L) m else 0L)
                }
                pc = next
            }
            NmOp.FABS_VEC, NmOp.FNEG_VEC, NmOp.FSQRT_VEC -> {
                val cnt = elemCount(arr)
                for (e in 0 until cnt) {
                    val x = elemFAny(rn, arr, e)
                    val r = when (op) {
                        NmOp.FABS_VEC -> Math.abs(x)
                        NmOp.FNEG_VEC -> -x
                        else -> nanFix(Math.sqrt(x), x, x)
                    }
                    setElemFAny(rd, arr, e, r)
                }
                pc = next
            }

            else -> {
                ext.dropouts++
                ext.dropoutOps[op] = (ext.dropoutOps[op] ?: 0) + 1
                pc = next
            }
        }

        // AdvSIMD 的**通用规则**：Q=0 的整寄存器写（`.8b` / `.4h` / `.2s` / `.1d`）
        // 只写低 64 位，高 64 位清零。逐 op 补必然漏——补了 `ld1`/`dup` 之后仍然
        // 在 `fadd v20.2s` 上翻车（实测 audio @0x78b1c 的 v20.hi 残留旧值）。
        // 例外只有两类：lane 插入/提取（`mov|fmov vD.T[k], xN` 保留其它 lane）
        // 与结构**存储**（`st1 {..}` 的 rd 是源寄存器，不写）。@spec Z-11
        if (arr != NmArr.NONE && isRdVec(f) && rd in 0 until 32 &&
            elemCount(arr) * elemSize(arr) <= 8 &&
            op != NmOp.MOV_VEC_INS && op != NmOp.FMOV_RR &&
            op != NmOp.ST1 && op != NmOp.ST2 && op != NmOp.ST3 && op != NmOp.ST4) {
            v[rd * 2 + 1] = 0L
        }
    }

    // ---------------- 辅助 ----------------
    private fun elemSizeFromWidth(width: Int): Int = when (width) {
        NmF.W8 -> 8
        NmF.W4 -> 4
        NmF.W2 -> 2
        else -> 1
    }

    private fun isScalarArr(arr: Int): Boolean =
        arr == NmArr.S1 || arr == NmArr.H1 || arr == NmArr.B1 || arr == NmArr.D1

    private fun effectiveAddr(rn: Int, rm: Int, imm: Long, f: Int, c: Int): Long {
        val base = ir(rn)
        return if (f and NmF.INDEX != 0 && rm >= 0) {
            var idx = ir(rm)
            val ext = extOf(c)
            if (ext != 0) idx = extendOperand(idx, ext)
            base + shiftOperand(idx, shiftOf(c), shamOf(c), NmF.W8)
        } else base + imm
    }

    private fun writeback(rn: Int, imm2: Long, f: Int) {
        if (f and NmF.WB != 0 && rn >= 0) iw(rn, ir(rn) + imm2, NmF.W8)
    }

    /** 取 128 位向量寄存器的第 [k] 个字节（k<8 走 vlo，否则走 vhi）。 */
    private fun nByteAt(reg: Int, k: Int): Byte =
        (if (k < 8) (vlo(reg) ushr (8 * k)) and 0xFF else (vhi(reg) ushr (8 * (k - 8))) and 0xFF)
            .toByte()

    private fun fillVector(reg: Int, arr: Int, value: Long) {
        val cnt = elemCount(arr)
        for (e in 0 until cnt) vset(reg, arr, e, value)
    }

    /** ARM64 的 NaN 结果规则。 @spec Z-11
     *
     * 有 NaN 操作数 ⇒ 传播第一个 NaN（ARM 的 NaN 传播顺序，首个操作数优先）；
     * **没有** NaN 操作数却算出 NaN（`0/0`、`inf-inf`、`0*inf`、`sqrt(负数)`）⇒
     * 默认 NaN，符号位为 0。JVM 的 IEEE 运算在 `-0.0/0.0` 这类情形会给
     * `0xFFC00000`（负 NaN），与 ARM 的 `0x7FC00000` 差一个符号位
     * （实测 audio @0x59328）。
     */
    private fun nanFix(r: Double, a: Double, b: Double): Double {
        if (!r.isNaN()) return r
        if (a.isNaN()) return a
        if (b.isNaN()) return b
        return Double.NaN
    }

    /** 浮点→整数各取整模式（标量与逐车道两条路径共用）。 @spec Z-10
     *
     * 取整之后必须按**目标位宽饱和**：ARM 的 FCVTZS/FCVTZU 是「饱和转换」，
     * 溢出时给目标类型的最值，而 JVM 的 `Double.toLong()` 只饱和到 64 位、
     * 再被 `iw(..., W4)` 截断就成了**回绕**（`fcvtzs w0, s0` 传 1e20 应得
     * `0x7FFFFFFF`，截断法给出 `0xFFFFFFFF`）。NaN 一律给 0。
     */
    private fun fcvtzOf(op: Int, d: Double, bits: Int): Long {
        if (d.isNaN()) return 0L
        // 先按取整模式落成整数（仍用 Double 承载，便于后面统一判饱和）
        val v = when (op) {
            NmOp.FCVTPU, NmOp.FCVTPS -> Math.ceil(d)
            NmOp.FCVTMS -> Math.floor(d)
            NmOp.FCVTAS -> if (d >= 0) Math.floor(d + 0.5) else Math.ceil(d - 0.5)
            else -> if (d < 0) Math.ceil(d) else Math.floor(d) // FCVTZS / FCVTZU
        }
        val unsigned = op == NmOp.FCVTZU || op == NmOp.FCVTPU
        if (unsigned) {
            if (v <= 0.0) return 0L
            return if (bits == 32) {
                if (v >= 4294967295.0) 0xFFFF_FFFFL else v.toLong()
            } else {
                // 2^64 上限；[2^63, 2^64) 要按无符号位模式落，直接 toLong 会夹到 MAX
                if (v >= 1.8446744073709552E19) -1L
                else if (v >= 9.223372036854775808E18)
                    (v - 9.223372036854775808E18).toLong() + Long.MIN_VALUE
                else v.toLong()
            }
        }
        return if (bits == 32) {
            when {
                v >= 2147483647.0 -> 0x7FFF_FFFFL
                v <= -2147483648.0 -> -0x8000_0000L
                else -> v.toLong()
            }
        } else v.toLong() // 64 位有符号：JVM 的 toLong 已是饱和语义
    }

    private fun elemF(reg: Int, arr: Int, idx: Int, width: Int): Double {
        val raw = vget(reg, arr, idx)
        return if (width == NmF.W4) Float.fromBits(raw.toInt()).toDouble()
        else Double.fromBits(raw)
    }

    private fun setElemF(reg: Int, arr: Int, idx: Int, width: Int, value: Double) {
        if (width == NmF.W4) {
            vset(reg, arr, idx, value.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL)
        } else vset(reg, arr, idx, value.toRawBits())
    }

    /** 按车道实际宽度（H/S/D）取浮点元素：半精度经 halfToFloat 归一。 */
    private fun elemFAny(reg: Int, arr: Int, idx: Int): Double = when (elemSize(arr)) {
        2 -> halfToFloat(vgetSz(reg, 2, idx).toInt()).toDouble()
        4 -> Float.fromBits(vgetSz(reg, 4, idx).toInt()).toDouble()
        else -> Double.fromBits(vgetSz(reg, 8, idx))
    }

    private fun setElemFAny(reg: Int, arr: Int, idx: Int, value: Double) {
        when (elemSize(arr)) {
            2 -> vsetSz(reg, 2, idx, floatToHalf(value.toFloat()).toLong())
            4 -> vsetSz(reg, 4, idx, value.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL)
            else -> vsetSz(reg, 8, idx, value.toRawBits())
        }
    }
}
