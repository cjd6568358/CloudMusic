package com.example.cloudmusic.storage

/**
 * `AppSettings` 及全部持久设置项（docs/08-配置项总表.md §1 / §2，03-05 §4，03-11 §4）。
 *
 * 08 §8：「各文档的设置项章节以本表为唯一真相源；新增项须先登记本表再编码」。
 * 因此这里逐字段对齐 08 的键名、类型与默认值；Kotlin 用 camelCase，
 * KDoc 里标注原表键名，便于与 DataStore(Proto) 的字段映射核对。
 *
 * **单一来源（08 §6）**：`metadata_wifi_only` 与 `force_webdav_proxy` 只存在于
 * [NetworkSettings]，`auto_skip_on_error` 只作为持久值存在于 [PlaybackSettings]。
 * `ScanSettings` / `DlnaSettings` 在设置页只做展示引用，**不另存字段**。
 *
 * 本模块为纯 JVM：Proto 序列化注解与 DataStore 实例属 Android 侧适配层，
 * 数据模型与默认值在此保持可编译、可单测。
 */
data class AppSettings(
    /** 08 §1 / 03-05 §4：每个数据源的 Addition 以 `additionRaw` 明文保存（决策 ④）。 */
    val storages: List<StorageDto> = emptyList(),
    /** 08 §2.1 `theme`，默认深色优先 + 跟随系统。 */
    val theme: String = ThemeMode.DARK_FOLLOW_SYSTEM,
    val scan: ScanSettings = ScanSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val network: NetworkSettings = NetworkSettings(),
    val dlna: DlnaSettings = DlnaSettings(),
    /** §61.118（T5-1）：均衡器持久化——enabled + 各 band millibel（band 数设备相关）。 */
    val audiofx: AudiofxSettings = AudiofxSettings(),
)

/** 08 §2.1 `theme` 的合法取值；以 String 存储，与 03-05 §4 的签名保持一致。 */
object ThemeMode {
    const val DARK_FOLLOW_SYSTEM = "DARK_FOLLOW_SYSTEM"
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"
    const val FOLLOW_SYSTEM = "FOLLOW_SYSTEM"
    val ALL: Set<String> = setOf(DARK_FOLLOW_SYSTEM, LIGHT, DARK, FOLLOW_SYSTEM)
}

/** 08 §3 / 03-05 §4：DataStore 中的数据源 DTO。`additionRaw` 含凭据，禁止写日志与 Room。 */
data class StorageDto(
    val id: String,
    val mountPath: String,
    val driverType: String,
    /** 08 §5 敏感项：WebDAV 账号密码等明文，禁止进日志 / 崩溃上报 / Room。 */
    val additionRaw: String,
    val remark: String = "",
    val disabled: Boolean = false,
    val errorMsg: String = "",
    /** 03-11 §5：修改凭据时更新此值，触发 Driver 重建与全量重扫。 */
    val modified: Long = 0L,
)

/** 08 §2.2 `scan.*`。注意 `metadata_wifi_only` 按 §6 归 [NetworkSettings]。 */
data class ScanSettings(
    val ignoreDirs: List<String> = listOf("Android", ".thumbnails", ".git"),
    val showHidden: Boolean = true,
    val followSymlinks: Boolean = false,
    val minDurationMs: Long = 10_000L,
    val incrementalOnLaunch: Boolean = true,
    val mergeDuplicates: Boolean = false,
)

/** 08 §2.3 `network.*`；同时是 `metadata_wifi_only` / `force_webdav_proxy` 的唯一持久位置。 */
data class NetworkSettings(
    val wifiOnly: Boolean = true,
    val metadataWifiOnly: Boolean = true,
    val forceWebdavProxy: Boolean = false,
    val cacheToDisk: Boolean = false,
)

/** 08 §2.4 `playback.*`。 */
data class PlaybackSettings(
    val autoPauseOnDisconnect: Boolean = true,
    val showConnectedToast: Boolean = true,
    val autoSkipOnError: Boolean = true,
    val defaultMode: String = PlayMode.SEQUENCE,
    val restoreLastQueue: Boolean = true,
    /** 03-07 §4 / 03-09 §5：蓝牙回连不自动恢复，默认关。 */
    val resumeOnBtReconnect: Boolean = false,
)

