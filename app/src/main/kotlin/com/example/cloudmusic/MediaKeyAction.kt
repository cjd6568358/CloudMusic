package com.example.cloudmusic

import android.view.KeyEvent

/**
 * 12 §51（第四十二批）· 媒体键 → Service action 的**纯判定逻辑**。
 *
 * 从 [MediaButtonEventReceiver] 抽出的无状态映射：Receiver 只负责把平台的
 * `EXTRA_KEY_EVENT` 解成 [Int] 键码并调用 [resolve]，再由前台 Service 统一裁决
 * （焦点 / FocusPolicy / 断连去重 / C-15 状态回传）。这样 §50 真机修复的
 * 「按键不再被空实现吞掉」才能在 `:app` 的 JVM 单测里被真正断言，而不依赖真机。
 *
 * 键码真相源仍是 AOSP [KeyEvent] 的编译期常量（`public static final int`），
 * Kotlin 会内联进调用方，故本对象在 JVM 单测中运行不触发 Android 框架类初始化。
 * 未知键码返回 null（不转发），`null` 键码（广播未带 EXTRA_KEY_EVENT）按
 * `12 §50` 的平台兜底语义视为播放。
 */
object MediaKeyAction {

    /** 只消费这四个媒体键；其余键码（含音量键）一律忽略，避免误触发播放。 */
    fun resolve(keyCode: Int?): String? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackService.ACTION_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        null -> PlaybackService.ACTION_PLAY
        else -> null
    }
}
