package com.ccmsc.cloudmusic.ftp

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.Storage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.228：FTP 驱动单测（fake 端口；对齐 §61.216 SftpDriverTest S1~S5 用例集）。 */
class FtpDriverTest {

    @TempDir
    lateinit var cacheDir: File

    private fun storage(extra: String = "") = Storage(
        id = "ftp-1", mountPath = "/ftp-1", driverType = "ftp",
        addition = Addition("""{"host":"192.0.2.9","port":2121,"username":"u","password":"p","root_folder_path":"/music"$extra}"""),
    )

    private class FakePort(
        private val connectOk: Boolean = true,
        private val entries: List<FtpEntry> = emptyList(),
        private val bytes: Map<String, ByteArray> = emptyMap(),
    ) : FtpClientPort {
        val reads = mutableListOf<Pair<String, Long>>()
        var closed = 0
        override suspend fun connect(): Boolean = connectOk
        override suspend fun list(path: String): List<FtpEntry> = entries.filter { it.path.startsWith(path) }
        override suspend fun stat(path: String): FtpEntry? = entries.firstOrNull { it.path == path }
        override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
            val data = bytes[path] ?: return null
            if (offset >= data.size) return ByteArray(0)
            reads.add(path to offset)
            val end = minOf(offset + length.toLong(), data.size.toLong()).toInt()
            return data.copyOfRange(offset.toInt(), end)
        }
        override fun close() { closed++ }
    }

    private suspend fun driver(port: FtpClientPort, st: Storage = storage()) = FtpDriver(
        st, st.addition.raw, { port }, cacheDir,
    ).also { it.init(DriverCtx()) }

    private suspend fun captureConfig(st: Storage): FtpConfig {
        var captured: FtpConfig? = null
        FtpDriver(st, st.addition.raw, { captured = it; object : FtpClientPort {
            override suspend fun connect() = true
            override suspend fun list(path: String) = emptyList<FtpEntry>()
            override suspend fun stat(path: String) = null
            override suspend fun readBytes(path: String, offset: Long, length: Int) = ByteArray(0)
            override fun close() {}
        } }, cacheDir).init(DriverCtx())
        return captured!!
    }

    private fun entry(path: String, name: String, size: Long, isDir: Boolean = false) =
        FtpEntry(name = name, path = path, size = size, isDir = isDir, modified = 1L)

    @Test
    @DisplayName("§61.228 F1：init 连接失败 / host 缺失如实失败，drop 幂等")
    fun initValidation() = runTest {
        val bad = FakePort(connectOk = false)
        val d = FtpDriver(storage(), storage().addition.raw, { bad }, cacheDir)
        assertFailsWith<CloudMusicError.DsUpstream> { d.init(DriverCtx()) }
        d.drop(); d.drop()
        assertEquals(1, bad.closed)

        val noHost = Storage("s", "/s", "ftp", Addition("""{"username":"u"}"""))
        val d2 = FtpDriver(noHost, noHost.addition.raw, { FakePort() }, cacheDir)
        assertFailsWith<CloudMusicError.DsInvalidConfig> { d2.init(DriverCtx()) }
    }

    @Test
    @DisplayName("§61.228 F2：list 转相对路径、过滤隐藏项；get 走 stat")
    fun listAndGet() = runTest {
        val port = FakePort(entries = listOf(
            entry("/music/a.flac", "a.flac", 100),
            entry("/music/.hidden", ".hidden", 1),
            entry("/music/sub", "sub", 0, isDir = true),
        ))
        val d = driver(port)
        val list = d.list(Obj(name = "", isDir = true, path = ""))
        assertEquals(listOf("a.flac", "sub"), list.map { it.name })
        assertEquals("a.flac", list.first { it.name == "a.flac" }.path)
        assertNotNull(d.get("sub"))
        assertNull(d.get("missing"))
        d.drop()
        assertEquals(1, port.closed)
    }

    @Test
    @DisplayName("§61.228 F3：link 下载到 LOCAL 缓存，二次命中不再读取；路径不含凭据")
    fun linkDownloadsAndCaches() = runTest {
        val data = ByteArray(150_000) { (it % 251).toByte() }
        val port = FakePort(bytes = mapOf("/music/song.flac" to data))
        val d = driver(port)
        val file = Obj(name = "song.flac", path = "song.flac", size = data.size.toLong(), modified = 1L)
        val first = d.link(file)
        assertEquals("LOCAL", first.kind.name)
        assertTrue(File(first.localPath!!).readBytes().contentEquals(data))
        assertTrue(first.localPath!!.contains("ftp"))
        assertTrue(!first.localPath!!.contains("password") && !first.localPath!!.contains("192.168"))
        val readsAfterFirst = port.reads.size
        d.link(file)
        assertEquals(readsAfterFirst, port.reads.size, "缓存命中零远端读取")
    }

    @Test
    @DisplayName("§61.228 F4：readBytes 区间 + readText 的 GB18030 回落")
    fun readCapabilities() = runTest {
        val text = "歌词第一行".toByteArray(Charset.forName("GB18030"))
        val port = FakePort(bytes = mapOf(
            "/music/a.flac" to ByteArray(20) { it.toByte() },
            "/music/lyric.lrc" to text,
        ))
        val d = driver(port)
        assertEquals(4, d.readBytes(Obj(name = "a.flac", path = "a.flac"), 2, 4)!!.size)
        assertEquals("歌词第一行", d.readText(Obj(name = "lyric.lrc", path = "lyric.lrc")))
    }

    @Test
    @DisplayName("§61.228 F5：空用户名按 anonymous + 端口/root 透传")
    fun pathSandboxAndAnonymous() = runTest {
        val raw = """{"host":"h","port":21,"username":"","password":"","root_folder_path":"/m"}"""
        val st = Storage("s2", "/s", "ftp", Addition(raw))
        val cfg = captureConfig(st)
        assertEquals("anonymous", cfg.username, "username 空 → anonymous（FTP 惯例）")
        assertEquals("/m", cfg.root)
        assertEquals(21, cfg.port)
        assertTrue(!cfg.ssl, "未配置 ssl → 明文 FTP")
    }

    @Test
    @DisplayName("§61.235 F6：ssl=true 透传 FtpConfig（FTPS 显式 TLS）")
    fun sslPassthrough() = runTest {
        val raw = """{"host":"h","port":990,"username":"u","password":"p","root_folder_path":"/m","ssl":true}"""
        val cfg = captureConfig(Storage("s3", "/s", "ftp", Addition(raw)))
        assertTrue(cfg.ssl, "ssl 配置透传")
        assertEquals(990, cfg.port, "FTPS 隐式端口示例")
    }
}
