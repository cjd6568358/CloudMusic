package com.ccmsc.cloudmusic.di.scan

import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * §61.233（V0.2 刮削）：封面在线刮削——Deezer Search API（免费、无 key；D-146 个人
 * 自用口径，与 §61.198 在线歌词同族）。
 *
 * - 批处理动作（设置页触发，同 §61.130 ReplayGain 分析模式）：**手动触发 + 限频限数**，
 *   不挂扫描期（§61.225 全量下载教训——扫描链不做网络重活）；
 * - 目标：coverRef 缺失 且 album/artist 非空 且 可刮源（本地 / WebDAV / SFTP / FTP）；
 * - 落盘走 [CoverStoreWriter.write]（512×512 WebP 同口径）→ `updateCover`（只写缺封面行）；
 * - 网络失败 / 无命中 / 解析失败都按「本次无结果」处理（派生数据可重刮）。
 */

/** 刮削结果：命中数 / 尝试数（UI toast 用）。 */
data class ScrapeReport(val attempted: Int, val hit: Int)

/** 网络端口（JVM 单测 fake；生产 OkHttp）。 */
fun interface CoverHttp {
    suspend fun fetch(url: String): ByteArray?
}

/** Deezer 搜索 JSON → cover 图 URL（纯函数，单测直测）。 */
internal fun parseDeezerCover(json: String): String? {
    if (json.isBlank()) return null
    // 最小 JSON 投影：data[0].album.cover_big / cover_medium / cover 兜底——刮削只取
    // 一个字段，字符串定位足够且容错（不引入完整 JSON 模型）。
    val dataIdx = json.indexOf("\"data\":")
    if (dataIdx < 0) return null
    val albumIdx = json.indexOf("\"album\":", dataIdx)
    if (albumIdx < 0) return null
    for (key in listOf("\"cover_big\"", "\"cover_medium\"", "\"cover\"")) {
        val keyIdx = json.indexOf(key, albumIdx)
        if (keyIdx < 0) continue
        val colon = json.indexOf(':', keyIdx)
        if (colon < 0) continue
        val open = json.indexOf('"', colon)
        if (open < 0) continue
        val close = json.indexOf('"', open + 1)
        if (close <= open) continue
        val url = json.substring(open + 1, close)
        if (url.startsWith("http")) return url
    }
    return null
}

/** Deezer 搜索 URL 构造（album 优先 + artist；query 最小 URL encode）。 */
internal fun deezerSearchUrl(album: String, artist: String): String {
    val q = java.net.URLEncoder.encode("""album:"$album" artist:"$artist"""", "UTF-8")
    return "https://api.deezer.com/search?limit=1&q=$q"
}

/** 可刮源：本地 / WebDAV / SFTP / FTP（STRM 直链无稳定元数据，不刮）。 */
private val SCRAPE_SOURCE_TYPES = setOf(
    SourceType.LOCAL, SourceType.WEBDAV, SourceType.SFTP, SourceType.FTP,
)

/**
 * 刮削会话（挂 [PlaybackRuntime]：设置页触发，maxCount 限数防流量失控）。
 */
class CoverScrapeSession(
    private val tracks: TrackRepository,
    private val writer: CoverWriterPort,
    private val http: CoverHttp,
) {
    suspend fun scrapeMissing(maxCount: Int): ScrapeReport {
        val candidates = tracks.observeAll().first()
            .filter {
                it.coverRef.isNullOrBlank() && it.album.isNotBlank() &&
                    it.artist.isNotBlank() && it.sourceType in SCRAPE_SOURCE_TYPES
            }
            .take(maxCount)
        var hit = 0
        var attempted = 0
        for (track in candidates) {
            currentCoroutineContext().ensureActive()
            attempted++
            val searchUrl = deezerSearchUrl(track.album, track.artist)
            val coverUrl = runCatching { http.fetch(searchUrl) }
                .getOrNull()?.decodeToString()?.let { parseDeezerCover(it) } ?: continue
            val bytes = runCatching { http.fetch(coverUrl) }.getOrNull() ?: continue
            val ref = runCatching { writer.write(track.id.value, bytes) }.getOrNull() ?: continue
            if (ref.isBlank()) continue
            tracks.updateCover(track.id, ref)
            hit++
        }
        return ScrapeReport(attempted, hit)
    }
}
