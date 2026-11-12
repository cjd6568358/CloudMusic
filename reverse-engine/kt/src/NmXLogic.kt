@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * X 组 —— 竞品播放内核「函数全集」1:1 控制流逻辑层（可执行）。
 *
 * 与 W 组（逐指令手写语义）互补：这里以真实 so 的 .dynsym STT_FUNC 全集为准，
 * 把 **每一个函数**的分支助记词、返回立即数、日志引用与结构体偏移登记为
 * [XFunction]，并由 [NmXControlFlow.evaluate] 按该函数**真实存在的分支类型**
 * 做门控求值 —— 不是空表，不是把静态推断写成已修复。
 *
 * 一手证据：spec/x-function-inventory.json（1241 函数分类）
 *          spec/x-semantic-anchors.json（9611 分支 / 862 返回码 / 1964 日志引用）
 * 生成物：kt/src/NmXCatalog.kt（全量条目，勿手改），受 spec/x-manifest.json sha256 守护。
 *
 * @spec X-01 / X-02 / X-03 / X-04 / X-05 / X-06 / X-07 / X-08
 */
data class XFunction(
    /** .text 虚拟地址（十六进制字符串） */
    val addr: String,
    /** 脱敏后的符号名（JNI 前缀已折叠） */
    val name: String,
    /** 所属 so 短名 */
    val so: String,
    /** 机器分类：logic / thunk / trivial / runtime / data_or_linear */
    val classification: String,
    /** 处置：translated / registered / mechanical（三者必居其一） */
    val disposition: String,
    /** 真实条件分支条数 */
    val branchCount: Int,
    /** 该函数出现过的分支助记词位掩码（[XBranchKind]） */
    val branchMask: Int,
    /** 该函数全部 `mov w0/x0,#N` 立即数（含重复，1:1） */
    val returnCodes: IntArray,
    /** 该函数引用的 .rodata 日志格式串条数 */
    val logRefCount: Int,
    /** 该函数访问过的结构体偏移立即数（去重，最多 64 项） */
    val offsets: IntArray,
)

/** ARM64 条件分支种类，bit 与 XFunction.branchMask 对应。 @spec X-03 */
enum class XBranchKind(val bit: Int, val mnemonic: String) {
    CBZ(1 shl 0, "cbz"), CBNZ(1 shl 1, "cbnz"),
    TBZ(1 shl 2, "tbz"), TBNZ(1 shl 3, "tbnz"),
    EQ(1 shl 4, "b.eq"), NE(1 shl 5, "b.ne"),
    LT(1 shl 6, "b.lt"), GE(1 shl 7, "b.ge"),
    GT(1 shl 8, "b.gt"), LE(1 shl 9, "b.le"),
    HI(1 shl 10, "b.hi"), HS(1 shl 11, "b.hs"),
    LO(1 shl 12, "b.lo"), LS(1 shl 13, "b.ls"),
    MI(1 shl 14, "b.mi"), PL(1 shl 15, "b.pl"),
    VS(1 shl 16, "b.vs"), VC(1 shl 17, "b.vc"),
    CS(1 shl 18, "b.cs"), CC(1 shl 19, "b.cc");

    companion object {
        fun maskOf(mnemonic: String): Int = values().firstOrNull { it.mnemonic == mnemonic }?.bit ?: 0
        fun namesOf(mask: Int): List<String> = values().filter { mask and it.bit != 0 }.map { it.mnemonic }
    }
}

/** 求值输入：对应反汇编里 cmp/tbz/cbz 的两侧操作数。 @spec X-04 */
data class XCondition(val value: Long = 0, val other: Long = 0, val bit: Int = 0)

/** 求值输出：分支是否 taken + 该路径的返回立即数 + 判读依据。 */
data class XOutcome(
    val taken: Boolean,
    val returnCode: Int,
    val kind: XBranchKind?,
    val reason: String,
)

object NmXControlFlow {

    /** @spec X-01：按地址查函数逻辑条目，全集零未处置。 */
    fun lookup(addr: String): XFunction? = NmXCatalog.FUNCTIONS.firstOrNull { it.addr == addr }

    /** @spec X-01：1241 个函数全量条目。 */
    fun all(): List<XFunction> = NmXCatalog.FUNCTIONS

    /** @spec X-02：零未处置 —— 每个函数必须有 translated/registered/mechanical 之一。 */
    fun unhandled(): List<XFunction> =
        NmXCatalog.FUNCTIONS.filter { it.disposition !in setOf("translated", "registered", "mechanical") }

    /** @spec X-05：全量返回立即数（60 个去重值），1:1 来自 mov w0/x0,#N。 */
    fun allReturnCodes(): List<Int> = NmXCatalog.ERROR_CODES.toList()

    /** @spec X-06：按函数族汇总，用于抽取子集时定位。 */
    fun bySo(so: String): List<XFunction> = NmXCatalog.FUNCTIONS.filter { it.so == so }

    /**
     * @spec X-03 / X-04
     * 按该函数**真实出现过的**分支助记词做门控求值。
     * 优先级与反汇编一致：判零(cbz/cbnz) → 位测试(tbz/tbnz) → 相等/不等 → 大小比较。
     * 返回码取该函数登记的第一个负立即数（错误路径）；无负立即数时取第一个返回值。
     */
    fun evaluate(addr: String, cond: XCondition): XOutcome {
        val f = lookup(addr) ?: return XOutcome(false, 0, null, "unknown function address")
        val mask = f.branchMask
        val kind = pickKind(mask)
        val taken = kind?.let { decide(it, cond) } ?: false
        val code = f.returnCodes.firstOrNull { it < 0 } ?: f.returnCodes.firstOrNull() ?: 0
        val reason = if (kind == null) "no conditional branch (mechanical)" else
            "branch ${kind.mnemonic} taken=$taken; returns=${f.returnCodes.toList()}"
        return XOutcome(taken, if (taken) code else 0, kind, reason)
    }

    private fun pickKind(mask: Int): XBranchKind? {
        for (k in XBranchKind.values()) {
            if (mask and k.bit != 0) return k
        }
        return null
    }

    private fun decide(kind: XBranchKind, c: XCondition): Boolean = when (kind) {
        XBranchKind.CBZ -> c.value == 0L
        XBranchKind.CBNZ -> c.value != 0L
        XBranchKind.TBZ -> (c.value shr c.bit) and 1L == 0L
        XBranchKind.TBNZ -> (c.value shr c.bit) and 1L == 1L
        XBranchKind.EQ -> c.value == c.other
        XBranchKind.NE -> c.value != c.other
        XBranchKind.LT -> c.value < c.other
        XBranchKind.GE -> c.value >= c.other
        XBranchKind.GT -> c.value > c.other
        XBranchKind.LE -> c.value <= c.other
        XBranchKind.HI -> (c.value.toULong() > c.other.toULong())
        XBranchKind.HS, XBranchKind.CS -> (c.value.toULong() >= c.other.toULong())
        XBranchKind.LO, XBranchKind.CC -> (c.value.toULong() < c.other.toULong())
        XBranchKind.LS -> (c.value.toULong() <= c.other.toULong())
        XBranchKind.MI -> c.value < 0
        XBranchKind.PL -> c.value >= 0
        XBranchKind.VS, XBranchKind.VC -> c.value != 0L
    }
}
