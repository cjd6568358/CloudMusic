package com.example.cloudmusic.media

import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-07 §3 门面契约 + §12 T01~T07 的纯 JVM 判定部分。 */
class MediaControlContractTest {

    private class FakeController : PlayerControllerHandle {
        var pauseFromFocusCount = 0
        var resumeCount = 0
        var unduckCount = 0
        var playCount = 0
        var pauseCount = 0
        var nextCount = 0
        var prevCount = 0
        var duckVolume: Float? = null
        var seekDelta = 0L

        override fun pauseFromFocus() { pauseFromFocusCount++ }
        override fun resumeFromFocus() { resumeCount++ }
        override fun duck(volume: Float) { duckVolume = volume }
        override fun unduck() { unduckCount++; duckVolume = 1f }
        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun next() { nextCount++ }
        override fun prev() { prevCount++ }
        override fun seekRelative(deltaMs: Long) { seekDelta = deltaMs }
    }

    private class FakeClock(var t: Long = 0L) {
        fun now(): Long = t
    }

    private fun track(title: String = "晴天") = Track(
        id = TrackId("t-1"), title = title, artist = "周杰伦", album = "叶惠美",
        durationMs = 269_000L,
        sourceRef = SourceRef("s-1", "music/a.flac"),
        sourceType = SourceType.LOCAL,
    )

    private fun media(clock: FakeClock = FakeClock()): Pair<InMemoryMediaControl, FakeController> {
        val control = InMemoryMediaControl(now = { clock.t })
        val controller = FakeController()
        control.bind(controller)
        return control to controller
    }

    @Test
    @DisplayName("03-07 §12 T01 LOSS_TRANSIENT → GAIN 自动恢复")
    fun t01() {
        val (control, c) = media()
        control.onFocusChange(FocusChange.LOSS_TRANSIENT)
        assertEquals(1, c.pauseFromFocusCount)
        assertTrue(control.isResumePending)
        control.onFocusChange(FocusChange.GAIN)
        assertEquals(1, c.resumeCount)
        assertEquals(FocusState.PLAYING, control.snapshot().focus)
        assertFalse(control.isResumePending)
    }

    @Test
    @DisplayName("03-07 §12 T02 永久 LOSS → GAIN 不自动恢复")
    fun t02() {
        val (control, c) = media()
        control.onFocusChange(FocusChange.LOSS)
        control.onFocusChange(FocusChange.GAIN)
        assertEquals(0, c.resumeCount)
        assertEquals(FocusState.IDLE, control.snapshot().focus)
    }

    @Test
    @DisplayName("03-07 §12 T03 手动暂停 → GAIN 不恢复")
    fun t03() {
        val (control, c) = media()
        control.onFocusChange(FocusChange.LOSS_TRANSIENT)
        control.onManualPause()
        control.onFocusChange(FocusChange.GAIN)
        assertEquals(0, c.resumeCount)
    }

    @Test
    @DisplayName("03-07 §12 T04 duck → unduck 音量回到 1.0")
    fun t04() {
        val (control, c) = media()
        control.onFocusChange(FocusChange.LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.25f, c.duckVolume)
        assertEquals(0.25f, control.snapshot().volume)
        assertEquals(FocusState.DUCKED, control.snapshot().focus)
        control.onFocusChange(FocusChange.GAIN)
        assertEquals(1f, c.duckVolume)
        assertEquals(1f, control.snapshot().volume)
        assertEquals(1, c.unduckCount)
    }

