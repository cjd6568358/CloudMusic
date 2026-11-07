package com.example.cloudmusic.output.router

import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.media.FocusChange
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.media.MediaAction
import com.example.cloudmusic.media.MediaKey
import com.example.cloudmusic.media.PlayerControllerHandle
import com.example.cloudmusic.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-09 §3 / §4 / §5 / §7 + §12 T01~T09 / T15 的纯 JVM 判定部分。 */
class OutputRoutingContractTest {

    private class FakeMediaControl : IMediaControl {
        val focusEvents = mutableListOf<FocusChange>()
        var otherCalls = 0
            private set
        override fun onFocusChange(change: FocusChange) { focusEvents.add(change) }
        override fun onMediaButton(key: MediaKey, action: MediaAction) { otherCalls++ }
        override fun bind(controller: PlayerControllerHandle) { otherCalls++ }
        override fun publishMetadata(track: Track, coverPath: String?) { otherCalls++ }
        override fun setPlaybackState(state: EngineState, positionMs: Long, durationMs: Long) { otherCalls++ }
        override fun setEnabled(enabled: Boolean) { otherCalls++ }
        override fun release() { otherCalls++ }
    }

    private fun fixture(
        settings: BluetoothSettings = BluetoothSettings(),
    ): Triple<InMemoryOutputRouter, FakeMediaControl, BluetoothOutputPolicy> {
        val router = InMemoryOutputRouter()
        val media = FakeMediaControl()
        return Triple(router, media, BluetoothOutputPolicy(settings, media, router))
    }

    private fun info(name: String = "Sony WH-1000XM5", address: String = "AA:BB") =
        BluetoothInfo(name, address, connectedAt = 1_000L)

    @Test
    @DisplayName("03-09 §12 T01 STATE_CONNECTED → 设备信息正确、播放状态不变")
    fun t01() = runTest {
        val (router, media, policy) = fixture()
        val result = policy.onEvent(BluetoothEvent.CONNECTED, info())
        assertTrue(result.bluetoothUpdated)
        assertTrue(result.toastShown)
        assertEquals("Sony WH-1000XM5", router.state().first().bluetooth?.name)
        assertEquals("蓝牙 · Sony WH-1000XM5", router.state().first().routeHint)
        assertEquals(ActiveOutput.LOCAL, router.state().first().activeOutput)
        assertTrue(media.focusEvents.isEmpty())
        assertEquals(0, media.otherCalls)
    }

    @Test
    @DisplayName("03-09 §12 T02 断开 + 开关开 → 只经 IMediaControl.onFocusChange(LOSS)")
    fun t02() {
        val (_, media, policy) = fixture()
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        val result = policy.onEvent(BluetoothEvent.DISCONNECTED)
        assertTrue(result.pauseDispatched)
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
        assertEquals(0, media.otherCalls)
        assertEquals(1, policy.pauseCount)
    }

    @Test
    @DisplayName("03-09 §12 T03 autoPauseOnDisconnect=false → 无任何暂停调用")
    fun t03() {
        val (_, media, policy) = fixture(BluetoothSettings(autoPauseOnDisconnect = false))
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        val result = policy.onEvent(BluetoothEvent.DISCONNECTED)
        assertFalse(result.pauseDispatched)
        assertTrue(media.focusEvents.isEmpty())
    }

    @Test
    @DisplayName("03-09 §12 T04 activeOutput=DLNA 时断开 → 忽略，DMR 继续播放")
    fun t04() = runTest {
        val (router, media, policy) = fixture()
        router.setActive(ActiveOutput.DLNA)
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        val result = policy.onEvent(BluetoothEvent.DISCONNECTED)
        assertFalse(result.pauseDispatched)
        assertTrue(media.focusEvents.isEmpty())
        assertEquals(ActiveOutput.DLNA, router.activeOutput)
        assertNull(router.state().first().bluetooth)
    }

    @Test
    @DisplayName("03-09 §12 T05 蓝牙回连 → 不自动恢复播放")
    fun t05() {
        val (_, media, policy) = fixture()
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        policy.onEvent(BluetoothEvent.DISCONNECTED)
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
        assertFalse(media.focusEvents.contains(FocusChange.GAIN))
        assertEquals(0, media.otherCalls)
    }

    @Test
    @DisplayName("03-09 §12 T06 断开 + 来电叠加重合 → 只暂停一次；用户恢复后可再暂停")
    fun t06() {
        val (_, media, policy) = fixture()
        policy.onEvent(BluetoothEvent.DISCONNECTED)
        policy.onEvent(BluetoothEvent.BECOMING_NOISY)
        policy.onEvent(BluetoothEvent.ADAPTER_OFF)
        assertEquals(1, media.focusEvents.size)
        policy.notifyPlaybackResumed()
        policy.onEvent(BluetoothEvent.DISCONNECTED)
        assertEquals(2, media.focusEvents.size)
    }

    @Test
    @DisplayName("03-09 §12 T07 / B01 未授权限 → 降级，只有线拔出仍能暂停")
    fun t07() = runTest {
        val (router, media, policy) = fixture(BluetoothSettings(permissionGranted = false))
        assertEquals(BluetoothEventResult.NO_OP, policy.onEvent(BluetoothEvent.CONNECTED, info()))
        assertNull(router.state().first().bluetooth)
        assertEquals(0, policy.toastCount)
        val noisy = policy.onEvent(BluetoothEvent.BECOMING_NOISY)
        assertTrue(noisy.pauseDispatched)
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
    }

