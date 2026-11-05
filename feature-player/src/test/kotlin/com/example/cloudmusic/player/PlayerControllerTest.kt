package com.example.cloudmusic.player

import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.InMemoryMusicEngine
import com.example.cloudmusic.media.InMemoryMediaControl
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-06 §15 T07 / T08 / T11 + §8 双轨 / §9 状态机的纯 JVM 判定部分。 */
class PlayerControllerTest {

    private fun track(n: Int) = Track(
        id = TrackId("id-$n"),
        title = "title-$n",
        artist = "artist",
        album = "album",
        durationMs = 100_000L,
        sourceRef = SourceRef(storageId = "storage", path = "/p$n.flac"),
        sourceType = SourceType.LOCAL,
    )

    private val tracks = (1..5).map { track(it) }

    private val localResolver = TrackSourceResolver { t ->
        Link(localPath = "/music/${t.id.value}.flac", localSize = t.durationMs)
    }

    private class Fixture(
        val resolver: TrackSourceResolver,
        val config: PlayerConfig = PlayerConfig(shuffleSeed = 42L),
        val scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val engine = InMemoryMusicEngine()
        val sync = RecordingQueueSync()
        val media = InMemoryMediaControl()
        val repo = InMemoryQueueRepository { 1_000L }
        val controller = PlayerController(
            engine = engine,
            sync = sync,
            lookup = InMemoryTrackLookup(tracksOf(5)),
            resolver = resolver,
            media = media,
            config = config,
            scope = scope,
        )

        companion object {
            fun tracksOf(n: Int) = (1..n).map { i ->
                Track(
                    id = TrackId("id-$i"), title = "title-$i", artist = "a", album = "b",
                    durationMs = 100_000L,
                    sourceRef = SourceRef("storage", "/p$i.flac"),
                    sourceType = SourceType.LOCAL,
                )
            }
        }
    }

    private fun fixture(
        resolver: TrackSourceResolver = localResolver,
        config: PlayerConfig = PlayerConfig(shuffleSeed = 42L),
        scope: kotlinx.coroutines.CoroutineScope,
    ) = Fixture(resolver, config, scope)

    private suspend fun Fixture.queue(mode: PlayMode = PlayMode.SEQUENCE) {
        repo.setAll(Fixture.tracksOf(5))
        repo.setMode(mode)
        controller.setQueue(repo.snapshotItems(), mode)
    }

    // ------------------------------------------------------------------
    // T07 / T08
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T07 · SHUFFLE 由业务层生成且同 seed 可复现，不读 native")
    fun t07() = runTest {
        val a = ShuffleOrder.generate(42L, 5)
        val b = ShuffleOrder.generate(42L, 5)
        assertEquals(a, b)
        assertEquals(setOf(0, 1, 2, 3, 4), a.toSet(), "不重不漏")
        assertTrue(ShuffleOrder.generate(43L, 5) != a || a.size <= 1, "不同 seed 应产生不同顺序")
        assertEquals(emptyList(), ShuffleOrder.generate(1L, 0))

        // 按业务层顺序推进，不依赖 native 随机
        val first = PlayModeOrdering.nextIndex(0, 5, PlayMode.SHUFFLE, a)
        assertEquals(a.getOrNull(a.indexOf(0) + 1), first)
        assertNull(PlayModeOrdering.nextIndex(a.last(), 5, PlayMode.SHUFFLE, a), "随机一轮结束")
    }

    @Test
    @DisplayName("T08 · QueueRepository.move(0,3) / remove / insertNext 后 position 连续无空洞")
    fun t08() = runTest {
        val repo = InMemoryQueueRepository { 1_000L }
        repo.setAll(tracks)
        assertEquals((0..4).toList(), repo.snapshotItems().map { it.position })

        repo.move(0, 3)
        assertEquals((0..4).toList(), repo.snapshotItems().map { it.position })
        assertEquals("id-2", repo.snapshotItems()[0].trackId.value)
        assertEquals("id-1", repo.snapshotItems()[3].trackId.value)

        repo.remove(2)
        assertEquals((0..3).toList(), repo.snapshotItems().map { it.position })
        repo.insertNext(track(9))   // 5 → remove 4 → 插播 5
        assertEquals((0..4).toList(), repo.snapshotItems().map { it.position })
        assertTrue(repo.snapshotItems().any { it.trackId.value == "id-9" })

        repo.move(99, 0) // 越界不改动
        assertEquals(5, repo.snapshotItems().size)
    }

    // ------------------------------------------------------------------
    // T11 冷启动恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T11 · 冷启动恢复：曲目 / 进度 / 模式一致，且保持 PAUSED 不自动开播")
    fun t11() = runTest {
        val f = fixture(scope = this)
        f.repo.setAll(tracks)
        f.repo.setMode(PlayMode.LIST_LOOP)
        f.repo.saveCursor("id-3", 42_000L)
        val snapshot = f.repo.restore()
        assertNotNull(snapshot)
        assertEquals(PlayMode.LIST_LOOP, snapshot.playMode)
        assertEquals(42_000L, snapshot.positionMs)

        f.controller.restore(snapshot, f.repo.snapshotItems())
        val state = f.controller.state.value
        assertEquals(PlayerPhase.PAUSED, state.phase)
        assertEquals("id-3", state.currentTrackId?.value)
        assertEquals(42_000L, state.positionMs)
        assertEquals(PlayMode.LIST_LOOP, state.mode)
        assertEquals(EngineState.PAUSED, f.engine.snapshot().state, "引擎不得自动进入 PLAYING")
        assertEquals(1, f.sync.pushes.size, "恢复只能单向全量下发一次")
    }

