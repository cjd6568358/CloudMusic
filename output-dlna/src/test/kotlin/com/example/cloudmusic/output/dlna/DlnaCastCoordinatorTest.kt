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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §61.110：DlnaCastCoordinator 的纯 JVM 判定（fake 端口全覆盖，不触网）。 */
class DlnaCastCoordinatorTest {

    private fun track(source: SourceType = SourceType.LOCAL) = Track(
        id = TrackId("track-1"), title = "老人与海", artist = "artist", album = "album",
        durationMs = 125_678L,
        sourceRef = SourceRef("storage", "/a.flac"),
        sourceType = source,
    )

    private fun device(name: String = "客厅音响") = DmrDevice(
        usn = "uuid:test", friendlyName = name, manufacturer = null,
        baseUrl = "http://192.168.1.3:49152",
        avTransportControlUrl = "http://192.168.1.3:49152/ctrl",
        renderingControlUrl = "", eventSubUrl = null,
        source = DeviceSource.SSDP, lastSeen = 0L,
    )

    private class FakeDiscover(private val devices: List<DmrDevice>) : DmrDiscoverPort {
        var lastTimeout = -1
        override suspend fun discover(timeoutMs: Int): List<DmrDevice> {
            lastTimeout = timeoutMs
            return devices
        }
    }

    private class FakeSoap : SoapTransport {
        /** 按调用序出队：(status, body)；缺省成功。 */
        val script = ArrayDeque<Pair<Int, String>>()
        val actions = ArrayList<String>()
        var thrownOnPost: Exception? = null

        override suspend fun post(request: SoapRequest): SoapResponse {
            thrownOnPost?.let { throw it }
            actions.add(request.soapAction)
            val (status, body) = if (script.isEmpty()) 200 to "" else script.removeFirst()
            return SoapResponse(status, body)
        }

        fun failWithUpnpError(code: Int) {
            script.add(
                500 to "<s:Envelope><s:Body><UPnPError><errorCode>$code</errorCode>" +
                    "</UPnPError></s:Body></s:Envelope>",
            )
        }
    }

    private class RecordingProxy(private val delegate: FakeProxy) : CastProxyPort {
        var ensured = 0
            private set
        override fun ensureStarted() {
            ensured++
            delegate.ensureStarted()
        }

        override fun urlFor(trackId: String) = delegate.urlFor(trackId)
        override fun coverUrlFor(trackId: String): String? = null
    }

    private class FakeProxy : CastProxyPort {
        var started = false
            private set
        override fun ensureStarted() { started = true }
        override fun urlFor(trackId: String) = "http://192.168.1.2:38123/t/tok-$trackId"
    }

    private class FakeDirectLinks(private val url: String?) : DirectLinkProvider {
        override suspend fun directUrl(link: Link): String? = url
    }

    private class FakeHead(private val pass: Boolean) : HeadProbe {
        override suspend fun preProbe(url: String): Boolean = pass
    }

    private fun coordinator(
        soap: FakeSoap,
        proxy: RecordingProxy,
        link: Link = Link(localPath = "/a.flac"),
        discover: FakeDiscover = FakeDiscover(listOf(device())),
    ): DlnaCastCoordinator {
        val resolver = CastUriResolver(
            settings = ProxySettings(preferDirectLink = true),
            proxy = proxy,
            links = { link },
            directLinks = FakeDirectLinks("http://media.example/a.flac"),
            headProbe = FakeHead(pass = false),   // 探测失败 → 直推 + fallback=PROXY_ON_FAILURE
        )
        return DlnaCastCoordinator(
            discoverPort = discover,
            resolver = resolver,
            soap = soap,
            proxy = proxy,
            onHint = {},
        )
    }

    private fun urlLink(): Link = Link(url = "http://media.example/a.flac")

    @Test
    @DisplayName("§61.110 C1：LOCAL 经代理 SetAVTransportURI+Play 成功，提示设备名")
    fun castViaProxySuccess() = runTest {
        val soap = FakeSoap()
        val fake = FakeProxy()
        val proxy = RecordingProxy(fake)
        val hints = ArrayList<String>()
        val coordinator = DlnaCastCoordinator(
            discoverPort = FakeDiscover(listOf(device())),
            resolver = CastUriResolver(
                settings = ProxySettings(),
                proxy = proxy,
                links = { Link(localPath = "/a.flac") },
                directLinks = FakeDirectLinks(null),
                headProbe = FakeHead(pass = false),
            ),
            soap = soap,
            proxy = proxy,
            onHint = { hints.add(it) },
        )
        val result = coordinator.startCast(track(), device())
        assertTrue(result.started, "两步 SOAP 都 200 → started")
        assertFalse(result.fellBackToProxy)
        assertTrue(result.viaProxy, "LOCAL 必走代理（03-08 §3.1 第 1 行）")
        assertTrue(proxy.ensured >= 1, "resolve 内部已 ensureStarted")
        assertEquals(2, soap.actions.size, "SetAVTransportURI + Play")
        assertTrue(soap.actions[0].contains("SetAVTransportURI"))
        assertTrue(soap.actions[1].contains("AVTransport:1#Play"))
        assertTrue(hints.last().contains("客厅音响"), "最终提示含设备名")
    }

    @Test
    @DisplayName("§61.110 C2：直链被 DMR 拒（UPnPError 714）且 fallback=PROXY_ON_FAILURE → 代理重推 1 次成功")
    fun fallbackToProxyOnce() = runTest {
        val soap = FakeSoap()
        soap.failWithUpnpError(714)   // 第一次 SetAVTransportURI 被拒
        val proxy = RecordingProxy(FakeProxy())
        val coordinator = coordinator(soap, proxy, link = urlLink())
        val result = coordinator.startCast(track(), device())
        assertTrue(result.started)
        assertTrue(result.fellBackToProxy, "§9：直链失败回代理重推")
        assertEquals(3, soap.actions.size, "直链 Set → 代理 Set → Play")
        assertTrue(proxy.ensured >= 1)
    }

    @Test
    @DisplayName("§61.110 C3：两步全拒（UPnPError 501）→ 抛 DlnaSoapError，不伪造成 started")
    fun soapErrorPropagates() = runTest {
        val soap = FakeSoap()
        soap.failWithUpnpError(501)
        soap.failWithUpnpError(501)
        val proxy = RecordingProxy(FakeProxy())
        val coordinator = coordinator(soap, proxy, link = urlLink())
        assertFailsWith<DlnaSoapError> {
            coordinator.startCast(track(), device())
        }
    }

    @Test
    @DisplayName("§61.110 C4：discover 透传端口结果与超时参数")
    fun discoverPassThrough() = runTest {
        val discover = FakeDiscover(listOf(device("书房音箱")))
        val coordinator = coordinator(FakeSoap(), RecordingProxy(FakeProxy()), discover = discover)
        val devices = coordinator.discover(timeoutMs = 1234)
        assertEquals(1, devices.size)
        assertEquals("书房音箱", devices[0].friendlyName)
        assertEquals(1234, discover.lastTimeout)
    }

    @Test
    @DisplayName("§61.110 C5：stopCast 尽力而为——传输抛错不外溢")
    fun stopBestEffort() = runTest {
        val soap = FakeSoap().apply { thrownOnPost = IllegalStateException("network down") }
        val coordinator = coordinator(soap, RecordingProxy(FakeProxy()))
        coordinator.stopCast(device())   // 不抛 = 判据
    }
}
