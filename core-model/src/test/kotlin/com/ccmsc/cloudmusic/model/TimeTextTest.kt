package com.ccmsc.cloudmusic.model

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `§61.297` 时间文本单一真相源。
 *
 * 钉死三件事：
 * 1. 播放位置恒 `mm:ss`、负数按 0（时间轴端点不能出现 `-1:-1`）；
 * 2. 时长字段未知用 `—` 而不是 `00:00`（`§61.218 F3`）；
 * 3. **Locale 无关**——`ar_EG` / `fa_IR` 下仍必须是 ASCII 数字（实测不带 Locale
 *    的 `%02d` 会输出本地数字，时长变成 `٦٥:٣٠`）。
 */
class TimeTextTest {

    @Test
    fun `播放位置是 mm ss 且负数按 0`() {
        assertEquals("00:00", TimeText.clock(0L))
        assertEquals("00:00", TimeText.clock(-1L))
        assertEquals("00:00", TimeText.clock(999L))
        assertEquals("00:01", TimeText.clock(1_000L))
        assertEquals("01:05", TimeText.clock(65_000L))
        assertEquals("04:04", TimeText.clock(244_000L))
    }

    @Test
    fun `分钟位不折叠为小时 百分钟仍正常`() {
        assertEquals("65:30", TimeText.clock(3_930_000L))
        assertEquals("100:00", TimeText.clock(6_000_000L))
    }

    @Test
    fun `时长字段未知用占位而不是 00 00`() {
        assertEquals(TrackInfo.UNKNOWN, TimeText.duration(0L))
        assertEquals(TrackInfo.UNKNOWN, TimeText.duration(-5L))
        assertEquals("04:04", TimeText.duration(244_000L))
    }

    @Test
    fun `阿拉伯与波斯 locale 下仍是 ASCII 数字`() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val clock = TimeText.clock(3_930_000L)
                assertEquals("65:30", clock, "locale=$tag 输出被本地化")
                assertTrue(clock.all { it.code < 0x80 }, "locale=$tag 出现非 ASCII 数字：$clock")
                val dur = TimeText.duration(244_000L)
                assertEquals("04:04", dur, "locale=$tag 时长被本地化")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
