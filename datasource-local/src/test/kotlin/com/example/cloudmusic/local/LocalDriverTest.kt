package com.example.cloudmusic.local

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第十九批（`12 §28`，T-A3）`LocalDriver` 的纯 JVM 契约测试。
 * 用 fake [LocalDirectorySource] 覆盖 03-02 §3 的 Addition / 双通道 / 沙箱 / 过滤 / link，
 * 不实例化任何 Android 框架类；Android 生产适配的纯映射逻辑也在此覆盖。
 */
class LocalDriverTest {

    private val rootPath = "/storage/emulated/0/Music"

    private fun storage(): Storage = Storage(
        id = "local-1",
        mountPath = "/local",
        driverType = "local",
        addition = Addition("{}"),
    )

    private fun addition(
        root: String = rootPath,
        tree: String = "",
        extra: String = "",
    ): String = buildString {
        append('{')
        append("\"root_folder_path\":\"").append(root).append('"')
        if (tree.isNotEmpty()) append(",\"tree_uri\":\"").append(tree).append('"')
        if (extra.isNotEmpty()) append(',').append(extra)
        append('}')
    }

    private class FakeSource(
        override val channel: LocalChannel,
        var entries: List<LocalEntry> = emptyList(),
        var valid: Boolean = true,
    ) : LocalDirectorySource {
        val listedPaths = mutableListOf<String>()
        val foundPaths = mutableListOf<String>()

        override suspend fun list(root: LocalRoot, absolutePath: String): List<LocalEntry> {
            listedPaths += absolutePath
            return entries
        }

        override suspend fun find(root: LocalRoot, absolutePath: String): LocalEntry? {
            foundPaths += absolutePath
            return entries.firstOrNull { it.path == absolutePath }
        }

        override fun permissionValid(root: LocalRoot): Boolean = valid
    }

    private fun entry(
        name: String,
        isDir: Boolean = false,
        size: Long = 1_024L,
        uri: String = "content://media/$name",
        symlink: Boolean = false,
    ) = LocalEntry(
        name = name,
        path = "$rootPath/$name",
        isDir = isDir,
        size = size,
        modified = 1_700_000_000L,
        uri = uri,
        symlink = symlink,
    )

    private fun driver(
        raw: String = addition(),
        saf: LocalDirectorySource? = null,
        mediaStore: LocalDirectorySource? = null,
    ) = LocalDriver(storage(), raw, saf, mediaStore)

    // ---- 03-02 §3.1 Addition -------------------------------------------------

    @Test
    fun `T-A3-01 addition defaults and Go-Zig key compatibility`() {
        assertEquals(LocalAddition.DEFAULTS, LocalAddition.parse("not json"))
        val defaults = LocalAddition.from(Json.parseToJsonElement("{}").jsonObject)
        assertTrue(defaults.showHidden)
        assertFalse(defaults.followSymlinks)
        assertEquals(listOf("Android", ".thumbnails"), defaults.ignoreDirs)
        assertEquals(10_000L, defaults.minDurationMs)

        val custom = LocalAddition.parse(
            addition(root = "/sd/Music", extra = """
                "show_hidden":false,"follow_symlinks":true,
                "ignore_dirs":["Cache",".git"],"min_duration_ms":3000
            """.trimIndent()),
        )
        assertEquals("/sd/Music", custom.rootFolderPath)
        assertFalse(custom.showHidden)
        assertTrue(custom.followSymlinks)
        assertEquals(listOf("Cache", ".git"), custom.ignoreDirs)
        assertEquals(3_000L, custom.minDurationMs)

        val zig = LocalAddition.parse("""{"root_path":"/legacy/Music"}""")
        assertEquals("/legacy/Music", zig.rootFolderPath)
    }

    // ---- 03-02 §3.3 路径沙箱与 SAF docId 映射 --------------------------------

