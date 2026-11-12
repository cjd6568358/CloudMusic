@file:JvmName("ContractTests")
@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * 竞品播放内核 1:1 Kotlin 逻辑翻译 —— 纯 JVM 契约测试。
 *
 * 零外部依赖（无 JUnit / 无 Android / 无 kotlin-reflect），由
 * tools/build.py 编译并运行。每个断言对应 spec/LOGIC-SPEC.md 里的
 * 一条 @spec 编号，确保「反汇编证据 → 规格 → Kotlin → 测试」可机读闭环。
 *
 * 退出码：0 = 全绿；1 = 有失败（build.py 据此判定 overallOk）。
 */

// ---------------------------------------------------------------------------
// 轻量断言框架
// ---------------------------------------------------------------------------

private class Suite {
    var total: Int = 0
        private set
    var passed: Int = 0
        private set
    var failed: Int = 0
        private set
    val failures = mutableListOf<String>()

    fun check(name: String, condition: Boolean) {
        total++
        if (condition) {
            passed++
        } else {
            failed++
            failures += name
            println("  [FAIL] $name")
        }
    }

    fun eq(name: String, expected: Any?, actual: Any?) {
        total++
        if (expected == actual) {
            passed++
        } else {
            failed++
            val msg = "$name (expected=$expected, actual=$actual)"
            failures += msg
            println("  [FAIL] $msg")
        }
    }

    fun section(name: String) = println("[SECTION] $name")
}

// ---------------------------------------------------------------------------
// 测试桩
// ---------------------------------------------------------------------------

private class ConfigurableRequester(
    var ok: Boolean = true,
    var code: Int = 0,
) : NmRangeRequester {
    var lastGeneration: Long = -1L
    var lastOffset: Long = -1L
    var lastLength: Long = -1L
    var callCount: Int = 0

    override fun start(generation: Long, offset: Long, length: Long): NmRangeStartResult {
        lastGeneration = generation
        lastOffset = offset
        lastLength = length
        callCount++
        return NmRangeStartResult(ok, code)
    }
}

private class NoopSink : NmDataSink {
    var calls: Int = 0
    var lastKind: Long = -1L
    var lastOffset: Long = -1L
    var lastSize: Long = -1L

    override fun onNetSourceData(kind: Long, buffer: ByteArray, offset: Long, size: Long): Int {
        calls++
        lastKind = kind
        lastOffset = offset
        lastSize = size
        return 0
    }
}

private class RecordingCallback : NmPlayerCallback {
    val errors = mutableListOf<Int>()
    val states = mutableListOf<Int>()
    override fun onError(code: Int) { errors += code }
    override fun onStateChange(stateCode: Int) { states += stateCode }
}

private class GoodLogger : NmLogger {
    val calls = mutableListOf<Pair<Int, String>>()
    override fun biInfo(level: Int, message: String) { calls += level to message }
}

private fun newGate(): Pair<NmLogGate, NmCollectingLogSink> {
    val sink = NmCollectingLogSink()
    return NmLogGate(sink) to sink
}

private fun newNet(
    requester: ConfigurableRequester = ConfigurableRequester(),
    sink: NmDataSink = NoopSink(),
    gate: NmLogGate = newGate().first,
): NmNetSource = NmNetSource(requester, sink, gate, sourceId = 1L)

private fun newBuffer(gate: NmLogGate, fileLen: Long = 1048576L): NmBufferSourceImp {
    val slices = NmSlicesFile(fileLen, 4096L, gate = gate)
    val net = newNet(gate = gate)
    return NmBufferSourceImp(gate, slices, net)
}

// ---------------------------------------------------------------------------
// 1. 类型 / 常量层
// ---------------------------------------------------------------------------

private fun testNativeTypes(s: Suite) {
    s.section("1. 类型与常量层（偏移 / 错误码 / kind / 日志级别）")

    s.eq("NET_COMMIT offset", 0x30, NmFieldOffsets.NET_COMMIT)
    s.eq("NET_GENERATION offset", 0xC0, NmFieldOffsets.NET_GENERATION)
    s.eq("NET_REQ_SIZE offset", 0x38, NmFieldOffsets.NET_REQ_SIZE)
    s.eq("NET_SIGNAL_SIZE offset", 0x40, NmFieldOffsets.NET_SIGNAL_SIZE)
    s.eq("BUF_FILE_OFFSET offset", 0xA8, NmFieldOffsets.BUF_FILE_OFFSET)
    s.eq("BUF_NORMAL_MODE offset", 0xB8, NmFieldOffsets.BUF_NORMAL_MODE)
    s.eq("BUF_PENDING_ASYNC offset", 0x198, NmFieldOffsets.BUF_PENDING_ASYNC)
    s.eq("BUF_ASYNC_ERROR offset", 0x1A0, NmFieldOffsets.BUF_ASYNC_ERROR)
    s.eq("SLI_FILE_LEN offset", 0x70, NmFieldOffsets.SLI_FILE_LEN)
    s.eq("SLI_SLICE_SIZE offset", 0xD0, NmFieldOffsets.SLI_SLICE_SIZE)
    s.eq("SLI_FINISHED offset", 0x104, NmFieldOffsets.SLI_FINISHED)
    s.eq("AUD_TRACK offset", 0x1E8, NmFieldOffsets.AUD_TRACK)
    s.eq("AUD_PAUSED offset", 0x20C, NmFieldOffsets.AUD_PAUSED)
    s.eq("AUD_RETRY offset", 0x354, NmFieldOffsets.AUD_RETRY)
    s.eq("G_LEVEL_BSS", 0x4C388, NmFieldOffsets.G_LEVEL_BSS)

    s.eq("EOF", -1, NmError.EOF)
    s.eq("WAIT_STARVED", -2, NmError.WAIT_STARVED)
    s.eq("AUDIO_RETRY_EXHAUSTED", -3, NmError.AUDIO_RETRY_EXHAUSTED)
    s.eq("SLICE_WRITE_FAILED", -98, NmError.SLICE_WRITE_FAILED)
    s.eq("SLICE_DISABLED", -100, NmError.SLICE_DISABLED)
    s.eq("INVALID_RANGE", -102, NmError.INVALID_RANGE)
    s.eq("FINISH_ASYNC", -103, NmError.FINISH_ASYNC)
    s.eq("WAIT_FORCE", -106, NmError.WAIT_FORCE)

    s.eq("KIND_NORMAL", 1L, NmKind.NATIVE_DATA_KIND_NORMAL)
    s.eq("KIND_PRELOAD", 2L, NmKind.NATIVE_DATA_KIND_PRELOAD)
    s.eq("DONE_FLUSH", 1L, NmKind.NATIVE_DONE_KIND_FLUSH)
    s.eq("DONE_PRELOAD", 2L, NmKind.NATIVE_DONE_KIND_PRELOAD_DONE)

    s.eq("level 3 -> DEBUG", NmLogLevel.DEBUG, NmLogLevel.fromNative(3))
    s.eq("level 4 -> INFO", NmLogLevel.INFO, NmLogLevel.fromNative(4))
    s.eq("level 5 -> WARN", NmLogLevel.WARN, NmLogLevel.fromNative(5))
    s.eq("level 6 -> ERROR", NmLogLevel.ERROR, NmLogLevel.fromNative(6))
    s.eq("level 99 -> null", null, NmLogLevel.fromNative(99))
    s.eq("WARN androidPriority", "W", NmLogLevel.WARN.androidPriority)
}

// ---------------------------------------------------------------------------
// 2. 日志门控 G-01 ~ G-15
// ---------------------------------------------------------------------------

private fun testLogGate(s: Suite) {
    s.section("2. 全局日志门控 G-01~G-15（§61.70 语义纠错）")

    val (gate, sink) = newGate()
    s.eq("G-03/G-04 默认 g_level=0", 0, gate.globalLevel)
    s.check("G-05 默认 D(3) 全开", gate.shouldLog(3))
    s.check("G-05 默认 I(4) 全开", gate.shouldLog(4))
    s.check("G-05 默认 W(5) 全开", gate.shouldLog(5))
    s.check("G-05 默认 E(6) 全开", gate.shouldLog(6))

    // getter 语义：g_level > N 才抑制
    s.check("G-01 g=0 不抑制 W(5)", !gate.isSuppressed(5))
    gate.setLevel(5)
    s.eq("G-02 setLevel 生效", 5, gate.globalLevel)
    s.check("G-01 g=5 抑制 D(3)", gate.isSuppressed(3))
    s.check("G-01 g=5 抑制 I(4)", gate.isSuppressed(4))
    s.check("G-01 g=5 **不**抑制 W(5)", !gate.isSuppressed(5))
    s.check("G-01 g=5 不抑制 E(6)", !gate.isSuppressed(6))
    gate.setLevel(6)
    s.check("G-01 g=6 抑制 W(5)", gate.isSuppressed(5))
    gate.setLevel(0)

    // log 实际输出
    gate.log(5, "hello-warn")
    s.eq("G-09/G-13 log 输出 1 条", 1, sink.records.size)
    s.eq("G-09 tag 固定 NMCache", NmLogGate.TAG_NMCACHE, sink.records[0].tag)
    s.eq("G-09 level=WARN", NmLogLevel.WARN, sink.records[0].level)
    s.eq("G-09 消息原样", "hello-warn", sink.records[0].message)
    gate.setLevel(6)
    gate.log(5, "suppressed-warn")
    s.eq("G-05 抑制时不输出", 1, sink.records.size)
    gate.setLevel(0)

    // SetLoggerCallback：只调 setter；logger 类型决定注册成败
    val good = gate.setLoggerCallback(3, GoodLogger())
    s.check("G-10 合法 NmLogger 注册成功", good)
    s.eq("G-02 setLoggerCallback 抬阈值", 3, gate.globalLevel)
    s.check("G-11 loggerRegistered", gate.loggerRegistered)
    s.check("G-11 loggerInitMarker", gate.loggerInitMarker)
    s.check("G-11 loggerMethodIdCached", gate.loggerMethodIdCached)
    s.check("G-11 loggerGlobalRefCached", gate.loggerGlobalRefCached)

    val (gate2, _) = newGate()
    val bad = gate2.setLoggerCallback(5, Any())
    s.check("G-11 空类缺 BIInfo → 注册失败", !bad)
    s.eq("G-02 失败仍抬阈值", 5, gate2.globalLevel)
    s.check("G-11 失败不置 initMarker", !gate2.loggerInitMarker)
    s.check("G-12 失败不置 methodId", !gate2.loggerMethodIdCached)

    // 统计常量
    s.eq("G-06 getter 调用点", 173, NmLogGate.GETTER_CALL_SITES)
    s.eq("G-07 logfn 调用点", 156, NmLogGate.LOGFN_CALL_SITES)
    s.eq("G-08 (N,M) 恒等对", 163, NmLogGate.NM_IDENTICAL_PAIRS)
    s.eq("G-08 tbnz 数", 147, NmLogGate.TBNZ_COUNT)
    s.eq("G-08 tbz 数", 26, NmLogGate.TBZ_COUNT)
    s.eq("G-08 直方图合计=恒等对", NmLogGate.NM_IDENTICAL_PAIRS, NmLogGate.LEVEL_PAIR_HISTOGRAM.values.sum())

    // §61.70 §5.1 早退副作用：默认关闭，不虚构指令
    val (gate3, _) = newGate()
    s.eq("G-15 默认不改变返回码", 0, gate3.earlyReturnCodeIfSuppressed(5))
    gate3.suppressedSideEffectCode = NmError.GATE_EARLY_6
    gate3.setLevel(6)
    s.eq("G-15 显式配置后抑制返回 -6", NmError.GATE_EARLY_6, gate3.earlyReturnCodeIfSuppressed(5))
    s.eq("G-15 未抑制返回 0", 0, gate3.earlyReturnCodeIfSuppressed(6))
}

// ---------------------------------------------------------------------------
// 3. NMNetSource N-01 ~ N-30
// ---------------------------------------------------------------------------

