package com.example.cloudmusic.datasource

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.common.ErrorCatalog
import com.example.cloudmusic.common.mapDriverError
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/03-01 §12 契约测试 T12~T16（:datasource-api 侧）。
 * T17/T18（WebDAV PROPFIND / 401 协商）与 T19（strm 首行注释）依赖 03-02 / 03-03 的驱动实现；
 * T20（协程取消）依赖 OkHttp 调用链，均待对应模块落地后补。
 */
class DriverContractTest {

    @BeforeEach
    fun resetRegistry() {
        DriverRegistry.reset()
    }

    // ------------------------------------------------------------------
    // T12 路径规范化与沙箱
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T12 normalizePath 遇到越界 .. 由沙箱拒绝")
    fun t12() {
        assertFailsWith<CloudMusicError.DsNotSupport> {
            PathRule.normalizePath("/nas/../../etc/passwd")
        }
        assertEquals("b/c.flac", PathRule.normalizePath("a/../b/c.flac"))
        assertEquals("/nas/a/b.flac", PathRule.normalizePath("/nas//a/./b.flac"))
        assertFailsWith<CloudMusicError.DsNotSupport> {
            PathRule.sandbox("/nas", "/etc/passwd")
        }
        assertEquals("Album/01.flac", PathRule.relativeTo("/nas", "/nas/Album/01.flac"))
    }

    // ------------------------------------------------------------------
    // T13 DriverConfig 能力位
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T13 onlyProxy=true → mustProxy() == true")
    fun t13() {
        assertTrue(DriverConfig(name = "webdav", onlyProxy = true).mustProxy())
        assertTrue(DriverConfig(name = "ftp", noLinkUrl = true).mustProxy())
        assertTrue(!DriverConfig(name = "webdav").mustProxy())
        assertTrue(!DriverConfig(name = "webdav").preferProxy) // 10 §2 R6：默认 false
    }

    // ------------------------------------------------------------------
    // T14 生命周期幂等
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T14 drop() 连续 3 次 / init 失败后 drop 均不抛异常")
    fun t14() = runTest {
        var initCalls = 0
        var dropCalls = 0
        val driver = StubDriver(
            onInit = { initCalls++; if (initCalls == 1) error("auth failed") },
            onDrop = { dropCalls++ },
        )
        val lifecycle = DriverLifecycle(driver)

        assertFailsWith<IllegalStateException> { lifecycle.init(DriverCtx(caller = "test")) }
        assertEquals(DriverLifecycle.State.FAILED, lifecycle.state)
        lifecycle.drop()
        lifecycle.drop()
        lifecycle.drop()
        assertEquals(DriverLifecycle.State.CLOSED, lifecycle.state)

        // FAILED 后可重试 init（先 drop），重复 init 会先 drop 再初始化（B01）
        lifecycle.init(DriverCtx(caller = "test"))
        assertEquals(DriverLifecycle.State.READY, lifecycle.state)
        lifecycle.init(DriverCtx(caller = "test"))
        assertEquals(DriverLifecycle.State.READY, lifecycle.state)
        assertTrue(initCalls >= 3)
        assertTrue(dropCalls >= 2)
    }

    // ------------------------------------------------------------------
    // T15 上游消息透传
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T15 mapDriverError 优先使用 errDetail，不显示笼统「网络错误」")
    fun t15() {
        val ctx = DriverCtx(tag = "[webdav:nas]", caller = "test")
        ctx.setErrDetail("NAS 返回 403 Forbidden")
        val error = CloudMusicError.DsUpstream("403", "网络错误")
        assertEquals("NAS 返回 403 Forbidden", mapDriverError(error, ctx.errDetail.value))
        // 07 §8：笼统「网络错误」禁止原样展示，降级为带错误码的提示
        assertEquals("操作失败（E_DS_UPSTREAM_403）", mapDriverError(error, null))
        // 具体上游消息仍原样透传（无 errDetail 时的正常路径）
        assertEquals("401 Unauthorized", mapDriverError(CloudMusicError.DsAuthFailed("401 Unauthorized")))
        assertEquals("操作失败", mapDriverError(RuntimeException()))
        assertTrue(ErrorCatalog.isRegistered(error.code))
        assertTrue(ErrorCatalog.isRegistered("E_OUT_SOAP_401"))
        assertTrue(!ErrorCatalog.isRegistered("E_MADE_UP"))
        assertEquals(
            true,
            ErrorCatalog.isFatal("E_ENGINE_ABI_MISSING"),
        )
        assertEquals(false, ErrorCatalog.isFatal("E_SCAN_TAG_PARSE"))
    }

    // ------------------------------------------------------------------
    // T16 注册表
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T16 注册 local/webdav/strm/playlist 后 types() 恰含 4 个；重复 register 抛异常")
    fun t16() {
        listOf("local", "webdav", "strm", "playlist").forEach { type ->
            DriverRegistry.register(type) { _, _ -> StubDriver(config = DriverConfig(type)) }
        }
        assertEquals(setOf("local", "webdav", "strm", "playlist"), DriverRegistry.types())
        assertFailsWith<IllegalStateException> {
            DriverRegistry.register("local") { _, _ -> StubDriver() }
        }
        assertFailsWith<CloudMusicError.DsUnknownDriver> {
            DriverRegistry.create("sftp", storage("s1"), Addition())
        }

        val storage = storage("s1")
        val driver = DriverRegistry.create("webdav", storage, Addition())
        DriverRegistry.put(storage.id, driver)
        assertNotNull(DriverRegistry.get(storage.id))
        assertEquals("webdav", driver.config.name)
        DriverRegistry.remove(storage.id)
        assertNull(DriverRegistry.get(storage.id))
    }

    // ------------------------------------------------------------------
    // 补充：LinkRule 安全日志不得泄漏 URL / header 值
    // ------------------------------------------------------------------
    @Test
    @DisplayName("补充：safeDescription 不含带签名的 URL 与 header 值")
    fun safeDescription() {
        val link = Link(
            url = "https://nas.example.com/a.flac?sign=SECRET",
            headers = listOf(com.example.cloudmusic.model.Header("Authorization", "Basic SECRET")),
        )
        val description = LinkRule.safeDescription(link)
        assertTrue(!description.contains("SECRET"))
        assertTrue(!description.contains("https://"))
        assertEquals(LinkRule.Scenario.URL_DIRECT, LinkRule.classify(link))
        assertEquals(LinkRule.Scenario.VIRTUAL_TEXT, LinkRule.classify(Link(proxy = true, text = "x")))
    }

    private fun storage(id: String) = Storage(
        id = id, mountPath = "/nas", driverType = "webdav", addition = Addition(),
    )

    /** 契约测试用的最小 Driver 实现（不触网、不递归）。 */
    private class StubDriver(
        override val config: DriverConfig = DriverConfig("stub"),
        private val onInit: suspend () -> Unit = {},
        private val onDrop: () -> Unit = {},
    ) : Driver {
        override suspend fun init(ctx: DriverCtx) {
            onInit()
        }

        override fun drop() {
            onDrop()
        }

        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()

        override suspend fun get(path: String): Obj? = null

        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(proxy = true)
    }
}
