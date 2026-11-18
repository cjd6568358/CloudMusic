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
import com.example.cloudmusic.model.Storage
import org.junit.jupiter.api.DisplayName
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.227：.strm 相对路径引用——扫描链展开为同源 AUDIO 候选（相对 .strm 所在目录，
 * `../` 消解；目标 miss → E_SCAN 可见化）。
 */
class StrmRelativeScanTest {

    private val storageId = "s-strmrel"

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove(storageId)
    }

    private fun obj(path: String, isDir: Boolean = false, size: Long = 1000L) = Obj(
        name = path.substringAfterLast('/'), path = path, size = size, modified = 100L, isDir = isDir,
    )

    private class FakeDriver(
        private val tree: Map<String, List<Obj>>,
        private val texts: Map<String, String>,
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake-strmrel")
        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = tree[dir.path] ?: emptyList()
        override suspend fun get(path: String): Obj? =
            tree.values.flatten().firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = "/files/" + file.path)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }

    private suspend fun runScan(
        root: List<Obj>,
        texts: Map<String, String>,
    ): Pair<ScanReport, InMemoryTrackSink> {
        val sink = InMemoryTrackSink()
        DriverRegistry.put(storageId, FakeDriver(mapOf("" to root), texts))
        val report = ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> =
                    listOf(Storage(storageId, "/mnt", "webdav", Addition("{}")))
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = EmptyTagReader,
            pacer = ScanPacerNoOp,
        ).run(ScanRequest(storageIds = setOf(storageId)))
        return report to sink
    }

    @Test
    @DisplayName("§61.227 · .strm 相对引用同目录音频 → AUDIO 候选（sourceRef=目标路径）")
    fun relativeHit() = runTest {
        val (report, sink) = runScan(
            root = listOf(obj("/m/album.strm", size = 100), obj("/m/song.flac")),
            texts = mapOf("/m/album.strm" to "song.flac\n"),
        )
        assertEquals(0, report.errors.size)
        val audio = sink.all().first { it.sourceRef.path == "/m/song.flac" }
        assertEquals("song", audio.title, "文件名兜底（NameParser 剥扩展名）")
    }

    @Test
    @DisplayName("§61.227 · ../ 消解到上级目录目标")
    fun relativeParentDir() = runTest {
        val (report, sink) = runScan(
            root = listOf(
                obj("/m/sub", isDir = true), obj("/m/sub/a.strm", size = 100),
                obj("/m/top.flac"),
            ),
            texts = mapOf("/m/sub/a.strm" to "../top.flac\n"),
        )
        assertEquals(0, report.errors.size)
        assertTrue(sink.all().any { it.sourceRef.path == "/m/top.flac" })
    }

    @Test
    @DisplayName("§61.227 · 目标 miss → E_SCAN 可见化，不静默")
    fun relativeMiss() = runTest {
        val (report, sink) = runScan(
            root = listOf(obj("/m/broken.strm", size = 100)),
            texts = mapOf("/m/broken.strm" to "missing.flac\n"),
        )
        assertTrue(report.errors.any { it.detail.contains("目标不存在") })
        assertTrue(sink.all().none { it.sourceRef.path.contains("missing") })
    }
}
