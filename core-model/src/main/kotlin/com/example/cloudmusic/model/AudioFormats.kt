package com.example.cloudmusic.model

/**
 * 全项目音频格式的**单一真相源**（`12 §61.45` / `docs/17 §8` 的 F1）。
 *
 * F1 之前 6 处各自定义白名单，互不一致（14 / 8 / 9 / 9 / 7 / 7，
 * 见 `analysis/l3-audio-format-audit.json`）：扫描器与 strm 会直接把
 * `aiff / ape / dff / dsf / tak / wv` 挡在曲库之外，三处 MIME 推断又各自硬编码。
 * 本对象把「扩展名集合」与「扩展名 → MIME」收敛到一处，6 处一律引用本对象，
 * 不再各自维护字面量清单。
 *
 * **客观边界（§61.305 K2b 换轨）**：自控双源 = `engine-opensource/src/main/jni/build_ffmpeg.sh`
 * 的 `ENABLED_DECODERS`（ffmpeg 软解集）**∪** Media3 内建 Extractors + 四手写提取器
 * （`Ape/Asf/Aiff/Wavpack`，容器层）。原「以逆向产物编入集为准」口径随 Kernel 2.0 K4
 * 逆向清退退役（§61.299 R1 残留项）。本对象**不声称"全格式已支持"** —— 纳入
 * [AUDIO_EXT] 只表示「允许进入扫描 / 取流链路」，能否真机播放由 F3 的
 * 「逐格式 × REMOTE/LOCAL 双路径」矩阵判定（复跑挂 K-01 真机窗）。
 *
 * 纯 Kotlin + JDK、零 Android / 零第三方依赖，故所有模块（含 `:scanner` /
 * `:datasource-strm` 经 `:datasource-api` 的 `api` 传递）均可直接引用，
 * **不新增任何 Gradle 依赖、不新增模块**。
 */
object AudioFormats {

    /**
     * 曲库收录的音频扩展名（小写、无前导点）。
     * 与 F1 前 `ObjectType.AUDIO_EXT` 的 **14 项**逐字一致 —— F1 只统一来源，
     * **不擅自扩大收录集**，是否补 `alac` 等留待 F3 真机矩阵给结论。
     */
    val AUDIO_EXT: Set<String> = linkedSetOf(
        "mp3", "flac", "ogg", "m4a", "wav", "opus", "wma", "aac",
        "ape", "dsf", "dff", "aiff", "wv", "tak",
    )

    /**
     * 已知 MIME、但**暂不纳入曲库收录**的音频扩展名。
     *
     * `alac`（Apple Lossless）在 F1 前的 `StrmPaths.defaultFilterTypes` 与 `MimeHint.MAP`
     * 中已存在，ffmpeg 软解集亦含 `alac` decoder（`FfmpegLibrary` 标准映射）；但 F1 前
     * 的 14 项收录集不含它，且 `.alac` 裸流能否真机播放**未经 F3 验证**。故 F1 保持
     * 「可推断 MIME / 可 strm 过滤」，不把行为回退掉，也不擅自扩收录集。
     * 是否并入 [AUDIO_EXT] 由 F3 决定。
     */
    val EXTRA_AUDIO_EXT: Set<String> = linkedSetOf("alac")

    /** 文本 / 播放列表项：不是音频，但需要 MIME 映射（`03-03 §3.5` / `10 §2 R14`）。 */
    val TEXT_MIME_BY_EXT: Map<String, String> = linkedMapOf(
        "m3u8" to "application/vnd.apple.mpegurl",
        "lrc" to "text/plain",
    )

    /** 未知扩展名的兜底 MIME，不猜测。 */
    const val OCTET_STREAM = "application/octet-stream"

    /**
     * 扩展名 → MIME 的**唯一映射表**。
     *
     * 口径沿用 `03-06 §7` 的 `MimeHint.MAP`（11 项 = 9 音频 + 2 文本，条目最全且是
     * 契约文档口径）：`aac` → `audio/aac`（裸 ADTS 流）、`m4a` → `audio/mp4`（MP4 容器）。
     * F1 前 `PlayerController.mimeHintOf` / `DidlLiteBuilder.mimeOf` 把 `aac` 也写成
     * `audio/mp4`，属 6 处之间的口径分歧；F1 统一到本表，`.aac` 的 MIME 由
     * `audio/mp4` 变为 `audio/aac`（行为变更已登记于 `12 §61.45`，待 F3 真机复核）。
     *
     * §61.305（K2b）MIME 赎回（「不编造」口径到期，自控证据赎回三格）：
     * `ape→audio/x-ape`（`ApeExtractor` 样例输出 + `FfmpegLibrary` 映射 +
     * `MediaCodecSelector` 排除口径同值）、`aiff→audio/x-aiff`（`AiffExtractor`
     * 同证）、`wv→audio/x-wavpack`（`WavpackExtractor` 同证）——三者均为
     * **自控提取器的在册样例 MIME**，非竞品产物、非猜测。`dsf/dff/tak` 无容器层
     * 实现与实机 Content-Type 证据，**维持 [OCTET_STREAM] 占位**（不编造原则不破；
     * 回填待 DSD 解码链接通 / tak 提取器立项 + K-01 真机窗 Content-Type 实测）。
     */
    val MIME_BY_EXT: Map<String, String> = linkedMapOf(
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "wma" to "audio/x-ms-wma",
        "alac" to "audio/x-alac",
        "opus" to "audio/ogg",
        "ape" to "audio/x-ape",
        "aiff" to "audio/x-aiff",
        "wv" to "audio/x-wavpack",
    ) + TEXT_MIME_BY_EXT