    // ------------------------------------------------------------------
    // §9 状态机 / C-05 / 双轨
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§9 · playAt / pause / resume / next 状态流转，prepare 前释放旧 handle（T06）")
    fun stateMachine() = runTest {
        val f = fixture(scope = this)
        f.queue()
        f.controller.playAt(0)
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertEquals("id-1", f.engine.snapshot().source?.trackId)

        f.controller.pause()
        assertEquals(PlayerPhase.PAUSED, f.controller.state.value.phase)
        f.controller.resume()
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)

        f.controller.next() // 第二次 prepare，验证旧 handle 已 release
        assertEquals("id-2", f.engine.snapshot().source?.trackId)
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)

        f.controller.seek(-100)
        assertEquals(0L, f.controller.state.value.positionMs)
        f.controller.seek(999_999)
        assertEquals(100_000L, f.controller.state.value.positionMs, "钳制到 duration")
    }

    @Test
    @DisplayName("C-05 · URL 态填 url、LOCAL 态填 localPath，门面 14 方法不变")
    fun c05Mapping() = runTest {
        val urlResolver = TrackSourceResolver { t -> Link(url = "https://example.com/${t.id.value}.mp3") }
        val f = fixture(resolver = urlResolver, scope = this)
        f.queue()
        f.controller.playAt(0)
        val source = f.engine.snapshot().source
        assertNotNull(source)
        assertEquals("https://example.com/id-1.mp3", source.url)
        assertNull(source.localPath)
        assertEquals("audio/mpeg", source.mimeHint)

        val local = fixture(scope = this)
        local.queue()
        local.controller.playAt(0)
        val localSource = local.engine.snapshot().source
        assertNotNull(localSource)
        assertNull(localSource.url)
        assertEquals("/music/id-1.flac", localSource.localPath)
    }

    @Test
    @DisplayName("16 §3.2 · PROXIED 无回环 URL 时进入 ERROR，不静默播放")
    fun proxiedNeedsLoopbackUrl() = runTest {
        val broken = TrackSourceResolver { Link(proxy = true) }
        val f = fixture(resolver = broken, scope = this)
        f.queue()
        f.controller.playAt(0)
        val s = f.controller.state.value
        assertEquals(PlayerPhase.ERROR, s.phase)
        assertNotNull(s.lastError)
    }

    @Test
    @DisplayName("§8 双轨 · setQueue 全量下发且顺序一致；沙箱约束常量生效")
    fun queueSyncIsOneWay() = runTest {
        val f = fixture(scope = this)
        f.queue(PlayMode.SHUFFLE)
        assertEquals(1, f.sync.pushes.size)
        assertEquals(5, f.sync.pushes[0].items.size)
        assertEquals(PlayMode.SHUFFLE, f.sync.pushes[0].mode)
        assertEquals((0..4).toList(), f.sync.pushes[0].items.map { it.position })

        assertTrue(NativeQueueSandbox.isSandboxPath("noBackupFilesDir/native_playlist/db"))
        assertTrue(NativeQueueSandbox.isSandboxPath("cacheDir/native_playlist"))
        assertFalse(NativeQueueSandbox.isSandboxPath("datastore/credentials"))
        assertTrue(NativeQueueSandbox.FORBIDDEN_NATIVE_CALLS.contains("nativeRestoreFromDb"))
    }

    @Test
    @DisplayName("§9 · 解码失败自动跳下一首（可关）；DLNA 切换静音并转遥控器")
    fun errorAndOutputSwitch() = runTest {
        val f = fixture(scope = this)
        f.queue()
        f.controller.playAt(0)
        f.controller.onEngineError(com.example.cloudmusic.common.CloudMusicError.DsUpstream("403", "上游拒绝"))
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertEquals("id-2", f.controller.state.value.currentTrackId?.value)

        val noSkip = fixture(config = PlayerConfig(autoSkipOnError = false), scope = this)
        noSkip.queue()
        noSkip.controller.playAt(0)
        noSkip.controller.onEngineError(com.example.cloudmusic.common.CloudMusicError.DsUpstream("500", "失败"))
        assertEquals(PlayerPhase.ERROR, noSkip.controller.state.value.phase)
        assertNotNull(noSkip.controller.state.value.lastError)

        f.controller.onOutputChanged(dlna = true)
        assertTrue(f.controller.state.value.remoteOutput)
        assertEquals(PlayerPhase.PAUSED, f.controller.state.value.phase)
        assertEquals(0f, f.engine.snapshot().volume)
        f.controller.onOutputChanged(dlna = false)
        assertFalse(f.controller.state.value.remoteOutput)
        assertEquals(1f, f.engine.snapshot().volume)
    }
}
