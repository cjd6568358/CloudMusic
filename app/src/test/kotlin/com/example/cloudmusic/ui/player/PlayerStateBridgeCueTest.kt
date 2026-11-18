package com.example.cloudmusic.ui.player

import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** §61.226：CUE 分轨 UI 相对时间映射纯函数（displayDuration / displayPosition / absoluteSeek）。 */
class PlayerStateBridgeCueTest {

    private val whole = Track(
        id = TrackId("w"), title = "t", artist = "a", album = "b", durationMs = 100_000L,
        sourceRef = SourceRef("s", "/p.flac"),
        sourceType = com.example.cloudmusic.model.SourceType.LOCAL,
    )
    private val cued = whole.copy(cueStartMs = 30_000L, cueEndMs = 60_000L)

    @Test
    @DisplayName("§61.226 · 显示时长：有界分轨 = 包络长；整轨 / 无界 / end 超界原样")
    fun displayDuration() {
        assertEquals(30_000L, PlayerStateBridge.displayDuration(100_000L, cued), "end-start = 30s")
        assertEquals(100_000L, PlayerStateBridge.displayDuration(100_000L, whole), "整轨原样")
        // end=0 无界：引擎真值原样
        assertEquals(100_000L, PlayerStateBridge.displayDuration(
            100_000L, whole.copy(cueStartMs = 0L, cueEndMs = 0L)))
        // end 超过引擎真值（数据异常）：不裁剪，原样
        assertEquals(100_000L, PlayerStateBridge.displayDuration(
            100_000L, whole.copy(cueStartMs = 10_000L, cueEndMs = 200_000L)))
    }

    @Test
    @DisplayName("§61.226 · 显示位置：absolute ≥ cueStart 时减偏移（0 起点）；整轨原样")
    fun displayPosition() {
        assertEquals(15_000L, PlayerStateBridge.displayPosition(45_000L, cued), "45s → 分轨内 15s")
        assertEquals(0L, PlayerStateBridge.displayPosition(30_000L, cued), "正好在起点")
        assertEquals(45_000L, PlayerStateBridge.displayPosition(45_000L, whole), "整轨原样")
    }

    @Test
    @DisplayName("§61.226 · onSeek 换算：相对时间 + cueStart = absolute；往返一致")
    fun absoluteSeek() {
        assertEquals(45_000L, PlayerStateBridge.absoluteSeek(15_000L, cued))
        assertEquals(30_000L, PlayerStateBridge.absoluteSeek(0L, cued), "相对 0 = 分轨起点")
        assertEquals(15_000L, PlayerStateBridge.absoluteSeek(15_000L, whole), "整轨不加偏移")
        // 往返一致：display(absoluteSeek(x)) == x
        val x = 12_345L
        assertEquals(x, PlayerStateBridge.displayPosition(
            PlayerStateBridge.absoluteSeek(x, cued), cued))
    }
}
