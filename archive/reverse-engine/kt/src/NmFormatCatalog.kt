@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * libffmpeg.so 格式覆盖清单 —— 由 tools/gen_catalog.py 全量生成，勿手改。
 *
 * 证据：analysis/l3-b2-m2-decoder-symbols.json（12 §61.83 M2_PREFLIGHT）。
 * 口径：ELF .dynsym 导出符号**存在性**实证；symtab 已 strip；
 * 导出符号存在 != decoder 可用 != 真机可播（F-08 边界声明原样登记）。
 * 本文件是**数据层全量登记**，不实现 M2、不改 B2 阶段状态、不宣称 F4 已解决。
 */

data class NmLibFingerprint(
    val abi: String,
    val bytes: Long,
    val sha256: String,
    val elfClass: Int,
    val eMachine: Int,
    val sectionCount: Int,
    val hasSymtab: Boolean,
    val dynsymCount: Int,
    val dynsymNonemptyNames: Int,
    val decoderSymbolCount: Int,
    val demuxerSymbolCount: Int,
)

data class NmFormatQuery(
    val query: String,
    val decoderSymbolExact: Boolean,
    val demuxerSymbolExact: Boolean,
    val decoderFamilyMatches: List<String>,
    val demuxerFamilyMatches: List<String>,
    val symbolVerdict: String,
)

object NmFormatCatalog {
    // @spec F-01：100 个 ff_*_decoder 导出符号（全量）
    const val DECODER_SYMBOL_COUNT: Int = 100

    // @spec F-02：75 个 ff_*_demuxer 导出符号（全量）
    const val DEMUXER_SYMBOL_COUNT: Int = 75

    // @spec F-03 / F-04：双 ABI 完全一致；symtab 已 strip，仅覆盖 .dynsym
    const val CROSS_ABI_IDENTICAL: Boolean = true
    const val SYMTAB_STRIPPED: Boolean = true
    const val CHECKS_TOTAL: Int = 18
    const val CHECKS_PASS: Int = 18
    const val ALL_PASS: Boolean = true
    val EVIDENCE_LEVEL: String = "ELF_exported_dynsym_symbol"
    val PRIOR_EVIDENCE_LEVEL: String = "binary_printable_string_scan (12 §61.44)"
    val VERDICT: String = "M2_PREFLIGHT_SYMBOL_EVIDENCE_UPGRADE__M2_GATE_NOT_PASSED__F4_BLOCKED：承接 §61.82 留下的「9 个格式 decoder 编入未确认」缺口，本批纯离线用手写 ELF32/64 解析器读 libffmpeg.so 的 .dynsym 导出符号表，把证据等级从 §61.44 的「二进制可打印字符串扫描」提升到「ELF 导出符号」。arm64-v8a 与 armeabi-v7a 两个 ABI 各自恰好 100 个 ff_*_decoder + 75 个 ff_*_demuxer 导出符号，集合完全一致，且与 §61.44 的 100/75 计数及清单吻合；两份 so 的 .symtab 均已被 strip，但这些 AVCodec 全局变量作为导出符号保留在 .dynsym，故符号表级实证成立。9 个存疑格式与 15 个候选格式已逐项给出精确符号存在性与族匹配的二值结果（见 JSON）。铁律不变：导出符号存在 ≠ decoder 可用 ≠ 真机可播；逐格式可播仍需 F3 REMOTE/LOCAL 双路径真机矩阵（NOT RUN）。本批不产出 B2 扩展产物、不改 P0 源码、不动解码器、不连 adb / 不联网 / 不下载，M2 阶段门禁未通过、M2 未开工、未完成。F4 仍 BLOCKED，§61.75 终局裁决不变。符号表补证 ≠ M2 完成。"

    // 两个 ABI 的 ELF 指纹（不含工程路径，仅登记可核验的元数据）
    val LIBRARIES: Map<String, NmLibFingerprint> = mapOf(
        "arm64-v8a" to NmLibFingerprint("arm64-v8a", 6658592L, "a527e23288f5281cc899a2e0756fe4843d7b9ec7d81e6f8c2ce8b313f51e83d2", 64, 183, 23, false, 3753, 3750, 100, 75),
        "armeabi-v7a" to NmLibFingerprint("armeabi-v7a", 5340888L, "f73dc5adef4aa0e5d39036aefed7547e535c7a9fe956d051b4ff71002e69b388", 32, 40, 26, false, 3875, 3874, 100, 75),
    )

