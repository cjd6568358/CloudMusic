package com.example.cloudmusic.output.dlna

import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.output.localproxy.ProxySettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-08 §13 T01~T09 / T13 / T14 的纯 JVM 判定部分。 */
class DlnaContractTest {

    private fun track(title: String = "title") = Track(
        id = TrackId("track-1"), title = title, artist = "artist", album = "album",
        durationMs = 125_678L,
        sourceRef = SourceRef("storage", "/a.flac"),
        sourceType = SourceType.LOCAL,
    )

    private class FakeProxy : CastProxyPort {
        var started = false
            private set
        override fun ensureStarted() { started = true }
        override fun urlFor(trackId: String) = "http://192.168.1.2:38123/t/tok-$trackId"
    }

    private class FakeDirectLinks(private val url: String?) : DirectLinkProvider {
        var calls = 0
            private set
        override suspend fun directUrl(link: Link): String? { calls++; return url }
    }

    private class FakeHead(private val pass: Boolean = true) : HeadProbe {
        var calls = 0
            private set
        override suspend fun preProbe(url: String): Boolean { calls++; return pass }
    }

    private class Case(
        val resolver: CastUriResolver,
        val proxy: FakeProxy,
        val direct: FakeDirectLinks,
        val head: FakeHead,
    )

    private fun case(
        link: Link,
        directUrl: String? = null,
        settings: ProxySettings = ProxySettings(),
        driverPreferProxy: Boolean = false,
        probePass: Boolean = true,
    ): Case {
        val proxy = FakeProxy()
        val direct = FakeDirectLinks(directUrl)
        val head = FakeHead(probePass)
        return Case(
            CastUriResolver(
                settings = settings,
                proxy = proxy,
                links = LinkProvider { link },
                directLinks = direct,
                headProbe = head,
                driverPreferProxy = driverPreferProxy,
            ),
            proxy, direct, head,
        )
    }

    private fun assertXmlWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    // ------------------------------------------------------------------
    // T01~T06 决策表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T01 · LOCAL 必须代理，URI 为 http://{IP}:{port}/t/{token}")
    fun t01() = runTest {
        val c = case(Link(localPath = "/music/a.flac"))
        val uri = c.resolver.resolve(track())
        assertTrue(uri.viaProxy)
        assertTrue(uri.uri.startsWith("http://192.168.1.2:38123/t/tok-"))
        assertTrue(c.proxy.started)
        assertEquals(0, c.direct.calls, "LOCAL 不探测直链")
    }

    @Test
    @DisplayName("T02 · URL 态原样直推，不启动代理")
    fun t02() = runTest {
        val url = "https://example.com/a.mp3"
        val c = case(Link(url = url))
        val uri = c.resolver.resolve(track())
        assertEquals(url, uri.uri)
        assertFalse(uri.viaProxy)
        assertFalse(c.proxy.started)
        assertTrue(uri.directProbePassed)
        assertEquals(CastFallback.NONE, uri.fallback)
    }

    @Test
    @DisplayName("T03 · PROXIED + directUrl() 非 null → 直推该 URL")
    fun t03() = runTest {
        val direct = "https://nas.example.com/anon.mp3"
        val c = case(Link(proxy = true), directUrl = direct)
        val uri = c.resolver.resolve(track())
        assertEquals(direct, uri.uri)
        assertFalse(uri.viaProxy)
        assertFalse(c.proxy.started)
        assertEquals(1, c.direct.calls)
    }

    @Test
    @DisplayName("T04 · PROXIED + directUrl() 为 null → 走代理")
    fun t04() = runTest {
        val c = case(Link(proxy = true), directUrl = null)
        val uri = c.resolver.resolve(track())
        assertTrue(uri.viaProxy)
        assertTrue(c.proxy.started)
    }

    @Test
    @DisplayName("T05 · forceWebdavProxy=true → directUrl() 不被采用")
    fun t05() = runTest {
        val c = case(
            Link(proxy = true), directUrl = "https://nas/anon.mp3",
            settings = ProxySettings(forceWebdavProxy = true),
        )
        assertTrue(c.resolver.resolve(track()).viaProxy)
        assertEquals(0, c.direct.calls, "强制代理时不得探测直链")
    }

    @Test
    @DisplayName("T06 · preferDirectLink=false → 除 LOCAL 外全部走代理")
    fun t06() = runTest {
        val settings = ProxySettings(preferDirectLink = false)
        val urlCase = case(Link(url = "https://example.com/a.mp3"), settings = settings)
        assertTrue(urlCase.resolver.resolve(track()).viaProxy)
        val proxiedCase = case(Link(proxy = true), directUrl = "https://nas/a.mp3", settings = settings)
        assertTrue(proxiedCase.resolver.resolve(track()).viaProxy)
        assertEquals(0, proxiedCase.direct.calls)
    }

