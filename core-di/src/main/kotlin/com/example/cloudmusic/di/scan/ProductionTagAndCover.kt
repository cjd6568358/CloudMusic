package com.example.cloudmusic.di.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.scanner.AudioFormat
import com.example.cloudmusic.scanner.AudioProbe
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.CoverResolver
import com.example.cloudmusic.scanner.RawTags
import com.example.cloudmusic.scanner.TagReader
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream

/**
 * docs/03-04 §6.2 定案的 Android 生产实现（第二十批 / T-A4）：
 * `MediaMetadataRetriever` 为主（支持 content:// SAF URI），JAudioTagger(LGPL) 补充
 * 文本帧与内嵌封面，网络音源回落 `:scanner` 的 `HeadTagReader`（ByteRange 头部解析）。
 * 所有框架调用经 [ScanContracts.kt] 的端口隔离，失败一律降级、不中断整轮扫描（B16）。
 */

class AndroidMediaMetadataPort(
    context: Context,
) : MediaMetadataPort {
    private val appContext = context.applicationContext

    override fun read(ref: String): MediaTags = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (ref.startsWith("content://")) {
                retriever.setDataSource(appContext, Uri.parse(ref))
            } else {
                retriever.setDataSource(ref.removePrefix("file://"))
            }
            MediaTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.take(4)?.toIntOrNull(),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                embeddedCover = retriever.embeddedPicture,
            )
        } finally {
            retriever.release()
        }
    }.getOrDefault(MediaTags.EMPTY)
}

/**
 * JAudioTagger 只接受 `java.io.File`，故 SAF 的 `content://` 不走此通道；
 * 也不触碰 `Artwork.getImage()`（依赖 java.awt，Android 无此包），只读 `binaryData`。
 */
class JAudioTaggerFilePort : JAudioTaggerPort {
    override fun read(filePath: String): MediaTags = runCatching {
        val file = File(filePath)
        if (!file.isFile) return@runCatching MediaTags.EMPTY
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: return@runCatching MediaTags.EMPTY
        MediaTags(
            title = tag.getFirst(FieldKey.TITLE),
            artist = tag.getFirst(FieldKey.ARTIST),
            album = tag.getFirst(FieldKey.ALBUM),
            trackNo = tag.getFirst(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            year = tag.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            genre = tag.getFirst(FieldKey.GENRE),
            embeddedCover = tag.firstArtwork?.binaryData,
        )
    }.getOrDefault(MediaTags.EMPTY)
}

/** 03-04 §6.4：内嵌 / 同目录封面统一压缩到 512×512 WebP(q80) 落 `cacheDir/covers/`。 */
@Suppress("DEPRECATION") // Bitmap.CompressFormat.WEBP 在 API 30 前仍可用，只是被标记 deprecated
class AndroidCoverWriter(context: Context) : CoverWriterPort {
    private val appContext = context.applicationContext
    private val dir = File(CoverPolicy.coverDir(appContext.cacheDir.absolutePath))