    @Test
    @DisplayName("03-09 §12 T08 / B07 设备名为空 → 兜底文案")
    fun t08() = runTest {
        val (router, _, policy) = fixture()
        policy.onEvent(BluetoothEvent.CONNECTED, info(name = "   "))
        assertEquals(BluetoothInfo.FALLBACK_NAME, router.state().first().bluetooth?.name)
        assertEquals("蓝牙 · ${BluetoothInfo.FALLBACK_NAME}", router.state().first().routeHint)
    }

    @Test
    @DisplayName("03-09 §12 T09 / B14 重复 STATE_CONNECTED → 只提示一次")
    fun t09() {
        val (_, _, policy) = fixture()
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        assertEquals(1, policy.toastCount)
        policy.onEvent(BluetoothEvent.DISCONNECTED)
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        assertEquals(2, policy.toastCount)
    }

    @Test
    @DisplayName("EA01 PLAYING_STATE_CHANGED 仅记录，不干预")
    fun ea01() {
        val (_, media, policy) = fixture()
        assertEquals(BluetoothEventResult.NO_OP, policy.onEvent(BluetoothEvent.PLAYING_STATE_CHANGED, info()))
        assertTrue(media.focusEvents.isEmpty())
    }

    @Test
    @DisplayName("EA02 B08 适配器关闭视为全部断开，触发一次暂停")
    fun ea02() {
        val (_, media, policy) = fixture()
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        policy.onEvent(BluetoothEvent.ADAPTER_OFF)
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
    }

    @Test
    @DisplayName("EA03 OutputRouter 是唯一裁决者：state Flow 与 setActive")
    fun ea03() = runTest {
        val (router, _, _) = fixture()
        assertEquals(ActiveOutput.LOCAL, router.state().first().activeOutput)
        router.setActive(ActiveOutput.DLNA)
        assertEquals("DLNA", router.state().first().routeHint)
        assertEquals(ActiveOutput.DLNA, router.activeOutput)
        router.setActive(ActiveOutput.LOCAL)
        assertEquals(ActiveOutput.LOCAL, router.state().first().activeOutput)
    }

    @Test
    @DisplayName("EA04 §7 DLNA 回落本机 → refreshBluetoothFrom 重新读取 A2DP 状态")
    fun ea04() = runTest {
        val (router, _, _) = fixture()
        router.setActive(ActiveOutput.DLNA)
        router.updateBluetooth(null)
        router.setActive(ActiveOutput.LOCAL)
        router.refreshBluetoothFrom { info() }
        assertEquals("Sony WH-1000XM5", router.state().first().bluetooth?.name)
        assertEquals(ActiveOutput.LOCAL, router.activeOutput)
    }

    @Test
    @DisplayName("EA05 B10 切 DLNA 期间收到蓝牙连接 → 只更新设备信息，不改 activeOutput")
    fun ea05() = runTest {
        val (router, media, policy) = fixture()
        router.setActive(ActiveOutput.DLNA)
        policy.onEvent(BluetoothEvent.CONNECTED, info())
        assertEquals(ActiveOutput.DLNA, router.activeOutput)
        assertEquals("Sony WH-1000XM5", router.state().first().bluetooth?.name)
        assertTrue(media.focusEvents.isEmpty())
    }

    @Test
    @DisplayName("03-09 §12 T15 静态：策略不持有 PlayerController，也不直接调 pause")
    fun t15() {
        val clazz = BluetoothOutputPolicy::class.java
        val holdsController = clazz.declaredFields.any {
            it.type.simpleName?.contains("PlayerController") == true
        } || clazz.declaredMethods.any { m ->
            m.parameterTypes.any { it.simpleName?.contains("PlayerController") == true }
        }
        assertFalse(holdsController)
        val (_, media, policy) = fixture()
        policy.onEvent(BluetoothEvent.DISCONNECTED)
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
        assertEquals(0, media.otherCalls)
    }

    @Test
    @DisplayName("12 §46 updateSettings 热更新：运行时关闭开关后 BECOMING_NOISY 不暂停，重开后仍可暂停")
    fun productionHotUpdate() {
        val (router, media, policy) = fixture(BluetoothSettings(permissionGranted = false))
        // permissionGranted=false 时只放行 BECOMING_NOISY（B01 降级路径）
        assertTrue(policy.onEvent(BluetoothEvent.BECOMING_NOISY).pauseDispatched)
        assertEquals(listOf(FocusChange.LOSS), media.focusEvents)
        policy.notifyPlaybackResumed()

        // 模拟用户在设置页关闭自动暂停，Service 每次事件前刷新配置
        policy.updateSettings(
            BluetoothSettings(autoPauseOnDisconnect = false, permissionGranted = false),
        )
        assertFalse(policy.onEvent(BluetoothEvent.BECOMING_NOISY).pauseDispatched)
        assertEquals(1, media.focusEvents.size)

        // 重新开启后仍可暂停（去重标记已由 notifyPlaybackResumed 复位）
        policy.updateSettings(
            BluetoothSettings(autoPauseOnDisconnect = true, permissionGranted = false),
        )
        assertTrue(policy.onEvent(BluetoothEvent.BECOMING_NOISY).pauseDispatched)
        assertEquals(2, media.focusEvents.size)
        assertEquals(ActiveOutput.LOCAL, router.activeOutput)
    }
}
