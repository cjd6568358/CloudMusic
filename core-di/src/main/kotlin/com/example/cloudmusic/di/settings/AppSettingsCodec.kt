package com.example.cloudmusic.di.settings

import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.DlnaSettings
import com.example.cloudmusic.storage.NetworkSettings
import com.example.cloudmusic.storage.PlayMode
import com.example.cloudmusic.storage.PlaybackSettings
import com.example.cloudmusic.storage.ScanSettings
import com.example.cloudmusic.storage.StorageDto
import com.example.cloudmusic.storage.ThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * docs/03-05 §4 / 08-配置项总表.md 的 `AppSettings` 持久化编解码（**纯 JVM，零 Android 依赖**）。
 *
 * 第十八批（`12 §27`，T-A2）：`03-05 §4` 的 `@Serializable` 是规格示意，`:core-storage` 的模型
 * 自第九批起刻意保持「零注解 / 零编译器插件 / 纯 JVM 可单测」。因此 DataStore 的
 * `Serializer<AppSettings>` 使用这里的**显式 JSON codec**，而不是给 8 个设置类加注解再引入
 * kotlinx-serialization 编译器插件（D-49）。好处：
 * - 字段缺失 / 类型变化时按 `AppSettings()` 默认值回落（03-02 `firstStr()` 兼容读取同型）；
 * - `additionRaw` 作为字符串整体存放，凭据里的 `:` `@` 中文 `"` `\` 由 JSON 转义，不进日志、不进 Room；
 * - 编解码逻辑可在 JVM 单测里逐字段断言，无需实例化 Android `Context`。
 */
object AppSettingsCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(settings: AppSettings): String = buildJsonObject {
        put("storages", buildJsonArray { settings.storages.forEach { add(encodeStorage(it)) } })
        put("theme", settings.theme)
        put("scan", buildJsonObject {
            put("ignoreDirs", buildJsonArray { settings.scan.ignoreDirs.forEach { add(JsonPrimitive(it)) } })
            put("showHidden", settings.scan.showHidden)
            put("followSymlinks", settings.scan.followSymlinks)
            put("minDurationMs", settings.scan.minDurationMs)
            put("incrementalOnLaunch", settings.scan.incrementalOnLaunch)
            put("mergeDuplicates", settings.scan.mergeDuplicates)
        })
        put("playback", buildJsonObject {
            put("autoPauseOnDisconnect", settings.playback.autoPauseOnDisconnect)
            put("showConnectedToast", settings.playback.showConnectedToast)
            put("autoSkipOnError", settings.playback.autoSkipOnError)
            put("defaultMode", settings.playback.defaultMode)
            put("restoreLastQueue", settings.playback.restoreLastQueue)
            put("resumeOnBtReconnect", settings.playback.resumeOnBtReconnect)
        })
        put("network", buildJsonObject {
            put("wifiOnly", settings.network.wifiOnly)
            put("metadataWifiOnly", settings.network.metadataWifiOnly)
            put("forceWebdavProxy", settings.network.forceWebdavProxy)
            put("cacheToDisk", settings.network.cacheToDisk)
        })
        put("dlna", buildJsonObject {
            put("manualDmrHistory", buildJsonArray {
                settings.dlna.manualDmrHistory.forEach { add(JsonPrimitive(it)) }
            })
            put("preferDirectLink", settings.dlna.preferDirectLink)
        })
    }.toString()

    fun decode(raw: String): AppSettings {
        if (raw.isBlank()) return AppSettings()
        val root = json.parseToJsonElement(raw).jsonObject
        val scan = root.obj("scan")
        val playback = root.obj("playback")
        val network = root.obj("network")
        val dlna = root.obj("dlna")
        return AppSettings(
            storages = (root["storages"] as? JsonArray)
                ?.map { decodeStorage(it.jsonObject) } ?: emptyList(),
            theme = root.str("theme", ThemeMode.DARK_FOLLOW_SYSTEM),
            scan = ScanSettings(
                ignoreDirs = scan.strs("ignoreDirs", listOf("Android", ".thumbnails", ".git")),
                showHidden = scan.bool("showHidden", true),
                followSymlinks = scan.bool("followSymlinks", false),
                minDurationMs = scan.long("minDurationMs", 10_000L),
                incrementalOnLaunch = scan.bool("incrementalOnLaunch", true),
                mergeDuplicates = scan.bool("mergeDuplicates", false),
            ),
            playback = PlaybackSettings(
                autoPauseOnDisconnect = playback.bool("autoPauseOnDisconnect", true),
                showConnectedToast = playback.bool("showConnectedToast", true),
                autoSkipOnError = playback.bool("autoSkipOnError", true),
                defaultMode = playback.str("defaultMode", PlayMode.SEQUENCE),
                restoreLastQueue = playback.bool("restoreLastQueue", true),
                resumeOnBtReconnect = playback.bool("resumeOnBtReconnect", false),
            ),
            network = NetworkSettings(
                wifiOnly = network.bool("wifiOnly", true),
                metadataWifiOnly = network.bool("metadataWifiOnly", true),
                forceWebdavProxy = network.bool("forceWebdavProxy", false),
                cacheToDisk = network.bool("cacheToDisk", false),
            ),
            dlna = DlnaSettings(
                manualDmrHistory = dlna.strs("manualDmrHistory", emptyList()),
                preferDirectLink = dlna.bool("preferDirectLink", true),
            ),
        )
    }

    /** 损坏文件不静默清库：返回全默认设置，由 Serializer 记入失败记录（03-05 §11 / B10）。 */
    fun decodeOrDefault(raw: String): AppSettings = try {
        decode(raw)
    } catch (_: Exception) {
        AppSettings()
    }

    private fun encodeStorage(dto: StorageDto): JsonObject = buildJsonObject {
        put("id", dto.id)
        put("mountPath", dto.mountPath)
        put("driverType", dto.driverType)
        // 凭据原文：只作为 JSON 字符串值，绝不拼接进日志或 SQL
        put("additionRaw", dto.additionRaw)
        put("remark", dto.remark)
        put("disabled", dto.disabled)
        put("errorMsg", dto.errorMsg)
        put("modified", dto.modified)
    }

    private fun decodeStorage(obj: JsonObject): StorageDto = StorageDto(
        id = obj.str("id", ""),
        mountPath = obj.str("mountPath", ""),
        driverType = obj.str("driverType", ""),
        additionRaw = obj.str("additionRaw", "{}"),
        remark = obj.str("remark", ""),
        disabled = obj.bool("disabled", false),
        errorMsg = obj.str("errorMsg", ""),
        modified = obj.long("modified", 0L),
    )

    private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: buildJsonObject { }
    private fun JsonObject.str(key: String, default: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default
    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default
    private fun JsonObject.long(key: String, default: Long): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: default
    private fun JsonObject.strs(key: String, default: List<String>): List<String> =
        (this[key] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: default
}
