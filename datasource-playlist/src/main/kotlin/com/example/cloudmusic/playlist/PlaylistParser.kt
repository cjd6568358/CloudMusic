package com.example.cloudmusic.playlist

import com.example.cloudmusic.common.CloudMusicError
import java.nio.charset.Charset

/** docs/03-03 §3.2 · 播放列表条目与解析结果。 */
const val MAX_DEPTH = 5

sealed interface PlaylistEntry {
    val titleHint: String?
    val durationSec: Long?

    data class Relative(
        val path: String,
        override val titleHint: String? = null,
        override val durationSec: Long? = null,
    ) : PlaylistEntry

    data class Absolute(
        val path: String,
        override val titleHint: String? = null,
        override val durationSec: Long? = null,
    ) : PlaylistEntry

    data class Url(
        val url: String,
        override val titleHint: String? = null,
        override val durationSec: Long? = null,
    ) : PlaylistEntry

    data class Unsupported(val raw: String, val reason: String) : PlaylistEntry {
        override val titleHint: String? = null
        override val durationSec: Long? = null
    }
}

sealed interface PlaylistParseResult {
    data class Ok(val entries: List<PlaylistEntry>) : PlaylistParseResult

    /** 整份拒绝（HLS）。 */
    data class Rejected(val reason: String) : PlaylistParseResult
}

/** B16：先 UTF-8，出现替换字符则回落 GBK；再失败抛 E_DS_ENCODING，由扫描器跳过该文件。 */
fun decodePlaylistText(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    if ('\uFFFD' !in utf8) return utf8
    val gbk = runCatching { String(bytes, Charset.forName("GBK")).removePrefix("\uFEFF") }
        .getOrElse { throw CloudMusicError.DsEncoding("播放列表编码无法识别") }
    if ('\uFFFD' in gbk) throw CloudMusicError.DsEncoding("播放列表编码无法识别")
    return gbk
}

/**
 * `.m3u8` 在本项目是 UTF-8 文本播放列表，不是 HLS；出现 `#EXT-X-*` 即整份拒绝（B14）。
 * m3u 无 `#EXTM3U` 头仍按纯路径列表解析（B08）。
 */
fun parsePlaylist(fileName: String, text: String): PlaylistParseResult {
    val clean = text.removePrefix("\uFEFF")
    if (clean.lineSequence().any { it.trimStart().startsWith("#EXT-X-", ignoreCase = true) }) {
        return PlaylistParseResult.Rejected("HLS 播放列表不在支持范围")
    }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return PlaylistParseResult.Ok(if (ext == "pls") parsePls(clean) else parseM3u(clean))
}

private fun parseM3u(text: String): List<PlaylistEntry> {
    val out = mutableListOf<PlaylistEntry>()
    var duration: Long? = null
    var title: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = line.substringAfter(':')
                // -1 = 时长未知（B09）
                duration = body.substringBefore(',').trim().toLongOrNull()?.takeIf { it >= 0 }
                title = body.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
            }
            continue
        }
        out += classifyLine(line, title, duration)
        duration = null
        title = null
    }
    return out
}

private fun parsePls(text: String): List<PlaylistEntry> {
    val files = mutableMapOf<Int, String>()
    val titles = mutableMapOf<Int, String>()
    val lengths = mutableMapOf<Int, Long>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        val index = key.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull() ?: continue
        when {
            key.startsWith("file") -> files[index] = value
            key.startsWith("title") -> titles[index] = value
            key.startsWith("length") -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { lengths[index] = it }
        }
    }
    // NumberOfEntries 只作校验，不截断；按实际出现的序号解析（T09）
    return files.keys.sorted().map { i -> classifyLine(files.getValue(i), titles[i], lengths[i]) }
}

private fun classifyLine(line: String, titleHint: String?, durationSec: Long?): PlaylistEntry {
    val scheme = line.substringBefore(':', "").lowercase()
    if ("://" in line && scheme !in setOf("http", "https")) {
        return PlaylistEntry.Unsupported(line, "不支持的 scheme: $scheme")
    }
    val ext = line.substringAfterLast('.', "").substringBefore('?').lowercase()
    if (ext == "ts" || ext == "m4s") {
        return PlaylistEntry.Unsupported(line, "HLS 分片不在支持范围")
    }
    return when {
        scheme == "http" || scheme == "https" -> PlaylistEntry.Url(line, titleHint, durationSec)
        line.startsWith("/") || (line.length >= 2 && line[1] == ':' && line[0].isLetter()) ->
            PlaylistEntry.Absolute(line, titleHint, durationSec)
        else -> PlaylistEntry.Relative(line, titleHint, durationSec)
    }
}
