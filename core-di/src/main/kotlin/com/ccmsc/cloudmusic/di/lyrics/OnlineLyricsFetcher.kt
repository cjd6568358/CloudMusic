package com.ccmsc.cloudmusic.di.lyrics

import com.ccmsc.cloudmusic.model.LrcParser
import com.ccmsc.cloudmusic.model.Lyrics
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.storage.LyricFileStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * docs/01 §0（2026-11-16 用户拍板）：歌词本地优先（内嵌 / 同目录 lrc），本地缺失
 * **允许在线抓取**（个人自用、不考虑合规风险）。本文件是拍板能力的生产落地（§61.198）。
 *
 * 边界：
 * - **只做按需抓取，不进扫描热路径**——92 首 STRM 冷扫已 ≈2min，逐曲联网会放大一个数量级；
 * - **HTTP 可注入**（[httpGet]），纯 JVM 判定逻辑（URL 构造 / 歌曲匹配 / lrc 校验）可单测，
 *   网络异常 / 接口降级一律返回 null（歌词是派生数据，失败不阻断播放）；
 * - 命中即写 [LyricFileStore]（`cacheDir/lyrics/<trackId>.lrc`，原始文本），下次直接读缓存，
 *   与内嵌 / 同目录来源同构；**不新增 Room 表**（守 9 表发布基线）；
 * - 曲名 / 艺人必须匹配才取歌词（宁可无歌词，不显示错歌词）；纯音乐 / 空 lrc 不缓存。
 *
 * 接口形态于 2026-11-17 实测：`/api/search/get?s=…&type=1` 与 `/api/song/lyric?id=…`
 * 均 `code=200` 且返回逐行 lrc，无需登录 cookie。接口变更由 [httpGet] 返回 null 静默降级。
 */
object NeteaseLyricsApi {
    private const val SEARCH_URL = "https://music.163.com/api/search/get"
    private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
    private val LRC_STAMP = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // core-di 未启用 kotlinx-serialization 编译器插件，按项目既有
    // （AppSettingsCodec / RoomEntities）用 parseToJsonElement + JsonObject 导航，
    // 不新增 codegen 插件、不改构建基线。接口字段变动统一在此收口并静默降级。

    fun searchUrl(query: String, limit: Int = 5): String =
        "$SEARCH_URL?s=${URLEncoder.encode(query, "UTF-8")}&type=1&offset=0&limit=$limit"

    fun lyricUrl(songId: Long): String =
        "$LYRIC_URL?id=$songId&lv=1&kv=1&tv=-1"

    /**
     * 从搜索响应挑最可信的 songId：标题精确 +4 / 包含 +2，艺人精确或包含 +2。
     * 艺人已知时门槛 6（标题与艺人都对上），否则 4；不满足返回 null（不猜歌词）。
     */
    fun pickSongId(searchJson: String, title: String, artist: String): Long? {
        val root = runCatching { json.parseToJsonElement(searchJson).jsonObject }.getOrNull()
            ?: return null
        val code = runCatching { root["code"]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0
        if (code != 0 && code != 200) return null
        val qTitle = normalize(title)
        val qArtist = normalize(artist)
        if (qTitle.isEmpty()) return null
        val threshold = if (qArtist.isEmpty()) 4 else 6
        val songs = runCatching {
            root["result"]?.jsonObject?.get("songs")?.jsonArray.orEmpty()
        }.getOrDefault(emptyList())
        var bestId: Long? = null
        var bestScore = 0
        songs.forEach { song ->
            val el = runCatching { song.jsonObject }.getOrNull() ?: return@forEach
            val id = runCatching { el["id"]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
                ?: return@forEach
            val t = normalize(runCatching { el["name"]?.jsonPrimitive?.content }.getOrNull())
            val artistNames = runCatching {
                el["artists"]?.jsonArray?.mapNotNull { a ->
                    runCatching { a.jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()
                }.orEmpty()
            }.getOrDefault(emptyList())
            val a = artistNames.joinToString("/") { normalize(it) }
            val titleScore = when {
                t == qTitle -> 4
                qTitle.length >= 2 && t.contains(qTitle) -> 2
                else -> 0
            }
            val artistScore = when {
                qArtist.isEmpty() -> 0
                a == qArtist || a.contains(qArtist) -> 2
                else -> 0
            }
            val score = titleScore + artistScore
            if (score >= threshold && score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return bestId?.takeIf { it > 0L }
    }

    /** 只接受含 LRC 时间戳的响应——纯音乐 / 空壳 / 错误码一律 null。 */
    fun validLrc(lyricJson: String): String? {
        val root = runCatching { json.parseToJsonElement(lyricJson).jsonObject }.getOrNull()
            ?: return null
        val code = runCatching { root["code"]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0
        if (code != 0 && code != 200) return null
        val raw = runCatching {
            root["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return raw.takeIf { LRC_STAMP.containsMatchIn(it) }
    }

    /** 去空格/标点/括号副标题后小写比较（`(Live)` / ` - ` 等不阻断匹配）。 */
    private fun normalize(s: String?): String = s.orEmpty().trim().lowercase()
        .replace(Regex("""[（(][^)）]*[)）]"""), "")
        .replace(Regex("""[\s\-_'.,!?·]"""), "")
}

/**
 * 在线歌词抓取器（阻塞式，调用方须在 IO 调度器执行）。
 * @param httpGet URL → 响应体；网络/HTTP 失败返回 null 或抛异常均可，均按「无歌词」降级。
 * @param store 命中后写缓存；null = 只返回不落盘（测试用）。
 */
class OnlineLyricsFetcher(
    private val httpGet: (String) -> String?,
    private val store: LyricFileStore? = null,
) {
    /** 失败记忆：同一曲目不因 UI 重入反复联网；上限防无界增长。 */
    private val failed = ConcurrentHashMap<String, Boolean>()

    fun fetchRaw(track: Track): String? {
        if (track.title.isBlank() || failed.containsKey(track.id.value)) return null
        val raw: String? = runCatching {
            val query = listOf(track.title, track.artist)
                .filter { it.isNotBlank() }.joinToString(" ")
            val searchJson = httpGet(NeteaseLyricsApi.searchUrl(query)) ?: return@runCatching null
            val songId = NeteaseLyricsApi.pickSongId(searchJson, track.title, track.artist)
                ?: return@runCatching null
            val lyricJson = httpGet(NeteaseLyricsApi.lyricUrl(songId)) ?: return@runCatching null
            NeteaseLyricsApi.validLrc(lyricJson)
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            if (failed.size < 4096) failed[track.id.value] = true
            return null
        }
        store?.saveRaw(track.id, raw)
        return raw
    }

    fun fetch(track: Track): Lyrics? =
        fetchRaw(track)?.let { LrcParser.parse(it, SOURCE) }

    companion object {
        const val SOURCE = "online:netease"
    }
}

/** 生产 HTTP 适配器：Referer / UA 按网易云网页口径，异常由 [OnlineLyricsFetcher] 统一降级。 */
fun okHttpLyricsGet(client: OkHttpClient): (String) -> String? = { url ->
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) CloudMusic/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}
