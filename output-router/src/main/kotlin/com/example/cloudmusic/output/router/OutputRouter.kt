package com.example.cloudmusic.output.router

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/03-09 §7「OutputRouter 是**唯一裁决者**」：
 * 03-08 切 DLNA、03-09 上报蓝牙状态都只改它，不互相直接调用（03-08 §14 #9）。
 */
interface OutputRouter {
    fun state(): Flow<OutputDeviceState>
    val activeOutput: ActiveOutput
    fun setActive(output: ActiveOutput)
    fun updateBluetooth(info: BluetoothInfo?)

    /** DLNA 回落本机时重新读取当前 A2DP 状态刷新设备信息（§7）。 */
    fun refreshBluetoothFrom(source: () -> BluetoothInfo?)
}

class InMemoryOutputRouter : OutputRouter {

    private val _state = MutableStateFlow(
        OutputDeviceState(ActiveOutput.LOCAL, null, ""),
    )

    override fun state(): Flow<OutputDeviceState> = _state.asStateFlow()

    override val activeOutput: ActiveOutput get() = _state.value.activeOutput

    override fun setActive(output: ActiveOutput) {
        _state.value = _state.value.copy(
            activeOutput = output,
            routeHint = if (output == ActiveOutput.DLNA) "DLNA" else hint(_state.value.bluetooth),
        )
    }

    override fun updateBluetooth(info: BluetoothInfo?) {
        _state.value = _state.value.copy(bluetooth = info, routeHint = hint(info))
    }

    override fun refreshBluetoothFrom(source: () -> BluetoothInfo?) {
        updateBluetooth(source())
    }

    private fun hint(info: BluetoothInfo?): String = when {
        info == null -> ""
        info.name.isBlank() -> "蓝牙 · ${BluetoothInfo.FALLBACK_NAME}"
        else -> "蓝牙 · ${info.name}"
    }
}
