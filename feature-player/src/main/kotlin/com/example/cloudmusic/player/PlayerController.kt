package com.example.cloudmusic.player

import com.example.cloudmusic.common.CloudMusicError
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var queue: List<QueueItem> = emptyList()
    private var shuffle: List<Int> = emptyList()
    private var handle: EngineHandle? = null

    private val controllerHandle: PlayerControllerHandle = HandleAdapter()

    init {
        media?.bind(controllerHandle)
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
        // §61.25（E24/E25 真机暴露）：播放完成后句柄已随 msg=2 释放（引擎槽回 IDLE），
        // 此时再按播放不应空转导致 MediaSession 假报 PLAYING——标准行为是
        // 从头重播当前曲（重建引擎句柄走完整 prepare）。
        val h = handle ?: run {
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
    }

    /** 供 `IMediaControl.bind` 使用的非 suspend 适配（03-07 §3）。 */
    fun asHandle(): PlayerControllerHandle = controllerHandle

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

    private fun mimeHintOf(link: Link): String {
        val raw = link.localPath ?: link.url.substringBefore('?')
        return when (raw.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

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
