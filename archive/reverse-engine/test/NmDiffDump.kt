@file:Suppress("unused")

package cloudmusic.reverse.kernel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Z 组差分验证的 Kotlin 侧执行器（**不是**契约测试，独立入口）。
 *
 * 由 `tools/z_diff.py` 驱动：读 cases.tsv（`domain \t entry \t nInsn \t sp`），
 * 按加载器语义把 so 的 PT_LOAD 段灌进 `NmArmState` 的稀疏内存，再用
 * `NmMachine` 跑恰好 nInsn 条指令，把架构状态打成一行供 Python 比对。
 *
 * 为什么必须灌数据段：`adrp/ldr/br` 组成的 PLT 桩通过 GOT 取目标地址，
 * `.data.rel.ro` 里的函数指针也常被直接 `ldr` 后 `blr`。内存全零时这些读
 * 全部得 0，`br` 会跳到 0，比对就退化成「两边都在乱跳」。灌真实段内容后，
 * 间接跳转目标与 Unicorn 侧一致，差异才反映真实语义。
 *
 * 输出（制表符分隔，`R` 前缀便于脚本过滤）：
 *   R  executed  sp  flags  x0..x29  v0..v63  memDigest  unresolved  budgetExceeded
 *
 * 口径必须与 Unicorn 侧完全一致：x0..x29 = 0、v = 0、栈区全零、x30 = 哨兵。
 * 内存摘要覆盖 `SCRATCH..SCRATCH+SCRATCH_SIZE`（与 Python 的 FNV-1a 同参数）。
 *
 * @spec Z-09
 */
object NmDiffDump {
    private const val SCRATCH = 0x2000_0000L
    private const val SCRATCH_SIZE = 0x2_0000L
    private const val FNV_OFF = -3750763034362895579L  // 0xCBF29CE484222325 的有符号形式
    private const val FNV_PRIME = 0x100_0000_01B3L

    private fun fnv(seed: Long, b: Long): Long = (seed xor (b and 0xFF)) * FNV_PRIME

    private fun memDigest(st: NmArmState): Long {
        var h = FNV_OFF
        var a = SCRATCH
        while (a < SCRATCH + SCRATCH_SIZE) {
            val v = st.load(a, 8)
            var k = 0
            while (k < 8) {
                h = fnv(h, (v ushr (8 * k)) and 0xFF)
                k++
            }
            a += 8
        }
        return h
    }

    /** 照 ELF 加载器语义把 PT_LOAD 段写进稀疏内存（.bss 部分天然为 0）。 */
    private fun loadImage(st: NmArmState, path: String) {
        val b = File(path).readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = bb.getLong(0x20)
        val phentsize = bb.getShort(0x36).toInt() and 0xFFFF
        val phnum = bb.getShort(0x38).toInt() and 0xFFFF
        for (i in 0 until phnum) {
            val o = (phoff + i * phentsize).toInt()
            if (bb.getInt(o) != 1) continue
            val off = bb.getLong(o + 8).toInt()
            val vaddr = bb.getLong(o + 16)
            val filesz = bb.getLong(o + 32)
            var k = 0L
            while (k + 8 <= filesz) {
                st.store(vaddr + k, 8, bb.getLong(off + k.toInt()))
                k += 8
            }
            while (k < filesz) {
                st.store(vaddr + k, 1, b[off + k.toInt()].toLong())
                k++
            }
        }
    }

    private fun parsePaths(args: Array<String>): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (a in args) for (kv in a.split(';')) {
            val i = kv.indexOf('=')
            if (i > 0) m[kv.substring(0, i)] = kv.substring(i + 1)
        }
        return m
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().filter { it.isNotBlank() }
        val paths = parsePaths(args.drop(1).toTypedArray())
        val cache = HashMap<String, ByteArray>()
        val out = StringBuilder(lines.size * 512)
        for (line in lines) {
            val f = line.split('\t')
            val so = f[0]
            val entry = f[1].toLong()
            val n = f[2].toInt()
            val sp = f[3].toLong()
            val wantTrace = f.size > 4 && f[4] == "1"

            val st = NmArmState()
            st.sp = sp
            val p = paths[so]
            if (p != null) loadImage(st, p)
            if (wantTrace) st.pcTrace = ArrayList(n.coerceAtLeast(1))
            if (n > 0) NmMachine(st).call(so, entry, n)

            // abortAt 的判定发生在 steps++ 之后，触发预算时 steps 会多记一条；
            // 真实执行条数用 min(steps, n) 还原。
            val executed = if (st.steps > n) n.toLong() else st.steps
            if (wantTrace) {
                out.append("T\t")
                val tr = st.pcTrace
                if (tr != null) for (i in tr.indices) {
                    if (i > 0) out.append(',')
                    out.append(tr[i])
                }
                out.append('\n')
            }
            out.append('R').append('\t').append(executed)
            out.append('\t').append(st.sp)
            out.append('\t').append(st.flags and 0xF)
            out.append('\t')
            for (i in 0..29) {
                if (i > 0) out.append(',')
                out.append(st.x[i])
            }
            out.append('\t')
            for (i in 0 until 64) {
                if (i > 0) out.append(',')
                out.append(st.v[i])
            }
            out.append('\t').append(memDigest(st))
            out.append('\t').append(st.ext.unresolvedTargets.size)
            out.append('\t').append(st.ext.budgetExceeded)
            out.append('\n')
        }
        print(out)
    }
}
