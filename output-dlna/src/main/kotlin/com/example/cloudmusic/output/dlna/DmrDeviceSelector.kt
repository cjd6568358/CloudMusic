package com.example.cloudmusic.output.dlna

/**
 * docs/03-08 §4.1 / §10.1：DMR 设备列表的**选择判定**（纯 JVM，不触网）。
 *
 * SSDP 发现会重复上报同一 USN，手动指定设备与自动发现设备也会并存；UI 不应把
 * 原始列表直接铺给用户。本类收口三条口径：
 * - [ranked]：按 USN 去重（保留 lastSeen 最新的一条），手动指定优先，再按 lastSeen
 *   降序、友好名排序 —— 用户刚用过的设备排最前；
 * - [pick]：记住的 USN 仍在线 → 直接选它（投屏体验「上次那台」）；否则取排序第一台；
 * - 选中记录由调用方持有，本类无状态，便于 JVM 单测与 UI 重组零副作用。
 *
 * 手动指定 DMR（A7）的探测由 `ManualDmrProbe` 负责；本类只处理**已拿到的设备列表**。
 */
object DmrDeviceSelector {

    fun ranked(devices: List<DmrDevice>): List<DmrDevice> = devices
        .groupBy { it.usn }
        .values.mapNotNull { group -> group.maxByOrNull { it.lastSeen } }
        .sortedWith(
            compareByDescending<DmrDevice> { it.source == DeviceSource.MANUAL }
                .thenByDescending { it.lastSeen }
                .thenBy { it.friendlyName.lowercase() }
        )

    fun pick(devices: List<DmrDevice>, rememberedUsn: String?): DmrDevice? {
        val list = ranked(devices)
        if (list.isEmpty()) return null
        return if (rememberedUsn.isNullOrBlank()) list.first()
        else list.firstOrNull { it.usn == rememberedUsn } ?: list.first()
    }
}
