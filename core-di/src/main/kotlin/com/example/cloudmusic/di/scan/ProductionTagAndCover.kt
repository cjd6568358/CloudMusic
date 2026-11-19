package com.example.cloudmusic.di.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DirectLinkCapability
import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.TextReadCapability
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.scanner.AudioFormat
import com.example.cloudmusic.scanner.AudioProbe
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.CoverResolver
import com.example.cloudmusic.scanner.NameParser
import com.example.cloudmusic.scanner.RawTags
import com.example.cloudmusic.scanner.RemoteTagPolicy
import com.example.cloudmusic.scanner.ScanPacer
import com.example.cloudmusic.scanner.TagReader
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

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
        val header = audio.audioHeader
        val tags = audio.tag
        // §61.112：补时长 / 采样率（audioHeader），此前只取文本帧与封面——
        // 曲库 durationMs 缺失会让播放页进度条落 00:00（真机 §61.111④ 一手）。
        val durationMs = header?.trackLength?.toLong()?.times(1000L)
            ?.takeIf { it > 0L }
        val sampleRate = header?.sampleRate?.toIntOrNull()?.takeIf { it > 0 }
        if (tags == null) {
            MediaTags(
                durationMs = durationMs,
                sampleRate = sampleRate,
            )
        } else {
            MediaTags(
                title = tags.getFirst(FieldKey.TITLE),
                artist = tags.getFirst(FieldKey.ARTIST),
                album = tags.getFirst(FieldKey.ALBUM),
                trackNo = tags.getFirst(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
                year = tags.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
                genre = tags.getFirst(FieldKey.GENRE),
                durationMs = durationMs,
                sampleRate = sampleRate,
                embeddedCover = tags.firstArtwork?.binaryData,
                // docs/01 §0（2026-11-16 拍板）：完整内嵌歌词（USLT/LYRICS）。
                // JAudioTagger 读整文件，不受 HeadTagReader 64KB 头部限制，是内嵌歌词主通道。
                lyrics = tags.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(MediaTags.EMPTY)
}

/** 03-04 §6.4：内嵌 / 同目录封面统一压缩到 512×512 WebP(q80) 落 `cacheDir/covers/`。 */
@Suppress("DEPRECATION") // Bitmap.CompressFormat.WEBP 在 API 30 前仍可用，只是被标记 deprecated
class AndroidCoverWriter(context: Context) : CoverWriterPort {
    private val appContext = context.applicationContext
    private val dir = File(CoverPolicy.coverDir(appContext.cacheDir.absolutePath))

    /** §61.145：内容哈希 → 已落盘 ref 的会话级去重——同字节封面（同专辑多曲目 / 夹具
     *  复制集）只 decode/encode 一次；命中直接复用路径，封面文件本身按 trackId 命名
     *  语义不变（首个 trackId 的文件被同内容后续曲目共享引用，字节一致无损）。
     *  §61.205（A1-④）：会话级之上再挂跨扫描持久索引（dedup-index.txt，与封面文件
     *  同住 covers/ 目录，缓存清理同生共死）——进程重启后的首轮全量重扫不再整批
     *  重复 decode/encode；命中须 ref 文件仍在，防外部删除产生孤儿引用。 */
    private val written = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dedupIndex = CoverDedupIndex(File(dir, "dedup-index.txt"))

    override fun write(trackId: String, bytes: ByteArray): String? {
        if (!CoverPolicy.accepts(bytes)) return null
        val hash = java.security.MessageDigest.getInstance("MD5")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        written[hash]?.let { return it }
        dedupIndex.get(hash)?.let { cached ->
            if (File(cached.removePrefix("file://")).isFile) {
                written[hash] = cached
                return cached
            }
        }
        val ref = runCatching { decodeAndWrite(trackId, bytes) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        written[hash] = ref
        dedupIndex.put(hash, ref)
        return ref
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



/** §61.248：分块流式 SHA-256（纯逻辑可测）——读不到任何字节 = null（不伪造空指纹）。 */
object ContentHashing {
    const val CHUNK_SIZE: Int = 256 * 1024

    /** [readChunk](offset, length) 返回 null/空 = EOF。返回小写十六进制指纹。 */
    suspend fun hashChunks(
        readChunk: suspend (offset: Long, length: Int) -> ByteArray?,
        chunkSize: Int = CHUNK_SIZE,
    ): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var offset = 0L
        var total = 0L
        while (true) {
            val chunk = readChunk(offset, chunkSize) ?: break
            if (chunk.isEmpty()) break
            digest.update(chunk)
            offset += chunk.size
            total += chunk.size
        }
        if (total == 0L) return null
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * §61.248：生产哈希器——经 `DriverRegistry` 取已 init 的驱动（App 启动即冷扫灌入；
 * 缺驱动 = null 如实跳过）。路径 = [com.example.cloudmusic.model.Track.sourceRef.path]
 * （§61.221 口径：driver 相对路径，与扫描链 readBytes 语义一致）。
 * **流量口径**：本哈希器只被 Tier1 确认趟消费——仅对 Tier2 疑似组候选文件全读，
 * 限数在会话侧（§61.225 全量下载教训：绝不在扫描期对全库逐文件全读）。
 */
class DriverContentHasher : TrackContentHasher {
    override suspend fun hash(track: com.example.cloudmusic.model.Track): String? {
        val driver = com.example.cloudmusic.datasource.DriverRegistry.get(track.sourceRef.storageId)
            ?: return null
        // readBytes 是 ByteRangeCapability 能力位（§61.225 分块读设计本意）；无该能力的
        // 驱动（如纯直链型）不伪造——null 跳过。
        val ranged = driver as? ByteRangeCapability ?: return null
        val obj = com.example.cloudmusic.model.Obj(
            name = track.sourceRef.path.substringAfterLast('/'),
            size = 0L,
            isDir = false,
            modified = 0L,
            path = track.sourceRef.path,
        )
        return runCatching {
            ContentHashing.hashChunks(readChunk = { offset, length -> ranged.readBytes(obj, offset, length) })
        }.getOrNull()
    }
}
/** 生产 `Link` 定位：经 `DriverRegistry` 的 `link()` 拿到可读取的 ref，不缓存 Link 对象。 */
class RegistryLinkLocator : LinkLocator {
    override suspend fun locate(candidate: Candidate): String? {
        val driver = DriverRegistry.get(candidate.storage.id) ?: return null
        // §61.225（真机一手）：分块读驱动（ByteRange）且无直链探测（DirectLink）的驱动
        // 扫描期**不走 link()**——SftpDriver.link() 是全量缓存拉取（§61.216 设计为播放
        // 链语义），仅为读 tag 触发全量下载 = 冷扫流量爆炸。返回驱动相对路径 ref，
        // tag 读取经 head.read → rangeFor → readBytes 头尾分块（§61.216 设计本意）；
        // 歌词/封面侧链按 ref 五态分派照常工作。WebDAV 等直链驱动保持 link() 语义不变。
        if (driver is ByteRangeCapability && driver !is DirectLinkCapability) {
            return candidate.obj.path
        }
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
 * §61.205（A1-⑤）：生产扫描节奏——扫描全程持 PARTIAL wake lock（30min 超时兜底防
 * 泄漏）防深睡冻结 CPU；熄屏时 metadata 块间让步 50ms，防 CPU 长满触发 MIUI 异常
 * （§61.144 一手）。begin/end 引用计数幂等（多 Storage / 重入成对出现）。
 * MIUI 进程级冻结无法由应用完全避免——完整判据实测仍须 USB+亮屏会话（如实登记）。
 */
class AndroidScanPacer(private val powerManager: PowerManager) : ScanPacer {
    private val holders = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun beginScan() {
        if (holders.incrementAndGet() > 1) return
        runCatching {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudmusic:scan")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(30L * 60L * 1000L)
                }
        }
    }

    override fun endScan() {
        if (holders.decrementAndGet() > 0) return
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override suspend fun yieldBetweenChunks() {
        if (!powerManager.isInteractive) {
            kotlinx.coroutines.delay(50)
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
    /** §61.147：按 (storage, 目录) 缓存目录枚举——同目录 N 候选只 list 一次，
     *  消灭平铺大目录下每候选全量 list+sort 的 O(n²)（trace_34 一手：list0 单线程
     *  59s CPU）。列表含目录条目，size/modified 与快照无关，缓存会话级即可。 */
    private val siblingsCache = java.util.concurrent.ConcurrentHashMap<String, List<Obj>?>()

    /** §61.203：按 (storage, 目录) 缓存**已解析的封面 ref**——封面字节每目录只读
     *  一次（网络 = 一次 Range 读 ≤5MB；本地 = 一次文件读），同目录其余候选直接复用
     *  写入 [CoverLookupCache]。此前每候选重复读字节再靠 CoverWriter 哈希去重兜底，
     *  字节级重复传输/读盘仍在。[DirRef] 包装规避 ConcurrentHashMap 不存 null。 */
    private class DirRef(val ref: String?)
    private val dirCoverRefs = java.util.concurrent.ConcurrentHashMap<String, DirRef>()

    suspend operator fun invoke(candidate: Candidate, ref: String) {
        val storageId = candidate.storage.id
        val parentPath = candidate.obj.path.substringBeforeLast('/', "")
        val cacheKey = "$storageId:$parentPath"
        dirCoverRefs[cacheKey]?.let { cached ->
            cached.ref?.let { cache.put(storageId, candidate.obj.path, it) }
            return
        }
        val driver = DriverRegistry.get(storageId) ?: return
        val siblings = siblingsCache.getOrPut(cacheKey) {
            runCatching {
                driver.list(Obj(name = "", isDir = true, path = parentPath))
            }.getOrNull()
        } ?: return
        val coverName = CoverPolicy.pickDirectoryCover(siblings.map { it.name }) ?: return
        val coverObj = siblings.firstOrNull { it.name == coverName } ?: return
        val trackId = TrackId.of(storageId, candidate.obj.path).value
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
        // §61.203：含「该目录无封面」的 null 结果一并记忆，同目录不重复判定。
        dirCoverRefs[cacheKey] = DirRef(written)
        written?.let { cache.put(storageId, candidate.obj.path, it) }
    }
}

/** §61.147：`MediaMetadataRetriever` 兜底白名单——其可靠支持的容器扩展（真机一手：
 *  APE/WV 等异构容器 native 级挂死，见 trace_31 / `12 §61.147`）。 */
private val RETRIEVER_FALLBACK_EXTS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus")

/**
 * §61.186（用户点单「说好的歌词全都没有」的根因修复）：同目录 `.lrc` 歌词提取。
 *
 * 用户曲库主体是 WebDAV/STRM（无内嵌 USLT），而此前的内嵌提取（lyricsSink）只覆盖
 * 本地标签链 ⇒ 歌词缓存恒空、播放页歌词视图恒占位。本类按 [DirectoryCoverLookup]
 * 同型：merged 无内嵌歌词时尝试同目录 `<音频名>.lrc`——
 * - LOCAL：`java.io.File` 直读（UTF-8 → GB18030 回落，NAS 场景 .lrc 常见 GBK）；
 * - 网络：驱动 [TextReadCapability]（UTF-8→GBK 回落内建，§61.153）；
 * 命中即经 lyricsSink 落缓存；失败按 (storage, 目录) 记忆——无 .lrc 目录不逐文件
 * 重试 GET（不 list、直击候选路径，§61.146 O(n²) 教训同源）。
 * §61.217：候选由 [LyricsPathCandidates] 给出，覆盖 lyrics/ 子目录、标题 / 艺人名
 * 命名与大写 .LRC；目录记忆保证最多只对一个目录发起一轮候选请求。
 */
class SiblingLyricsLookup(
    private val sink: (String, String) -> Unit,
    private val driverFor: (String) -> Driver? = { DriverRegistry.get(it) },
    /** §61.223：App cacheDir 绝对路径前缀（生产注入 context.cacheDir）——SFTP link()
     *  的全量本地缓存路径（§61.216）以此开头，须回落驱动读同目录 lrc，不按 LOCAL 处理。 */
    private val cacheDirPrefix: String = "",
) {
    /** (storageId:目录) → 无 .lrc 记忆；上限防无界增长（重扫会话级即可）。 */
    private val noLyricsDirs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    suspend operator fun invoke(candidate: Candidate, ref: String) {
        val storageId = candidate.storage.id
        val path = candidate.obj.path
        // §61.186：先归一化分隔符再切父目录/基名——生产 Android 恒 '/'，但 LOCAL ref
        // 在桌面 JVM 测试（@TempDir）里是 Windows 反斜杠路径，非 POSIX 假设必须消除。
        val normPath = path.replace('\\', '/')
        val parent = normPath.substringBeforeLast('/', "")
        // §61.223：ref 形态五态判定提升到最前——parent 提前返回与下方分派共用同一语义。
        // 驱动相对路径（SFTP root 根目录文件 = 裸文件名，无斜杠）与 link() 本地缓存路径
        // （§61.216，cacheDir 前缀）parent 为空是合法形态，候选 path 交驱动 full() 拼 root；
        // 只有「真 LOCAL」（file:// / 绝对路径含 Windows 盘符）才按父目录语义提前返回。
        val isCacheRef = cacheDirPrefix.isNotEmpty() && ref.startsWith(cacheDirPrefix)
        val isLocalFile = !isCacheRef && (ref.startsWith("file://") || ref.startsWith("/") ||
            (ref.length >= 3 && ref[1] == ':' && (ref[2] == '/' || ref[2] == '\\')))
        if (parent.isEmpty() && (isLocalFile || ref.startsWith("content://"))) return
        val dirKey = "$storageId:$parent"
        if (noLyricsDirs.containsKey(dirKey)) return
        // V0.2 §61.217：同目录同名之外，再试 lyrics/ 子目录、标题 / 艺人名、大写 .LRC。
        // 全部是确定性直击路径，不 list 目录（§61.146 O(n²) 教训）；目录级失败记忆不变。
        val names = LyricsPathCandidates.forTrack(
            fileName = normPath.substringAfterLast('/'),
            title = candidate.hint?.title,
            artist = candidate.hint?.artist,
        )
        if (names.isEmpty()) return
        // §61.223（真机一手）：ref 形态五态分派——http(s) 直链、SFTP 等驱动**相对路径**
        // （无 scheme）与 **link() 本地缓存路径**（cacheDir 前缀，§61.216 全量缓存语义）
        // 走 TextReadCapability；content:// 无同目录语义跳过；
        // file:// / 绝对路径（含桌面 JVM 测试的 Windows 盘符 C:\）走 LOCAL File。
        // 旧三态判定把相对路径错判为 LOCAL（File("nas_song.mp3").parentFile = null）→
        // SFTP 外置 lrc 恒不落盘；缓存路径错判则会在 cache/sftp/ 里找 lrc（必 miss）。
        val cap = if (ref.startsWith("content://") || isLocalFile) null
        else driverFor(storageId) as? TextReadCapability
        // §61.223：缓存路径 / 驱动相对路径候选 path 相对驱动 root；LOCAL 不进此分支
        val localDir = if (isLocalFile) File(ref.removePrefix("file://")).parentFile else null
        if (cap == null && localDir == null) return // content:// 同语义跳过
        var raw: String? = null
        for (name in names) {
            raw = when {
                cap != null -> runCatching {
                    cap.readText(Obj(
                        name = name.substringAfterLast('/'), isDir = false, path = "$parent/$name",
                    ))
                }.getOrNull()
                localDir != null -> runCatching {
                    val f = File(localDir, name.replace('/', File.separatorChar))
                    if (f.isFile) readLocalText(f) else null
                }.getOrNull()
                else -> null
            }
            if (!raw.isNullOrBlank()) break
        }
        if (raw.isNullOrBlank()) {
            if (noLyricsDirs.size < 4096) noLyricsDirs[dirKey] = true
            return
        }
        sink(TrackId.of(storageId, path).value, raw)
    }

    /** UTF-8 → GB18030 回落（出现替换符即按 GBK 系重解；与 TextReadCapability 口径一致）。 */
    private fun readLocalText(file: File): String {
        val bytes = file.readBytes()
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, Charset.forName("GB18030")) else utf8
    }
}

class AndroidTagReader(
    private val media: MediaMetadataPort,
    private val jaudio: JAudioTaggerPort,
    private val locator: LinkLocator,
    private val head: TagReader,
    private val directoryCover: suspend (Candidate, String) -> Unit = { _, _ -> },
    /** docs/01 §0：扫描读到的内嵌歌词写盘回调（cacheDir/lyrics/<trackId>.lrc）。
     *  默认空实现，JVM 单测 / 无缓存场景零影响。 */
    private val lyricsSink: (String, String) -> Unit = { _, _ -> },
    /** §61.186：内嵌歌词缺失时的同目录 `.lrc` 提取（LOCAL File / 网络 TextReadCapability）。 */
    private val siblingLyrics: suspend (Candidate, String) -> Unit = { _, _ -> },
    /** §61.203：远程 tag 跳过策略（默认全跳过，验收 #2 主刀）。 */
    private val remoteTagPolicy: RemoteTagPolicy = RemoteTagPolicy.SKIP,
) : TagReader {

    /** §61.147：JAudio 缺项计数（限频观测用）。 */
    private val jaudioInsufficient = java.util.concurrent.atomic.AtomicLong()

    override suspend fun read(candidate: Candidate): RawTags {
        val ref = runCatching { locator.locate(candidate) }.getOrNull()
        if (ref.isNullOrBlank()) return head.read(candidate)
        val local = ref.startsWith("content://") || ref.startsWith("file://") || ref.startsWith("/")
        if (!local) {
            // §61.203：远程 tag 跳过——467s 冷扫主导成本 = 逐文件 Range 头尾读 ×
            // 鉴权握手（§61.160 一手：网络存储 ≈380s）。跳过后标题/艺人走 NameParser
            // 文件名兜底（非 EMPTY 语义 ⇒ 仍进 TrackFactory.build，目录封面照常消费），
            // 时长由 §61.181 播放期真值通道回填。专辑/年份/风格等文本帧信息如实让位。
            if (remoteTagPolicy.shouldSkip(candidate.obj.name, candidate.obj.size)) {
                val fallback = NameParser.parse(candidate.obj.name, candidate.parentDirName, candidate.hint)
                val skeleton = RawTags(
                    title = fallback.title, artist = fallback.artist, album = fallback.album,
                    completeness = fallback.completeness,
                )
                return sideChannels(candidate, ref, skeleton)
            }
            val merged = TagMerger.merge(TagMerger.fromRaw(head.read(candidate)))
            return sideChannels(candidate, ref, merged)
        }

        // §61.145：LOCAL 文件路径 JAudioTagger 先行——其文本帧 + header(duration/sampleRate)
        // 覆盖除音质外全部字段且成本毫秒级；MediaMetadataRetriever（§61.144 真机一手：
        // MIUI 上百 ms 级/文件，7,514 文件 = 分钟级）降为**缺项兜底**：JAudio 未给出
        // title 或 duration 时才调用。content:// 不受影响（JAudio 不接受流）。
        val ext = ref.substringAfterLast('.', "").lowercase()
        val fromJAudio = if (ref.startsWith("content://")) MediaTags.EMPTY
        else runCatching { jaudio.read(ref.removePrefix("file://")) }.getOrDefault(MediaTags.EMPTY)
        val jaudioSufficient = fromJAudio.title != null && fromJAudio.durationMs != null
        if (!jaudioSufficient) {
            // §61.147：缺项观测（限频）——JAudio 为何对某批文件给不出 title/duration
            // 直接决定 metadata 阶段成本（走检索器/骨架降级），logcat CM-Tag 可审计。
            // runCatching：JVM 单测无 android.util.Log（not mocked），真机正常输出。
            val n = jaudioInsufficient.incrementAndGet()
            if (n <= 3L || n % 500L == 0L) {
                runCatching { android.util.Log.w("CM-Tag", "jaudio insufficient #$n ext=$ext") }
            }
        }
        // §61.147：检索器兜底只对**其可靠支持的容器扩展**启用——APE/WV 等异构容器上
        // MediaMetadataRetriever 会 native 级挂死（trace_31 一手：native_setup S 状态
        // 占住信号量 → 整个 metadata chunk awaitAll 永久阻塞 = 此前三轮扫描冻结根因）。
        // JAudio 读不了的异构容器按 B01 骨架降级（RawTags.EMPTY），不再进检索器。
        val retrieverSafe = ext in RETRIEVER_FALLBACK_EXTS
        val fromMedia = if (ref.startsWith("content://") || (!jaudioSufficient && retrieverSafe))
            runCatching { media.read(ref) }.getOrDefault(MediaTags.EMPTY)
        else MediaTags.EMPTY
        val fromHead = TagMerger.fromRaw(head.read(candidate))
        val merged = TagMerger.merge(fromMedia, fromJAudio, fromHead)
        return sideChannels(candidate, ref, merged)
    }

    /**
     * §61.203：封面 / 歌词侧链，LOCAL 与网络分支共用。
     *
     * **§61.186 缺口修复**：此前网络分支 `if (!local) return head.read(candidate)`
     * 提前返回——目录封面（C-11/C-12 登记「网络经 ByteRange 只读封面字节」）、
     * 同目录 `.lrc`（§61.186 登记「WebDAV/STRM 曲库主歌词来源」）、内嵌歌词写盘
     * 三条侧链对网络 ref **从未生效**（§61.189 判据 B「服务器无 .lrc」的空结果
     * 掩盖了这一点：提取根本没跑）。现网络分支（跳过与否）都走本侧链。
     */
    private suspend fun sideChannels(candidate: Candidate, ref: String, merged: RawTags): RawTags {
        // §61.146：仅当无内嵌封面才查同目录封面——原实现无条件 list 父目录，
        // 平铺大目录（夹具根 7,514 文件）下每候选一次全量 list+sort = O(n² log n)，
        // 有内嵌封面时纯属白干（真机 467% 多核空转一手）。
        if (merged.embeddedCover == null) {
            runCatching { directoryCover(candidate, ref) }
        }
        // docs/01 §0（2026-11-16 拍板）：内嵌歌词写盘缓存（IO 密集但极小文本，
        // 与封面写盘同批）；失败静默，不影响扫描主流程（歌词是派生数据，可重扫恢复）。
        // §61.186：内嵌缺失 → 同目录 .lrc 提取。
        val embeddedLyrics = merged.lyrics?.takeIf { it.isNotBlank() }
        if (embeddedLyrics != null) {
            val trackId = com.example.cloudmusic.model.TrackId
                .of(candidate.storage.id, candidate.obj.path).value
            runCatching { lyricsSink(trackId, embeddedLyrics) }
        } else {
            runCatching { siblingLyrics(candidate, ref) }
        }
        return merged
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
