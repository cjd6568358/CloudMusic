package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Track
import java.text.Collator
import java.util.Locale

/**
 * docs/03-05 §8 的展示层规则，全部为**纯函数**，便于在不引入 Room / FTS4 的情况下单测。
 *
 * 纯 JVM 降级说明（与 11 §11.3 的 DOM 替代 XmlPullParser 同性质）：
 * `tracks_fts` 的 MATCH 语义在这里用「空白分词 AND + 字段子串命中」等价实现；
 * 中文不分词，按整串子串匹配。Android 侧换回 FTS4 时，Repository 签名与行为不变。
 */
object LibraryQuery {

    const val FAILURE_LOG_MAX = 1_000
    const val FAILURE_LOG_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    enum class SortKey { TITLE, ARTIST, ALBUM, DURATION }

    /** Collator(Locale.CHINA)，中文按拼音/笔画而非二进制序（§8 / B04 / T05）。 */
    private val zhComparator = Comparator<String> { a, b ->
        Collator.getInstance(Locale.CHINA).compare(a, b)
    }

    fun sort(tracks: List<Track>, key: SortKey = SortKey.TITLE): List<Track> = when (key) {
        SortKey.TITLE -> tracks.sortedWith(compareBy<Track, String>(zhComparator) { it.title }.thenBy { it.id.value })
        SortKey.ARTIST -> tracks.sortedWith(compareBy<Track, String>(zhComparator) { it.artist }.thenBy { it.id.value })
        SortKey.ALBUM -> tracks.sortedWith(compareBy<Track, String>(zhComparator) { it.album }.thenBy { it.id.value })
        SortKey.DURATION -> tracks.sortedWith(compareBy<Track> { it.durationMs }.thenBy { it.id.value })
    }

    private fun fields(t: Track): List<String> = listOf(t.title, t.artist, t.album)

    private fun likeHit(t: Track, q: String): Boolean =
        fields(t).any { it.contains(q, ignoreCase = true) }

    /**
     * 短查询（<2 字符）或 FTS 无结果时回落 `LIKE %q%`（§8 / B05）。
     * `availability != OK` 的曲目仍可被搜索到（B07）—— 这里不做任何状态过滤。
     */
    fun search(tracks: List<Track>, query: String): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return tracks
        if (q.length < 2) return tracks.filter { likeHit(it, q) }
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matched = tracks.filter { t ->
            tokens.all { token -> fields(t).any { f -> f.contains(token, ignoreCase = true) } }
        }
        return matched.ifEmpty { tracks.filter { likeHit(it, q) } }
    }

    /** 文件夹视图：`sourceRef.path` 前缀分组（§8，路径来自 03-01 规范化结果）。 */
    fun byFolder(tracks: List<Track>, prefix: String): List<Track> {
        val normalized = prefix.trimEnd('/')
        if (normalized.isEmpty()) return tracks
        return tracks.filter { it.sourceRef.path.startsWith("$normalized/") || it.sourceRef.path == normalized }
    }

    /**
     * 「合并重复项」开关（§8 / T04）：按 `(title, artist, durationMs ±2s)` 分组折叠。
     * **纯展示层**：只返回分组，不改数据、不合并 ID。
     */
    fun groupDuplicates(tracks: List<Track>, toleranceMs: Long = 2_000): List<List<Track>> {
        val result = mutableListOf<List<Track>>()
        tracks.groupBy { "${it.title.lowercase()}\u0000${it.artist.lowercase()}" }.values.forEach { candidates ->
            val sorted = candidates.sortedWith(compareBy<Track> { it.durationMs }.thenBy { it.id.value })
            var bucket = mutableListOf<Track>()
            var anchor = 0L
            sorted.forEach { t ->
                if (bucket.isEmpty()) {
                    bucket.add(t)
                    anchor = t.durationMs
                } else if (t.durationMs - anchor <= toleranceMs) {
                    bucket.add(t)
                } else {
                    result.add(bucket)
                    bucket = mutableListOf(t)
                    anchor = t.durationMs
                }
            }
            if (bucket.isNotEmpty()) result.add(bucket)
        }
        return result
    }

    /** B13 / T17：failure_log 只保留最近 1,000 条 / 30 天。 */
    fun pruneFailures(
        records: List<FailureRecord>,
        now: Long,
        max: Int = FAILURE_LOG_MAX,
        maxAgeMs: Long = FAILURE_LOG_MAX_AGE_MS,
    ): List<FailureRecord> = records
        .filter { it.happenedAt >= now - maxAgeMs }
        .sortedWith(compareByDescending<FailureRecord> { it.happenedAt }.thenByDescending { it.id })
        .take(max)
}