    // @spec F-01：全量 decoder 符号
    val DECODER_SYMBOLS: List<String> = listOf(
        "aac",
        "aac_latm",
        "ac4_mediacodec",
        "adpcm_4xm",
        "adpcm_afc",
        "adpcm_agm",
        "adpcm_aica",
        "adpcm_argo",
        "adpcm_ct",
        "adpcm_dtk",
        "adpcm_ea",
        "adpcm_ea_maxis_xa",
        "adpcm_ea_r1",
        "adpcm_ea_r2",
        "adpcm_ea_r3",
        "adpcm_ea_xas",
        "adpcm_ima_alp",
        "adpcm_ima_amv",
        "adpcm_ima_apc",
        "adpcm_ima_apm",
        "adpcm_ima_cunning",
        "adpcm_ima_dat4",
        "adpcm_ima_dk3",
        "adpcm_ima_dk4",
        "adpcm_ima_ea_eacs",
        "adpcm_ima_ea_sead",
        "adpcm_ima_iss",
        "adpcm_ima_mtf",
        "adpcm_ima_oki",
        "adpcm_ima_qt",
        "adpcm_ima_rad",
        "adpcm_ima_smjpeg",
        "adpcm_ima_ssi",
        "adpcm_ima_wav",
        "adpcm_ima_ws",
        "adpcm_ms",
        "adpcm_mtaf",
        "adpcm_psx",
        "adpcm_sbpro_2",
        "adpcm_sbpro_3",
        "adpcm_sbpro_4",
        "adpcm_swf",
        "adpcm_thp",
        "adpcm_thp_le",
        "adpcm_xa",
        "adpcm_yamaha",
        "adpcm_zork",
        "alac",
        "amrnb",
        "amrwb",
        "ape",
        "dca",
        "dsd_lsbf",
        "dsd_lsbf_planar",
        "dsd_msbf",
        "dsd_msbf_planar",
        "eac3_mediacodec",
        "flac",
        "flv",
        "h263",
        "h263i",
        "h263p",
        "h264",
        "h264_mediacodec",
        "hevc",
        "hevc_mediacodec",
        "init_cabac",
        "mp2",
        "mp3",
        "mpeg4",
        "opus",
        "pcm_alaw",
        "pcm_f32le",
        "pcm_f64le",
        "pcm_mulaw",
        "pcm_s16le",
        "pcm_s24be",
        "pcm_s24le",
        "pcm_s32le",
        "pcm_s8",
        "pcm_u8",
        "png",
        "theora",
        "vorbis",
        "vp3",
        "vp56_init_range",
        "vp6",
        "vp6a",
        "vp6f",
        "vp7",
        "vp8_mediacodec",
        "vp9_mediacodec",
        "wmalossless",
        "wmapro",
        "wmav1",
        "wmav2",
        "wmavoice",
        "xma1",
        "xma2",
        "yuv4",
    )

    // @spec F-02：全量 demuxer 符号
    val DEMUXER_SYMBOLS: List<String> = listOf(
        "aac",
        "amr",
        "ape",
        "asf",
        "av3a",
        "avi",
        "dsf",
        "dts",
        "flac",
        "flv",
        "h263",
        "h264",
        "hevc",
        "image2",
        "image2pipe",
        "image_bmp_pipe",
        "image_dds_pipe",
        "image_dpx_pipe",
        "image_exr_pipe",
        "image_gif_pipe",
        "image_j2k_pipe",
        "image_jpeg_pipe",
        "image_jpegls_pipe",
        "image_pam_pipe",
        "image_pbm_pipe",
        "image_pcx_pipe",
        "image_pgm_pipe",
        "image_pgmyuv_pipe",
        "image_pictor_pipe",
        "image_png_pipe",
        "image_ppm_pipe",
        "image_psd_pipe",
        "image_qdraw_pipe",
        "image_sgi_pipe",
        "image_sunrast_pipe",
        "image_svg_pipe",
        "image_tiff_pipe",
        "image_webp_pipe",
        "image_xpm_pipe",
        "image_xwd_pipe",
        "kux",
        "live_flv",
        "m4v",
        "matroska",
        "mov",
        "mp3",
        "mpegps",
        "mpegts",
        "mpegtsraw",
        "mpegvideo",
        "ogg",
        "pcm_alaw",
        "pcm_f32be",
        "pcm_f32le",
        "pcm_f64be",
        "pcm_f64le",
        "pcm_mulaw",
        "pcm_s16be",
        "pcm_s16le",
        "pcm_s24be",
        "pcm_s24le",
        "pcm_s32be",
        "pcm_s32le",
        "pcm_s8",
        "pcm_u16be",
        "pcm_u16le",
        "pcm_u24be",
        "pcm_u24le",
        "pcm_u32be",
        "pcm_u32le",
        "pcm_u8",
        "pcm_vidc",
        "sln",
        "wav",
        "webm_dash_manifest",
    )

