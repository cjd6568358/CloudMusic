package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.output.dlna.DlnaSsdpFailed
import com.example.cloudmusic.output.dlna.MulticastTransport
import com.example.cloudmusic.output.dlna.SsdpDiscovery
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * docs/03-08 §4.1：SSDP `M-SEARCH` 的 `DatagramSocket` 生产实现（纯 JVM，可注入 fake socket）。
 * `WifiManager.MulticastLock` 是独立端口（见 [WifiMulticastLockPort]），由 `SsdpScanner` 在
 * 发现开始 / 结束时成对 acquire / release。
 */
class DatagramMulticastTransport(
    private val socket: DatagramSocket = DatagramSocket(),
) : MulticastTransport, Closeable {

    override fun send(payload: ByteArray) {
        runCatching {
            val target = InetAddress.getByName(SsdpDiscovery.MULTICAST_HOST)
            socket.send(DatagramPacket(payload, payload.size, target, SsdpDiscovery.MULTICAST_PORT))
        }.onFailure {
            throw DlnaSsdpFailed("SSDP 多播发送失败：${it.javaClass.simpleName}")
        }
    }

    /** 在 timeoutMs 内持续收包；单次 socket timeout 视为收集结束，不当成失败。 */
    override fun receive(timeoutMs: Int): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(2048)
        val responses = mutableListOf<String>()
        while (System.currentTimeMillis() < deadline) {
            socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                responses += String(packet.data, 0, packet.length, Charsets.UTF_8)
            } catch (_: SocketTimeoutException) {
                break
            } catch (e: Exception) {
                throw DlnaSsdpFailed("SSDP 接收失败：${e.javaClass.simpleName}")
            }
        }
        return responses
    }

    override fun close() = socket.close()
}
