package com.example.cloudmusic.output.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * docs/03-08 §5：设备描述 XML 解析（JDK DOM，替代 Android XmlPullParser，同 `12 §11.3` 的 WebDAV 先例）。
 * 兼容任意命名空间前缀与大小写（W3 规则）；
 * `controlURL` 可为相对路径或绝对 URL，相对时与 `baseURL` 拼接并归一化尾斜杠。
 */
object DescriptionParser {

    fun parse(
        xml: String,
        descriptionUrl: String,
        source: DeviceSource = DeviceSource.SSDP,
        now: Long = System.currentTimeMillis(),
    ): DmrDevice {
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw DlnaDescParse("描述 XML 解析失败：${e.message}")
        }

        val base = textOf(doc, "URLBase")?.takeIf { it.isNotBlank() }
            ?: originOf(descriptionUrl)
        val services = serviceNodes(doc)

        // serviceNodes 的字段键已按 localName 归一为小写（见 §5「忽略大小写」），此处用小写键匹配。
        val av = services.firstOrNull { it["servicetype"].orEmpty().contains("AVTransport:1", true) }
        val rc = services.firstOrNull { it["servicetype"].orEmpty().contains("RenderingControl:1", true) }

        // §13 T09：缺 AVTransport 明确报错，不静默当成 DMR
        val avControl = av?.get("controlurl")?.let { resolveUrl(base, it) }
            ?: throw DlnaDescParse("设备描述缺少 AVTransport:1 控制地址（$descriptionUrl）")

        return DmrDevice(
            usn = textOf(doc, "UDN")?.takeIf { it.isNotBlank() } ?: descriptionUrl,
            friendlyName = textOf(doc, "friendlyName") ?: "未知设备",
            manufacturer = textOf(doc, "manufacturer"),
            baseUrl = base,
            avTransportControlUrl = avControl,
            renderingControlUrl = rc?.get("controlurl")?.let { resolveUrl(base, it) } ?: "",
            eventSubUrl = av?.get("eventsuburl")?.let { resolveUrl(base, it) },
            source = source,
            lastSeen = now,
        )
    }

    /** 相对 controlURL → 绝对 URL；绝对 URL 原样返回；尾斜杠归一化（§5 / T08）。 */
    fun resolveUrl(base: String, control: String): String {
        val value = control.trim()
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return base.trimEnd('/') + "/" + value.trimStart('/')
    }

    fun originOf(url: String): String = try {
        val uri = URI(url)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    } catch (e: Exception) {
        url.trimEnd('/')
    }

    /** 忽略命名空间前缀（如 `upnp:friendlyName`）与元素大小写差异。 */
    private fun localName(nodeName: String): String =
        nodeName.substringAfterLast(':').lowercase()

    private fun textOf(doc: Document, name: String): String? {
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (localName(el.nodeName) == name.lowercase()) {
                return el.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun serviceNodes(doc: Document): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (localName(el.nodeName) != "service") continue
            val fields = mutableMapOf<String, String>()
            val children = el.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                fields[localName(child.nodeName)] = child.textContent?.trim().orEmpty()
            }
            result.add(fields)
        }
        return result
    }
}
