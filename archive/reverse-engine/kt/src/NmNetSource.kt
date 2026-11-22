@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * NMNetSource（libNMCommonCache.so）—— 1:1 翻译请求代次闸门 / commit 记账 / 唤醒链。
 *
 * 证据：analysis/l3-f4-netsource-commit-disasm.json、
 *       analysis/l3-f4-generation-gate-disasm.json、
 *       analysis/l3-f4-netsource-commit-report.md（12 §61.65 / §61.67）。
 *
 * 精度修正（§61.65）：commit 是「连续确认偏移」，**不是** vfile 磁盘写入字节数。
 */
class NmNetSource(
    private val requester: NmRangeRequester,
    private val sink: NmDataSink,
    private val gate: NmLogGate,
    /** [+0x18]：WaitRequest/OnData 日志里 `(%lld-%lld)` 的第一个数。 */
    var sourceId: Long = 1L,
) {
    // ---- 字段（保留 native 偏移语义，见 NmFieldOffsets） ----

    /** @spec N-02 / N-26 field [+0x20] 本次 Range 请求起始偏移 */
    var requestOffset: Long = 0L
        private set

    /** @spec N-03 / N-05 / N-12 field [+0xC0] 当前请求代次 */
    var generation: Long = 0L
        private set

    /** @spec N-15 / N-21 field [+0x30] 连续确认偏移 commit */
    var commit: Long = 0L
        private set

    /** @spec N-22 field [+0x38] 当前请求长度基准 */
    var requestSize: Long = 0L
        private set

    /** @spec N-22 field [+0x40] 唤醒阈值 signal_size（真机 8192） */
    var signalSize: Long = 8192L
        private set

    /** @spec N-23 field [+0xB8] 越过阈值后的唤醒标志 */
    var wakeFlag: Boolean = false
        private set

    /** @spec N-10 请求失败：把 [+0x20..0x40] 区间置 -1，0x10540 存错误码 */
    var requestFailed: Boolean = false
        private set
    var requestErrorCode: Int = 0
        private set

    /** @spec N-24 condition_variable::notify_all 计数（便于测试观测） */
    var notifyCount: Int = 0
        private set

    /** 统计：被代次闸门整包丢弃的包数（F4 观测证据） */
    var droppedPacketCount: Int = 0
        private set

    // ---- MakeRangeRequestAsync @0x10458 ----

    /**
     * @spec N-01 / N-02 / N-03 / N-04 / N-05 / N-06 / N-07 / N-08 / N-09 / N-10
     *
     * 由 `WaitRequest @0x10304` 唯一调用。
     * 0x10498 `str x22,[x26,#0x20]!` pre-index 写回（x26 变为 this+0x20，
     * 故 `+0xA0 == this+0xC0`）→ 读代次 → +1 → 写回 → 新代次作为首参
     * → vtable+0x10 虚调用发起 Range 请求。请求失败把 [+0x20..0x40] 置 -1。
     */
    fun makeRangeRequestAsync(offset: Long, length: Long, signal: Long): Long {
        // @spec N-02
        requestOffset = offset
        // @spec N-03 / N-04 / N-05：代次递增点
        val current = generation
        val next = current + 1
        generation = next
        // @spec N-22
        requestSize = length
        signalSize = signal
        // @spec N-06 / N-07 / N-08：args = (obj, gen, offset, length)
        val result = requester.start(next, offset, length)
        // @spec N-09：0x10510 还原日志 0x3CD64
        gate.log(
            4,
            "NMNetSource::MakeRangeRequestAsync($next), start result:${if (result.ok) 0 else 1}, " +
                "offset:$offset, length:$length",
        )
        gate.log(4, "NMNetSource($sourceId-$next)::MakeRangeRequestAsync offset:$offset, length:$length, signal_size:$signal")
        // @spec N-10：请求失败 0x1053C `stp q0,q0,[x26]` 把区间置 -1
        if (!result.ok) {
            requestFailed = true
            requestErrorCode = result.code
            requestOffset = -1L
            requestSize = -1L
            signalSize = -1L
        }
        return next
    }

    // ---- NMNetSource::OnData @0x10748 ----

    /**
     * @spec N-11 / N-12 / N-13 / N-14 / N-15 / N-16 / N-17 / N-18 / N-19 / N-20 / N-21 / N-22 / N-23 / N-24 / N-25
     *
     * 0x10798 `b.ne 0x108E0` 是该函数**唯一**硬早退：代次不匹配 → 整包丢弃，
     * 既不投递 0x1086C、也不写 commit、不置 +0xB8、不唤醒读侧。
     *
     * @param startGeneration 发起该请求时的代次（回调 arg1，JNI 透传的 requestId）
     */
    fun onData(startGeneration: Long, offset: Long, size: Long, data: ByteArray): NmOnDataResult {
        // @spec N-12 / N-13 / N-14：0x10790 / 0x10794 / 0x10798 b.ne 0x108E0
        if (generation != startGeneration) {
            // @spec N-25：0x108E0 丢弃路径终点，只 unlock + 栈保护 + ret
            droppedPacketCount++
            return NmOnDataResult.DROPPED
        }

        // @spec G-14：0x107BC getter N=5，W 级 `OnData() offset:...`
        gate.log(
            5,
            "NMNetSource($sourceId-$generation)::OnData() offset:$offset, size:$size, " +
                "except offset:$commit, end:${offset + size}",
        )

        // @spec N-15 / N-16：0x107F4 读 commit / 0x107F8 cmp / 0x107FC b.eq
        // commit != offset 只打 W 级日志，**不丢弃**、仍继续更新 commit。
        if (commit != offset) {
            gate.log(
                5,
                "NMNetSource($sourceId-$generation)::OnData() UnExpect New Offsetcommted:$commit, " +
                    "offset:$offset, size:$size, cur_req_size:$requestSize",
            )
        }

        // @spec N-17 / N-18 / N-19：0x10840 取 [+0x190] → vtable+0x18 → blr 0x1086C
        // 数据投递对象 → OnNetSourceData → OnWriteNetData（落盘入口）
        sink.onNetSourceData(NmKind.NATIVE_DATA_KIND_NORMAL, data, offset, size)

        // @spec N-20 / N-21：0x10884 adds x8,x21,x22 / 0x10888 str x8,[x20,#0x30]
        commit = offset + size

        // @spec N-22：越过等待阈值才唤醒（[+0x40]>=1 且 commit >= [+0x38]+[+0x40]）
        if (signalSize >= 1 && commit >= requestSize + signalSize) {
            // @spec N-23：0x108D0 置唤醒标志 [+0xB8]
            wakeFlag = true
            // @spec N-24：0x108DC bl 0x8AB0 condition_variable::notify_all
            notifyWaitingReaders()
        }
        return NmOnDataResult.ACCEPTED(commit)
    }

    /** @spec N-24：0x108DC 唤醒等待读侧的条件变量。 */
    private fun notifyWaitingReaders() {
        notifyCount++
    }

    // ---- WaitRequest @0x10198 / @0x10304 ----

    /**
     * @spec N-26 / N-27 / N-28 / N-29
     *
     * 日志两个数来自 [+0x18] 与 [+0xC0]（真机从 (1-0) 递增到 (1-8)）。
     * 0x10320 `ldr x8,[x19,#0x30]` 即日志 `commit : %lld` 的取值。
     *
     * 返回值由 `ReadFromFileNoLock @0xC6F8` 映射为错误码：
     * 0 → 成功回读；1 → -2；2 → -106；其它 → -103。
     */
    fun waitRequest(
        curOffset: Long,
        curEnd: Long,
        expectOffset: Long,
        end: Long,
        force: Boolean,
    ): Int {
        gate.log(
            4,
            "NMNetSource($sourceId-$generation)::WaitRequest offset:$curOffset, " +
                "length:${end - curOffset}, signal_size:$signalSize, force:$force",
        )
        gate.log(
            4,
            "NMNetSource($sourceId-$generation)::WaitRequest cur offset:$curOffset, " +
                "cur end : $curEnd, expect offset : $expectOffset, end : $end, commit : $commit",
        )
        // @spec N-29：signal_size 门控
        if (commit >= end) return WAIT_OK
        if (force) return WAIT_FORCE
        if (commit < expectOffset) return WAIT_STARVED
        return WAIT_OTHER
    }

    /** @spec N-30：PreSeekReadTask 回退到连续提交点并重新发请求。 */
    fun rollbackToContinuousCommitPoint(continuousCommit: Long): Long {
        commit = continuousCommit
        return makeRangeRequestAsync(continuousCommit, 1048576L, 8192L)
    }

    /** 供测试/抽取重置状态。 */
    fun reset() {
        requestOffset = 0L
        generation = 0L
        commit = 0L
        requestSize = 0L
        signalSize = 8192L
        wakeFlag = false
        requestFailed = false
        requestErrorCode = 0
        notifyCount = 0
        droppedPacketCount = 0
    }

    companion object {
        /** @spec N-27：WaitRequest 返回值枚举（不是错误码本身）。 */
        const val WAIT_OK: Int = 0
        const val WAIT_STARVED: Int = 1       // → -2
        const val WAIT_FORCE: Int = 2         // → -106
        const val WAIT_OTHER: Int = 3         // → -103
    }
}

/** @spec N-14 / N-25：OnData 的两种结果。 */
sealed class NmOnDataResult {
    /** 0x10798 b.ne 0x108E0：整包丢弃。 */
    object DROPPED : NmOnDataResult()

    /** 通过代次校验，commit 已写为 offset+size。 */
    data class ACCEPTED(val newCommit: Long) : NmOnDataResult()
}
