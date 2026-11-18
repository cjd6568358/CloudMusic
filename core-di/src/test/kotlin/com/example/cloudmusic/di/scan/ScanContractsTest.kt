package com.example.cloudmusic.di.scan

import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverFactory
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.di.settings.SettingsUpdate
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.CandidateKind
import com.example.cloudmusic.scanner.EmptyTagReader
import com.example.cloudmusic.scanner.RawTags
import com.example.cloudmusic.scanner.RemoteTagPolicy
import com.example.cloudmusic.scanner.TagReader
import com.example.cloudmusic.scanner.TrackHint
import com.example.cloudmusic.scanner.CoverResolver
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.StorageDto
import com.example.cloudmusic.storage.TrackRepository
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第二十批（`12 §29`，T-A4）扫描器生产组合层的**纯 JVM 契约测试**。
 * `MediaMetadataRetriever` / `Bitmap` / JAudioTagger 全部以端口 fake 替换，
 * 只断言合并策略、封面参数、Driver 同步决策与仓储委托，不实例化 Android 框架类。
 */
class ScanContractsTest {

    @BeforeEach
    fun resetRegistry() = DriverRegistry.reset()

    @AfterEach
    fun clearRegistry() = DriverRegistry.reset()

    private fun storage(id: String = "s1", driverType: String = "local", modified: Long = 0L) =
        Storage(id, "/$driverType", driverType, Addition("{}"), modified = modified)

    private fun candidate(
        storage: Storage = storage(),
        path: String = "/Music/a.mp3",
    ) = Candidate(
        storage = storage,
        obj = Obj(name = path.substringAfterLast('/'), path = path, size = 4_096L),
        kind = CandidateKind.AUDIO,
        parentDirName = "Music",
        hint = TrackHint(),
    )

    private class FakeAccess(initial: AppSettings = AppSettings()) : SettingsAccess {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> get() = state
        override fun current(): AppSettings = state.value
        override suspend fun snapshot(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate {
            val before = state.value
            val after = transform(before)
            state.value = after
            return SettingsUpdate.of(before, after)
        }
    }

    private class FakeDriver : Driver {
        override val config = DriverConfig(name = "Fake")
        var initialized = false
        override suspend fun init(ctx: DriverCtx) {
            initialized = true
        }

        override fun drop() {
            initialized = false
        }

        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link =
            Link(localPath = "/files/${file.name}")
    }

    private class FakeWriter(
        private val written: String? = "/cache/covers/x.webp",
    ) : CoverWriterPort {
        val writes = mutableListOf<Pair<String, ByteArray>>()
        override fun write(trackId: String, bytes: ByteArray): String? {
            writes += trackId to bytes
            return written
        }

        override fun writeFromRef(trackId: String, ref: String): String? = written
    }

    private class FakeLocator(private val ref: String?) : LinkLocator {
        override suspend fun locate(candidate: Candidate): String? = ref
    }

    private class FakeTrackRepository : TrackRepository {
        val batches = mutableListOf<List<Track>>()
        val availability = mutableListOf<Pair<TrackId, Availability>>()
        override suspend fun upsertBatch(tracks: List<Track>) {
            batches.add(tracks)
        }

        override suspend fun markAvailability(id: TrackId, a: Availability) {
            availability.add(id to a)
        }

        override suspend fun markOrphan(storageId: String) = Unit

