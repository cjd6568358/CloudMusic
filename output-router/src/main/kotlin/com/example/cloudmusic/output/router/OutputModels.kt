package com.example.cloudmusic.output.router

/** docs/03-09 §3 状态模型（逐字对齐）。 */

/** 蓝牙**不**单列枚举：它只是「本机输出」的一种路由状态，否则会出现蓝牙与 DLNA 同时激活的矛盾态。 */
enum class ActiveOutput { LOCAL, DLNA }

data class BluetoothInfo(
    val name: String,
    /** 仅本地展示，不上传、不入日志、不进曲库 DB（§10 B12）。 */
    val address: String,
    val connectedAt: Long,
    val codec: String? = null,
) {
    companion object {
        const val FALLBACK_NAME = "蓝牙设备"
    }
}

data class OutputDeviceState(
    val activeOutput: ActiveOutput,
    val bluetooth: BluetoothInfo?,
    /** UI 展示，如「蓝牙 · Sony WH-1000XM5」。 */
    val routeHint: String = "",
)

data class BluetoothSettings(
    /** PRD §6.3：断开蓝牙/拔出耳机自动暂停，可关。 */
    val autoPauseOnDisconnect: Boolean = true,
    val showConnectedToast: Boolean = true,
    /** false = 未授予 BLUETOOTH_CONNECT，降级为只处理 ACTION_AUDIO_BECOMING_NOISY（§4 / B01）。 */
    val permissionGranted: Boolean = true,
)

enum class BluetoothEvent {
    CONNECTED,
    DISCONNECTED,
    /** 蓝牙适配器关闭，视为全部断开（B08）。 */
    ADAPTER_OFF,
    /** 有线耳机拔出（ACTION_AUDIO_BECOMING_NOISY），无需权限（B01 / B09）。 */
    BECOMING_NOISY,
    /** 仅记录，不干预，避免与照搬层重复裁决。 */
    PLAYING_STATE_CHANGED,
}

/** 策略处理结果（供 UI / 单测断言，不含任何设备地址）。 */
data class BluetoothEventResult(
    val bluetoothUpdated: Boolean = false,
    val pauseDispatched: Boolean = false,
    val toastShown: Boolean = false,
) {
    companion object {
        val NO_OP = BluetoothEventResult()
    }
}