    @Test
    fun `T-A3-02 treeUri parses to a documentId`() {
        assertEquals(
            "primary:Music",
            LocalPathMapping.treeDocumentId(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            ),
        )
        assertNull(LocalPathMapping.treeDocumentId("file:///storage/emulated/0/Music"))
    }

    @Test
    fun `T-A3-03 SAF documentId round-trips and rejects out-of-tree ids`() {
        val root = LocalRoot.from(LocalAddition(rootFolderPath = rootPath))
        assertEquals("/", LocalPathMapping.relativePath(root, rootPath))
        assertEquals(rootPath + "/Jazz/a.mp3", LocalPathMapping.absolutePath(root, "Jazz/a.mp3"))
        assertEquals("/Jazz/a.mp3", LocalPathMapping.relativePath(root, rootPath + "/Jazz/a.mp3"))

        for (tree in listOf("primary:Music", "primary:")) {
            val rel = "/Jazz/a.mp3"
            val docId = LocalPathMapping.safDocumentId(tree, rel)
            assertEquals(rel, LocalPathMapping.safRelativeFromDocumentId(tree, docId))
            assertEquals(tree, LocalPathMapping.safDocumentId(tree, "/"))
        }
        assertEquals(
            "primary:Music/Jazz/a.mp3",
            LocalPathMapping.safDocumentId("primary:Music", "/Jazz/a.mp3"),
        )
        assertNull(
            LocalPathMapping.safRelativeFromDocumentId("primary:Music", "primary:DCIM/x.jpg"),
        )
        assertTrue(LocalPathMapping.isValidEntryName("a.mp3"))
        assertFalse(LocalPathMapping.isValidEntryName("a/b.mp3"))
        assertFalse(LocalPathMapping.isValidEntryName(".."))
    }

    @Test
    fun `T-A3-04 MediaStore flat rows are grouped into directories`() {
        val rows = listOf(
            LocalEntry("a.mp3", "$rootPath/Jazz/a.mp3", false),
            LocalEntry("b.mp3", "$rootPath/Jazz/b.mp3", false),
            LocalEntry("c.flac", "$rootPath/Rock/Sub/c.flac", false),
            LocalEntry("top.mp3", "$rootPath/top.mp3", false),
            LocalEntry("other.mp3", "/elsewhere/other.mp3", false),
        )
        val grouped = LocalEntryGrouper.group(rootPath, rows)
        assertEquals(setOf("Jazz", "Rock", "top.mp3"), grouped.map { it.name }.toSet())
        assertTrue(grouped.first { it.name == "Jazz" }.isDir)
        assertTrue(grouped.first { it.name == "top.mp3" }.let { !it.isDir })
        assertTrue(grouped.none { it.name == "other.mp3" })
    }

    // ---- 03-02 §3.2 / §3.4 init 与双通道 ------------------------------------

    @Test
    fun `T-A3-05 init rejects missing root and expired SAF permission`() {
        // 语句体：表达式体 `= runBlocking { assertFailsWith{...} }` 返回类型非 Unit，
        // JUnit Jupiter 会静默跳过（12 §27 D-56；本批静态自检已加守护）。
        runBlocking {
            assertFailsWith<CloudMusicError.DsInvalidConfig> {
                driver(raw = """{"root_folder_path":""}""").init(DriverCtx())
            }
            val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
            val expired = FakeSource(LocalChannel.SAF, valid = false)
            assertFailsWith<CloudMusicError.DsInvalidConfig> {
                driver(raw = addition(tree = tree), saf = expired).init(DriverCtx())
            }
            assertFailsWith<CloudMusicError.DsInvalidConfig> {
                driver(raw = addition(tree = "not-a-tree-uri"), saf = expired).init(DriverCtx())
            }
        }
    }

