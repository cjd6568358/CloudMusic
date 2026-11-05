package com.example.cloudmusic.storage

/**
 * 设置变更的生效规则（docs/08-配置项总表.md §7，03-11 §5 / §6 B11 / B14）。
 *
 * 08 §7 把「改了什么」映射到「必须做什么」；此处把它做成可单测的纯函数：
 * 设置页写入 DataStore 后，由 `effectsFor(before, after)` 一次性算出副作用集合，
 * 避免各页面各自判断而漏掉 Driver 重建 / 缓存失效。
 */
enum class SettingsChange {
    /** 03-11 B11：WebDAV 凭据 / 地址变更。 */
    STORAGE_CREDENTIALS,

    /** 新增 / 删除 / 禁用数据源。 */
    STORAGE_LIST,
    FORCE_WEBDAV_PROXY,
    PREFER_DIRECT_LINK,
    SCAN,
    PLAYBACK,
    THEME,
    NETWORK_GENERAL,
    DLNA_HISTORY,
}

enum class Effect {
    /** 仅持久化，无运行时副作用。 */
    SAVE_ONLY,

    /** Storage.modified → DriverRegistry.drop() 后重建 → 该 Storage 全量重扫（03-11 §5 / B11）。 */
    REBUILD_DRIVER_AND_FULL_RESCAN,

    /** `directUrl()` 探测结果缓存失效，下次投屏重新决策（08 §7）。 */
    INVALIDATE_DIRECT_URL_CACHE,

    /** 下一首曲目重新 `CastUriResolver.resolve()`（08 §7）。 */
    RESOLVE_NEXT_TRACK,

    /** 下次扫描生效，进行中的扫描不中断（08 §7）。 */
    NEXT_SCAN_ONLY,

    /** Compose 即时重组；播放页（照搬 View）保持一致不闪白（08 §7）。 */
    RECOMPOSE_IMMEDIATELY,
}

object SettingsChangePolicy {

    /** 逐分组对比，返回本次实际发生的变更集合；无变化返回空集。 */
    fun diff(before: AppSettings, after: AppSettings): Set<SettingsChange> = buildSet {
        if (before.storages != after.storages) {
            val sameShape = before.storages.size == after.storages.size &&
                    before.storages.zip(after.storages).all { (a, b) ->
                        a.id == b.id && a.driverType == b.driverType
                    }
            // 03-11 §5：凭据/地址变化必须重建 Driver；增删数据源同样需要重建注册表。
            add(if (sameShape) SettingsChange.STORAGE_CREDENTIALS else SettingsChange.STORAGE_LIST)
        }
        if (before.network.forceWebdavProxy != after.network.forceWebdavProxy) {
            add(SettingsChange.FORCE_WEBDAV_PROXY)
        }
        if (before.dlna.preferDirectLink != after.dlna.preferDirectLink) {
            add(SettingsChange.PREFER_DIRECT_LINK)
        }
        if (before.scan != after.scan) add(SettingsChange.SCAN)
        if (before.playback != after.playback) add(SettingsChange.PLAYBACK)
        if (before.theme != after.theme) add(SettingsChange.THEME)
        if (before.network.copy(forceWebdavProxy = after.network.forceWebdavProxy) != after.network) {
            add(SettingsChange.NETWORK_GENERAL)
        }
        if (before.dlna.manualDmrHistory != after.dlna.manualDmrHistory) {
            add(SettingsChange.DLNA_HISTORY)
        }
    }

    /** 08 §7 的单一映射表。 */
    fun effectsOf(change: SettingsChange): Set<Effect> = when (change) {
        SettingsChange.STORAGE_CREDENTIALS,
        SettingsChange.STORAGE_LIST -> setOf(Effect.REBUILD_DRIVER_AND_FULL_RESCAN)

        SettingsChange.FORCE_WEBDAV_PROXY -> setOf(
            Effect.INVALIDATE_DIRECT_URL_CACHE, Effect.RESOLVE_NEXT_TRACK,
        )

        SettingsChange.PREFER_DIRECT_LINK -> setOf(Effect.RESOLVE_NEXT_TRACK)
        SettingsChange.SCAN -> setOf(Effect.NEXT_SCAN_ONLY)
        SettingsChange.THEME -> setOf(Effect.RECOMPOSE_IMMEDIATELY)
        SettingsChange.PLAYBACK,
        SettingsChange.NETWORK_GENERAL,
        SettingsChange.DLNA_HISTORY -> setOf(Effect.SAVE_ONLY)
    }

    fun effectsFor(before: AppSettings, after: AppSettings): Set<Effect> {
        val effects = diff(before, after).flatMapTo(mutableSetOf<Effect>()) { effectsOf(it) }
        return if (effects.isEmpty()) setOf(Effect.SAVE_ONLY) else effects
    }

    /**
     * B14：设置页在飞行模式修改网络项仍正常保存，**不联网校验**。
     * 08 §7 的全部生效动作都是本地/下次生效，没有任何一项需要即时联网确认。
     */
    fun requiresNetworkValidation(change: SettingsChange): Boolean = false

    /** B11 的显式判据，供设置页与单测共用。 */
    fun requiresFullRescan(before: AppSettings, after: AppSettings): Boolean =
        Effect.REBUILD_DRIVER_AND_FULL_RESCAN in effectsFor(before, after)
}