/** 08 §2.4 `playback.default_mode` 的合法取值。 */
object PlayMode {
    const val SEQUENCE = "SEQUENCE"
    const val SINGLE_LOOP = "SINGLE_LOOP"
    const val LIST_LOOP = "LIST_LOOP"
    const val SHUFFLE = "SHUFFLE"
    val ALL: Set<String> = setOf(SEQUENCE, SINGLE_LOOP, LIST_LOOP, SHUFFLE)
}

/** 08 §2.5 `dlna.*`；`force_webdav_proxy` 按 §6 只归 [NetworkSettings]。 */
/** §61.118（T5-1）：audiofx 持久化。bands 为 millibel 数组（长度 = 实际 band 数）。 */
data class AudiofxSettings(
    val enabled: Boolean = false,
    /** 全频段电平（millibel，可为负）；空 = 未自定义（跟随档位默认）。 */
    val bandsMillibel: List<Int> = emptyList(),
    /** 档位（1~5，0=关）——与 §61.93 attachAudioEffect 的 effectId 同源。 */
    val effectLevel: Int = 0,
    /** §61.230（V0.2 M6）：淡入淡出开关。起播渐入 / 切曲与终点渐出（节拍器音量包络）。 */
    val fadeEnabled: Boolean = false,
    /** §61.230：淡入淡出时长（毫秒）；0 = 未自定义（跟随默认 2000）。 */
    val fadeDurationMs: Int = 0,
)

data class DlnaSettings(
    /** 上限 20，按 lastSeen 淘汰最旧（08 §2.5）。 */
    val manualDmrHistory: List<String> = emptyList(),
    val preferDirectLink: Boolean = true,
    /**
     * §61.241（V3-M2）：上次成功投屏设备 USN——进程死亡后仍生效的记忆
     * （区别于运行期 castingDevice：回本机不清，下次发现列表置顶 + 自动选择优先）。
     * null = 从未投屏。持久化进既有 settings JSON，零新存储设施。
     */
    val lastDmrUsn: String? = null,
    /** §61.241：上次投屏设备友好名（仅 UI 提示用；离线时列表无此设备，不伪造在线态）。 */
    val lastDmrName: String = "",
) {
    companion object {
        const val HISTORY_LIMIT = 20
    }

    /** 追加/提到最新，去重并淘汰最旧，保证 ≤ [HISTORY_LIMIT]。 */
    fun withManualDmr(deviceId: String): DlnaSettings {
        if (deviceId.isBlank()) return this
        val next = (manualDmrHistory.filter { it != deviceId } + deviceId).takeLast(HISTORY_LIMIT)
        return copy(manualDmrHistory = next)
    }

    /** §61.241：投屏成功时更新上次设备记忆（空 USN 不写）。 */
    fun withLastDmr(usn: String, name: String): DlnaSettings =
        if (usn.isBlank()) this else copy(lastDmrUsn = usn, lastDmrName = name)
}

/** 设置项合法性校验（08 §2 的取值范围 + 08 §5 的敏感项约束）。 */
object SettingsValidator {

    fun validate(settings: AppSettings): List<String> = buildList {
        if (settings.theme !in ThemeMode.ALL) add("theme 非法取值：${settings.theme}")
        if (settings.playback.defaultMode !in PlayMode.ALL) {
            add("playback.default_mode 非法取值：${settings.playback.defaultMode}")
        }
        if (settings.scan.minDurationMs < 0) add("scan.min_duration_ms 不得为负")
        if (settings.dlna.manualDmrHistory.size > DlnaSettings.HISTORY_LIMIT) {
            add("dlna.manual_dmr_history 超过 20 条上限")
        }
        if (settings.playback.resumeOnBtReconnect) {
            add("playback.resume_on_bt_reconnect 必须保持 false（永久 LOSS 语义）")
        }
        if (settings.network.cacheToDisk) add("network.cache_to_disk V0.1 恒为 false")
        val ids = settings.storages.map { it.id }
        if (ids.size != ids.toSet().size) add("storages[].id 重复")
    }

    fun isValid(settings: AppSettings): Boolean = validate(settings).isEmpty()
}
