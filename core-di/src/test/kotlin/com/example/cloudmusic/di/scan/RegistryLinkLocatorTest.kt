package com.example.cloudmusic.di.scan

import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DirectLinkCapability
import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.LinkRule
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.CandidateKind
import com.example.cloudmusic.scanner.TrackHint
import org.junit.jupiter.api.DisplayName
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §61.223 真机一手：SFTP 扫描期经 RegistryLinkLocator → link() 触发全量下载（仅为读 tag）。
 * §61.225：分块读驱动（ByteRange）且无直链探测（DirectLink）→ locate 返回驱动相对路径，
 * tag 读取走 rangeFor 分块；直链驱动（WebDAV 型）与普通驱动保持 link() 语义。
 */
class RegistryLinkLocatorTest {

    private val locator = RegistryLinkLocator()

    private val storage = Storage("s-x", "/s-x", "sftp", Addition("{}"))

    private fun candidate(path: String) = Candidate(
        storage = storage,
        obj = Obj(name = path.substringAfterLast('/'), path = path, size = 1_000L),
        kind = CandidateKind.AUDIO,
        parentDirName = "",
        hint = TrackHint(),
    )

    private class SftpLikeDriver(
        private val linkCalls: java.util.concurrent.atomic.AtomicInteger,
    ) : Driver, ByteRangeCapability {
        override val config = DriverConfig("fake-sftp")
        override suspend fun init(ctx: DriverCtx) {}
        override fun drop() {}
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link {
            linkCalls.incrementAndGet()
            return Link(localPath = "/cache/should-not-be-used", localSize = 1_000L)
        }
        override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? = null
    }

    private class WebDavLikeDriver : Driver, ByteRangeCapability, DirectLinkCapability {
        override val config = DriverConfig("fake-webdav")
        override suspend fun init(ctx: DriverCtx) {}
        override fun drop() {}
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link =
            Link(localPath = "/cache/webdav-local", localSize = 1_000L)
        override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? = null
        override suspend fun directUrl(file: Obj): String? = null
    }

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove("s-x")
    }

    @Test
    @DisplayName("§61.225 · SFTP 型驱动（仅 ByteRange）→ 相对路径 ref，零 link() 调用")
    fun sftpLikeRelativeRef() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        DriverRegistry.put("s-x", SftpLikeDriver(calls))
        val ref = locator.locate(candidate("nas_song.mp3"))
        assertEquals("nas_song.mp3", ref, "驱动相对路径 ref（tag 走 rangeFor 分块）")
        assertEquals(0, calls.get(), "link() 不得被扫描期调用（全量下载缺陷）")
    }

    @Test
    @DisplayName("§61.225 · WebDAV 型驱动（ByteRange + DirectLink）→ 保持 link() 语义")
    fun webDavLikeKeepsLink() = runTest {
        DriverRegistry.put("s-x", WebDavLikeDriver())
        val ref = locator.locate(candidate("/dav/song.mp3"))
        assertEquals("/cache/webdav-local", ref, "直链驱动的 link 语义不变（LOCAL ref）")
    }

    @Test
    @DisplayName("§61.225 · 驱动未注册 → null（B01 降级语义不变）")
    fun unregisteredDriverNull() = runTest {
        assertNull(locator.locate(candidate("x.mp3")))
    }
}
