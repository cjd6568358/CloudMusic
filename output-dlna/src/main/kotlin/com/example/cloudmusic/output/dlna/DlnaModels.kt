package com.example.cloudmusic.output.dlna

import com.example.cloudmusic.common.CloudMusicError

/** docs/03-08 §4.3：SSDP 发现与手动指定汇聚成同一类型，仅用 [source] 区分。 */
enum class DeviceSource { SSDP, MANUAL }

data class DmrDevice(
    val usn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val baseUrl: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String,
    val eventSubUrl: String?,
    val source: DeviceSource,
    val lastSeen: Long,
)

/** §4.2 主动指定 DMR（A7，竞品没有的能力）。 */
data class ManualDmrSpec(
    val host: String,
    val port: Int? = null,
    val descriptionUrl: String? = null,
    val label: String = "",
)

/** §4.1 SSDP 响应：LOCATION → 描述 URL；USN / SERVER 用于去重。 */
data class SsdpResponse(
    val usn: String,
    val location: String,
    val server: String? = null,
)

/** §3 投屏 URI 决策结果。 */
data class CastUri(
    val uri: String,
    val viaProxy: Boolean,
    val fallback: CastFallback = CastFallback.NONE,
    val directProbePassed: Boolean = false,
)

enum class CastFallback { NONE, PROXY_ON_FAILURE }

// ---- E_OUT_* 错误（07 §2 / ErrorCatalog 已登记） ----------------------------

class DlnaSsdpFailed(detail: String) : CloudMusicError(detail, "E_OUT_SSDP_FAILED")
class DlnaManualUnreachable(detail: String) : CloudMusicError(detail, "E_OUT_MANUAL_UNREACHABLE")
class DlnaDescParse(detail: String) : CloudMusicError(detail, "E_OUT_DESC_PARSE")

/** HTTP 500 + `<UPnPError><errorCode>` → 通配码 `E_OUT_SOAP_*`。 */
class DlnaSoapError(code: Int, detail: String) : CloudMusicError(detail, "E_OUT_SOAP_$code")
