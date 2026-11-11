@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * U 组补遗：NMBufferSourceImp 构造期 Memory Mode 双门控 + NMBufferSourceAdapter::Open()
 * 的 onError(1001) 通用错误码链 —— A3 阶段一手反汇编里此前未被翻译的竞品 native 控制流。
 *
 * 证据（均为对竞品 so 的 capstone / ELF 符号表反汇编，不是项目自身实现）：
 *   - analysis/a3-stage-c-analysis-2026-11-07.md §1.1 / §1.2（libNMCommonCache.so，
 *     构造函数 0x9164 size 1012）—— **L1**：逐指令坐实 [+0xB8] 初值与 Memory Mode 门控。
 *   - analysis/a3-phase-ab-evidence-2026-11-07.md §1（libncmplayerengine.so，
 *     NMBufferSourceAdapter::Open @0x42350 size 5572）—— **L2**：报告给出语义与地址锚点，
 *     10 处 mov 立即数的逐条指令上下文未复核，故 U-06 / U-07 明确标 L2。
 *
 * 口径纠正：这两份证据此前在 COVERAGE 第 9 节被归为 C 类「项目自身 A3 真机验收与阶段
 * 挂账证据，非竞品 native 内核反汇编控制逻辑」；复核发现其正文是对竞品 so 的指令级
 * 反汇编，坐实的是竞品控制流，故本批改列 A 类逻辑锚点（U-10）。
 * 本组不把 A3 的 E1~E5 真机现象写成 F4 因果链，F4 结论仍以 §61.70 / §61.75 为准。
 *
 * 与既有组的关系：B-08 / B-14 / B-20 已翻译 [+0xB8] 的「写失败后永久清零」与
 * 「ReadFromFileNoLock 清零」；本组补的是**构造期初值**与**进入 Memory Mode 的清零门控**，
 * 二者是同一字段的不同生命周期，不重复、不冲突。
 */

/** U-01~U-07 的指令 / 结构锚点常量（对照反汇编地址，勿语义化改名后丢掉数值）。 */
object NmA3Anchors {
    /** 0x9164 NMBufferSourceImp 构造函数，size=1012 B */
    const val CTOR_ADDR: Int = 0x9164
    const val CTOR_SIZE: Int = 1012

    /** @spec U-01 0x91B8 mov w8,#0x101 / 0x91E0 strh w8,[x0,#0xB8] */
    const val CTOR_INIT_MOV_ADDR: Int = 0x91B8
    const val CTOR_INIT_STRH_ADDR: Int = 0x91E0

    /** @spec U-02 0x92A0 strb w23,[x19,#0x98] 策略 / 0x92C4 stp ... [x19,#0xD8] vfile */
    const val CTOR_STRATEGY_STORE_ADDR: Int = 0x92A0
    const val CTOR_VFILE_STORE_ADDR: Int = 0x92C4

    /** @spec U-03 / U-04 Memory Mode 双门控窗口 */
    const val MEMORY_TBZ_ADDR: Int = 0x941C
    const val MEMORY_LDR_VFILE_ADDR: Int = 0x9420
    const val MEMORY_CBNZ_ADDR: Int = 0x9424
    const val MEMORY_CLEAR_ADDR: Int = 0x942C
    const val MEMORY_LOG_ADDR: Int = 0x9448

    /** @spec U-05 0x9448 add x2,x2,#0x649 指向的 .rodata 格式串 */
    const val MEMORY_MODE_LOG_FMT: String = "NMBufferSourceImp(%lld)::Start Use Memory Mode."

    /** @spec U-06 NMBufferSourceAdapter::Open() 符号与 size */
    const val OPEN_ADDR: Int = 0x42350
    const val OPEN_SIZE: Int = 5572

    /** @spec U-06 Open() 内 4 处 mov wN,#0x3e9 的命中点；全 .text 共 10 处 */
    val OPEN_1001_SITES: List<Int> = listOf(0x424FC, 0x42558, 0x425B0, 0x42D9C)
    const val TOTAL_1001_IMM_HITS: Int = 10
    const val OPEN_1001_SITE_COUNT: Int = 4

    /** @spec U-07 ISourceCallback* 字段与 OnError(int) 的 vtable 槽位 */
    const val CALLBACK_FIELD: Int = 0x40
    const val ONERROR_VTABLE_SLOT: Int = 0x18
}

/** U-01 / U-05 / U-06 的 native 立即数。 */
object NmA3Error {
    /** @spec U-06 mov wN,#0x3e9 = 1001，Open() 多个失败分支共用的通用错误码 */
    const val OPEN_GENERIC_ERROR: Int = 1001
    const val OPEN_GENERIC_ERROR_IMM: Int = 0x3E9

    /** @spec U-01 0x101 一次 strh 同时置 [+0xB8]=1 与 [+0xB9]=1 */
    const val INIT_BOTH_FLAGS: Int = 0x101

    /** @spec U-05 0x9428 mov w0,#6，进入 Memory Mode 分支的日志实参 */
    const val MEMORY_MODE_LOG_ARG: Int = 6
}

