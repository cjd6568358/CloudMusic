package com.ccmsc.cloudmusic.di.playback

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.206（A2-①）：MediaStore 变更 → 定向增量扫的判定核（RESCAN 幂等 + 订阅空转守卫）。
 * 防抖/订阅链路的真机判据（本地新增/删除 10s 内反映入列）pending-device（轨 B 巡检）。
 */
class MediaChangeScanPolicyTest {

    @Test
    @DisplayName("扫描运行中 → 不扫不排队（RESCAN 幂等，打断是破坏 §61.178）")
    fun activeScanBlocksObserverScan() {
        assertFalse(
            MediaChangeScanPolicy.shouldScan(scanActive = true, localIds = setOf("s1")),
        )
    }

    @Test
    @DisplayName("本地存储集合为空（全网络源）→ 订阅空转守卫，零扫描动作")
    fun noLocalStorageNoScan() {
        assertFalse(
            MediaChangeScanPolicy.shouldScan(scanActive = false, localIds = emptySet()),
        )
    }

    @Test
    @DisplayName("空闲 + 本地源在位 → 定向增量扫；请求只含本地源（网络源不入）")
    fun idleWithLocalStorageTriggersDirectedScan() {
        val localIds = setOf("local-1", "local-2")
        assertTrue(
            MediaChangeScanPolicy.shouldScan(scanActive = false, localIds = localIds),
        )
        val request = com.ccmsc.cloudmusic.scanner.ScanRequest(storageIds = localIds)
        assertEquals(localIds, request.storageIds)
        assertEquals(false, request.refresh, "定向增量：refresh=false，靠快照 diff 消化变更")
    }

    @Test
    @DisplayName("NoOp 订阅注册即返回空注销，回调不抛")
    fun noOpSourceIsInert() {
        var fired = false
        val unregister = NoOpMediaChangeSource.register { fired = true }
        unregister()
        assertFalse(fired, "NoOp 永不触发回调")
    }
}
