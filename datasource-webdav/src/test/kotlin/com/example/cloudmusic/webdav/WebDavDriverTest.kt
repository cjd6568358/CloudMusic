package com.example.cloudmusic.webdav

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 03-01 §12 T18 / T20，03-02 §9 T04~T06 / T10~T15 / T18。 */
class WebDavDriverTest {

    @Test
    @DisplayName("03-01 T18 模拟 401 → Basic → 200，首请求不带 Authorization")
    fun t18() = runTest {
        val fake = FakeHttp(
            mutableListOf(
                WebDavResponse(401, mapOf("WWW-Authenticate" to "Basic realm=\"nas\"")),
                WebDavResponse(207, body = SIMPLE_XML),
            ),
        )
        val driver = driver(fake)
        driver.init(DriverCtx())

        val items = driver.list(Obj(name = "music", isDir = true, path = "/music"), false)
        assertEquals(1, items.size)
        assertEquals(2, fake.requests.size)
        assertNull(fake.requests[0].headers["Authorization"])
        assertEquals("Basic dTpw", fake.requests[1].headers["Authorization"])
        // 已进入认证态 → directUrl 拒绝直推，由 03-08 回落代理
        assertNull(driver.directUrl(Obj(name = "a.flac", path = "/music/a.flac")))
    }

    @Test
    @DisplayName("03-01 T20 协程序中途取消 → 底层调用被取消，无悬挂连接")
    fun t20() = runTest {
        val http = HangingHttp()
        val driver = driver(http)
        driver.init(DriverCtx())
        val job = launch {
            driver.list(Obj(name = "music", isDir = true, path = "/music"), false)
        }
        yield()
        job.cancelAndJoin()
        assertTrue(http.cancelled)
    }

    @Test
    @DisplayName("03-02 T06 401 Digest → 第二次带 qop=auth 与 response")
    fun digestNegotiation() = runTest {
        val fake = FakeHttp(
            mutableListOf(
                WebDavResponse(
                    401,
                    mapOf("WWW-Authenticate" to "Digest realm=\"nas\", nonce=\"abc123\", qop=\"auth\""),
                ),
                WebDavResponse(207, body = SIMPLE_XML),
            ),
        )
        val driver = driver(fake)
        driver.init(DriverCtx())
        driver.list(Obj(name = "music", isDir = true, path = "/music"), false)
        val auth = fake.requests[1].headers["Authorization"].orEmpty()
        assertTrue(auth.startsWith("Digest"))
        assertTrue("qop=auth" in auth)
        assertTrue("response=" in auth)
    }

    @Test
    @DisplayName("03-02 T12 link() 默认 → PROXIED + 鉴权头 + requireReference")
    fun defaultLink() = runTest {
        val driver = driver(FakeHttp())
        driver.init(DriverCtx())
        val link = driver.link(Obj(name = "a.flac", path = "/music/a.flac", size = 100))
        assertEquals(Link.Kind.PROXIED, link.kind)
        assertTrue(link.requireReference)
        assertTrue(link.proxy)
        assertEquals(100L, link.contentLength)
    }

    @Test
    @DisplayName("03-02 T11 link(redirect=true) 收到 302 → 返回 Location 的 URL Link")
    fun redirectLink() = runTest {
        val fake = FakeHttp(
            mutableListOf(
                WebDavResponse(302, mapOf("Location" to "https://cdn.example.com/a.flac?sign=1")),
                WebDavResponse(200),
            ),
        )
        val driver = driver(fake)
        driver.init(DriverCtx())
        val link = driver.link(
            Obj(name = "a.flac", path = "/music/a.flac", size = 10),
            LinkArgs(redirect = true),
        )
        assertEquals(Link.Kind.URL, link.kind)
        assertEquals("https://cdn.example.com/a.flac?sign=1", link.url)
    }

