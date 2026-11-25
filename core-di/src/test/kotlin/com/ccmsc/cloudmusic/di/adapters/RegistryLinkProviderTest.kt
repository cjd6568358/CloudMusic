package com.ccmsc.cloudmusic.di.adapters

import com.ccmsc.cloudmusic.datasource.Driver
import com.ccmsc.cloudmusic.datasource.DriverConfig
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.datasource.DriverRegistry
import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.LinkArgs
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * §61.252（P0 修复）：RegistryLinkProvider 的 URL 条目直链口径回归——
 * 03-03 §2/§3.6 设计口径（strm 曲目 = Obj 曲目 + Link(url=...)）。
 * 真机一手：driver 在位时 driver.link(.strm 条目) 拿到「文本文件」的 PROXIED Link，
 * 引擎播的是 strm 文本 → 首页最近添加/队列 STRM 全部无法播放。
 */
class RegistryLinkProviderTest {

    private val provider = RegistryLinkProvider()

    private val storage = Storage("webdav-strm-probe", "/s", "webdav", Addition("{}"))

    private fun strmTrack(url: String?) = Track(
        id = TrackId("id-1"), title = "官方回答", artist = "dollyy99", album = "未知",
        durationMs = 0L, sourceRef = SourceRef(storage.id, "/dollyy99 - 官方回答.strm", url),
        sourceType = SourceType.STRM,
    )

    /** 伪 WebDAV 驱动：link 返回 PROXIED（回源=条目文件本身——修复前的错误行为）。 */
    private class WebDavLikeDriver : Driver {
        override val config = DriverConfig("fake-webdav")
        override suspend fun init(ctx: DriverCtx) {}
        override fun drop() {}
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? =
            Obj(name = path.substringAfterLast('/'), path = path, size = 100L)
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(
            url = "", proxy = true, contentLength = file.size, requireReference = true,
            proxySourceUrl = "http://nas/dav${file.path}",
        )
    }

    @AfterTest
    fun cleanup() {
        DriverRegistry.remove(storage.id)
    }

    @Test
    @DisplayName("§61.252 R1 · STRM 轨带缓存直链 → 直链优先（driver 在位也不走条目文件）")
    fun urlFirstEvenWithDriver() = runTest {
        DriverRegistry.put(storage.id, WebDavLikeDriver())
        val link = provider.link(strmTrack("http://nas/d/audio.mp3?sign=x"))
        assertEquals(Link.Kind.URL, link.kind, "URL 条目直推（03-03 §3.6）")
        assertEquals("http://nas/d/audio.mp3?sign=x", link.url)
    }

    @Test
    @DisplayName("§61.252 R2 · 无缓存直链 + driver 在位 → driver.link（音频 PROXIED 语义零回归）")
    fun driverFallbackWhenNoUrl() = runTest {
        DriverRegistry.put(storage.id, WebDavLikeDriver())
        val link = provider.link(strmTrack(null))
        assertEquals(Link.Kind.PROXIED, link.kind)
        assertEquals("http://nas/dav/dollyy99 - 官方回答.strm", link.proxySourceUrl)
    }

    @Test
    @DisplayName("§61.252 R3 · 无直链 + 无驱动 → DsUnreachable（孤儿轨如实报错）")
    fun orphanThrowsUnreachable() = runTest {
        val ex = assertFailsWith<com.ccmsc.cloudmusic.common.CloudMusicError.DsUnreachable> {
            provider.link(strmTrack(null))
        }
        assertEquals("E_DS_UNREACHABLE", ex.code)
    }

    @Test
    @DisplayName("§61.252 R4 · 非法 url（非 http）+ 无驱动 → 仍 DsUnreachable")
    fun nonHttpUrlIgnored() = runTest {
        val ex = assertFailsWith<com.ccmsc.cloudmusic.common.CloudMusicError.DsUnreachable> {
            provider.link(strmTrack("ftp://nas/file.mp3"))
        }
        assertEquals("E_DS_UNREACHABLE", ex.code)
    }
}