    @Test
    fun `T-A3-06 both channels produce identical Obj trees`() = runBlocking {
        val entries = listOf(
            entry("b.mp3"),
            entry("A.flac"),
            entry("Jazz", isDir = true),
        )
        val saf = FakeSource(LocalChannel.SAF, entries)
        val media = FakeSource(LocalChannel.MEDIA_STORE, entries)
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

        val viaSaf = driver(raw = addition(tree = tree), saf = saf)
        val viaMedia = driver(raw = addition(), mediaStore = media)
        viaSaf.init(DriverCtx())
        viaMedia.init(DriverCtx())

        val a = viaSaf.list(Obj(name = "Music", isDir = true, path = "/"))
        val b = viaMedia.list(Obj(name = "Music", isDir = true, path = "/"))
        assertEquals(a, b)
        assertEquals(listOf("A.flac", "b.mp3", "Jazz"), a.map { it.name })
        assertEquals(listOf("/A.flac", "/b.mp3", "/Jazz"), a.map { it.path })
        assertEquals(listOf(rootPath), saf.listedPaths)
        assertEquals(listOf(rootPath), media.listedPaths)
        assertEquals("Local", viaSaf.config.name)
        assertTrue(viaSaf.config.localSort)
    }

    @Test
    fun `T-A3-07 list filters hidden, ignored dirs, symlinks and bad names`() = runBlocking {
        val entries = listOf(
            entry("keep.mp3"),
            entry(".hidden.mp3"),
            entry("Android", isDir = true),
            entry("Cache", isDir = true),
            entry("link.mp3", symlink = true),
            entry("bad/name.mp3"),
            entry("song.mp3", isDir = false),
        )
        val source = FakeSource(LocalChannel.SAF, entries)
        val d = driver(
            raw = addition(extra = "\"show_hidden\":false,\"ignore_dirs\":[\"Android\",\"Cache\"]"),
            mediaStore = source,
        )
        d.init(DriverCtx())
        val names = d.list(Obj(name = "Music", isDir = true, path = "/")).map { it.name }
        assertEquals(listOf("keep.mp3", "song.mp3"), names)
    }

    @Test
    fun `T-A3-08 get returns null for missing and maps entry to Obj`() = runBlocking {
        val e = entry("a.mp3", size = 2_048L, uri = "content://media/7")
        val source = FakeSource(LocalChannel.MEDIA_STORE, listOf(e))
        val d = driver(mediaStore = source)
        d.init(DriverCtx())

        assertNull(d.get("missing.mp3"))
        val obj = d.get("a.mp3")
        assertEquals("a.mp3", obj?.name)
        assertEquals("/a.mp3", obj?.path)
        assertEquals(2_048L, obj?.size)
        assertEquals("content://media/7", obj?.priv)
        assertTrue(source.foundPaths.all { it.startsWith(rootPath) })
    }

    @Test
    fun `T-A3-09 link is LOCAL and carries the uri plus size`() = runBlocking {
        val d = driver(mediaStore = FakeSource(LocalChannel.MEDIA_STORE))
        d.init(DriverCtx())
        val link = d.link(
            Obj(name = "a.mp3", path = "/a.mp3", size = 4_096L, priv = "content://media/7"),
        )
        assertEquals(Link.Kind.LOCAL, link.kind)
        assertEquals("content://media/7", link.localPath)
        assertEquals(4_096L, link.localSize)
        assertEquals(4_096L, link.contentLength)
        assertNull(link.proxySourceUrl)
    }

    @Test
    fun `T-A3-10 path sandbox rejects traversal and drop is idempotent`() {
        runBlocking {
            val d = driver(mediaStore = FakeSource(LocalChannel.MEDIA_STORE))
            d.init(DriverCtx())
            assertFailsWith<CloudMusicError.DsNotSupport> {
                d.get("../../etc/passwd")
            }
            assertFailsWith<CloudMusicError.DsNotSupport> {
                d.list(Obj(name = "x", isDir = true, path = "../../etc"))
            }
            d.drop()
            d.drop()
            assertFailsWith<IllegalStateException> {
                d.list(Obj(name = "Music", isDir = true, path = "/"))
            }
        }
    }
}
