package com.example.cloudmusic.common

/**
 * 权限申请闸门与拒绝降级矩阵（docs/03-11 §2 / §3 / §6 / §8）。
 *
 * 03-11 §2 给出的 `PermissionGate` 含 `Activity` 参数；本模块是纯 JVM，
 * 只落地可判定部分：`requiredFor` / `explainDenied` / 申请时序策略。
 * `request(activity, permissions)` 与 UI 弹窗属 Android 侧 :app，
 * 由它把本对象的结果接进 `ActivityResultContracts.RequestMultiplePermissions`。
 */
object PermissionGate {

    /** 03-12 §2：targetSdk 35。 */
    const val TARGET_SDK = 35

    /** 按动作触发，禁止启动时一次性申请全部权限（03-11 §2 / §8 #2）。 */
    enum class UserAction {
        /** 03-11 §2.1：SAF 优先路径，用户已显式授权目录，无需任何读取权限。 */
        ADD_LOCAL_SAF,

        /** MediaStore 通道，需要读媒体权限（随 API 级别二选一）。 */
        ADD_LOCAL_MEDIASTORE,

        /** 首次开始播放前申请通知权限（03-07 §6）。 */
        START_PLAYBACK,

        /** 设置页 / 输出设备弹窗需要显示蓝牙设备名时（03-09 §4）。 */
        SHOW_BLUETOOTH_NAME,

        /** 硬规则：本地曲库浏览与本地播放永远不应触发权限申请。 */
        BROWSE_LOCAL_LIBRARY,
        PLAY_LOCAL_TRACK,
        SHOW_SETTINGS,
    }

    /** 一次权限申请的结果。三集合互斥，便于 B01 判定「不再询问」。 */
    data class GrantResult(
        val granted: Set<String> = emptySet(),
        val denied: Set<String> = emptySet(),
        val permanentlyDenied: Set<String> = emptySet(),
    ) {
        init {
            require(granted.none { it in denied || it in permanentlyDenied }) {
                "同一权限不能同时出现在 granted 与 denied 中"
            }
            require(permanentlyDenied.none { it in denied }) {
                "permanentlyDenied 是 denied 的子集语义，不应重复登记"
            }
        }

        /** 被拒绝的全集（含永久拒绝）。 */
        val allDenied: Set<String> get() = denied + permanentlyDenied
    }

    /** 03-11 §2 的默认签名：按 targetSdk 判定。 */
    fun requiredFor(action: UserAction): List<String> = requiredFor(action, TARGET_SDK)

    /**
     * 03-11 §2 时序表 + B09 / B10 的 API 级别分支。
     * 每个动作最多返回一个权限；启动/本地类动作返回空列表。
     */
    fun requiredFor(action: UserAction, sdkInt: Int): List<String> = when (action) {
        UserAction.ADD_LOCAL_SAF -> emptyList()                       // T03：SAF 不需要读取权限
        UserAction.ADD_LOCAL_MEDIASTORE -> listOf(                   // T04
            if (sdkInt >= 33) PermissionCatalog.READ_MEDIA_AUDIO
            else PermissionCatalog.READ_EXTERNAL_STORAGE
        )

        UserAction.START_PLAYBACK ->
            if (sdkInt >= 33) listOf(PermissionCatalog.POST_NOTIFICATIONS) else emptyList()

        UserAction.SHOW_BLUETOOTH_NAME ->
            if (sdkInt >= 31) listOf(PermissionCatalog.BLUETOOTH_CONNECT) else emptyList() // B09

        UserAction.BROWSE_LOCAL_LIBRARY,
        UserAction.PLAY_LOCAL_TRACK,
        UserAction.SHOW_SETTINGS -> emptyList()
    }

    /** 03-11 §8 #2：启动阶段不申请任何权限。 */
    fun requestsForStartup(): List<String> = emptyList()

    /** B01：永久拒绝后只给「去系统设置」说明，不循环弹窗。 */
    fun shouldRetryRequest(result: GrantResult): Boolean =
        result.permanentlyDenied.isEmpty() && result.denied.isNotEmpty()

    fun shouldShowSettingsEntry(result: GrantResult): Boolean = result.permanentlyDenied.isNotEmpty()

    /**
     * 03-11 §2 `explainDenied` 的纯 JVM 部分：返回面向用户的说明文本，
     * Android 侧负责渲染与「去系统设置」跳转。
     */
    fun explainDenied(result: GrantResult): List<String> = buildList {
        if (PermissionCatalog.POST_NOTIFICATIONS in result.allDenied) {
            add("开启后可显示锁屏控制与播放通知；当前仍可正常播放")
        }
        if (PermissionCatalog.BLUETOOTH_CONNECT in result.allDenied) {
            add("蓝牙设备名称不可见；拔出耳机自动暂停仍可用")
        }
        if (PermissionCatalog.READ_MEDIA_AUDIO in result.allDenied ||
            PermissionCatalog.READ_EXTERNAL_STORAGE in result.allDenied
        ) {
            add("可改用「添加本地目录」(SAF) 授权，无需读取权限")
        }
        if (result.permanentlyDenied.isNotEmpty()) {
            add("已选择「不再询问」，请到系统设置中手动开启")
        }
    }
}

