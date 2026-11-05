package com.example.cloudmusic.engine.api

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.IDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-06 §4 门面契约 + §15 T06 / T09 的纯 JVM 判定部分。 */
class EngineApiContractTest {

    private class FakeDataSource : IDataSource {
        var closed = false
        var openedAt = -1L
        override fun open(offset: Long): Long { openedAt = offset; return 4096L }
        override fun readAt(offset: Long, buf: ByteArray, len: Int): Int = 0
        override fun close() { closed = true }
        override fun mimeHint(): String = "audio/flac"
    }

    private fun source(length: Long = 240_000L) =
        EngineSource(trackId = "t-1", mimeHint = "audio/flac", expectedLength = length)

    @Test
    @DisplayName("EA01 EngineHandle 是 value class，raw 往返与相等性一致")
    fun ea01() {
        val h = EngineHandle(42L)
        assertEquals(42L, h.raw)
        assertEquals(h, EngineHandle(42L))
        assertNotEquals(h, EngineHandle(43L))
    }

    @Test
    @DisplayName("EA02 EngineState 六态齐全，初始态为 IDLE")
    fun ea02() = runTest {
        assertEquals(
            listOf("IDLE", "PREPARING", "PLAYING", "PAUSED", "ENDED", "ERROR"),
            EngineState.entries.map { it.name },
        )
        assertEquals(EngineState.IDLE, InMemoryMusicEngine().state().first())
    }

    @Test
    @DisplayName("EA03 EngineSource / EngineConfig 默认值符合 03-06 §4")
    fun ea03() {
        val s = EngineSource("t", "audio/mp3")
        assertEquals(-1L, s.expectedLength)
        val c = EngineConfig()
        assertEquals(0, c.sampleRateHz)
        assertTrue(c.preferHardwareDecoder)
        assertEquals(2000, c.bufferMs)
        assertTrue(c.autoSkipOnError)
    }

    @Test
    @DisplayName("03-06 §15 T06 两次 prepare 只允许一个有效 handle；release 后可再 prepare")
    fun t06() {
        val engine = InMemoryMusicEngine()
        val h1 = engine.prepare(source(), EngineConfig())
        assertFailsWith<IllegalStateException> { engine.prepare(source(), EngineConfig()) }
        assertEquals(h1.raw, engine.snapshot().handleRaw)
        engine.release(h1)
        val h2 = engine.prepare(source(), EngineConfig())
        assertNotEquals(h1.raw, h2.raw)
        assertEquals(h2.raw, engine.snapshot().handleRaw)
    }

    @Test
    @DisplayName("03-06 §15 T09 seek 钳制到 [0, duration]；未知长度不设上界")
    fun t09() {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(240_000L), EngineConfig())
        engine.seek(h, -100L)
        assertEquals(0L, engine.positionMs(h))
        engine.seek(h, 300_000L)
        assertEquals(240_000L, engine.positionMs(h))
        engine.seek(h, 12_345L)
        assertEquals(12_345L, engine.positionMs(h))
        engine.release(h)

        val h2 = engine.prepare(source(-1L), EngineConfig())
        engine.seek(h2, 999_999L)
        assertEquals(999_999L, engine.positionMs(h2))
        assertEquals(-1L, engine.durationMs(h2))
    }

    @Test
    @DisplayName("EA04 完整生命周期：state Flow 按 PREPARING→PLAYING→PAUSED→IDLE 推送")
    fun ea04() = runTest {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(), EngineConfig())
        assertEquals(EngineState.PREPARING, engine.state().first())
        engine.play(h)
        assertEquals(EngineState.PLAYING, engine.state().first())
        engine.pause(h)
        assertEquals(EngineState.PAUSED, engine.state().first())
        engine.play(h)
        engine.stop(h)
        assertEquals(EngineState.IDLE, engine.state().first())
        assertEquals(0L, engine.positionMs(h))
        engine.release(h)
        assertNull(engine.snapshot().handleRaw)
    }

    @Test
    @DisplayName("EA05 attachDataSource 后 release 必须关闭 IDataSource（03-06 §4）")
    fun ea05() {
        val engine = InMemoryMusicEngine()
        val ds = FakeDataSource()
        val h = engine.prepare(source(), EngineConfig())
        assertFalse(engine.snapshot().dataSourceAttached)
        engine.attachDataSource(h, ds)
        assertTrue(engine.snapshot().dataSourceAttached)
        assertEquals("audio/flac", ds.mimeHint())
        engine.release(h)
        assertTrue(ds.closed)
        assertFalse(engine.snapshot().dataSourceAttached)
    }

    @Test
    @DisplayName("EA06 setVolume 只接受 0f..1f")
    fun ea06() {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(), EngineConfig())
        engine.setVolume(h, 0f)
        assertEquals(0f, engine.snapshot().volume)
        engine.setVolume(h, 0.5f)
        assertEquals(0.5f, engine.snapshot().volume)
        assertFailsWith<IllegalArgumentException> { engine.setVolume(h, -0.1f) }
        assertFailsWith<IllegalArgumentException> { engine.setVolume(h, 1.1f) }
    }

    @Test
    @DisplayName("EA07 引擎错误 / 自然结束可经 state 与 lastError 观测")
    fun ea07() = runTest {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(), EngineConfig())
        val err = CloudMusicError.DsUpstream("403", "上游拒绝")
        engine.reportError(err)
        assertEquals(EngineState.ERROR, engine.state().first())
        assertEquals(err, engine.lastError())
        engine.reportEnded()
        assertEquals(EngineState.ENDED, engine.state().first())
        engine.release(h)
        assertNull(engine.lastError())
    }

    @Test
    @DisplayName("EA08 release 后旧 handle 的任何操作都被拒绝")
    fun ea08() {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(), EngineConfig())
        engine.release(h)
        assertFailsWith<IllegalStateException> { engine.play(h) }
        assertFailsWith<IllegalStateException> { engine.positionMs(h) }
        assertFailsWith<IllegalStateException> { engine.attachDataSource(h, FakeDataSource()) }
        assertFailsWith<IllegalStateException> { engine.release(h) }
    }

    @Test
    @DisplayName("EA09 snapshot 完整反映 source / config / duration / volume")
    fun ea09() {
        val engine = InMemoryMusicEngine()
        val config = EngineConfig(sampleRateHz = 96_000, preferHardwareDecoder = false,
            bufferMs = 800, autoSkipOnError = false)
        val h = engine.prepare(source(180_000L), config)
        val snap = engine.snapshot()
        assertEquals("t-1", snap.source?.trackId)
        assertEquals(config, snap.config)
        assertEquals(180_000L, snap.durationMs)
        assertEquals(1f, snap.volume)
        assertEquals(EngineState.PREPARING, snap.state)
        assertNull(snap.lastError)
        engine.release(h)
    }

    @Test
    @DisplayName("EA10 setQuality / attachAudioEffect 为透传，不校验有效 handle 之外的语义")
    fun ea10() {
        val engine = InMemoryMusicEngine()
        val h = engine.prepare(source(), EngineConfig())
        engine.setQuality(h, 3)
        engine.attachAudioEffect(h, 7)
        assertEquals(EngineState.PREPARING, engine.snapshot().state)
        engine.release(h)
    }
}
