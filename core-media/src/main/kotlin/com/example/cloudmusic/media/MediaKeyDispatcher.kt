package com.example.cloudmusic.media

/**
 * docs/03-07 §5「媒体按键分发」的纯 JVM 参考实现。
 *
 * 竞品的连击 / 长按间隔常量（`13 §8` 挂账：焦点状态机真实类名待 jadx）以实际 dex 为准，
 * 这里把 [doubleClickWindowMs] / [seekDeltaMs] 做成可注入参数，便于校准而不改调用方。
 * `targetSdk 35` 的入口迁移（Receiver → `MediaSession.Callback`，§5 补注）属 Android 侧，
 * 本类只负责**同一个按键只分发一次**的语义，两个入口不得重复调用 [dispatch]。
 */
class MediaKeyDispatcher(
    private val seekDeltaMs: Long = 10_000L,
    private val doubleClickWindowMs: Long = 300L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var lastUpAt = Long.MIN_VALUE
    private var lastUpKey: MediaKey? = null

    fun dispatch(
        key: MediaKey,
        action: MediaAction,
        handle: PlayerControllerHandle,
        isPlaying: () -> Boolean = { false },
    ) {
        when (action) {
            MediaAction.DOWN -> Unit // 单击语义在 UP 时触发，避免 DOWN/UP 双触发

            MediaAction.UP -> {
                val t = now()
                val doubleClick = lastUpKey == key && t - lastUpAt in 0..doubleClickWindowMs
                lastUpAt = t
                lastUpKey = key
                if (doubleClick) {
                    lastUpKey = null // 消费掉，避免三连击被判成两次双击
                    handle.next()
                } else {
                    dispatchSingle(key, handle, isPlaying())
                }
            }

            // 照搬层已判定的复合动作，直接执行
            MediaAction.DOUBLE_CLICK -> handle.next()
            MediaAction.LONG_PRESS -> handle.seekRelative(seekDeltaMs)
        }
    }

    private fun dispatchSingle(key: MediaKey, handle: PlayerControllerHandle, playing: Boolean) {
        when (key) {
            MediaKey.PLAY -> handle.play()
            MediaKey.PAUSE -> handle.pause()
            MediaKey.PLAY_PAUSE -> if (playing) handle.pause() else handle.play()
            MediaKey.NEXT -> handle.next()
            MediaKey.PREV -> handle.prev()
            // PlayerControllerHandle 无 stop()：按竞品语义映射为暂停（03-07 §3 句柄）
            MediaKey.STOP -> handle.pause()
            MediaKey.FAST_FORWARD -> handle.seekRelative(seekDeltaMs)
            MediaKey.REWIND -> handle.seekRelative(-seekDeltaMs)
        }
    }

    fun reset() {
        lastUpAt = Long.MIN_VALUE
        lastUpKey = null
    }
}