    @Test
    @DisplayName("§3.2 · 预探测失败不阻止直推，只标 PROXY_ON_FAILURE；驱动级 preferProxy 生效")
    fun probeFailureAndPreferProxy() = runTest {
        val failed = case(Link(url = "https://example.com/a.mp3"), probePass = false)
        val uri = failed.resolver.resolve(track())
        assertFalse(uri.viaProxy)
        assertFalse(uri.directProbePassed)
        assertEquals(CastFallback.PROXY_ON_FAILURE, uri.fallback)

        val driven = case(
            Link(proxy = true), directUrl = "https://nas/anon.mp3", driverPreferProxy = true,
        )
        assertTrue(driven.resolver.resolve(track()).viaProxy)
        assertEquals(0, driven.direct.calls)

        val caching = CachingHeadProbe(FakeHead(true))
        assertEquals(true, caching.preProbe("u"))
        assertEquals(true, caching.preProbe("u"))
        caching.invalidate("u")
        assertEquals(true, caching.preProbe("u"))
    }

    // ------------------------------------------------------------------
    // T07 / T08 / T09 协议细节
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T07 · DIDL-Lite XML 转义后嵌入 SOAP，报文仍是合法 XML")
    fun t07() = runTest {
        val t = track("A & B <live> \"quoted\"")
        val didl = DidlLiteBuilder.build(t, "http://192.168.1.2:38123/t/tok-1")
        assertTrue(didl.contains("A &amp; B &lt;live&gt;"))
        assertTrue(didl.contains(DidlLiteBuilder.CONTENT_FEATURES))
        assertEquals("00:02:05.678", DidlLiteBuilder.formatDuration(t.durationMs))

        val soap = SoapController.setAvTransportUri("http://dmr/AVTransport/control", "http://x/a.mp3", didl)
        assertXmlWellFormed(soap.body)
        assertTrue(soap.body.contains("CurrentURIMetaData"))
        assertFalse(soap.body.contains("<dc:title>"), "元数据必须作为转义文本，不能变成 SOAP 元素")
        assertTrue(soap.soapAction.startsWith("\"") && soap.soapAction.endsWith("\""))
        assertTrue(soap.soapAction.endsWith("#SetAVTransportURI\""))
    }

    @Test
    @DisplayName("T08 · 相对 controlURL 拼接（尾斜杠归一化）；绝对 URL 原样")
    fun t08() {
        assertEquals(
            "http://192.168.1.5:8200/AVTransport/control",
            DescriptionParser.resolveUrl("http://192.168.1.5:8200/", "/AVTransport/control"),
        )
        assertEquals(
            "http://192.168.1.5:8200/AVTransport/control",
            DescriptionParser.resolveUrl("http://192.168.1.5:8200", "AVTransport/control"),
        )
        assertEquals(
            "http://other:9000/ctl",
            DescriptionParser.resolveUrl("http://192.168.1.5:8200", "http://other:9000/ctl"),
        )
        assertEquals("http://192.168.1.5:8200", DescriptionParser.originOf("http://192.168.1.5:8200/description.xml"))
    }

    @Test
    @DisplayName("T09 · 描述 XML 缺 AVTransport → E_OUT_DESC_PARSE；前缀兼容与 URLBase 兜底")
    fun t09() {
        val noAv = """<root><device><friendlyName>TV</friendlyName>
            |<serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            |<controlURL>/rc</controlURL></service></serviceList></device></root>""".trimMargin()
        val err = assertFailsWith<DlnaDescParse> {
            DescriptionParser.parse(noAv, "http://192.168.1.5:8200/description.xml")
        }
        assertEquals("E_OUT_DESC_PARSE", err.code)

        val prefixed = """<s:root xmlns:s="urn:schemas-upnp-org:device-1-0"><s:device>
            |<s:friendlyName>Living Room</s:friendlyName><s:UDN>uuid:abc</s:UDN>
            |<s:serviceList><s:service><s:serviceType>urn:schemas-upnp-org:service:AVTransport:1</s:serviceType>
            |<s:controlURL>/AVTransport/control</s:controlURL>
            |<s:eventSubURL>/AVTransport/event</s:eventSubURL></s:service></s:serviceList>
            |</s:device></s:root>""".trimMargin()
        val device = DescriptionParser.parse(prefixed, "http://192.168.1.5:8200/description.xml", now = 123L)
        assertEquals("Living Room", device.friendlyName)
        assertEquals("http://192.168.1.5:8200/AVTransport/control", device.avTransportControlUrl)
        assertEquals("http://192.168.1.5:8200/AVTransport/event", device.eventSubUrl)
        assertEquals("http://192.168.1.5:8200", device.baseUrl, "URLBase 缺失时回落到描述 URL 的 origin")
        assertEquals(123L, device.lastSeen)
    }

