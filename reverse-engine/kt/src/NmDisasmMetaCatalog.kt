@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * 【生成物，勿手改】U 组：A3 阶段反汇编 + 机读反汇编 JSON 的全量元数据登记。
 * 由 tools/gen_a3_meta_manifest.py 从 7 份一手证据生成，
 * 受 spec/a3-meta-manifest.json 的源 sha256 守护，audit.py 第 13/14 项每次比对。
 *
 * @spec U-08：5 份机读反汇编 JSON 的独有元数据全量登记（源 sha256 守护）。
 * 口径：本 catalog 只做数据全量登记，不新增控制流翻译；
 * A3 两份 md 坐实的控制流见 NmA3Supplement.kt（U-01~U-07）。
 */
object NmDisasmMetaCatalog {
    const val SOURCE_COUNT: Int = 7

    // ---- A3_STAGE_C ----
    const val A3_STAGE_C_SHA256: String = "0f6970658b9a51c8431858e92929290eb9da8a823083d10d6df68672729c75fb"
    const val A3_STAGE_C_BYTES: Int = 7616
    const val A3_STAGE_C_HEX_ADDR_COUNT: Int = 21
    const val A3_STAGE_C_LINE_COUNT: Int = 154
    const val A3_STAGE_C_FENCE_COUNT: Int = 6
    const val A3_STAGE_C_ASM_LINE_COUNT: Int = 13

    // ---- A3_PHASE_AB ----
    const val A3_PHASE_AB_SHA256: String = "035c965200c4f29b05c01b1263e94dfdc13d78bde10e235b5a0f21fe933976c5"
    const val A3_PHASE_AB_BYTES: Int = 29989
    const val A3_PHASE_AB_HEX_ADDR_COUNT: Int = 18
    const val A3_PHASE_AB_LINE_COUNT: Int = 555
    const val A3_PHASE_AB_FENCE_COUNT: Int = 38
    const val A3_PHASE_AB_ASM_LINE_COUNT: Int = 0

    // ---- MINUS103 ----
    const val MINUS103_SHA256: String = "5361577f3f7cb075eea905ba9ef0386cdf11305def2458789f30134004f048cc"
    const val MINUS103_BYTES: Int = 5461
    const val MINUS103_HEX_ADDR_COUNT: Int = 46
    const val MINUS103_TOP_LEVEL_KEYS: Int = 26
    const val MINUS103_CHECKS_TOTAL: Int = 25
    const val MINUS103_ANCHOR_COUNT: Int = 16
    const val MINUS103_LIST_FIELD_COUNT: Int = 8
    const val MINUS103_ALL_PASS: Int = 1
    const val MINUS103_DEVICE_RUN: Int = 0

    // ---- PLAY_GATE ----
    const val PLAY_GATE_SHA256: String = "86878128b039b6357e5994b30bb16550e6e369e38924bc69cf50a39ff2bd83df"
    const val PLAY_GATE_BYTES: Int = 4753
    const val PLAY_GATE_HEX_ADDR_COUNT: Int = 22
    const val PLAY_GATE_TOP_LEVEL_KEYS: Int = 23
    const val PLAY_GATE_CHECKS_TOTAL: Int = 16
    const val PLAY_GATE_ANCHOR_COUNT: Int = 0
    const val PLAY_GATE_LIST_FIELD_COUNT: Int = 6
    const val PLAY_GATE_ALL_PASS: Int = 1
    const val PLAY_GATE_DEVICE_RUN: Int = 0

    // ---- LEVEL5_SEMANTICS ----
    const val LEVEL5_SEMANTICS_SHA256: String = "c2d98a60173bb6a75ec0affba83ca55a5effd5ae25e41142cc3fc7c9f33037b2"
    const val LEVEL5_SEMANTICS_BYTES: Int = 15596
    const val LEVEL5_SEMANTICS_HEX_ADDR_COUNT: Int = 26
    const val LEVEL5_SEMANTICS_TOP_LEVEL_KEYS: Int = 14
    const val LEVEL5_SEMANTICS_CHECKS_TOTAL: Int = 34
    const val LEVEL5_SEMANTICS_ANCHOR_COUNT: Int = 0
    const val LEVEL5_SEMANTICS_LIST_FIELD_COUNT: Int = 2
    const val LEVEL5_SEMANTICS_ALL_PASS: Int = 1
    const val LEVEL5_SEMANTICS_DEVICE_RUN: Int = 0

    // ---- LOGLEVEL_PROBE ----
    const val LOGLEVEL_PROBE_SHA256: String = "19506b2e991669eed187219688057c17bfd1a06580feabd617deae1367fa153c"
    const val LOGLEVEL_PROBE_BYTES: Int = 5431
    const val LOGLEVEL_PROBE_HEX_ADDR_COUNT: Int = 22
    const val LOGLEVEL_PROBE_TOP_LEVEL_KEYS: Int = 14
    const val LOGLEVEL_PROBE_CHECKS_TOTAL: Int = 19
    const val LOGLEVEL_PROBE_ANCHOR_COUNT: Int = 12
    const val LOGLEVEL_PROBE_LIST_FIELD_COUNT: Int = 6
    const val LOGLEVEL_PROBE_ALL_PASS: Int = 1
    const val LOGLEVEL_PROBE_DEVICE_RUN: Int = 0

    // ---- JNI_ARG ----
    const val JNI_ARG_SHA256: String = "3e4779ca3738807ca426e6322bb01b1c0162e77b7ae8626d7cf8cbb5d6177249"
    const val JNI_ARG_BYTES: Int = 4124
    const val JNI_ARG_HEX_ADDR_COUNT: Int = 35
    const val JNI_ARG_TOP_LEVEL_KEYS: Int = 10
    const val JNI_ARG_CHECKS_TOTAL: Int = 17
    const val JNI_ARG_ANCHOR_COUNT: Int = 0
    const val JNI_ARG_LIST_FIELD_COUNT: Int = 2
    const val JNI_ARG_ALL_PASS: Int = 1
    const val JNI_ARG_DEVICE_RUN: Int = 0

}