    // @spec F-05：9 个「decoder 编入未确认」格式查询
    val UNCONFIRMED_9: List<NmFormatQuery> = listOf(
        NmFormatQuery("tak", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("tta", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("wavpack", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("dsf", false, true, emptyList(), listOf("dsf"), "DEMUXER_SYMBOL_ONLY"),
        NmFormatQuery("dff", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("aiff", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("shorten", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("truehd", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("mlp", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
    )

    // @spec F-06：15 个候选格式查询
    val CANDIDATE_15: List<NmFormatQuery> = listOf(
        NmFormatQuery("mp3", true, true, listOf("mp3"), listOf("mp3"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("flac", true, true, listOf("flac"), listOf("flac"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("ogg", false, true, emptyList(), listOf("ogg"), "DEMUXER_SYMBOL_ONLY"),
        NmFormatQuery("m4a", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("wav", false, true, listOf("adpcm_ima_wav"), listOf("wav"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("opus", true, false, listOf("opus"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wma", false, false, listOf("wmalossless", "wmapro", "wmav1", "wmav2", "wmavoice"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("aac", true, true, listOf("aac", "aac_latm"), listOf("aac"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("ape", true, true, listOf("ape"), listOf("ape"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("dsf", false, true, emptyList(), listOf("dsf"), "DEMUXER_SYMBOL_ONLY"),
        NmFormatQuery("dff", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("aiff", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("wv", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("tak", false, false, emptyList(), emptyList(), "NO_SYMBOL"),
        NmFormatQuery("alac", true, false, listOf("alac"), emptyList(), "DECODER_SYMBOL_PRESENT"),
    )

    // @spec F-07：19 个 codec 查询
    val CODEC_QUERIES: List<NmFormatQuery> = listOf(
        NmFormatQuery("vorbis", true, false, listOf("vorbis"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("dsd", false, false, listOf("dsd_lsbf", "dsd_lsbf_planar", "dsd_msbf", "dsd_msbf_planar"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("pcm_s16le", true, true, listOf("pcm_s16le"), listOf("pcm_s16le"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wmav1", true, false, listOf("wmav1"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wmav2", true, false, listOf("wmav2"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wmapro", true, false, listOf("wmapro"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wmalossless", true, false, listOf("wmalossless"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("wmavoice", true, false, listOf("wmavoice"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("adpcm_ima_wav", true, false, listOf("adpcm_ima_wav"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("ac3", false, false, listOf("eac3_mediacodec"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("eac3", false, false, listOf("eac3_mediacodec"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("dts", false, true, emptyList(), listOf("dts"), "DEMUXER_SYMBOL_ONLY"),
        NmFormatQuery("mp3", true, true, listOf("mp3"), listOf("mp3"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("aac", true, true, listOf("aac", "aac_latm"), listOf("aac"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("aac_latm", true, false, listOf("aac_latm"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("alac", true, false, listOf("alac"), emptyList(), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("flac", true, true, listOf("flac"), listOf("flac"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("ape", true, true, listOf("ape"), listOf("ape"), "DECODER_SYMBOL_PRESENT"),
        NmFormatQuery("opus", true, false, listOf("opus"), emptyList(), "DECODER_SYMBOL_PRESENT"),
    )

    // @spec F-08：8 条边界声明（原样登记，不作法律/能力结论）
    val BOUNDARIES: List<String> = listOf(
        "本工具只做 ELF .dynsym 导出符号存在性实证，不实现 M2、不产出 FFmpeg 扩展产物、不联网、不连 adb",
        "导出符号存在 ≠ decoder 可用 ≠ 真机可播；逐格式可播结论只能由 F3 REMOTE/LOCAL 双路径真机矩阵给出（NOT RUN）",
        "族匹配（子串包含）只是机械检索辅助，不是「该格式由该 decoder 解码」的能力断言",
        ".symtab 已被 strip，本证据只覆盖 .dynsym 导出符号；未导出且被 strip 的实现不在本证据范围内，不作「未编入」的绝对断言",
        "§61.82 第 3 节「有 demuxer 或字符串痕迹但未确认 decoder 编入」按本批符号表结果只追加批注、不改历史正文",
        "M0/M1/M2 前置历史证据（§61.50~§61.82）冻结不改；本批不重跑历史工具",
        "R7 / GPL v2+ 边界只登记不作法律结论；不引 media3-session、18 个 so 暂不移除、不动解码器",
        "符号表补证 ≠ M2 完成，M2 门禁未通过不得进入 M3；F4 仍 BLOCKED、§61.75 终局裁决不变",
    )

    fun hasDecoder(symbol: String): Boolean = DECODER_SYMBOLS.contains(symbol)
    fun hasDemuxer(symbol: String): Boolean = DEMUXER_SYMBOLS.contains(symbol)

    fun queriesWithVerdict(verdict: String): List<NmFormatQuery> =
        (UNCONFIRMED_9 + CANDIDATE_15 + CODEC_QUERIES).filter { it.symbolVerdict == verdict }
}
