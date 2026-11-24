package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §61.255：孤儿源 sweep——曲库中存在、已不在 active 清单的源轨道 → UNREACHABLE + 错误可见。 */
class OrphanSweepTest {

    private val liveStorage = Storage("s-live", "/mnt/s-live", "local", Addition("{}"))

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove("s-live")
    }

    /** 记录型 sink：双源轨道 + 标记捕获（trackedStorageIds/trackIdsByStorage 真实现）。 */
    private class RecordingSink : TrackSink {
        val tracks = mutableMapOf<String, Track>()
        val markedIds = mutableListOf<String>()
        override suspend fun upsertBatch(items: List<Track>) {
            items.forEach { tracks[it.id.value] = it }
        }
        override suspend fun markUnavailable(ids: List<TrackId>) {
            ids.forEach { markedIds += it.value; tracks[it.value] = tracks[it.value]!! }
        }
        override suspend fun trackedStorageIds(): Set<String> =
            tracks.values.map { it.sourceRef.storageId }.toSet()
        override suspend fun trackIdsByStorage(storageId: String): List<TrackId> =
            tracks.values.filter { it.sourceRef.storageId == storageId }.map { it.id }
    }

    private fun track(id: String, storageId: String) = Track(
        id = TrackId(id), title = "t-$id", artist = "a", album = "al", durationMs = 100_000L,
        sourceRef = SourceRef(storageId, "/$id.flac"), sourceType = SourceType.LOCAL,
    )

    private suspend fun runScan(sink: RecordingSink): ScanReport {
        DriverRegistry.put("s-live", object : Driver {
            override val config = DriverConfig("fake-live")
            override suspend fun init(ctx: DriverCtx) = Unit
            override fun drop() = Unit
            override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> =
                listOf(Obj(name = "live.flac", path = "/live.flac", isDir = false))
            override suspend fun get(path: String): Obj? = null
            override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        })
        return ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(liveStorage)
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = EmptyTagReader,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf("s-live")))
    }

    @Test
    @DisplayName("§61.255 · 非 active 源的轨道 → UNREACHABLE + E_SCAN_ORPHAN_SOURCE 可见")
    fun orphanMarkedAndVisible() = runTest {
        val sink = RecordingSink()
        sink.upsertBatch(listOf(
            track("live-1", "s-live"),       // active 源轨道 → 不动
            track("orph-1", "s-dead"),       // 孤儿源轨道 → 标记
        ))
        val report = runScan(sink)
        assertTrue(sink.markedIds.contains("orph-1"), "孤儿源轨道被标记")
        assertEquals(1, sink.markedIds.size, "active 源轨道零误伤")
        assertTrue(report.errors.any { it.code == "E_SCAN_ORPHAN_SOURCE" }, "错误可见化")
    }

    @Test
    @DisplayName("§61.345 / G-4 · 配置存在但 init 失败的源 → 不当孤儿、轨道不标灰")
    fun initFailedSourceIsNotOrphan() = runTest {
        val sink = RecordingSink()
        sink.upsertBatch(listOf(
            track("live-1", "s-live"),
            track("flaky-1", "s-flaky"),   // 配置在、但 active() 剔除（模拟 SAF 授权过期）
        ))
        val report = ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(liveStorage)
                override suspend fun configured(): List<Storage> = listOf(
                    liveStorage,
                    Storage("s-flaky", "/mnt/flaky", "local", Addition("{}")),
                )
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = EmptyTagReader,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf("s-live")))
        assertEquals(0, sink.markedIds.size, "init 失败 ≠ 源被删除，不得标灰")
        assertTrue(report.errors.none { it.code == "E_SCAN_ORPHAN_SOURCE" })
    }

    @Test
    @DisplayName("§61.255 · 无孤儿 → 零标记零错误（定向重扫不误标其它源口径）")
    fun noOrphansNoMarks() = runTest {
        val sink = RecordingSink()
        sink.upsertBatch(listOf(track("live-1", "s-live"), track("other-1", "s-live2")))
        // active = [s-live, s-live2]（全量口径）→ s-live2 非孤儿
        DriverRegistry.put("s-live", object : Driver {
            override val config = DriverConfig("fake-live")
            override suspend fun init(ctx: DriverCtx) = Unit
            override fun drop() = Unit
            override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> =
                listOf(Obj(name = "live.flac", path = "/live.flac", isDir = false))
            override suspend fun get(path: String): Obj? = null
            override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        })
        val report = ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(
                    liveStorage,
                    Storage("s-live2", "/mnt/s-live2", "local", Addition("{}")),
                )
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = EmptyTagReader,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf("s-live")))
        assertEquals(0, sink.markedIds.size, "全量 active 口径：定向扫不误标")
        assertTrue(report.errors.none { it.code == "E_SCAN_ORPHAN_SOURCE" })
    }
}