    // ------------------------------------------------------------------
    // T13 / T14 设备来源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T13 · SSDP 模拟多响应按 USN 去重；M-SEARCH 报文与 MulticastLock 生命周期")
    fun t13() {
        val r1 = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.5:8200/description.xml\r\nUSN: uuid:a\r\nSERVER: x"
        val r2 = "HTTP/1.1 200 OK\r\nlocation: http://192.168.1.6:8200/rootDesc.xml\r\nusn: uuid:a\r\n"
        val r3 = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.7/description.xml\r\nUSN: uuid:b\r\n"
        val list = SsdpDiscovery.parseResponses(listOf(r1, r2, r3))
        assertEquals(2, list.size)
        assertEquals(setOf("uuid:a", "uuid:b"), list.map { it.usn }.toSet())
        assertEquals("http://192.168.1.5:8200/description.xml", list[0].location)

        val msearch = SsdpDiscovery.buildMSearch(SsdpDiscovery.MEDIA_RENDERER)
        assertTrue(msearch.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(msearch.contains("MX: 3"))
        assertTrue(SsdpDiscovery.SEARCH_TARGETS.contains(SsdpDiscovery.AV_TRANSPORT))

        val lock = object : MulticastLockPort {
            var acquired = 0
            var released = 0
            override fun acquire() { acquired++ }
            override fun release() { released++ }
        }
        val transport = object : MulticastTransport {
            val sent = mutableListOf<ByteArray>()
            override fun send(payload: ByteArray) { sent.add(payload) }
            override fun receive(timeoutMs: Int) = listOf(r1, r2, r3)
        }
        val found = SsdpScanner(transport, lock).discover()
        assertEquals(2, found.size, "扫描器按 USN 去重")
        assertEquals(2, transport.sent.size, "两个 ST 各发一次")
        assertEquals(1, lock.acquired)
        assertEquals(1, lock.released)
    }

    @Test
    @DisplayName("T14 · 手动输入 IP:Port 依次探测候选路径，得到 DmrDevice(MANUAL)")
    fun t14() = runTest {
        val spec = ManualDmrSpec(host = "192.168.1.5", port = 8200)
        val urls = ManualDmrProbe.candidateUrls(spec)
        assertEquals("http://192.168.1.5:8200/description.xml", urls.first())
        assertTrue(urls.contains("http://192.168.1.5:49152/description.xml"))

        val body = """<root><device><friendlyName>Manual DMR</friendlyName>
            |<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            |<controlURL>/AVTransport/control</controlURL></service></serviceList></device></root>""".trimMargin()
        val tried = mutableListOf<String>()
        val fetcher = DescriptionFetcher { url ->
            tried.add(url)
            if (url.endsWith("/devicedesc.xml")) body else null
        }
        val device = ManualDmrProbe.probe(spec, fetcher, now = 999L)
        assertEquals(DeviceSource.MANUAL, device.source)
        assertEquals("Manual DMR", device.friendlyName)
        assertEquals("http://192.168.1.5:8200/AVTransport/control", device.avTransportControlUrl)
        assertEquals(2, tried.size, "第二个候选命中即停止")

        val explicit = ManualDmrProbe.candidateUrls(ManualDmrSpec("h", descriptionUrl = "http://h/d.xml"))
        assertEquals(listOf("http://h/d.xml"), explicit)

        assertFailsWith<DlnaManualUnreachable> {
            ManualDmrProbe.probe(spec, DescriptionFetcher { null })
        }
    }

    @Test
    @DisplayName("§6.2 · SOAP 动作表、errorCode 映射与时长格式")
    fun soapActions() {
        assertEquals("E_OUT_SOAP_501", DlnaSoapError(501, "Action Failed").code)
        assertEquals(714, SoapController.errorCode("<UPnPError><errorCode>714</errorCode></UPnPError>"))
        assertNull(SoapController.errorCode("<ok/>"))
        assertEquals("00:02:05", SoapController.formatSeek(125_678L))
        assertEquals("01:00:00", SoapController.formatSeek(3_600_000L))
        assertTrue(SoapController.play("http://d/ctl").body.contains("<Speed>1</Speed>"))
        assertTrue(SoapController.seek("http://d/ctl", 65_000).body.contains("<Target>00:01:05</Target>"))
        assertTrue(SoapController.setVolume("http://d/rc", 30).soapAction.contains("RenderingControl:1"))
    }
}
