@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * NMSlicesFile::Write @0x127C0 / Read —— 1:1 翻译四条静默不落盘门控 + 分片循环。
 *
 * 证据：analysis/l3-f4-generation-gate-disasm.json（12 §61.67）、
 *       analysis/l3-f4-level5-gate-semantics-report.md（12 §61.70 §3.2）。
 */
class NmSlicesFile(
    /** [+0x70] file_len */
    var fileLen: Long,
    /** [+0xD0] slice_size */
    var sliceSize: Long,
    /** [+0xC8] 分片写禁用标志 */
    var disabled: Boolean = false,
    private val gate: NmLogGate,
) {
    /** @spec S-20 field [+0x104] 全部写完成标志 */
    var writeFinished: Boolean = false
        private set

    /** 观测用：实际分片写调用次数 */
    var sliceWriteCalls: Int = 0
        private set

    /** 可注入分片写结果，用于验证 S-18 失败分支。 */
    var sliceWriteOverride: ((Long) -> Int)? = null

    /**
     * @spec S-01 / S-02 / S-03 / S-04 / S-05 / S-06 / S-07 / S-08 / S-09 / S-10 / S-11 / S-12 / S-13 / S-14 / S-15 / S-16 / S-17 / S-18 / S-19 / S-20 / S-21
     *
     * 四条静默不落盘门控：
     *  ① [+0xC8] 非 0 → -100
     *  ② len==0 或 offset 低 6 位非 0（未 64B 对齐）→ -102
     *  ③ offset+len > file_len → -102
     *  ④ file_len == offset（EOF）→ -1
     */
    fun write(buffer: ByteArray, offset: Long, len: Int): Int {
        // @spec S-02 / S-03：0x127C0 ldrb / 0x127CC mov x0,#-0x64
        if (disabled) return NmError.SLICE_DISABLED

        // @spec S-04 / S-05：0x12810 cbz w2 / 0x12814 tbnz x21,#0x3f
        val unaligned = (offset and 0x3FL) != 0L
        if (len == 0 || unaligned) {
            // @spec S-09 / S-10 / S-11 / S-12 / S-13：W 级失败诊断日志门控
            val early = emitWriteDiagAndGate(offset, len, "")
            if (early != 0) return early
            return NmError.INVALID_RANGE
        }

        // @spec S-06 / S-07 / S-08：0x12818 读 file_len / 0x1281C end / 0x12820 cmp / 0x12824 b.le
        val end = offset + len
        if (end > fileLen) {
            val early = emitWriteDiagAndGate(offset, len, "")
            if (early != 0) return early
            return NmError.INVALID_RANGE
        }

        // @spec S-14 / S-15：0x12868 cmp x8,x21 / 0x1286C b.ne / 0x1287C mov x0,#-1
        if (fileLen == offset) {
            gate.log(5, "NMSlicesFile Write buffer len:$len, offset:$offset, file_len:$fileLen Finish EOF.")
            return NmError.EOF
        }

        // @spec S-16：0x12890 / 0x12894 按 slice_size 计算起止片索引
        val startSlice = offset / sliceSize
        val endSlice = end / sliceSize

        // @spec S-17：0x12980 bl 0x150E8 实际分片写
        for (index in startSlice..endSlice) {
            val code = sliceWriteOverride?.invoke(index) ?: 0
            sliceWriteCalls++
            // @spec S-18：0x12AF4 tbnz w0,#0x1f 写失败分支 / 0x12B30 mov x0,#-0x62
            if (code != 0) {
                gate.log(5, "NMSlicesFile Write buffer len:$len, offset:$offset, file_len:$fileLen Fail. code:$code")
                return NmError.SLICE_WRITE_FAILED
            }
        }

        // @spec S-19：0x12AD4 / 0x12ADC 还原 "Write all finish" @0x3D0AE
        gate.log(5, "NMSlicesFile Write all finish")
        // @spec S-20：0x12AEC 成功路径置完成标志 [+0x104]
        writeFinished = true
        // @spec S-21：0x12A84 mov x0,xzr 成功返回 0
        return 0
    }

    /**
     * @spec S-09 / S-10 / S-11 / S-12 / S-13 / G-15
     *
     * 0x12828 mov w0,#5 → 0x1282C bl getter → 0x12830 `tbz w0,#0,#0x1283C`：
     * `g_level <= 5` 时进入 0x1283C 打 W 级诊断日志；`g_level > 5` 时不打日志，
     * 直接落 0x12834 `mov x0,#-0x66` 返回 -102。**两条路径返回码相同，仅日志不同**。
     * §61.70 §5.1 另登记「tbz 早退可能改变部分错误码」，以 [NmLogGate.earlyReturnCodeIfSuppressed]
     * 表达，默认关闭、不虚构指令。
     */
    private fun emitWriteDiagAndGate(offset: Long, len: Int, suffix: String): Int {
        if (!gate.shouldLog(5)) {
            return gate.earlyReturnCodeIfSuppressed(5)
        }
        gate.log(5, "NMSlicesFile Write buffer len:$len, offset:$offset, file_len:$fileLen $suffix")
        return 0
    }

    /**
     * @spec S-23 / S-22
     * NMSlicesFile::Read 尾 0x12C48 为 W(5) 级 `tbz → 进日志`，
     * EOF 0x12C5C 为 I(4) 级 `tbz → 进日志`（§61.70 §3.1）。
     */
    fun read(offset: Long, len: Int): Int {
        if (offset >= fileLen) {
            gate.log(4, "NMSlicesFile::Read buffer len:$len, offset:$offset, file_len:$fileLen Finish EOF.")
            return NmError.EOF
        }
        gate.log(5, "NMSlicesFile::Read buffer len:$len, offset:$offset, file_len:$fileLen ")
        return minOf(len.toLong(), fileLen - offset).toInt()
    }

    /**
     * @spec B-30
     * §61.77 §2 登记的另一处 `mov x0,#-2`（0x12DC8）：NMSlicesFile::Write 的
     * `offset+len <= file_len` 越界门控返回 **-2**，与入口处的 -102 是两条独立路径。
     * 静态证据只定位到立即数，触发条件未逐指令坐实，故翻译为独立可调用语义，不并入主分支。
     */
    fun secondaryRangeGate(offset: Long, len: Int): Int =
        if (offset + len > fileLen) NmError.WAIT_STARVED else 0
}
