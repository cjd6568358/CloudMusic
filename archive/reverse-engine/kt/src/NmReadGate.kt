@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * W 组 —— NMBufferSourceImp::ReadFromFileNoLock / NMSlicesFile::Read 门控的 1:1 翻译。
 *
 * 一手证据：spec/w-disasm-evidence.json 窗口 W3(0xC3F4~0xC4F8)、W4(0x12CC0~0x12CF8)、
 * W5(0x12D00~0x12DF0)、W6(0x37E20~0x37E34)、W8(0x12BD0~0x12C6C)。
 *
 * 逐指令链（此前 B-20 / B-30 是 L3「触发条件未坐实」，本轮定案）：
 * ```
 * 0xC408 ldrb w8,[x0,#0xb8] / 0xC40C cbz w8,#0xC4BC   [+0xB8]==0 → 直接 -6
 * 0xC434 blr x8                                        虚调用 → result(w23)
 * 0xC43C mov w0,#3 / bl 0x37E20 / tbnz w0,#0,#0xC474 I(3) 级日志门控
 * 0xC474 tbz  w23,#0x1f,#0xC4C4                       result>=0 → 边界判定
 * 0xC478 cmn  w23,#2 / 0xC47C b.eq #0xC4C4            result==-2 → 边界判定
 * 0xC480 mov w0,#5 / bl 0x37E20 / tbnz w0,#0,#0xC4B8 W(5) 级日志门控
 * 0xC4B8 strb wzr,[x19,#0xb8]                         ★ 清 [+0xB8]
 * 0xC4BC mov w0,#-6                                   ★ 返回 -6
 * 0xC4C4 ldr w8,[x22] / cbz w8,#0xC4E0               readed==0 → -3
 * 0xC4D0 add x8,x8,x20 / cmp x8,x9 / 0xC4D8 csetm w0,ge  ≥[+0x80] → -1，否则 0
 * ```
 * 故 B-20 的触发条件是 `[+0xB8]!=0 && result<0 && result!=-2`；
 * `[+0xB8]==0` 直接 -6；`result>=0 || result==-2` 不清除标志。
 *
 * @spec W-01 / W-08 / W-09 / W-10 / W-11 / W-12 / W-13
 */
object NmReadGate {

    /** 0xC4BC `mov w0,#-6` —— 清标志路径与 [+0xB8]==0 短路路径共用。 */
    const val CODE_GATE_RETURN: Int = -6
    /** 0xC4E0 `mov w0,#-3` —— readed==0。 */
    const val CODE_ZERO_READED: Int = -3
    /** 0x12DC8 `mov x0,#-2` —— 由 0x12CEC `b.lt` 在请求长度 <1 时触发。 */
    const val CODE_ZERO_LENGTH: Int = -2
    /** 0x12DE0 `mov x0,#-0x62` —— 分片读失败。 */
    const val CODE_SLICE_READ_FAILED: Int = -98
    /** 0xC43C `mov w0,#3` —— ReadFromFileNoLock 的 I 级诊断日志。 */
    const val LEVEL_I_READ: Int = 3
    /** 0xC480 `mov w0,#5` —— 清标志前的 W 级诊断日志。 */
    const val LEVEL_W_READ: Int = 5

    data class Outcome(
        /** 函数返回值（w0）。 */
        val code: Int,
        /** 是否执行 0xC4B8 `strb wzr,[x19,#0xb8]`。 */
        val clearNormalMode: Boolean,
        /** 是否执行 0xC470 的 I(3) 级日志。 */
        val iLogEmitted: Boolean,
        /** 是否执行 0xC4B4 的 W(5) 级日志。 */
        val wLogEmitted: Boolean,
        /** 走到的指令路径标签，便于契约断言定位。 */
        val path: String,
    )

    /**
     * @spec W-01
     * 0x37E20~0x37E30 逐指令坐实日志门控本体：
     * `ldr w8,[g_level]; cmp w8,w0; cset w0,gt` → 返回 1 表示 `g_level > level` 抑制。
     * 调用点 `tbnz w0,#0` 跳过日志块、`tbz w0,#0` 进入日志块，两者互为补集。
     */
    fun isSuppressed(gate: NmLogGate, level: Int): Boolean = gate.isSuppressed(level)

