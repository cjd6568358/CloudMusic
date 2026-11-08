package com.example.cloudmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import com.example.cloudmusic.output.router.BluetoothEvent

/**
 * `12 §61.29` / `03-09 §4` · 蓝牙 ACL 广播 → [BluetoothEvent] 的**纯判定层**。
 *
 * 与 `MediaKeyAction`（`12 §51`）同型：Android 侧 receiver 只负责解出 `action` 与
 * `EXTRA_STATE` 的 [Int]，映射交给本对象，因此 `03-09 §10 B01~B14` 的裁决表能在
 * `:app` 的 JVM 单测里被真正断言，而不依赖真机或蓝牙硬件。
 *
 * 平台常量的真相源仍是 AOSP 编译期常量（`public static final`），Kotlin 会内联进
 * 调用方，故 JVM 单测运行不触发 `android.bluetooth.*` 类初始化。
 *
 * 未识别的 action / 中间态（`CONNECTING` / `DISCONNECTING`）一律返回 null：
 * 不干预、不改输出状态（`03-09 §9 E_OUT_BT_STATE` 的静态部分）。
 */
object BluetoothBroadcastAction {

    fun resolve(action: String?, state: Int): BluetoothEvent? = when (action) {
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> when (state) {
            BluetoothProfile.STATE_CONNECTED -> BluetoothEvent.CONNECTED
            BluetoothProfile.STATE_DISCONNECTED -> BluetoothEvent.DISCONNECTED
            else -> null
        }

        // B08：适配器关闭视为全部断开；STATE_ON / TURNING_* 不干预（B13 不主动开关蓝牙）。
        BluetoothAdapter.ACTION_STATE_CHANGED ->
            if (state == BluetoothAdapter.STATE_OFF) BluetoothEvent.ADAPTER_OFF else null

        // §4：仅记录不干预，避免与照搬层重复裁决。
        BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> BluetoothEvent.PLAYING_STATE_CHANGED

        // B01 / B09：无需 BLUETOOTH_CONNECT 的有线拔出事件，未授权时仍必须可用。
        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> BluetoothEvent.BECOMING_NOISY

        else -> null
    }
}
