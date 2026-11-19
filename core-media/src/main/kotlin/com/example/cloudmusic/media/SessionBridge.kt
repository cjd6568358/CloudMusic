package com.example.cloudmusic.media

import com.example.cloudmusic.engine.api.EngineState

/**
 * §61.240（V3-M1 第一刀）：系统会话补全——把 mediaControl 发布链
 * （[ObservableMediaControl.metadata] / [ObservableMediaControl.playback]，§61.239 起分轨
 * 相对口径）转成系统会话可写视图。
 *
 * 本类保持纯 JVM（`:core-media` 无 Android 依赖）：判定逻辑在此可测；
 * `MediaSession`/`PlaybackState` 常量映射留在 app 侧 [com.example.cloudmusic.PlaybackService]
 * （Android 框架类），经 [SessionPlaybackWriter] / [SessionMetadataWriter] 两口注入。
 *
 * 口径（如实登记）：
 * - 播放态只认 [EngineState.PLAYING] → 播放中；其余（PAUSED/IDLE/PREPARING/ENDED/ERROR）
 *   一律按「非播放」写回退态——不伪造播放（§61.25 口径延续：完成/出错回退 PAUSED）。
 * - 位置负值（引擎异常一手防御）钳 0；未知时长（0）原样透传，由会话侧表现为无时长。
 * - metadata 为 null（尚无曲目）不写——系统会话保留上一份元数据是合理幂等；
 *   缺标题兜底已在 [InMemoryMediaControl.publishMetadata]（03-07 §12 T07）完成。
 */
class SessionBridge(
    private val playbackWriter: SessionPlaybackWriter,
    private val metadataWriter: SessionMetadataWriter,
) {

    /** 系统会话播放态写口（app 侧实现：PlaybackState.Builder + 速度 1f/0f）。 */
    fun interface SessionPlaybackWriter {
        fun write(playing: Boolean, positionMs: Long, durationMs: Long)
    }

    /** 系统会话元数据写口（app 侧实现：MediaMetadataCompat.Builder）。 */
    fun interface SessionMetadataWriter {
        fun write(title: String, artist: String, album: String, durationMs: Long, coverPath: String?)
    }

    /** 发布链快照 → 会话播放态写。null（尚无发布）不写。 */
    fun onPlayback(snapshot: MediaPlaybackSnapshot?) {
        if (snapshot == null) return
        playbackWriter.write(
            playing = snapshot.state == EngineState.PLAYING,
            positionMs = clampPosition(snapshot.positionMs),
            durationMs = snapshot.durationMs,
        )
    }

    /** 发布链元数据 → 会话元数据写。null（尚无曲目）不写。 */
    fun onMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        metadataWriter.write(
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs,
            coverPath = metadata.coverPath,
        )
    }

    /** 引擎异常负位置防御（一手判据：不向系统会话写负毫秒）。 */
    private fun clampPosition(posMs: Long): Long = if (posMs < 0) 0L else posMs
}
