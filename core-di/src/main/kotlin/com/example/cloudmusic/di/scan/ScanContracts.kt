package com.example.cloudmusic.di.scan

import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.RawTags
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * docs/03-04 §6 / §7 的**纯 JVM 策略与端口**（第二十批 / T-A4）。
 *
 * `MediaMetadataRetriever` / `Bitmap` / JAudioTagger 都隔离在端口后面：
 * 合并规则、封面参数、Driver 重建决策可在本模块的 JVM 单测里完整断言，
 * 与第十九批 `LocalDriver` 的端口隔离同型。
 */

/** 单个解析通道的产出；多个通道按 [TagMerger] 逐字段取第一个非空值。 */
data class MediaTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val embeddedCover: ByteArray? = null,
) {
    companion object {
        val EMPTY = MediaTags()
    }
}

fun interface MediaMetadataPort {
    /** `ref` 为 content:// URI 或绝对文件路径；失败返回 [MediaTags.EMPTY]。 */
    fun read(ref: String): MediaTags
}

fun interface JAudioTaggerPort {
    /** 只接受真实文件路径（JAudioTagger 的 `AudioFileIO.read(File)` 不支持 content://）。 */
    fun read(filePath: String): MediaTags
}

interface CoverWriterPort {
    fun write(trackId: String, bytes: ByteArray): String?
    fun writeFromRef(trackId: String, ref: String): String?
}

/** 把 `Candidate` 解析成可读取的 ref；生产实现经 `DriverRegistry` 的 `link()`。 */
interface LinkLocator {
    suspend fun locate(candidate: Candidate): String?
}

/** 03-04 §6.1 / §6.5：多通道标签合并 + `tagCompleteness` 计算（纯函数）。 */
object TagMerger {
    fun merge(vararg sources: MediaTags): RawTags {
        fun <T : Any> pick(selector: (MediaTags) -> T?): T? =
            sources.firstNotNullOfOrNull(selector)

        val raw = RawTags(
            title = pick { it.title?.takeIf(String::isNotBlank) },
            artist = pick { it.artist?.takeIf(String::isNotBlank) },
            album = pick { it.album?.takeIf(String::isNotBlank) },
            trackNo = pick { it.trackNo },
            year = pick { it.year },
            genre = pick { it.genre?.takeIf(String::isNotBlank) },
            durationMs = pick { it.durationMs },
            sampleRate = pick { it.sampleRate },
            bitDepth = pick { it.bitDepth },
            embeddedCover = pick { it.embeddedCover?.takeIf(ByteArray::isNotEmpty) },
        )
        return raw.copy(completeness = completeness(raw))
    }

    fun fromRaw(raw: RawTags): MediaTags = MediaTags(
        title = raw.title, artist = raw.artist, album = raw.album,
        trackNo = raw.trackNo, year = raw.year, genre = raw.genre,
        durationMs = raw.durationMs, sampleRate = raw.sampleRate, bitDepth = raw.bitDepth,
        embeddedCover = raw.embeddedCover,
    )

    /** 03-04 §6.5：标签齐全 1.0 / 部分 0.5 / 无标签 0（文件名兜底由 NameParser 给 0.2）。 */
    fun completeness(raw: RawTags): Float {
        val filled = listOf(raw.title, raw.artist, raw.album).count { !it.isNullOrBlank() }
        return when {
            filled == 3 -> 1.0f
            filled > 0 -> 0.5f
            else -> 0f
        }
    }
}

/** 03-04 §6.4 封面落盘参数；全部为纯函数，供 JVM 单测与 Android 实现共用。 */
object CoverPolicy {
    const val TARGET_SIZE = 512
    const val QUALITY = 80
    const val MAX_EMBEDDED = 5 * 1024 * 1024

    val COVER_NAMES = listOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg")

    fun accepts(bytes: ByteArray?): Boolean =
        bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_EMBEDDED

    /** 2 的幂采样率，避免把 4000×3000 整图解码进内存。 */
    fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var size = 1
        while (maxOf(width, height) / (size * 2) >= TARGET_SIZE && size < 16) size *= 2
        return size
    }

    /** 最长边 ≤ 512、不放大、保持比例。 */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return TARGET_SIZE to TARGET_SIZE
        val scale = minOf(
            TARGET_SIZE.toFloat() / width,
            TARGET_SIZE.toFloat() / height,
            1f,
        )
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    fun fileName(trackId: String): String =
        trackId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".webp"

    fun coverDir(cachePath: String): String = cachePath.trimEnd('/') + "/covers"

    fun pickDirectoryCover(names: Collection<String>): String? {
        val lowered = names.map { it.lowercase() }
        return COVER_NAMES.firstOrNull { it in lowered }
            ?.let { wanted -> names.firstOrNull { it.lowercase() == wanted } }
    }
}

/**
 * `CoverResolver.resolve` 是**同步**方法，而同目录封面要 `driver.list()`（suspend）才能找到。
 * 因此由 `AndroidTagReader.read`（阶段 B，suspend）顺带查同目录封面并写入本缓存，
 * `CoverResolver` 只读缓存。网络音源（URL / PROXIED）的同目录封面需 ByteRange，属 T-A5（C-11）。
 */
class CoverLookupCache {
    private val refs = ConcurrentHashMap<String, String>()

    private fun key(storageId: String, path: String) = "$storageId|$path"

    fun put(storageId: String, path: String, ref: String) {
        refs[key(storageId, path)] = ref
    }

    fun get(storageId: String, path: String): String? = refs[key(storageId, path)]

    fun clear(storageId: String? = null) {
        if (storageId == null) refs.clear()
        else refs.keys.removeIf { it.startsWith("$storageId|") }
    }
}

/**
 * `DriverRegistry.get(storageId)` 取的是**实例**，而 `DriverRegistrar` 只注册 factory。
 * [StorageDriverBinder] 在扫描前按本计划把活跃 Storage 的 Driver 实例建好并放入注册表；
 * `modified` 变化（03-11 §5 / 08 §7）时先 remove 再 create，触发全量重扫。
 */
data class DriverSyncPlan(
    val toRemove: List<String>,
    val toCreate: List<Storage>,
    val unchanged: List<Storage>,
)

object DriverSyncPlanner {
    fun plan(active: List<Storage>, known: Map<String, Long>): DriverSyncPlan {
        val activeIds = active.mapTo(LinkedHashSet()) { it.id }
        val changed = active.filter { known[it.id] != it.modified }
        val changedIds = changed.mapTo(LinkedHashSet()) { it.id }
        return DriverSyncPlan(
            toRemove = known.keys.filter { it !in activeIds || it in changedIds },
            toCreate = changed,
            unchanged = active.filter { it.id in known && it.id !in changedIds },
        )
    }
}
