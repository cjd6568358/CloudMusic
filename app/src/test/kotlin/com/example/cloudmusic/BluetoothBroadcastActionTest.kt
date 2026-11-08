package com.example.cloudmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import com.example.cloudmusic.common.DegradationPolicy
import com.example.cloudmusic.common.PermissionCatalog
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.output.router.BluetoothEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `03-09 §4 / §8 / §10 B01~B14` 与 `03-11 §2/§3` 的 `:app` JVM 判定部分
 * （`12 §61.29`，T-L L2 收口）。平台常量按 AOSP 编译期常量内联，无需真机蓝牙硬件。
 */
class BluetoothBroadcastActionTest {

    @Test
    @DisplayName("03-09 §4 · A2DP 连接/断开映射，中间态不干预")
    fun a2dpConnectionStates() {
        assertEquals(
            BluetoothEvent.CONNECTED,
            BluetoothBroadcastAction.resolve(
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED,
            ),
        )
        assertEquals(
            BluetoothEvent.DISCONNECTED,
            BluetoothBroadcastAction.resolve(
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED,
            ),
        )
        assertNull(
            BluetoothBroadcastAction.resolve(
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTING,
            ),
            "CONNECTING 是中间态，不改输出状态（E_OUT_BT_STATE）",
        )
    }

    @Test
    @DisplayName("03-09 §10 B08/B13 · 适配器仅 STATE_OFF 视为全部断开，其余不干预")
    fun adapterOffOnly() {
        assertEquals(
            BluetoothEvent.ADAPTER_OFF,
            BluetoothBroadcastAction.resolve(
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.STATE_OFF,
            ),
        )
        assertNull(
            BluetoothBroadcastAction.resolve(
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.STATE_ON,
            ),
            "不主动开关蓝牙（B13）",
        )
    }

    @Test
    @DisplayName("03-09 §4 · 播放态仅记录、有线拔出无需权限、未知广播忽略")
    fun passiveAndNoisyEvents() {
        assertEquals(
            BluetoothEvent.PLAYING_STATE_CHANGED,
            BluetoothBroadcastAction.resolve(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED, -1),
        )
        assertEquals(
            BluetoothEvent.BECOMING_NOISY,
            BluetoothBroadcastAction.resolve(AudioManager.ACTION_AUDIO_BECOMING_NOISY, -1),
        )
        assertNull(BluetoothBroadcastAction.resolve("com.example.unknown", 0))
        assertNull(BluetoothBroadcastAction.resolve(null, 0))
    }

    @Test
    @DisplayName("03-09 §8 B01 / 03-11 §3 · 权限拒绝降级：不显示设备名，但核心能力不阻断")
    fun bluetoothDenialDegradesWithoutBlocking() {
        // 03-11 §2：API 31+ 才需要 BLUETOOTH_CONNECT（B09）
        assertEquals(
            listOf(PermissionCatalog.BLUETOOTH_CONNECT),
            PermissionGate.requiredFor(PermissionGate.UserAction.SHOW_BLUETOOTH_NAME, 31),
        )
        assertTrue(PermissionGate.requestsForStartup().isEmpty(), "启动阶段零申请")

        val denied = PermissionDecision.classify(
            mapOf(PermissionCatalog.BLUETOOTH_CONNECT to false),
            rationale = emptySet(),
        )
        assertTrue(PermissionCatalog.BLUETOOTH_CONNECT in denied.allDenied)
        val outcome = DegradationPolicy.evaluate(denied.allDenied)
        assertFalse(outcome.bluetoothNameVisible, "未授予时不显示设备名")
        assertTrue(outcome.localPlaybackAvailable, "B01：播放不受影响")
        assertTrue(PermissionDecision.coreAvailable(denied), "任何权限拒绝都不得阻断核心能力")
        assertTrue(
            PermissionDecision.hints(denied).any { "蓝牙设备名称不可见" in it },
            "拒绝后必须给出明确降级说明，不循环弹窗",
        )
    }
}
