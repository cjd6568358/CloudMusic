@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * 【生成物，勿手改】V 组：章节矩阵机器自证 + 低分证据零漏网 + §61.87/§61.88 口径快照。
 * 由 tools/gen_chapter_matrix_manifest.py 从 2 份扫描 JSON 生成，
 * 受 spec/chapter-matrix-manifest.json 的源 sha256 守护，audit.py 第 15/16 项每次比对。
 *
 * @spec V-01 / V-02 / V-03 / V-04 / V-05 / V-06
 * 口径：本 catalog 只做覆盖审计元数据登记，不新增竞品控制流翻译；
 * 总章节数不写死上界，章节矩阵防漂移由 tools/scan_chapter_matrix.py 的
 * unmapped=0 断言守护；B2 事实只登记、不折算成竞品 native 结论。
 */
object NmChapterMatrixCatalog {

    // ---- 扫描快照 Int 常量（章节矩阵 + 低分证据零漏网）----
    const val TOTAL_CHAPTERS: Int = 84
    const val NUMBERED_MAX: Int = 88
    const val MISSING_NUMBER_COUNT: Int = 4
    const val TABLE_ROWS: Int = 24
    const val TABLE_MAPPED_NUMBER_COUNT: Int = 88
    const val A_CLASS_COUNT: Int = 9
    const val B_CLASS_COUNT: Int = 1
    const val C_CLASS_COUNT: Int = 74
    const val NONE_CLASS_COUNT: Int = 4
    const val UNMAPPED_COUNT: Int = 0
    const val C_CLASS_HIGH_L1_COUNT: Int = 0
    const val C_CLASS_L1_HIGH_SCORE_CHAPTER_COUNT: Int = 4
    const val C_CLASS_ANCHOR_CHAPTER_COUNT: Int = 2
    const val ANCHOR_ADJUDICATION_COUNT: Int = 2
    const val NEWLY_REGISTERED_BY_V_COUNT: Int = 16
    const val MAX_C_CLASS_ADDR_INSN_LINES: Int = 0
    const val MAX_C_CLASS_MAX_MNE_BLOCK: Int = 0
    const val A_CLASS_FIRST: Int = 54
    const val B_CLASS_FIRST: Int = 83
    const val TOP_L1_A_CLASS_CHAPTER: Int = 67
    const val LOW_SCORE_TOTAL: Int = 134
    const val ASM_CANDIDATE_COUNT: Int = 0
    const val ASM_FENCE_TOTAL: Int = 0
    const val MAX_ADDR_INSN_LINES: Int = 0
    const val MAX_MAX_MNE_BLOCK: Int = 0
    const val MAX_INLINE_ADDR_MNEMONIC_LINES: Int = 0
    const val PLAIN_FENCE_CANDIDATE_COUNT: Int = 29
    const val PLAIN_FENCE_ADJUDICATION_COUNT: Int = 29

    // ---- 上面的 Int 常量已含 LOW_* / ASM_* / PLAIN_* 低分零漏网快照 ----
    // ---- 源指纹 / 名单指纹 ----
    const val CHAPTER_SCAN_SHA256: String = "8e016dfaea9bf54e621a354464e34ef14adc012bcc69e7545e96e5881186c1f7"
    const val LOW_SCORE_SCAN_SHA256: String = "2a79918875d8848b7984dc846cba5e353632001a7aa10ee9940dbc0a0b7824be"
    const val FENCE_FINGERPRINT: String = "51a8b36982271dad9531d5f2da6b5280ab769940951b8105a14dc1a47f84c4ec"

    // ---- §61.87 / §61.88 B2 路线事实（另一条路线，只登记）----
    const val F3_B2_PASS: Int = 14
    const val F3_B2_FAIL: Int = 8
    const val F3_B2_NO_SAMPLE: Int = 3
    const val M2_PASS_BASELINE_A: Int = 11
    const val B2_M2_GATE_PASSED: Boolean = true
    const val B2_M3_GATE_PASSED: Boolean = false
    const val F4_STATUS: String = "BLOCKED"
    const val LATEST_CHAPTER: String = "61.88"
}