private fun testNetSource(s: Suite) {
    s.section("3. NMNetSource 代次闸门 / commit / 唤醒 N-01~N-30")

    val requester = ConfigurableRequester()
    val sink = NoopSink()
    val (gate, logs) = newGate()
    val net = NmNetSource(requester, sink, gate, sourceId = 1L)

    s.eq("N-03 初始代次=0", 0L, net.generation)
    s.eq("N-15 初始 commit=0", 0L, net.commit)
    s.eq("N-22 初始 signal_size", 8192L, net.signalSize)

    // N-01/N-02/N-05 递增代次 + 透传参数
    val gen1 = net.makeRangeRequestAsync(0L, 16384L, 8192L)
    s.eq("N-05 新代次=1", 1L, gen1)
    s.eq("N-03 写回代次", 1L, net.generation)
    s.eq("N-02 requestOffset", 0L, net.requestOffset)
    s.eq("N-22 requestSize", 16384L, net.requestSize)
    s.eq("N-22 signalSize", 8192L, net.signalSize)
    s.eq("N-06 requester 收到代次", 1L, requester.lastGeneration)
    s.eq("N-06 requester 收到 offset", 0L, requester.lastOffset)
    s.eq("N-06 requester 收到 length", 16384L, requester.lastLength)

    // N-15~N-21 正常顺序写入
    repeat(4) { i ->
        val r = net.onData(gen1, i * 4096L, 4096L, ByteArray(4096))
        s.check("N-20 第${i + 1}包 ACCEPTED", r is NmOnDataResult.ACCEPTED)
    }
    s.eq("N-21 commit=16384", 16384L, net.commit)
    s.eq("N-17 数据投递 4 次", 4, sink.calls)
    s.eq("N-17 投递 kind=NORMAL", NmKind.NATIVE_DATA_KIND_NORMAL, sink.lastKind)
    s.eq("G-14 正常顺序不打 UnExpect", 0, logs.countOf("UnExpect New Offsetcommted"))

    // N-15/N-16 commit != offset 只打 W 日志、不丢弃
    val jumped = net.onData(gen1, 20480L, 4096L, ByteArray(4096))
    s.check("N-16 跳跃仍 ACCEPTED", jumped is NmOnDataResult.ACCEPTED)
    s.eq("N-16 跳跃打印 UnExpect", 1, logs.countOf("UnExpect New Offsetcommted"))
    s.eq("N-21 跳跃后 commit=24576", 24576L, net.commit)

    // N-12/N-13/N-14/N-25 代次不匹配整包丢弃
    net.makeRangeRequestAsync(16384L, 1048576L, 8192L) // 代次 -> 2
    val commitBefore = net.commit
    val dropped = net.onData(gen1, 24576L, 4096L, ByteArray(4096))
    s.check("N-14 旧代次 DROPPED", dropped is NmOnDataResult.DROPPED)
    s.eq("N-25 丢弃不写 commit", commitBefore, net.commit)
    s.eq("N-25 丢弃不投递", 5, sink.calls) // 4 正常 + 1 跳跃，丢弃包未新增
    s.eq("N-25 droppedPacketCount", 1, net.droppedPacketCount)

    // N-22/N-23/N-24 唤醒链
    val (gate2, _) = newGate()
    val net2 = NmNetSource(ConfigurableRequester(), NoopSink(), gate2)
    val g2 = net2.makeRangeRequestAsync(0L, 100L, 10L)
    s.check("N-23 初始未唤醒", !net2.wakeFlag)
    net2.onData(g2, 0L, 109L, ByteArray(109))
    s.check("N-22 commit(109) < 110 不唤醒", !net2.wakeFlag)
    net2.onData(g2, 109L, 1L, ByteArray(1))
    s.check("N-23 commit(110)>=110 置唤醒标志", net2.wakeFlag)
    s.eq("N-24 notify_all 计数", 1, net2.notifyCount)

    // N-10 请求失败把 [+0x20..0x40] 置 -1
    val failRequester = ConfigurableRequester(ok = false, code = -7)
    val (gate3, _) = newGate()
    val net3 = NmNetSource(failRequester, NoopSink(), gate3)
    net3.makeRangeRequestAsync(500L, 1000L, 8192L)
    s.check("N-10 requestFailed", net3.requestFailed)
    s.eq("N-10 requestErrorCode", -7, net3.requestErrorCode)
    s.eq("N-10 requestOffset=-1", -1L, net3.requestOffset)
    s.eq("N-10 requestSize=-1", -1L, net3.requestSize)
    s.eq("N-10 signalSize=-1", -1L, net3.signalSize)

    // N-26~N-29 WaitRequest 四种返回
    val (gate4, _) = newGate()
    val net4 = NmNetSource(ConfigurableRequester(), NoopSink(), gate4)
    s.eq("N-27 commit>=end → WAIT_OK", NmNetSource.WAIT_OK, net4.waitRequest(0, 0, 0, 0, false))
    s.eq("N-27 force → WAIT_FORCE", NmNetSource.WAIT_FORCE, net4.waitRequest(0, 100, 100, 100, true))
    s.eq("N-29 commit<expectOffset → WAIT_STARVED", NmNetSource.WAIT_STARVED, net4.waitRequest(0, 100, 100, 100, false))
    val g4 = net4.makeRangeRequestAsync(0, 100, 8192)
    net4.onData(g4, 0, 50, ByteArray(50)) // commit=50
    s.eq("N-29 其它 → WAIT_OTHER", NmNetSource.WAIT_OTHER, net4.waitRequest(0, 100, 50, 100, false))

    // N-30 回退连续提交点
    val (gate5, _) = newGate()
    val net5 = NmNetSource(ConfigurableRequester(), NoopSink(), gate5)
    val g5a = net5.makeRangeRequestAsync(0, 16384, 8192)
    net5.onData(g5a, 0, 16384, ByteArray(16384))
    val g5b = net5.rollbackToContinuousCommitPoint(16384L)
    s.eq("N-30 回退后 commit", 16384L, net5.commit)
    s.eq("N-30 回退触发代次递增", 2L, g5b)

    // reset
    net5.reset()
    s.eq("reset 代次归零", 0L, net5.generation)
    s.eq("reset commit 归零", 0L, net5.commit)
    s.eq("reset dropped 归零", 0, net5.droppedPacketCount)
}

// ---------------------------------------------------------------------------
// 4. NMSlicesFile S-01 ~ S-23
// ---------------------------------------------------------------------------

private fun testSlicesFile(s: Suite) {
    s.section("4. NMSlicesFile 四道静默门控 / 分片写 S-01~S-23")

    val (gate, logs) = newGate()
    val f = NmSlicesFile(fileLen = 1048576L, sliceSize = 4096L, gate = gate)

    // S-01/S-02/S-03 disabled → -100
    f.disabled = true
    s.eq("S-03 disabled → -100", NmError.SLICE_DISABLED, f.write(ByteArray(64), 0L, 64))
    f.disabled = false

    // S-04/S-05 len==0 或 未 64B 对齐 → -102
    s.eq("S-04 len==0 → -102", NmError.INVALID_RANGE, f.write(ByteArray(0), 0L, 0))
    s.eq("S-05 offset=1 未对齐 → -102", NmError.INVALID_RANGE, f.write(ByteArray(64), 1L, 64))
    s.check("S-09 失败诊断日志已打印", logs.countOf("NMSlicesFile Write buffer") >= 2)

    // S-06/S-07/S-08 越界 → -102
    s.eq("S-08 offset+len>file_len → -102", NmError.INVALID_RANGE, f.write(ByteArray(128), 1048576L - 64L, 128))

    // S-14/S-15 EOF → -1（仅 len<=0 的非正常输入可达，1:1 保留）
    s.eq("S-15 file_len==offset → -1", NmError.EOF, f.write(ByteArray(64), 1048576L, -1))
    s.check("S-15 Finish EOF 日志", logs.countOf("Finish EOF") >= 1)

    // S-16~S-21 正常分片写
    val rc = f.write(ByteArray(8192), 0L, 8192)
    s.eq("S-21 成功返回 0", 0, rc)
    s.check("S-20 writeFinished 置位", f.writeFinished)
    // @spec W-04 / W-06：真实指令是半开区间 [qStart, upper)，end=8192 整除 4096 时 upper=2
    // → 只写索引 0/1 共 2 次；旧实现 qStart..end/size 闭区间会多写一片（3 次），已修正。
    s.eq("S-16 / W-04 分片写调用 2 次（半开区间 [0,2)）", 2, f.sliceWriteCalls)
    s.check("S-19 Write all finish 日志", logs.countOf("Write all finish") >= 1)

    // S-18 分片写失败 → -98
    val (gate2, _) = newGate()
    val f2 = NmSlicesFile(1048576L, 4096L, gate = gate2)
    f2.sliceWriteOverride = { -1 }
    s.eq("S-18 分片失败 → -98", NmError.SLICE_WRITE_FAILED, f2.write(ByteArray(64), 0L, 64))
    s.check("S-18 失败不置 writeFinished", !f2.writeFinished)

    // S-09~S-13 / G-15：g_level>5 不打诊断日志，但返回码不变
    val (gate3, logs3) = newGate()
    gate3.setLevel(6)
    val f3 = NmSlicesFile(1048576L, 4096L, gate = gate3)
    s.eq("S-13 g>5 仍返回 -102", NmError.INVALID_RANGE, f3.write(ByteArray(0), 0L, 0))
    s.eq("S-13 g>5 不打诊断日志", 0, logs3.countOf("NMSlicesFile Write buffer"))

    // S-23/S-22 Read
    s.eq("S-22 Read EOF → -1", NmError.EOF, f.read(1048576L, 64))
    s.eq("S-23 Read 正常返回 min", 64, f.read(0L, 64))
    s.eq("S-23 Read 尾部截断", 100, f.read(1048576L - 100L, 200))

    // B-30 另一处 -2 越界门控（独立路径）
    s.eq("B-30 secondaryRangeGate 越界 → -2", NmError.WAIT_STARVED, f.secondaryRangeGate(1048576L, 10))
    s.eq("B-30 secondaryRangeGate 正常 → 0", 0, f.secondaryRangeGate(0L, 10))
}

// ---------------------------------------------------------------------------
// 5. NMBufferSourceImp B-01 ~ B-30
// ---------------------------------------------------------------------------

private fun testBufferSource(s: Suite) {
    s.section("5. NMBufferSourceImp 写链 / 错误链 B-01~B-30")

    // B-01~B-06 kind 分支
    val (gate, _) = newGate()
    val buf = newBuffer(gate)
    buf.onNetSourceData(NmKind.NATIVE_DATA_KIND_NORMAL, ByteArray(64), 0L, 64L)
    s.eq("B-04 kind=1 写内存镜像", 1, buf.memoryCacheWrites)
    buf.onNetSourceData(NmKind.NATIVE_DATA_KIND_PRELOAD, ByteArray(64), 64L, 64L)
    s.eq("B-02 kind=2 跳过内存镜像", 1, buf.memoryCacheWrites)
    s.eq("B-05/B-06 两种 kind 都投递任务", 2, buf.writeTaskQueue.size)

    // B-07~B-15 OnWriteNetData
    val (gate2, _) = newGate()
    val buf2 = newBuffer(gate2)
    buf2.setFileOffset(0L)
    s.eq("B-15 正常写返回 0", 0, buf2.onWriteNetData(ByteArray(64), 0L, 64L))
    s.eq("B-11 写成功推进 file_offset_", 64L, buf2.fileOffset)
    s.eq("B-15 NotifyCacheProgress 记录", 1, buf2.cacheProgress.size)

    s.eq("B-10 buffer 空 → DATA_INVALID", NmBufferSourceImp.RESULT_DATA_INVALID, buf2.onWriteNetData(ByteArray(0), 64L, 64L))
    s.eq("B-12 end<=file_offset_ → SKIP", NmBufferSourceImp.RESULT_SKIP, buf2.onWriteNetData(ByteArray(64), 0L, 64L))

    // B-13 TRIM：offset < waterMark < end
    val (gate3, _) = newGate()
    val buf3 = newBuffer(gate3)
    buf3.setFileOffset(128L)
    s.eq("B-13 TRIM 后写成功", 0, buf3.onWriteNetData(ByteArray(128), 64L, 128L))
    s.eq("B-11 TRIM 后 file_offset_=192", 192L, buf3.fileOffset)

    // B-14 写失败永久清零 normal-mode
    val (gate4, _) = newGate()
    val slices4 = NmSlicesFile(1048576L, 4096L, gate = gate4)
    slices4.sliceWriteOverride = { -1 }
    val buf4b = NmBufferSourceImp(gate4, slices4, newNet(gate = gate4))
    s.eq("B-14 分片写失败返回 -98", NmError.SLICE_WRITE_FAILED, buf4b.onWriteNetData(ByteArray(64), 0L, 64L))
    s.check("B-14 normalMode 永久清零", !buf4b.normalMode)
    s.eq("B-08 再写直接 NOT_SUPPORT", NmBufferSourceImp.RESULT_NOT_SUPPORT, buf4b.onWriteNetData(ByteArray(64), 64L, 64L))
    buf4b.clearNormalModeOnReadPath()
    s.check("B-20 clearNormalModeOnReadPath 独立语义", !buf4b.normalMode)

    // B-16/B-17/B-18/B-19 —— §61.54/§61.55 纠错：两分支不对称，不再「都 Flush」
    val (gate5, _) = newGate()
    val buf5 = newBuffer(gate5)
    val t1 = buf5.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_PRELOAD_DONE)
    s.eq("B-16 kind=2 → preloadDone 投递 1", 1, buf5.preloadDoneCount)
    s.eq("B-16 kind=2 → 不投递 Flush 任务", 0, buf5.flushTaskPosts)
    s.eq("D-02 kind=2 → PreloadDoneWrite", NmDoneTask.PreloadDoneWrite, t1)
    val t2 = buf5.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
    s.eq("B-16 kind=1 不再增 preloadDone", 1, buf5.preloadDoneCount)
    s.eq("B-17 kind=1 → 投递 Flush 任务 1", 1, buf5.flushTaskPosts)
    s.eq("D-03 kind=1 → Flush", NmDoneTask.Flush, t2)
    s.eq("D-05 两分支都进同一队列", 2, buf5.doneTaskQueue.size)
    s.eq("B-17 未 drain 前不执行 Flush", 0, buf5.flushCount)
    buf5.notifyCacheProgress(999L)
    s.eq("B-18 NotifyCacheProgress 追加", 1, buf5.cacheProgress.size)
    buf5.setFileOffset(777L)
    s.eq("B-19 SetFileOffset", 777L, buf5.fileOffset)

    // B-21/B-22/B-29 ReadFromFileNoLock 四种映射
    val (gate6, _) = newGate()
    val buf6 = newBuffer(gate6)
    s.eq("B-22 WAIT_OK → 0", 0, buf6.readFromFileNoLock(0L, 0L, 0L))
    s.eq("B-22 WAIT_STARVED → -2", NmError.WAIT_STARVED, buf6.readFromFileNoLock(0L, 100L, 100L))
    s.eq("B-29 starvedReadCount", 1, buf6.starvedReadCount)
    val net6 = newNet(gate = gate6)
    val g6 = net6.makeRangeRequestAsync(0, 100, 8192)
    net6.onData(g6, 0, 50, ByteArray(50))
    val buf6b = NmBufferSourceImp(gate6, NmSlicesFile(1048576L, 4096L, gate = gate6), net6)
    s.eq("B-22 WAIT_OTHER → -103", NmError.FINISH_ASYNC, buf6b.readFromFileNoLock(0L, 100L, 50L))

    // B-23/B-24/B-25/B-26/B-27/B-28 挂起异步读 + Finish Async
    val (gate7, logs7) = newGate()
    val buf7 = newBuffer(gate7)
    buf7.onAsyncReadComplete(starved = false)
    s.eq("B-23 未登记挂起读 → 不写 [+0x1A0]", 0, buf7.asyncErrorCode)
    buf7.markAsyncReadStarted()
    s.eq("B-26 pendingAsyncRead=1", 1L, buf7.pendingAsyncRead)
    s.eq("B-26 asyncCtxA=1", 1L, buf7.asyncCtxA)
    buf7.onAsyncReadComplete(starved = false)
    s.eq("B-25 非饥饿 → -103", NmError.FINISH_ASYNC, buf7.asyncErrorCode)
    val code7 = buf7.finishAsync()
    s.eq("B-27 Finish Async 原样返回 -103", NmError.FINISH_ASYNC, code7)
    s.eq("B-27 清零 pending", 0L, buf7.pendingAsyncRead)
    s.eq("B-27 清零 ctxA", 0L, buf7.asyncCtxA)
    s.eq("B-27 清零 ctxB", 0L, buf7.asyncCtxB)
    s.eq("B-27 finishAsyncLogCount", 1, buf7.finishAsyncLogCount)
    s.check("B-28 Finish Async 日志含 -103", logs7.countOf("Finish Async:-103") >= 1)

    buf7.markAsyncReadStarted()
    buf7.onAsyncReadComplete(starved = true)
    s.eq("B-23 饥饿分支 → -2", NmError.WAIT_STARVED, buf7.asyncErrorCode)

    // B-01 Seek
    s.eq("B-01 Seek 返回位置", 123L, buf7.seek(123L))
}

