@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * libncmaudioplayer.so —— AudioTrack::Write 路径 1:1 翻译。
 *
 * 证据：analysis/l3-f4-play-gate-report.md（12 §61.74）、
 *       analysis/l3-f4-minus103-chain-report.md（12 §61.76 §3）。
 *
 * .rodata @0xE180A = `AudioTrack(%d): Write start play nErr:%d`，tag @0xDD1B3 = NMAudioPlayer。
 * 全 .text 扫描该格式串**唯一**引用点 0x66EEC/0x66EF4，打印调用 0x66F00 `bl 0x5C18C`，
 * 函数窗口 0x66E44~0x66FA8。到达打印点前有 **4 道静默早退门控**。
 */
class NmAudioPlayer(private val gate: NmLogGate) {

    /** @spec A-11 field [+0x1E8] AudioTrack 对象指针 */
    var audioTrack: Any? = null
        private set

    /** @spec A-10 / A-12 field [+0x20C] paused 标志（`AudioTrack(%d): Paused` @0xE190A 坐实） */
    var paused: Boolean = false
        private set

    /** @spec A-08 / A-09 field [+0x354] 写失败重试计数 */
    var writeRetryCount: Int = 0
        private set

    /** @spec A-09 field [+0xD8] vtable+0x20 回调对象 */
    var callback: NmPlayerCallback? = null

    /** `bl 0x13D80` 的返回值（门控 4），可注入测试。 */
    var internalStateProbeResult: Int = 0

    /** `bl 0x14500` 的返回值，作为 nErr 打印。 */
    var writeResult: Int = 0

    var audioSessionId: Int = 0
    var writeStartPlayLogCount: Int = 0
        private set
    var earlyReturnMinus1Count: Int = 0
        private set
    var skipPrintCount: Int = 0
        private set
    var lastNErr: Int? = null
        private set
    var callbackErrorCodes: MutableList<Int> = mutableListOf()

    /**
     * @spec A-01 / A-02 / A-03 / A-04 / A-05 / A-06 / A-07 / A-08 / A-09
     *
     * 4 道门控全部放行，`0x66EDC bl 0x14500` 的返回值才作为 nErr 在 0x66F00 打印。
     */
    fun writeStartPlay(): NmWriteOutcome {
        // @spec A-03：门控 1 —— 0x66E70/0x66E74 → 0x66F50，[+0x1E8] 为空 → 返回 -1，不打印
        if (audioTrack == null) {
            earlyReturnMinus1Count++
            return NmWriteOutcome.EARLY_RETURN_MINUS_1
        }
        // @spec A-04：门控 2 —— 0x66EA8/0x66EAC → 0x66F40，二次判空 → 收尾 -1
        if (audioTrack == null) {
            earlyReturnMinus1Count++
            return NmWriteOutcome.EARLY_RETURN_MINUS_1
        }
        // @spec A-05：门控 3 —— 0x66EB0/0x66EB4 → 0x66F5C，[+0x20C] 非零 → 跳过打印
        if (paused) {
            skipPrintCount++
            return NmWriteOutcome.SKIP_PRINT
        }
        // @spec A-06：门控 4 —— 0x66EC8/0x66ECC → 0x66F5C，`bl 0x13D80` 返回 3 → 跳过打印
        if (internalStateProbeResult == 3) {
            skipPrintCount++
            return NmWriteOutcome.SKIP_PRINT
        }

        // @spec A-07：0x66EDC bl 0x14500 → nErr
        val nErr = writeResult
        lastNErr = nErr
        // @spec A-01 / A-02：0x66F00 bl 0x5C18C（唯一打印点）
        gate.log(5, "AudioTrack($audioSessionId): Write start play nErr:$nErr")
        writeStartPlayLogCount++

        // @spec A-08：nErr==0 时 0x66F58 清零 [+0x354] 重试计数
        if (nErr == 0) {
            writeRetryCount = 0
        } else if (writeRetryCount > 9) {
            // @spec A-09：nErr!=0 且计数 >9 → 0x66F28 经 [+0xD8] vtable+0x20 回调传 -3
            callbackErrorCodes += NmError.AUDIO_RETRY_EXHAUSTED
            callback?.onError(NmError.AUDIO_RETRY_EXHAUSTED)
        } else {
            writeRetryCount++
        }
        return NmWriteOutcome.PRINTED(nErr)
    }

    /**
     * @spec A-10
     * 0x65198 `strb wzr,[x19,#0x20C]` 构造清零；
     * 0x657D4 / 0x67478 `strb w8(=1)` 置 paused → vtable+0x150(w1=2)；
     * 0x67314 `strb wzr` 清 paused → vtable+0x150(w1=1)。
     */
    fun setPaused(value: Boolean) {
        paused = value
        callback?.onStateChange(if (value) 2 else 1)
    }

    /** @spec A-11：AudioTrack 对象指针设置点 0x572D4 / 0x583BC。 */
    fun attachAudioTrack(track: Any) {
        audioTrack = track
    }

    /**
     * @spec A-11 / P-16
     * 释放清零点 0x4CEA8 / 0x4D630 / 0x55B5C / 0x65CD0 / 0x66810
     *（后者紧跟 `bl 0x14470` + `blr [vtable+0xB0]`）→ AudioTrack Release。
     */
    fun releaseAudioTrack() {
        audioTrack = null
    }

    fun reset() {
        audioTrack = null
        paused = false
        writeRetryCount = 0
        internalStateProbeResult = 0
        writeResult = 0
        writeStartPlayLogCount = 0
        earlyReturnMinus1Count = 0
        skipPrintCount = 0
        lastNErr = null
        callbackErrorCodes.clear()
    }

    companion object {
        /** @spec A-11：AudioTrack 对象指针释放清零的 6 处地址。 */
        val TRACK_RELEASE_SITES: List<Long> = listOf(0x4CEA8, 0x4D630, 0x55B5C, 0x65CD0, 0x66810)
        val TRACK_SET_SITES: List<Long> = listOf(0x572D4, 0x583BC)
        const val WRITE_START_PLAY_RODATA: Long = 0xE180A
        const val PAUSED_RODATA: Long = 0xE190A
    }
}

/** @spec A-03 / A-04 / A-05 / A-06 / A-07：writeStartPlay 的四种结果。 */
sealed class NmWriteOutcome {
    /** [+0x1E8] 为空 → 返回 -1，不打印。 */
    object EARLY_RETURN_MINUS_1 : NmWriteOutcome()

    /** 门控 3 / 4 命中 → 跳过打印，直接进入后续路径。 */
    object SKIP_PRINT : NmWriteOutcome()

    /** 4 道门控全放行，已打印 `Write start play nErr:%d`。 */
    data class PRINTED(val nErr: Int) : NmWriteOutcome()
}
