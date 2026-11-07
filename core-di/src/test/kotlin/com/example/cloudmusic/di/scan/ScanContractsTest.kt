package com.example.cloudmusic.di.scan

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
        val reader = AndroidTagReader(
            media = { MediaTags(title = "不该被调用") },
            jaudio = { MediaTags.EMPTY },
            locator = FakeLocator("https://nas/a.mp3"),
            head = EmptyTagReader,
        )
        runBlocking {
            assertEquals(RawTags.EMPTY, reader.read(candidate()))
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