    @Test
    @DisplayName("03-07 §12 T05 按键双击判定（窗口内两次同键 UP → next）")
    fun t05() {
        val clock = FakeClock()
        val (control, c) = media(clock)
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.DOWN)
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.UP) // 第一次：单键 play()
        clock.t = 200
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.DOWN)
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.UP) // 第二次：双击 next()
        assertEquals(1, c.playCount)
        assertEquals(1, c.nextCount)

        val clock2 = FakeClock()
        val (control2, c2) = media(clock2)
        control2.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        clock2.t = 1_000 // 超出双击窗口：仍是两次独立的单键 NEXT
        control2.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        assertEquals(2, c2.nextCount)
    }

    @Test
    @DisplayName("03-07 §12 T06 setEnabled(false) 期间收到按键被忽略")
    fun t06() {
        val (control, c) = media()
        control.setEnabled(false)
        control.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        control.onMediaButton(MediaKey.PLAY, MediaAction.DOUBLE_CLICK)
        control.onFocusChange(FocusChange.LOSS)
        assertEquals(0, c.nextCount + c.playCount + c.pauseFromFocusCount)
        assertFalse(control.snapshot().enabled)
        control.setEnabled(true)
        control.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        assertEquals(1, c.nextCount)
    }

    @Test
    @DisplayName("03-07 §12 T07 空标题发布兜底文案")
    fun t07() = runTest {
        val (control, _) = media()
        control.publishMetadata(track(title = "   "), null)
        assertEquals(MediaMetadata.FALLBACK_TITLE, control.metadata().first()?.title)
        control.publishMetadata(track(), "/cover/a.webp")
        val meta = control.metadata().first()
        assertEquals("晴天", meta?.title)
        assertEquals("周杰伦", meta?.artist)
        assertEquals("/cover/a.webp", meta?.coverPath)
        assertEquals(269_000L, meta?.durationMs)
    }

    @Test
    @DisplayName("EA01 门面枚举与 03-07 §3 逐字对齐")
    fun ea01() {
        assertEquals(7, FocusChange.entries.size)
        assertEquals(8, MediaKey.entries.size)
        assertEquals(4, MediaAction.entries.size)
        assertEquals(
            listOf("DOWN", "UP", "LONG_PRESS", "DOUBLE_CLICK"),
            MediaAction.entries.map { it.name },
        )
    }

    @Test
    @DisplayName("EA02 bind 之前收到事件直接报错，避免静默丢弃")
    fun ea02() {
        val control = InMemoryMediaControl()
        assertFailsWith<IllegalStateException> { control.onFocusChange(FocusChange.GAIN) }
        assertFailsWith<IllegalStateException> {
            control.onMediaButton(MediaKey.PLAY, MediaAction.UP)
        }
        assertFalse(control.snapshot().bound)
    }

    @Test
    @DisplayName("EA03 单键分发表：PLAY/PAUSE/PLAY_PAUSE/NEXT/PREV/STOP/FF/REWIND")
    fun ea03() = runTest {
        val (control, c) = media()
        control.onMediaButton(MediaKey.PLAY, MediaAction.UP)
        assertEquals(1, c.playCount)
        control.onMediaButton(MediaKey.PAUSE, MediaAction.UP)
        assertEquals(1, c.pauseCount)
        control.setPlaybackState(EngineState.PLAYING, 1_000L, 200_000L)
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.UP)
        assertEquals(2, c.pauseCount) // PLAYING 时 PLAY_PAUSE → pause（累加前面的 PAUSE）
        control.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        control.onMediaButton(MediaKey.PREV, MediaAction.UP)
        assertEquals(1, c.nextCount)
        assertEquals(1, c.prevCount)
        control.onMediaButton(MediaKey.STOP, MediaAction.UP)
        assertEquals(3, c.pauseCount) // PLAY_PAUSE + PAUSE + STOP
        control.onMediaButton(MediaKey.FAST_FORWARD, MediaAction.UP)
        assertEquals(10_000L, c.seekDelta)
        control.onMediaButton(MediaKey.REWIND, MediaAction.UP)
        assertEquals(-10_000L, c.seekDelta)
    }

    @Test
    @DisplayName("EA04 照搬层显式传入的 DOUBLE_CLICK / LONG_PRESS")
    fun ea04() {
        val (control, c) = media()
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.DOUBLE_CLICK)
        assertEquals(1, c.nextCount)
        control.onMediaButton(MediaKey.PLAY_PAUSE, MediaAction.LONG_PRESS)
        assertEquals(10_000L, c.seekDelta)
    }

    @Test
    @DisplayName("EA05 setPlaybackState 经 Flow 可观测")
    fun ea05() = runTest {
        val (control, _) = media()
        assertNull(control.playback().first())
        control.setPlaybackState(EngineState.PAUSED, 12_345L, 269_000L)
        val snap = control.playback().first()
        assertEquals(EngineState.PAUSED, snap?.state)
        assertEquals(12_345L, snap?.positionMs)
        assertEquals(269_000L, snap?.durationMs)
    }

    @Test
    @DisplayName("EA06 release 后清空句柄 / 元数据并停用")
    fun ea06() = runTest {
        val (control, c) = media()
        control.publishMetadata(track(), null)
        control.setPlaybackState(EngineState.PLAYING, 0L, 269_000L)
        control.release()
        val snap = control.snapshot()
        assertFalse(snap.enabled)
        assertFalse(snap.bound)
        assertNull(snap.metadata)
        assertNull(snap.playback)
        control.onMediaButton(MediaKey.NEXT, MediaAction.UP)
        assertEquals(0, c.nextCount)
    }

    @Test
    @DisplayName("EA07 OFF 常量语义：不下发任何动作、状态不变")
    fun ea07() {
        val (control, c) = media()
        val before = control.snapshot().focus
        control.onFocusChange(FocusChange.OFF)
        assertEquals(before, control.snapshot().focus)
        assertEquals(0, c.pauseFromFocusCount + c.resumeCount + c.unduckCount)
    }

    @Test
    @DisplayName("EA08 切 DLNA（setEnabled(false)）清除焦点恢复标记")
    fun ea08() {
        val (control, c) = media()
        control.onFocusChange(FocusChange.LOSS_TRANSIENT)
        assertTrue(control.isResumePending)
        control.setEnabled(false)
        assertFalse(control.isResumePending)
        control.setEnabled(true)
        control.onFocusChange(FocusChange.GAIN)
        assertEquals(0, c.resumeCount)
    }
}