    @Test
    @DisplayName("03-02 T13 / T14 directUrl 匿名 HEAD 200 直推、401 回落、强制代理恒 null")
    fun directUrl() = runTest {
        val ok = FakeHttp(mutableListOf(WebDavResponse(200)))
        val d1 = driver(ok)
        d1.init(DriverCtx())
        val url = d1.directUrl(Obj(name = "a.flac", path = "/music/a.flac", modified = 1))
        assertTrue(url!!.startsWith("https://nas.example.com/dav/music/"))
        assertEquals("HEAD", ok.requests.single().method)
        assertNull(ok.requests.single().headers["Authorization"])

        val denied = FakeHttp(mutableListOf(WebDavResponse(401)))
        val d2 = driver(denied)
        d2.init(DriverCtx())
        assertNull(d2.directUrl(Obj(name = "b.flac", path = "/music/b.flac")))

        val forced = FakeHttp()
        val d3 = driver(forced, forceWebdavProxy = true)
        d3.init(DriverCtx())
        assertNull(d3.directUrl(Obj(name = "c.flac", path = "/music/c.flac")))
        assertEquals(0, forced.requests.size, "强制代理时不应发起匿名探测")
    }

    @Test
    @DisplayName("03-02 T10 get 404 返回 null；T15 路径越界被沙箱拒绝")
    fun notFoundAndSandbox() = runTest {
        val d = driver(FakeHttp(mutableListOf(WebDavResponse(404))))
        d.init(DriverCtx())
        assertNull(d.get("/music/missing.flac"))
        assertFailsWith<CloudMusicError.DsNotSupport> {
            d.get("/music/../../etc/passwd")
        }
    }

    @Test
    @DisplayName("03-02 T18 drop() 幂等，重复调用不抛")
    fun dropIdempotent() = runTest {
        val d = driver(FakeHttp())
        d.init(DriverCtx())
        d.drop()
        d.drop()
        assertFailsWith<IllegalStateException> {
            d.list(Obj(name = "music", isDir = true, path = "/music"), false)
        }
    }

    @Test
    @DisplayName("03-02 §2 键名兼容 address/url、root_folder_path/root_path")
    fun keyCompat() = runTest {
        val fake = FakeHttp(mutableListOf(WebDavResponse(207, body = SIMPLE_XML)))
        val addition = """{"url":"https://legacy.example.com/dav","root_path":"/music"}"""
        val d = WebDavDriver(storage(addition), addition, fake)
        d.init(DriverCtx())
        assertTrue(fake.requests.isEmpty())
        d.list(Obj(name = "music", isDir = true, path = "/music"), false)
        assertTrue(fake.requests.single().url.startsWith("https://legacy.example.com/dav/music"))
    }

    private fun storage(addition: String) =
        Storage("w1", "/nas", "webdav", Addition(addition))

    private fun driver(http: HttpStack, forceWebdavProxy: Boolean = false) =
        WebDavDriver(storage(BASE), BASE, http, forceWebdavProxy)

    private class FakeHttp(
        private val scripted: MutableList<WebDavResponse> = mutableListOf(),
    ) : HttpStack {
        val requests = mutableListOf<WebDavRequest>()
        override suspend fun execute(request: WebDavRequest): WebDavResponse {
            requests += request
            return if (scripted.isNotEmpty()) scripted.removeAt(0) else WebDavResponse(200)
        }
    }

    private class HangingHttp : HttpStack {
        @Volatile
        var cancelled = false

        override suspend fun execute(request: WebDavRequest): WebDavResponse =
            suspendCancellableCoroutine<WebDavResponse> { cont ->
                cont.invokeOnCancellation { cancelled = true }
            }
    }

    companion object {
        const val BASE = """{"address":"https://nas.example.com/dav","root_folder_path":"/music","username":"u","password":"p"}"""

        val SIMPLE_XML = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/music/</D:href></D:response>
              <D:response><D:href>/music/a.flac</D:href>
                <D:propstat><D:prop><D:getcontentlength>1024</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
    }
}
