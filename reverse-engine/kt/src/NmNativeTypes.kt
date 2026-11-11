@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package cloudmusic.reverse.kernel

/**
 * 竞品播放内核 1:1 逻辑翻译 —— 基础类型层。
 *
 * 数值语义对齐 ARM64 (arm64-v8a) native 侧：
 *  - `Long` 对应 x 寄存器 / int64_t（offset / length / commit / generation / file_len）
 *  - `Int`  对应 w 寄存器 / int32_t（错误码 / kind / 日志级别 / size）
 *
 * 结构体偏移集中保留在 [NmFieldOffsets]，抽取合并时可直接对照反汇编 `[this+0xXX]`。
 * 本文件为纯 JVM Kotlin：无 Android 框架依赖、不引用任何竞品包名。
 *
 * 证据源见 spec/LOGIC-SPEC.md；每条逻辑以 `// @spec EV-xx` 标注，可机读审计。
 */

/** native 结构体字段偏移（十六进制，单位 byte）。 */
object NmFieldOffsets {
    // NMNetSource
    const val NET_REQ_OFFSET: Int = 0x20      // [this+0x20] 本次 Range 请求起始
    const val NET_COMMIT: Int = 0x30          // [this+0x30] 连续确认偏移（不是落盘字节数）
    const val NET_REQ_SIZE: Int = 0x38        // [this+0x38] 当前请求长度基准
    const val NET_SIGNAL_SIZE: Int = 0x40     // [this+0x40] 唤醒阈值
    const val NET_DATA_SINK: Int = 0x190      // [this+0x190] vtable+0x18 数据投递对象
    const val NET_WAKE_FLAG: Int = 0xB8       // [this+0xB8] 越过阈值后的唤醒标志
    const val NET_GENERATION: Int = 0xC0      // [this+0xC0] 当前请求代次

    // NMBufferSourceImp
    const val BUF_FILE_OFFSET: Int = 0xA8     // [this+0xA8] vfile 连续写水位 file_offset_
    const val BUF_NORMAL_MODE: Int = 0xB8     // [this+0xB8] normal-mode 标志（写失败后永久清零）
    const val BUF_PENDING_ASYNC: Int = 0x198  // [this+0x198] 挂起异步读请求
    const val BUF_ASYNC_ERROR: Int = 0x1A0    // [this+0x1A0] Finish Async 返回给播放侧的错误码
    const val BUF_ASYNC_CTX_A: Int = 0x188    // [this+0x188] 异步读上下文（回调清零）
    const val BUF_ASYNC_CTX_B: Int = 0x190    // [this+0x190] 异步读上下文（回调清零）

    // NMSlicesFile
    const val SLI_FILE_LEN: Int = 0x70        // [this+0x70] file_len
    const val SLI_DISABLED: Int = 0xC8        // [this+0xC8] 分片写禁用标志
    const val SLI_SLICE_SIZE: Int = 0xD0      // [this+0xD0] slice_size
    const val SLI_FINISHED: Int = 0x104       // [this+0x104] 全部写完成标志

    // libncmaudioplayer.so / AudioTrack 写路径
    const val AUD_TRACK: Int = 0x1E8          // [this+0x1E8] AudioTrack 对象指针
    const val AUD_PAUSED: Int = 0x20C         // [this+0x20C] paused 标志
    const val AUD_RETRY: Int = 0x354          // [this+0x354] 写失败重试计数
    const val AUD_CALLBACK: Int = 0xD8        // [this+0xD8] vtable+0x20 回调对象

    // libNMCommonCache.so 全局
    const val G_LEVEL_BSS: Int = 0x4C388      // .bss 全局日志阈值 g_level
    const val G_LOGGER_METHOD_ID: Int = 0x4C318 // jmethodID 缓存
    const val G_LOGGER_GLOBAL_REF: Int = 0x4C310 // global ref 缓存
    const val G_LOGGER_INIT_MARK: Int = 0x4C320 // 初始化标记
}

/**
 * native 侧硬编码错误码（立即数 / mov 指令）。
 * 全部来自反汇编，不是项目自研的 E_* 错误码。
 */
object NmError {
    const val EOF: Int = -1                     // 0x1287C file_len==offset；0x66F50 AudioTrack 为空
    const val WAIT_STARVED: Int = -2           // 0xC728 WaitRequest 返回 1；0x12DC8 越界
    const val AUDIO_RETRY_EXHAUSTED: Int = -3  // 0x66F28 重试 >9 经 vtable+0x20 回调
    const val GATE_EARLY_6: Int = -6           // §61.70 §5.1 tbz 早退可能返回 -6
    const val SLICE_WRITE_FAILED: Int = -98    // 0x12B30 (-0x62)
    const val GATE_EARLY_99: Int = -99         // §61.70 §5.1 tbz 早退可能返回 -0x63
    const val SLICE_DISABLED: Int = -100       // 0x127CC (-0x64)
    const val INVALID_RANGE: Int = -102        // 0x12834 / 0x12860 (-0x66)
    const val FINISH_ASYNC: Int = -103         // 0xA9C4 (-0x67) Finish Async
    const val WAIT_FORCE: Int = -106           // 0xC710 (-0x6a) WaitRequest 返回 2
}

/** native 日志消息级别 N → Android logcat 优先级。 */
enum class NmLogLevel(val native: Int, val androidPriority: String) {
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E");

    companion object {
        fun fromNative(n: Int): NmLogLevel? = values().firstOrNull { it.native == n }
    }
}

/** Java 侧透传给 native 的 kind 槽（JNI x3 / long j2）。 */
object NmKind {
    const val NATIVE_DATA_KIND_NORMAL: Long = 1L        // 32768 B 正常送达解码器
    const val NATIVE_DATA_KIND_PRELOAD: Long = 2L       // 尾段 push 丢失 → Send Packet Invalid data error
    const val NATIVE_DONE_KIND_FLUSH: Long = 1L         // §61.55 已真机校准的正常完成值
    const val NATIVE_DONE_KIND_PRELOAD_DONE: Long = 2L  // j1==2 → PreloadDoneTaskOnWriteThread
}

/** 日志接收端：测试用收集实现，便于断言「某级别是否输出」。 */
interface NmLogSink {
    fun emit(level: NmLogLevel, tag: String, message: String)
}

data class NmLogRecord(val level: NmLogLevel, val tag: String, val message: String)

class NmCollectingLogSink : NmLogSink {
    private val _records = mutableListOf<NmLogRecord>()
    val records: List<NmLogRecord> get() = _records.toList()

    override fun emit(level: NmLogLevel, tag: String, message: String) {
        _records += NmLogRecord(level, tag, message)
    }

    fun countOf(messageContains: String): Int = _records.count { messageContains in it.message }
    fun clear() = _records.clear()
}

/** vtable+0x18：OnData 通过它把数据投递给 NMBufferSourceImp::OnNetSourceData。 */
interface NmDataSink {
    fun onNetSourceData(kind: Long, buffer: ByteArray, offset: Long, size: Long): Int
}

/** vtable+0x10：MakeRangeRequestAsync 通过它发起 Range 请求（Java Start 的 native 侧）。 */
interface NmRangeRequester {
    fun start(generation: Long, offset: Long, length: Long): NmRangeStartResult
}

data class NmRangeStartResult(val ok: Boolean, val code: Int = 0)

/** 播放侧回调（vtable+0x20 / vtable+0x150 的抽象）。 */
interface NmPlayerCallback {
    fun onError(code: Int)
    fun onStateChange(stateCode: Int)
}