    override suspend fun markReplayGain(id: TrackId, gainDb: Float) = Unit
    override suspend fun updateCover(id: TrackId, coverRef: String) { /* §61.233 fake */ }
    override suspend fun updateDuration(id: TrackId, durationMs: Long) = Unit
        override suspend fun count(): Int = 0
        override fun observeAll(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun search(query: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byAlbum(album: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byArtist(artist: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byFolder(prefix: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override suspend fun get(id: TrackId): Track? = null
    }

    // ---- 03-04 §6.1 / §6.5 标签合并 -----------------------------------------

    @Test
    fun `T-A4-01 tag merger takes first non-empty field per channel`() {
        val media = MediaTags(title = "标题", artist = "艺人", durationMs = 200_000L)
        val jaudio = MediaTags(
            title = "覆盖不了", artist = null, album = "专辑",
            trackNo = 3, year = 2024, genre = "Jazz",
            sampleRate = 44_100, bitDepth = 16,
            embeddedCover = byteArrayOf(1, 2, 3),
        )
        val merged = TagMerger.merge(media, jaudio)
        assertEquals("标题", merged.title)
        assertEquals("艺人", merged.artist)
        assertEquals("专辑", merged.album)
        assertEquals(200_000L, merged.durationMs)
        assertEquals(3, merged.trackNo)
        assertEquals(2024, merged.year)
        assertEquals(44_100, merged.sampleRate)
        assertEquals(3, merged.embeddedCover?.size)
        assertEquals(1.0f, merged.completeness)
    }

    @Test
    fun `T-A4-02 completeness is 1_0 0_5 or 0 and EMPTY stays empty`() {
        assertEquals(1.0f, TagMerger.completeness(RawTags("a", "b", "c")))
        assertEquals(0.5f, TagMerger.completeness(RawTags(title = "a")))
        assertEquals(0f, TagMerger.completeness(RawTags.EMPTY))
        assertEquals(RawTags.EMPTY, TagMerger.merge(MediaTags.EMPTY, TagMerger.fromRaw(RawTags.EMPTY)))
        val raw = RawTags(title = "a", artist = "b", completeness = 0.5f)
        assertEquals("a", TagMerger.fromRaw(raw).title)
    }

    // ---- 03-04 §6.4 封面策略 -------------------------------------------------

    @Test
    fun `T-A4-03 cover policy enforces size and 512px target`() {
        assertTrue(CoverPolicy.accepts(ByteArray(10)))
        assertFalse(CoverPolicy.accepts(null))
        assertFalse(CoverPolicy.accepts(ByteArray(0)))
        assertFalse(CoverPolicy.accepts(ByteArray(CoverPolicy.MAX_EMBEDDED + 1)))
        assertEquals(1, CoverPolicy.sampleSize(200, 100))
        assertEquals(8, CoverPolicy.sampleSize(4096, 3072))
        assertEquals(512 to 256, CoverPolicy.scaledSize(1000, 500))
        assertEquals(100 to 100, CoverPolicy.scaledSize(100, 100))
        assertEquals("abc1-2_3.webp", CoverPolicy.fileName("a/b:c.1-2_3"))
        assertEquals("/cache/covers", CoverPolicy.coverDir("/cache/"))
    }

    @Test
    fun `T-A4-04 directory cover priority is case-insensitive`() {
        assertEquals(
            "FOLDER.JPG",
            CoverPolicy.pickDirectoryCover(listOf("a.mp3", "FOLDER.JPG", "front.jpg")),
        )
        assertNull(CoverPolicy.pickDirectoryCover(listOf("a.mp3", "back.jpg")))
        assertEquals("cover.jpg", CoverPolicy.pickDirectoryCover(listOf("cover.jpg", "cover.png")))
        assertEquals("cover.png", CoverPolicy.pickDirectoryCover(listOf("FOLDER.JPG", "cover.png")))
    }

    @Test
    fun `T-A4-05 cover lookup cache is scoped by storage and path`() {
        val cache = CoverLookupCache()
        cache.put("s1", "/a.mp3", "/covers/a.webp")
        cache.put("s2", "/a.mp3", "/covers/b.webp")
        assertEquals("/covers/a.webp", cache.get("s1", "/a.mp3"))
        assertEquals("/covers/b.webp", cache.get("s2", "/a.mp3"))
        cache.clear("s1")
        assertNull(cache.get("s1", "/a.mp3"))
        assertEquals("/covers/b.webp", cache.get("s2", "/a.mp3"))
        cache.clear()
        assertNull(cache.get("s2", "/a.mp3"))
    }

    // ---- C-10 Driver 实例同步 ------------------------------------------------

    @Test
    fun `T-A4-06 driver sync plan handles add remove change and no-op`() {
        val a = storage("a", modified = 1)
        val b = storage("b", modified = 2)
        val first = DriverSyncPlanner.plan(listOf(a, b), emptyMap())
        assertEquals(2, first.toCreate.size)
        assertTrue(first.toRemove.isEmpty())

        val noop = DriverSyncPlanner.plan(listOf(a, b), mapOf("a" to 1L, "b" to 2L))
        assertTrue(noop.toCreate.isEmpty() && noop.toRemove.isEmpty())
        assertEquals(2, noop.unchanged.size)

        val changed = DriverSyncPlanner.plan(
            listOf(a.copy(modified = 9), b),
            mapOf("a" to 1L, "b" to 2L),
        )
        assertEquals(listOf("a"), changed.toRemove)
        assertEquals(listOf("a"), changed.toCreate.map { it.id })
        assertEquals(listOf("b"), changed.unchanged.map { it.id })

        val removed = DriverSyncPlanner.plan(listOf(a), mapOf("a" to 1L, "b" to 2L))
        assertEquals(listOf("b"), removed.toRemove)
    }

    @Test
    fun `T-A4-07 binder creates driver instances and is idempotent`() {
        val drivers = mutableListOf<FakeDriver>()
        val factory: DriverFactory = { _, _ -> FakeDriver().also { drivers += it } }
        val binder = StorageDriverBinder(mapOf("local" to factory))
        val a = storage("a", modified = 1)
        runBlocking {
            val first = binder.sync(listOf(a))
            assertEquals(listOf("a"), first.created)
            assertTrue(first.failed.isEmpty())
            assertTrue(DriverRegistry.get("a") is FakeDriver)
            assertTrue(drivers.single().initialized)

            val second = binder.sync(listOf(a))
            assertTrue(second.created.isEmpty())
            assertEquals(1, drivers.size) // 无重复实例

            binder.sync(listOf(a.copy(modified = 2)))
            assertEquals(2, drivers.size) // 配置变更 → 重建
        }
    }

    @Test
    fun `T-A4-08 storage provider filters disabled storages and failed drivers`() {
        val access = FakeAccess(
            AppSettings(
                storages = listOf(
                    StorageDto("on", "/local", "local", "{}"),
                    StorageDto("off", "/local", "local", "{}", disabled = true),
                    StorageDto("broken", "/local", "local", "{}", errorMsg = "E_DS_AUTH_FAILED"),
                ),
            ),
        )
        val binder = StorageDriverBinder(emptyMap()) // 无 factory → 所有 active 都 failed
        runBlocking {
            assertTrue(DataStoreStorageProvider(access, binder).active().isEmpty())
        }
        val withFactory = StorageDriverBinder(
            mapOf("local" to { _, _ -> FakeDriver() }),
        )
        runBlocking {
            val active = DataStoreStorageProvider(access, withFactory).active()
            assertEquals(listOf("on"), active.map { it.id })
        }
    }

    // ---- 03-04 §6 / C-04 生产行为（fake 端口） -------------------------------

    @Test
    fun `T-A4-10 binder defers to pre-registered instances and rebuilds only on config change`() {
        val drivers = mutableListOf<FakeDriver>()
        val factory: DriverFactory = { _, _ -> FakeDriver().also { drivers += it } }
        val binder = StorageDriverBinder(mapOf("local" to factory))
        runBlocking {
            // 既有实例（§61.143 playLocal 沙箱注入同型）：binder 不新建、不覆盖。
            val foreign = FakeDriver()
            DriverRegistry.put("a", foreign)
            val first = binder.sync(listOf(storage("a", modified = 1)))
            assertTrue(first.failed.isEmpty())
            assertEquals(listOf("a"), first.created)
            assertEquals(0, drivers.size)
            assertTrue(DriverRegistry.get("a") === foreign)

            // 幂等：同配置再次 sync 仍不动既有实例。
            binder.sync(listOf(storage("a", modified = 1)))
            assertEquals(0, drivers.size)
            assertTrue(DriverRegistry.get("a") === foreign)

            // 配置变更（modified 不同）→ 重建语义：移除既有实例并由 binder 新建。
            binder.sync(listOf(storage("a", modified = 2)))
            assertEquals(1, drivers.size)
            assertTrue(DriverRegistry.get("a") !== foreign)
        }
    }

    @Test
    fun `T-A4-12 LOCAL ref with sufficient JAudio tags skips MediaMetadataRetriever`() {
        var mediaCalls = 0
        val media = object : MediaMetadataPort {
            override fun read(ref: String): MediaTags {
                mediaCalls++
                return MediaTags(title = "不该被调用", durationMs = 1L)
            }
        }
        val jaudio = object : JAudioTaggerPort {
            override fun read(filePath: String) = MediaTags(
                title = "文件标签", artist = "艺人", durationMs = 26_000L, sampleRate = 44_100,
                embeddedCover = byteArrayOf(1),
            )
        }
        var dirCoverCalls = 0
        val reader = AndroidTagReader(media, jaudio, FakeLocator("/files/full.mp3"), EmptyTagReader,
            directoryCover = { _, _ -> dirCoverCalls++ })
        runBlocking {
            val raw = reader.read(candidate())
            assertEquals("文件标签", raw.title)
            assertEquals("艺人", raw.artist)
            assertEquals(26_000L, raw.durationMs)
            assertEquals(44_100, raw.sampleRate)
            assertEquals(0, mediaCalls) // §61.145：title+duration 齐备 → 检索器零调用
            assertEquals(0, dirCoverCalls) // §61.146：有内嵌封面 → 目录封面查找零调用
        }
        val poor = AndroidTagReader(media, jaudio, FakeLocator("/files/poor.mp3"), EmptyTagReader)
        runBlocking {
            // JAudio 缺 duration → 检索器兜底仍要跑；merge 仍按既有 media-first 逐字段优先
            val tagged = object : JAudioTaggerPort {
                override fun read(filePath: String) = MediaTags(title = "只有标题", artist = "兜底艺人")
            }
            val r = AndroidTagReader(media, tagged, FakeLocator("/files/poor.mp3"), EmptyTagReader)
                .read(candidate())
            assertEquals("不该被调用", r.title)   // media-first merge 语义不变
            assertEquals(1L, r.durationMs)
            assertEquals(1, mediaCalls)          // 兜底恰好一次
        }
        runBlocking {
            // §61.147：异构容器（JAudio 读不了 → EMPTY）不得进检索器——白名单外扩展
            // 直接按 B01 骨架降级（此前 APE/WV 上检索器 native 挂死 = 三轮扫描冻结根因）
            val broken = object : JAudioTaggerPort {
                override fun read(filePath: String) = MediaTags.EMPTY
            }
            val exotic = AndroidTagReader(media, broken, FakeLocator("/files/x.ape"), EmptyTagReader)
                .read(candidate())
            assertEquals(1, mediaCalls)          // 白名单外不再调用（仍是 1 次）
            assertEquals(0, exotic.embeddedCover?.size ?: 0)
        }
    }

    @Test
    fun `T-A4-09 tag reader merges local channels and skips JAudioTagger for content uri`() {
        var jaudioCalls = 0
        val media = object : MediaMetadataPort {
            override fun read(ref: String) = MediaTags(title = "标题", durationMs = 1000L)
        }
        val jaudio = object : JAudioTaggerPort {
            override fun read(filePath: String): MediaTags {
                jaudioCalls++
                return MediaTags(artist = "艺人", album = "专辑", embeddedCover = byteArrayOf(1))
            }
        }
        val fileReader = AndroidTagReader(media, jaudio, FakeLocator("/files/a.mp3"), EmptyTagReader)
        runBlocking {
            val raw = fileReader.read(candidate())
            assertEquals("标题", raw.title)
            assertEquals("艺人", raw.artist)
            assertEquals(1.0f, raw.completeness)
            assertEquals(1, jaudioCalls)
        }
        val safReader = AndroidTagReader(media, jaudio, FakeLocator("content://media/7"), EmptyTagReader)
        runBlocking {
            val raw = safReader.read(candidate())
            assertEquals("标题", raw.title)
            assertNull(raw.artist)
            assertEquals(1, jaudioCalls) // content:// 不再调用 JAudioTagger
        }
    }

    @Test
    fun `T-A4-10 tag reader falls back to head reader for network refs and failures`() {
        // §61.203：网络 ref 默认跳过头尾 Range 读 → NameParser 文件名兜底骨架
        // （**非 EMPTY**：仍走 TrackFactory.build 消费目录封面侧链；时长由
        // §61.181 播放期真值通道回填）。
        val skipped = AndroidTagReader(
            media = { MediaTags(title = "不该被调用") },
            jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/a.mp3"),
            head = EmptyTagReader,
        )
        runBlocking {
            val raw = skipped.read(candidate())
            assertEquals("a", raw.title)
            assertEquals(0.2f, raw.completeness)
        }
        // §61.203：读模式（skip=false + 白名单内扩展）→ 仍回落 HeadTagReader（此处 EMPTY）
        val reading = AndroidTagReader(
            media = { MediaTags(title = "不该被调用") },
            jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/a.mp3"),
            head = EmptyTagReader,
            remoteTagPolicy = RemoteTagPolicy(skipRemoteTags = false),
        )
        runBlocking {
            assertEquals(RawTags.EMPTY, reading.read(candidate()))
        }
        val broken = AndroidTagReader(
            media = { throw IllegalStateException("boom") },
            jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("/files/a.mp3"),
            head = EmptyTagReader,
        )
        runBlocking {
            assertEquals(RawTags.EMPTY, broken.read(candidate()))
        }
    }

    @Test
    fun `T-A4-13 remote skip keeps cover and lyrics side channels and returns filename fallback`() {
        var headCalls = 0
        val countingHead = object : TagReader {
            override suspend fun read(candidate: Candidate): RawTags {
                headCalls++
                return RawTags.EMPTY
            }
        }
        var coverCalls = 0
        var lyricsCalls = 0
        val reader = AndroidTagReader(
            media = { MediaTags(title = "不该被调用") },
            jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/Music/%E5%91%A8%E6%9D%B0%E4%BC%A6%20-%20%E6%99%B4%E5%A4%A9.mp3"),
            head = countingHead,
            directoryCover = { _, _ -> coverCalls++ },
            siblingLyrics = { _, _ -> lyricsCalls++ },
        )
        runBlocking {
            val raw = reader.read(candidate(path = "/Music/周杰伦 - 晴天.mp3"))
            assertEquals(0, headCalls)          // §61.203：头尾 Range 读零调用
            assertEquals("晴天", raw.title)      // NameParser 文件名兜底（含 %XX 与 GBK 清洗）
            assertEquals("周杰伦", raw.artist)
            assertEquals(0.2f, raw.completeness)
            assertEquals(1, coverCalls)         // §61.186 缺口修复：网络分支侧链照走
            assertEquals(1, lyricsCalls)
        }
    }

    @Test
    fun `T-A4-14 remote read mode respects whitelist and size threshold`() {
        // 纯策略断言：SKIP 恒跳过；读模式按白名单；容量阈值恒生效
        assertTrue(RemoteTagPolicy.SKIP.shouldSkip("a.mp3", 1L))
        assertFalse(RemoteTagPolicy(skipRemoteTags = false).shouldSkip("a.mp3", 1L))
        assertTrue(RemoteTagPolicy(skipRemoteTags = false).shouldSkip("x.ape", 1L))
        assertTrue(RemoteTagPolicy.SKIP.shouldSkip("a.mp3", Long.MAX_VALUE))
        assertTrue(RemoteTagPolicy(skipRemoteTags = false, maxBytes = 1024L).shouldSkip("a.mp3", 2048L))

        var headCalls = 0
        val countingHead = object : TagReader {
            override suspend fun read(candidate: Candidate): RawTags {
                headCalls++
                return RawTags(title = "头标签")
            }
        }
        // 白名单内（mp3）→ 头读发生
        val reader = AndroidTagReader(
            media = { MediaTags.EMPTY }, jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/a.mp3"), head = countingHead,
            remoteTagPolicy = RemoteTagPolicy(skipRemoteTags = false),
        )
        runBlocking {
            assertEquals("头标签", reader.read(candidate()).title)
            assertEquals(1, headCalls)
        }
        // 白名单外（ape）→ 跳过，头读零新增
        val exotic = AndroidTagReader(
            media = { MediaTags.EMPTY }, jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/x.ape"), head = countingHead,
            remoteTagPolicy = RemoteTagPolicy(skipRemoteTags = false),
        )
        runBlocking {
            assertEquals(0.2f, exotic.read(candidate(path = "/Music/x.ape")).completeness)
            assertEquals(1, headCalls)
        }
        // 容量阈值：读模式下超限同样跳过
        val capped = AndroidTagReader(
            media = { MediaTags.EMPTY }, jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/a.mp3"), head = countingHead,
            remoteTagPolicy = RemoteTagPolicy(skipRemoteTags = false, maxBytes = 1024L),
        )
        runBlocking {
            assertEquals(0.2f, capped.read(candidate()).completeness)
            assertEquals(1, headCalls)
        }
    }

    @Test
    fun `T-A4-15 directory cover reads bytes once per dir and shares ref across candidates`() {
        val driver = object : Driver {
            override val config = DriverConfig(name = "FakeWebdav")
            override suspend fun init(ctx: DriverCtx) {}
            override fun drop() {}
            override suspend fun list(dir: Obj, refresh: Boolean) = listOf(
                Obj(name = "folder.jpg", isDir = false, path = "${dir.path}/folder.jpg", size = 2048L),
            )
            override suspend fun get(path: String): Obj? = null
            override suspend fun link(file: Obj, args: LinkArgs) = Link(url = "https://nas${file.path}")
        }
        DriverRegistry.put("w1", driver)
        var rangeCalls = 0
        val range = object : ByteRangeCapability {
            override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
                rangeCalls++
                return ByteArray(2048)
            }
        }
        val writer = FakeWriter("/cache/covers/folder.webp")
        val cache = CoverLookupCache()
        val lookup = DirectoryCoverLookup(writer, cache, rangeFor = { range })
        val webdavStorage = storage("w1", driverType = "webdav")
        val a = candidate(storage = webdavStorage, path = "/Music/a.mp3")
        val b = candidate(storage = webdavStorage, path = "/Music/b.mp3")
        runBlocking {
            lookup(a, "https://nas/Music/a.mp3")
            lookup(b, "https://nas/Music/b.mp3")
            assertEquals(1, rangeCalls)   // §61.203：封面字节每目录只读一次
            assertEquals("/cache/covers/folder.webp", cache.get("w1", "/Music/a.mp3"))
            assertEquals("/cache/covers/folder.webp", cache.get("w1", "/Music/b.mp3"))
        }
    }

    @Test
    fun `T-A4-16 cover dedup index persists across instances and tolerates corrupt lines`() {
        val indexFile = java.nio.file.Files.createTempFile("dedup", ".txt").toFile()
        try {
            val index = CoverDedupIndex(indexFile)
            index.put("a".repeat(32), "/covers/first.webp")
            index.put("b".repeat(32), "/covers/second.webp")
            assertEquals("/covers/first.webp", index.get("a".repeat(32)))
            // §61.205：跨实例（进程重启）持久命中
            val reopened = CoverDedupIndex(indexFile)
            assertEquals("/covers/first.webp", reopened.get("a".repeat(32)))
            assertEquals("/covers/second.webp", reopened.get("b".repeat(32)))
            // 会话内重复 put 幂等（不重复写行）
            index.put("a".repeat(32), "/covers/first.webp")
            assertEquals(2, indexFile.readLines().filter { it.isNotBlank() }.size)
            // 损坏行跳过（缺字段 / hash 非法长度）
            indexFile.appendText("garbage\n")
            indexFile.appendText("short-hash ref.webp\n")
            val repaired = CoverDedupIndex(indexFile)
            assertEquals("/covers/first.webp", repaired.get("a".repeat(32)))
            assertNull(repaired.get("short-hash"))
        } finally {
            indexFile.delete()
        }
    }

    @Test
    fun `T-A4-11 cover resolver prefers embedded then cached directory then placeholder`() {
        val writer = FakeWriter("/cache/covers/x.webp")
        val cache = CoverLookupCache()
        val resolver: CoverResolver = AndroidCoverResolver(writer, cache, placeholder = "placeholder")
        val c = candidate()
        assertEquals("/cache/covers/x.webp", resolver.resolve(c, byteArrayOf(1, 2, 3)))
        assertEquals(1, writer.writes.size)

        val noEmbedded = AndroidCoverResolver(FakeWriter(null), cache, "placeholder")
        assertEquals("placeholder", noEmbedded.resolve(c, null))
        cache.put("s1", "/Music/a.mp3", "/cache/covers/folder.webp")
        assertEquals("/cache/covers/folder.webp", noEmbedded.resolve(c, null))
    }

    @Test
    fun `T-A4-12 track sink goes through the C-04 reconcilation channel`() {
        val repo = FakeTrackRepository()
        val sink = RoomTrackSink(repo)
        val track = Track(
            id = TrackId("t1"), title = "a", artist = "b", album = "c",
            durationMs = 1_000L,
            sourceRef = com.example.cloudmusic.model.SourceRef("s1", "/a.mp3"),
            sourceType = com.example.cloudmusic.model.SourceType.LOCAL,
        )
        runBlocking {
            sink.upsertBatch(listOf(track))
            assertEquals(1, repo.batches.size)
            // ScanReconciler 在 upsert 之后显式置回 OK（C-04 恢复通道）
            assertEquals(TrackId("t1") to Availability.OK, repo.availability.single())
            sink.markUnavailable(listOf(TrackId("t1")))
            assertEquals(TrackId("t1") to Availability.UNREACHABLE, repo.availability.last())
        }
    }
}
