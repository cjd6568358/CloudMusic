package com.example.cloudmusic.webdav

import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** docs/03-02 §4.4 · PROPFIND 响应项。 */
data class PropfindItem(
    val href: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val contentType: String?,
)

/**
 * W1 跳过第一个 response（集合自身，否则出现幽灵重复目录）
 * W2 href 尾斜杠归一化
 * W3 剥离命名空间前缀：D:href / d:href / href 等价
 * W4 URL-decode 后再拼路径（中文 / 空格文件名）
 *
 * 用 JDK DOM 而非 Android XmlPullParser，保持本模块纯 JVM、可在本机跑单测。
 */
object PropfindParser {

    fun parse(xml: String, skipFirst: Boolean = true): List<PropfindItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 禁止 DTD / 外部实体，避免解析不可信 NAS 响应时的 XXE
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        doc.documentElement.normalize()

        val responses = mutableListOf<Node>()
        collect(doc, "response", responses)
        val items = responses.drop(if (skipFirst) 1 else 0)
        return items.mapNotNull { response ->

            val href = firstText(response, "href") ?: return@mapNotNull null
            val decoded = runCatching {
                // URLDecoder 会把 '+' 变空格，路径里的字面 '+' 需先转义
                URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            }.getOrElse { return@mapNotNull null }
            val normalized = decoded.trimEnd('/')
            val isCollection = descendants(response, "resourcetype").any {
                descendants(it, "collection").isNotEmpty()
            }
            val size = firstText(response, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            val modified = firstText(response, "getlastmodified")?.trim()?.let(::rfc1123ToEpoch) ?: 0L
            PropfindItem(
                href = normalized,
                name = normalized.substringAfterLast('/'),
                isDir = isCollection,
                size = size,
                modified = modified,
                contentType = firstText(response, "getcontenttype")?.trim(),
            )
        }.distinctBy { it.href } // W2：尾斜杠归一化后按 href 去重，避免 Nextcloud / nginx 混用产生重复项
    }

    private fun rfc1123ToEpoch(value: String): Long = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
    }.getOrDefault(0L)

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfterLast(':')

    private fun collect(node: Node, tag: String, out: MutableList<Node>) {
        if (node.nodeType == Node.ELEMENT_NODE && localName(node).equals(tag, ignoreCase = true)) {
            out += node
        }
        val children = node.childNodes
        for (i in 0 until children.length) collect(children.item(i), tag, out)
    }

    private fun descendants(node: Node, tag: String): List<Node> =
        mutableListOf<Node>().also { collect(node, tag, it) }

    private fun firstText(node: Node, tag: String): String? =
        descendants(node, tag).firstOrNull()?.textContent
}
