package com.example.cloudmusic.di.settings

import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.output.localproxy.ProxySettings
import com.example.cloudmusic.player.PlayerConfig
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.Effect
import com.example.cloudmusic.storage.SettingsChangePolicy
import com.example.cloudmusic.storage.StorageDto
import kotlinx.coroutines.flow.Flow

/**
 * docs/08-配置项总表.md §6 / §7 的**设置访问端口**（纯 JVM，零 Android 依赖）。
 *
 * 第十八批（`12 §27`，T-A2）：DataStore 实例由 [SettingsStore] 实现，但 Repository 与
 * 运行时投影只依赖本接口，因此仓储行为可在 JVM 单测里用 fake 完整覆盖（端口隔离惯例）。
 */
interface SettingsAccess {
    /** 持久设置的变更流；UI / 运行时投影必须订阅它（架构 §10 单向数据流）。 */
    val settings: Flow<AppSettings>

    /** 最近的已加载值；首次读取前可能是全默认值，见 [snapshot]。 */
    fun current(): AppSettings

    /** 挂起读取磁盘上的真实值，用于启动初始化。 */
    suspend fun snapshot(): AppSettings

    /** 原子写入，并返回 08 §7 的生效副作用集合。 */
    suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate
}

/** 一次设置写入的结果；[effects] 由 `SettingsChangePolicy.effectsFor` 统一裁决（08 §7）。 */
data class SettingsUpdate(
    val before: AppSettings,
    val after: AppSettings,
    val effects: Set<Effect>,
) {
    companion object {
        fun of(before: AppSettings, after: AppSettings): SettingsUpdate =
            SettingsUpdate(before, after, SettingsChangePolicy.effectsFor(before, after))
    }
}

/**
 * 08 §6「单一来源」的运行时投影：持久值只在 `AppSettings` 里存一份，
 * `ProxySettings` / `PlayerConfig` 等由这里派生，**不另建持久字段**。
 */
object SettingsProjection {
    fun proxy(settings: AppSettings): ProxySettings = ProxySettings(
        preferDirectLink = settings.dlna.preferDirectLink,
        forceWebdavProxy = settings.network.forceWebdavProxy,
    )

    fun playerConfig(settings: AppSettings): PlayerConfig = PlayerConfig(
        autoSkipOnError = settings.playback.autoSkipOnError,
        restoreLastQueue = settings.playback.restoreLastQueue,
    )
}

/** 03-05 §4 `StorageDto`（DataStore）↔ `:core-model` 的 `Storage`；`additionRaw` 原样保留明文。 */
fun StorageDto.toModel(): Storage = Storage(
    id = id,
    mountPath = mountPath,
    driverType = driverType,
    addition = Addition(additionRaw),
    remark = remark,
    disabled = disabled,
    errorMsg = errorMsg,
    modified = modified,
)

fun Storage.toDto(): StorageDto = StorageDto(
    id = id,
    mountPath = mountPath,
    driverType = driverType,
    additionRaw = addition.raw,
    remark = remark,
    disabled = disabled,
    errorMsg = errorMsg,
    modified = modified,
)