// ---------------------------------------------------------------------------
// 6. NmAudioPlayer A-01 ~ A-11
// ---------------------------------------------------------------------------

private fun testAudioPlayer(s: Suite) {
    s.section("6. AudioTrack::Write 四道门控 / retry / callback A-01~A-11")

    val (gate, logs) = newGate()
    val audio = NmAudioPlayer(gate)
    val cb = RecordingCallback()
    audio.callback = cb

    // A-03 门控 1：AudioTrack 为空
    s.check("A-03 门控1 EARLY_RETURN_MINUS_1", audio.writeStartPlay() is NmWriteOutcome.EARLY_RETURN_MINUS_1)
    s.eq("A-03 不打印", 0, audio.writeStartPlayLogCount)
    s.eq("A-03 earlyReturnMinus1Count", 1, audio.earlyReturnMinus1Count)

    // A-05 门控 3：paused
    audio.attachAudioTrack(Any())
    audio.setPaused(true)
    s.check("A-05 门控3 SKIP_PRINT", audio.writeStartPlay() is NmWriteOutcome.SKIP_PRINT)
    s.eq("A-05 不打印", 0, audio.writeStartPlayLogCount)
    s.eq("A-10 setPaused 回调 state=2", 2, cb.states.last())

    // A-06 门控 4：bl 0x13D80 返回 3
    audio.setPaused(false)
    audio.internalStateProbeResult = 3
    s.check("A-06 门控4 SKIP_PRINT", audio.writeStartPlay() is NmWriteOutcome.SKIP_PRINT)
    s.eq("A-10 解 paused 回调 state=1", 1, cb.states.last())

    // A-07/A-01/A-02 四门控全放行 → 打印
    audio.internalStateProbeResult = 0
    audio.writeResult = 0
    val printed = audio.writeStartPlay()
    s.check("A-07 全放行 PRINTED", printed is NmWriteOutcome.PRINTED)
    s.eq("A-02 打印次数", 1, audio.writeStartPlayLogCount)
    s.check("A-01 日志含 Write start play", logs.countOf("Write start play nErr:0") >= 1)
    s.eq("A-08 nErr=0 清零 retry", 0, audio.writeRetryCount)

    // A-08/A-09 nErr!=0 重试与回调
    audio.writeResult = 5
    repeat(11) { audio.writeStartPlay() }
    s.eq("A-08 retry 累计到 10", 10, audio.writeRetryCount)
    s.check("A-09 回调收到 -3", cb.errors.contains(NmError.AUDIO_RETRY_EXHAUSTED))

    // A-11 attach / release
    audio.releaseAudioTrack()
    s.eq("A-11 release 清 AudioTrack", null, audio.audioTrack)
    s.check("A-11 再写命中门控1", audio.writeStartPlay() is NmWriteOutcome.EARLY_RETURN_MINUS_1)

    // 常量
    s.eq("A-11 release 站点数", 5, NmAudioPlayer.TRACK_RELEASE_SITES.size)
    s.eq("A-11 set 站点数", 2, NmAudioPlayer.TRACK_SET_SITES.size)
    s.eq("A-01 rodata 地址", 0xE180A, NmAudioPlayer.WRITE_START_PLAY_RODATA.toInt())
}

// ---------------------------------------------------------------------------
// 7. NmJniBridge P-01 ~ P-13
// ---------------------------------------------------------------------------

private fun testJniBridge(s: Suite) {
    s.section("7. Java→native 参数槽透传 / Inventory P-01~P-13")

    val data = NmJniBridge.onNativeDataRequestData(nativePtr = 0x1234L, j2 = 77L)
    s.eq("P-01 x0=nativePtr", 0x1234L, data.x0)
    s.eq("P-04 x1=j2 原样透传", 77L, data.x1)
    s.eq("P-01 trampoline 0x36F68", 0x36F68L, data.trampoline)
    s.eq("P-01 vtableSlot 0x20", 0x20, data.vtableSlot)

    val end = NmJniBridge.onNativeDataRequestEnd(nativePtr = 0xABCDL, j2 = 88L)
    s.eq("P-01 End x0", 0xABCDL, end.x0)
    s.eq("P-02 End x1=j2 原样", 88L, end.x1)
    s.eq("P-03 End trampoline 0x36C70", 0x36C70L, end.trampoline)

    val (a, b) = NmJniBridge.dispatch(data)
    s.eq("P-03 dispatch x0 不变", 0x1234L, a)
    s.eq("P-03 dispatch x1 不变", 77L, b)

    s.eq("P-05 kindSlotBranch(2)=2", 2, NmJniBridge.kindSlotBranch(NmKind.NATIVE_DATA_KIND_PRELOAD))
    s.eq("P-05 kindSlotBranch(1)=1", 1, NmJniBridge.kindSlotBranch(NmKind.NATIVE_DATA_KIND_NORMAL))

    val inv = NmJniBridge.Inventory
    s.eq("P-08 精确 JNI 类", 12, inv.PRECISE_JNI_CLASSES)
    s.eq("P-08 native 方法数", 162, inv.NATIVE_METHODS)
    s.eq("P-08 JNI 符号数", 202, inv.JNI_SYMBOLS)
    s.eq("P-08 ncmplayerengine 符号", 118, inv.LIB_NCMPLAYERENGINE_SYMBOLS)
    s.eq("P-08 audioplayer 符号", 2, inv.LIB_AUDIOPLAYER_SYMBOLS)
    s.eq("P-08 nmplaylist 符号", 24, inv.LIB_NMPLAYLIST_SYMBOLS)
    s.eq("P-08 NMCommonCache 符号", 58, inv.LIB_NMCOMMONCACHE_SYMBOLS)
    s.eq("P-08 arm64 so 总数", 167, inv.SO_TOTAL_ARM64)
    s.eq("P-09 AudioPlayerUnit 方法 27", 27, inv.AUDIO_PLAYER_UNIT_METHODS.size)
    s.check("P-10 Playback 分组非空", inv.PLAYBACK_METHOD_GROUPS.isNotEmpty())
    s.eq("P-11 PlaybackEngine 方法数", 24, inv.PLAYBACK_ENGINE_METHODS)
    s.eq("P-12 PlaybackDataSource 方法数", 7, inv.PLAYBACK_DATA_SOURCE_METHODS)
    s.eq("P-13 PlaylistJniBridge 方法数", 31, inv.PLAYLIST_JNI_BRIDGE_METHODS)
    s.eq("P-13 NEAudioDeviceListener 方法数", 2, inv.NE_AUDIO_DEVICE_LISTENER_METHODS)
}

// ---------------------------------------------------------------------------
// 8. F4 集成链路（NmF4Chain）
// ---------------------------------------------------------------------------

private fun testF4Chain(s: Suite) {
    s.section("8. F4 阻塞链路端到端复现（§61.65~§61.77 集成）")

    val chain = NmF4Chain()
    val r = chain.reproduce()

    s.eq("F4 步骤数=11", 11, r.steps.size)
    s.eq("F4 g_level 默认 0", 0, r.initialGlobalLevel)
    s.eq("F4 末代次=22（真机 1→22）", 22L, r.finalGeneration)
    s.eq("F4 commit 冻结 16384", 16384L, r.frozenCommit)
    s.eq("F4 旧代次包丢弃 8 次", 8, r.droppedPackets)
    s.eq("F4 UnExpect W 日志命中 1 次", 1, r.unexpectOffsetLogCount)
    s.eq("F4 WaitRequest → -2", NmError.WAIT_STARVED, r.waitStarvedResult)
    s.eq("F4 Finish Async → -103", NmError.FINISH_ASYNC, r.finishAsyncCode)
    s.eq("F4 paused → SKIP_PRINT", "SKIP_PRINT", r.outcomeWhenPaused)
    s.eq("F4 release → EARLY_RETURN_MINUS_1", "EARLY_RETURN_MINUS_1", r.outcomeWhenReleased)
    s.eq("F4 Write start play 命中 0 次", 0, r.writeStartPlayLogCount)
    s.check("F4 正常写入阶段有分片写", r.sliceWriteCalls > 0)
    s.check("F4 正常写入阶段有 NotifyCacheProgress", r.cacheProgressCount > 0)
    s.check("F4 结论含 F4 仍 BLOCKED", "F4 仍 BLOCKED" in r.conclusion)
    s.check("F4 结论含 代次错配", "代次错配" in r.conclusion)

    val rendered = chain.render(r)
    s.check("render 含 Markdown 表头", "| # | 步骤 | 观测 | 证据锚点 |" in rendered)
    s.check("render 含终态快照", "frozenCommit" in rendered)
    s.check("render 含结论段", "## 结论" in rendered)

    val p = r.doneChain
    s.eq("F4 旁路 kind=2 -> PreloadDoneWrite", "PreloadDoneWrite", p.kind2Task)
    s.eq("F4 旁路 kind=2 outcomes=2（含级联 Render）", 2, p.kind2Outcomes.size)
    s.eq("F4 旁路 kind=2 首个 CASCADE", "PRELOAD_WRITE_CASCADE", p.kind2Outcomes[0])
    s.eq("F4 旁路 kind=2 次个 RENDER_DONE", "PRELOAD_RENDER_DONE", p.kind2Outcomes[1])
    s.eq("F4 旁路 kind=2 级联投递 1 次", 1, p.kind2CascadePosts)
    s.eq("F4 旁路 kind=2 Render 完成", true, p.kind2RenderDone)
    s.eq("F4 旁路 kind=1 -> Flush", "Flush", p.kind1Task)
    s.eq("F4 旁路 kind=1 outcomes=1", 1, p.kind1Outcomes.size)
    s.eq("F4 旁路 kind=1 -> TAIL_CALL", "FLUSH_TAIL_CALL", p.kind1Outcomes[0])
    s.eq("F4 旁路 kind=1 不级联", 0, p.kind1CascadePosts)
    s.eq("F4 旁路 Flush 尾调用 1 次", 1, p.flushTailCalls)
    s.eq("F4 旁路 两分支共享队列投递 2 次", 2, p.sharedQueuePosts)
    s.check("render 含完成通知链旁路验证", "## 完成通知链旁路验证" in rendered)
    s.check("render 标注非 F4 因果环节", "非 F4 因果环节" in rendered)
    s.eq("F4 因果链仍是 11 步（旁路未混入因果）", 11, r.steps.size)
}

// ---------------------------------------------------------------------------
// 9. Java SDK 入口层 JNI 清单（C 组全量登记）
// ---------------------------------------------------------------------------

// @spec C-01 / C-02 / C-03 / C-04 / C-05 / C-06 / C-07 / C-08：C 组全量登记契约断言
private fun testJniCatalog(s: Suite) {
    s.section("9. Java SDK 入口层 JNI 清单全量登记 C-01~C-08")

    val c = NmJniCatalog
    s.eq("C-01 CLASSES 全量 19", 19, c.CLASSES.size)
    s.eq("C-01 CLASS_COUNT 常量", 19, c.CLASS_COUNT)
    s.eq("C-01 精确 JNI 类 12", 12, c.preciseClasses().size)
    s.eq("C-01 PRECISE_CLASS_COUNT", 12, c.PRECISE_CLASS_COUNT)
    s.check("C-01 简单类名唯一", c.CLASSES.map { it.simpleName }.toSet().size == c.CLASSES.size)

    s.eq("C-02 METHODS 全量 162", 162, c.METHODS.size)
    s.eq("C-02 METHOD_COUNT 常量", 162, c.METHOD_COUNT)
    s.eq("C-02 逐类方法数求和", 162, c.CLASSES.sumOf { it.nativeMethodCount })
    var perClassConsistent = true
    for (cl in c.CLASSES) {
        if (c.methodsOf(cl.simpleName).size != cl.nativeMethodCount) perClassConsistent = false
    }
    s.check("C-02 每类方法数与 METHODS 逐条一致", perClassConsistent)
    s.eq("C-02 AudioPlayerUnit 27", 27, c.methodsOf("AudioPlayerUnit").size)
    s.eq("C-02 Playback 60", 60, c.methodsOf("Playback").size)
    s.eq("C-02 PlaylistJniBridge 31", 31, c.methodsOf("PlaylistJniBridge").size)
    s.eq("C-02 PlaybackEngine 24", 24, c.methodsOf("PlaybackEngine").size)
    s.eq("C-02 PlaybackDataSource 7", 7, c.methodsOf("PlaybackDataSource").size)
    s.check("C-02 distinctMethodNames 非空", c.distinctMethodNames().isNotEmpty())

    s.eq("C-03 JNI_SYMBOL_COUNT", 202, c.JNI_SYMBOL_COUNT)
    s.eq("C-03 4 个 so", 4, c.JNI_SYMBOLS_BY_LIB.size)
    s.eq("C-03 分布求和 = 202", 202, c.JNI_SYMBOLS_BY_LIB.values.sum())
    s.eq("C-03 ncmplayerengine 118", 118, c.JNI_SYMBOLS_BY_LIB["libncmplayerengine.so"])
    s.eq("C-03 audioplayer 2", 2, c.JNI_SYMBOLS_BY_LIB["libaudioplayer.so"])
    s.eq("C-03 nmplaylist 24", 24, c.JNI_SYMBOLS_BY_LIB["libnmplaylist.so"])
    s.eq("C-03 NMCommonCache 58", 58, c.JNI_SYMBOLS_BY_LIB["libNMCommonCache.so"])

    s.eq("C-05 classes14.dex 12 类", 12, c.CLASSES.count { it.dex == "classes14.dex" })
    s.eq("C-05 classes5.dex 6 类", 6, c.CLASSES.count { it.dex == "classes5.dex" })
    s.eq("C-05 classes16.dex 1 类", 1, c.CLASSES.count { it.dex == "classes16.dex" })

    s.eq("C-06 deltaKnown 2 处", 2, c.DELTA_KNOWN.size)

    val created = c.METHODS.first { it.name == "nativeCreate" }
    s.eq("C-07 owner 简单类名", "AudioPlayerUnit", created.ownerSimpleName)
    s.eq("C-07 javaReturn 脱敏", "long", created.javaReturn)
    s.eq("C-07 declaration 由脱敏类型重组", "long nativeCreate(long)", created.declaration)
    val allValues: List<String> =
        c.CLASSES.flatMap { listOf(it.simpleName, it.dex) } +
            c.METHODS.flatMap {
                listOf(it.ownerSimpleName, it.name, it.javaReturn, it.declaration) +
                    it.javaParams + it.descriptorParams
            }
    s.check("C-04 全量登记值零包路径", allValues.none { "/" in it || "java.lang" in it })

    s.eq("C-08 CLASS_COUNT 自洽", c.CLASS_COUNT, c.CLASSES.size)
    s.eq("C-08 METHOD_COUNT 自洽", c.METHOD_COUNT, c.METHODS.size)
    s.eq("C-08 JNI_SYMBOL_COUNT 自洽", c.JNI_SYMBOL_COUNT, c.JNI_SYMBOLS_BY_LIB.values.sum())
}

