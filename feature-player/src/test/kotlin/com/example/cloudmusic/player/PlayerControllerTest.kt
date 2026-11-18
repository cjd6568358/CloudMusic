package com.example.cloudmusic.player

import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.common.NetworkPlaybackPolicy
import com.example.cloudmusic.engine.api.EngineHandle
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.engine.api.InMemoryMusicEngine
import com.example.cloudmusic.media.InMemoryMediaControl
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
        networkGate: NetworkPlaybackGate = NetworkPlaybackPolicy.ALLOW_ALL,
        /** §61.181：non-null 时模拟 B2「READY 解出真值时长」（与曲库元数据无关）。 */
        engineDurationOverride: Long? = null,
        val lookupTracks: List<Track> = tracksOf(5),
        durationWriter: TrackDurationWriter? = null,
        cursorWriter: CursorWriter? = null,
        /** §61.224：节拍器交棒测试——positionMs 劫持值。 */
        positionOverride: Long? = null,
    ) {
        val engine = InMemoryMusicEngine()

        /** §61.181：可变真值（模拟异步解出时机）；snapshot 断言仍走 [engine] 本体。 */
        val durationEngine: DurationResolvingEngine? =
            engineDurationOverride?.let { DurationResolvingEngine(engine, it) }
        /** §61.224：positionMs 可控委托（节拍器交棒测试用）。-1 = 走真实 position。 */
        val positionEngine: PositionControllableEngine? =
            positionOverride?.let { PositionControllableEngine(engine, it) }
        private val engineFacade: IMusicEngine = positionEngine ?: durationEngine ?: engine
        val sync = RecordingQueueSync()
        val media = InMemoryMediaControl()
        val repo = InMemoryQueueRepository { 1_000L }
        val controller = PlayerController(
            engine = engineFacade,
            sync = sync,
            lookup = InMemoryTrackLookup(lookupTracks),
            resolver = resolver,
            media = media,
            config = config,
            networkGate = networkGate,
            durationWriter = durationWriter,
            cursorWriter = cursorWriter,
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

    /**
     * §61.181：仅覆写 `durationMs` 的委托引擎——InMemoryMusicEngine 的时长来自
     * `expectedLength`（= 曲库元数据），无法模拟 B2 在 READY 时解出与元数据无关的
     * 真值（STRM 直链 244933 的核心场景）。
     */
    /** §61.224：仅覆写 positionMs 的委托引擎（节拍器交棒测试）。 */
    private class PositionControllableEngine(
        private val real: InMemoryMusicEngine,
        @Volatile var fakePosition: Long,
    ) : IMusicEngine by real {
        override fun positionMs(handle: EngineHandle): Long =
            if (fakePosition >= 0) fakePosition else real.positionMs(handle)
    }

    private class DurationResolvingEngine(
        private val real: InMemoryMusicEngine,
        @Volatile var resolvedDuration: Long,
    ) : IMusicEngine by real {
        override fun durationMs(handle: EngineHandle): Long = resolvedDuration
    }

    /** §61.181：STRM 曲目——曲库时长缺值（0），真实时长只能由引擎解出。 */
    private fun strmTrack(n: Int) = Track(
        id = TrackId("id-$n"),
        title = "strm-$n",
        artist = "artist",
        album = "album",
        durationMs = 0L,
        sourceRef = SourceRef(
            storageId = "storage", path = "/p$n.strm", url = "https://example.com/s$n.mp3",
        ),
        sourceType = SourceType.STRM,
    )

    private fun fixture(
        resolver: TrackSourceResolver = localResolver,
        config: PlayerConfig = PlayerConfig(shuffleSeed = 42L),
        scope: kotlinx.coroutines.CoroutineScope,
        networkGate: NetworkPlaybackGate = NetworkPlaybackPolicy.ALLOW_ALL,
        engineDurationOverride: Long? = null,
        lookupTracks: List<Track> = Fixture.tracksOf(5),
        durationWriter: TrackDurationWriter? = null,
        cursorWriter: CursorWriter? = null,
        positionOverride: Long? = null,
    ) = Fixture(resolver, config, scope, networkGate, engineDurationOverride, lookupTracks, durationWriter, cursorWriter, positionOverride)

    private val urlResolver = TrackSourceResolver { t ->
        Link(url = "https://example.com/${t.id.value}.mp3")
    }

    /** 模拟「仅 WiFi + 当前非 WiFi」：只对网络音源拒绝。 */
    private val blockingGate = NetworkPlaybackGate { networkSource ->
        if (networkSource) "当前为移动网络，已开启「仅 WiFi 播放」" else null
    }

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
        val f = fixture(scope = backgroundScope)
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

    @Test
    @DisplayName("§61.27 · 冷启动播放指令先于 Room.restore 到达时不丢失")
    fun pendingPlayBeforeRestore() = runTest {
        val f = fixture(scope = backgroundScope)
        f.repo.setAll(tracks)
        f.repo.setMode(PlayMode.LIST_LOOP)
        f.repo.saveCursor("id-3", 42_000L)
        val snapshot = f.repo.restore()
        assertNotNull(snapshot)

        // 播放指令先到：队列尚未恢复，不得假播、也不得丢指令
        f.controller.resume()
        assertEquals(PlayerPhase.IDLE, f.controller.state.value.phase)
        assertEquals(EngineState.IDLE, f.engine.snapshot().state)

        // restore 完成后兑现：只一次 play，状态与引擎都进 PLAYING
        f.controller.restore(snapshot, f.repo.snapshotItems())
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertEquals(EngineState.PLAYING, f.engine.snapshot().state)
        assertEquals("id-3", f.engine.snapshot().source?.trackId)
    }

    // ------------------------------------------------------------------
    // §61.28 · 03-02 §344「播放前由 03-06 检查」的默认仅 WiFi 闸门
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.28 · 非 WiFi 下网络音源被拒：不 prepare、明确错误、不自动跳曲")
    fun wifiOnlyBlocksNetworkSource() = runTest {
        val f = fixture(resolver = urlResolver, scope = backgroundScope, networkGate = blockingGate)
        f.queue()
        f.controller.playAt(0)
        val state = f.controller.state.value
        assertEquals(PlayerPhase.ERROR, state.phase)
        assertEquals("E_DS_UPSTREAM_WIFI_ONLY", state.lastError?.code)
        assertEquals(0, state.currentIndex, "用户策略拒绝不是音源故障，不逐首跳曲")
        assertNull(f.engine.snapshot().source, "被拒时不得 prepare / 占用引擎句柄")
    }

    @Test
    @DisplayName("§61.28 · 03-11 §6 B05：本地音源一律放行")
    fun wifiOnlyNeverBlocksLocalSource() = runTest {
        val f = fixture(scope = backgroundScope, networkGate = blockingGate)
        f.queue()
        f.controller.playAt(0)
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertNotNull(f.engine.snapshot().source)
        assertNull(f.engine.snapshot().source?.url)
    }

    @Test
    @DisplayName("§61.28 · restore 只 prepare 不被拦，用户点播 resume 才被拦")
    fun wifiOnlyBlocksResumeNotRestore() = runTest {
        val f = fixture(resolver = urlResolver, scope = backgroundScope, networkGate = blockingGate)
        f.repo.setAll(tracks)
        f.repo.setMode(PlayMode.LIST_LOOP)
        f.repo.saveCursor("id-3", 42_000L)
        val snapshot = f.repo.restore()
        assertNotNull(snapshot)

        f.controller.restore(snapshot, f.repo.snapshotItems())
        assertEquals(PlayerPhase.PAUSED, f.controller.state.value.phase)
        assertNotNull(f.engine.snapshot().source, "恢复阶段只 prepare，不受闸门影响")

        f.controller.resume()
        assertEquals(PlayerPhase.ERROR, f.controller.state.value.phase)
        assertEquals(EngineState.PAUSED, f.engine.snapshot().state, "不得假播，引擎仍暂停")
    }

    @Test
    @DisplayName("§61.28 · WiFi / 放行态下网络音源正常 prepare 并播放")
    fun wifiOnlyAllowsWifiNetworkSource() = runTest {
        val f = fixture(resolver = urlResolver, scope = backgroundScope)
        f.queue()
        f.controller.playAt(0)
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertEquals("https://example.com/id-1.mp3", f.engine.snapshot().source?.url)
    }

    // ------------------------------------------------------------------
    // §9 状态机 / C-05 / 双轨
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§9 · playAt / pause / resume / next 状态流转，prepare 前释放旧 handle（T06）")
    fun stateMachine() = runTest {
        val f = fixture(scope = backgroundScope)
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
        val f = fixture(resolver = urlResolver, scope = backgroundScope)
        f.queue()
        f.controller.playAt(0)
        val source = f.engine.snapshot().source
        assertNotNull(source)
        assertEquals("https://example.com/id-1.mp3", source.url)
        assertNull(source.localPath)
        assertEquals("audio/mpeg", source.mimeHint)

        val local = fixture(scope = backgroundScope)
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
        val f = fixture(resolver = broken, scope = backgroundScope)
        f.queue()
        f.controller.playAt(0)
        val s = f.controller.state.value
        assertEquals(PlayerPhase.ERROR, s.phase)
        assertNotNull(s.lastError)
    }

    @Test
    @DisplayName("§8 双轨 · setQueue 全量下发且顺序一致；沙箱约束常量生效")
    fun queueSyncIsOneWay() = runTest {
        val f = fixture(scope = backgroundScope)
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
        val f = fixture(scope = backgroundScope)
        f.queue()
        f.controller.playAt(0)
        f.controller.onEngineError(com.example.cloudmusic.common.CloudMusicError.DsUpstream("403", "上游拒绝"))
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        assertEquals("id-2", f.controller.state.value.currentTrackId?.value)

        val noSkip = fixture(config = PlayerConfig(autoSkipOnError = false), scope = backgroundScope)
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

    // ------------------------------------------------------------------
    // §61.139 · gapless 播放链接线（门面 prepareQueue → PlayerController）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.139 · 顺序本地队列 ≥2 走 prepareQueue，next 不重建 handle")
    fun gaplessQueueUsesPlaylist() = runTest {
        val f = fixture(scope = backgroundScope)
        f.queue()
        f.controller.playAt(1)
        assertEquals(5, f.engine.queuedSources?.size, "整队列一次性下发")
        assertEquals(1, f.engine.queuedStartIndex)
        assertEquals(1, f.engine.queueIndex().value)
        assertEquals("id-2", f.controller.state.value.currentTrackId?.value)

        val handleBefore = f.engine.snapshot().handleRaw
        f.controller.next()
        assertEquals("id-3", f.controller.state.value.currentTrackId?.value)
        assertEquals("id-3", f.engine.snapshot().source?.trackId, "队列内跳播同步引擎当前曲目")
        assertEquals(2, f.engine.queueIndex().value)
        assertEquals(handleBefore, f.engine.snapshot().handleRaw, "不得 release + 重新 prepare")
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
    }

    @Test
    @DisplayName("§61.139 · 网络音源 / 随机模式 / 带位置恢复均保持单曲路径")
    fun gaplessKeepsSingleTrackPath() = runTest {
        val remote = fixture(resolver = urlResolver, scope = backgroundScope)
        remote.queue()
        remote.controller.playAt(0)
        assertEquals(-1, remote.engine.queueIndex().value, "网络队列逐曲过网络闸门")

        val shuffled = fixture(scope = backgroundScope)
        shuffled.queue(PlayMode.SHUFFLE)
        shuffled.controller.playAt(0)
        assertEquals(-1, shuffled.engine.queueIndex().value, "业务 SHUFFLE 顺序不能交给线性 playlist")

        val restoring = fixture(scope = backgroundScope)
        restoring.repo.setAll(tracks)
        restoring.repo.setMode(PlayMode.SEQUENCE)
        restoring.repo.saveCursor("id-2", 30_000L)
        val snapshot = restoring.repo.restore()
        assertNotNull(snapshot)
        restoring.controller.restore(snapshot, restoring.repo.snapshotItems())
        assertEquals(-1, restoring.engine.queueIndex().value, "恢复带位置且保持 PAUSED")
    }

    @Test
    @DisplayName("§61.139 · 引擎侧自动切曲（queueIndex 变化）同步业务游标")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun gaplessQueueIndexSync() = runTest {
        val f = fixture(scope = backgroundScope)
        f.queue()
        f.controller.playAt(0)
        val h = EngineHandle(requireNotNull(f.engine.snapshot().handleRaw))
        f.engine.seekToQueueIndex(h, 3)
        runCurrent()
        assertEquals(3, f.controller.state.value.currentIndex)
        assertEquals("id-4", f.controller.state.value.currentTrackId?.value)
        assertEquals(0L, f.controller.state.value.positionMs)
    }

    // ------------------------------------------------------------------
    // §61.181 · 引擎真值时长通道（A 案）+ 曲库回填（B 案）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.181 A案 · STRM 曲库时长 0：引擎真值写入 PlayerState 且进媒体会话")
    fun strmDurationFromEngine() = runTest {
        val f = fixture(
            scope = backgroundScope,
            resolver = urlResolver,
            engineDurationOverride = 244_933L,   // 真机一手：READY 时解出 4'04"
            lookupTracks = listOf(strmTrack(1)),
        )
        f.repo.setAll(listOf(strmTrack(1)))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        val s = f.controller.state.value
        assertEquals(PlayerPhase.PLAYING, s.phase)
        assertEquals(244_933L, s.durationMs, "引擎解出的真值，非曲库 0")
        assertEquals(244_933L, f.media.snapshot().playback?.durationMs, "媒体会话与 state 同源")
        // §61.185：通知栏 metadata 时长同源（publishMetadata 用引擎真值副本，非曲库 0）
        assertEquals(244_933L, f.media.snapshot().metadata?.durationMs, "通知栏元数据真值")
    }

    @Test
    @DisplayName("§61.181 A案 · 切曲即清零：上一曲真值不残留到下一曲未解出期")
    fun noDurationResidueAcrossTracks() = runTest {
        val f = fixture(
            scope = backgroundScope,
            resolver = urlResolver,
            engineDurationOverride = 100_000L,   // 第一曲引擎已解出
            lookupTracks = listOf(track(1), strmTrack(2)),
        )
        f.repo.setAll(listOf(track(1), strmTrack(2)))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        assertEquals(100_000L, f.controller.state.value.durationMs)

        // 下一曲引擎尚未解出（真机 B2 READY 异步）→ 恒 0，不得残留 100_000
        f.durationEngine!!.resolvedDuration = 0L
        f.controller.playAt(1)
        assertEquals(0L, f.controller.state.value.durationMs)
    }

    @Test
    @DisplayName("§61.181 B案 · 曲库缺时长：引擎解出真值后回填一次；已有元数据不回填")
    fun backfillsLibraryDuration() = runTest {
        val writes = mutableListOf<Pair<TrackId, Long>>()
        val f = fixture(
            scope = backgroundScope,
            resolver = urlResolver,
            engineDurationOverride = 244_933L,
            lookupTracks = listOf(strmTrack(1)),
            durationWriter = TrackDurationWriter { id, d -> writes.add(id to d) },
        )
        f.repo.setAll(listOf(strmTrack(1)))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        assertEquals(listOf(TrackId("id-1") to 244_933L), writes, "解出真值即回填")

        writes.clear()
        val local = track(2)
        val f2 = fixture(
            scope = backgroundScope,
            engineDurationOverride = 244_933L,
            lookupTracks = listOf(local),
            durationWriter = TrackDurationWriter { id, d -> writes.add(id to d) },
        )
        f2.repo.setAll(listOf(local))
        f2.controller.setQueue(f2.repo.snapshotItems(), PlayMode.SEQUENCE)
        f2.controller.playAt(0)
        assertEquals(emptyList(), writes, "曲库已有时长（100_000）不覆盖")
    }

    @Test
    @DisplayName("§61.181 A案 · 引擎 READY 异步解出：节拍器采集真值并兑现回填")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun tickerPicksUpLateResolvedDuration() = runTest {
        val writes = mutableListOf<Pair<TrackId, Long>>()
        val f = fixture(
            scope = backgroundScope,
            resolver = urlResolver,
            engineDurationOverride = 0L,          // prepare 返回时未解出（真机 B2 时序）
            lookupTracks = listOf(strmTrack(1)),
            durationWriter = TrackDurationWriter { id, d -> writes.add(id to d) },
        )
        f.repo.setAll(listOf(strmTrack(1)))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        runCurrent()                              // 节拍器第一 tick：仍未知
        assertEquals(0L, f.controller.state.value.durationMs)

        f.durationEngine!!.resolvedDuration = 244_933L   // READY：引擎解出真值
        advanceTimeBy(500)
        runCurrent()                              // 下一 tick 采集
        assertEquals(244_933L, f.controller.state.value.durationMs)
        assertEquals(listOf(TrackId("id-1") to 244_933L), writes, "节拍器兑现 B 案回填")
    }

    // ------------------------------------------------------------------
    // §61.182 · 播放游标写侧复活（saveCursor 此前生产链零写方 → 恢复链死路）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.182 · 切歌 / 暂停 / PLAYING 每 5s 三口落 playback_state")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cursorPersistsOnPreparePauseAndTick() = runTest {
        val cursors = mutableListOf<Pair<String, Long>>()
        val f = fixture(
            scope = backgroundScope,
            cursorWriter = CursorWriter { id, pos -> cursors.add(id to pos) },
        )
        f.queue()
        f.controller.playAt(0)
        runCurrent()   // 节拍器第一 tick（tick=0 即写）
        assertTrue(
            cursors.contains("id-1" to 0L),
            "prepareAt 落游标（trackId + seekTo）——写侧不得再断",
        )

        // PLAYING 期节奏：5s（10 tick）一写，必须在 pause 前验证（PAUSED 后 ticker 自行终止）
        val atPrepare = cursors.size
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(cursors.size > atPrepare, "PLAYING 期每 5s（10 tick）落游标")

        val atTick = cursors.size
        f.controller.pause()
        runCurrent()
        assertTrue(cursors.size > atTick, "暂停点落游标")
        assertTrue(cursors.all { it.first == "id-1" }, "游标曲目与当前曲一致")
    }

    @Test
    @DisplayName("§61.182 · restore（PAUSED）同样落游标，重启后 Room 才有恢复输入")
    fun cursorPersistsOnRestorePrepare() = runTest {
        val cursors = mutableListOf<Pair<String, Long>>()
        val f = fixture(
            scope = backgroundScope,
            cursorWriter = CursorWriter { id, pos -> cursors.add(id to pos) },
        )
        f.repo.setAll(tracks)
        f.repo.setMode(PlayMode.LIST_LOOP)
        f.repo.saveCursor("id-3", 42_000L)
        val snapshot = f.repo.restore()
        assertNotNull(snapshot)
        f.controller.restore(snapshot, f.repo.snapshotItems())
        assertEquals(PlayerPhase.PAUSED, f.controller.state.value.phase)
        assertTrue(
            cursors.contains("id-3" to 42_000L),
            "restore 的 prepareAt 落游标（同值幂等回写）",
        )
    }

    @Test
    @DisplayName("§61.182 · ENDED 后 resume 从头重播（B2 ENDED 不释放句柄、play() 不重播）")
    fun resumeAfterEndedRestartsFromStart() = runTest {
        val f = fixture(scope = backgroundScope)
        f.queue()
        f.controller.playAt(4)   // 队尾：ENDED 后 SEQUENCE 无下一首 → 相位 ENDED
        f.engine.reportEnded()   // 引擎自然播完
        runCurrent()
        assertEquals(PlayerPhase.ENDED, f.controller.state.value.phase)

        // 旧路径缺陷：handle 仍在 + ExoPlayer ENDED 下 play() 空转 → 假 PLAYING 无声
        f.controller.resume()
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        // playAt(4) 本地顺序队列 ≥2 → gapless 路径（prepareQueue 整队列重建，
        // 内存参考实现的 source 恒为 sources.first()，业务游标看 queuedStartIndex）
        assertEquals(5, f.engine.queuedSources?.size, "整队列重建")
        assertEquals(4, f.engine.queuedStartIndex, "从当前曲（队尾）重播")
        assertEquals(4, f.engine.queueIndex().value)
        assertEquals(EngineState.PLAYING, f.engine.snapshot().state)
        assertEquals(0L, f.engine.snapshot().positionMs, "位置归零重播，不留在曲尾")
    }

    // ------------------------------------------------------------------
    // §61.224：CUE 分轨播放包络——起始定位 / 恢复钳制 / 终点交棒 / seek 限界 / gapless 禁用
    // ------------------------------------------------------------------

    /** 分轨曲目夹具（§61.221 落库口径：cueStartMs/cueEndMs 非零）。 */
    private fun cueTrack(n: Int, startMs: Long, endMs: Long) = track(n).copy(
        cueStartMs = startMs,
        cueEndMs = endMs,
    )

    @Test
    @DisplayName("§61.224 · 分轨新起播定位到 cueStartMs")
    fun cueStartSeek() = runTest {
        val t = cueTrack(1, 30_000L, 60_000L)
        val f = fixture(scope = backgroundScope, lookupTracks = listOf(t))
        f.repo.setAll(listOf(t))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        assertEquals(30_000L, f.engine.snapshot().positionMs, "新起播落到分轨起点")
        assertEquals(30_000L, f.controller.state.value.positionMs)
    }

    @Test
    @DisplayName("§61.224 · 恢复点在包络外 → 回本轨起点（保持 PAUSED 不自动开播）")
    fun cueRestoreClamp() = runTest {
        val t = cueTrack(1, 30_000L, 60_000L)
        val f = fixture(scope = backgroundScope, lookupTracks = listOf(t))
        f.repo.setAll(listOf(t))
        f.repo.saveCursor(t.id.value, 5_000L)
        f.controller.restore(f.repo.restore()!!, f.repo.snapshotItems())
        assertEquals(PlayerPhase.PAUSED, f.controller.state.value.phase)
        assertEquals(30_000L, f.engine.snapshot().positionMs, "包络前恢复点回本轨起点")
    }

    @Test
    @DisplayName("§61.224 · 节拍器交棒：position ≥ cueEndMs 自动 next（误差 ≤ 1 tick）")
    fun cueEndAdvance() = runTest {
        val t = cueTrack(1, 0L, 60_000L)
        val t2 = track(2)
        val f = fixture(scope = backgroundScope, lookupTracks = listOf(t, t2), positionOverride = 60_500L)
        f.repo.setAll(listOf(t, t2))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        assertEquals(PlayerPhase.PLAYING, f.controller.state.value.phase)
        // 队列 2 首、本地音源，但队列含分轨 → 必须禁用 gapless（单曲 prepare 路径）
        assertNull(f.engine.queuedSources, "队列含分轨必须禁用 gapless")
        // 引擎 position ≥ cueEndMs（60_500）→ 节拍器下一 tick 交棒 next()
        advanceTimeBy(500)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, f.controller.state.value.currentIndex, "终点交棒进下一曲")
        assertEquals("id-2", f.controller.state.value.currentTrackId?.value)
    }

    @Test
    @DisplayName("§61.224 · seek 钳制进包络：包络后回本轨起点")
    fun cueSeekClamp() = runTest {
        val t = cueTrack(1, 30_000L, 60_000L)
        val f = fixture(scope = backgroundScope, lookupTracks = listOf(t))
        f.repo.setAll(listOf(t))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        f.controller.seek(99_000L)
        assertEquals(30_000L, f.engine.snapshot().positionMs, "包络后目标回本轨起点")
        f.controller.seek(45_000L)
        assertEquals(45_000L, f.engine.snapshot().positionMs, "包络内原样")
    }

    @Test
    @DisplayName("§61.224 · 整轨曲目零行为变化（无分轨时 seek/起播语义不变）")
    fun wholeTrackNoChange() = runTest {
        val t = track(1)
        val f = fixture(scope = backgroundScope, lookupTracks = listOf(t))
        f.repo.setAll(listOf(t))
        f.controller.setQueue(f.repo.snapshotItems(), PlayMode.SEQUENCE)
        f.controller.playAt(0)
        assertEquals(0L, f.engine.snapshot().positionMs, "整轨从 0 起播")
        f.controller.seek(42_000L)
        assertEquals(42_000L, f.engine.snapshot().positionMs, "整轨 seek 不受包络影响")
    }

    @Test
    @DisplayName("§61.230 · fadeCurve 线性包络：两端 0/1、中点半、越界钳制")
    fun fadeCurveLinear() = runTest {
        val f = fixture(scope = backgroundScope)
        assertEquals(0f, f.controller.fadeCurve(0f))
        assertEquals(1f, f.controller.fadeCurve(1f))
        assertEquals(0.5f, f.controller.fadeCurve(0.5f))
        assertEquals(0f, f.controller.fadeCurve(-0.5f), "越界钳到 0")
        assertEquals(1f, f.controller.fadeCurve(1.5f), "越界钳到 1")
    }
}