    /**
     * strm 过滤类型：曲库音频集 + 已知额外音频扩展名。
     * 保持 F1 前的 `alac` 支持**不回退**，同时补上 6 种此前被误过滤的格式。
     */
    val STREAM_FILTER_EXT: Set<String> = LinkedHashSet(AUDIO_EXT).apply {
        addAll(EXTRA_AUDIO_EXT)
    }

    /** 供 `StrmPaths.defaultFilterTypes` 使用：逗号分隔、由真相源派生、顺序稳定。 */
    val STREAM_FILTER_TYPES: String = STREAM_FILTER_EXT.joinToString(",")

    /** 是否属于曲库收录的音频扩展名（大小写不敏感）。 */
    fun isAudio(ext: String): Boolean = ext.lowercase() in AUDIO_EXT

    /**
     * 从文件名 / URL / 本地路径取小写扩展名：去 query → 取路径末段 → 取最后一个点之后。
     * `https://x.y.com/a.MP3?token=1` → `mp3`；`/a.flac` → `flac`；`x` → `""`。
     */
    fun extOf(nameOrUrl: String): String = nameOrUrl
        .substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()

    /** 按扩展名推断 MIME；未知一律 [OCTET_STREAM]。 */
    fun mimeOf(ext: String): String = MIME_BY_EXT[ext.lowercase()] ?: OCTET_STREAM

    /** 从完整名称 / URL 直接推断 MIME。 */
    fun mimeOfName(nameOrUrl: String): String = mimeOf(extOf(nameOrUrl))

    /**
     * MIME → 扩展名的逆查表（`12 §61.48` / F2-b）。
     *
     * 由 [MIME_BY_EXT] **派生**，绝不另立白名单：同一 MIME 有多个扩展名时
     * （`audio/ogg` → `ogg` / `opus`）取首个登记项，保持顺序稳定、不猜测。
     */
    private val EXT_BY_MIME: Map<String, String> = run {
        val inverse = LinkedHashMap<String, String>()
        for ((ext, mime) in MIME_BY_EXT) inverse.putIfAbsent(mime, ext)
        inverse
    }

    /**
     * `12 §61.48` / F2-b：为 native `ResourceInfo.extension` 生成**真实扩展名**，
     * 而不是把 MIME 字符串（`audio/mpeg`）塞进去。
     *
     * `§61.47` 真机实证：LOCAL 源 `url == null` 时旧链落到 `source.mimeHint`，
     * 日志为 `resourceInfo extension=audio/mpeg fileSize=-1`；native 虽经
     * `XorLocalSource` 内容探测仍能播 mp3，但语义错配真实存在，且其它格式可能
     * 依赖 `extension` 选择 demuxer（由 F3 逐格式矩阵判定，**不预断**）。
     * 历史 A3 已有 `extension=mp3` 成功日志（`a3-phase-ab-evidence §E4`），
     * 故传真实扩展名不是新猜测。
     *
     * 优先级：URL 后缀 → LOCAL 路径真实后缀 → `mimeHint` 反查已登记 MIME → 空串。
     * 只接受 1..5 位字母 / 数字的小写扩展名，避免把域名片段、长 token、
     * `.gitignore` 之类误当扩展名；未知 MIME 一律返回空串（**不编造、不回传 MIME**）。
     *
     * 纯 JVM、零 Android / 零第三方依赖；`:engine-legacy` 已有
     * `implementation(project(":core-model"))`，调用它不新增任何 Gradle 依赖。
     */
    fun extOfSource(url: String?, localPath: String?, mimeHint: String?): String {
        for (candidate in listOfNotNull(url, localPath)) {
            val ext = extOf(candidate)
            if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) {
                return ext
            }
        }
        val mime = mimeHint?.trim().orEmpty()
        if (mime.isNotEmpty() && mime != OCTET_STREAM) {
            EXT_BY_MIME[mime]?.let { return it }
        }
        return ""
    }
}
