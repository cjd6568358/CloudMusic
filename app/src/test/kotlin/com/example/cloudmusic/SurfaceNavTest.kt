package com.example.cloudmusic

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §61.183：验收期 debug 导航通道的纯 JVM 判定（SurfaceNav.parse）。 */
class SurfaceNavTest {

    @Test
    @DisplayName("OPEN_PLAYER / CLOSE_PLAYER 映射到对应 surface")
    fun mapsKnownActions() {
        assertEquals(SurfaceNav.Surface.PLAYER, SurfaceNav.parse(SurfaceNav.ACTION_OPEN_PLAYER))
        assertEquals(SurfaceNav.Surface.HOME, SurfaceNav.parse(SurfaceNav.ACTION_CLOSE_PLAYER))
    }

    @Test
    @DisplayName("常规 LAUNCHER 启动 / 未知 action 返回 null（零行为变化）")
    fun ignoresNonNavActions() {
        assertNull(SurfaceNav.parse(IntentDefaults.MAIN))
        assertNull(SurfaceNav.parse("android.intent.action.VIEW"))
        assertNull(SurfaceNav.parse(""))
        assertNull(SurfaceNav.parse(null))
    }

    private object IntentDefaults {
        const val MAIN = "android.intent.action.MAIN"
    }
}
