package com.example.cloudmusic.strm

/** docs/03-03 §2.3 · 借鉴 OpenList 的 paths 映射 / autoFlatten，不照搬虚拟文件生成。 */

const val defaultFilterTypes = "mp3,flac,aac,wav,ogg,m4a,wma,alac,opus"
const val defaultDownloadTypes = "ass,srt,vtt,sub,strm,lrc"

data class StrmPaths(
    /** key → 多个 dst（同 key 多源合并） */
    val groups: Map<String, List<String>>,
    /** 只有 1 个 key 时根目录直接展开 dst 内容 */
    val autoFlatten: Boolean,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
    val rootKeys: List<String> get() = groups.keys.sorted()
    val allDestinations: List<String> get() = groups.values.flatten().distinct()
}

/** 小写、去空白、去前导点（03-03 T07）。 */
fun normalizeExtList(raw: String): Set<String> =
    raw.split(',', ';', '\n')
        .map { it.trim().lowercase().removePrefix(".") }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * 每行 `key:dst` 或裸 `dst`（key = dst 的 base 名）；同 key 合并；单 key → autoFlatten=true。
 * Windows 盘符 `C:/...` 的冒号不当作 key 分隔符。
 */
fun parsePaths(raw: String): StrmPaths {
    val groups = linkedMapOf<String, MutableList<String>>()
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val colon = line.indexOf(':')
            val isDriveLetter = colon == 1 && line[0].isLetter() &&
                line.getOrNull(2) in listOf('/', '\\')
            val (key, dst) = if (colon > 0 && !isDriveLetter) {
                line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            } else {
                val d = line.trimEnd('/', '\\')
                d.substringAfterLast('/').ifEmpty { d } to d
            }
            if (dst.isNotEmpty()) groups.getOrPut(key) { mutableListOf() }.add(dst)
        }
    val merged = groups.mapValues { (_, v) -> v.distinct() }
    return StrmPaths(merged, merged.size == 1)
}
