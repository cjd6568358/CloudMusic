package com.example.cloudmusic.output.dlna

/**
 * docs/03-08 §4.1 SSDP 广播发现（纯 JVM 可测部分）。
 * `DatagramSocket` 生产实现与 `WifiManager.MulticastLock` 做成端口，单测注入 fake，不触网、不依赖 Android。
 */
object SsdpDiscovery {
    const val MULTICAST_HOST = "239.255.255.250"
    const val MULTICAST_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    val SEARCH_TARGETS = listOf(MEDIA_RENDERER, AV_TRANSPORT)

    fun buildMSearch(st: String, mx: Int = 3): String = listOf(
        "M-SEARCH * HTTP/1.1",
        "HOST: $MULTICAST_HOST:$MULTICAST_PORT",
        "MAN: \"ssdp:discover\"",
        "MX: $mx",
        "ST: $st",
        "",
        "",
    ).joinToString("\r\n")

    /** 多响应按 USN 去重（§13 T13）；丢弃缺 USN / LOCATION 的响应。 */
    fun parseResponses(raw: Iterable<String>): List<SsdpResponse> {
        val byUsn = LinkedHashMap<String, SsdpResponse>()
        raw.forEach { block ->
            val headers = HashMap<String, String>()
            block.lines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
            val usn = headers["USN"] ?: return@forEach
            val location = headers["LOCATION"] ?: return@forEach
            byUsn.putIfAbsent(usn, SsdpResponse(usn, location, headers["SERVER"]))
        }
        return byUsn.values.toList()
    }
}

interface MulticastTransport {
    fun send(payload: ByteArray)
    fun receive(timeoutMs: Int): List<String>
}

/** Android 侧用 `WifiManager.MulticastLock` 实现；JVM 可用空实现。 */
interface MulticastLockPort {
    fun acquire()
    fun release()
}

class SsdpScanner(
    private val transport: MulticastTransport,
    private val lock: MulticastLockPort,
) {
    /** 发两组 ST，收集 MX=3 的响应，5s 超时后按 USN 去重返回；无论成败都释放 MulticastLock。 */
    fun discover(timeoutMs: Int = 5_000): List<SsdpResponse> {
        lock.acquire()
        try {
            SsdpDiscovery.SEARCH_TARGETS.forEach { st ->
                transport.send(SsdpDiscovery.buildMSearch(st).toByteArray())
            }
            return SsdpDiscovery.parseResponses(transport.receive(timeoutMs))
        } finally {
            lock.release()
        }
    }
}
