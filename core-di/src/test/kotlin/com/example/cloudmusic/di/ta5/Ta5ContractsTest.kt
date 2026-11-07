package com.example.cloudmusic.di.ta5

import com.example.cloudmusic.di.adapters.ConservativeDirectLinkProvider
import com.example.cloudmusic.di.proxy.LanAddressProvider
import com.example.cloudmusic.di.proxy.NanoHttpStreamProxy
import com.example.cloudmusic.di.scan.ByteRangePolicy
import com.example.cloudmusic.di.scan.EffectAction
import com.example.cloudmusic.di.scan.EffectsPolicy
import com.example.cloudmusic.di.scan.RangeHttpResult
import com.example.cloudmusic.di.scan.RangeRequester
import com.example.cloudmusic.di.scan.UrlByteRangeSource
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.output.localproxy.ProxyMethod
import com.example.cloudmusic.output.localproxy.ProxyRequest
import com.example.cloudmusic.output.localproxy.ProxyStatus
import com.example.cloudmusic.output.localproxy.TokenRegistry
import com.example.cloudmusic.scanner.InMemoryTrackSink
import com.example.cloudmusic.scanner.ScanPhase
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.Effect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** T-A5（`12 §31`）· C-12 / C-13 / C-14 的纯 JVM 契约用例（不触网、不碰 Android 框架）。 */
class Ta5ContractsTest {

    // ---- C-12：ByteRange 策略与 URL 态生产实现 ------------------------------

    @Test
    @DisplayName("T-A5-04 ByteRangePolicy：Range 头为闭区间")
    fun rangeHeader() {
        assertEquals("bytes=100-199", ByteRangePolicy.header(100, 100))
    }

    @Test
    @DisplayName("T-A5-05 ByteRangePolicy：206 直接返回，200 本地切片")
    fun rangeSlice() {
        val bytes = ByteArray(100) { it.toByte() }
        assertContentEquals(bytes, ByteRangePolicy.slice(bytes, 0, 10, partial = true))
        assertContentEquals(
            byteArrayOf(10, 11, 12, 13),
            ByteRangePolicy.slice(bytes, 10, 4, partial = false),
        )
        assertNull(ByteRangePolicy.slice(bytes, 200, 10, partial = false))
        assertNull(ByteRangePolicy.slice(ByteArray(0), 0, 10, partial = true))
    }

    @Test
    @DisplayName("T-A5-06 UrlByteRangeSource：206 返回区间并带上 Range 头")
    fun urlRange206() = runTest {
        val fake = FakeRangeRequester(RangeHttpResult(206, byteArrayOf(7, 8, 9)))
        val source = UrlByteRangeSource("https://example.com/a.mp3", emptyList(), fake)
        assertContentEquals(
            byteArrayOf(7, 8, 9),
            source.readBytes(Obj(name = "a.mp3"), 100, 3),
        )
        assertEquals("bytes=100-102", fake.lastHeaders["Range"])
    }

    @Test
    @DisplayName("T-A5-07 UrlByteRangeSource：200 忽略 Range 时本地切片")
    fun urlRange200() = runTest {
        val bytes = ByteArray(50) { it.toByte() }
        val fake = FakeRangeRequester(RangeHttpResult(200, bytes))
        val source = UrlByteRangeSource("https://example.com/a.mp3", emptyList(), fake)
        assertContentEquals(
            byteArrayOf(5, 6, 7),
            source.readBytes(Obj(name = "a.mp3"), 5, 3),
        )
    }

    @Test
    @DisplayName("T-A5-08 UrlByteRangeSource：失败 / 非法参数返回 null")
    fun urlRangeFailure() = runTest {
        val fake = FakeRangeRequester(RangeHttpResult(404, ByteArray(10)))
        val source = UrlByteRangeSource("https://example.com/a.mp3", emptyList(), fake)
        val obj = Obj(name = "a.mp3")
        assertNull(source.readBytes(obj, 0, 10))
        assertNull(source.readBytes(obj, -1, 10))
        assertNull(source.readBytes(obj, 0, 0))
    }

    // ---- C-14：effects 策略与扫描请求模型 -----------------------------------

    @Test
    @DisplayName("T-A5-09 EffectsPolicy：全量重扫 = FULL_RESCAN + CLEAR_COVER_CACHE")
    fun effectsFullRescan() {
        assertEquals(
            setOf(EffectAction.FULL_RESCAN, EffectAction.CLEAR_COVER_CACHE),
            EffectsPolicy.actionsFor(setOf(Effect.REBUILD_DRIVER_AND_FULL_RESCAN)),
        )
    }

    @Test
    @DisplayName("T-A5-10 EffectsPolicy：直链缓存失效 / 其余无副作用")
    fun effectsCacheAndNoop() {
        assertEquals(
            setOf(EffectAction.INVALIDATE_DIRECT_CACHE),
            EffectsPolicy.actionsFor(setOf(Effect.INVALIDATE_DIRECT_URL_CACHE)),
        )
        assertEquals(
            setOf(EffectAction.NOOP),
            EffectsPolicy.actionsFor(setOf(Effect.RESOLVE_NEXT_TRACK, Effect.NEXT_SCAN_ONLY)),
        )
        assertEquals(setOf(EffectAction.NOOP), EffectsPolicy.actionsFor(setOf(Effect.SAVE_ONLY)))
    }

    @Test
    @DisplayName("T-A5-11 ScanRequest：定向增量默认关闭，phase 是进度标签")
    fun scanRequestDefaults() = runTest {
        val request = ScanRequest()
        assertEquals(false, request.onlyUnavailable)
        assertEquals(ScanPhase.FULL, request.phase)
        assertEquals(emptySet<String>(), InMemoryTrackSink().unavailablePaths("s1"))
    }

    // ---- C-08 / C-13：端口与代理本体 ----------------------------------------

    @Test
    @DisplayName("T-A5-12 C-08：带 Track 的默认重载转发单参数，既有实现零回归")
    fun directLinkDefaultOverload() = runTest {
        val provider = ConservativeDirectLinkProvider()
        val url = Link(url = "https://example.com/a.mp3")
        assertEquals(url.url, provider.directUrl(url))
        assertEquals(provider.directUrl(url), provider.directUrl(url, null))
        assertNull(provider.directUrl(Link(url = "", proxy = true), null))
    }

    @Test
    @DisplayName("T-A5-13 C-13：nanohttpd 未启动时不服务、未发布时显式报错、封面 URL 为空")
    fun nanoProxyLifecycle() = runTest {
        val proxy = NanoHttpStreamProxy(
            registry = TokenRegistry(),
            dataSourceFactory = { StubDataSource() },
            coverProvider = { null },
            lanAddress = LanAddressProvider { "192.168.1.10" },
        )
        assertEquals(false, proxy.isRunning())
        assertEquals(
            ProxyStatus.NOT_FOUND,
            proxy.serve(ProxyRequest(ProxyMethod.GET, "/t/abc")).status,
        )
        assertNull(proxy.coverUrlFor("missing"))
        assertFailsWith<IllegalStateException> { proxy.urlFor("missing") }
    }

    private class FakeRangeRequester(private val result: RangeHttpResult) : RangeRequester {
        @Volatile
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun get(url: String, headers: Map<String, String>): RangeHttpResult {
            lastHeaders = headers
            return result
        }
    }

    private class StubDataSource : IDataSource {
        override fun open(offset: Long): Long = 0
        override fun readAt(offset: Long, buf: ByteArray, len: Int): Int = 0
        override fun close() = Unit
        override fun mimeHint(): String = "audio/mpeg"
    }
}