/** 03-11 §3 权限拒绝降级矩阵的判定结果。 */
data class DegradationOutcome(
    /** §3 硬规则：任何权限拒绝都不得阻断本地曲库浏览与本地播放。 */
    val localLibraryAvailable: Boolean = true,
    val localPlaybackAvailable: Boolean = true,
    /** POST_NOTIFICATIONS 被拒 → 前台服务照常播放，仅无通知卡片（B03）。 */
    val mediaNotificationVisible: Boolean = true,
    /** BLUETOOTH_CONNECT 被拒 → 不显示设备名（03-09 §4 T07）。 */
    val bluetoothNameVisible: Boolean = true,
    /** 无需权限的 ACTION_AUDIO_BECOMING_NOISY 始终可用。 */
    val becomingNoisyPauseAvailable: Boolean = true,
    val mediaStoreImportAvailable: Boolean = true,
    /** SAF 路径下无需 READ_MEDIA_AUDIO。 */
    val safImportAvailable: Boolean = true,
    /** B02：系统回收 SAF 授权 → 该数据源标「暂不可用」，Track 不丢。 */
    val safStorageAvailable: Boolean = true,
    val ssdpDiscoveryAvailable: Boolean = true,
    /** B06：组播失败不静默失败，提供「手动指定 DMR」入口。 */
    val manualDmrRequired: Boolean = false,
    /** B05 / PRD §10 #10：飞行模式不弹网络错误。 */
    val showNetworkErrorDialog: Boolean = false,
    /** C-16（12 §61.34）：离线时网络音源不可达；本地能力恒可用、不弹错误弹窗。 */
    val networkSourceAvailable: Boolean = true,
    val hints: List<String> = emptyList(),
) {
    /** §8 #3：权限拒绝不得作为致命错误处理。 */
    val isCoreCapabilityBlocked: Boolean
        get() = !localLibraryAvailable || !localPlaybackAvailable
}

/**
 * 03-11 §3 降级矩阵的唯一裁决者。只依据「被拒集合 + 环境事实」计算能力，
 * 不持有任何 Android 组件，便于 JVM 单测覆盖全部组合。
 *
 * **C-16（`12 §61.34`）**：[offline] 此前是**死参数**（函数体从未读取），
 * 「飞行模式 / 网络权限」这一行的语义只体现在恒为 false 的
 * [DegradationOutcome.showNetworkErrorDialog] 上，两态断言实际等价。
 * 现在 `offline = true` 会把 [DegradationOutcome.networkSourceAvailable] 置 false
 * 并追加一条信息提示；§3 硬规则不变：本地曲库与本地播放恒可用、不弹网络错误弹窗。
 *
 * **C-17（`12 §61.35`）**：`offline` 的唯一来源是
 * [NetworkPlaybackPolicy.isOffline]`(currentKind())`，即 [NetworkKind.NONE]；
 * 播放闸门与 UI 降级共用同一份离线事实，由 `NetworkPlaybackPolicyTest` 交叉断言守护。
 */
object DegradationPolicy {

    fun evaluate(
        denied: Set<String>,
        safRevoked: Boolean = false,
        multicastBlocked: Boolean = false,
        offline: Boolean = false,
    ): DegradationOutcome {
        val has = { p: String -> p in denied }
        val notificationVisible = !has(PermissionCatalog.POST_NOTIFICATIONS)
        val btNameVisible = !has(PermissionCatalog.BLUETOOTH_CONNECT)
        val mediaStoreAvailable = !has(PermissionCatalog.READ_MEDIA_AUDIO) &&
                !has(PermissionCatalog.READ_EXTERNAL_STORAGE)
        val ssdpAvailable = !has(PermissionCatalog.CHANGE_WIFI_MULTICAST_STATE) && !multicastBlocked

        val hints = buildList {
            if (!notificationVisible) add("开启通知权限后可显示锁屏控制")
            if (!btNameVisible) add("蓝牙（名称不可见）")
            if (!mediaStoreAvailable) add("已引导改用 SAF 添加本地目录")
            if (safRevoked) add("目录授权已失效，数据源暂不可用，请重新授权")
            if (!ssdpAvailable) add("自动发现不可用，请手动指定 DMR")
            if (offline) add("离线中：网络音源暂不可用，本地曲库与播放不受影响")
        }

        // 硬规则：本地曲库浏览与本地播放恒可用；offline 只关闭网络音源，不弹网络错误。
        return DegradationOutcome(
            localLibraryAvailable = true,
            localPlaybackAvailable = true,
            mediaNotificationVisible = notificationVisible,
            bluetoothNameVisible = btNameVisible,
            becomingNoisyPauseAvailable = true,
            mediaStoreImportAvailable = mediaStoreAvailable,
            safImportAvailable = true,
            safStorageAvailable = !safRevoked,
            ssdpDiscoveryAvailable = ssdpAvailable,
            manualDmrRequired = !ssdpAvailable,
            showNetworkErrorDialog = false,
            networkSourceAvailable = !offline,
            hints = hints,
        )
    }
}
