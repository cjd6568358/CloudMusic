package com.example.cloudmusic.strm

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 03-01 §12 T19、03-03 B06 去重与委托型驱动契约。 */
class StrmDriverTest {

    @BeforeEach
    fun reset() = DriverRegistry.reset()

    @Test
    @DisplayName("03-01 T19 strm 首行注释 + 第二行 URL → 取第二行")
    fun t19() = runTest {
        val obj = Obj(name = "a.strm", path = "/music/a.strm", size = 40)
        val text = "# generated\nhttps://nas/a.flac?sign=1"
        register(obj to text)
        val driver = driver("""{"delegate_storage_id":"s1"}""")
        driver.init(DriverCtx())

        val link = driver.link(obj)
        assertEquals("https://nas/a.flac?sign=1", link.url)
        assertEquals(Link.Kind.URL, link.kind)
        assertEquals(-1L, link.contentLength)
    }

    @Test
    @DisplayName("03-03 B06 同一 URL 在两份 .strm 中去重，保留首次路径")
    fun dedupe() = runTest {
        val a = Obj(name = "a.strm", path = "/music/a.strm")
        val b = Obj(name = "b.strm", path = "/music/b.strm")
        FakeTextDriver(mapOf(a.path to "https://x/a.flac", b.path to "https://x/a.flac"), listOf(a, b))
            .also { DriverRegistry.put("s1", it) }
        val driver = driver("""{"delegate_storage_id":"s1"}""")
        driver.init(DriverCtx())

        val listed = driver.list(Obj(name = "music", isDir = true, path = "/"), false)
        assertEquals(1, listed.size)
        assertEquals(a.path, listed.single().path)
    }

    @Test
    @DisplayName("B01 / B03 空内容与非 http scheme 标状态但不删条目")
    fun unsupportedMarked() = runTest {
        val empty = Obj(name = "empty.strm", path = "/music/empty.strm")
        val smb = Obj(name = "smb.strm", path = "/music/smb.strm")
        FakeTextDriver(
            mapOf(empty.path to "# only comment", smb.path to "smb://nas/a.flac"),
            listOf(empty, smb),
        ).also { DriverRegistry.put("s1", it) }
        val driver = driver("""{"delegate_storage_id":"s1"}""")
        driver.init(DriverCtx())

        val listed = driver.list(Obj(name = "music", isDir = true, path = "/"), false)
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.priv == "strm:empty" })
        assertTrue(listed.any { it.priv == "strm:unsupported:smb" })
    }

    @Test
    @DisplayName("03-02 §2 / 03-01 B02 init 失败后 drop 不抛；委托不存在时报错")
    fun lifecycle() = runTest {
        val driver = driver("""{"delegate_storage_id":"missing"}""")
        assertFailsWith<Exception> { driver.init(DriverCtx()) }
        driver.drop()
        driver.drop()
        // drop 后 initialized 复位，list 会被 ensureReady 拦下
        assertFailsWith<IllegalStateException> {
            driver.list(Obj(name = "music", isDir = true, path = "/"), false)
        }
    }

    private fun driver(addition: String) = StrmDriver(
        Storage(id = "s2", mountPath = "/strm", driverType = "strm", addition = Addition(addition)),
        addition,
        DriverRegistry,
    )

    private fun register(vararg entries: Pair<Obj, String>) {
        FakeTextDriver(
            texts = entries.associate { it.first.path to it.second },
            objects = entries.map { it.first },
        ).also { DriverRegistry.put("s1", it) }
    }

    /** 测试替身：同时充当底层 Driver 与 TextReadCapability（03-01 §5.1 可选能力）。 */
    private class FakeTextDriver(
        private val texts: Map<String, String>,
        private val objects: List<Obj>,
    ) : Driver, TextReadCapability {
        override val config = DriverConfig("fake")
        var dropped = false
            private set

        override suspend fun init(ctx: DriverCtx) = Unit
        override fun drop() { dropped = true }
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = objects
        override suspend fun get(path: String): Obj? = objects.firstOrNull { it.path == path }
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = file.path)
        override suspend fun readText(file: Obj): String = texts[file.path].orEmpty()
    }
}
