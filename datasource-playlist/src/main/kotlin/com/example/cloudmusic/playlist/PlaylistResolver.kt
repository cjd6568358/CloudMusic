package com.example.cloudmusic.playlist

import com.example.cloudmusic.datasource.PathRule
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.strm.StrmParseResult
import com.example.cloudmusic.strm.parseStrm

/** 播放列表展开所需的只读能力，由 PlaylistDriver 委托底层数据源实现；测试可注入 fake。 */
interface PlaylistSource {
    suspend fun readText(path: String): String?
    suspend fun find(path: String): Obj?
}

sealed interface ResolvedTarget {
    data class Url(val url: String) : ResolvedTarget
    data class File(val path: String) : ResolvedTarget
    data class Rejected(val reason: String) : ResolvedTarget
}

/** docs/03-03 §3.3：基准是播放列表文件自身父目录，归一化后必须仍在 Storage 根内。 */
fun resolveEntry(entry: PlaylistEntry, baseDir: String, rootPath: String): ResolvedTarget = when (entry) {
    is PlaylistEntry.Url -> ResolvedTarget.Url(entry.url)
    is PlaylistEntry.Unsupported -> ResolvedTarget.Rejected(entry.reason)
    is PlaylistEntry.Relative, is PlaylistEntry.Absolute -> {
        val raw = when (entry) {
            is PlaylistEntry.Relative -> "$baseDir/${entry.path}"
            is PlaylistEntry.Absolute -> entry.path
            else -> error("unreachable")
        }
        runCatching {
            val normalized = PathRule.normalizePath(raw)
            PathRule.requirePathWithin(rootPath, normalized)
            ResolvedTarget.File(normalized) as ResolvedTarget
        }.getOrElse { ResolvedTarget.Rejected("路径越界或非法: $raw") }
    }
}

/**
 * docs/03-03 §3.4：递归展开、单列表内去重、循环引用跳过、深度上限 5。
 * 跨播放列表的重复曲目允许共存（PRD §13 #14），distinctBy 只作用于单次展开。
 */
suspend fun expandPlaylist(
    file: Obj,
    source: PlaylistSource,
    rootPath: String,
    depth: Int = 0,
    visited: MutableSet<String> = mutableSetOf(),
): List<Obj> {
    if (depth > MAX_DEPTH) return listOf(mark(file, "播放列表嵌套过深"))
    if (!visited.add(file.path)) return emptyList()
    val text = source.readText(file.path) ?: return listOf(mark(file, "播放列表内容不可读"))
    val baseDir = file.path.substringBeforeLast('/', "")
    val parsed = parsePlaylist(file.name, text)
    if (parsed is PlaylistParseResult.Rejected) return listOf(mark(file, parsed.reason))
    val entries = (parsed as PlaylistParseResult.Ok).entries
    return entries.flatMap { entry ->
        when (val target = resolveEntry(entry, baseDir, rootPath)) {
            is ResolvedTarget.Url -> listOf(
                Obj(name = urlName(target.url), path = target.url, priv = "playlist:url|${target.url}"),
            )
            is ResolvedTarget.Rejected -> listOf(mark(file, target.reason))
            is ResolvedTarget.File -> expandTarget(target.path, file, source, rootPath, depth, visited)
        }
    }.distinctBy { it.path }
}

private suspend fun expandTarget(
    path: String,
    origin: Obj,
    source: PlaylistSource,
    rootPath: String,
    depth: Int,
    visited: MutableSet<String>,
): List<Obj> {
    val ext = path.substringAfterLast('.', "").lowercase()
    val target = source.find(path)
        ?: return listOf(mark(origin, "条目指向的文件不存在: $path"))
    return when (ext) {
        "strm" -> {
            val text = source.readText(path)
            when (val r = text?.let { parseStrm(it) }) {
                is StrmParseResult.Url -> listOf(target.copy(priv = "playlist:url|${r.url}"))
                else -> listOf(mark(origin, "strm 内容无效: $path"))
            }
        }
        "m3u", "m3u8", "pls" -> expandPlaylist(target, source, rootPath, depth + 1, visited)
        else -> listOf(target)
    }
}

private fun mark(obj: Obj, reason: String): Obj = obj.copy(priv = "playlist:unsupported|$reason")

private fun urlName(url: String): String =
    url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotEmpty() } ?: "网络曲目"

/** 供上层查询被标记的原因（不删条目）。 */
fun Obj.playlistRejection(): String? = priv
    ?.takeIf { it.startsWith("playlist:unsupported|") }
    ?.removePrefix("playlist:unsupported|")

fun Obj.playlistDirectUrl(): String? = priv
    ?.takeIf { it.startsWith("playlist:url|") }
    ?.removePrefix("playlist:url|")
