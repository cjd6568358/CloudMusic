package com.example.cloudmusic.output.dlna

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §61.200 / 03-08 §4.1：DMR 设备列表去重、排序与记忆选择的纯 JVM 契约。 */
class DmrDeviceSelectorTest {

    private fun device(
        usn: String,
        name: String = usn,
        source: DeviceSource = DeviceSource.SSDP,
        lastSeen: Long = 0L,
    ) = DmrDevice(
        usn = usn, friendlyName = name, manufacturer = null,
        baseUrl = "http://$name.local:49152",
        avTransportControlUrl = "http://$name.local:49152/ctrl",
        renderingControlUrl = "", eventSubUrl = null,
        source = source, lastSeen = lastSeen,
    )

    @Test
    @DisplayName("§61.200 L1：同一 USN 去重，保留 lastSeen 最新的一条")
    fun dedupeByUsn() {
        val list = DmrDeviceSelector.ranked(
            listOf(device("u1", "旧名", lastSeen = 10), device("u1", "新名", lastSeen = 99)),
        )
        assertEquals(1, list.size)
        assertEquals("新名", list[0].friendlyName)
    }

    @Test
    @DisplayName("§61.200 L2：手动设备优先，其次最近发现时间，最后按名称")
    fun rankOrder() {
        val list = DmrDeviceSelector.ranked(
            listOf(
                device("a", "阿尔法", lastSeen = 100),
                device("b", "贝塔", DeviceSource.MANUAL, lastSeen = 1),
                device("c", "查理", lastSeen = 200),
            ),
        )
        assertEquals(listOf("贝塔", "查理", "阿尔法"), list.map { it.friendlyName })
    }

    @Test
    @DisplayName("§61.200 L3：记住的设备仍在线则优先，否则回落排序第一台")
    fun pickRemembered() {
        val devices = listOf(device("a", "阿尔法", lastSeen = 100), device("b", "贝塔", lastSeen = 200))
        assertEquals("阿尔法", DmrDeviceSelector.pick(devices, "a")!!.friendlyName)
        assertEquals("贝塔", DmrDeviceSelector.pick(devices, "已下线")!!.friendlyName)
        assertEquals("贝塔", DmrDeviceSelector.pick(devices, null)!!.friendlyName)
    }

    @Test
    @DisplayName("§61.200 L4：空列表返回 null（UI 不得伪造设备）")
    fun emptyListReturnsNull() {
        assertNull(DmrDeviceSelector.pick(emptyList(), "u1"))
        assertEquals(emptyList<DmrDevice>(), DmrDeviceSelector.ranked(emptyList()))
    }

    // ------------------------------------------------------------------
    // §61.241（V3-M2）：上次投屏设备持久化记忆 → 发现列表置顶
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.241 L1：上次投屏设备在线 → 置顶（优先于手动/最近发现排序）")
    fun rankedPinsLastUsed() {
        val devices = listOf(
            device("manual", "手动盒子", DeviceSource.MANUAL, lastSeen = 300),
            device("tv", "客厅电视", lastSeen = 100),
            device("box", "卧室盒子", lastSeen = 200),
        )
        val list = DmrDeviceSelector.ranked(devices, "tv")
        assertEquals("客厅电视", list[0].friendlyName, "上次投屏置顶")
        assertEquals("手动盒子", list[1].friendlyName, "其余保持手动优先排序")
        assertEquals("卧室盒子", list[2].friendlyName)
    }

    @Test
    @DisplayName("§61.241 L2：null/空/已下线 USN → 现行排序零变化")
    fun rankedNoPinKeepsLegacyOrder() {
        val devices = listOf(
            device("a", "阿尔法", lastSeen = 100),
            device("b", "贝塔", DeviceSource.MANUAL, lastSeen = 1),
            device("c", "查理", lastSeen = 200),
        )
        val legacy = listOf("贝塔", "查理", "阿尔法")
        assertEquals(legacy, DmrDeviceSelector.ranked(devices).map { it.friendlyName }, "缺省参数零变化")
        assertEquals(legacy, DmrDeviceSelector.ranked(devices, null).map { it.friendlyName })
        assertEquals(legacy, DmrDeviceSelector.ranked(devices, "").map { it.friendlyName })
        assertEquals(legacy, DmrDeviceSelector.ranked(devices, "已下线-usn").map { it.friendlyName })
    }

    @Test
    @DisplayName("§61.241 L3：pick 持久化记忆直通——进程重启后读到的 USN 仍优先选中")
    fun pickWithPersistedUsn() {
        val devices = listOf(device("a", "阿尔法", lastSeen = 100), device("tv", "客厅电视", lastSeen = 50))
        assertEquals("客厅电视", DmrDeviceSelector.pick(devices, "tv")!!.friendlyName)
        // 下线回落排序第一台（贝塔语义），不伪造在线
        assertEquals("阿尔法", DmrDeviceSelector.pick(devices, "offline")!!.friendlyName)
    }
}
