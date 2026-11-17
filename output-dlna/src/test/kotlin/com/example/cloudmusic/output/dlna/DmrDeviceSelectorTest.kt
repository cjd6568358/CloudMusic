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
}
