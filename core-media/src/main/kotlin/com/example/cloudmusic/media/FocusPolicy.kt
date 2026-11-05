package com.example.cloudmusic.media

/**
 * docs/03-07 §4「焦点状态机（照搬语义）」的纯 JVM 参考实现。
 *
 * 不变量（§12 T01~T04）：
 *  - `LOSS_TRANSIENT` 暂停后收到 GAIN **自动恢复**（[resumePending] 标记「因焦点而暂停」）；
 *  - 永久 `LOSS` / 用户手动暂停 / 切 DLNA 后收到 GAIN **不自动恢复**；
 *  - duck 结束 `unduck()` 后音量回到 1.0；
 *  - `OFF`（竞品 AUDIOFOCUS_OFF / IGNORE 常量语义）不响应。
 */
enum class FocusState { IDLE, PLAYING, PAUSED_FOR_FOCUS, DUCKED, LOST, MANUAL_PAUSED }

class FocusPolicy(private val duckVolume: Float = 0.25f) {

    var state: FocusState = FocusState.IDLE
        private set
    var volume: Float = 1f
        private set

    private var resumePending = false

    fun onFocusChange(change: FocusChange, handle: PlayerControllerHandle) {
        when (change) {
            FocusChange.GAIN,
            FocusChange.GAIN_TRANSIENT,
            FocusChange.GAIN_TRANSIENT_MAY_DUCK -> onGain(handle)

            FocusChange.LOSS -> {
                handle.pauseFromFocus()
                resumePending = false
                state = FocusState.LOST
            }

            FocusChange.LOSS_TRANSIENT -> {
                handle.pauseFromFocus()
                resumePending = true
                state = FocusState.PAUSED_FOR_FOCUS
            }

            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                handle.duck(duckVolume)
                volume = duckVolume
                state = FocusState.DUCKED
            }

            FocusChange.OFF -> Unit // 竞品常量语义：不响应
        }
    }

    private fun onGain(handle: PlayerControllerHandle) {
        when {
            state == FocusState.DUCKED -> {
                handle.unduck()
                volume = 1f
                state = FocusState.PLAYING
            }

            state == FocusState.PAUSED_FOR_FOCUS && resumePending -> {
                handle.unduck()
                handle.resumeFromFocus()
                volume = 1f
                resumePending = false
                state = FocusState.PLAYING
            }

            else -> {
                // 有焦点但播放由用户 / 业务层决定：绝不下发 resumeFromFocus()
                resumePending = false
                state = FocusState.IDLE
            }
        }
    }

    /** 用户手动暂停 → 清掉「因焦点而暂停」标记（§12 T03）。 */
    fun onManualPause() {
        resumePending = false
        state = FocusState.MANUAL_PAUSED
    }

    /** 切到 DLNA 等外部输出 → 本机会话停用，不参与恢复（§12 T09）。 */
    fun onDisabled() {
        resumePending = false
        state = FocusState.IDLE
    }

    fun onPlaying() {
        state = FocusState.PLAYING
    }

    val isResumePending: Boolean get() = resumePending
}
