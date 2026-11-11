@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * libNMCommonCache.so 全局日志门控 —— 1:1 翻译 §61.70 语义纠错结论。
 *
 * 关键：这里**不存在**「需要额外开启的 level-5 开关」。
 * getter 0x37E20 = `w0 = (g_level > N)`，调用点 tbnz/tbz 以「大于」抑制。
 * `g_level` 位于 .bss 0x4C388，进程启动恒为 0 → I/D/W/E 默认全开。
 * `SetLoggerCallback(..., level)` 只用于**抬高阈值关日志**。
 */
class NmLogGate(private val sink: NmLogSink = NmCollectingLogSink()) {

    // @spec G-03 / G-04：.bss 零填充 + 全库唯一写者 + .init_array 无写 → 启动 = 0
    @Volatile
    var globalLevel: Int = 0
        private set

    // @spec G-11 / G-12：JNI logger 注册状态
    var loggerRegistered: Boolean = false
        private set
    var loggerInitMarker: Boolean = false
        private set
    var loggerMethodIdCached: Boolean = false
        private set
    var loggerGlobalRefCached: Boolean = false
        private set

    // @spec G-15：§61.70 §5.1 报告的副作用（level>=6 时部分 tbz 早退可能改变返回码）。
    // 该副作用是「报告级推断、未逐指令坐实」，默认 0 表示不改变返回码，不虚构指令。
    var suppressedSideEffectCode: Int = 0

    /**
     * @spec G-01 / G-05
     * getter 0x37E20: `adrp; ldr w8,[x8,#0x388]; cmp w8,w0; cset w0,gt`
     * 返回 true 表示该条日志被抑制（调用点 tbnz 跳过 / tbz 不进入日志路径）。
     */
    fun isSuppressed(messageLevel: Int): Boolean = globalLevel > messageLevel

    /** @spec G-05：`g_level <= N` 才输出日志。 */
    fun shouldLog(messageLevel: Int): Boolean = !isSuppressed(messageLevel)

    /**
     * @spec G-02
     * setter 0x37E34 / 0x37E38 `str w0,[x8,#0x388]`：全 .text 唯一的 g_level 写者。
     */
    fun setLevel(level: Int) {
        globalLevel = level
    }

    /**
     * @spec G-10 / G-11 / G-12
     * JNI `SetLoggerCallback` @0x32D64 → 0x32D88 tail-call setter 0x37E34。
     * level 参数是**唯一**作用。
     *
     * 注册链 @0x36198：`GetObjectClass(logger)` → `GetMethodID(jclass, "BIInfo",
     * "(ILjava/lang/String;)V")` → methodID 缓存 0x4C318 / global ref 0x4C310 →
     * 初始化标记 0x4C320。若对象没有该方法，GetMethodID 返回 NULL → 0x3624C cbz
     * 走失败分支、标记不置位，且 JNI 规范下可能留下 pending exception。
     */
    fun setLoggerCallback(level: Int, logger: Any?): Boolean {
        setLevel(level)
        val ok = logger is NmLogger
        loggerRegistered = ok
        loggerMethodIdCached = ok
        loggerGlobalRefCached = ok
        loggerInitMarker = ok
        return ok
    }

    /**
     * @spec G-09 / G-13
     * logfn 0x3138C → PLT 0x8A70 → __android_log_print；tag 固定 "NMCache"，
     * 格式 "%s"。消息级别 N 同时作为 Android priority 传入。
     */
    fun log(messageLevel: Int, message: String) {
        if (!shouldLog(messageLevel)) return
        val level = NmLogLevel.fromNative(messageLevel) ?: return
        sink.emit(level, TAG_NMCACHE, message)
    }

    /** @spec G-15：tbz 早退分支可能提前返回的错误码（仅当显式配置时生效）。 */
    fun earlyReturnCodeIfSuppressed(messageLevel: Int): Int =
        if (isSuppressed(messageLevel)) suppressedSideEffectCode else 0

    val sinkRef: NmLogSink get() = sink

    companion object {
        const val TAG_NMCACHE: String = "NMCache"                 // .rodata @0x3B5F0
        const val TAG_NMAUDIOPLAYER: String = "NMAudioPlayer"    // .rodata @0xDD1B3

        /**
         * @spec G-06 / G-07 / G-08
         * 全 .text 线性扫描的统计常量：getter 调用点 173、logfn 调用点 156、
         * (N,M) 严格恒等 163/173 = 94.2%，分支 tbnz 147 / tbz 26。
         */
        const val GETTER_CALL_SITES: Int = 173
        const val LOGFN_CALL_SITES: Int = 156
        const val NM_IDENTICAL_PAIRS: Int = 163
        const val TBNZ_COUNT: Int = 147
        const val TBZ_COUNT: Int = 26
        val LEVEL_PAIR_HISTOGRAM: Map<Pair<Int, Int>, Int> = mapOf(
            Pair(5, 5) to 98,
            Pair(4, 4) to 30,
            Pair(3, 3) to 27,
            Pair(6, 6) to 8,
        )
    }
}

/**
 * @spec G-11
 * native 用 `GetMethodID("BIInfo", "(ILjava/lang/String;)V")` 缓存的接口。
 * 仓库内 INMLogger.java 是空类 → 该方法不存在 → 注册必然失败（§61.70 §5.1 #3）。
 */
interface NmLogger {
    fun biInfo(level: Int, message: String)
}
