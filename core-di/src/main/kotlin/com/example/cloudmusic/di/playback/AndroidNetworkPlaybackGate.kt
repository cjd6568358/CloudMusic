package com.example.cloudmusic.di.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.cloudmusic.common.NetworkKind
import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.common.NetworkPlaybackPolicy
import com.example.cloudmusic.di.settings.SettingsAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `12 §61.28` / `03-02 §344`：「默认仅 WiFi 播放」的 Android 生产实现。
 *
 * 判定本身是 [NetworkPlaybackPolicy] 的纯 JVM 策略；本类只负责把平台网络状态和
 * 持久设置喂给它。每次调用都读 `SettingsAccess.current()`，所以设置页改动
 * `network.wifiOnly` 后**下一次播放即生效**（`03-11 §4`），不需要重建播放器、
 * 也不依赖 effects（`03-11 §6 B14` 飞行模式改设置不联网校验）。
 *
 * `ACCESS_NETWORK_STATE` 本就在 `03-11 §1.1` 的 11 项权限内，**零新增权限**；
 * 不申请位置权限、不读取 SSID，只用 `NetworkCapabilities` 的 transport 位。
 */
@Singleton
class AndroidNetworkPlaybackGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsAccess,
) : NetworkPlaybackGate {

    override fun rejectReason(networkSource: Boolean): String? =
        NetworkPlaybackPolicy.rejectReason(
            wifiOnly = settings.current().network.wifiOnly,
            kind = currentKind(),
            networkSource = networkSource,
        )

    private fun currentKind(): NetworkKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkKind.NONE
        val network = cm.activeNetwork ?: return NetworkKind.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            else -> NetworkKind.OTHER
        }
    }
}
