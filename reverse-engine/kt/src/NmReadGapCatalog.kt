@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * §61.77 F4 读侧空白链一手反汇编证据 —— 全量登记（R 组）。
 * 证据源：analysis/l3-f4-read-gap-chain.json（31/31 checks, all_pass=true）。
 * 由 tools/gen_read_gap_manifest.py 自动生成，勿手改；
 * 受 spec/read-gap-manifest.json 的源 JSON sha256 + 常量期望值守护。
 * 口径：静态二进制证据 ≠ 修复 ≠ 真机验证；F4 仍 BLOCKED。
 */
object NmReadGapCatalog {

    /** @spec R-01 .rodata 格式串地址（唯一引用点 0xC454/0xC45C） */
    const val RODATA_ADDR: Int = 0x3BEE8
    const val RODATA_TEXT: String = "NMBufferSourceImp(%lld)::ReadFromFileNoLock  Local File Read expect len:%u, offset:%lld, readed_len:%u, result:%d"
    const val RODATA_UNIQUE: Boolean = true
    const val RODATA_REF_ADRP_ADDR: Int = 0xC454
    const val RODATA_REF_ADD_ADDR: Int = 0xC45C

    /** @spec R-06 24 个指令锚点地址（与源 JSON anchors 一一对应） */
    const val ANCHOR_COUNT: Int = 24
    const val A_FMT_REF_ADRP_ADDR: Int = 0xC454
    const val A_FMT_REF_ADD_ADDR: Int = 0xC45C
    const val A_READ_RESULT_CALL_ADDR: Int = 0xC434
    const val A_READ_LOG_CALL_ADDR: Int = 0xC470
    const val A_MINUS2_BRANCH_ADDR: Int = 0xC478
    const val A_WAIT_REQUEST_CALL_ADDR: Int = 0xC6F8
    const val A_WAIT_RET1_CMP_ADDR: Int = 0xC700
    const val A_WAIT_RET1_BEQ_ADDR: Int = 0xC704
    const val A_RESULT_MINUS2_ADDR: Int = 0xC728
    const val A_RESULT_MINUS106_ADDR: Int = 0xC710
    const val A_RESULT_MINUS103_ADDR: Int = 0xC730
    const val A_READ_RET_ADDR: Int = 0xC73C
    const val A_HARDCODED_MINUS2_ADDR: Int = 0xA9BC
    const val A_HARDCODED_MINUS103_ADDR: Int = 0xA9C4
    const val A_PENDING_READ_LOAD_ADDR: Int = 0xA9D0
    const val A_PENDING_READ_CBZ_ADDR: Int = 0xA9D4
    const val A_WRITE_1A0_ADDR: Int = 0xA9E4
    const val A_PENDING_REGISTER_ADDR: Int = 0x9F44
    const val A_PENDING_CLEAR_FINISH_ADDR: Int = 0xA018
    const val A_PENDING_WAIT_NEAR_ADDR: Int = 0x10B20
    const val A_SLICES_WRITE_MINUS2_ADDR: Int = 0x12DC8
    const val A_GENERATION_CMP_ADDR: Int = 0x10794
    const val A_GENERATION_BNE_ADDR: Int = 0x10798
    const val A_COMMIT_STORE_ADDR: Int = 0x10888

    /** @spec R-04 [+0x198] 挂起异步读的 6 处写入点 */
    const val PENDING_198_WRITE_COUNT: Int = 6
    val PENDING_198_WRITES: List<Int> = listOf(0x9F44, 0xA018, 0x100C8, 0x106C0, 0x1095C, 0x10B20)

    /** @spec R-05 -2 的 7 处硬编码立即数点（含 0x12DC8 NMSlicesFile::Write 越界） */
    const val HARDCODED_MINUS2_POINT_COUNT: Int = 7
    val HARDCODED_MINUS2_POINTS: List<Int> = listOf(0xA9BC, 0xC728, 0xE720, 0xE780, 0xEBDC, 0x12DC8, 0x1329C)

    /** @spec R-07 上线复跑优先锚点 / 自洽性统计 */
    const val FIX_ANCHOR_COUNT: Int = 3
    val FIX_ANCHORS: List<String> = listOf("0x10798 代次比较早退", "0xc728 WaitRequest 读空 -2", "0x12dc8 NMSlicesFile::Write 越界 -2")
    const val CHECKS_TOTAL: Int = 31
    const val CHECKS_PASS: Int = 31
    const val ALL_PASS: Boolean = true
    const val F4_STATUS: String = "BLOCKED"
}
