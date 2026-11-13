package com.example.cloudmusic.output.dlna

import com.example.cloudmusic.model.Track

/**
 * §61.110（复核报告 P2-9「DLNA 第一个生产调用点」）：03-08 §10.1 切 DLNA 时序的
 * **最小生产闭环编排器**——发现端口 → `CastUriResolver.resolve` → DIDL-Lite →
 * `SetAVTransportURI` → `Play`，直链被拒按 §9 回落代理重推 1 次。
 *
 * **端口隔离（同 [CastUriResolver] 纪律）**：本类纯 JVM 不触网；SSDP 发现 /
 * SOAP 传输 / 代理由端口注入，fake 全覆盖单测（`DlnaCastCoordinatorTest`）。
 *
 * **§61.110 诚实挂账（不伪造完成）**：03-08 §10.1 的本机侧动作
 * （`IMediaControl.setEnabled(false)` / 引擎静音保进度）、§10.2 断连回落本机
 * （轮询 3 次超时 / seek 恢复）、§10.3 切歌保持投屏、PositionPoller —— 均属
 * **DLNA 会话引擎**，触碰真机播放态，无设备不施工；本类只覆盖「选定设备 →
 * 推流起播」这一段真实可验证的闭环。
 */
class DlnaCastCoordinator(
    private val discoverPort: DmrDiscoverPort,
    private val resolver: CastUriResolver,
    private val soap: SoapTransport,
    private val proxy: CastProxyPort,
    /** 进度提示（`OutputRouter.routeHint` 的生产写入方在 `:core-di` 组合层）。 */
    private val onHint: (String) -> Unit = {},
) {
    /** §4.1 SSDP 发现 + 描述解析；生产实现见 `:core-di` 的 Android 组合层。 */
    suspend fun discover(timeoutMs: Int = 5_000): List<DmrDevice> =
        discoverPort.discover(timeoutMs)

    /**
     * 把 [track] 推到 [device] 起播。返回 [CastStartResult] 如实登记每一步；
     * SOAP 带 `UPnPError` 时抛 [DlnaSoapError]（07 §2 错误码表）。
     */
    suspend fun startCast(
        track: Track,
        device: DmrDevice,
        coverUrl: String? = proxy.coverUrlFor(track.id.value),
    ): CastStartResult {
        onHint("正在连接 ${device.friendlyName} …")
        val castUri = resolver.resolve(track)
        val didl = DidlLiteBuilder.build(track, castUri.uri, coverUrl = coverUrl)
        var setResp = soap.post(
            SoapController.setAvTransportUri(device.avTransportControlUrl, castUri.uri, didl),
        )
        var fellBack = false
        // 03-08 §9：直链被 DMR 拒（非 2xx）且决策表标了 PROXY_ON_FAILURE →
        // proxy.ensureStarted() + 同一设备重推代理 URL，最多 1 次/曲目。
        if (!isOk(setResp) && !castUri.viaProxy && castUri.fallback == CastFallback.PROXY_ON_FAILURE) {
            proxy.ensureStarted()
            val proxied = proxy.urlFor(track.id.value)
            val proxiedDidl = DidlLiteBuilder.build(track, proxied, coverUrl = coverUrl)
            setResp = soap.post(
                SoapController.setAvTransportUri(device.avTransportControlUrl, proxied, proxiedDidl),
            )
            fellBack = isOk(setResp)
            if (fellBack) onHint("直链被拒，已切换手机转发（${device.friendlyName}）")
        }
        if (!isOk(setResp)) {
            val code = SoapController.errorCode(setResp.body)
            val detail = if (code != null) {
                "SetAVTransportURI errorCode=$code"
            } else {
                "SetAVTransportURI HTTP ${setResp.status}"
            }
            onHint("投屏失败：$detail")
            if (code != null) throw DlnaSoapError(code, detail)
            return CastStartResult(started = false, device = device, viaProxy = castUri.viaProxy, fellBackToProxy = fellBack)
        }
        val playResp = soap.post(SoapController.play(device.avTransportControlUrl))
        if (!isOk(playResp)) {
            onHint("已设曲但 Play 未确认（${device.friendlyName}）")
            return CastStartResult(started = false, device = device, viaProxy = castUri.viaProxy, fellBackToProxy = fellBack)
        }
        onHint("DLNA · ${device.friendlyName}")
        return CastStartResult(started = true, device = device, viaProxy = castUri.viaProxy, fellBackToProxy = fellBack)
    }

    /** §10.2 的 Stop 步骤：尽力而为，失败不阻塞（回落会话引擎属挂账项）。 */
    suspend fun stopCast(device: DmrDevice) {
        runCatching { soap.post(SoapController.stop(device.avTransportControlUrl)) }
    }

    private fun isOk(r: SoapResponse): Boolean = r.status in 200..299
}

/** startCast 的结果口径（如实登记，不把失败写成成功）。 */
data class CastStartResult(
    val started: Boolean,
    val device: DmrDevice,
    val viaProxy: Boolean,
    val fellBackToProxy: Boolean,
)

/**
 * DMR 发现端口：生产 = `SsdpScanner`（Android MulticastSocket + MulticastLock）
 * → 逐 LOCATION 拉描述 XML → [DescriptionParser]；JVM fake 供单测。
 */
interface DmrDiscoverPort {
    suspend fun discover(timeoutMs: Int): List<DmrDevice>
}
