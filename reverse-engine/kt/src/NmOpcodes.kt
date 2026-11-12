@file:Suppress("unused")
package cloudmusic.reverse.kernel

/** 【生成物，勿手改】Y 组：121 种 ARM64 助记词表。 */
object NmOpcodes : NmOpcodeTable {
    const val COUNT: Int = 121
    private val NAMES = arrayOf("add", "adds", "adr", "adrp", "and", "ands", "asr", "b", "b.eq", "b.ge", "b.gt", "b.hi", "b.hs", "b.le", "b.lo", "b.ls", "b.lt", "b.mi", "b.ne", "b.pl", "bfi", "bfxil", "bic", "bl", "blr", "br", "cbnz", "cbz", "ccmp", "cinc", "clrex", "clz", "cmn", "cmp", "cneg", "cnt", "csel", "cset", "csetm", "csinc", "csinv", "dup", "eor", "ext", "fadd", "fcmp", "fcsel", "fcvt", "fcvtpu", "fcvtzs", "fcvtzu", "fdiv", "fmax", "fmov", "fmul", "fneg", "fsub", "ld1", "ld1r", "ld2", "ld4", "ldar", "ldarb", "ldaxr", "ldaxrb", "ldp", "ldr", "ldrb", "ldrsw", "ldur", "ldurb", "ldxr", "lsl", "lsr", "madd", "mov", "movi", "movk", "mrs", "msub", "mul", "mvn", "neg", "nop", "orr", "prfm", "prfum", "ret", "rev64", "ror", "scvtf", "sdiv", "smulh", "smull", "sshll", "st1", "st2", "stlr", "stlrb", "stlxr", "stlxrb", "stp", "str", "strb", "strh", "stur", "sturb", "sturh", "stxr", "sub", "subs", "sxtw", "tbnz", "tbz", "tst", "uaddlv", "ubfx", "ucvtf", "udiv", "umull", "uxth")
    override fun name(id: Int): String = if (id in NAMES.indices) NAMES[id] else "?"
    fun idOf(name: String): Int = NAMES.indexOf(name)
}
