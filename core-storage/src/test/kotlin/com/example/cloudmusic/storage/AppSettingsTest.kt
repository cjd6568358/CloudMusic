package com.example.cloudmusic.storage

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/08-配置项总表.md §1~§8 / 03-05 §4 / 03-11 §4~§7 的纯 JVM 判定部分。
 * 重点：默认值逐项对齐总表、08 §6 单一来源、08 §7 生效规则、B11 / B14。
 */
class AppSettingsTest {

    @Test
    @DisplayName("08 §1 / §2 · AppSettings 全部分组默认值与总表逐项一致")
    fun defaultsMatchCatalog() {
        val s = AppSettings()
        assertEquals(ThemeMode.DARK_FOLLOW_SYSTEM, s.theme)
        assertTrue(s.storages.isEmpty())

        assertEquals(listOf("Android", ".thumbnails", ".git"), s.scan.ignoreDirs)
        assertTrue(s.scan.showHidden)
        assertFalse(s.scan.followSymlinks)
        assertEquals(10_000L, s.scan.minDurationMs)
        assertTrue(s.scan.incrementalOnLaunch)
        assertFalse(s.scan.mergeDuplicates)

        assertTrue(s.network.wifiOnly)
        assertTrue(s.network.metadataWifiOnly)
        assertFalse(s.network.forceWebdavProxy)
        assertFalse(s.network.cacheToDisk)

        assertTrue(s.playback.autoPauseOnDisconnect)
        assertTrue(s.playback.showConnectedToast)
        assertTrue(s.playback.autoSkipOnError)
        assertEquals(PlayMode.SEQUENCE, s.playback.defaultMode)
        assertTrue(s.playback.restoreLastQueue)
        assertFalse(s.playback.resumeOnBtReconnect, "永久 LOSS 语义：蓝牙回连不自动恢复")

        assertTrue(s.dlna.manualDmrHistory.isEmpty())
        assertTrue(s.dlna.preferDirectLink)
        assertTrue(SettingsValidator.isValid(s))
    }

    @Test
    @DisplayName("08 §6 · 去重与单一来源：重复项只在一处持久")
    fun singleSourceOfTruth() {
        fun gettersOf(type: Class<*>) = type.declaredMethods.map { it.name }.toSet()

        // metadata_wifi_only / force_webdav_proxy 只归 NetworkSettings
        assertTrue("getMetadataWifiOnly" in gettersOf(NetworkSettings::class.java))
        assertFalse("getMetadataWifiOnly" in gettersOf(ScanSettings::class.java))
        assertTrue("getForceWebdavProxy" in gettersOf(NetworkSettings::class.java))
        assertFalse("getForceWebdavProxy" in gettersOf(DlnaSettings::class.java))
        // auto_skip_on_error 持久值只归 PlaybackSettings
        assertTrue("getAutoSkipOnError" in gettersOf(PlaybackSettings::class.java))
        assertFalse("getAutoSkipOnError" in gettersOf(NetworkSettings::class.java))
    }

    @Test
    @DisplayName("08 §2.5 · manualDmrHistory 去重、按最旧淘汰、上限 20")
    fun manualDmrHistoryIsBounded() {
        var settings = DlnaSettings()
        for (i in 1..25) settings = settings.withManualDmr("dmr-$i")
        assertEquals(20, settings.manualDmrHistory.size)
        assertEquals("dmr-6", settings.manualDmrHistory.first())
        assertEquals("dmr-25", settings.manualDmrHistory.last())

        val dedup = settings.withManualDmr("dmr-10")
        assertEquals(20, dedup.manualDmrHistory.size)
        assertEquals("dmr-25", dedup.manualDmrHistory[18])
        assertEquals("dmr-10", dedup.manualDmrHistory.last())
        assertEquals(settings, settings.withManualDmr("  "))
    }

