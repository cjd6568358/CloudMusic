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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.323（B-2）：metadata 并发预算是**全源共享**还是**每源一份**。
 *
 * 判据是行为级的：两源各 4 曲、预算 2 →
 * - 全局共享：同时在读 tag 的协程峰值 = 2（且必须真的并发到 2，否则串行也能"通过"）
 * - 每源一份（§61.322 及以前）：峰值 = 4，本测试 FAIL
 *
 * 只查源码里 `Semaphore(...)` 的位置是判不出共享语义的（构造位置可被包一层骗过）。
 */
class ScanConcurrencyBudgetTest {

    private val storageA = Storage("s-a", "/mnt/s-a", "local", Addition("{}"))
    private val storageB = Storage("s-b", "/mnt/s-b", "local", Addition("{}"))

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove("s-a")
        DriverRegistry.remove("s-b")
    }

    private class Probe(private val holdMs: Long) : TagReader {
        private val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val reads = AtomicInteger()

        override suspend fun read(candidate: Candidate): RawTags {
            reads.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                delay(holdMs)
                return RawTags.EMPTY
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class Sink : TrackSink {
        val tracks = mutableMapOf<String, Track>()
        override suspend fun upsertBatch(items: List<Track>) {
            items.forEach { tracks[it.id.value] = it }
        }
        override suspend fun markUnavailable(ids: List<TrackId>) {
            ids.forEach { tracks.remove(it.value) }
        }
        override suspend fun trackedStorageIds(): Set<String> =
            tracks.values.map { it.sourceRef.storageId }.toSet()
        override suspend fun trackIdsByStorage(storageId: String): List<TrackId> =
            tracks.values.filter { it.sourceRef.storageId == storageId }.map { it.id }
    }

    private fun fakeDriver(tag: String) = object : Driver {
        override val config = DriverConfig("fake-$tag")
        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> =
            (1..4).map { Obj(name = "$tag-$it.flac", path = "/$tag-$it.flac", isDir = false) }
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
    }

    @Test
    @DisplayName("§61.323 B-2 · 两源各 4 曲、预算 2 → 在读 tag 峰值 = 2（全源共享）")
    fun metadataBudgetIsSharedAcrossStorages() = runTest {
        DriverRegistry.put("s-a", fakeDriver("a"))
        DriverRegistry.put("s-b", fakeDriver("b"))
        val probe = Probe(holdMs = 50)
        val sink = Sink()

        val report = ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(storageA, storageB)
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = probe,
            pacer = ScanPacerNoOp,
        ).run(
            ScanRequest(
                storageIds = setOf("s-a", "s-b"),
                metadataConcurrency = 2,
                storageConcurrency = 4,
            ),
        )

        assertEquals(8, probe.reads.get(), "两源共 8 曲都读了 tag")
        assertTrue(probe.peak.get() <= 2, "在跑 tag 读取峰值 ${probe.peak.get()} 超过全局预算 2（每源一份的老行为 = 4）")
        assertTrue(probe.peak.get() >= 2, "峰值 ${probe.peak.get()} < 2——测试没造出并发，判据失去意义")
        assertEquals(8, sink.tracks.size, "8 首全部入库")
        assertTrue(report.errors.isEmpty(), "无错误：${report.errors}")
    }

    @Test
    @DisplayName("§61.323 B-2 · 单源预算 2 → 峰值仍为 2（共享不改变单源上限）")
    fun singleStorageKeepsBudget() = runTest {
        DriverRegistry.put("s-a", fakeDriver("a"))
        val probe = Probe(holdMs = 50)
        val sink = Sink()

        ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> = listOf(storageA)
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = probe,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf("s-a"), metadataConcurrency = 2))

        assertEquals(4, probe.reads.get())
        assertTrue(probe.peak.get() in 2..2, "单源峰值 ${probe.peak.get()} 应为 2")
    }
}
