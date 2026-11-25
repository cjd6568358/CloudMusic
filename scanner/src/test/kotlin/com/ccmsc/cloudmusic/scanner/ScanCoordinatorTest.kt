package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.datasource.Driver
import com.ccmsc.cloudmusic.datasource.DriverConfig
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.datasource.DriverRegistry
import com.ccmsc.cloudmusic.datasource.TextReadCapability
import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.LinkArgs
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
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
    @DisplayName("§61.144 metadata 写库按 batchSize 聚合：单行事务 ×N 不得出现")
    fun metadataBatchedUpsert() = runTest {
        val files = (1..30).map { obj("/music/m$it.mp3", size = it.toLong()) }
        DriverRegistry.put("s1", FakeDriver(mapOf("" to files)))
        val recording = RecordingSink()
        val tagged = object : TagReader {
            override suspend fun read(candidate: Candidate): RawTags =
                RawTags(title = candidate.obj.name.substringBeforeLast('.'), completeness = 1f)
        }
        coordinator(listOf(storage()), recording, tagReader = tagged)
            .run(ScanRequest(includeMetadata = true, batchSize = 500))
        // 阶段 A 一次批量(30) + metadata 按 500 聚合为一批(30)——不是 30 个单行事务
        assertEquals(listOf(30, 30), recording.batches)
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

    // ---- §61.204（A1-②③）：refresh stat 命中跳过 + metadataDone 快照回写 ----

    @Test
    @DisplayName("T18 refresh=true + stat 命中且元数据已完成 → 零重读零重 upsert，快照继承 done")
    fun t18() = runTest {
        val file = obj("/music/a.mp3")
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(file))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save(
            "s1",
            listOf(snapshot("s1", file).copy(metadataDone = true)),
        )
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(refresh = true, includeMetadata = true))
        assertEquals(0, counting.reads.get())
        assertEquals(0, sink.all().size, "stat 命中：阶段 A 骨架也不重 upsert（行已在库）")
        assertEquals(0, report.upserted)
        assertEquals(1, report.skipped)
        assertEquals(true, snapshots.load("s1")["/music/a.mp3"]?.metadataDone, "快照继承 done")
    }

    @Test
    @DisplayName("T19 refresh=true + 元数据未完成（上次中断/WiFi 闸）→ 重读补齐并回写 done")
    fun t19() = runTest {
        val file = obj("/music/a.mp3")
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(file))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save("s1", listOf(snapshot("s1", file)))   // metadataDone 默认 false
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(refresh = true, includeMetadata = true))
        assertEquals(1, counting.reads.get(), "metadataDone=false → 必须重读补齐")
        assertEquals(true, snapshots.load("s1")["/music/a.mp3"]?.metadataDone, "块完成即回写 done")
    }

    @Test
    @DisplayName("T20 refresh=true + stat 命中但行不可用（C-04/§61.179）→ 重处理置回 OK")
    fun t20() = runTest {
        val file = obj("/music/a.mp3")
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(file))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save("s1", listOf(snapshot("s1", file).copy(metadataDone = true)))
        val inner = InMemoryTrackSink()
        val counting = CountingTagReader()
        // 行 UNREACHABLE → 不在 knownPaths（「存在且可用」口径）→ 不得被 statHit 跳过
        val sink = KnownPathsSink(inner, emptySet())
        coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(refresh = true, includeMetadata = true))
        assertEquals(1, counting.reads.get(), "行不可用必须重处理（C-04 恢复语义）")
        assertEquals(1, inner.all().size)
    }

    @Test
    @DisplayName("T21 普通增量扫描（refresh=false）语义不变：stat 未变即跳过（T11 口径保持）")
    fun t21() = runTest {
        val file = obj("/music/a.mp3")
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(file))))
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save("s1", listOf(snapshot("s1", file).copy(metadataDone = true)))
        val counting = CountingTagReader()
        val sink = InMemoryTrackSink()
        val report = coordinator(listOf(storage()), sink, snapshots, counting)
            .run(ScanRequest(refresh = false, includeMetadata = true))
        assertEquals(0, counting.reads.get())
        assertEquals(1, report.skipped)
    }

    @Test
    @DisplayName("T22 §61.205 扫描节奏：begin/end 成对（引用计数幂等），块间让步被调用")
    fun t22() = runTest {
        DriverRegistry.put("s1", FakeDriver(mapOf("" to listOf(obj("/music/a.mp3")))))
        val counting = CountingTagReader()
        val pacer = CountingPacer()
        val snapshots = InMemoryScanSnapshotStore()
        coordinator(listOf(storage()), InMemoryTrackSink(), snapshots, counting, pacer = pacer)
            .run(ScanRequest(includeMetadata = true, metadataConcurrency = 1))
        assertEquals(1, pacer.begins.get(), "一次 run → begin 一次")
        assertEquals(1, pacer.ends.get(), "run 结束（含取消路径）→ end 成对")
        assertEquals(1, pacer.yields.get(), "metadata 单块完成 → 让步一次")
        assertEquals(true, snapshots.load("s1")["/music/a.mp3"]?.metadataDone)
    }

    @Test
    @DisplayName("T23 §61.212 strm stat 命中且快照带 URL → 零 readText 复用直链；内容变化才重读")
    fun t23() = runTest {
        val strm = obj("/music/a.strm")
        val url = "https://nas/a.flac"
        val driver = FakeDriver(
            tree = mapOf("" to listOf(strm)),
            texts = mapOf(strm.path to "https://nas/NEW.flac"),
        )
        DriverRegistry.put("s1", driver)
        val snapshots = InMemoryScanSnapshotStore()
        snapshots.save(
            "s1",
            listOf(snapshot("s1", strm, kind = CandidateKind.STRM, directUrl = url)),
        )
        val sink = InMemoryTrackSink()
        // refresh=true 是 URL 复用的主战场（refresh 重扫不再逐文件重读 .strm 内容）
        coordinator(listOf(storage()), sink, snapshots, tagReader = CountingTagReader())
            .run(ScanRequest(refresh = true, includeMetadata = true))
        assertEquals(0, driver.textReads.get(), "stat 命中 + 快照缓存 URL → 免重读 .strm 内容")
        assertEquals(url, sink.all().single().sourceRef.url, "directUrl 复用快照缓存值")

        // 内容变化（size 变）→ 重读并取新 URL
        val changed = obj("/music/a.strm", size = 2000)
        driver.setRoot(listOf(changed))
        snapshots.save(
            "s1",
            listOf(snapshot("s1", strm, kind = CandidateKind.STRM, directUrl = url)),
        )
        coordinator(listOf(storage()), sink, snapshots, tagReader = CountingTagReader())
            .run(ScanRequest(refresh = true, includeMetadata = true))
        assertEquals(1, driver.textReads.get(), "stat 变化 → 重读 .strm 内容")
        assertEquals("https://nas/NEW.flac", sink.all().single().sourceRef.url, "新 URL 入库与快照")
        assertEquals(
            "https://nas/NEW.flac", snapshots.load("s1")["/music/a.strm"]?.directUrl,
            "新 URL 写回快照供下轮复用",
        )
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

    private fun snapshot(
        storageId: String,
        file: Obj,
        kind: CandidateKind = CandidateKind.AUDIO,
        directUrl: String? = null,
    ) = ScanSnapshot(
        storageId = storageId,
        path = file.path,
        size = file.size,
        modified = file.modified,
        kind = kind,
        scannedAt = 0,
        directUrl = directUrl,
    )

    private fun coordinator(
        storages: List<Storage>,
        sink: TrackSink = InMemoryTrackSink(),
        snapshots: ScanSnapshotStore = InMemoryScanSnapshotStore(),
        tagReader: TagReader = EmptyTagReader,
        pacer: ScanPacer = ScanPacerNoOp,
    ) = ScanCoordinator(
        storageProvider = object : StorageProvider {
            override suspend fun active(): List<Storage> = storages
        },
        driverRegistry = DriverRegistry,
        trackSink = sink,
        snapshotStore = snapshots,
        tagReader = tagReader,
        pacer = pacer,
    )

    private class FakeDriver(
        private var tree: Map<String, List<Obj>>,
        private val texts: Map<String, String> = emptyMap(),
    ) : Driver, TextReadCapability {
        /** §61.212：readText 计数（expandStrm URL 复用回归锚）。 */
        var textReads = java.util.concurrent.atomic.AtomicInteger(0)
        override val config = DriverConfig("fake")

        fun setRoot(items: List<Obj>) {
            tree = tree.toMutableMap().also { it[""] = items }
        }

        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = tree[dir.path] ?: emptyList()
        override suspend fun get(path: String): Obj? = tree.values.flatten().firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        override suspend fun readText(file: Obj): String {
            textReads.incrementAndGet()
            return texts[file.path].orEmpty()
        }
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

    /** §61.205：节奏计数 fake。 */
    private class CountingPacer : ScanPacer {
        val begins = AtomicInteger(0)
        val ends = AtomicInteger(0)
        val yields = AtomicInteger(0)
        override fun beginScan() { begins.incrementAndGet() }
        override fun endScan() { ends.incrementAndGet() }
        override suspend fun yieldBetweenChunks() { yields.incrementAndGet() }
    }

    private class RecordingSink : TrackSink {
        val batches = mutableListOf<Int>()
        override suspend fun upsertBatch(tracks: List<Track>) { batches += tracks.size }
        override suspend fun markUnavailable(ids: List<TrackId>) = Unit
    }

    /** §61.204：knownPaths 可控的 sink——stat 命中要求「行存在且可用」，用空集模拟行不可用。 */
    private class KnownPathsSink(
        private val delegate: InMemoryTrackSink,
        private val paths: Set<String>,
    ) : TrackSink by delegate {
        override suspend fun knownPaths(storageId: String): Set<String> = paths
    }
}