    /**
     * @spec W-08 / W-09 / W-10 / W-11
     * ReadFromFileNoLock @0xC3F4 的完整门控（含 I/W 双级日志与 [+0xB8] 清除）。
     *
     * @param normalModeFlag [+0xB8] 当前值
     * @param virtualReadResult 0xC434 `blr x8` 的返回值（w23）
     * @param readed [x22] 实际读出的字节数（w 寄存器）
     * @param offset 本次读起始偏移（x20）
     * @param limit [+0x80] 边界上限（x9）
     */
    fun readFromFileNoLock(
        normalModeFlag: Boolean,
        virtualReadResult: Int,
        readed: Int,
        offset: Long,
        limit: Long,
        gate: NmLogGate = NmLogGate(),
    ): Outcome {
        // 0xC40C cbz w8,#0xC4BC：标志为 0 时连虚调用都不做，直接 -6
        if (!normalModeFlag) {
            return Outcome(CODE_GATE_RETURN, false, false, false, "FLAG_ZERO")
        }

        // 0xC43C `mov w0,#3` + 0xC440 bl getter + 0xC444 tbnz → I 级日志
        val iLog = !isSuppressed(gate, LEVEL_I_READ)

        // 0xC474 tbz w23,#0x1f（非负）/ 0xC478 cmn w23,#2（==-2）→ 0xC4C4 边界判定
        if (virtualReadResult >= 0 || virtualReadResult == -2) {
            val code = when {
                readed == 0 -> CODE_ZERO_READED                       // 0xC4C8 cbz → 0xC4E0
                readed + offset >= limit -> -1                       // 0xC4D8 csetm w0,ge → 全 1
                else -> 0                                            // csetm 条件为假 → 0
            }
            return Outcome(code, false, iLog, false, "BOUNDARY")
        }

        // 0xC480 `mov w0,#5` + 0xC484 bl getter + 0xC488 tbnz → W 级日志
        val wLog = !isSuppressed(gate, LEVEL_W_READ)
        // 0xC4B8 strb wzr,[+0xB8] → 0xC4BC mov w0,#-6
        return Outcome(CODE_GATE_RETURN, true, iLog, wLog, "CLEAR_FLAG")
    }

    /**
     * @spec W-12
     * 0x12CE4 `cmp x12,#1` + 0x12CEC `b.lt #0x12DC8`：请求长度 <1 →
     * 0x12DC8 `mov x0,#-2` 直接返回。这是 B-30 那条 -2 的**唯一**引用，
     * 位于读侧分片窗口计算之前，不是写侧越界门控（修正 §61.77 报告级口径）。
     */
    fun zeroLengthGate(len: Long): Outcome? =
        if (len < 1L) Outcome(CODE_ZERO_LENGTH, false, false, false, "ZERO_LENGTH")
        else null

    /**
     * @spec W-13
     * 0x12DA8 `bl #0x15220` 分片读 → 0x12DAC `tbnz w0,#0x1f,#0x12DE0` 失败 →
     * 0x12DE0 `mov x0,#-0x62` 返回 -98；成功时 0x12DB4 `add w26,w26,w23` 累加、
     * 0x12DD4 `mov w0,w26` + 0x12DD8 `str w26,[x21]` 把累计长度写回出参。
     */
    fun sliceReadLoop(sliceResults: List<Pair<Boolean, Int>>): Outcome {
        // 每项是 (0x12DA8 分片读是否成功, 本次读长度 w23)；成功时 w26 累加。
        var accumulated = 0
        for ((ok, len) in sliceResults) {
            if (!ok) return Outcome(CODE_SLICE_READ_FAILED, false, false, false, "SLICE_FAILED")
            accumulated += len
        }
        // 注意：ACCUMULATED 路径下 code 是 0x12DD4 的累计读长度，不是错误码。
        return Outcome(accumulated, false, false, false, "ACCUMULATED")
    }
}
