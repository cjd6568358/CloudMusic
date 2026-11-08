package com.example.cloudmusic.common

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/01 §6.1` / `03-02 §344` / `03-11 §4 §6 B05 B14` / `08 §2.3` 的纯 JVM 判定部分
 * （`12 §61.28`）。「默认仅 WiFi 播放」的全部边界都被压成一张可穷举的表。
 */
class NetworkPlaybackPolicyTest {

    private fun reason(wifiOnly: Boolean, kind: NetworkKind, networkSource: Boolean = true) =
        NetworkPlaybackPolicy.rejectReason(wifiOnly, kind, networkSource)

    @Test
    @DisplayName("01 §6.1 · wifiOnly=true 时只有 WIFI 放行网络音源")
    fun onlyWifiAllowsNetworkSource() {
        assertNull(reason(true, NetworkKind.WIFI))
        val mobile = reason(true, NetworkKind.MOBILE)
        assertNotNull(mobile)
        assertTrue("移动网络" in mobile)
        val offline = reason(true, NetworkKind.NONE)
        assertNotNull(offline)
        assertTrue("无网络" in offline)
        val other = reason(true, NetworkKind.OTHER)
        assertNotNull(other)
        assertTrue("不是 WiFi" in other)
    }

    @Test
    @DisplayName("03-11 §4 · 用户开启移动数据播放（wifiOnly=false）后任何网络都放行")
    fun mobileSwitchDisablesGate() {
        NetworkKind.entries.forEach { kind ->
            assertNull(reason(false, kind), "wifiOnly=false 不应拦截 $kind")
        }
    }

    @Test
    @DisplayName("03-11 §6 B05 · 飞行模式 / 无网下本地音源一律放行（含未 prepare 的未知态）")
    fun localSourceNeverBlocked() {
        NetworkKind.entries.forEach { kind ->
            assertNull(reason(true, kind, networkSource = false), "本地音源被 $kind 误拦")
            assertNull(reason(false, kind, networkSource = false))
        }
    }

    @Test
    @DisplayName("07 §2/§8 · 拒绝原因落到已登记的 E_DS_UPSTREAM_WIFI_ONLY，不新增错误码类别")
    fun errorCodeIsRegistered() {
        val error = CloudMusicError.DsUpstream(NetworkPlaybackPolicy.ERROR_CODE, "测试原因")
        assertEquals("E_DS_UPSTREAM_WIFI_ONLY", error.code)
        assertTrue(ErrorCatalog.isRegistered(error.code), "通配前缀必须已在 07 §2 登记")
        assertEquals(ErrorCatalog.Domain.DS, ErrorCatalog.lookup(error.code)?.domain)
        assertTrue(!ErrorCatalog.isFatal(error.code), "策略拒绝不是致命错误，不得阻断启动")
    }

    @Test
    @DisplayName("C-17 · 离线事实单一真相源：NetworkKind.NONE ⇔ DegradationPolicy.offline")
    fun offlineFactHasSingleSource() {
        for (kind in NetworkKind.entries) {
            val offline = NetworkPlaybackPolicy.isOffline(kind)
            assertEquals(kind == NetworkKind.NONE, offline)
            val outcome = DegradationPolicy.evaluate(emptySet(), offline = offline)
            assertEquals(!offline, outcome.networkSourceAvailable)
            assertTrue(outcome.localLibraryAvailable && outcome.localPlaybackAvailable)
            assertFalse(outcome.showNetworkErrorDialog)
        }
        val none = reason(true, NetworkKind.NONE)
        assertNotNull(none)
        assertTrue("无网络" in none, "播放闸门对 NONE 的拒绝原因应可感知")
    }

    @Test
    @DisplayName("端口基线 · 未注入生产实现时 ALLOW_ALL 不拦截任何播放")
    fun allowAllBaseline() {
        assertNull(NetworkPlaybackPolicy.ALLOW_ALL.rejectReason(true))
        assertNull(NetworkPlaybackPolicy.ALLOW_ALL.rejectReason(false))
    }
}
