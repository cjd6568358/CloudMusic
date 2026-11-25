package com.ccmsc.cloudmusic.di.playback

import com.ccmsc.cloudmusic.common.NetworkKind
import com.ccmsc.cloudmusic.scanner.ScanRequest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.323（B-2）：`onlyWifiMetadata` / `wifiAvailable` 两个「有字段、无写方」的接线判据。
 *
 * 修复前：两个字段恒为默认 true ⇒ 扫描侧的
 * `includeMetadata && (!onlyWifiMetadata || wifiAvailable)` 恒真 ⇒
 * 「仅 WiFi 补元数据」开关永不生效（架构审计 §4 序 11）。
 */
class EffectiveScanRequestTest {

    private val base = ScanRequest(storageIds = setOf("s-1"), refresh = true)

    @Test
    @DisplayName("§61.323 B-2 · WiFi + 设置开 → 允许补元数据")
    fun wifiWithSettingOn() {
        val r = effectiveScanRequest(base, metadataWifiOnly = true, kind = NetworkKind.WIFI)
        assertTrue(r.onlyWifiMetadata, "设置开 → 限定 WiFi")
        assertTrue(r.wifiAvailable, "WiFi → 事实位真")
        assertTrue(r.includeMetadata && (!r.onlyWifiMetadata || r.wifiAvailable), "应进 metadata 阶段")
    }

    @Test
    @DisplayName("§61.323 B-2 · 移动网络 + 设置开 → 事实位为假，跳过 metadata（旧版恒 true）")
    fun mobileWithSettingOnBlocks() {
        val r = effectiveScanRequest(base, metadataWifiOnly = true, kind = NetworkKind.MOBILE)
        assertFalse(r.wifiAvailable, "移动网络 → 事实位必须为假（旧实现恒 true 是根因）")
        assertFalse(r.includeMetadata && (!r.onlyWifiMetadata || r.wifiAvailable), "应跳过 metadata 阶段")
    }

    @Test
    @DisplayName("§61.323 B-2 · 设置关 + 移动网络 → 设置项真的被尊重（旧版从未传进请求）")
    fun settingOffIgnoresNetwork() {
        val r = effectiveScanRequest(base, metadataWifiOnly = false, kind = NetworkKind.MOBILE)
        assertFalse(r.onlyWifiMetadata, "设置关 → 不限定 WiFi")
        assertTrue(r.includeMetadata && (!r.onlyWifiMetadata || r.wifiAvailable), "不限定则照常补元数据")
    }

    @Test
    @DisplayName("§61.323 B-2 · 无网络 / 以太网 / 未知 → 一律不当作 WiFi")
    fun nonWifiKinds() {
        for (kind in listOf(NetworkKind.NONE, NetworkKind.OTHER)) {
            val r = effectiveScanRequest(base, metadataWifiOnly = true, kind = kind)
            assertFalse(r.wifiAvailable, "$kind 不应被当作 WiFi")
        }
    }

    @Test
    @DisplayName("§61.323 B-2 · 只改写这两个字段，其余请求语义原样保留")
    fun keepsOtherFields() {
        val r = effectiveScanRequest(base, metadataWifiOnly = false, kind = NetworkKind.WIFI)
        assertEquals(base.storageIds, r.storageIds)
        assertEquals(base.refresh, r.refresh)
        assertEquals(base.batchSize, r.batchSize)
        assertEquals(base.metadataConcurrency, r.metadataConcurrency)
        assertEquals(base.onlyUnavailable, r.onlyUnavailable)
    }
}
