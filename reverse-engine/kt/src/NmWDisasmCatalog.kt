@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * 【生成物，勿手改】W 组：竞品播放内核 1:1 指令级补译常量表。
 * 由 tools/gen_w_manifest.py 从 spec/w-disasm-evidence.json 生成，
 * 受 spec/w-disasm-manifest.json 的源 sha256 守护，audit.py 第 17/18 项每次比对。
 *
 * 证据：libNMCommonCache.so arm64-v8a 全 .text capstone 反汇编
 *       （8 窗口 / 238 指令 / 28 锚点全通过）。
 *
 * @spec W-01 / W-02 / W-03 / W-04 / W-05 / W-06 / W-07 / W-08 / W-09 / W-10 / W-11 / W-12 / W-13 / W-14
 * 口径：只登记逐指令坐实的控制流 / 门控 / 错误码；不推断未坐实的分支语义。
 */
object NmWDisasmCatalog {

    // ---- 一手证据指纹 ----
    const val EVIDENCE_SHA256: String = "d5096bb330eea95f5703bd7aa64f826c0a0c41b6f729758b4ecff73f2daaca70"
    const val SO_SHA256: String = "7a1adc95c255c241c1d5d64060468cefe3961c73c459ba15527ce87e5fde4a6c"

    // ---- 反汇编锚点地址 / 定案常量（Int）----
    const val SLICE_OBJECT_STRIDE: Int = 0xe8
    const val LOG_GATE_FUNC_ADDR: Int = 0x37e20
    const val LOG_GATE_SET_ADDR: Int = 0x37e34
    const val WRITE_INDEX_SDIV_ADDR: Int = 0x12890
    const val WRITE_CINC_ADDR: Int = 0x128a0
    const val WRITE_EMPTY_BRANCH_ADDR: Int = 0x128ac
    const val READ_CLEAR_FLAG_ADDR: Int = 0xc4b8
    const val READ_MINUS2_ADDR: Int = 0x12dc8
    const val READ_MINUS2_BRANCH_ADDR: Int = 0x12cec
    const val CODE_GATE_CLEAR_RETURN: Int = -6
    const val CODE_ZERO_READED: Int = -3
    const val CODE_ZERO_LENGTH: Int = -2
    const val CODE_SLICE_READ_FAILED: Int = -98
    const val CODE_EOF: Int = -1
    const val CODE_INVALID_RANGE: Int = -102
    const val CODE_EOF_DIAG: Int = -99
    const val LEVEL_I_READ: Int = 0x3
    const val LEVEL_W_READ: Int = 0x5
    const val LEVEL_W_EOF: Int = 0x5
    const val LEVEL_I_EOF: Int = 0x4
    const val ALIGN_MASK: Int = 0x3f

    /** W 组规格编号全集，供契约测试与审计核对。 */
    val SPEC_IDS: List<String> = listOf("W-01", "W-02", "W-03", "W-04", "W-05", "W-06", "W-07", "W-08", "W-09", "W-10", "W-11", "W-12", "W-13", "W-14")
}
