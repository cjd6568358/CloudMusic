package com.example.cloudmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * T-A5（`12 §31` / `03-07 §5` B12 / `03-11 §1.1`）· 媒体按键兼容兜底。
 *
 * 12 §50（真机 A3 修正）：原「有意不处理」在真机上被证明是链路断点 ——
 * 系统 / MIUI 的媒体键与 `cmd media_session dispatch` 会投递到本 receiver，
 * 空实现把按键静默吞掉，`PlaybackService` 的 `MediaSession.Callback` 永远收不到。
 * 这里改为把按键转成 [PlaybackService.ACTION_PLAY] / [ACTION_PAUSE]，
 * 由已运行的前台服务统一裁决（焦点 / FocusPolicy / 断连去重）。
 * 两条路径最终都落到同一份 `performPlay/Pause()`，且 `requestAudioFocus()` 幂等，
 * 不会重复消费同一次按键。
 *
 * `exported=true`：系统媒体会话需要向本 receiver 投递 ACTION_MEDIA_BUTTON。
 *
 * 12 §51（第四十二批）：键码 → action 的判定下沉到纯 JVM 的 [MediaKeyAction]，
 * 让 §50 的转发链路可被 `:app` 的 JVM 单测覆盖；本类只保留 Android 平台侧的解包与转发，
 * **判定语义与 §50 完全一致**（含 null 键码兜底为播放、未知键码忽略）。
 */
class MediaButtonEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        // 用 Bundle.get 避免 getParcelableExtra 的 API 33 泛型重载，minSdk 26 直用。
        val event = intent.extras?.get(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        val action = MediaKeyAction.resolve(event?.keyCode) ?: return
        context.startForegroundService(
            Intent(context, PlaybackService::class.java).setAction(action),
        )
    }
}
