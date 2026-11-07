package com.example.cloudmusic.di.proxy

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C-13（`12 §31` / `03-08 §8.4`）：代理 URL 必须是 **DMR 能访问的局域网 IP**，
 * 不能用 `127.0.0.1`。`ACCESS_WIFI_STATE` 已在 11 项权限清单中，无需新增权限。
 *
 * 优先取 WiFi 的 IPv4；不可用（以太网 / 无 WiFi / 模拟器）时枚举网卡，
 * 排除回环、link-local 与虚拟接口，最终才回落回环地址（本机回环取流仍可用）。
 */
@Singleton
class AndroidLanAddressProvider @Inject constructor(
    @ApplicationContext context: Context,
) : LanAddressProvider {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION") // WifiInfo.ipAddress 在 API 31+ 弃用，但 V0.1 仍是最稳的局域网 IPv4 来源
    override fun address(): String {
        val wifi = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
        val ip = runCatching { wifi?.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
        if (ip != 0) return intToIp(ip)
        return fromInterfaces() ?: "127.0.0.1"
    }

    private fun fromInterfaces(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun intToIp(ip: Int): String = buildString {
        append(ip and 0xff); append('.')
        append((ip shr 8) and 0xff); append('.')
        append((ip shr 16) and 0xff); append('.')
        append((ip shr 24) and 0xff)
    }
}
