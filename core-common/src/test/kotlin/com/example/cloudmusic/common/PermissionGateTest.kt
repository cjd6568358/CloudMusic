package com.example.cloudmusic.common

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/03-11 §1 / §2 / §3 / §6 / §7 / §8 的纯 JVM 判定部分。
 * T01/T02 为 Manifest 静态口径，这里校验同一份 Kotlin 清单；
 * Python 侧 C3 与 `check_source_contracts.py` 负责 Manifest 与交叉一致性。
 */
class PermissionGateTest {

    @Test
    @DisplayName("T01 · 权限清单恰 11 项、无 §1.2 禁止项、allowBackup=false")
    fun declaredCatalogMatchesSpec() {
        assertEquals(11, PermissionCatalog.DECLARED.size)
        assertEquals(11, PermissionCatalog.ALLOWED.size)
        assertTrue(PermissionCatalog.ALLOWED.intersect(PermissionCatalog.FORBIDDEN).isEmpty())
        assertTrue(PermissionCatalog.DECLARED.all { it.name in PermissionCatalog.ALLOWED })
        assertFalse(PermissionCatalog.ALLOW_BACKUP, "10 §2 R4：明文凭据禁止随系统备份上云")
        val bt = PermissionCatalog.DECLARED.single { it.name == PermissionCatalog.BLUETOOTH_CONNECT }
        assertTrue(bt.neverForLocation, "03-11 §1.1：neverForLocation 避免被判定需要位置权限")
    }

    @Test
    @DisplayName("T02 / B08 · 权限总数上限 12，第三方 merge 后仍受约束")
    fun permissionCountWithinLimit() {
        assertTrue(PermissionCatalog.withinLimit(PermissionCatalog.DECLARED.size))
        assertTrue(PermissionCatalog.withinLimit(12))
        assertFalse(PermissionCatalog.withinLimit(13))
    }

