package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.TextReadCapability
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/03-04 §14 T11~T17、T16 与 §13 边界 B07 / B11 / B12。 */
class ScanCoordinatorTest {

    @BeforeEach
    fun reset() = DriverRegistry.reset()

    @Test
    @DisplayName("T11 快照 (size, modified) 一致 → 不触发 TagReader.read、不 upsert")
    fun t11() = runTest {
        val file = obj("/music/a.mp3")
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(file))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save("s1", listOf(snapshot("s1", file)))
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(includeMetadata = true))
        assertEquals(0, counting.reads.get())
        assertEquals(0, sink.all().size)
        assertEquals(1, report.skipped)
    }

    @Test
    @DisplayName("T12 文件变更 → 重新解析并 upsert")
    fun t12() = runTest {
        val changed = obj("/music/a.mp3", size = 2000, modified = 999)
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(changed))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save("s1", listOf(snapshot("s1", obj("/music/a.mp3"))))
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(includeMetadata = true))
        assertEquals(1, report.upserted)
        assertEquals(1, counting.reads.get())
        assertEquals(1, sink.all().size)
    }

    @Test
    @DisplayName("T13 文件消失 → UNREACHABLE（不删除），恢复出现 → 自动 OK")
    fun t13() = runTest {
        val file = obj("/music/a.mp3")
        val driver = FakeDriver(mapOf("" to listOf(file)))
        DriverRegistry.put("s1", driver)
        val sink = InMemoryTrackSink()
        val snapshots = InMemoryScanSnapshotStore()
        val c = coordinator(listOf(storage()), sink, snapshots)
        c.run(ScanRequest(includeMetadata = false))
        val id = sink.all().single().id
        driver.setRoot(emptyList())
        c.run(ScanRequest(includeMetadata = false))
        assertEquals(Availability.UNREACHABLE, sink.find(id)!!.availability)
        driver.setRoot(listOf(file))
        c.run(ScanRequest(includeMetadata = false))
        assertEquals(Availability.OK, sink.find(id)!!.availability)
        assertEquals(1, sink.all().size, "条目全程未被删除")
    }

    @Test
    @DisplayName("T14 中途取消 → 正常结束、已完成部分保留、底层调用被取消")
    fun t14() = runTest {
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(obj("/music/a.mp3")))))
        val entered = CompletableDeferred<Unit>()
        val hanging = HangingDriver(entered)
        DriverRegistry.put("s2", hanging)
        val sink = InMemoryTrackSink()
        val c = coordinator(listOf(storage("s1"), storage("s2")), sink)
        var report: ScanReport? = null
        val job = launch { report = c.run(ScanRequest(includeMetadata = false)) }
        entered.await()   // 确定性等待：s2 已进入挂起的 list
        job.cancelAndJoin()
        assertTrue(hanging.cancelled)
        assertEquals(true, report?.cancelled)
        assertTrue(sink.all().isNotEmpty(), "已完成 Storage 的入库结果保留")
    }

    @Test
    @DisplayName("T16 阶段 A 结束即可搜到曲目，阶段 B 由开关控制")
    fun t16() = runTest {
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(obj("/music/a.mp3")))))
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink, tagReader = counting)
            .run(ScanRequest(includeMetadata = false))
        assertEquals(1, sink.all().size)
        assertEquals(0.2f, sink.all().single().tagCompleteness)
        assertEquals(0, counting.reads.get())
        assertEquals(true, report.stageAFinished)
    }

    @Test
    @DisplayName("T17 / B07 一个 Storage 连不上，其它 Storage 照常完成并汇总错误")
    fun t17() = runTest {
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(obj("/music/a.mp3")))))
        // s2 故意不注册 Driver
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage("s1"), storage("s2")), sink)
            .run(ScanRequest(includeMetadata = false))
        assertEquals(1, sink.all().size)
        assertTrue(report.errors.any { it.code == "E_SCAN_STORAGE_UNREACHABLE" })
    }

    @Test
    @DisplayName("03-04 §5 内联展开：.strm 直接解析为 URL 曲目，不实例化 StrmDriver")
    fun inlineStrm() = runTest {
        val strm = obj("/music/周杰伦 - 晴天.strm")
        val url = "https://nas/a.flac?sign=abc&e=123"
        DriverRegistry.put(
            "s1",
            FakeDriver(
                tree = mapOf("" to listOf(strm)),
                texts = mapOf(strm.path to "# 注释\n$url"),
            ),
        )
        val sink = InMemoryTrackSink()
        coordinator(listOf(storage()), sink).run(ScanRequest(includeMetadata = false))
        val track = sink.all().single()
        assertEquals(SourceType.STRM, track.sourceType)
        assertEquals(url, track.sourceRef.url)
        assertEquals("晴天", track.title)
        assertEquals("周杰伦", track.artist)
    }

    @Test
    @DisplayName("03-04 §5 内联展开：播放列表展开出多个候选")
    fun inlinePlaylist() = runTest {
        val list = obj("/music/list.m3u")
        DriverRegistry.put(
            "s1",
            FakeDriver(
                tree = mapOf("" to listOf(list)),
                texts = mapOf(list.path to "#EXTM3U\nhttps://x/a.flac\nhttps://x/b.flac"),
            ),
        )
        val sink = InMemoryTrackSink()
        coordinator(listOf(storage()), sink).run(ScanRequest(includeMetadata = false))
        assertEquals(2, sink.all().size)
        assertTrue(sink.all().all { it.sourceType == SourceType.PLAYLIST })
        assertTrue(sink.all().all { it.sourceRef.url != null })
    }

    @Test
    @DisplayName("B11 .nomedia 命中 → 整个子树跳过，不入库")
    fun nomediaSubtree() = runTest {
        val root = obj("/music/a.mp3")
        val sub = obj("/music/sub", isDir = true, size = 0)
        val hidden = obj("/music/sub/hidden.mp3")
        DriverRegistry.put(
            "s1",
            FakeDriver(
                mapOf(
                    "" to listOf(root, sub),
                    "/music/sub" to listOf(obj("/music/sub/.nomedia"), hidden),
                ),
            ),
        )
        val sink = InMemoryTrackSink()
        coordinator(listOf(storage()), sink).run(ScanRequest(includeMetadata = false))
        assertEquals(1, sink.all().size)
        assertEquals("a", sink.all().single().title)   // 文件名兜底去扩展名
    }

    @Test
    @DisplayName("B12 / §10 批量 upsert：1000 条按 500 分两批，禁止逐条写库")
    fun batchedUpsert() = runTest {
        val files = (1..1000).map { obj("/music/f$it.mp3", size = it.toLong()) }
        DriverRegistry.put("s1", FakeDriver(mapOf("" to files)))
        val recording = RecordingSink()
        val report = coordinator(listOf(storage()), recording)
            .run(ScanRequest(includeMetadata = false, batchSize = 500))
        assertEquals(listOf(500, 500), recording.batches)
        assertEquals(1000, report.upserted)
    }

    @Test
    @DisplayName("失败汇总：非 http scheme 的 strm 记 E_LINK_BAD_SCHEME，不中断扫描")
    fun failureSummary() = runTest {
        val bad = obj("/music/bad.strm")
        val good = obj("/music/good.mp3")
        DriverRegistry.put(
            "s1",
            FakeDriver(
                tree = mapOf("" to listOf(bad, good)),
                texts = mapOf(bad.path to "smb://nas/a.flac"),
            ),
        )
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink).run(ScanRequest(includeMetadata = false))
        assertEquals(1, sink.all().size)
        assertTrue(report.errors.any { it.code == "E_LINK_BAD_SCHEME" })
    }

    // ------------------------------------------------------------------
    private fun storage(id: String = "s1") = Storage(id, "/mnt/$id", "local", Addition("{}"))

    private fun obj(
        path: String,
        size: Long = 1000,
        modified: Long = 100,
        isDir: Boolean = false,
    ) = Obj(
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        modified = modified,
        isDir = isDir,
    )

    private fun snapshot(storageId: String, file: Obj) = ScanSnapshot(
        storageId = storageId,
        path = file.path,
        size = file.size,
        modified = file.modified,
        kind = CandidateKind.AUDIO,
        scannedAt = 0,
    )

    private fun coordinator(
        storages: List<Storage>,
        sink: TrackSink = InMemoryTrackSink(),
        snapshots: ScanSnapshotStore = InMemoryScanSnapshotStore(),
        tagReader: TagReader = EmptyTagReader,
    ) = ScanCoordinator(
        storageProvider = object : StorageProvider {
            override suspend fun active(): List<Storage> = storages
        },
        driverRegistry = DriverRegistry,
        trackSink = sink,
        snapshotStore = snapshots,
        tagReader = tagReader,
    )

    private class FakeDriver(
        private var tree: Map<String, List<Obj>>,
        private val texts: Map<String, String> = emptyMap(),
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake")

        fun setRoot(items: List<Obj>) {
            tree = tree.toMutableMap().also { it[""] = items }
        }

        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = tree[dir.path] ?: emptyList()
        override suspend fun get(path: String): Obj? = tree.values.flatten().firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }

    private class HangingDriver(
        private val entered: CompletableDeferred<Unit>,
    ) : Driver {
        override val config = DriverConfig("hanging")

        @Volatile
        var cancelled = false

        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> =
            suspendCancellableCoroutine { cont ->
                entered.complete(Unit)
                cont.invokeOnCancellation { cancelled = true }
            }
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(proxy = true)
    }

    private class CountingTagReader : TagReader {
        val reads = AtomicInteger(0)
        override suspend fun read(candidate: Candidate): RawTags {
            reads.incrementAndGet()
            return RawTags.EMPTY
        }
    }

    private class RecordingSink : TrackSink {
        val batches = mutableListOf<Int>()
        override suspend fun upsertBatch(tracks: List<Track>) { batches += tracks.size }
        override suspend fun markUnavailable(ids: List<TrackId>) = Unit
    }
}
