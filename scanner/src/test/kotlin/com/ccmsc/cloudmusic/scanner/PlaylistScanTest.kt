package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.datasource.Driver
import com.ccmsc.cloudmusic.datasource.DriverConfig
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.datasource.DriverRegistry
import com.ccmsc.cloudmusic.datasource.TextReadCapability
import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.LinkArgs
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.253：m3u/m3u8/pls 在**任意源**内的扫描展开 E2E（walk FileClass.PLAYLIST →
 * expandPlaylistInline）——URL 条目直链 / 相对音频条目 / 嵌套 strm / 坏条目可见化。
 * 用户场景：webdav 源内放 .m3u → 重扫 → 曲库出现展开轨 → 播放（§61.252 url-first 闭环）。
 */
class PlaylistScanTest {

    private val storageId = "s-m3u"

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove(storageId)
    }

    private class FakeDriver(
        private var tree: Map<String, List<Obj>>,
        private val texts: Map<String, String>,
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake-m3u")
        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = tree[dir.path] ?: emptyList()
        override suspend fun get(path: String): Obj? =
            tree.values.flatten().firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }

    private fun obj(path: String, size: Long = 1000, modified: Long = 100) = Obj(
        name = path.substringAfterLast('/'), path = path, size = size, modified = modified, isDir = false,
    )

    private suspend fun runScan(
        root: List<Obj>,
        texts: Map<String, String> = emptyMap(),
        sink: InMemoryTrackSink = InMemoryTrackSink(),
        importSink: PlaylistImportSink? = null,
    ): ScanReport {
        DriverRegistry.put(storageId, FakeDriver(mapOf("" to root), texts))
        return ScanCoordinator(
            storageProvider = object : StorageProvider {
                override suspend fun active(): List<Storage> =
                    listOf(Storage(storageId, "/mnt/$storageId", "webdav", Addition("{}")))
            },
            driverRegistry = DriverRegistry,
            trackSink = sink,
            snapshotStore = InMemoryScanSnapshotStore(),
            tagReader = EmptyTagReader,
            pacer = ScanPacerNoOp,
            playlistImportSink = importSink,
        ).run(ScanRequest(storageIds = setOf(storageId)))
    }

    @Test
    @DisplayName("§61.253 P1 · m3u（URL 条目 + 相对音频条目）展开为曲库轨")
    fun m3uExpandsToTracks() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:200,dollyy99 - 官方回答
            http://nas/d/audio.mp3
            /music/爱就爱了.flac
        """.trimIndent()
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(
                obj("/music/爱就爱了.flac", size = 9000),
                obj("/周华健精选.m3u", size = 300),
            ),
            texts = mapOf("/周华健精选.m3u" to m3u),
            sink = sink,
        )
        assertEquals(0, report.errors.size, "正常 m3u 无错误: ${report.errors}")
        val tracks = sink.all()
        // URL 条目 → PLAYLIST 轨（sourceRef.url = 直链）
        val urlTrack = tracks.first { it.title.contains("audio") || it.sourceRef.url != null }
        assertEquals(SourceType.PLAYLIST, urlTrack.sourceType)
        assertEquals("http://nas/d/audio.mp3", urlTrack.sourceRef.url, "URL 条目直链入 sourceRef")
        // 相对条目 → 同源音频文件轨（相对基准 = m3u 所在目录）
        val audioTrack = tracks.first { it.sourceRef.path == "/music/爱就爱了.flac" }
        assertEquals(SourceType.PLAYLIST, audioTrack.sourceType)
        assertTrue(tracks.size >= 2)
    }

    @Test
    @DisplayName("§61.253 P2 · m3u 指向 .strm → 嵌套展开（strm 直链入 sourceRef）")
    fun nestedStrmEntry() = runTest {
        val m3u = "/官方回答.strm\n"
        val strm = "http://192.168.1.1:15244/d/x.mp3?sign=abc\n"
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(obj("/官方回答.strm"), obj("/歌单.m3u", size = 200)),
            texts = mapOf("/歌单.m3u" to m3u, "/官方回答.strm" to strm),
            sink = sink,
        )
        assertEquals(0, report.errors.size)
        val tracks = sink.all()
        val urlTrack = tracks.first { it.sourceRef.url != null }
        assertEquals("http://192.168.1.1:15244/d/x.mp3?sign=abc", urlTrack.sourceRef.url, "嵌套 strm 直链")
        assertEquals(SourceType.PLAYLIST, urlTrack.sourceType)
    }

    @Test
    @DisplayName("§61.253 P3 · 坏条目（指向不存在文件）→ E_DS_NOT_SUPPORT 可见化，不炸扫描")
    fun badEntryVisible() = runTest {
        val m3u = "/不存在的文件.flac\n"
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(obj("/歌单.m3u", size = 200)),
            texts = mapOf("/歌单.m3u" to m3u),
            sink = sink,
        )
        assertTrue(report.errors.any { it.code == "E_DS_NOT_SUPPORT" }, "坏条目可见化")
    }

    @Test
    @DisplayName("§61.253 P4 · HLS（#EXT-X）整份拒绝可见化；曲库不产生轨")
    fun hlsRejected() = runTest {
        val m3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\nseg0.ts\n"
        val sink = InMemoryTrackSink()
        val report = runScan(
            root = listOf(obj("/直播.m3u8", size = 200)),
            texts = mapOf("/直播.m3u8" to m3u8),
            sink = sink,
        )
        assertTrue(report.errors.any { it.code == "E_DS_NOT_SUPPORT" }, "HLS 拒绝可见化")
        assertEquals(0, sink.all().size, "HLS 不产生轨")
    }

    @Test
    @DisplayName("§61.253 P5 · 扫描→Track→播放解析全链：url-first 消费 sourceRef.url")
    fun scanToPlaybackChain() = runTest {
        val m3u = "http://nas/d/track.mp3\n"
        val sink = InMemoryTrackSink()
        runScan(
            root = listOf(obj("/歌单.m3u", size = 200)),
            texts = mapOf("/歌单.m3u" to m3u),
            sink = sink,
        )
        val track = sink.all().first { it.sourceRef.url != null }
        // §61.252 修复后的 RegistryLinkProvider 口径：有缓存直链 → 直链即播放源
        assertEquals("http://nas/d/track.mp3", track.sourceRef.url)
        assertEquals(SourceType.PLAYLIST, track.sourceType)
        assertEquals(SourceRef(storageId, track.sourceRef.path, "http://nas/d/track.mp3"), track.sourceRef)
    }

    // ------------------------------------------------------------------
    // §61.254：m3u → 歌单页命名实体（扫描导入）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.254 I1 · m3u 展开注册为命名歌单——稳定 id / 名称 / 条目序 trackIds")
    fun importRegistersNamedPlaylist() = runTest {
        val m3u = """
            #EXTINF:200,官方回答
            http://nas/d/audio.mp3
            /music/爱就爱了.flac
        """.trimIndent()
        val imports = mutableListOf<Triple<String, String, List<String>>>()
        val sink = PlaylistImportSink { id, name, ids -> imports += Triple(id, name, ids.map { it.value }) }
        val root = listOf(
            obj("/music/爱就爱了.flac", size = 9000),
            obj("/周华健精选.m3u", size = 300),
        )
        val texts = mapOf("/周华健精选.m3u" to m3u)
        runScan(root = root, texts = texts, importSink = sink)
        assertEquals(1, imports.size)
        val (id, name, ids) = imports[0]
        assertTrue(id.startsWith("pl_imp_"), "导入 id 前缀")
        assertEquals("周华健精选", name, "name = m3u 文件名去扩展名")
        assertEquals(2, ids.size, "URL 条目 + 相对音频条目")
        // 稳定 id：重扫幂等（同路径 → 同 id → upsert 不重复）
        runScan(root = root, texts = texts, importSink = sink)
        assertEquals(2, imports.size)
        assertEquals(id, imports[1].first, "重扫 id 稳定")
    }

    @Test
    @DisplayName("§61.254 I2 · 坏条目不进导入 trackIds（accepted 交集过滤）")
    fun importExcludesRejected() = runTest {
        val m3u = "/不存在的文件.flac\nhttp://nas/d/ok.mp3\n"
        val imports = mutableListOf<Triple<String, String, List<String>>>()
        val sink = PlaylistImportSink { id, name, ids -> imports += Triple(id, name, ids.map { it.value }) }
        runScan(
            root = listOf(obj("/歌单.m3u", size = 200)),
            texts = mapOf("/歌单.m3u" to m3u),
            importSink = sink,
        )
        assertEquals(1, imports.size)
        assertEquals(1, imports[0].third.size, "坏条目被剔除，仅 URL 条目进歌单")
    }
}