    @Test
    @DisplayName("08 §2 取值范围 / 03-05 §4 · SettingsValidator 拦截非法值")
    fun validatorRejectsInvalidValues() {
        assertTrue(SettingsValidator.validate(AppSettings(theme = "NEON")).any { "theme" in it })
        assertTrue(
            SettingsValidator.validate(AppSettings(playback = PlaybackSettings(defaultMode = "RANDOM")))
                .any { "default_mode" in it }
        )
        assertTrue(
            SettingsValidator.validate(AppSettings(scan = ScanSettings(minDurationMs = -1)))
                .any { "min_duration_ms" in it }
        )
        assertTrue(
            SettingsValidator.validate(AppSettings(playback = PlaybackSettings(resumeOnBtReconnect = true)))
                .any { "resume_on_bt_reconnect" in it }
        )
        assertTrue(
            SettingsValidator.validate(AppSettings(network = NetworkSettings(cacheToDisk = true)))
                .any { "cache_to_disk" in it }
        )
        val dup = listOf(
            StorageDto("a", "/a", "local", ""),
            StorageDto("a", "/b", "webdav", ""),
        )
        assertTrue(SettingsValidator.validate(AppSettings(storages = dup)).any { "id 重复" in it })
    }

    private fun storage(additionRaw: String = "{}") = StorageDto(
        id = "nas", mountPath = "/music", driverType = "webdav", additionRaw = additionRaw,
    )

    @Test
    @DisplayName("B11 / 08 §7 · 修改 WebDAV 凭据 → Driver 重建 + 全量重扫")
    fun credentialChangeTriggersFullRescan() {
        val before = AppSettings(storages = listOf(storage("user:old")))
        val after = before.copy(storages = listOf(storage("user:new")))
        val effects = SettingsChangePolicy.effectsFor(before, after)
        assertTrue(Effect.REBUILD_DRIVER_AND_FULL_RESCAN in effects)
        assertTrue(SettingsChangePolicy.requiresFullRescan(before, after))
        assertEquals(setOf(SettingsChange.STORAGE_CREDENTIALS), SettingsChangePolicy.diff(before, after))
    }

    @Test
    @DisplayName("08 §7 · forceWebdavProxy / preferDirectLink / scan / theme 各自映射到正确副作用")
    fun changeEffectsMapping() {
        val base = AppSettings()
        assertTrue(
            Effect.INVALIDATE_DIRECT_URL_CACHE in SettingsChangePolicy.effectsFor(
                base, base.copy(network = base.network.copy(forceWebdavProxy = true)),
            )
        )
        assertEquals(
            setOf(Effect.RESOLVE_NEXT_TRACK),
            SettingsChangePolicy.effectsFor(base, base.copy(dlna = DlnaSettings(preferDirectLink = false))),
        )
        val scan = SettingsChangePolicy.effectsFor(base, base.copy(scan = ScanSettings(minDurationMs = 5_000)))
        assertEquals(setOf(Effect.NEXT_SCAN_ONLY), scan, "进行中的扫描不中断，不重建 Driver")
        assertEquals(
            setOf(Effect.RECOMPOSE_IMMEDIATELY),
            SettingsChangePolicy.effectsFor(base, base.copy(theme = ThemeMode.LIGHT)),
        )
        assertEquals(setOf(Effect.SAVE_ONLY), SettingsChangePolicy.effectsFor(base, base))
    }

    @Test
    @DisplayName("B14 · 飞行模式修改网络项正常保存，任何变更都不要求即时联网校验")
    fun offlineSettingChangesNeverRequireNetwork() {
        for (change in SettingsChange.entries) {
            assertFalse(
                SettingsChangePolicy.requiresNetworkValidation(change),
                "$change 不应触发联网校验",
            )
        }
        val base = AppSettings()
        val after = base.copy(network = base.network.copy(wifiOnly = false, metadataWifiOnly = false))
        assertEquals(setOf(Effect.SAVE_ONLY), SettingsChangePolicy.effectsFor(base, after))
    }

    @Test
    @DisplayName("03-11 §5 · 增删数据源也重建注册表；多项同时变更合并为并集")
    fun combinedChangesAreMerged() {
        val base = AppSettings(storages = listOf(storage()))
        val after = base.copy(
            storages = emptyList(),
            theme = ThemeMode.DARK,
            network = base.network.copy(forceWebdavProxy = true),
        )
        val diff = SettingsChangePolicy.diff(base, after)
        assertTrue(SettingsChange.STORAGE_LIST in diff)
        assertTrue(SettingsChange.THEME in diff)
        assertTrue(SettingsChange.FORCE_WEBDAV_PROXY in diff)
        val effects = SettingsChangePolicy.effectsFor(base, after)
        assertTrue(Effect.REBUILD_DRIVER_AND_FULL_RESCAN in effects)
        assertTrue(Effect.RECOMPOSE_IMMEDIATELY in effects)
        assertTrue(Effect.INVALIDATE_DIRECT_URL_CACHE in effects)
    }
}
