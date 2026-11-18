package com.example.cloudmusic.scanner

/**
 * docs/03-04 §5 V0.2 · CUE sheet 解析（§61.221，纯 JVM 零依赖）。
 *
 * 口径（与 `analysis/review-2026-11-18-v02-dev-plan.md` M5 一致）：
 * - 只收 `TRACK nn AUDIO`（MODE1/2 等非音频轨跳过并计入 errors，不产分轨）；
 * - 分轨起点 = 本轨 `INDEX 01`；`INDEX 00`（pregap）不并入起点，V0.2 最小闭环如实登记；
 * - 分轨终点 = 同一 FILE 内下一轨的 `INDEX 01`；跨 FILE 或末轨 = 0（无界，播到本 FILE 自然结束）；
 * - 多 FILE 语义：INDEX 相对各自 FILE 起点；每 FILE 只关联一个音频文件（basename 匹配由展开器负责）；
 * - 非法行宽松跳过（REM / FLAGS / ISRC / CATALOG / PREGAP / POSTGAP / CDTEXTFILE 等）；
 * - 时间 `MM:SS:FF`（75 帧/秒）→ 毫秒向下取整。
 */
data class CueSheet(
    val albumTitle: String?,
    val albumPerformer: String?,
    val tracks: List<CueTrack>,
    val errors: List<String>,
) {
    fun hasAudioTrack(): Boolean = tracks.any { it.audio }
}

/** 分轨描述：TRACK 语句内的轨道级元数据（展开器据此构造 Candidate）。 */
data class CueTrack(
    /** TRACK nn 的十进制序号（01→1）。 */
    val no: Int,
    val title: String?,
    /** TRACK 内 PERFORMER；缺省由展开器回落到 [CueSheet.albumPerformer]。 */
    val performer: String?,
    /** 所属 FILE 名（引号内原样，相对 cue 所在目录）。 */
    val file: String?,
    /** INDEX 01 起点（毫秒；相对本 FILE）。 */
    val startMs: Long,
    val audio: Boolean,
)

/** CUE 文本 → [CueSheet]；宽松解析，解析永不抛异常（非法行进 errors）。 */
object CueParser {

    private const val FRAMES_PER_SECOND = 75

    fun parse(text: String): CueSheet {
        val text = text.removePrefix("﻿")
        var albumTitle: String? = null
        var albumPerformer: String? = null
        val tracks = mutableListOf<CueTrack>()
        val errors = mutableListOf<String>()

        var currentFile: String? = null
        var trackNo: Int? = null
        var trackTitle: String? = null
        var trackPerformer: String? = null
        var trackAudio = false
        var startIndex: Long? = null

        fun flush() {
            val no = trackNo
            if (no != null) {
                if (startIndex == null) {
                    errors += "TRACK $no 缺少 INDEX 01，已跳过"
                } else if (!trackAudio) {
                    errors += "TRACK $no 非音频轨（非 AUDIO），已跳过"
                } else {
                    tracks += CueTrack(
                        no = no,
                        title = trackTitle,
                        performer = trackPerformer,
                        file = currentFile,
                        startMs = startIndex ?: 0L,
                        audio = true,
                    )
                }
            }
            trackNo = null
            trackTitle = null
            trackPerformer = null
            trackAudio = false
            startIndex = null
        }

        text.lineSequence().forEachIndexed { lineIdx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("REM", ignoreCase = true)) return@forEachIndexed
            val upper = line.uppercase()
            when {
                upper.startsWith("FILE") -> {
                    flush()   // FILE 切换即结束上一轨上下文
                    currentFile = quotedValue(line)
                    if (currentFile == null) errors += "第 ${lineIdx + 1} 行 FILE 缺少文件名，已跳过"
                }
                upper.startsWith("TRACK") -> {
                    flush()
                    val parts = line.split(Regex("\\s+"), limit = 3)
                    trackNo = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (trackNo == null) {
                        errors += "第 ${lineIdx + 1} 行 TRACK 序号非法，已跳过"
                    } else {
                        // TRACK 后的类型子句（AUDIO / MODE1/2352 ...）；无类型子句按 AUDIO 宽松收
                        val kind = parts.getOrNull(2)?.trim()?.uppercase()
                        trackAudio = kind == null || kind == "AUDIO"
                    }
                }
                upper.startsWith("INDEX") -> {
                    val parts = line.split(Regex("\\s+"), limit = 3)
                    val type = parts.getOrNull(1)?.trim()
                    val mmssff = parts.getOrNull(2)?.trim()
                    if (type == "01" && mmssff != null) {
                        val ms = mmssff.toMsOrNull()
                        if (ms == null) errors += "第 ${lineIdx + 1} 行 INDEX 01 时间格式非法，已跳过"
                        else startIndex = ms
                    }
                    // INDEX 00（pregap）/ 02+ 忽略（口径见 KDoc）
                }
                upper.startsWith("TITLE") -> {
                    val v = quotedValue(line)
                    if (trackNo != null) trackTitle = v else if (albumTitle == null) albumTitle = v
                }
                upper.startsWith("PERFORMER") -> {
                    val v = quotedValue(line)
                    if (trackNo != null) trackPerformer = v else if (albumPerformer == null) albumPerformer = v
                }
                // PREGAP / POSTGAP / FLAGS / ISRC / CATALOG / CDTEXTFILE / SONGWRITER 等 → 宽松忽略
            }
        }
        flush()
        return CueSheet(albumTitle, albumPerformer, tracks.toList(), errors.toList())
    }

    /** `KEY "value" ...` 形式的引号内值；无引号取 KEY 后的第一个裸 token（FILE a.flac WAVE）。 */
    private fun quotedValue(line: String): String? {
        val first = line.indexOf('"')
        if (first < 0) return line.split(Regex("\\s+")).getOrNull(1)?.takeIf { it.isNotEmpty() }
        val last = line.lastIndexOf('"')
        if (last <= first) return null
        return line.substring(first + 1, last).takeIf { it.isNotEmpty() }
    }

    /** `MM:SS:FF` → 毫秒；FF 为 1/75 秒帧（向下取整）。非法返回 null。 */
    private fun String.toMsOrNull(): Long? {
        val parts = split(":")
        if (parts.size != 3) return null
        val mm = parts[0].toIntOrNull() ?: return null
        val ss = parts[1].toIntOrNull() ?: return null
        val ff = parts[2].toIntOrNull() ?: return null
        if (mm < 0 || ss < 0 || ss >= 60 || ff < 0 || ff >= FRAMES_PER_SECOND) return null
        return (mm * 60L + ss) * 1000L + ff * 1000L / FRAMES_PER_SECOND
    }
}
