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
 * **§61.193**：§10.3 切歌保持由 `:core-di` 的 `dlnaRecastLoop` + UI 记住设备落地。
 * **§61.199**：§10.2 断连回落的进度读取（[positionMs]）与健康判定（[DlnaSessionMonitor]）
 * 在本模块纯 JVM 落地，执行接线在 `:core-di` 的 `PlaybackRuntime`；真机 E2E 仍 pending-device。
 * 挂账：多设备列表、手动指定 DMR 的 UI 入口、真实 DMR 进度驱动的 UI 数值显示。
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

    /**
     * §10.1 PositionPoller / §10.2 断连回落的进度源：读 DMR 的 `GetPositionInfo.RelTime`。
     * 非 2xx / `UPnPError` / 无 RelTime / 非 `HH:MM:SS(.sss)` 一律 null（交会话判定器计失败）。
     * 不触网逻辑之外的状态，纯读；异常由 [runCatching] 收敛为 null，不打断轮询。
     */
    suspend fun positionMs(device: DmrDevice): Long? {
        val response = runCatching {
            soap.post(SoapController.getPositionInfo(device.avTransportControlUrl))
        }.getOrNull() ?: return null
        if (!isOk(response) || SoapController.errorCode(response.body) != null) return null
        val relTime = REL_TIME.find(response.body)?.groupValues?.get(1) ?: return null
        return parseClock(relTime)
    }

    /**
     * §61.202 / §10.1：播放页进度条在投屏态的 seek 目标 —— DMR 而非本机被静音的引擎。
     * 复用 [SoapController.seek] 的 REL_TIME 报文；非 2xx / 传输异常返回 false（不伪造成功）。
     */
    suspend fun seekTo(device: DmrDevice, ms: Long): Boolean {
        if (ms < 0L) return false
        val response = runCatching {
            soap.post(SoapController.seek(device.avTransportControlUrl, ms))
        }.getOrNull() ?: return false
        return isOk(response)
    }

    /**
     * §61.229（V0.2 M7）：DMR 音量——RenderingControl:1 SetVolume（Master channel）。
     * 非 2xx / 传输异常返回 false（不伪造成功）；volume 越界钳制 0..100。
     */
    suspend fun setVolume(device: DmrDevice, volume: Int): Boolean {
        val v = volume.coerceIn(0, 100)
        val response = runCatching {
            soap.post(SoapController.setVolume(device.renderingControlUrl, v))
        }.getOrNull() ?: return false
        return isOk(response)
    }

    /** §61.229：GetVolume（Master）——解析失败 / 异常返回 null（UI 保持上次显示值）。 */
    suspend fun getVolume(device: DmrDevice): Int? {
        val response = runCatching {
            soap.post(SoapController.getVolume(device.renderingControlUrl))
        }.getOrNull() ?: return null
        if (!isOk(response)) return null
        return Regex(
            """<(?:[a-zA-Z0-9_-]+:)?CurrentVolume>\s*(\d+)\s*</""",
            RegexOption.IGNORE_CASE,
        ).find(response.body)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** §10.2 的 Stop 步骤：尽力而为，失败不阻塞。 */
    suspend fun stopCast(device: DmrDevice) {
        runCatching { soap.post(SoapController.stop(device.avTransportControlUrl)) }
    }

    private fun isOk(r: SoapResponse): Boolean = r.status in 200..299

    private companion object {
        val REL_TIME = Regex(
            "<(?:[a-zA-Z0-9_-]+:)?RelTime>\\s*([^<]+?)\\s*</",
            RegexOption.IGNORE_CASE,
        )

        /** DMR 常见 `00:03:12` / `0:03:12.345`；非法分秒返回 null（不猜进度）。 */
        fun parseClock(raw: String): Long? {
            val parts = raw.trim().split(':')
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val s = parts[2].toDoubleOrNull() ?: return null
            if (h < 0 || m !in 0..59 || s < 0.0 || s >= 60.0) return null
            return ((h * 3600L + m * 60L) * 1000L + (s * 1000.0).toLong())
        }
    }
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
