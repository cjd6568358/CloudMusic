package com.example.cloudmusic.playlist

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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/03-03 §7 T12 / T13 / T15 与 §3.4 递归展开。 */
class PlaylistResolverTest {

    @BeforeEach
    fun reset() = DriverRegistry.reset()

    @Test
    @DisplayName("T12 A→B→A 循环引用不死循环")
    fun t12() = runTest {
        val a = Obj(name = "A.m3u", path = "/music/A.m3u")
        val b = Obj(name = "B.m3u", path = "/music/B.m3u")
        val source = FakeSource(
            texts = mapOf(a.path to "B.m3u", b.path to "A.m3u"),
            objects = mapOf(a.path to a, b.path to b),
        )
        val expanded = expandPlaylist(a, source, "/music")
        assertTrue(expanded.isEmpty())
    }

    @Test
    @DisplayName("T13 超过 MAX_DEPTH=5 的嵌套被标记")
    fun t13() = runTest {
        val files = mutableMapOf<String, String>()
        val objects = mutableMapOf<String, Obj>()
        repeat(8) { i ->
            val path = "/music/l$i.m3u"
            files[path] = "l${i + 1}.m3u"
            objects[path] = Obj(name = "l$i.m3u", path = path)
        }
        val start = objects.getValue("/music/l0.m3u")
        val expanded = expandPlaylist(start, FakeSource(files, objects), "/music")
        assertTrue(expanded.any { it.playlistRejection() == "播放列表嵌套过深" })
    }

    @Test
    @DisplayName("T15 混合 URL / 本地相对 / .strm 条目分别产出正确 Link")
    fun t15() = runTest {
        val playlist = Obj(name = "mix.m3u", path = "/music/mix.m3u")
        val local = Obj(name = "a.flac", path = "/music/a.flac", size = 128)
        val strm = Obj(name = "b.strm", path = "/music/b.strm")
        val content = """
            #EXTM3U
            https://nas/remote.flac?sign=1
            a.flac
            b.strm
        """.trimIndent()
        val fake = FakeTextDriver(
            texts = mapOf(
                playlist.path to content,
                strm.path to "https://nas/strm.flac",
            ),
            listed = listOf(playlist),
            objects = mapOf(local.path to local, strm.path to strm, playlist.path to playlist),
        )
        DriverRegistry.put("s1", fake)
        val driver = PlaylistDriver(
            Storage("s2", "/pl", "playlist", Addition("""{"delegate_storage_id":"s1","root_folder_path":"/music"}""")),
            """{"delegate_storage_id":"s1","root_folder_path":"/music"}""",
            DriverRegistry,
        )
        driver.init(DriverCtx())

        val expanded = driver.list(Obj(name = "music", isDir = true, path = "/music"), false)
        assertEquals(3, expanded.size)
        // localSort=true：list 按 name 排序，不假设解析顺序
        val byName = expanded.associateBy { it.name }
        val remoteLink = driver.link(byName.getValue("remote.flac"))
        val localLink = driver.link(byName.getValue("a.flac"))
        val strmLink = driver.link(byName.getValue("b.strm"))
        assertEquals(Link.Kind.URL, remoteLink.kind)
        assertEquals("https://nas/remote.flac?sign=1", remoteLink.url)
        assertEquals(Link.Kind.LOCAL, localLink.kind)
        assertEquals(Link.Kind.URL, strmLink.kind)
        assertEquals("https://nas/strm.flac", strmLink.url)
    }

    private class FakeSource(
        private val texts: Map<String, String>,
        private val objects: Map<String, Obj>,
    ) : PlaylistSource {
        override suspend fun readText(path: String): String? = texts[path]
        override suspend fun find(path: String): Obj? = objects[path]
    }

    private class FakeTextDriver(
        private val texts: Map<String, String>,
        private val listed: List<Obj>,
        private val objects: Map<String, Obj>,
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake")
        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() = Unit
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = listed
        override suspend fun get(path: String): Obj? = objects[path]
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path, localSize = file.size)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }
}
