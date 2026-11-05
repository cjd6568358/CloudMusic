package com.example.cloudmusic.media

import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `IMediaControl` 的纯 JVM 内存参考实现：供单测、UI 预览与组合层联调，
 * **不替代** `:media-legacy/MediaLegacyAdapter`（后者负责 Receiver / MediaSession / AVRCP）。
 *
 * 落实 03-07 §12 的静态可测部分：T01~T04 焦点语义、T05 双击判定、
 * T06 `setEnabled(false)` 忽略按键、T07 空标题兜底、T09 切 DLNA 停用。
 */
class InMemoryMediaControl(
    duckVolume: Float = 0.25f,
    seekDeltaMs: Long = 10_000L,
    doubleClickWindowMs: Long = 300L,
    now: () -> Long = { System.currentTimeMillis() },
) : IMediaControl, ObservableMediaControl {

    private val focus = FocusPolicy(duckVolume)
    private val keys = MediaKeyDispatcher(seekDeltaMs, doubleClickWindowMs, now)

    private var controller: PlayerControllerHandle? = null
    private var enabled = true
    private val _metadata = MutableStateFlow<MediaMetadata?>(null)
    private val _playback = MutableStateFlow<MediaPlaybackSnapshot?>(null)

    override fun bind(controller: PlayerControllerHandle) {
        this.controller = controller
    }

    private fun requireBound(): PlayerControllerHandle =
        controller ?: error("bind(controller) must be called before media events (03-07 §3)")

    override fun onFocusChange(change: FocusChange) {
        if (!enabled) return
        focus.onFocusChange(change, requireBound())
    }

    override fun onMediaButton(key: MediaKey, action: MediaAction) {
        if (!enabled) return
        val handle = requireBound()
        keys.dispatch(key, action, handle) {
            _playback.value?.state == EngineState.PLAYING
        }
    }

    override fun publishMetadata(track: Track, coverPath: String?) {
        _metadata.value = MediaMetadata(
            title = track.title.takeIf { it.isNotBlank() } ?: MediaMetadata.FALLBACK_TITLE,
            artist = track.artist,
            album = track.album,
            coverPath = coverPath,
            durationMs = track.durationMs,
        )
    }

    override fun setPlaybackState(state: EngineState, positionMs: Long, durationMs: Long) {
        _playback.value = MediaPlaybackSnapshot(state, positionMs, durationMs)
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            focus.onDisabled()
            keys.reset()
        }
    }

    override fun release() {
        controller = null
        enabled = false
        focus.onDisabled()
        keys.reset()
        _metadata.value = null
        _playback.value = null
    }

    override fun metadata(): Flow<MediaMetadata?> = _metadata.asStateFlow()

    override fun playback(): Flow<MediaPlaybackSnapshot?> = _playback.asStateFlow()

    override fun snapshot(): MediaControlSnapshot = MediaControlSnapshot(
        enabled = enabled,
        bound = controller != null,
        focus = focus.state,
        volume = focus.volume,
        metadata = _metadata.value,
        playback = _playback.value,
    )

    /** 供上层通知「用户手动暂停」，清除焦点恢复标记（§12 T03）。 */
    fun onManualPause() = focus.onManualPause()

    /** 供上层通知「已开始播放」，供 PLAY_PAUSE 切换判定。 */
    fun onPlaying() = focus.onPlaying()

    val isResumePending: Boolean get() = focus.isResumePending
}