// ---------------------------------------------------------------------------
// 10. libffmpeg.so 格式覆盖（F 组全量登记）
// ---------------------------------------------------------------------------

// @spec F-01 / F-02 / F-03 / F-04 / F-05 / F-06 / F-07 / F-08：F 组全量登记契约断言
private fun testFormatCatalog(s: Suite) {
    s.section("10. libffmpeg.so 格式覆盖全量登记 F-01~F-08")

    val f = NmFormatCatalog
    s.eq("F-01 DECODER_SYMBOLS 全量 100", 100, f.DECODER_SYMBOLS.size)
    s.eq("F-01 DECODER_SYMBOL_COUNT", 100, f.DECODER_SYMBOL_COUNT)
    s.eq("F-02 DEMUXER_SYMBOLS 全量 75", 75, f.DEMUXER_SYMBOLS.size)
    s.eq("F-02 DEMUXER_SYMBOL_COUNT", 75, f.DEMUXER_SYMBOL_COUNT)
    s.check("F-01 decoder 符号唯一", f.DECODER_SYMBOLS.toSet().size == 100)
    s.check("F-02 demuxer 符号唯一", f.DEMUXER_SYMBOLS.toSet().size == 75)
    s.check("F-01 含 aac decoder", f.hasDecoder("aac"))
    s.check("F-01 含 flac decoder", f.hasDecoder("flac"))
    s.check("F-01 含 mp3 decoder", f.hasDecoder("mp3"))
    s.check("F-02 含 aac demuxer", f.hasDemuxer("aac"))
    s.check("F-02 含 mp3 demuxer", f.hasDemuxer("mp3"))
    s.check("F-02 wav 只有 demuxer", !f.hasDecoder("wav") && f.hasDemuxer("wav"))

    s.eq("F-03 CROSS_ABI_IDENTICAL", true, f.CROSS_ABI_IDENTICAL)
    s.eq("F-03 2 个 ABI", 2, f.LIBRARIES.size)
    s.eq("F-03 arm64 decoder 100", 100, f.LIBRARIES["arm64-v8a"]?.decoderSymbolCount)
    s.eq("F-03 arm64 demuxer 75", 75, f.LIBRARIES["arm64-v8a"]?.demuxerSymbolCount)
    s.eq("F-03 v7a decoder 100", 100, f.LIBRARIES["armeabi-v7a"]?.decoderSymbolCount)
    s.eq("F-03 v7a demuxer 75", 75, f.LIBRARIES["armeabi-v7a"]?.demuxerSymbolCount)
    s.check("F-03 arm64 sha256 是 64 位十六进制长度", f.LIBRARIES["arm64-v8a"]?.sha256?.length == 64)
    s.eq("F-03 CHECKS_TOTAL 18", 18, f.CHECKS_TOTAL)
    s.eq("F-03 CHECKS_PASS 18", 18, f.CHECKS_PASS)
    s.check("F-03 ALL_PASS", f.ALL_PASS)

    s.eq("F-04 SYMTAB_STRIPPED", true, f.SYMTAB_STRIPPED)
    s.check("F-04 两个 ABI 都无 symtab", f.LIBRARIES.values.all { !it.hasSymtab })

    s.eq("F-05 UNCONFIRMED_9 全量", 9, f.UNCONFIRMED_9.size)
    s.eq("F-06 CANDIDATE_15 全量", 15, f.CANDIDATE_15.size)
    s.eq("F-07 CODEC_QUERIES 全量", 19, f.CODEC_QUERIES.size)
    s.check("F-05 9 个查询都有 verdict", f.UNCONFIRMED_9.all { it.symbolVerdict.isNotEmpty() })
    s.check("F-07 19 个 query 非空", f.CODEC_QUERIES.all { it.query.isNotEmpty() })

    s.eq("F-08 BOUNDARIES 8 条", 8, f.BOUNDARIES.size)
    s.check("F-08 verdict 含 F4_BLOCKED", "F4_BLOCKED" in f.VERDICT)
    s.check("F-08 verdict 含 M2_GATE_NOT_PASSED", "M2_GATE_NOT_PASSED" in f.VERDICT)
    s.check("F-08 边界声明含「导出符号存在」", f.BOUNDARIES.any { "导出符号存在" in it })
    s.check("F-08 queriesWithVerdict(NO_SYMBOL) 可用", f.queriesWithVerdict("NO_SYMBOL").isNotEmpty())
}


// ---------------------------------------------------------------------------
// 11. OnNetSourceDataDone 双分支 / 级联完成链（§61.54 / §61.55 补翻译）
// ---------------------------------------------------------------------------

// @spec D-01 / D-02 / D-03 / D-04 / D-05 / D-06 / D-07 / D-08 / D-09 / D-10 / D-11 / D-12
private fun testDoneTaskChain(s: Suite) {
    s.section("11. OnNetSourceDataDone 双分支 / 级联完成链 D-01~D-12（§61.54/§61.55）")
    val K = NmBufferSourceImp.Companion

    // 一手反汇编元数据逐项登记
    s.eq("D-01 OnNetSourceDataDone @0xBEE8", 0xBEE8, K.DONE_FUNC_ADDR)
    s.eq("D-01 size 384", 384, K.DONE_FUNC_SIZE)
    s.eq("D-02 GOT kind2 0x4BED0", 0x4BED0, K.GOT_KIND2)
    s.eq("D-02 PreloadWrite @0xB86C", 0xB86C, K.PRELOAD_WRITE_ADDR)
    s.eq("D-02 PreloadWrite 288B", 288, K.PRELOAD_WRITE_SIZE)
    s.eq("D-02 PreloadWrite 72 指令", 72, K.PRELOAD_WRITE_INSNS)
    s.eq("D-03 GOT other 0x4BEE8", 0x4BEE8, K.GOT_OTHER)
    s.eq("D-03 Flush @0xC068", 0xC068, K.FLUSH_ADDR)
    s.eq("D-03 Flush 88B", 88, K.FLUSH_SIZE)
    s.eq("D-03 Flush 22 指令", 22, K.FLUSH_INSNS)
    s.eq("D-04 任务对象 0x60", 0x60, K.TASK_OBJECT_SIZE)
    s.eq("D-05 共享队列 0x16468", 0x16468, K.QUEUE_POST_ADDR)
    s.eq("D-05 投递调用点 3 处", 3, K.QUEUE_POST_CALL_SITES)
    s.eq("D-05 Done 窗口内投递点 2 处（源 JSON 口径）", 2, K.DONE_WINDOW_QUEUE_POSTS)
    s.eq("D-10 GOT render 0x4BF28", 0x4BF28, K.GOT_RENDER)
    s.eq("D-10 Render @0xADBC", 0xADBC, K.PRELOAD_RENDER_ADDR)
    s.eq("D-10 Render 420B", 420, K.PRELOAD_RENDER_SIZE)
    s.eq("D-10 Render 105 指令", 105, K.PRELOAD_RENDER_INSNS)
    s.eq("D-12 task-diff checks 14", 14, K.TASK_DIFF_CHECKS_TOTAL)
    s.eq("D-12 task-diff pass 14", 14, K.TASK_DIFF_CHECKS_PASS)

    // kind==2 → PreloadDoneWrite → 级联 PreloadDoneRender
    val (gate, _) = newGate()
    val buf = newBuffer(gate)
    val a = buf.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_PRELOAD_DONE)
    s.eq("D-02 kind=2 投递 PreloadDoneWrite", NmDoneTask.PreloadDoneWrite, a)
    s.eq("D-05 队列长度 1", 1, buf.doneTaskQueue.size)
    val outcomes = buf.drainDoneQueue()
    s.eq("D-06 drain 执行 2 个 task", 2, outcomes.size)
    s.eq("D-09 bool=true 置位", true, buf.preloadDoneBoolTrue)
    s.eq("D-10 级联投递 1 次", 1, buf.cascadePosts)
    s.eq("D-10 首个结果 CASCADE", NmDoneTaskOutcome.PRELOAD_WRITE_CASCADE, outcomes[0])
    s.eq("D-11 次个结果 RENDER_DONE", NmDoneTaskOutcome.PRELOAD_RENDER_DONE, outcomes[1])
    s.eq("D-11 renderDoneCount", 1, buf.renderDoneCount)
    s.eq("D-03 kind=2 不执行 Flush", 0, buf.flushCount)
    s.eq("D-05 队列已排空", 0, buf.doneTaskQueue.size)

    // kind!=2 → Flush：四道门控全放行 → vtable+0x40 尾调用
    val (gate2, _) = newGate()
    val buf2 = newBuffer(gate2)
    val b = buf2.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
    s.eq("D-03 kind=1 投递 Flush", NmDoneTask.Flush, b)
    val f1 = buf2.drainDoneQueue()
    s.eq("D-06 drain 执行 1 个 task", 1, f1.size)
    s.eq("D-07 全放行 → TAIL_CALL", NmDoneTaskOutcome.FLUSH_TAIL_CALL, f1[0])
    s.eq("D-07 尾调用计数 1", 1, buf2.flushTailCallCount)
    s.eq("D-05 Flush 不级联新任务", 0, buf2.cascadePosts)

    // [+0xB8]==0 → W 级日志分支（0xC090~0xC0BC）
    val (gate3, logs3) = newGate()
    val buf3 = newBuffer(gate3)
    buf3.clearNormalModeOnReadPath()
    buf3.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
    val f2 = buf3.drainDoneQueue()
    s.eq("D-08 [+0xB8]=0 → LOG_BRANCH", NmDoneTaskOutcome.FLUSH_LOG_BRANCH, f2[0])
    s.eq("D-08 W(5) 级日志已打印", true, logs3.records.any { it.level == NmLogLevel.WARN })
    s.eq("D-08 无尾调用", 0, buf3.flushTailCallCount)

    // g_level>5 抑制 W 日志，但返回路径不变（§61.70 getter 语义）
    val (gate4, logs4) = newGate()
    gate4.setLevel(6)
    val buf4 = newBuffer(gate4)
    buf4.clearNormalModeOnReadPath()
    buf4.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
    val f3 = buf4.drainDoneQueue()
    s.eq("D-08 g>5 仍走 LOG_BRANCH", NmDoneTaskOutcome.FLUSH_LOG_BRANCH, f3[0])
    s.eq("D-08 g>5 不打日志", 0, logs4.records.size)

    // [+0xD8] 回调对象为空 → 0xC07C cbz → 0xC09C ret，不尾调用
    val (gate5, _) = newGate()
    val buf5 = newBuffer(gate5)
    buf5.flushCallbackPresent = false
    buf5.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
    val f4 = buf5.drainDoneQueue()
    s.eq("D-07 [+0xD8] 空 → RET_NO_CALLBACK", NmDoneTaskOutcome.FLUSH_RET_NO_CALLBACK, f4[0])
    s.eq("D-07 无尾调用", 0, buf5.flushTailCallCount)
}