    @Test
    @DisplayName("T03 · ADD_LOCAL_SAF 不需要任何读取权限（SAF 优先）")
    fun safRequiresNoPermission() {
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_SAF).isEmpty())
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_SAF, 26).isEmpty())
    }

    @Test
    @DisplayName("T04 · ADD_LOCAL_MEDIASTORE 在 API 33+ 申请 READ_MEDIA_AUDIO")
    fun mediaStoreRequiresReadAudio() {
        assertEquals(
            listOf(PermissionCatalog.READ_MEDIA_AUDIO),
            PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE, 35),
        )
        assertEquals(
            listOf(PermissionCatalog.READ_EXTERNAL_STORAGE),
            PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE, 32),
        )
    }

    @Test
    @DisplayName("B09 / B10 · API 级别分支：蓝牙 API<31 不申请；READ_EXTERNAL_STORAGE 在 API33+ 被排除")
    fun apiLevelBranches() {
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.SHOW_BLUETOOTH_NAME, 30).isEmpty())
        assertEquals(
            listOf(PermissionCatalog.BLUETOOTH_CONNECT),
            PermissionGate.requiredFor(PermissionGate.UserAction.SHOW_BLUETOOTH_NAME, 31),
        )
        val api35 = PermissionCatalog.effectiveAt(35).map { it.name }
        assertFalse(PermissionCatalog.READ_EXTERNAL_STORAGE in api35)
        assertTrue(PermissionCatalog.READ_MEDIA_AUDIO in api35)
        val api32 = PermissionCatalog.effectiveAt(32).map { it.name }
        assertTrue(PermissionCatalog.READ_EXTERNAL_STORAGE in api32)
        assertFalse(PermissionCatalog.READ_MEDIA_AUDIO in api32)
        assertFalse(PermissionCatalog.FOREGROUND_SERVICE_MEDIA_PLAYBACK in api32)
    }

    @Test
    @DisplayName("§8 #2 · 启动不批量申请；每个动作最多一个权限；本地动作零申请")
    fun noBulkRequestOnStartup() {
        assertTrue(PermissionGate.requestsForStartup().isEmpty())
        for (sdk in listOf(26, 30, 31, 33, 34, 35)) {
            for (action in PermissionGate.UserAction.entries) {
                val required = PermissionGate.requiredFor(action, sdk)
                assertTrue(required.size <= 1, "$action @ $sdk 不应一次申请多项")
                assertTrue(required.all { it in PermissionCatalog.ALLOWED })
            }
        }
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.BROWSE_LOCAL_LIBRARY).isEmpty())
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.PLAY_LOCAL_TRACK).isEmpty())
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.SHOW_SETTINGS).isEmpty())
    }

    @Test
    @DisplayName("B01 · 永久拒绝不循环弹窗，只给「去系统设置」说明")
    fun permanentDenialDoesNotLoop() {
        val result = PermissionGate.GrantResult(
            permanentlyDenied = setOf(PermissionCatalog.POST_NOTIFICATIONS),
        )
        assertFalse(PermissionGate.shouldRetryRequest(result))
        assertTrue(PermissionGate.shouldShowSettingsEntry(result))
        assertTrue(PermissionGate.explainDenied(result).any { "系统设置" in it })
        val softDenial = PermissionGate.GrantResult(denied = setOf(PermissionCatalog.POST_NOTIFICATIONS))
        assertTrue(PermissionGate.shouldRetryRequest(softDenial))
        assertFalse(PermissionGate.shouldShowSettingsEntry(softDenial))
    }

    @Test
    @DisplayName("§3 / B03 · 通知权限被拒仍可播放，只是无通知卡片")
    fun notificationDenialDegrades() {
        val o = DegradationPolicy.evaluate(setOf(PermissionCatalog.POST_NOTIFICATIONS))
        assertTrue(o.localPlaybackAvailable)
        assertFalse(o.mediaNotificationVisible)
        assertTrue(o.hints.any { "锁屏控制" in it })
        assertFalse(o.isCoreCapabilityBlocked)
    }

    @Test
    @DisplayName("§3 / 03-09 §4 · 蓝牙权限被拒不显示设备名，BECOMING_NOISY 自动暂停仍可用")
    fun bluetoothDenialDegrades() {
        val o = DegradationPolicy.evaluate(setOf(PermissionCatalog.BLUETOOTH_CONNECT))
        assertFalse(o.bluetoothNameVisible)
        assertTrue(o.becomingNoisyPauseAvailable)
        assertTrue(o.hints.any { "名称不可见" in it })
        assertTrue(o.localPlaybackAvailable)
    }

    @Test
    @DisplayName("§3 · 读媒体权限被拒引导 SAF；SAF 授权被回收不丢本地能力")
    fun storageDenialDegrades() {
        val denied = DegradationPolicy.evaluate(setOf(PermissionCatalog.READ_MEDIA_AUDIO))
        assertFalse(denied.mediaStoreImportAvailable)
        assertTrue(denied.safImportAvailable)
        assertTrue(denied.hints.any { "SAF" in it })

        val revoked = DegradationPolicy.evaluate(emptySet(), safRevoked = true)
        assertFalse(revoked.safStorageAvailable, "B02：数据源标「暂不可用」")
        assertTrue(revoked.localLibraryAvailable, "Track 与曲库不丢")
        assertTrue(revoked.localPlaybackAvailable)
    }

    @Test
    @DisplayName("§3 / B06 · 组播不可用时不静默失败，提供手动指定 DMR")
    fun multicastFailureRequiresManualDmr() {
        for (o in listOf(
            DegradationPolicy.evaluate(emptySet(), multicastBlocked = true),
            DegradationPolicy.evaluate(setOf(PermissionCatalog.CHANGE_WIFI_MULTICAST_STATE)),
        )) {
            assertFalse(o.ssdpDiscoveryAvailable)
            assertTrue(o.manualDmrRequired)
            assertTrue(o.hints.any { "手动指定 DMR" in it })
        }
    }

    @Test
    @DisplayName("§3 硬规则 / B05 · 任意拒绝组合下本地曲库与本地播放恒可用，飞行模式不弹网络错误")
    fun hardRuleNeverBlocksLocalPlayback() {
        val combos = listOf(
            emptySet(),
            PermissionCatalog.ALLOWED,
            setOf(
                PermissionCatalog.POST_NOTIFICATIONS, PermissionCatalog.BLUETOOTH_CONNECT,
                PermissionCatalog.READ_MEDIA_AUDIO, PermissionCatalog.CHANGE_WIFI_MULTICAST_STATE,
            ),
        )
        for (denied in combos) {
            for (offline in listOf(false, true)) {
                val o = DegradationPolicy.evaluate(denied, offline = offline)
                assertTrue(o.localLibraryAvailable)
                assertTrue(o.localPlaybackAvailable)
                assertFalse(o.showNetworkErrorDialog)
                assertFalse(o.isCoreCapabilityBlocked)
                assertTrue(o.hints.isNotEmpty() || denied.isEmpty())
            }
        }
    }
}
