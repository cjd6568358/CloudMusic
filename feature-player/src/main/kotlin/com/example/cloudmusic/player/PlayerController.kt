package com.example.cloudmusic.player

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.common.NetworkPlaybackPolicy
import com.example.cloudmusic.engine.api.EngineConfig
import com.example.cloudmusic.engine.api.EngineHandle
import com.example.cloudmusic.engine.api.EngineSource
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.media.PlayerControllerHandle
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * docs/03-06 §9 状态机 + §8 队列双轨的**唯一业务编排者**。
 *
 * - UI **不得**直接调 [IMusicEngine]；只观察 [state]，动作经 [asHandle] / suspend API。
 * - 每次队列变更由 [QueueSync] **单向全量下发**；禁止反向读 native 队列回写业务态（§8 / `13 §4`）。
 * - C-05 的直接消费方：按 [Link.kind] 把 url / localPath / 回环代理 URL 填进 [EngineSource]，
 *   引擎舱经 `DataProvider` 自取，不消费 Java 字节流（`16 §3.2`）。
 * - prepare 新曲目必须先 [IMusicEngine.release] 旧 handle（§15 T06，内存实现会 check）。
 */
class PlayerController(
    private val engine: IMusicEngine,
    private val sync: QueueSync,
    private val lookup: TrackLookup,
    private val resolver: TrackSourceResolver,
    private val media: IMediaControl? = null,
    private val config: PlayerConfig = PlayerConfig(),
    /** 03-02 §344：播放前由 03-06 检查「默认仅 WiFi 播放」；纯 JVM 端口，生产实现由 :core-di 注入。 */
    private val networkGate: NetworkPlaybackGate = NetworkPlaybackPolicy.ALLOW_ALL,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var queue: List<QueueItem> = emptyList()
    private var shuffle: List<Int> = emptyList()
    private var handle: EngineHandle? = null

    /** 最近一次 prepare 的曲目是否为网络音源；供 PlaybackService 同步判定避免会话假报 PLAYING。 */
    @Volatile
    private var currentNetworkSource = false

    /**
     * §61.27（G08 真机实证 · 冷启动启动竞态）：`PlaybackService.onCreate` 异步
     * `restore()` 重建队列，而 `ACTION_PLAY` / 媒体键可能在 queue 仍为空时到达 ——
     * 此时 [resume] 既无 handle、`restartCurrentIfAny()` 的 index 又不在
     * `queue.indices`，指令被静默丢弃；restore 随后按 PRD §6.1 只 prepare 不播
     * → MediaSession 假报 PLAYING、进度恒 0（K60 13:11:14 实测：需第二次
     * ACTION_PLAY 才出声）。这里记下**用户明确的播放意图**，由 restore 兑现。
     * 用 AtomicBoolean：播放指令在 Service 线程、restore 在 [scope] 协程。
     */
    private val pendingPlay = java.util.concurrent.atomic.AtomicBoolean(false)

    private val controllerHandle: PlayerControllerHandle = HandleAdapter()

    init {
        media?.bind(controllerHandle)
        // §61.26（G05/G07 验收）：引擎完成（ENDED）/ 出错（ERROR）事件此前无人消费——
        // 播完不自动进下一首、解码失败不自动跳过。这里统一裁决：
        // ENDED → 按播放模式进下一首（SEQUENCE 末尾置 ENDED 态收束）；
        // ERROR → 记录 lastError 并按 autoSkipOnError 跳下一首。
        // SINGLE_LOOP 的失败重试语义保留（同曲重试）。prepareAt 的异常就地吞掉，
        // 记为该曲失败，不让未捕获异常带崩本 scope。
        scope.launch {
            engine.state().collect { s ->
                runCatching {
                    when (s) {
                        EngineState.ENDED -> next()
                        EngineState.ERROR -> onEngineError(
                            engine.lastError()
                                ?: CloudMusicError.DsUpstream("ENGINE", "未知引擎错误"),
                        )
                        else -> Unit
                    }
                }
            }
        }
    }

    /** 队列全量替换：重算 SHUFFLE 顺序并单向全量下发 native（§8）。 */
    suspend fun setQueue(items: List<QueueItem>, mode: PlayMode, startAt: Int = 0) {
        queue = items.toList()
        shuffle = if (mode == PlayMode.SHUFFLE) ShuffleOrder.generate(config.shuffleSeed, items.size) else emptyList()
        val index = if (items.isEmpty()) -1 else startAt.coerceIn(0, items.size - 1)
        _state.update {
            it.copy(mode = mode, currentIndex = index, currentTrackId = items.getOrNull(index)?.trackId)
        }
        sync.push(queue, mode, index.coerceAtLeast(0))
    }

    suspend fun playAt(index: Int) = prepareAt(index, autoPlay = true, seekTo = 0L)

    suspend fun pause() {
        val h = handle ?: return
        // §61.25（E25 真机暴露）：句柄可能已被引擎完成路径（msg=2 → releaseAllInternal）
        // 释放，引擎对陈旧句柄显式报 DsNotFound；暂停不得因此带崩协程，会话态仍回 PAUSED。
        runCatching {
            engine.pause(h)
            val pos = engine.positionMs(h)
            _state.update { it.copy(phase = PlayerPhase.PAUSED, positionMs = pos) }
            media?.setPlaybackState(EngineState.PAUSED, pos, engine.durationMs(h))
        }.onFailure {
            _state.update { it.copy(phase = PlayerPhase.PAUSED) }
            media?.setPlaybackState(EngineState.PAUSED, 0L, 0L)
        }
    }

    suspend fun resume() {
        // 03-02 §344：用户点播 / 焦点恢复 / pendingPlay 兑现都必须过闸门；
        // prepareAt 的 autoPlay 分支只覆盖 playAt / 自动进曲。
        if (blockedByNetworkPolicy()) return
        // §61.25（E24/E25 真机暴露）：播放完成后句柄已随 msg=2 释放（引擎槽回 IDLE），
        // 此时再按播放不应空转导致 MediaSession 假报 PLAYING——标准行为是
        // 从头重播当前曲（重建引擎句柄走完整 prepare）。
        val h = handle ?: run {
            // §61.27：队列尚未恢复（冷启动竞态）时不能丢掉这一次播放指令。
            if (queue.isEmpty()) {
                pendingPlay.set(true)
                return
            }
            restartCurrentIfAny()
            return
        }
        runCatching { engine.play(h) }.onFailure {
            // 引擎侧已释放（完成后 / 出错后）→ 陈旧句柄，从头重播当前曲。
            restartCurrentIfAny()
            return
        }
        _state.update { it.copy(phase = PlayerPhase.PLAYING) }
        media?.setPlaybackState(EngineState.PLAYING, engine.positionMs(h), engine.durationMs(h))
    }

    /** §61.93（音效必须支持）：effectId>0 启用引擎基础音效（audiofx 档位），0 = 释放。 */
    fun attachEffect(effectId: Int) {
        val h = handle ?: return
        runCatching { engine.attachAudioEffect(h, effectId) }
    }

    /** §61.25：当前队列游标有效则从头重播（resume 空转兜底）。 */
    private suspend fun restartCurrentIfAny() {
        val index = _state.value.currentIndex
        if (index in queue.indices) playAt(index)
    }

    /** §15 T09：钳制到 [0, duration]；未知时长（-1）不钳制上界。 */
    suspend fun seek(ms: Long) {
        val h = handle ?: return
        runCatching {
            val duration = engine.durationMs(h)
            val upper = if (duration > 0) duration else Long.MAX_VALUE
            val clamped = ms.coerceIn(0L, upper)
            engine.seek(h, clamped)
            _state.update { it.copy(positionMs = clamped) }
        }   // 陈旧句柄（完成后）静默忽略：无可 seek 的活动流。
    }

    suspend fun next() {
        val s = _state.value
        val target = PlayModeOrdering.nextIndex(s.currentIndex, queue.size, s.mode, shuffle)
        if (target == null) {
            _state.update { it.copy(phase = PlayerPhase.ENDED) }
            return
        }
        playAt(target)
    }

    suspend fun prev() {
        val target = PlayModeOrdering.prevIndex(_state.value.currentIndex, queue.size) ?: return
        playAt(target)
    }

    /** §9：记录失败 → 自动跳下一首（可关）→ UI 展示具体原因。 */
    suspend fun onEngineError(error: CloudMusicError) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, lastError = error) }
        if (config.autoSkipOnError) next()
    }

    /** §9：切 DLNA → 本机内核静音 + 暂停，手机转遥控器；回落恢复。 */
    fun onOutputChanged(dlna: Boolean) {
        if (dlna) {
            handle?.let { engine.setVolume(it, 0f) }
            _state.update { it.copy(phase = PlayerPhase.PAUSED, remoteOutput = true) }
            media?.setEnabled(false)
        } else {
            handle?.let { engine.setVolume(it, 1f) }
            _state.update { it.copy(remoteOutput = false) }
            media?.setEnabled(true)
        }
    }

    /**
     * §8.2 恢复顺序 / §15 T11：Room.restore() → 重建队列 → push → prepare → seek →
     * **保持 PAUSED**，等用户点播放（PRD §6.1 不自动开播）。
     */
    suspend fun restore(snapshot: PlaybackSnapshot, items: List<QueueItem>) {
        val index = items.indexOfFirst { it.trackId.value == snapshot.currentTrackId }
            .let { if (it < 0) 0 else it }
        setQueue(items, snapshot.playMode, index)
        if (items.isEmpty()) {
            _state.update { it.copy(phase = PlayerPhase.IDLE, positionMs = snapshot.positionMs) }
            return
        }
        prepareAt(index, autoPlay = false, seekTo = snapshot.positionMs)
        _state.update { it.copy(phase = PlayerPhase.PAUSED, positionMs = snapshot.positionMs) }
        // §61.27：兑现冷启动期间到达的播放意图。PRD §6.1「不自动开播」约束的是
        // 无用户指令的 restore；只有 ACTION_PLAY / 媒体键才会置位 pendingPlay。
        // prepareAt 已建立 handle，resume() 会经 EngineAdapter 的 prepareGate 下发 play。
        if (pendingPlay.compareAndSet(true, false)) resume()
    }

    /** 供 `IMediaControl.bind` 使用的非 suspend 适配（03-07 §3）。 */
    fun asHandle(): PlayerControllerHandle = controllerHandle

    /** 03-02 §344：当前曲目是否网络音源（未 prepare 时按本地处理，不误拦）。 */
    fun currentSourceIsNetwork(): Boolean = currentNetworkSource

    /** @return true = 已落 ERROR 并中止本次播放动作。 */
    private fun blockedByNetworkPolicy(): Boolean {
        val reason = networkGate.rejectReason(currentNetworkSource) ?: return false
        fail(CloudMusicError.DsUpstream(NetworkPlaybackPolicy.ERROR_CODE, reason))
        return true
    }

    private suspend fun prepareAt(index: Int, autoPlay: Boolean, seekTo: Long) {
        if (queue.isEmpty() || index !in queue.indices) {
            _state.update { it.copy(phase = PlayerPhase.ENDED) }
            return
        }
        val item = queue[index]
        _state.update {
            it.copy(
                phase = PlayerPhase.PREPARING, currentIndex = index,
                currentTrackId = item.trackId, positionMs = seekTo, lastError = null,
            )
        }
        val track = lookup.get(item.trackId)
        if (track == null) {
            fail(CloudMusicError.DsNotFound(item.trackId.value))
            return
        }
        val link = try {
            resolver.resolve(track)
        } catch (e: Exception) {
            fail(if (e is CloudMusicError) e else CloudMusicError.DsUpstream("RESOLVE", e.message ?: "解析失败"))
            return
        }
        currentNetworkSource = link.kind != Link.Kind.LOCAL
        // 03-02 §344 / 01 §6.1：播放前检查「默认仅 WiFi 播放」。
        // restore(autoPlay=false) 只 prepare + seek 不播，不得被闸门拦
        // （PRD §6.1 不自动开播；03-11 §6 B05 飞行模式下本地/曲库完全可用）。
        // 被拒时落 ERROR + 明确原因，不 prepare、不释放现有 handle，也不自动跳曲
        // （这是用户策略拒绝而非音源故障，避免把整条队列逐首标失败）。
        if (autoPlay && blockedByNetworkPolicy()) return
        val source = try {
            engineSourceOf(track, link)
        } catch (e: IllegalArgumentException) {
            fail(CloudMusicError.DsInvalidConfig(e.message ?: "音源形态非法"))
            return
        }

        handle?.let { engine.release(it) }
        handle = engine.prepare(
            source,
            EngineConfig(autoSkipOnError = config.autoSkipOnError),
        )
        val h = requireNotNull(handle)
        if (seekTo > 0) engine.seek(h, seekTo)
        // 冷启动恢复只 prepare + seek，引擎必须停在 PAUSED（§8.2 / PRD §6.1 不自动开播）。
        if (autoPlay) engine.play(h) else engine.pause(h)
        _state.update {
            it.copy(
                phase = if (autoPlay) PlayerPhase.PLAYING else PlayerPhase.PAUSED,
                positionMs = seekTo,
            )
        }
        media?.publishMetadata(track, track.coverRef)
        media?.setPlaybackState(
            if (autoPlay) EngineState.PLAYING else EngineState.PAUSED,
            seekTo,
            track.durationMs,
        )
    }

    /** C-05：四态 → EngineSource 的唯一映射（16 §3.2 决策表）。 */
    private fun engineSourceOf(track: Track, link: Link): EngineSource {
        val loopbackOrDirect = link.url.takeIf { it.isNotBlank() }
        val url = when (link.kind) {
            Link.Kind.URL -> link.url
            Link.Kind.PROXIED, Link.Kind.TEXT -> requireNotNull(loopbackOrDirect) {
                "PROXIED/TEXT 本机播放必须由组合层 proxy.publish 提供回环 URL（16 §3.2）"
            }
            Link.Kind.LOCAL -> null
        }
        val localPath = if (link.kind == Link.Kind.LOCAL) link.localPath else null
        return EngineSource(
            trackId = track.id.value,
            mimeHint = mimeHintOf(link),
            expectedLength = track.durationMs,
            url = url,
            localPath = localPath,
        )
    }

    /** 12 §61.45 / F1：MIME 推断统一委托 AudioFormats，本处不再维护 when 分支。 */
    private fun mimeHintOf(link: Link): String =
        com.example.cloudmusic.model.AudioFormats.mimeOfName(link.localPath ?: link.url)

    private fun fail(error: CloudMusicError) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, lastError = error) }
    }

    private inner class HandleAdapter : PlayerControllerHandle {
        override fun play() { scope.launch { resume() } }
        override fun pause() { scope.launch { pause() } }
        override fun next() { scope.launch { next() } }
        override fun prev() { scope.launch { prev() } }
        override fun seekRelative(deltaMs: Long) {
            scope.launch { seek(_state.value.positionMs + deltaMs) }
        }

        override fun pauseFromFocus() { scope.launch { pause() } }
        override fun resumeFromFocus() { scope.launch { resume() } }
        override fun duck(volume: Float) { handle?.let { engine.setVolume(it, volume) } }
        override fun unduck() { handle?.let { engine.setVolume(it, 1f) } }
    }
}
