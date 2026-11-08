package com.example.cloudmusic.common

/**
 * docs/01 §6.1 / 03-02 §344 / 03-11 §4 §6 / 08 §2.3 ·「默认仅 WiFi 播放」的**纯 JVM 判定层**
 * （`12 §61.28`，第五十五批）。
 *
 * `03-02 §344` 明确「播放前由 03-06 检查」：编排层 `PlayerController` 在真正下发
 * native play 之前向 [NetworkPlaybackGate] 询问，本对象只提供可单测的策略判定，
 * **不碰 Android 框架**（`:core-common` 是纯 JVM 模块）。
 *
 * 配置唯一来源是 `AppSettings.network.wifiOnly`（`08 §6` 单一来源，默认 true）。
 * 生产实现每次判定都读 `SettingsAccess.current()`，因此设置改动**即时生效**
 * （`03-11 §4`「Flow<AppSettings> 驱动，修改后不需要重启」），无需重建播放器或
 * 依赖 effects；`03-11 §6 B14` 在飞行模式修改网络项也照常保存、不联网校验。
 */
enum class NetworkKind {
    /** WiFi（含 WiFi Direct 等 TRANSPORT_WIFI 形态）。 */
    WIFI,

    /** 移动数据。 */
    MOBILE,

    /** 以太网 / VPN 等其它已连接网络：按「仅 WiFi」字面，不放行。 */
    OTHER,

    /** 无活动网络（含飞行模式）。 */
    NONE,
}

/**
 * 播放前的网络准入端口。`networkSource=true` 表示本次播放的曲目是 URL / 回环代理源；
 * `false` 表示本地文件源（`03-11 §6 B05`：飞行模式下曲库与本地播放必须完全可用）。
 *
 * @return null = 放行；非 null = 拒绝原因（由调用方落到 `E_DS_UPSTREAM_WIFI_ONLY`）。
 */
fun interface NetworkPlaybackGate {
    fun rejectReason(networkSource: Boolean): String?
}

object NetworkPlaybackPolicy {

    /** `07 §2` 的 `E_DS_UPSTREAM_*` 子类，不新增错误码类别。 */
    const val ERROR_CODE = "WIFI_ONLY"

    /**
     * C-17（`12 §61.35`）：离线事实的**单一真相源**。
     *
     * 播放闸门（本对象的 [NetworkKind.NONE] 分支，有生产消费方 `PlayerController`）
     * 与 UI 降级契约（`DegradationPolicy.evaluate` 的 `offline`，C-16）必须共用这一判定，
     * 避免「飞行模式 / 无网络」演化成两套口径。Android 侧不要再另写一份
     * `ConnectivityManager` 离线判断，直接用 `currentKind()` 的结果调用本函数。
     */
    fun isOffline(kind: NetworkKind): Boolean = kind == NetworkKind.NONE

    /**
     * 唯一判定表。覆盖 `01 §6.1` 与 `03-11 §6 B05` 的全部边界：
     * - 本地音源 / 未 prepare（未知）→ 一律放行；
     * - 用户在设置中开启「移动数据播放」（`wifiOnly=false`）→ 任何网络都放行；
     * - `wifiOnly=true` 时只有 [NetworkKind.WIFI] 放行。
     */
    fun rejectReason(wifiOnly: Boolean, kind: NetworkKind, networkSource: Boolean): String? {
        if (!networkSource) return null
        if (!wifiOnly) return null
        if (kind == NetworkKind.WIFI) return null
        return when (kind) {
            NetworkKind.MOBILE ->
                "当前为移动网络，已开启「仅 WiFi 播放」；可在设置中开启移动数据播放"
            NetworkKind.NONE ->
                "当前无网络连接，且已开启「仅 WiFi 播放」（本地曲目不受影响）"
            NetworkKind.OTHER ->
                "当前网络不是 WiFi，且已开启「仅 WiFi 播放」；可在设置中开启移动数据播放"
            NetworkKind.WIFI -> null
        }
    }

    /** 纯 JVM 测试替身 / 未注入生产实现时的放行基线（既有单测零回归）。 */
    val ALLOW_ALL: NetworkPlaybackGate = NetworkPlaybackGate { null }
}
