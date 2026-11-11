@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * NMBufferSourceImp（libNMCommonCache.so）—— 1:1 翻译
 * OnNetSourceData / OnWriteNetData / OnNetSourceDataDone / OnFlushNetData /
 * NotifyCacheProgress / SetFileOffset / ReadFromFileNoLock / Finish Async 错误链。
 *
 * 证据：analysis/l3-f4-netsource-commit-disasm.json、netsource-commit-report.md（§61.65）、
 *       analysis/l3-f4-minus103-chain-report.md（§61.76）、
 *       analysis/l3-f4-read-gap-chain-report.md（§61.77）、
 *       analysis/l3-f4-jni-arg-report.md（§61.56）、
 *       analysis/l3-f4-disasm-verify-report.md（§61.54）、
 *       analysis/l3-f4-task-diff-report.md / l3-f4-task-diff.json（§61.55）、
 *       analysis/l3-f4-read-gap-chain.json（§61.77，R 组全量登记 / 日志格式串 1:1）。
 */
class NmBufferSourceImp(
    private val gate: NmLogGate,
    private val slicesFile: NmSlicesFile,
    private val netSource: NmNetSource,
) {
    // ---- 字段（保留 native 偏移语义） ----

    /** @spec B-19 field [+0xA8] vfile 连续写水位，由 SetFileOffset 写入 */
    var fileOffset: Long = 0L
        private set

    /** @spec B-08 / B-14 / B-20 field [+0xB8] normal-mode 标志 */
    var normalMode: Boolean = true
        private set

    /** @spec B-24 / B-26 field [+0x198] 挂起异步读请求 */
    var pendingAsyncRead: Long = 0L
        private set

    /** @spec B-25 / B-27 field [+0x1A0] Finish Async 错误码 */
    var asyncErrorCode: Int = 0
        private set

    /** @spec B-27 回调清零的 [+0x188] / [+0x190] */
    var asyncCtxA: Long = 0L
        private set
    var asyncCtxB: Long = 0L
        private set

    /** 观测用统计 */
    var memoryCacheWrites: Int = 0
        private set

    /** @spec D-03 / D-07 投递 OnFlushNetData 任务次数（0x16468 队列） */
    var flushTaskPosts: Int = 0
        private set

    /** @spec D-07 onFlushNetData 实际执行次数 */
    var flushCount: Int = 0
        private set

    /** @spec D-02 投递 PreloadDoneTaskOnWriteThread 次数 */
    var preloadDoneCount: Int = 0
        private set

    /** @spec D-10 PreloadDoneTaskOnWriteThread 级联再投递次数（0xb924 bl 0x16468） */
    var cascadePosts: Int = 0
        private set

    /** @spec D-11 PreloadDoneTaskOnRenderThread 执行次数 */
    var renderDoneCount: Int = 0
        private set

    /** @spec D-07 0xc08c `br x1` 尾调用计数 */
    var flushTailCallCount: Int = 0
        private set

    /** @spec D-09 0xb8f0 `strb w10,[x0,#0x20]` 置 bool=true */
    var preloadDoneBoolTrue: Boolean = false
        private set

    /**
     * @spec D-07 0xc078 `ldr x0,[x0,#0xD8]` 取到的回调对象是否非空。
     * 默认 true（正常路径）；置 false 可复现 0xc07c `cbz` → 0xc09c ret 分支。
     */
    var flushCallbackPresent: Boolean = true

    var starvedReadCount: Int = 0
        private set
    var finishAsyncLogCount: Int = 0
        private set
    val cacheProgress: MutableList<Long> = mutableListOf()
    val writeTaskQueue: MutableList<NmWriteTask> = mutableListOf()

    /** @spec D-05 两分支共用的 0x16468 完成通知任务队列。 */
    val doneTaskQueue: MutableList<NmDoneTask> = mutableListOf()

    data class NmWriteTask(val buffer: ByteArray, val offset: Long, val size: Long)

    // ---- OnNetSourceData @0xBA44 ----

    /**
     * @spec B-01 / B-02 / B-03 / B-04 / B-05 / B-06
     *
     * 签名 `(int kind, unique_ptr<NMDataBuffer>, long long, long long)`。
     * 0xBAAC `cmp w23,#2` / 0xBAB0 `b.eq 0xBB34`：
     *  kind != 2 → 额外 `bl 0xFE48` (NMMemoryCache::Write 内存镜像)
     *  kind == 2 → 跳过内存缓存写
     * 两种 kind **都**汇合到 0xBB34 构造任务，并 0xBC10 `bl 0x16468` 投递同一队列。
     */
    fun onNetSourceData(kind: Long, buffer: ByteArray, offset: Long, size: Long): Int {
        if (kind != NmKind.NATIVE_DATA_KIND_PRELOAD) {
            // @spec B-04：0xBAE8 bl 0xFE48 NMMemoryCache::Write
            memoryCacheWrites++
        }
        // @spec B-05 / B-06：构造任务对象并投递写线程队列
        writeTaskQueue += NmWriteTask(buffer, offset, size)
        return 0
    }

    /** 驱动已投递的写任务（对应写线程任务队列 0x16468 / 0x9FC0）。 */
    fun drainWriteQueue(): List<Int> {
        val results = mutableListOf<Int>()
        while (writeTaskQueue.isNotEmpty()) {
            val task = writeTaskQueue.removeAt(0)
            results += onWriteNetData(task.buffer, task.offset, task.size)
        }
        return results
    }

    // ---- OnWriteNetData @0xBCAC ----

    /**
     * @spec B-07 / B-08 / B-09 / B-10 / B-11 / B-12 / B-13 / B-14 / B-15 / B-19
     *
     * 签名 `(const shared_ptr<NMDataBuffer>&, long long, long long)`。
     */
    fun onWriteNetData(buffer: ByteArray, offset: Long, size: Long): Int {
        // @spec B-08 / B-09：0xBCD8 ldrb [+0xB8] / 0xBDD4 mov w0,#5
        if (!normalMode) {
            gate.log(5, "NMBufferSourceImp::OnWriteNetData Failed Not Support.")
            return RESULT_NOT_SUPPORT
        }

        // @spec B-10：buffer 无效 → "Failed Data Invalid." 直接返回
        if (buffer.isEmpty() || size <= 0L) {
            gate.log(5, "NMBufferSourceImp::OnWriteNetData Failed Data Invalid.")
            return RESULT_DATA_INVALID
        }

        // @spec B-11：0xBD00 ldr x8,[x19,#0xA8] 读 file_offset_
        val waterMark = fileOffset
        val end = offset + size

        // @spec B-12：0xBD14 b.le 0xBE88 —— end <= file_offset_ → SKIP 整包跳过
        if (end <= waterMark) {
            gate.log(
                5,
                "NMBufferSourceImp(0)::OnWriteNetData SKIP offset:$offset size:$size file_offset_:$waterMark",
            )
            return RESULT_SKIP
        }

        // @spec B-13：0xBD18 subs x23,x8,x21 —— offset < file_offset_ < end → TRIM 只写尾部
        var effectiveOffset = offset
        var effectiveSize = size
        if (offset < waterMark && waterMark < end) {
            val skip = waterMark - offset
            effectiveOffset = waterMark
            effectiveSize = size - skip
            gate.log(
                5,
                "NMBufferSourceImp(0)::OnWriteNetData TRIM offset:$offset size:$size skip:$skip",
            )
        }

        val payload = if (effectiveOffset != offset || effectiveSize != size) {
            val from = (effectiveOffset - offset).toInt().coerceAtLeast(0)
            buffer.copyOfRange(from, from + effectiveSize.toInt().coerceAtMost(buffer.size - from))
        } else buffer

        val rc = slicesFile.write(payload, effectiveOffset, effectiveSize.toInt())

        // @spec B-14：0xBE80 strb wzr,[x19,#0xB8] 写失败后**永久**清零 normal-mode
        if (rc != 0) {
            normalMode = false
            gate.log(5, "Write Data Failed:$rc.")
            return rc
        }

        // 写成功推进连续写水位（SetFileOffset 语义）
        fileOffset = end
        // @spec B-15：0xBED4 bl 0x8940 → NMBufferSourceImp::NotifyCacheProgress(long)
        notifyCacheProgress(fileOffset)
        return 0
    }

    // ---- 其余具名函数 ----

    /**
     * @spec B-16 / D-01 / D-02 / D-03 / D-04 / D-05
     *
     * OnNetSourceDataDone @0xBEE8（size 384）。
     * 0xBF08 `cmp w1,#2` / 0xBF14 `b.ne 0xBFA0`：
     *   kind == 2 → GOT 0x4BED0 → PreloadDoneTaskOnWriteThreadEv @0xB86C（288B / 72 指令）
     *   kind != 2 → GOT 0x4BEE8 → OnFlushNetDataEv            @0xC068（88B  / 22 指令）
     * 两分支构造的任务对象结构完全一致（0x60 字节、同样 vtable 槽
     * 0x4AD18/0x4AD98/0x4AD50），唯一差别是 GOT 槽取出的函数指针；
     * **两分支都 bl 0x16468 投递同一队列**（共享投递函数共 3 个调用点）。
     * 0xC034 `blr x8` 为队列取出后的虚调用执行点。
     *
     * 注意：上一版把两个分支都简化成 onFlushNetData()，与 §61.54/§61.55 一手
     * 反汇编结论冲突，本批按「两分支不对称 + 级联完成链」1:1 纠正。
     */
    fun onNetSourceDataDone(kind: Long): NmDoneTask {
        val task = if (kind == NmKind.NATIVE_DONE_KIND_PRELOAD_DONE) {
            // @spec D-02：kind==2 → PreloadDoneTaskOnWriteThread
            preloadDoneCount++
            NmDoneTask.PreloadDoneWrite
        } else {
            // @spec D-03：kind!=2 → OnFlushNetData
            flushTaskPosts++
            NmDoneTask.Flush
        }
        // @spec D-05：两分支都投递同一 0x16468 队列
        doneTaskQueue += task
        return task
    }

    /**
     * @spec D-06 驱动完成通知队列：0xC034 `blr x8` 取出任务后虚调用执行。
     * PreloadDoneTaskOnWriteThread 会级联再投递 Render 任务，FIFO 语义下排到队尾。
     */
    fun drainDoneQueue(): List<NmDoneTaskOutcome> {
        val outcomes = mutableListOf<NmDoneTaskOutcome>()
        while (doneTaskQueue.isNotEmpty()) {
            val task = doneTaskQueue.removeAt(0)
            outcomes += when (task) {
                NmDoneTask.Flush -> onFlushNetData()
                NmDoneTask.PreloadDoneWrite -> preloadDoneTaskOnWriteThread()
                NmDoneTask.PreloadDoneRender -> preloadDoneTaskOnRenderThread()
            }
        }
        return outcomes
    }

    /**
     * @spec B-17 / D-07 / D-08
     *
     * OnFlushNetData @0xC068（size 88 / 22 条指令），完整指令链：
     *   0xC070 `ldrb w8,[x0,#0xB8]`   读 normal-mode
     *   0xC074 `cbz w8, 0xC090`      为 0 → W 级日志分支
     *   0xC078 `ldr x0,[x0,#0xD8]`   取回调对象
     *   0xC07C `cbz x0, 0xC09C`      为空 → 直接 ret
     *   0xC080 `ldr x8,[x0]`         取 vtable
     *   0xC084 `ldr x1,[x8,#0x40]`   取 vtable+0x40
     *   0xC08C `br x1`               尾调用刷数据（不投递任何新任务）
     * 日志分支：0xC090 `mov w0,#5` → 0xC094 `bl 0x37E20` getter
     *          → 0xC098 `tbz w0,#0,0xC0A4` → 0xC0BC `b 0x3138C` logfn。
     */
    fun onFlushNetData(): NmDoneTaskOutcome {
        flushCount++
        // @spec D-07：0xC070 / 0xC074
        if (!normalMode) {
            // @spec D-08：0xC090~0xC0BC W(5) 级日志门控分支
            gate.log(5, "NMBufferSourceImp::OnFlushNetData Not Normal Mode.")
            return NmDoneTaskOutcome.FLUSH_LOG_BRANCH
        }
        // @spec D-07：0xC078 / 0xC07C —— [+0xD8] 回调对象为空则直接 ret
        if (!flushCallbackPresent) {
            return NmDoneTaskOutcome.FLUSH_RET_NO_CALLBACK
        }
        // @spec D-07：0xC080 / 0xC084 / 0xC08C —— vtable+0x40 尾调用
        flushTailCallCount++
        return NmDoneTaskOutcome.FLUSH_TAIL_CALL
    }

    /**
     * @spec D-09 / D-10
     *
     * PreloadDoneTaskOnWriteThread @0xB86C（288B / 72 指令）：
     *   0xB8C0 `ldr x9,[x9,#0xF28]` 取 GOT 0x4BF28 → PreloadDoneTaskOnRenderThread
     *   0xB8D8 `mov w10,#1` / 0xB8F0 `strb w10,[x0,#0x20]` 置 bool=true
     *   0xB900 `bl 0xD23C` / 0xB914 `blr x8`
     *   0xB924 `bl 0x16468` **级联再投递**到同一队列
     */
    fun preloadDoneTaskOnWriteThread(): NmDoneTaskOutcome {
        preloadDoneBoolTrue = true
        cascadePosts++
        doneTaskQueue += NmDoneTask.PreloadDoneRender
        return NmDoneTaskOutcome.PRELOAD_WRITE_CASCADE
    }

    /**
     * @spec D-11
     *
     * PreloadDoneTaskOnRenderThread @0xADBC（420B / 105 指令），
     * 关键调用 0xAE14/0xAEB4 `bl 0x37E20`（getter）、0xAE3C/0xAEE4 `bl 0x3138C`（logfn）、
     * 0xAF10 `blr x8`、0xAF50 `bl 0x10580`、0xAF5C `bl 0x8D30`：
     * 向渲染线程宣告「预加载完成」，是一条重的完成通知链。
     */
    fun preloadDoneTaskOnRenderThread(): NmDoneTaskOutcome {
        renderDoneCount++
        gate.log(5, "NMBufferSourceImp::PreloadDoneTaskOnRenderThread preload done.")
        return NmDoneTaskOutcome.PRELOAD_RENDER_DONE
    }

    /** @spec B-18：NotifyCacheProgress @0xC0C8（size 324），PLT 0x8940。 */
    fun notifyCacheProgress(progress: Long) {
        cacheProgress += progress
    }

    /** @spec B-19：SetFileOffset @0xB740（size 104），唯一具名写入 [+0xA8]。 */
    fun setFileOffset(value: Long) {
        fileOffset = value
    }

    /**
     * @spec B-20
     * §61.65 登记 `[+0xB8]` 还会被 `ReadFromFileNoLock @0xC4B8` 清零。
     * 静态证据定位到写入点，触发条件未逐指令坐实，翻译为独立可调用语义。
     */
    fun clearNormalModeOnReadPath() {
        normalMode = false
    }

    // ---- ReadFromFileNoLock / -103 链 ----

    /**
     * @spec B-21 / B-22 / B-29 / R-01 / R-02
     *
     * 0xC434 `blr [vtable+0x28]` 返回值 w23 即日志里的 result；
     * 0xC6F8 `bl 0x10198` 调 WaitRequest；返回值映射：
     * w0==0 → 0xC718 成功回读；w0==1 → 0xC728 -2；
     * w0==2 → 0xC710 -0x6a(-106)；其它 → 0xC730 -0x67(-103)；0xC73C ret。
     * 0xC478 `cmn w23,#2; b.eq 0xC4C4` 是打印后对 -2 的判定分支。
     *
     * @spec R-01：`.rodata @0x3BEE8` 格式串（唯一引用点 0xC454 adrp + 0xC45C add）：
     *   `NMBufferSourceImp(%lld)::ReadFromFileNoLock  Local File Read expect len:%u,
     *    offset:%lld, readed_len:%u, result:%d`
     * 四字段顺序 1:1 还原；`readed_len` 按读结果取实参（成功=length / 失败=0），
     * 不再硬编码 0。静态证据只钉死格式串字段与顺序，不虚构 readed_len 的寄存器来源。
     */
    fun readFromFileNoLock(offset: Long, length: Long, expectOffset: Long): Int {
        val end = offset + length
        val wait = netSource.waitRequest(offset, end, expectOffset, end, false)
        val result = when (wait) {
            NmNetSource.WAIT_OK -> 0
            NmNetSource.WAIT_STARVED -> NmError.WAIT_STARVED
            NmNetSource.WAIT_FORCE -> NmError.WAIT_FORCE
            else -> NmError.FINISH_ASYNC
        }
        // @spec B-29
        if (result == NmError.WAIT_STARVED) starvedReadCount++
        // @spec R-01：成功回读才读到期望长度，失败路径读空 readed_len=0
        val readedLen = if (result == 0) length.toInt() else 0
        gate.log(
            3,
            "NMBufferSourceImp(0)::ReadFromFileNoLock  Local File Read " +
                "expect len:${length.toInt()}, offset:$offset, " +
                "readed_len:$readedLen, result:$result",
        )
        return result
    }

    /**
     * @spec B-26
     * [+0x198] 挂起异步读登记 / 清零共 6 处：
     * 0x9F44（构造异步读任务）、0x10B20（WaitRequest 附近）、0xA018（Finish Async 回调清零）、
     * 0x100C8 / 0x106C0 / 0x1095C。
     */
    fun markAsyncReadStarted() {
        pendingAsyncRead = 1L
        asyncCtxA = 1L
        asyncCtxB = 1L
    }

    /**
     * @spec B-23 / B-24 / B-25
     * 0xA9BC `mov w25,#-2` 与 0xA9C4 `mov w25,#-0x67` 是两条硬编码分支，
     * 共用 0xA9E4 `str w25,[+0x1A0]`；写入前置 0xA9D0 `ldr x8,[x19,#0x198]`
     * + 0xA9D4 `cbz`：仅挂起异步读非零才写 [+0x1A0]。
     */
    fun onAsyncReadComplete(starved: Boolean) {
        val code = if (starved) NmError.WAIT_STARVED else NmError.FINISH_ASYNC
        if (pendingAsyncRead != 0L) {
            asyncErrorCode = code
        }
    }

    /**
     * @spec B-27 / B-28
     * Finish Async 回调由写线程任务队列投递（0x9FC0 `bl 0x16468`）；
     * 0xA008 `ldr w20,[x19,#0x1A0]` 读错误码 → 0xA010~0xA018 清零
     * +0x188/+0x190/+0x198 → 0xA01C getter → 0xA040 `bl 0x3138C` 打印
     * → 0xA064 `mov w0,w20` + 0xA07C ret 把 -103 **原样返回播放侧**。
     * .rodata @0x3B8F3 = `NMBufferSourceImp::Read((%lld-%lld) Finish Async:%d`
     */
    fun finishAsync(): Int {
        val code = asyncErrorCode
        asyncCtxA = 0L
        asyncCtxB = 0L
        pendingAsyncRead = 0L
        gate.log(5, "NMBufferSourceImp::Read((1-${netSource.generation}) Finish Async:$code")
        finishAsyncLogCount++
        return code
    }

    /** @spec B-01：NMBufferSourceImp::Seek（rodata 0x3B927，与 Finish Async 串区分）。 */
    fun seek(position: Long): Long {
        gate.log(3, "NMBufferSourceImp::Seek($position)")
        return position
    }

    fun reset() {
        fileOffset = 0L
        normalMode = true
        pendingAsyncRead = 0L
        asyncErrorCode = 0
        asyncCtxA = 0L
        asyncCtxB = 0L
        memoryCacheWrites = 0
        flushCount = 0
        flushTaskPosts = 0
        preloadDoneCount = 0
        cascadePosts = 0
        renderDoneCount = 0
        flushTailCallCount = 0
        preloadDoneBoolTrue = false
        flushCallbackPresent = true
        doneTaskQueue.clear()
        starvedReadCount = 0
        finishAsyncLogCount = 0
        cacheProgress.clear()
        writeTaskQueue.clear()
    }

    companion object {
        /** OnWriteNetData 的本地分支返回码（非 native 立即数，属于该函数内部路径标记）。 */
        const val RESULT_NOT_SUPPORT: Int = 1
        const val RESULT_DATA_INVALID: Int = 2
        const val RESULT_SKIP: Int = 3

        // ---- §61.54 / §61.55 一手反汇编元数据（D 组，逐项可断言） ----
        /** @spec D-01 OnNetSourceDataDone 函数地址 / size */
        const val DONE_FUNC_ADDR: Int = 0xBEE8
        const val DONE_FUNC_SIZE: Int = 384
        /** @spec D-02 kind==2 的 GOT 槽与目标函数 */
        const val GOT_KIND2: Int = 0x4BED0
        const val PRELOAD_WRITE_ADDR: Int = 0xB86C
        const val PRELOAD_WRITE_SIZE: Int = 288
        const val PRELOAD_WRITE_INSNS: Int = 72
        /** @spec D-03 kind!=2 的 GOT 槽与目标函数 */
        const val GOT_OTHER: Int = 0x4BEE8
        const val FLUSH_ADDR: Int = 0xC068
        const val FLUSH_SIZE: Int = 88
        const val FLUSH_INSNS: Int = 22
        /** @spec D-04 任务对象大小 */
        const val TASK_OBJECT_SIZE: Int = 0x60
        /** @spec D-05 源 JSON `queue_bl_0x16468_count`：Done 函数窗口内的 2 处投递点 */
        const val DONE_WINDOW_QUEUE_POSTS: Int = 2
        /** @spec D-05 共享队列投递函数地址 / 调用点数（3 = 窗口内 2 + 级联 1） */
        const val QUEUE_POST_ADDR: Int = 0x16468
        const val QUEUE_POST_CALL_SITES: Int = 3
        /** @spec D-10 级联目标 GOT 与 Render 任务 */
        const val GOT_RENDER: Int = 0x4BF28
        const val PRELOAD_RENDER_ADDR: Int = 0xADBC
        const val PRELOAD_RENDER_SIZE: Int = 420
        const val PRELOAD_RENDER_INSNS: Int = 105
        /** @spec D-12 l3-f4-task-diff.json 的 checks 统计 */
        const val TASK_DIFF_CHECKS_TOTAL: Int = 14
        const val TASK_DIFF_CHECKS_PASS: Int = 14
    }
}

/** @spec D-02 / D-03 / D-10：OnNetSourceDataDone 投递到 0x16468 队列的任务类型。 */
enum class NmDoneTask { PreloadDoneWrite, Flush, PreloadDoneRender }

/** @spec D-07 / D-08 / D-09 / D-10 / D-11：任务执行结果（门控与链路终态）。 */
enum class NmDoneTaskOutcome {
    FLUSH_TAIL_CALL,
    FLUSH_RET_NO_CALLBACK,
    FLUSH_LOG_BRANCH,
    PRELOAD_WRITE_CASCADE,
    PRELOAD_RENDER_DONE,
}
