package com.ccmsc.cloudmusic.sftp

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V0.2 / §61.216：SFTP 驱动的纯 JVM 契约（fake 端口，零网络、零凭据外泄）。 */
class SftpDriverTest {

    @TempDir
    lateinit var cacheDir: File

    private fun storage(extra: String = "") = Storage(
        id = "sftp-test", mountPath = "/sftp", driverType = "sftp",
        addition = Addition(
            """{"host":"192.168.1.9","port":22,"username":"u","password":"p","root_folder_path":"/music"$extra}""",
        ),
    )

    private class FakePort(
        private val bytes: Map<String, ByteArray> = emptyMap(),
        private val entries: List<SftpEntry> = emptyList(),
        private val connectOk: Boolean = true,
    ) : SftpClientPort {
        var connects = 0
        var closed = 0
        val reads = ArrayList<Pair<String, Long>>()
        override suspend fun connect(): Boolean { connects++; return connectOk }
        override suspend fun list(path: String): List<SftpEntry> = entries.filter { it.path.startsWith(path) }
        override suspend fun stat(path: String): SftpEntry? = entries.firstOrNull { it.path == path }
        override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
            reads += path to offset
            val data = bytes[path] ?: return null
            if (offset >= data.size) return ByteArray(0)
            return data.copyOfRange(offset.toInt(), minOf(data.size, offset.toInt() + length))
        }
        override fun close() { closed++ }
    }

    private suspend fun driver(port: SftpClientPort, st: Storage = storage()) = SftpDriver(
        storage = st, additionJson = st.addition.raw,
        clientFactory = { port }, cacheDir = cacheDir,
    ).also { it.init(DriverCtx(tag = "[test]", caller = "test")) }

    private fun captureConfig(st: Storage): SftpConfig {
        var captured: SftpConfig? = null
        val d = SftpDriver(st, st.addition.raw, { cfg -> captured = cfg; FakePort() }, cacheDir)
        // init 不抛即成功（factory 只有在 doInit 里被调）
        kotlinx.coroutines.runBlocking { d.init(DriverCtx()) }
        return captured!!
    }

    private fun entry(path: String, name: String, size: Long, isDir: Boolean = false) =
        SftpEntry(name = name, path = path, size = size, isDir = isDir, modified = 1_700_000_000L)

    @Test
    @DisplayName("§61.216 S1：init 连接失败 / host 缺失如实失败，drop 幂等")
    fun initValidation() = runTest {
        val bad = FakePort(connectOk = false)
        val d = SftpDriver(storage(), storage().addition.raw, { bad }, cacheDir)
        assertFailsWith<CloudMusicError.DsUpstream> { d.init(DriverCtx()) }
        d.drop(); d.drop()
        assertEquals(1, bad.closed)   // init 失败时已就地 close 一次，client 未赋值故 drop 不再重复

        val noHost = Storage("s", "/s", "sftp", Addition("""{"username":"u"}"""))
        val d2 = SftpDriver(noHost, noHost.addition.raw, { FakePort() }, cacheDir)
        assertFailsWith<CloudMusicError.DsInvalidConfig> { d2.init(DriverCtx()) }
    }

    @Test
    @DisplayName("§61.216 S2：list 转相对路径、过滤隐藏项；get 走 stat")
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
    @DisplayName("§61.216 S3：link 下载到 LOCAL 缓存，二次命中不再读取；路径不含凭据")
    fun linkDownloadsAndCaches() = runTest {
        val data = ByteArray(150_000) { (it % 251).toByte() }
        val port = FakePort(bytes = mapOf("/music/song.flac" to data))
        val d = driver(port)
        val file = Obj(name = "song.flac", path = "song.flac", size = data.size.toLong(), modified = 1L)
        val first = d.link(file)
        assertEquals("LOCAL", first.kind.name)
        assertTrue(File(first.localPath!!).readBytes().contentEquals(data))
        assertTrue(first.localPath!!.contains("sftp"))
        assertTrue(!first.localPath!!.contains("password") && !first.localPath!!.contains("192.168"))
        val readsAfterFirst = port.reads.size
        d.link(file)
        assertEquals(readsAfterFirst, port.reads.size, "缓存命中零远端读取")
    }

    @Test
    @DisplayName("§61.216 S4：readBytes 区间 + readText 的 GB18030 回落")
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
    @DisplayName("§61.216 S5：路径越界抛 DsNotSupport（沙箱二次校验）")
    fun pathSandbox() = runTest {
        val d = driver(FakePort())
        assertFailsWith<CloudMusicError> { d.get("../../etc/passwd") }   // PathNormalizer 抛 DsUpstream，统一断言 CloudMusicError
    }

    @Test
    @DisplayName("§61.219 K1：PEM 私钥 \\n 还原为换行，passphrase 随配置传递")
    fun privateKeyConfig() {
        val raw = """{"host":"h","username":"u","private_key":"-----BEGIN KEY-----\\nABC\\n-----END KEY-----","passphrase":"secret"}"""
        val st = Storage("s3", "/s", "sftp", Addition(raw))
        val cfg = captureConfig(st)
        assertEquals("-----BEGIN KEY-----\nABC\n-----END KEY-----", cfg.privateKey)
        assertEquals("secret", cfg.passphrase)
        assertEquals("", cfg.password)
    }

    @Test
    @DisplayName("§61.219 K2：密码与私钥都为空时拒绝初始化（不静默连空凭据）")
    fun missingCredentials() = runTest {
        val st = Storage("s2", "/s", "sftp", Addition("""{"host":"h","username":"u"}"""))
        val d = SftpDriver(st, st.addition.raw, { FakePort() }, cacheDir)
        assertFailsWith<CloudMusicError.DsInvalidConfig> { d.init(DriverCtx()) }
    }
}