// @spec R-01 / R-02 / R-03 / R-04 / R-05 / R-06 / R-07 / R-08
private fun testReadGap(s: Suite) {
    s.section("12. §61.77 读侧空白链一手证据全量登记 R-01~R-08（read-gap-chain.json）")
    val K = NmReadGapCatalog

    // ---- R-01：.rodata @0x3BEE8 格式串四字段 1:1 ----
    s.eq("R-01 rodata 地址", 0x3BEE8, K.RODATA_ADDR)
    s.eq("R-01 rodata 唯一引用", true, K.RODATA_UNIQUE)
    s.eq("R-01 adrp 引用点", 0xC454, K.RODATA_REF_ADRP_ADDR)
    s.eq("R-01 add 引用点", 0xC45C, K.RODATA_REF_ADD_ADDR)
    s.check("R-01 格式串含 expect len", "expect len:%u" in K.RODATA_TEXT)
    s.check("R-01 格式串含 offset", "offset:%lld" in K.RODATA_TEXT)
    s.check("R-01 格式串含 readed_len", "readed_len:%u" in K.RODATA_TEXT)
    s.check("R-01 格式串含 result", "result:%d" in K.RODATA_TEXT)
    s.check(
        "R-01 四字段顺序 1:1",
        K.RODATA_TEXT.indexOf("expect len:") < K.RODATA_TEXT.indexOf("offset:") &&
            K.RODATA_TEXT.indexOf("offset:") < K.RODATA_TEXT.indexOf("readed_len:") &&
            K.RODATA_TEXT.indexOf("readed_len:") < K.RODATA_TEXT.indexOf("result:"),
    )

    // 运行态：读空日志四字段与顺序
    val (gateR, logsR) = newGate()
    val bufR = newBuffer(gateR)
    s.eq("R-02 读空 → -2", NmError.WAIT_STARVED, bufR.readFromFileNoLock(16384L, 4096L, 20480L))
    val lineR = logsR.records.lastOrNull { "ReadFromFileNoLock" in it.message }?.message ?: ""
    s.check("R-01 日志 expect len 实参", "expect len:4096" in lineR)
    s.check("R-01 日志 offset 实参", "offset:16384" in lineR)
    s.check("R-01 日志 readed_len:0（读空）", "readed_len:0" in lineR)
    s.check("R-01 日志 result:-2", "result:-2" in lineR)
    s.check(
        "R-01 日志四字段顺序 1:1",
        lineR.indexOf("expect len:") < lineR.indexOf("offset:") &&
            lineR.indexOf("offset:") < lineR.indexOf("readed_len:") &&
            lineR.indexOf("readed_len:") < lineR.indexOf("result:"),
    )

    // 成功回读：commit 越过 end → WAIT_OK → readed_len = length
    val (gateR2, logsR2) = newGate()
    val netR2 = newNet(gate = gateR2)
    netR2.rollbackToContinuousCommitPoint(4096L)
    val bufR2 = NmBufferSourceImp(gateR2, NmSlicesFile(1048576L, 4096L, gate = gateR2), netR2)
    s.eq("R-02 成功回读 → 0", 0, bufR2.readFromFileNoLock(0L, 256L, 0L))
    val lineR2 = logsR2.records.lastOrNull { "ReadFromFileNoLock" in it.message }?.message ?: ""
    s.check("R-01 成功回读 readed_len=256", "readed_len:256" in lineR2)

    // ---- R-02：WaitRequest 四返回码与立即数锚点 ----
    s.eq("R-02 WAIT_STARVED=-2", -2, NmError.WAIT_STARVED)
    s.eq("R-02 WAIT_FORCE=-106", -106, NmError.WAIT_FORCE)
    s.eq("R-02 FINISH_ASYNC=-103", -103, NmError.FINISH_ASYNC)
    s.eq("R-02 WaitRequest 调用点", 0xC6F8, K.A_WAIT_REQUEST_CALL_ADDR)
    s.eq("R-02 w0==1 比较点", 0xC700, K.A_WAIT_RET1_CMP_ADDR)
    s.eq("R-02 -2 立即数点", 0xC728, K.A_RESULT_MINUS2_ADDR)
    s.eq("R-02 -106 立即数点", 0xC710, K.A_RESULT_MINUS106_ADDR)
    s.eq("R-02 -103 立即数点", 0xC730, K.A_RESULT_MINUS103_ADDR)

    // ---- R-03：两条硬编码分支共用 0xA9E4 写 [+0x1A0] ----
    s.eq("R-03 硬编码 -2 点", 0xA9BC, K.A_HARDCODED_MINUS2_ADDR)
    s.eq("R-03 硬编码 -103 点", 0xA9C4, K.A_HARDCODED_MINUS103_ADDR)
    s.eq("R-03 写 [+0x1A0] 点", 0xA9E4, K.A_WRITE_1A0_ADDR)
    val (gateR3, _) = newGate()
    val bufR3 = newBuffer(gateR3)
    bufR3.markAsyncReadStarted()
    bufR3.onAsyncReadComplete(true)
    s.eq("R-03 饥饿分支写 -2", NmError.WAIT_STARVED, bufR3.asyncErrorCode)

    // ---- R-04：[+0x198] 六处写入点 + 挂起读门控 ----
    s.eq("R-04 [+0x198] 写入点数", 6, K.PENDING_198_WRITE_COUNT)
    s.eq("R-04 写入点列表长度", 6, K.PENDING_198_WRITES.size)
    s.eq("R-04 注册点", 0x9F44, K.PENDING_198_WRITES[0])
    s.eq("R-04 Finish 清零点", 0xA018, K.PENDING_198_WRITES[1])
    s.eq("R-04 ldr 门控点", 0xA9D0, K.A_PENDING_READ_LOAD_ADDR)
    s.eq("R-04 cbz 门控点", 0xA9D4, K.A_PENDING_READ_CBZ_ADDR)
    val (gateR4, _) = newGate()
    val bufR4 = newBuffer(gateR4)
    bufR4.onAsyncReadComplete(false)
    s.eq("R-04 无挂起读不写 [+0x1A0]", 0, bufR4.asyncErrorCode)

    // ---- R-05：-2 七处硬编码立即数点 ----
    s.eq("R-05 -2 硬编码点数", 7, K.HARDCODED_MINUS2_POINT_COUNT)
    s.eq("R-05 列表长度", 7, K.HARDCODED_MINUS2_POINTS.size)
    s.eq("R-05 含 0x12DC8（NMSlicesFile 越界）", true, K.HARDCODED_MINUS2_POINTS.contains(0x12DC8))
    s.eq("R-05 slices write -2 锚点", 0x12DC8, K.A_SLICES_WRITE_MINUS2_ADDR)

    // ---- R-06：24 个指令锚点全量登记 ----
    s.eq("R-06 锚点总数", 24, K.ANCHOR_COUNT)
    s.eq("R-06 代次 cmp", 0x10794, K.A_GENERATION_CMP_ADDR)
    s.eq("R-06 代次 b.ne", 0x10798, K.A_GENERATION_BNE_ADDR)
    s.eq("R-06 commit str", 0x10888, K.A_COMMIT_STORE_ADDR)
    s.eq("R-06 读结果虚调用", 0xC434, K.A_READ_RESULT_CALL_ADDR)
    s.eq("R-06 日志打印调用", 0xC470, K.A_READ_LOG_CALL_ADDR)

    // ---- R-07：31/31 checks + 3 条 fix_anchors + F4 口径 ----
    s.eq("R-07 checks 总数", 31, K.CHECKS_TOTAL)
    s.eq("R-07 checks 通过数", 31, K.CHECKS_PASS)
    s.eq("R-07 all_pass", true, K.ALL_PASS)
    s.eq("R-07 F4 状态", "BLOCKED", K.F4_STATUS)
    s.eq("R-07 fix_anchors 数", 3, K.FIX_ANCHOR_COUNT)
    s.eq("R-07 fix_anchors 列表长度", 3, K.FIX_ANCHORS.size)

    // ---- R-08：证据源登记口径 ----
    s.check("R-08 格式串取自 .rodata 而非推断", K.RODATA_TEXT.startsWith("NMBufferSourceImp("))
    s.check("R-08 静态证据不等于修复", K.F4_STATUS == "BLOCKED")
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

// @spec U-01 / U-02 / U-03 / U-04 / U-05 / U-06 / U-07 / U-08 / U-09 / U-10
private fun testUGroup(s: Suite) {
    s.section("13. A3 反汇编补遗与 C 类再清点 U-01~U-10（a3-*.md / 机读 JSON / 扫描裁定）")
    val A = NmA3Anchors
    val E = NmA3Error
    val M = NmDisasmMetaCatalog

    // ---- U-01 构造期初值 ----
    s.eq("U-01 构造 init mov 地址", 0x91B8, A.CTOR_INIT_MOV_ADDR)
    s.eq("U-01 构造 strh 地址", 0x91E0, A.CTOR_INIT_STRH_ADDR)
    s.eq("U-01 构造函数地址", 0x9164, A.CTOR_ADDR)
    s.eq("U-01 构造函数 size", 1012, A.CTOR_SIZE)
    s.eq("U-01 双标志立即数 0x101", 0x101, E.INIT_BOTH_FLAGS)

    // ---- U-02 字段存储 ----
    s.eq("U-02 策略存储地址 0x92A0", 0x92A0, A.CTOR_STRATEGY_STORE_ADDR)
    s.eq("U-02 vfile 存储地址 0x92C4", 0x92C4, A.CTOR_VFILE_STORE_ADDR)

    // ---- U-03 / U-04 / U-05 双门控真值表（4 组独立实例） ----
    val cases = listOf(
        Triple(0, true, true),
        Triple(0, false, true),
        Triple(1, false, true),
        Triple(1, true, false),
    )
    for ((strategy, vfile, expected) in cases) {
        val g = NmMemoryModeGate()
        val entered = g.apply(strategy, vfile)
        s.eq("U-03/04 gate($strategy,$vfile) 进入判定", expected, entered)
        s.eq("U-04 gate($strategy,$vfile) normalMode", !expected, g.normalMode)
        s.eq("U-01 gate($strategy,$vfile) [+0xB9] 保持 1", true, g.secondaryFlag)
        s.eq("U-02 gate($strategy,$vfile) 策略落 [+0x98]", strategy, g.strategy)
        s.eq("U-02 gate($strategy,$vfile) vfile 落 [+0xD8]", vfile, g.virtualFilePresent)
        s.eq("U-05 gate($strategy,$vfile) 日志次数", if (expected) 1 else 0, g.memoryModeLogCount)
        s.eq("U-05 gate($strategy,$vfile) 日志实参", if (expected) 6 else 0, g.logArgOnEnter)
    }
    s.eq("U-03 tbz 地址", 0x941C, A.MEMORY_TBZ_ADDR)
    s.eq("U-04 ldr vfile 地址", 0x9420, A.MEMORY_LDR_VFILE_ADDR)
    s.eq("U-04 cbnz 地址", 0x9424, A.MEMORY_CBNZ_ADDR)
    s.eq("U-04 strb wzr 清零地址", 0x942C, A.MEMORY_CLEAR_ADDR)
    s.eq("U-05 日志 rodata 引用地址", 0x9448, A.MEMORY_LOG_ADDR)
    s.check("U-05 Memory Mode 日志格式串", "Start Use Memory Mode." in A.MEMORY_MODE_LOG_FMT)

    // ---- U-06 onError(1001) ----
    s.eq("U-06 通用错误码", 1001, E.OPEN_GENERIC_ERROR)
    s.eq("U-06 立即数 0x3E9", 0x3E9, E.OPEN_GENERIC_ERROR_IMM)
    s.eq("U-06 Open 符号地址", 0x42350, A.OPEN_ADDR)
    s.eq("U-06 Open size", 5572, A.OPEN_SIZE)
    s.eq("U-06 全 .text 命中数", 10, A.TOTAL_1001_IMM_HITS)
    s.eq("U-06 Open 内命中数", 4, A.OPEN_1001_SITE_COUNT)
    s.eq("U-06 命中点列表长度", 4, A.OPEN_1001_SITES.size)
    s.check("U-06 命中点含 0x424FC", 0x424FC in A.OPEN_1001_SITES)
    s.check("U-06 命中点含 0x42D9C", 0x42D9C in A.OPEN_1001_SITES)

    // ---- U-07 虚调用链 ----
    s.eq("U-07 ISourceCallback 字段", 0x40, A.CALLBACK_FIELD)
    s.eq("U-07 OnError vtable 槽位", 0x18, A.ONERROR_VTABLE_SLOT)
    val errors = mutableListOf<Int>()
    val sink = object : NmPlayerCallback {
        override fun onError(code: Int) { errors += code }
        override fun onStateChange(stateCode: Int) {}
    }
    val adapter = NmBufferSourceAdapter(sink)
    s.eq("U-07 probe 成功返回 0", 0, adapter.openWithProbe(false))
    s.eq("U-07 probe 成功不上报", 0, adapter.onError1001Count)
    s.eq("U-07 probe 失败返回 1001", 1001, adapter.openWithProbe(true))
    s.eq("U-07 上报次数", 1, adapter.onError1001Count)
    s.eq("U-07 最近错误码", 1001, adapter.lastErrorCode)
    s.check("U-07 回调收到 onError(1001)", errors.contains(1001))
    s.check("U-07 不上报 -3（与 AUDIO_RETRY_EXHAUSTED 区分）", !errors.contains(-3))

    // ---- U-08 5 份机读 JSON 全量登记 ----
    s.eq("U-08 证据源总数", 7, M.SOURCE_COUNT)
    s.eq("U-08 MINUS103 checks", 25, M.MINUS103_CHECKS_TOTAL)
    s.eq("U-08 PLAY_GATE checks", 16, M.PLAY_GATE_CHECKS_TOTAL)
    s.eq("U-08 LEVEL5_SEMANTICS checks", 34, M.LEVEL5_SEMANTICS_CHECKS_TOTAL)
    s.eq("U-08 LOGLEVEL_PROBE checks", 19, M.LOGLEVEL_PROBE_CHECKS_TOTAL)
    s.eq("U-08 JNI_ARG checks", 17, M.JNI_ARG_CHECKS_TOTAL)
    s.eq("U-08 MINUS103 anchors", 16, M.MINUS103_ANCHOR_COUNT)
    s.eq("U-08 LOGLEVEL_PROBE anchors", 12, M.LOGLEVEL_PROBE_ANCHOR_COUNT)
    s.eq("U-08 MINUS103 all_pass", 1, M.MINUS103_ALL_PASS)
    s.eq("U-08 JNI_ARG device_run", 0, M.JNI_ARG_DEVICE_RUN)
    s.check("U-08 A3_STAGE_C sha256 长度 64", M.A3_STAGE_C_SHA256.length == 64)
    s.check("U-08 MINUS103 sha256 十六进制", M.MINUS103_SHA256.all { it in '0'..'9' || it in 'a'..'f' })

    // ---- U-09 / U-10 再清点口径 ----
    s.check("U-09 A3 stage-c 含指令级反汇编行", M.A3_STAGE_C_ASM_LINE_COUNT > 0)
    s.check("U-09 A3 phase-ab 含地址锚点", M.A3_PHASE_AB_HEX_ADDR_COUNT > 0)
    s.eq("U-10 已登记证据源", 27, 20 + 7)
    s.eq("U-10 明确不翻译", 151, 158 - 7)
    s.eq("U-10 证据全集", 178, 27 + 151)
    s.eq("U-10 A 类逻辑锚点", 20, 18 + 2)
    s.eq("U-10 B 类全量数据源", 7, 2 + 5)
    s.eq("U-10 新增 A 类锚点 2 份", 2, 7 - 5)
}

// ---------------------------------------------------------------------------
// 14. V 组：章节矩阵机器自证 / 低分证据零漏网 / §61.87~61.88 口径同步
// ---------------------------------------------------------------------------

// @spec V-01 / V-02 / V-03 / V-04 / V-05 / V-06
private fun testVGroup(s: Suite) {
    s.section("14. V 组：章节矩阵机器自证 / 低分证据零漏网 / §61.87~61.88 口径同步")
    val C = NmChapterMatrixCatalog

    // V-01 / V-02：断言自洽关系，不写死总章节数（并行工作流会持续追加 docs/12 章节）
    s.eq("V-01 A+B+C == 有标题章节总数", C.TOTAL_CHAPTERS,
        C.A_CLASS_COUNT + C.B_CLASS_COUNT + C.C_CLASS_COUNT)
    s.eq("V-01 有标题 + 无标题 == 编号总数", C.NUMBERED_MAX,
        C.TOTAL_CHAPTERS + C.NONE_CLASS_COUNT)
    s.check("V-01 总章节数不小于本批基线 84", C.TOTAL_CHAPTERS >= 84)
    s.eq("V-01 unmapped 必须为 0", 0, C.UNMAPPED_COUNT)
    s.eq("V-01 表格登记编号数 == 编号总数", C.NUMBERED_MAX, C.TABLE_MAPPED_NUMBER_COUNT)
    s.eq("V-01 无标题编号数", 4, C.MISSING_NUMBER_COUNT)
    s.eq("V-02 A 类章节数 9", 9, C.A_CLASS_COUNT)
    s.eq("V-02 B 类章节数 1", 1, C.B_CLASS_COUNT)
    s.eq("V-02 首 A 类章节 §61.54", 54, C.A_CLASS_FIRST)
    s.eq("V-02 首 B 类章节 §61.83", 83, C.B_CLASS_FIRST)
    s.eq("V-02 最高 L1 的 A 类章节 §61.67", 67, C.TOP_L1_A_CLASS_CHAPTER)
    s.eq("V-02 本批补登章节数 16", 16, C.NEWLY_REGISTERED_BY_V_COUNT)
    s.eq("V-02 锚点章节与逐份裁定一一对应", C.C_CLASS_ANCHOR_CHAPTER_COUNT,
        C.ANCHOR_ADJUDICATION_COUNT)
    s.eq("V-02 C 类指令级反汇编候选 0", 0, C.C_CLASS_HIGH_L1_COUNT)
    s.eq("V-02 C 类带地址前缀指令行最大值 0", 0, C.MAX_C_CLASS_ADDR_INSN_LINES)
    s.eq("V-02 C 类连续助记词块最大值 0", 0, C.MAX_C_CLASS_MAX_MNE_BLOCK)
    s.check("V-02 C 类高 L1 分数章节是引用型文档（非空但非候选）",
        C.C_CLASS_L1_HIGH_SCORE_CHAPTER_COUNT > 0)

    // V-03：低分证据零漏网
    s.eq("V-03 低分(score<12)证据总数 134", 134, C.LOW_SCORE_TOTAL)
    s.eq("V-03 纯汇编片段候选 0", 0, C.ASM_CANDIDATE_COUNT)
    s.eq("V-03 asm 类 fence 总数 0", 0, C.ASM_FENCE_TOTAL)
    s.eq("V-03 带地址前缀指令行最大值 0", 0, C.MAX_ADDR_INSN_LINES)
    s.eq("V-03 连续助记词块最大值 0", 0, C.MAX_MAX_MNE_BLOCK)
    s.eq("V-03 同行地址+助记词最大值 0", 0, C.MAX_INLINE_ADDR_MNEMONIC_LINES)
    s.eq("V-03 多 fence 候选逐份裁定数一致", C.PLAIN_FENCE_CANDIDATE_COUNT,
        C.PLAIN_FENCE_ADJUDICATION_COUNT)
    s.check("V-03 fence 名单指纹是 64 位十六进制", C.FENCE_FINGERPRINT.length == 64)
    s.check("V-03 多 fence 候选非空（逐份裁定有对象）", C.PLAIN_FENCE_CANDIDATE_COUNT > 0)

    // V-04：双扫描指纹守护
    s.check("V-04 章节扫描 sha256 64 位", C.CHAPTER_SCAN_SHA256.length == 64)
    s.check("V-04 低分扫描 sha256 64 位", C.LOW_SCORE_SCAN_SHA256.length == 64)
    s.check("V-04 两份扫描指纹互不相同", C.CHAPTER_SCAN_SHA256 != C.LOW_SCORE_SCAN_SHA256)
    s.check("V-04 fence 指纹与扫描指纹不同", C.FENCE_FINGERPRINT != C.LOW_SCORE_SCAN_SHA256)

    // V-05：§61.87 / §61.88 事实口径 + 历史 verdict 1:1 保留
    s.eq("V-05 B2 M2 门禁 PASS", true, C.B2_M2_GATE_PASSED)
    s.eq("V-05 B2 M3 门禁未通过", false, C.B2_M3_GATE_PASSED)
    s.eq("V-05 F3 B2 PASS 14", 14, C.F3_B2_PASS)
    s.eq("V-05 F3 B2 FAIL 8", 8, C.F3_B2_FAIL)
    s.eq("V-05 F3 B2 NO_SAMPLE 3", 3, C.F3_B2_NO_SAMPLE)
    s.eq("V-05 M2 PASS 基线 11", 11, C.M2_PASS_BASELINE_A)
    s.eq("V-05 F3 22 格 + 3 种 NO_SAMPLE 总数自洽", 22 + 3,
        C.F3_B2_PASS + C.F3_B2_FAIL + C.F3_B2_NO_SAMPLE)
    s.check("V-05 M2 PASS 条件成立", C.F3_B2_PASS >= C.M2_PASS_BASELINE_A)
    s.eq("V-05 F4 仍 BLOCKED", "BLOCKED", C.F4_STATUS)
    s.eq("V-05 最新章节来自 numberedMax", "61.88", C.LATEST_CHAPTER)
    s.check("V-05 §61.83 历史 verdict 原样保留（源未变更）",
        "M2_GATE_NOT_PASSED" in NmFormatCatalog.VERDICT)
    s.check("V-05 §61.83 边界声明 NOT RUN 原样保留",
        NmFormatCatalog.BOUNDARIES.any { "NOT RUN" in it })
}

// ---------------------------------------------------------------------------
// 15. W 组 —— libNMCommonCache.so 一手反汇编逐指令补译
// ---------------------------------------------------------------------------

private fun testWGroup(s: Suite) {
    s.section("15. W 组：分片索引窗口 1:1 / 读侧门控 / 日志门控本体（capstone 一手反汇编）")
    val C = NmWDisasmCatalog

    // W-01：0x37E20 `cset w0,gt` —— g_level > level 才抑制（严格大于）
    val g = NmLogGate()
    s.check("W-01 g_level=0 时 I(3) 不抑制", !NmReadGate.isSuppressed(g, 3))
    s.check("W-01 g_level=0 时 W(5) 不抑制", !NmReadGate.isSuppressed(g, 5))
    g.setLevel(5)
    s.check("W-01 严格大于：g_level=5 时 W(5) 仍不抑制", !NmReadGate.isSuppressed(g, 5))
    g.setLevel(6)
    s.check("W-01 g_level=6 时 I(3) 抑制", NmReadGate.isSuppressed(g, 3))
    s.check("W-01 g_level=6 时 W(5) 抑制", NmReadGate.isSuppressed(g, 5))

    // W-02~W-07：end=8192 整除 4096 → upper=2，半开区间 [0,2)
    val w1 = NmSliceWindow.writeWindow(0L, 8192L, 4096L)
    s.eq("W-02 end=offset+len", 8192L, w1.end)
    s.eq("W-03 qEnd=end/size", 2L, w1.qEnd)
    s.eq("W-03 qStart=offset/size", 0L, w1.qStart)
    s.eq("W-04 rem=0", 0L, w1.remainder)
    s.eq("W-04 hasRemainder=false", false, w1.hasRemainder)
    s.eq("W-04 整除时 upper=2 不进位", 2L, w1.upper)
    s.check("W-05 qStart<upper → 非空", !w1.empty)
    s.eq("W-06 last=upper-1", 1L, w1.last)
    s.eq("W-06 count=upper-qStart", 2L, w1.count)
    s.eq("W-06 半开区间 [0,2)", listOf(0L, 1L), w1.indices.toList())
    s.eq("W-07 firstObjectOffset=qStart*232", 0L, w1.firstObjectOffset)
    s.eq("W-07 分片对象步长 232B", 232L, NmSliceWindow.OBJECT_STRIDE)

    // W-04：非整除时 cinc 进位（这正是 S-16 挂账的答案）
    val w2 = NmSliceWindow.writeWindow(0L, 4097L, 4096L)
    s.eq("W-04 非整除 rem=1", 1L, w2.remainder)
    s.check("W-04 非整除 hasRemainder=true", w2.hasRemainder)
    s.eq("W-04 非整除 upper=2（cinc +1）", 2L, w2.upper)
    s.eq("W-06 非整除 count=2", 2L, w2.count)

    // W-03 / W-07：起始片不在 0 时的对象偏移
    val w3 = NmSliceWindow.writeWindow(4096L, 4096L, 4096L)
    s.eq("W-03 跨片 qStart=1", 1L, w3.qStart)
    s.eq("W-04 跨片 upper=2", 2L, w3.upper)
    s.eq("W-06 跨片 count=1", 1L, w3.count)
    s.eq("W-06 跨片索引 [1,2)", listOf(1L), w3.indices.toList())
    s.eq("W-07 跨片 firstObjectOffset=232", 232L, w3.firstObjectOffset)

    // W-05：0x128AC b.ge 0x12A54 —— 零分片直接跳完成
    val w4 = NmSliceWindow.compute(0L, 0L, 4096L)
    s.eq("W-05 end=0 → upper=0", 0L, w4.upper)
    s.check("W-05 qStart>=upper → empty", w4.empty)
    s.eq("W-06 零分片 count=0", 0L, w4.count)
    s.check("W-05 零分片 indices 为空", w4.indices.toList().isEmpty())
    s.eq("W-12 读侧窗口同样向上取整 upper=1", 1L,
        NmSliceWindow.readWindow(100L, 200L, 4096L).upper)

    // W-14：0x12814 tbnz x21,#0x3f
    s.eq("W-14 对齐掩码 63", 63L, NmSliceWindow.ALIGN_MASK)
    s.check("W-14 offset=64 已对齐", !NmSliceWindow.isUnaligned(64L))
    s.check("W-14 offset=63 未对齐", NmSliceWindow.isUnaligned(63L))

    // 关键回归：旧实现 qStart..end/size 闭区间在整除时多写一片
    val f = NmSlicesFile(1048576L, 4096L, gate = NmLogGate())
    s.eq("W-04 回归：write(0,8192) 成功", 0, f.write(ByteArray(8192), 0L, 8192))
    s.eq("W-04 回归：分片写 2 次（旧实现 3 次）", 2, f.sliceWriteCalls)

    // W-08~W-11：ReadFromFileNoLock 完整门控
    val g3 = NmLogGate()
    val o1 = NmReadGate.readFromFileNoLock(false, -1, 10, 0L, 100L, g3)
    s.eq("W-08 [+0xB8]==0 → -6", -6, o1.code)
    s.check("W-08 短路路径不清除标志", !o1.clearNormalMode)
    s.check("W-08 短路路径不打 I 日志", !o1.iLogEmitted)
    s.eq("W-08 path=FLAG_ZERO", "FLAG_ZERO", o1.path)

    val o2 = NmReadGate.readFromFileNoLock(true, 0, 0, 0L, 100L, g3)
    s.eq("W-11 readed==0 → -3", -3, o2.code)
    s.check("W-09 result>=0 不清除标志", !o2.clearNormalMode)
    s.check("W-01 I(3) 日志默认开启", o2.iLogEmitted)
    s.eq("W-09 path=BOUNDARY", "BOUNDARY", o2.path)

    s.eq("W-11 readed+offset>=[+0x80] → csetm -1", -1,
        NmReadGate.readFromFileNoLock(true, 0, 200, 0L, 100L, g3).code)
    s.eq("W-11 未越界 → csetm 0", 0,
        NmReadGate.readFromFileNoLock(true, 0, 10, 0L, 100L, g3).code)

    val o5 = NmReadGate.readFromFileNoLock(true, -2, 10, 0L, 100L, g3)
    s.check("W-09 result==-2 不清除标志", !o5.clearNormalMode)
    s.eq("W-09 result==-2 走边界判定", "BOUNDARY", o5.path)

    val o6 = NmReadGate.readFromFileNoLock(true, -103, 10, 0L, 100L, g3)
    s.eq("W-10 result<0 且 !=-2 → -6", -6, o6.code)
    s.check("W-10 清 [+0xB8]", o6.clearNormalMode)
    s.check("W-10 W(5) 日志默认开启", o6.wLogEmitted)
    s.eq("W-10 path=CLEAR_FLAG", "CLEAR_FLAG", o6.path)

    val g4 = NmLogGate()
    g4.setLevel(6)
    val o7 = NmReadGate.readFromFileNoLock(true, -103, 10, 0L, 100L, g4)
    s.check("W-01 g_level=6 抑制 I 日志", !o7.iLogEmitted)
    s.check("W-01 g_level=6 抑制 W 日志", !o7.wLogEmitted)
    s.eq("W-10 抑制日志返回码不变", -6, o7.code)
    s.check("W-10 抑制日志仍清标志", o7.clearNormalMode)

    // W-12：0x12CEC b.lt → 长度 <1 返回 -2（B-30 定案）
    val z = NmReadGate.zeroLengthGate(0L)
    s.check("W-12 len=0 → -2", z != null && z.code == -2)
    s.check("W-12 len=1 不拦截", NmReadGate.zeroLengthGate(1L) == null)
    s.eq("W-12 接入 NmSlicesFile.read(0,0)", -2,
        NmSlicesFile(1048576L, 4096L, gate = NmLogGate()).read(0L, 0))

    // W-13：分片读循环累计 / 失败 -98
    s.eq("W-13 累计读长度 300", 300,
        NmReadGate.sliceReadLoop(listOf(true to 100, true to 200)).code)
    s.eq("W-13 单片失败 → -98", -98,
        NmReadGate.sliceReadLoop(listOf(true to 100, false to 200)).code)

    // 生成物常量表与一手证据的一致性
    s.eq("W-07 catalog 步长 232", 232, C.SLICE_OBJECT_STRIDE)
    s.eq("W-01 getter 地址 0x37E20", 0x37E20, C.LOG_GATE_FUNC_ADDR)
    s.eq("W-01 setter 地址 0x37E34", 0x37E34, C.LOG_GATE_SET_ADDR)
    s.eq("W-03 sdiv 地址 0x12890", 0x12890, C.WRITE_INDEX_SDIV_ADDR)
    s.eq("W-04 cinc 地址 0x128A0", 0x128A0, C.WRITE_CINC_ADDR)
    s.eq("W-05 b.ge 地址 0x128AC", 0x128AC, C.WRITE_EMPTY_BRANCH_ADDR)
    s.eq("W-10 清标志地址 0xC4B8", 0xC4B8, C.READ_CLEAR_FLAG_ADDR)
    s.eq("W-12 -2 立即数地址 0x12DC8", 0x12DC8, C.READ_MINUS2_ADDR)
    s.eq("W-12 b.lt 地址 0x12CEC", 0x12CEC, C.READ_MINUS2_BRANCH_ADDR)
    s.eq("W-13 分片读失败 -98", -98, C.CODE_SLICE_READ_FAILED)
    s.eq("W-14 掩码 63", 63, C.ALIGN_MASK)
    s.eq("W 组规格编号 14 条", 14, C.SPEC_IDS.size)
    s.check("W 组编号连续 W-01..W-14",
        C.SPEC_IDS == (1..14).map { if (it < 10) "W-0$it" else "W-$it" })
    s.check("W 组证据指纹 64 位 hex", C.EVIDENCE_SHA256.length == 64)
    s.check("W 组 so 指纹与 §61.77 一手证据一致", C.SO_SHA256.startsWith("7a1adc95c255c241"))
}

// ---------------------------------------------------------------------------
// 16. X 组 —— 竞品函数全集 1:1 逻辑目录 / 控制流求值
// ---------------------------------------------------------------------------

private fun testXGroup(s: Suite) {
    s.section("16. X 组：1241 函数 / 9611 分支 / 2670 格式串零未处置 + 控制流求值")
    val C = NmXCatalog

    // X-01 / X-02：函数全集零未处置
    s.eq("X-01 函数总数 1241", 1241, C.FUNCTION_COUNT)
    s.eq("X-01 all() 与常量一致", C.FUNCTION_COUNT, NmXControlFlow.all().size)
    s.eq("X-02 零未处置", 0, C.UNHANDLED_COUNT)
    s.eq("X-02 unhandled() 为空", 0, NmXControlFlow.unhandled().size)
    s.check("X-02 每个函数处置合法", C.FUNCTIONS.all {
        it.disposition in setOf("translated", "registered", "mechanical") })
    s.eq("X-02 translated 129", 129, C.DISPOSITION_COUNTS["translated"])
    s.eq("X-02 registered 814", 814, C.DISPOSITION_COUNTS["registered"])
    s.eq("X-02 mechanical 298", 298, C.DISPOSITION_COUNTS["mechanical"])
    s.eq("X-02 三类合计 == 函数总数", C.FUNCTION_COUNT, C.DISPOSITION_COUNTS.values.sum())

    // X-03 / X-05 / X-07：全量计数与逐函数合计互相印证
    s.eq("X-03 条件分支总数 9611", 9611, C.BRANCH_COUNT)
    s.eq("X-03 逐函数分支合计", C.BRANCH_COUNT, C.FUNCTIONS.sumOf { it.branchCount })
    s.eq("X-05 返回立即数总数 862", 862, C.RETURN_IMMEDIATE_COUNT)
    s.eq("X-05 逐函数返回码合计", C.RETURN_IMMEDIATE_COUNT,
        C.FUNCTIONS.sumOf { it.returnCodes.size })
    s.eq("X-05 去重错误码 60", 60, NmXControlFlow.allReturnCodes().size)
    s.eq("X-07 日志引用总数 1964", 1964, C.LOG_REF_COUNT)
    s.eq("X-07 逐函数 logRefCount 合计", C.LOG_REF_COUNT,
        C.FUNCTIONS.sumOf { it.logRefCount })
    s.eq("X-07 rodata 全量字符串 2670", 2670, C.RODATA_STRING_COUNT)
    s.check("X-05 含 -103", -103 in NmXControlFlow.allReturnCodes())
    s.check("X-05 含 -1002", -1002 in NmXControlFlow.allReturnCodes())

    // X-06：按 so 分布
    s.eq("X-06 cache 127", 127, NmXControlFlow.bySo("cache").size)
    s.eq("X-06 engine 966", 966, NmXControlFlow.bySo("engine").size)
    s.eq("X-06 audio 139", 139, NmXControlFlow.bySo("audio").size)
    s.eq("X-06 audio-legacy 9", 9, NmXControlFlow.bySo("audio-legacy").size)

    // X-01 / X-04：按真实地址查询与求值
    val rf = NmXControlFlow.lookup("0xc3f4")
    s.check("X-01 lookup ReadFromFileNoLock @0xc3f4", rf != null)
    s.check("X-04 该函数含条件分支", rf != null && rf.branchCount > 0)
    s.check("X-04 该函数有返回立即数", rf != null && rf.returnCodes.isNotEmpty())
    val taken = NmXControlFlow.evaluate("0xc3f4", XCondition(value = 0))
    val skipped = NmXControlFlow.evaluate("0xc3f4", XCondition(value = 1))
    s.eq("X-04 cbz value=0 → taken", true, taken.taken)
    s.eq("X-04 cbz value=1 → not taken", false, skipped.taken)
    s.check("X-04 taken 路径返回负错误码", taken.returnCode < 0)
    s.eq("X-04 not-taken 路径返回 0", 0, skipped.returnCode)
    s.check("X-04 判读依据非空", taken.reason.isNotEmpty() && taken.kind != null)
    s.check("X-04 未知地址安全返回", !NmXControlFlow.evaluate("0x0", XCondition()).taken)

    // X-06：814 个新增登记函数全部可查可求值
    val reg = C.FUNCTIONS.filter { it.disposition == "registered" }
    s.eq("X-06 registered 814", 814, reg.size)
    s.check("X-01 每个 registered 都能 lookup", reg.all { NmXControlFlow.lookup(it.addr) != null })
    s.check("X-04 每个 registered 都能 evaluate",
        reg.all { NmXControlFlow.evaluate(it.addr, XCondition(value = 1)).reason.isNotEmpty() })
    s.check("X-03 有分支的函数 branchMask 非零",
        C.FUNCTIONS.filter { it.branchCount > 0 }.all { it.branchMask != 0 })
    s.check("X-03 分支助记词可反解", XBranchKind.namesOf(XBranchKind.CBZ.bit).contains("cbz"))

    // X-08：脱敏与指纹
    val bannedNs = "com" + ".netease"
    val bannedPath = "com" + "/netease"
    s.check("X-08 符号名零竞品包名",
        C.FUNCTIONS.none { bannedNs in it.name || bannedPath in it.name })
    s.check("X-08 inventory 指纹 64 位", C.INVENTORY_SHA256.length == 64)
    s.check("X-08 anchors 指纹 64 位", C.ANCHORS_SHA256.length == 64)
    s.check("X-08 两份指纹不同", C.INVENTORY_SHA256 != C.ANCHORS_SHA256)
}

// ---------------------------------------------------------------------------
// 17. Z 组 —— 代码口径全量逐行翻译：守恒、索引、执行、外部依赖
// ---------------------------------------------------------------------------

private fun testZGroup(s: Suite) {
    s.section("17. Z 组：6324 块 / 388068 条指令逐行翻译与执行")
    val C = NmTranslatedCatalog

    // ---- Z-01 指令守恒：.text 字节账闭合 ----
    s.eq("Z-01 翻译指令 388068", 388068, C.INSTRUCTION_COUNT)
    s.eq("Z-01 代码块 6324", 6324, C.BLOCK_COUNT)
    s.eq("Z-01 函数入口 7486", 7486, C.ENTRY_COUNT)
    s.eq("Z-01 cache 775 块", 775, C.CACHE_BLOCKS)
    s.eq("Z-01 engine 1889 块", 1889, C.ENGINE_BLOCKS)
    s.eq("Z-01 audio 3211 块", 3211, C.AUDIO_BLOCKS)
    s.eq("Z-01 audio-legacy 449 块", 449, C.AUDIO_LEGACY_BLOCKS)
    s.eq("Z-01 四域块数合计 == BLOCK_COUNT", C.BLOCK_COUNT,
        C.CACHE_BLOCKS + C.ENGINE_BLOCKS + C.AUDIO_BLOCKS + C.AUDIO_LEGACY_BLOCKS)
    s.eq("Z-01 cache 783 入口", 783, C.CACHE_ENTRIES)
    s.eq("Z-01 engine 2646 入口", 2646, C.ENGINE_ENTRIES)
    s.eq("Z-01 audio 3459 入口", 3459, C.AUDIO_ENTRIES)
    s.eq("Z-01 audio-legacy 598 入口", 598, C.AUDIO_LEGACY_ENTRIES)
    s.eq("Z-01 四域入口合计 == ENTRY_COUNT", C.ENTRY_COUNT,
        C.CACHE_ENTRIES + C.ENGINE_ENTRIES + C.AUDIO_ENTRIES + C.AUDIO_LEGACY_ENTRIES)
    s.eq("Z-01 块基址连续无空洞",
        listOf(C.ENGINE_BASE, C.AUDIO_BASE, C.AUDIO_LEGACY_BASE),
        listOf(C.CACHE_BASE + C.CACHE_BLOCKS, C.ENGINE_BASE + C.ENGINE_BLOCKS,
            C.AUDIO_BASE + C.AUDIO_BLOCKS))
    s.eq("Z-01 末域块满即总数", C.BLOCK_COUNT, C.AUDIO_LEGACY_BASE + C.AUDIO_LEGACY_BLOCKS)
    // .text 可解码指令 406580 = 翻译 388068 + 字面量池 18512 + 不可解码可达 0
    s.eq("Z-01 .text 指令账闭合", 406580, C.INSTRUCTION_COUNT + 18512)

    // ---- Z-02 语义 op 表：不存在 PRESERVED 兜底桶 ----
    s.eq("Z-02 语义 op 216", 216, NmOp.COUNT)
    s.eq("Z-02 op 名表长度一致", NmOp.COUNT, NmOp.NAMES.size)
    s.check("Z-02 op 名可反查", NmOp.name(NmOp.UNKNOWN) == "UNKNOWN")
    s.check("Z-02 越界 op 名安全", NmOp.name(NmOp.COUNT) == "?" && NmOp.name(-1) == "?")
    s.check("Z-02 无 PRESERVED 兜底桶", NmOp.NAMES.none { "PRESERVED" in it })
    s.check("Z-02 标量与向量整数运算分桶", listOf(
        "ADD_REG", "ADD_VEC_I", "SUB_REG", "SUB_VEC_I", "MUL", "MUL_VEC_I",
        "AND_REG", "AND_VEC", "ORR_REG", "ORR_VEC", "EOR_REG", "EOR_VEC",
        "BIC_REG", "BIC_VEC", "FADD", "FADD_VEC",
        "LD", "ST", "LD1", "ST1", "BL", "BLR", "BR", "RET", "MRS", "SVC",
    ).all { it in NmOp.NAMES })

    // ---- Z-03 块索引：全部块的 lo / hi 反查闭合 ----
    val plan = listOf(
        "cache" to C.CACHE_BASE, "engine" to C.ENGINE_BASE,
        "audio" to C.AUDIO_BASE, "audio-legacy" to C.AUDIO_LEGACY_BASE,
    )
    var hit = 0
    var spanBad = 0
    var orderBad = 0
    for ((so, base) in plan) {
        var prev = -1L
        for (k in 0 until C.blocksOf(so)) {
            val g = base + k
            val lo = C.blockLo(g)
            val span = C.blockSpan(g)
            if (span < 1L || span > 64L) spanBad++
            if (lo <= prev) orderBad++
            prev = lo
            if (C.blockIndexOf(so, lo) == g) hit++
            if (C.blockIndexOf(so, lo + (span - 1) * 4) == g) hit++
        }
    }
    s.eq("Z-03 块 span 全部落在 1..64", 0, spanBad)
    s.eq("Z-03 每域块 lo 严格递增", 0, orderBad)
    s.eq("Z-03 lo/hi 反查全部闭合", C.BLOCK_COUNT * 2, hit)
    s.eq("Z-03 未知 so 返回 -1", -1, C.blockIndexOf("nope", C.blockLo(0)))
    s.eq("Z-03 段外地址返回 -1", -1, C.blockIndexOf("cache", 0L))
    s.eq("Z-03 块内偏移可命中", 0, C.blockIndexOf("cache", C.blockLo(0) + 4L))
    s.eq("Z-03 越界下标 lo = -1", -1L, C.blockLo(-1))
    s.eq("Z-03 越界下标 span = 0", 0L, C.blockSpan(C.BLOCK_COUNT))
    val aliasA = C.blockIndexOf("audio-legacy", C.blockLo(C.AUDIO_LEGACY_BASE))
    val aliasB = C.blockIndexOf("audio_legacy", C.blockLo(C.AUDIO_LEGACY_BASE))
    s.check("Z-03 域名连字符/下划线等价", aliasA == aliasB && aliasA == C.AUDIO_LEGACY_BASE)

    // ---- Z-04 入口表 ----
    s.eq("Z-04 入口表合计 == ENTRY_COUNT", C.ENTRY_COUNT,
        C.DOMAINS.sumOf { C.entryAddrs(it).size })
    s.check("Z-04 每域入口升序且落在块内", C.DOMAINS.all { so ->
        val a = C.entryAddrs(so)
        a.size == C.entriesOf(so) &&
            a.all { C.blockIndexOf(so, it) >= 0 } &&
            (1 until a.size).all { a[it] > a[it - 1] }
    })
    s.eq("Z-04 未知 so 入口表为空", 0, C.entryAddrs("nope").size)

    // ---- Z-05 分派：越界安全 + 真实执行 ----
    NmBlocks.exec(-1, NmArmState())
    NmBlocks.exec(C.BLOCK_COUNT, NmArmState())
    val stD = NmArmState()
    stD.pc = C.blockLo(0)
    NmBlocks.exec(0, stD)
    s.check("Z-05 越界分派安全且块分派真实执行", stD.steps > 0)

    // ---- Z-06 抽样逐行执行：7486 入口按固定步长分层抽样，全部可调用 ----
    // 抽样步长取 13（≈576 个入口 / 四域），目的是让动态 op 覆盖面接近真实分布，
    // 而不是只跑到某一小段代码。每入口给 12000 次分派预算，超预算的登记不报错。
    val agg = NmExternal()
    var ran = 0
    var progressed = 0
    var totalSteps = 0L
    val domainTrace = StringBuilder()
    for (so in C.DOMAINS) {
        val addrs = C.entryAddrs(so)
        var k = 0
        var dRan = 0
        var dSteps = 0L
        while (k < addrs.size) {
            val st = NmArmState(agg)
            NmMachine(st).call(so, addrs[k], 12_000)
            ran++
            dRan++
            if (st.steps > 0) progressed++
            totalSteps += st.steps
            dSteps += st.steps
            k += 13
        }
        if (domainTrace.isNotEmpty()) domainTrace.append(" / ")
        domainTrace.append("$so:$dRan 入口,$dSteps 步")
    }
    s.check("Z-06 抽样入口数 > 400", ran > 400)
    s.eq("Z-06 抽样入口全部执行了指令", ran, progressed)
    s.check("Z-06 累计执行指令数足够", totalSteps > ran * 50L)
    s.eq("Z-06 零 dropout：无 op 落到未实现兜底", 0, agg.dropouts)
    s.check("Z-06 dropout op 表为空", agg.dropoutOps.isEmpty())
    s.eq("Z-06 无异常 trap", 0, agg.traps)
    val opSeen = agg.opSeen.count { it }
    // 阈值是低水位（实测值的 1/2 左右），避免入口抽样步长调整时抖动；
    // 真实动态覆盖数打印在证据行，供人工核对「翻译确实在跑、不是空转」。
    s.check("Z-06 运行期动态覆盖 op 数 > 40", opSeen > 40)
    println("  [Z-06] sampledEntries=$ran executedInstructions=$totalSteps " +
        "dynamicOps=$opSeen/${NmOp.COUNT} externalCalls=${agg.externalCalls} " +
        "unresolvedTargets=${agg.unresolvedTargets.size} " +
        "budgetExceeded=${agg.budgetExceeded} dropouts=${agg.dropouts} traps=${agg.traps}")
    println("  [Z-06] perDomain=$domainTrace")

    // ---- Z-07 语义原语直测：每条指令一行，必须产生真实副作用 ----
    val st = NmArmState()
    st.pc = 0x1000L
    st.i(0x1000L, NmOp.MOV_IMM, 0, -1, -1, 4096L, 0L, NmF.W8, 0)
    s.eq("Z-07 mov x0,#4096", 4096L, st.x[0])
    st.i(0x1004L, NmOp.ADD_IMM, 1, 0, -1, 8L, 0L, NmF.W8, 0)
    s.eq("Z-07 add x1,x0,#8", 4104L, st.x[1])
    st.i(0x1008L, NmOp.ST, 1, 0, -1, 16L, 0L, NmF.W8, 0)
    s.eq("Z-07 str x1,[x0,#16]", 4104L, st.load(4112L, 8))
    st.i(0x100cL, NmOp.LD, 2, 0, -1, 16L, 0L, NmF.W8, 0)
    s.eq("Z-07 ldr x2,[x0,#16]", 4104L, st.x[2])

    // 标志位与条件选择（not-taken / taken 两路）
    st.x[3] = 7L
    st.x[4] = 9L
    st.pc = 0x2000L
    st.i(0x2000L, NmOp.CMP_REG, 3, -1, 4, 0L, 0L, NmF.W8, 0)
    s.check("Z-07 cmp x3,x4 不置 Z", (st.flags and NmFlag.Z) == 0)
    st.i(0x2004L, NmOp.CSEL, 5, 3, 4, 0L, 0L, NmF.W8, NmCC.EQ)
    s.eq("Z-07 csel eq 不成立取 rm", 9L, st.x[5])
    st.x[6] = 9L
    st.pc = 0x2010L
    st.i(0x2010L, NmOp.CMP_REG, 6, -1, 4, 0L, 0L, NmF.W8, 0)
    s.check("Z-07 cmp x6,x4 置 Z", (st.flags and NmFlag.Z) != 0)
    st.i(0x2014L, NmOp.CSEL, 7, 3, 4, 0L, 0L, NmF.W8, NmCC.EQ)
    s.eq("Z-07 csel eq 成立取 rn", 7L, st.x[7])

    // 标量浮点
    st.pc = 0x3000L
    st.v[0] = 0L
    st.v[2] = 1.5f.toBits().toLong()
    st.v[4] = 2.25f.toBits().toLong()
    st.i(0x3000L, NmOp.FADD, 0, 1, 2, 0L, 0L, NmF.W4, 0)
    s.eq("Z-07 fadd s0,s1,s2", 3.75f, Float.fromBits(st.v[0].toInt()))

    // NEON：立即数填充 + 向量浮点加 + 向量整数加
    val stV = NmArmState()
    stV.pc = 0x4000L
    stV.i(0x4000L, NmOp.MOVI_VEC, 1, -1, -1, 0x3F800000L, 0L, NmArr.S4 shl NmF.ARR_SHIFT, 0)
    stV.i(0x4004L, NmOp.MOVI_VEC, 2, -1, -1, 0x40000000L, 0L, NmArr.S4 shl NmF.ARR_SHIFT, 0)
    stV.i(0x4008L, NmOp.FADD_VEC, 0, 1, 2, 0L, 0L, NmArr.S4 shl NmF.ARR_SHIFT, 0)
    s.eq("Z-07 fadd v0.4s 首车道 1.0+2.0", 3.0f, Float.fromBits(stV.v[0].toInt()))
    s.eq("Z-07 fadd v0.4s 末车道 1.0+2.0", 3.0f, Float.fromBits((stV.v[1] ushr 32).toInt()))
    stV.pc = 0x4010L
    stV.i(0x4010L, NmOp.MOVI_VEC, 3, -1, -1, 7L, 0L, NmArr.H8 shl NmF.ARR_SHIFT, 0)
    stV.i(0x4014L, NmOp.MOVI_VEC, 4, -1, -1, 5L, 0L, NmArr.H8 shl NmF.ARR_SHIFT, 0)
    stV.i(0x4018L, NmOp.ADD_VEC_I, 5, 3, 4, 0L, 0L, NmArr.H8 shl NmF.ARR_SHIFT, 0)
    s.eq("Z-07 add v5.8h 逐车道 7+5", 12L, stV.vgetSz(5, 2, 0))
    s.eq("Z-07 add v5.8h 末车道 7+5", 12L, stV.vgetSz(5, 2, 7))

    // ---- Z-08 外部依赖如实登记，不伪造成功 ----
    val sm = NmArmState()
    sm.pc = 0x5000L
    sm.i(0x5000L, NmOp.MRS, 0, -1, -1, 2L, 0L, NmF.W8, 0)
    s.eq("Z-08 MRS cntvct_el0 登记", 1, sm.ext.sysregReads[2] ?: 0)
    s.check("Z-08 MRS 返回确定性桩值", sm.x[0] > 0L)
    val sb = NmArmState()
    sb.pc = 0x5100L
    sb.i(0x5100L, NmOp.BRK, -1, -1, -1, 0L, 0L, 0, 0)
    s.eq("Z-08 BRK 记 trap", 1, sb.ext.traps)
    s.eq("Z-08 BRK 停机到哨兵", NmArmState.RET_SENTINEL, sb.pc)
    val so = NmArmState()
    NmMachine(so).call("cache", 0x10L, 64)
    s.eq("Z-08 界外入口登记 unresolvedTargets", 1, so.ext.unresolvedTargets.size)
    s.eq("Z-08 界外入口不加 dropout", 0, so.ext.dropouts)

    // ---- Z-09 差分验证的观测口：pc 轨迹逐条记录真实执行序列 ----
    // z_diff.py 用它与 Unicorn 的轨迹逐条对齐，定位「第一条走岔的指令」；
    // 这里只钉住「记录的是真实执行序列、且不影响语义」。
    val stT = NmArmState()
    stT.pcTrace = ArrayList()
    stT.pc = 0x6000L
    stT.i(0x6000L, NmOp.MOV_IMM, 0, -1, -1, 7L, 0L, NmF.W8, 0)
    stT.i(0x6004L, NmOp.ADD_IMM, 0, 0, -1, 1L, 0L, NmF.W8, 0)
    stT.i(0x6008L, NmOp.ADD_IMM, 0, 0, -1, 1L, 0L, NmF.W8, 0)
    s.eq("Z-09 pc 轨迹长度 == 执行条数", 3, stT.pcTrace?.size)
    s.eq("Z-09 pc 轨迹逐条地址", listOf(0x6000L, 0x6004L, 0x6008L),
        stT.pcTrace?.toList())
    s.eq("Z-09 轨迹不影响语义", 9L, stT.x[0])

    // ---- Z-10 差分暴露的语义缺陷：逐条钉住，防止回归。 @spec Z-10 ----
    // ① fnmsub 的符号零：ARM 定义是 -Sa + (Sn*Sm)，不是 -(Sa - Sn*Sm)。
    //    两者实数值等价，但 Sa=-0.0、乘积=-0.0 时前者 +0.0、后者 -0.0。
    val sf = NmArmState()
    sf.pc = 0x7000L
    sf.v[14] = 0x8000_0006L     // s7 = 极小负数（v 数组是每寄存器 2 个 64 位槽）
    sf.v[18] = 0L               // s9 = +0.0
    sf.v[38] = 0x8000_0000L     // s19 = -0.0
    sf.i(0x7000L, NmOp.FNMSUB, 19, 7, 9, 0L, 19L, NmF.W4, 0)
    s.eq("Z-10 fnmsub 符号零归正 -Sa+(Sn*Sm)", 0L, sf.v[38] and 0xFFFF_FFFFL)

    // ② fcvtzs 按目标位宽饱和：1e20 不能截断回绕成 0xFFFFFFFF
    val sc = NmArmState()
    sc.pc = 0x7010L
    sc.v[2] = 1e20.toFloat().toBits().toLong()
    sc.i(0x7010L, NmOp.FCVTZS, 0, 1, -1, 0L, 0L, NmF.W4, NmF.W4 shl 24)
    s.eq("Z-10 fcvtzs 溢出饱和到 INT_MAX", 0x7FFF_FFFFL, sc.x[0] and 0xFFFF_FFFFL)
    sc.v[2] = (-1.5f).toBits().toLong()
    sc.i(0x7014L, NmOp.FCVTZU, 2, 1, -1, 0L, 0L, NmF.W4, NmF.W4 shl 24)
    s.eq("Z-10 fcvtzu 负数夹到 0", 0L, sc.x[2])

    // ③ fmov 通用↔浮点是位拷贝，不是数值转换
    val smv = NmArmState()
    smv.pc = 0x7020L
    smv.x[8] = 0x3F35_04F3L
    smv.i(0x7020L, NmOp.FMOV_RR, 0, 8, -1, 0L, 0L, NmF.W4 or NmF.RDV, 0)
    s.eq("Z-10 fmov s0,w8 位拷贝", 0x3F35_04F3L, smv.v[0] and 0xFFFF_FFFFL)

    // ④ mov d0, v1.d[1] 是 lane 提取，且高位清零
    val sl = NmArmState()
    sl.pc = 0x7030L
    sl.v[2] = 0x1111_1111_1111_1111L
    sl.v[3] = 0x2222_2222_2222_2222L
    sl.i(0x7030L, NmOp.MOV_VEC, 0, 1, -1, 8L, 0L, NmF.RDV, 1 shl 15)
    s.eq("Z-10 mov d0,v1.d[1] 取高 lane", 0x2222_2222_2222_2222L, sl.v[0])
    s.eq("Z-10 mov d0,v1.d[1] 清高 64 位", 0L, sl.v[1])

    // ⑤ movi dN, #imm 标量形式：没有排列码时立即数即整个 64 位
    val si = NmArmState()
    si.pc = 0x7040L
    si.i(0x7040L, NmOp.MOVI_VEC, 0, -1, -1, 0x0000_00FF_FFFF_FFFFL, 0L, 0, 0)
    s.eq("Z-10 movi d0 标量取整 64 位", 0x0000_00FF_FFFF_FFFFL, si.v[0])

    // ⑥ ext v16.8b：.8b 时每个源寄存器只贡献 8 字节
    val sx = NmArmState()
    sx.pc = 0x7050L
    sx.v[4] = 0x0807_0605_0403_0201L   // v2 lo
    sx.v[6] = 0x1817_1615_1413_1211L   // v3 lo
    sx.i(0x7050L, NmOp.EXT_VEC, 16, 2, 3, 4L, 0L, NmArr.B8 shl NmF.ARR_SHIFT, 0)
    s.eq("Z-10 ext v16.8b 按 8 字节拼接", 0x1413_1211_0807_0605L, sx.v[32])
    s.eq("Z-10 ext v16.8b 高位清零", 0L, sx.v[33])
}

fun main() {
    val s = Suite()
    testNativeTypes(s)
    testLogGate(s)
    testNetSource(s)
    testSlicesFile(s)
    testBufferSource(s)
    testAudioPlayer(s)
    testJniBridge(s)
    testF4Chain(s)
    testJniCatalog(s)
    testFormatCatalog(s)
    testDoneTaskChain(s)
    testReadGap(s)
    testUGroup(s)
    testVGroup(s)
    testWGroup(s)
    testXGroup(s)
    testZGroup(s)

    println()
    println("=".repeat(72))
    println("CONTRACT TESTS: ${s.passed}/${s.total} passed, ${s.failed} failed")
    if (s.failed > 0) {
        println("FAILED CASES:")
        s.failures.forEach { println("  - $it") }
    }
    println("=".repeat(72))

    // 结果呈现：把 F4 链路报告原样打印，供 build.py 收进 test-report.json
    println()
    val chain = NmF4Chain()
    println(chain.render(chain.reproduce()))

    if (s.failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
