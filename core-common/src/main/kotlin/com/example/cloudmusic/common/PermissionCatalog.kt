package com.example.cloudmusic.common

/**
 * 权限清单唯一真相源（docs/03-11 §1 / §1.1 / §1.2，PRD §9）。
 *
 * V0.1 最终 **11 项**，上限 12（PRD §10 验收 #9）。C3 静态检查
 * (`tools/check-permissions.py`) 持有同一份清单的 Python 表达；
 * `tools/check_source_contracts.py` 会交叉校验两处，防止清单漂移。
 *
 * 本对象只描述「声明了什么 / 什么系统版本生效 / 是否需要运行时申请」，
 * 不含任何 Android 框架类型；Manifest 生成与 `Activity.requestPermissions`
 * 属 Android 侧 :app，见 03-11 §2。
 */
object PermissionCatalog {

    const val INTERNET = "android.permission.INTERNET"
    const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    const val ACCESS_WIFI_STATE = "android.permission.ACCESS_WIFI_STATE"
    const val CHANGE_WIFI_MULTICAST_STATE = "android.permission.CHANGE_WIFI_MULTICAST_STATE"
    const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
    const val FOREGROUND_SERVICE_MEDIA_PLAYBACK = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val WAKE_LOCK = "android.permission.WAKE_LOCK"

    /** 03-11 §1.1 表格的一行。`minSdk` / `maxSdk` 表达「适用条件」而非必须条件声明。 */
    data class DeclaredPermission(
        val name: String,
        val purpose: String,
        val minSdk: Int = 1,
        val maxSdk: Int? = null,
        val runtime: Boolean = false,
        val neverForLocation: Boolean = false,
    ) {
        /** 该权限在指定 API 级别是否实际生效（B09 / B10）。 */
        fun appliesTo(sdkInt: Int): Boolean = sdkInt >= minSdk && (maxSdk == null || sdkInt <= maxSdk)
    }

    val DECLARED: List<DeclaredPermission> = listOf(
        DeclaredPermission(INTERNET, "WebDAV / strm / SSDP / SOAP"),
        DeclaredPermission(ACCESS_NETWORK_STATE, "判断是否在线、WiFi 检查"),
        DeclaredPermission(ACCESS_WIFI_STATE, "DLNA 局域网、组播"),
        DeclaredPermission(CHANGE_WIFI_MULTICAST_STATE, "SSDP MulticastLock"),
        DeclaredPermission(FOREGROUND_SERVICE, "后台播放服务"),
        DeclaredPermission(FOREGROUND_SERVICE_MEDIA_PLAYBACK, "前台服务类型", minSdk = 34),
        DeclaredPermission(POST_NOTIFICATIONS, "媒体通知", minSdk = 33, runtime = true),
        DeclaredPermission(READ_MEDIA_AUDIO, "MediaStore 通道读本地音乐", minSdk = 33, runtime = true),
        DeclaredPermission(READ_EXTERNAL_STORAGE, "同上，老设备兜底", maxSdk = 32, runtime = true),
        DeclaredPermission(
            BLUETOOTH_CONNECT, "读取 A2DP 连接状态/设备名",
            minSdk = 31, runtime = true, neverForLocation = true,
        ),
        DeclaredPermission(WAKE_LOCK, "后台播放持锁"),
    )

    /** 允许清单（11 项）。与 C3 的 `ALLOWED` 逐项一致。 */
    val ALLOWED: Set<String> = DECLARED.mapTo(LinkedHashSet()) { it.name }

    /**
     * 03-11 §1.2「明确不申请」的最小静态禁止集，与 C3 的 `FORBIDDEN` 逐项一致。
     * 其余不申请项（SMS / PACKAGE_USAGE_STATS / QUERY_ALL_PACKAGES / OAID 等）
     * 由「只允许 [ALLOWED] 白名单」这条规则一并拦截，无需重复枚举。
     */
    val FORBIDDEN: Set<String> = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.READ_PHONE_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )

    /** PRD §10 验收 #9 / 03-12 §3 C3。 */
    const val MAX_DECLARED_COUNT = 12

    /** 03-11 §1.1：DataStore 明文凭据禁止随系统备份上云（10 §2 R4 已拍板，不可回退）。 */
    const val ALLOW_BACKUP = false

    /** B08：第三方库 manifest merge 后权限总数仍不得超过上限。 */
    fun withinLimit(declaredCount: Int): Boolean = declaredCount <= MAX_DECLARED_COUNT

    /** 按 API 级别过滤出实际生效的声明（B09 / B10）。 */
    fun effectiveAt(sdkInt: Int): List<DeclaredPermission> = DECLARED.filter { it.appliesTo(sdkInt) }

    /** 该 API 级别需要在运行时按动作申请的授权项（不含普通权限）。 */
    fun runtimePermissionsAt(sdkInt: Int): Set<String> =
        effectiveAt(sdkInt).filter { it.runtime }.mapTo(LinkedHashSet()) { it.name }
}