/**
 * U-01~U-05：NMBufferSourceImp 构造期的 Memory Mode 双门控（L1 逐指令翻译）。
 *
 * 真值表（由 0x941C tbz + 0x9424 cbnz 直接得出）：
 * | strategy bit0 | NMVirtualFile | 结果 | 指令路径 |
 * | 0 | 任意 | 进 Memory Mode | tbz 直接跳 0x9428 |
 * | 1 | null | 进 Memory Mode | cbnz 未跳，落入 0x9428 |
 * | 1 | 非 null | **跳过** | cbnz 跳 0x9454 |
 * 即「不进 Memory Mode」的充要条件 = bit0==1 **且** NMVirtualFile!=null。
 * 进入后 0x942C `strb wzr,[x19,#0xB8]` 清 [+0xB8]，Java 侧 OnDataRequestData
 * 的字节此后全部被 OnWriteNetData 判为 "Failed Not Support"（B-08 已翻译该读侧后果）。
 */
class NmMemoryModeGate {
    /** @spec U-02 [+0x98] BufferSourceStrategy（按值传入 x5） */
    var strategy: Int = 0
        private set

    /** @spec U-02 [+0xD8] NMVirtualFile shared_ptr 是否非空 */
    var virtualFilePresent: Boolean = false
        private set

    /** @spec U-01 / U-04 [+0xB8] normal-mode 标志，构造初值 1，进入 Memory Mode 清零 */
    var normalMode: Boolean = true
        private set

    /** @spec U-01 [+0xB9] 与 [+0xB8] 由同一条 strh #0x101 置 1 */
    var secondaryFlag: Boolean = true
        private set

    /** @spec U-04 是否命中 0x9428 进入分支 */
    var memoryModeEntered: Boolean = false
        private set

    /** @spec U-05 "Start Use Memory Mode." 打印次数 */
    var memoryModeLogCount: Int = 0
        private set

    /** @spec U-05 0x9428 mov w0,#6 */
    var logArgOnEnter: Int = 0
        private set

    /** @spec U-01 / U-02 / U-03 / U-04 / U-05 构造期一次性应用门控，返回是否进入 Memory Mode。 */
    fun apply(strategy: Int, virtualFilePresent: Boolean): Boolean {
        // @spec U-01：0x91B8 mov w8,#0x101 → 0x91E0 strh w8,[x0,#0xB8]
        normalMode = true
        secondaryFlag = true
        memoryModeEntered = false
        logArgOnEnter = 0

        // @spec U-02：0x91FC mov x23,x5 / 0x92A0 存策略 / 0x92C4 存 NMVirtualFile
        this.strategy = strategy
        this.virtualFilePresent = virtualFilePresent

        // @spec U-03：0x941C tbz w23,#0,#0x9428 —— bit0==0 直接进 Memory Mode
        val bit0IsZero = (strategy and 1) == 0
        // @spec U-04：0x9420 ldr / 0x9424 cbnz x8,#0x9454 —— vfile 非空才跳过
        val enter = bit0IsZero || !virtualFilePresent

        if (enter) {
            // @spec U-04：0x942C strb wzr,[x19,#0xB8]
            normalMode = false
            memoryModeEntered = true
            // @spec U-05：0x9428 mov w0,#6 + 0x9448 "Start Use Memory Mode."
            logArgOnEnter = NmA3Error.MEMORY_MODE_LOG_ARG
            memoryModeLogCount++
        }
        return enter
    }
}

/**
 * U-06 / U-07：NMBufferSourceAdapter::Open() 的通用错误码链（L2）。
 *
 * 反汇编片段（a3-phase-ab-evidence §1）：
 * ```
 * ldr  x0, [x21, #0x40]     ; ISourceCallback*
 * ldr  x8, [x0]             ; vtable
 * mov  w1, #0x3e9           ; 1001
 * ldr  x8, [x8, #0x18]      ; OnError(int)
 * blr  x8
 * ```
 * 1001 是 Open() 多个失败分支共用的通用错误码（某虚调用返回 0 即报），不是单一原因；
 * 全 .text 共 10 处 `mov wN,#0x3e9`，其中 4 处落在 Open()（符号 0x42350，size 5572）。
 * 这里把 [x21,#0x40] / vtable+0x18 的虚调用映射为 NmPlayerCallback.onError(code)，
 * 与 NmAudioPlayer 的 AUDIO_RETRY_EXHAUSTED(-3) 回调是**不同来源、不同码值**。
 *
 * 诚实边界：报告只给出「某虚调用返回 0 即报」的语义，未逐条复核 10 处立即数的分支
 * 上下文，故本类用可注入的 probe 结果表达触发条件，不硬编码「哪个具体分支」。
 */
class NmBufferSourceAdapter(private val callback: NmPlayerCallback? = null) {
    /** @spec U-06 onError(1001) 上报次数 */
    var onError1001Count: Int = 0
        private set

    /** @spec U-06 最近一次上报的错误码 */
    var lastErrorCode: Int = 0
        private set

    /** @spec U-06 / U-07 probeReturnsZero 表达「Open() 内某虚调用返回 0」这一失败条件。 */
    fun openWithProbe(probeReturnsZero: Boolean): Int {
        if (!probeReturnsZero) return 0
        lastErrorCode = NmA3Error.OPEN_GENERIC_ERROR
        onError1001Count++
        // @spec U-07：[x21,#0x40] ISourceCallback* → vtable+0x18 OnError(int) → blr x8
        callback?.onError(NmA3Error.OPEN_GENERIC_ERROR)
        return NmA3Error.OPEN_GENERIC_ERROR
    }
}