    override fun write(trackId: String, bytes: ByteArray): String? {
        if (!CoverPolicy.accepts(bytes)) return null
        return runCatching { decodeAndWrite(trackId, bytes) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override fun writeFromRef(trackId: String, ref: String): String? {
        val bytes = runCatching { readRef(ref) }.getOrNull() ?: return null
        return write(trackId, bytes)
    }

    private fun decodeAndWrite(trackId: String, bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""

        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return ""
        val (width, height) = CoverPolicy.scaledSize(decoded.width, decoded.height)
        val scaled = if (width == decoded.width && height == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, width, height, true)
        try {
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, CoverPolicy.fileName(trackId))
            FileOutputStream(out).use {
                scaled.compress(Bitmap.CompressFormat.WEBP, CoverPolicy.QUALITY, it)
            }
            return out.absolutePath
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun readRef(ref: String): ByteArray =
        if (ref.startsWith("content://")) {
            appContext.contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
                ?: ByteArray(0)
        } else {
            File(ref.removePrefix("file://")).readBytes()
        }
}

/** 生产 `Link` 定位：经 `DriverRegistry` 的 `link()` 拿到可读取的 ref，不缓存 Link 对象。 */
class RegistryLinkLocator : LinkLocator {
    override suspend fun locate(candidate: Candidate): String? {
        val driver = DriverRegistry.get(candidate.storage.id) ?: return null
        val link = runCatching { driver.link(candidate.obj) }.getOrNull() ?: return null
        return when (link.kind) {
            Link.Kind.LOCAL -> link.localPath
            Link.Kind.URL -> link.url.takeIf { it.isNotEmpty() }
            Link.Kind.PROXIED -> link.proxySourceUrl // C-07：仅用于读取，禁止入日志
            Link.Kind.TEXT -> null
        }
    }
}

/**
 * 同目录封面查找（suspend），结果写入 [CoverLookupCache] 供同步的 CoverResolver 消费。
 *
 * C-11 / C-12（`12 §31`）：V0.1 曾按 ref 前缀直接跳过网络音源，导致同目录封面永远回落占位。
 * 现在网络分支经 [ByteRangeCapability] 只读封面文件的字节（上限 [ByteRangePolicy.MAX_COVER_BYTES]），
 * 由 [CoverWriterPort.write] 统一压成 512×512 WebP；失败不重试、直接回落占位（03-04 §6.4）。
 */
class DirectoryCoverLookup(
    private val writer: CoverWriterPort,
    private val cache: CoverLookupCache,
    private val rangeFor: (Candidate) -> ByteRangeCapability? = { null },
) {
    suspend operator fun invoke(candidate: Candidate, ref: String) {
        val driver = DriverRegistry.get(candidate.storage.id) ?: return
        val parentPath = candidate.obj.path.substringBeforeLast('/', "")
        val siblings = runCatching {
            driver.list(Obj(name = "", isDir = true, path = parentPath))
        }.getOrNull() ?: return
        val coverName = CoverPolicy.pickDirectoryCover(siblings.map { it.name }) ?: return
        val coverObj = siblings.firstOrNull { it.name == coverName } ?: return
        val trackId = TrackId.of(candidate.storage.id, candidate.obj.path).value
        val local = ref.startsWith("content://") || ref.startsWith("/") || ref.startsWith("file://")
        val written = if (local) {
            val coverRef = runCatching {
                val link = driver.link(coverObj)
                link.localPath ?: link.url.takeIf { it.isNotEmpty() }
            }.getOrNull()
            coverRef?.let { writer.writeFromRef(trackId, it) }
        } else {
            // 网络音源：不取 proxySourceUrl 播整文件，只发一次 Range 读封面字节。
            val range = rangeFor(candidate)
            val bytes = runCatching {
                range?.readBytes(coverObj, 0, ByteRangePolicy.MAX_COVER_BYTES)
            }.getOrNull()
            bytes?.let { writer.write(trackId, it) }
        }
        written?.let { cache.put(candidate.storage.id, candidate.obj.path, it) }
    }
}

class AndroidTagReader(
    private val media: MediaMetadataPort,
    private val jaudio: JAudioTaggerPort,
    private val locator: LinkLocator,
    private val head: TagReader,
    private val directoryCover: suspend (Candidate, String) -> Unit = { _, _ -> },
) : TagReader {

    override suspend fun read(candidate: Candidate): RawTags {
        val ref = runCatching { locator.locate(candidate) }.getOrNull()
        if (ref.isNullOrBlank()) return head.read(candidate)
        val local = ref.startsWith("content://") || ref.startsWith("file://") || ref.startsWith("/")
        if (!local) return head.read(candidate) // 网络音源：ByteRange 头部解析

        val fromMedia = runCatching { media.read(ref) }.getOrDefault(MediaTags.EMPTY)
        val fromJAudio = if (ref.startsWith("content://")) MediaTags.EMPTY
        else runCatching { jaudio.read(ref.removePrefix("file://")) }.getOrDefault(MediaTags.EMPTY)
        val fromHead = TagMerger.fromRaw(head.read(candidate))
        runCatching { directoryCover(candidate, ref) }
        return TagMerger.merge(fromMedia, fromJAudio, fromHead)
    }

    override fun probe(head: ByteArray): AudioFormat = AudioProbe.probe(head)
}

class AndroidCoverResolver(
    private val writer: CoverWriterPort,
    private val cache: CoverLookupCache,
    private val placeholder: String? = null,
) : CoverResolver {
    override fun resolve(candidate: Candidate, embedded: ByteArray?): String? {
        val trackId = TrackId.of(candidate.storage.id, candidate.obj.path).value
        if (CoverPolicy.accepts(embedded)) {
            writer.write(trackId, embedded!!)?.let { return it }
        }
        return cache.get(candidate.storage.id, candidate.obj.path) ?: placeholder
    }
}
