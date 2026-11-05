package com.example.cloudmusic.output.dlna

import java.util.Locale

data class SoapRequest(
    val url: String,
    /** 必须带引号：`"urn:...#Action"`（部分 DMR 严格校验）。 */
    val soapAction: String,
    val body: String,
)

data class SoapResponse(val status: Int, val body: String)

interface SoapTransport {
    suspend fun post(request: SoapRequest): SoapResponse
}

/** docs/03-08 §6.1 / §6.2 SOAP 动作表与报文模板。纯字符串构造，端口可注入 fake。 */
object SoapController {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun soapAction(serviceType: String, action: String): String = "\"$serviceType#$action\""

    fun envelope(serviceType: String, action: String, params: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<s:Body><u:$action xmlns:u=\"$serviceType\">$params</u:$action></s:Body></s:Envelope>"

    fun setAvTransportUri(controlUrl: String, uri: String, metadata: String): SoapRequest {
        // metadata 里的 DIDL-Lite 必须已经 XML 转义（§6.2 / T07）
        val params = "<InstanceID>0</InstanceID><CurrentURI>${DidlLiteBuilder.escape(uri)}</CurrentURI>" +
                "<CurrentURIMetaData>${DidlLiteBuilder.escape(metadata)}</CurrentURIMetaData>"
        return SoapRequest(
            url = controlUrl,
            soapAction = soapAction(AV_TRANSPORT, "SetAVTransportURI"),
            body = envelope(AV_TRANSPORT, "SetAVTransportURI", params),
        )
    }

    fun play(controlUrl: String) = simple(controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    fun pause(controlUrl: String) = simple(controlUrl, "Pause", "<InstanceID>0</InstanceID>")
    fun stop(controlUrl: String) = simple(controlUrl, "Stop", "<InstanceID>0</InstanceID>")
    fun seek(controlUrl: String, ms: Long) = simple(
        controlUrl, "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatSeek(ms)}</Target>",
    )

    fun getPositionInfo(controlUrl: String) = simple(controlUrl, "GetPositionInfo", "<InstanceID>0</InstanceID>")
    fun getTransportInfo(controlUrl: String) = simple(controlUrl, "GetTransportInfo", "<InstanceID>0</InstanceID>")

    fun setVolume(controlUrl: String, volume: Int) = SoapRequest(
        url = controlUrl,
        soapAction = soapAction(RENDERING_CONTROL, "SetVolume"),
        body = envelope(
            RENDERING_CONTROL, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$volume</DesiredVolume>",
        ),
    )

    /** HTTP 500 + `<UPnPError><errorCode>` → 映射成 `E_OUT_SOAP_*`（§6.2）。 */
    fun errorCode(body: String): Int? =
        Regex("<(?:[a-zA-Z0-9_-]+:)?errorCode>\\s*(\\d+)\\s*</", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.toIntOrNull()

    fun formatSeek(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return String.format(
            Locale.ROOT, "%02d:%02d:%02d",
            total / 3600, (total % 3600) / 60, total % 60,
        )
    }

    private fun simple(controlUrl: String, action: String, params: String) = SoapRequest(
        url = controlUrl,
        soapAction = soapAction(AV_TRANSPORT, action),
        body = envelope(AV_TRANSPORT, action, params),
    )
}
