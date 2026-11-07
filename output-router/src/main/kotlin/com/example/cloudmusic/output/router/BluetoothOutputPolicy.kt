package com.example.cloudmusic.output.router

import com.example.cloudmusic.media.FocusChange
import com.example.cloudmusic.media.IMediaControl

/**
 * docs/03-09 §4 / §5「断连自动暂停」的纯 JVM 策略（T01~T09、B01~B10）。
 *
 * ★ 唯一暂停出口是 [IMediaControl.onFocusChange]`(FocusChange.LOSS)`，
 * **禁止**直接调 `PlayerController.pause()`：照搬层需要维护「是否因焦点暂停」的恢复标记，
 * 直接暂停会导致来电恢复、蓝牙回连后状态错乱（§5 / §13 / T15）。
 *
 * 本类只依赖 `:core-media` 门面与 [OutputRouter]，不碰 `android.bluetooth`；
 * 广播接收器（BluetoothObserver）属 Android 侧，把系统事件翻译成本模块的 [BluetoothEvent]。
 */
class BluetoothOutputPolicy(
    private var settings: BluetoothSettings = BluetoothSettings(),
    private val mediaControl: IMediaControl,
    private val router: OutputRouter,
) {
    private var lastConnectedAddress: String? = null
    /** 连续断开 / 来电叠加时只暂停一次（T06 / B04）；用户重新播放后由 [notifyPlaybackResumed] 复位。 */
    private var pauseInFlight = false

    var toastCount = 0
        private set
    var pauseCount = 0
        private set

    /**
     * 生产接线（`12 §46`）：设置存在 DataStore 的 `Flow` 里，用户在设置页关闭
     * `autoPauseOnDisconnect` 后必须**立即生效**，不能沿用构造时的快照。
     * `PlaybackService` 在每次断连事件前用 `SettingsAccess.current()` 刷新本配置；
     * 只替换配置，**保留 [pauseInFlight] 去重状态**（B04 / T06）。
     */
    fun updateSettings(next: BluetoothSettings) {
        settings = next
    }

    fun onEvent(event: BluetoothEvent, info: BluetoothInfo? = null): BluetoothEventResult {
        // B01 / T07：未授予 BLUETOOTH_CONNECT 时降级，只保留无需权限的有线拔出事件
        if (!settings.permissionGranted && event != BluetoothEvent.BECOMING_NOISY) {
            return BluetoothEventResult.NO_OP
        }

        return when (event) {
            BluetoothEvent.CONNECTED -> onConnected(info)

            BluetoothEvent.DISCONNECTED,
            BluetoothEvent.ADAPTER_OFF,
            BluetoothEvent.BECOMING_NOISY -> onDisconnected()

            // §4：仅记录，不干预，避免与照搬层重复裁决
            BluetoothEvent.PLAYING_STATE_CHANGED -> BluetoothEventResult.NO_OP
        }
    }

    private fun onConnected(info: BluetoothInfo?): BluetoothEventResult {
        if (info == null) return BluetoothEventResult.NO_OP
        val effective = info.copy(name = info.name.ifBlank { BluetoothInfo.FALLBACK_NAME })
        val duplicate = lastConnectedAddress == effective.address
        lastConnectedAddress = effective.address
        val visible = if (settings.permissionGranted) effective else effective.copy(name = "")
        router.updateBluetooth(visible)

        val toast = !duplicate && settings.showConnectedToast && settings.permissionGranted
        if (toast) toastCount++
        // ★ 回连不自动恢复播放（T05 / B05）：这里绝不下发 GAIN / resume
        return BluetoothEventResult(bluetoothUpdated = true, toastShown = toast)
    }

    private fun onDisconnected(): BluetoothEventResult {
        lastConnectedAddress = null
        router.updateBluetooth(null)

        val shouldPause = settings.autoPauseOnDisconnect &&
            router.activeOutput == ActiveOutput.LOCAL &&
            !pauseInFlight
        if (!shouldPause) {
            // B03 / B10：投屏中或关闭开关时，只更新设备信息，不改 activeOutput、不触发暂停
            return BluetoothEventResult(bluetoothUpdated = true)
        }
        mediaControl.onFocusChange(FocusChange.LOSS)
        pauseInFlight = true
        pauseCount++
        return BluetoothEventResult(bluetoothUpdated = true, pauseDispatched = true)
    }

    /** 用户手动恢复播放后调用，允许下一次断开再次暂停（回连本身不调用它）。 */
    fun notifyPlaybackResumed() {
        pauseInFlight = false
    }
}
