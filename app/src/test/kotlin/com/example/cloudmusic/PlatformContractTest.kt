package com.example.cloudmusic

import android.content.Intent
import android.view.KeyEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 12 §51（第四十二批）· `12 §50` 真机 A3 平台层修复的 JVM 回归用例。
 *
 * §50 把 `MediaButtonEventReceiver` 从「有意空实现」改成把媒体键转发成
 * [PlaybackService.ACTION_PLAY] / [ACTION_PAUSE]，但当时**只有真机实证、没有 JVM 单测**。
 * 本类覆盖抽离后的纯判定逻辑 [MediaKeyAction]：按键映射、null 键码兜底、未知键码忽略、
 * action 入口的取值域。其余平台侧接线（restore / PlaybackState / mediaButtonReceiver /
 * 真类在位）由 `tools/check_source_contracts.py` 的 §50 长效守护静态断言。
 *
 * 不引入 Robolectric：`KeyEvent.KEYCODE_*` 是 Java 编译期常量，Kotlin 内联后进 class 文件，
 * 运行期不触发 Android 框架类初始化。
 */
class PlatformContractTest {

    @Test
    @DisplayName("12 §50 · MEDIA_PAUSE → ACTION_PAUSE（暂停键不再被空实现吞掉）")
    fun pauseKey() {
        assertEquals(
            PlaybackService.ACTION_PAUSE,
            MediaKeyAction.resolve(KeyEvent.KEYCODE_MEDIA_PAUSE),
        )
    }

    @Test
    @DisplayName("12 §50 · PLAY / PLAY_PAUSE / HEADSETHOOK / 无键码 → ACTION_PLAY")
    fun playKeys() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        ).forEach { key ->
            assertEquals(PlaybackService.ACTION_PLAY, MediaKeyAction.resolve(key), "key=$key")
        }
        // 广播未带 EXTRA_KEY_EVENT 时按 §50 的平台兜底语义视为播放。
        assertEquals(PlaybackService.ACTION_PLAY, MediaKeyAction.resolve(null))
    }

    @Test
    @DisplayName("12 §50 · 未知键码一律忽略（音量 / 上一首 / 下一首不得误触发播放）")
    fun unknownKeys() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            0, -1, 9999,
        ).forEach { key -> assertNull(MediaKeyAction.resolve(key), "key=$key") }
    }

    @Test
    @DisplayName("12 §50 · resolve 的取值域封闭：只可能是两个应用私有 action 或 null")
    fun closedValueDomain() {
        val allowed = setOf(PlaybackService.ACTION_PLAY, PlaybackService.ACTION_PAUSE)
        assertTrue(
            (-16..512).all { MediaKeyAction.resolve(it).let { v -> v == null || v in allowed } },
            "任意键码不得映射到应用私有 action 之外的值",
        )
    }

    @Test
    @DisplayName("12 §50 · action 入口是本应用私有常量，不与平台 ACTION_MEDIA_BUTTON 混淆")
    fun actionConstants() {
        assertEquals("com.example.cloudmusic.action.PLAY", PlaybackService.ACTION_PLAY)
        assertEquals("com.example.cloudmusic.action.PAUSE", PlaybackService.ACTION_PAUSE)
        assertTrue(PlaybackService.ACTION_PLAY != PlaybackService.ACTION_PAUSE)
        assertTrue(PlaybackService.ACTION_PLAY != Intent.ACTION_MEDIA_BUTTON)
        assertTrue(PlaybackService.ACTION_PAUSE != Intent.ACTION_MEDIA_BUTTON)
    }
}
