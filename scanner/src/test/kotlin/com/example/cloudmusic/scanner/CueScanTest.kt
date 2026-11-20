package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.TextReadCapability
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §61.221：CUE 分轨扫描集成——cue 展开 / 锚点 idPath / 孤儿 / statHit / 元数据跳过。
 * fake 侧完全复用 ScanCoordinatorTest 的模式（内存树 + 文本表）。
 */
class CueScanTest {

    private val storageId = "s-cue"

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove(storageId)
    }

    // ------------------------------------------------------------------
    @Test
    fun `cue 与整轨并存 - 分轨候选带锚点与起止`() = runTest {
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(obj("/m/album.flac", size = 9000), obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
        )
        assertEquals(0, report.errors.size, "正常 cue 不应产生错误")
        val tracks = sink.all()
        // 整轨 1 + 分轨 3
        val whole = tracks.filter { it.cueStartMs == 0L && it.cueEndMs == 0L }
        val cueTracks = tracks.filter { it.isCueTrack }
        assertEquals(1, whole.size)
        assertEquals(3, cueTracks.size)

        val t1 = cueTracks.first { it.trackNo == 1 }
        assertEquals("明天", t1.title)
        assertEquals("窦唯", t1.artist)
        assertEquals("黑梦", t1.album)
        assertEquals(33_000L, t1.cueStartMs)
        assertEquals(270_000L, t1.cueEndMs)
        assertEquals(237_000L, t1.durationMs)
        // sourceRef 保持真实音频路径（播放取流 / driver.get 语义不变）
        assertEquals("/m/album.flac", t1.sourceRef.path)
        assertEquals(SourceType.LOCAL, t1.sourceType)
        // 末轨无界：cueEndMs = 0
        val t3 = cueTracks.first { it.trackNo == 3 }
        assertEquals(492_000L + 37L * 1000L / 75L, t3.cueStartMs)
        assertEquals(0L, t3.cueEndMs)
        assertEquals(0L, t3.durationMs, "无界分轨 durationMs = 0（播放期真值回填）")
    }

    @Test
    fun `分轨 TrackId 用锚点且各轨唯一`() = runTest {
        val sink = InMemoryTrackSink()
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
        )
        val ids = sink.all().filter { it.isCueTrack }.map { it.id.value }
        assertEquals(3, ids.toSet().size, "三轨三 id")
        // 锚点可由 TrackId.of 复算（storage|path 规范化口径）
        val expected = TrackId.of(storageId, "/m/album.flac#album#2")
        assertEquals(expected.value, sink.all().first { it.trackNo == 2 && it.isCueTrack }.id.value)
    }

    @Test
    fun `cue 引用的音频缺失 - 计错误不产分轨`() = runTest {
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
        )
        assertTrue(report.errors.any { it.detail.contains("不在同目录") })
        assertTrue(sink.all().none { it.isCueTrack })
    }

    @Test
    fun `cue 删除 - 分轨标 UNREACHABLE 禁止删除`() = runTest {
        val snapshots = InMemoryScanSnapshotStore()
        val sink = InMemoryTrackSink()
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
            snapshots = snapshots,
        )
        val cueIds = sink.all().filter { it.isCueTrack }.map { it.id }
        assertEquals(3, cueIds.size)
        // 第二轮：cue 没了（音频还在）
        runScan(
            root = listOf(obj("/m/album.flac")),
            sink = sink,
            snapshots = snapshots,
        )
        assertTrue(
            sink.all().filter { it.isCueTrack }.all { it.availability.name == "UNREACHABLE" },
            "分轨标 UNREACHABLE 而非删除",
        )
        assertEquals("OK", sink.all().first { !it.isCueTrack }.availability.name)
    }

    @Test
    fun `cue 内容变化 - 音频 stat 未变仍触发重展开`() = runTest {
        val snapshots = InMemoryScanSnapshotStore()
        val sink = InMemoryTrackSink()
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300, modified = 100)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
            snapshots = snapshots,
        )
        // 第二轮：cue 文件 mtime 变了、文本换轨标题；音频 (size,modified) 未变
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 320, modified = 200)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE_V2),
            sink = sink,
            snapshots = snapshots,
            refresh = true,
        )
        val t1 = sink.all().first { it.isCueTrack && it.trackNo == 1 }
        assertEquals("新标题", t1.title, "cue 变化传导到分轨元数据")
    }

    @Test
    fun `refresh 且 cue 未变 - 分轨 statHit 跳过重 upsert`() = runTest {
        val snapshots = InMemoryScanSnapshotStore()
        val sink = CountingSink()
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300, modified = 100)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
            snapshots = snapshots,
        )
        val firstCount = sink.upserted.get()
        // 第二轮 refresh：音频与 cue stat 全未变 → 全部 statHit（快照 metadataDone 已回写）
        val report = runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300, modified = 100)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
            snapshots = snapshots,
            refresh = true,
        )
        assertEquals(
            sink.upserted.get(), firstCount,
            "statHit 命中 → 阶段 A 零重 upsert（分轨与整轨同口径）",
        )
        assertTrue(report.scanned > 0)
    }

    @Test
    fun `runMetadata 不读整轨 tag - 分轨元数据直接来自 cue`() = runTest {
        val sink = InMemoryTrackSink()
        val tagReader = CountingTagReader()
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            sink = sink,
            tagReader = tagReader,
        )
        assertEquals(1, tagReader.reads.get(), "只有整轨走 tag 读取；三轨分轨由 cueRaw 直接 build")
        val t2 = sink.all().first { it.isCueTrack && it.trackNo == 2 }
        assertEquals("孤单的人", t2.title, "阶段 A 骨架即带 cue 元数据，B01 降级不覆盖")
    }

    @Test
    fun `远程源分轨 - sourceType 跟随存储驱动`() = runTest {
        val sink = InMemoryTrackSink()
        val webdav = Storage(storageId, "/mnt/webdav", "webdav", Addition("{}"))
        runScan(
            root = listOf(obj("/m/album.flac"), obj("/m/album.cue", size = 300)),
            texts = mapOf("/m/album.cue" to SAMPLE_CUE),
            storage = webdav,
            sink = sink,
        )
        assertTrue(sink.all().filter { it.isCueTrack }.all { it.sourceType == SourceType.WEBDAV })
        assertTrue(
            sink.all().filter { it.isCueTrack }.all { it.sourceRef.path.startsWith("/m/") },
            "sourceRef.path 恒为真实音频路径",
        )
    }

    @Test
    fun `同目录两 cue 引用同一音频 - 锚点互不冲突`() = runTest {
        val sink = InMemoryTrackSink()
        runScan(
            root = listOf(
                obj("/m/album.flac"),
                obj("/m/album.cue", size = 300),
                obj("/m/live.cue", size = 300),
            ),
            texts = mapOf(
                "/m/album.cue" to SAMPLE_CUE,
                "/m/live.cue" to SAMPLE_CUE_V2,
            ),
            sink = sink,
        )
        val cueTracks = sink.all().filter { it.isCueTrack }
        assertEquals(6, cueTracks.size, "两 cue × 三轨全独立入库")
        assertEquals(6, cueTracks.map { it.id.value }.toSet().size, "锚点含 cue 文件名，零冲突")
    }

    @Test
    fun `坏 cue 文件按文件路径上报 不得写成数据源级失败`() = runTest {
        val report = runScan(
            root = listOf(obj("/m/bad.cue", size = 100)),
            texts = mapOf("/m/bad.cue" to "not a cue sheet"),
        )
        val err = report.errors.first { it.detail.contains("cue") }
        assertEquals("/m/bad.cue", err.path,
            "文件级 CUE 错误必须带文件路径；写成 storageId 会让 storageErrorStates 踢掉整个源")
    }

    // ------------------------------------------------------------------
    private suspend fun runScan(
        root: List<Obj>,
        texts: Map<String, String> = emptyMap(),
        storage: Storage = Storage(storageId, "/mnt/$storageId", "local", Addition("{}")),
        sink: TrackSink = InMemoryTrackSink(),
        snapshots: InMemoryScanSnapshotStore = InMemoryScanSnapshotStore(),
        tagReader: TagReader = EmptyTagReader,
        refresh: Boolean = false,
    ): ScanReport {
        DriverRegistry.put(storage.id, FakeDriver(mapOf("" to root), texts))
        val report = ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(storage)
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = snapshots,
            tagReader = tagReader,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf(storage.id), refresh = refresh))
        return report
    }

    private fun obj(
        path: String,
        size: Long = 1000,
        modified: Long = 100,
    ) = Obj(
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        modified = modified,
        isDir = false,
    )

    private class FakeDriver(
        private var tree: Map<String, List<Obj>>,
        private val texts: Map<String, String>,
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake-cue")
        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = tree[dir.path] ?: emptyList()
        override suspend fun get(path: String): Obj? =
            tree.values.flatten().firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }

    /** 记录阶段 A upsert 总量的 sink（statHit 断言用）。 */
    private class CountingSink : TrackSink {
        val upserted = java.util.concurrent.atomic.AtomicInteger(0)
        val backing = InMemoryTrackSink()
        override suspend fun upsertBatch(tracks: List<Track>) {
            upserted.addAndGet(tracks.size)
            backing.upsertBatch(tracks)
        }
        override suspend fun markUnavailable(ids: List<TrackId>) = backing.markUnavailable(ids)
        fun all(): List<Track> = backing.all()
    }

    private class CountingTagReader : TagReader {
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun read(candidate: Candidate): RawTags {
            reads.incrementAndGet()
            return RawTags.EMPTY
        }
    }

    companion object {
        private val SAMPLE_CUE = """
            TITLE "黑梦"
            PERFORMER "窦唯"
            FILE "album.flac" WAVE
            TRACK 01 AUDIO
            TITLE "明天"
            INDEX 01 00:33:00
            TRACK 02 AUDIO
            TITLE "孤单的人"
            INDEX 01 04:30:00
            TRACK 03 AUDIO
            TITLE "从命"
            INDEX 01 08:12:37
        """.trimIndent()

        private val SAMPLE_CUE_V2 = """
            TITLE "黑梦"
            PERFORMER "窦唯"
            FILE "album.flac" WAVE
            TRACK 01 AUDIO
            TITLE "新标题"
            INDEX 01 00:33:00
            TRACK 02 AUDIO
            TITLE "孤单的人"
            INDEX 01 04:30:00
            TRACK 03 AUDIO
            TITLE "从命"
            INDEX 01 08:12:37
        """.trimIndent()
    }
}
