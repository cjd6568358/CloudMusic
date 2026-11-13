package com.example.cloudmusic.engineopensource

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.engine.api.EngineConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.example.cloudmusic.engine.api.EngineHandle
import com.example.cloudmusic.engine.api.EngineSource
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.model.IDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/12 §61.81（M1 起）· `IMusicEngine` 的 Media3/ExoPlayer 实现。
 *
 * 现行边界（B2 M1~M5 已收口，见 `12 §61.92`；本 KDoc 于 `12 §61.100`
 * 做过期口径对齐勘误，只改注释、零行为改动）：
 * - 门面 [IMusicEngine] 14 方法签名逐字不变（03-06 §4）；单点换绑在
 *   `EngineModule.provideMusicEngine`，`:engine-legacy` / `EngineAdapter`
 *   与 18 个私有 so 保留为回退（C5 暂不移除）。
 * - `url` / `localPath` 经官方 `MediaItem` 装配；URL 态用 `CacheDataSource`
 *   + `SimpleCache`（`§61.88` M3），LOCAL 态 `DefaultDataSource` 直读；
 *   自定义 `ExtractorsFactory` 挂 Ape / Asf（`§61.94 / §61.97`）。
 *   **不透传 mimeType**（`1485ce0f`）：未注册 MIME 会让
 *   `DefaultMediaSourceFactory` 绕过自定义 ExtractorsFactory。
 * - FFmpeg 软解经 `EXTENSION_RENDERER_MODE_ON` 补位平台解码器（`§61.85` M2）。
 * - 音效：`attachAudioEffect` 已于 `§61.93` 挂系统 audiofx 三件套并真机 PASS；
 *   竞品 103 JNI 高级音效链按 `§61.98 / §61.99` 留 P2 专项，不在本类伪造接线。
 * - `§61.105` ReplayGain：官方 metadata（VorbisComment / ID3）→
 *   [ReplayGainController] → `ExoPlayer.volume`；不做音频扫描 / 不持久化，
 *   真机音量效果 NOT RUN。gapless：文件内 trimming 由官方 Extractor 具备，
 *   跨曲队列因 single-handle 契约（prepare 前 release → player 重建）判 BLOCKED，
 *   详见 [GaplessPolicy]，不在本类伪造队列接线。
 * - `setQuality` 仍为门面轻量实现；[EngineConfig] 的 `sampleRateHz` /
 *   `preferHardwareDecoder` / `bufferMs` / `autoSkipOnError` 不由本类逐项消费
 *   （平台 MediaCodec 优先、缓冲由 ExoPlayer 管理），不写成「已支持」。
 * - 媒体会话 / 音频焦点 / 媒体按键**不**由 Media3 主导（C1），仍走
 *   `:core-media` + `:media-legacy` + 平台 API；本模块不引 `media3-session`。
 *
 * M1 原始边界（`12 §61.81` **当时快照**，不作现行判据）：当时把
 * `RangeCacheDataSource`（自研缓存 / Range）列为 M3 待办、**不伪造接线**，
 * 音效按 C7 默认假设留空挂账，并强调「M1 骨架 ≠ B2 完成」。上述三项已分别由
 * `§61.88`（官方 CacheDataSource）、`§61.93`（audiofx 真机 PASS）、
 * `§61.92`（F4-B2 验收 PASS）演进；保留此段仅为满足历史守护的锚点可追溯。
 *
 * 状态机：PREPARING / PLAYING / PAUSED / IDLE 由门面调用即时驱动（与
 * `InMemoryMusicEngine` / `EngineAdapter` 一致），ENDED / ERROR 由
 * [Player.Listener] 驱动；B2 回归按 `§61.92` 多信号口径（READY + isPlaying /
 * ENDED / 自动进曲 / seek，不单看状态机上报）判定。
 */
class Media3EngineAdapter : IMusicEngine {

    /** 由 `EngineModule.provideMusicEngine` 用 `.also { it.appContext = context }` 注入。 */
    var appContext: Context? = null

    private val _state = MutableStateFlow(EngineState.IDLE)
    private var player: ExoPlayer? = null
    private var activeHandle: EngineHandle? = null
    private var dataSource: IDataSource? = null

    @Volatile
    private var error: CloudMusicError? = null
    private var nextRaw = 1L

    /**
     * §61.87（F3 首轮真机暴露）：ExoPlayer 强制「单线程（application thread）」
     * 访问——构建线程必须带 Looper，且所有后续调用（含 getter）都在同一线程，
     * 否则 `ExoPlayerImpl.verifyApplicationThread` 拒绝执行、命令静默丢弃
     * （prepare 永不 READY，F3 首轮 22 格全 FAIL 的根因）。门面调用来自
     * PlayerController 的 Dispatchers.Default 协程 → 统一 marshal 到主线程：
     * 命令 fire-and-forget（[onMain]），读值 post+latch（[onMainSync]）。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun <T> onMainSync(timeoutSec: Long, default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrDefault(default)
        }
        val latch = CountDownLatch(1)
        var out = default
        mainHandler.post {
            out = runCatching(block).getOrDefault(default)
            latch.countDown()
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return out
    }

    /**
     * `12 §61.105` / ReplayGain 接线的**生产消费点**：容器标签 →
     * [ReplayGainController] → `ExoPlayer.volume`。门面 14 方法签名零改动，
     * 无标签时退化为原始用户音量；真机音量效果 NOT RUN（设备离线）。
     */
    private val replayGain = ReplayGainController { volume ->
        // §61.87：ExoPlayer 单线程约束。listener 已在 application thread；
        // setVolume 走 guard → onMain；两处都收敛到主线程后再写 volume。
        onMain { player?.volume = volume }
    }

    /** §61.105：官方 metadata Entry → `KEY=VALUE` 行（纯 JVM 解析层不依赖 Android）。 */
    private fun metadataLines(metadata: Metadata): List<String> {
        val lines = ArrayList<String>(metadata.length())
        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is VorbisComment -> lines.add("${entry.key}=${entry.value}")
                is TextInformationFrame -> lines.add(
                    // `value` 单值字段已标记 deprecated；`values` 为 ID3 多值列表。
                    "${entry.description ?: ""}=${entry.values.firstOrNull() ?: ""}")
                is CommentFrame -> lines.add("${entry.description}=${entry.text}")
                else -> Unit
            }
        }
        return lines
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val d = player?.duration ?: C.TIME_UNSET
                    Log.i(
                        TAG,
                        "state=READY durationMs=%d".format(
                            if (d == C.TIME_UNSET) -1L else d),
                    )
                }
                Player.STATE_ENDED -> {
                    Log.i(TAG, "state=ENDED positionMs=%d".format(positionNow()))
                    _state.value = EngineState.ENDED
                }
                Player.STATE_IDLE -> Log.i(TAG, "state=IDLE")
                Player.STATE_BUFFERING -> Log.i(TAG, "state=BUFFERING")
            }
        }

        // §61.86：F3 双引擎判据的 B2 侧「真实出声」信号——isPlaying=true 表示
        // 渲染器已就绪且音频正在写 AudioTrack（对应 A 路线的 Write start play）。
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "state=%s positionMs=%d".format(
                if (isPlaying) "PLAYING" else "PAUSED", positionNow()))
        }

        // §61.108 F3 补强：seek 后的落点回执——工具侧以
        // `discontinuity reason=SEEK positionMs=` 验证 seek 真实生效。
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                Log.i(TAG, "discontinuity reason=SEEK positionMs=%d".format(
                    newPosition.positionMs))
            }
        }

        // §61.105：保留 Player.Listener.onMetadata 兼容通路（本机 ExoPlayer
        // 1.11.1 真机不触发，见 §61.107 探针实证）。
        override fun onMetadata(metadata: Metadata) {
            applyReplayGain(metadataLines(metadata), "metadata")
        }

        /**
         * §61.107：官方 Extractor 把 ID3 / VorbisComment 挂在
         * `Format.metadata`，经 `onTracksChanged(Tracks)` 才是 ExoPlayer 的
         * 可靠通路。§61.106/§61.107 首轮真机探针显示 `onMetadata` 0 命中，
         * 故补此通路（同一解析层，不改变音量换算语义）。
         */
        override fun onTracksChanged(tracks: Tracks) {
            val lines = ArrayList<String>()
            for (g in 0 until tracks.groups.size) {
                val group = tracks.groups[g]
                for (i in 0 until group.length) {
                    val meta: Metadata? = group.getTrackFormat(i).metadata
                    if (meta != null) lines.addAll(metadataLines(meta))
                }
            }
            applyReplayGain(lines, "tracks")
        }

        private fun applyReplayGain(lines: List<String>, source: String) {
            if (lines.isEmpty()) return
            replayGain.onMetadataLines(lines)
            Log.i(TAG, "replaygain(%s) tags=%d gain=%s volume=%.3f mode=%s".format(
                source,
                lines.size,
                replayGain.parsedGain()?.let { "%.2f dB".format(it.trackGainDb) } ?: "none",
                replayGain.effectiveVolume(),
                replayGain.mode,
            ))
        }

        override fun onPlayerError(e: PlaybackException) {
            Log.i(TAG, "error=%s code=%s".format(e.message ?: "", e.errorCodeName))
            Log.i(TAG, android.util.Log.getStackTraceString(e))
            fail(CloudMusicError.DsUpstream(
                "MEDIA3_PLAYBACK", e.message ?: "Media3 播放错误"))
        }
    }

    private fun positionNow(): Long {
        val p = player ?: return 0L
        val pos = p.currentPosition
        return if (pos == C.TIME_UNSET) 0L else pos
    }

    @Suppress("UNUSED_PARAMETER")
    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        check(activeHandle == null) {
            "single handle invariant: release() before a new prepare() (03-06 §4 / §15 T06)"
        }
        val ctx = appContext
            ?: throw CloudMusicError.DsUpstream(
                "MEDIA3_CONTEXT", "appContext 未注入（EngineModule 单点换绑需 .also）")
        // 局部副本：跨模块 val 无法 smart-cast（M1 编译 warning 的根因）。
        val url = source.url
        val localPath = source.localPath
        val uri: Uri = when {
            url != null -> Uri.parse(url)
            localPath != null -> Uri.fromFile(File(localPath))
            else -> throw CloudMusicError.DsUpstream(
                "MEDIA3_SOURCE", "EngineSource 既无 url 也无 localPath，无法构造 MediaItem")
        }
        error = null
        // §61.100 对齐勘误（原注释错挂 §61.99；文档 §61.99 实为 LoudnessEvaluator
        // / ReplayGain 实证，本改动来自 2026-11-13 07:11 提交 1485ce0f）：
        // 不透传 mimeType——media3 的 DefaultMediaSourceFactory 对
        // 未注册 mimeType（audio/x-wma 等）走 mimeType→媒体源映射，绕过自定义
        // ExtractorsFactory（真机 CM-Factory=0 一手实证）。不设 mimeType = 由
        // URI 后缀/内容推断 → ProgressiveMediaSource + 自定义 ExtractorsFactory ✓。
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(source.trackId)
            .build()
        // §61.85（B2 M2）：EXTENSION_RENDERER_MODE_ON = 平台 MediaCodec 硬解优先，
        // FFmpeg 软解（FfmpegAudioRenderer，libffmpegJNI）仅补位平台不支持的
        // 格式——对齐施工单 §4「硬解优先」与 B2 的「删掉缺陷层、不换解码器」。
        val renderersFactory = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // §61.86：F3 双引擎判据的 B2 侧一手日志。prepare 行与 A 路线
        // EngineAdapter 的 `prepare trackId=... remote=... url=...` 同格式，
        // 使 tools/l3_f3_matrix_run.py 的正则对两引擎通用。
        Log.i(
            TAG,
            "prepare trackId=%s remote=%s url=%s localPath=%s expectedLength=%d "
                .format(
                    source.trackId,
                    (source.url != null).toString(),
                    source.url ?: "-",
                    source.localPath ?: "-",
                    source.expectedLength,
                ) + "engine=media3-1.11.1 ffmpeg=EXTENSION_RENDERER_MODE_ON",
        )
        val handle = EngineHandle(nextRaw++)
        // §61.88（B2 M3）：URL 态 = 官方 CacheDataSource（缓存优先 + LRU 256MiB +
        // FLAG_IGNORE_CACHE_ON_ERROR 回落直连）；LOCAL 态 = DefaultDataSource
        // 直读文件（不缓存本地文件）。全部官方语义，无 A 路线私有 vfile/commit。
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
            if (url != null) {
                CacheDataSource.Factory()
                    .setCache(ensureCache(ctx))
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            ctx,
                            DefaultHttpDataSource.Factory()
                                .setAllowCrossProtocolRedirects(true),
                        ),
                    )
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                DefaultDataSource.Factory(ctx)
            }
        // §61.94/§61.97：Extractor 清单 = Ape/Asf（容器缺口 ①②）+ 官方默认清单。
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            androidx.media3.extractor.ape.ApeExtractor.factoryWithApe(),
        )
        // §61.87：ExoPlayer 的构建 + prepare 必须在 application thread（主 Looper）。
        onMainSync(10L, Unit) {
            val p = ExoPlayer.Builder(ctx, renderersFactory, mediaSourceFactory).build()
            // §61.105：先赋值，保证 metadata 回调（主线程消息队列，本块结束后才到达）
            // 里 ReplayGainController 能立刻写到 player.volume。
            player = p
            p.addListener(listener)
            p.setMediaItem(item)
            // §61.105 / gapless：显式关闭「曲目结束暂停」，避免 B2 侧额外插入间隙。
            // 文件内 encoder delay/padding 裁剪由官方 Extractor + AudioSink 负责；
            // 跨曲队列 gapless 由 GaplessPolicy 判定当前 single-handle 架构可达性
            // （判 BLOCKED，不伪造队列接线；真机逐格式验证 NOT RUN）。
            p.setPauseAtEndOfMediaItems(false)
            val gapless = GaplessPolicy.assess(
                playerReused = false,
                queuedItemCount = 1,
                pauseAtEndOfMediaItems = false,
            )
            Log.i(TAG, "gapless in_file=%s cross_track=%s blocker=%s".format(
                gapless.inFileTrimmingAvailable,
                gapless.crossTrackAvailable,
                gapless.blocker ?: "none",
            ))
            p.prepare()
            activeHandle = handle
            // §61.105：切歌先清上一曲标签（写回 volume=用户音量）。
            replayGain.reset()
            // 与 EngineAdapter / InMemoryMusicEngine 一致：prepare 后等 play() 才进入 PLAYING。
            _state.value = EngineState.PAUSED
        }
        return handle
    }

    private fun requireActive(handle: EngineHandle) {
        check(activeHandle == handle) {
            "invalid or released EngineHandle(${handle.raw}); active=${activeHandle?.raw}"
        }
    }

    private fun fail(e: CloudMusicError) {
        Log.i(TAG, "engine_error=%s code=%s".format(e.message, e.code))
        error = e
        _state.value = EngineState.ERROR
    }

    private inline fun guard(handle: EngineHandle, crossinline block: (ExoPlayer) -> Unit) {
        requireActive(handle)
        // §61.87：命令统一 marshal 到主线程（fire-and-forget，播放命令无返回值）。
        onMain {
            val p = player ?: return@onMain
            try {
                block(p)
            } catch (t: Throwable) {
                fail(CloudMusicError.DsUpstream("ENGINE_CALL", t.message ?: "Media3 调用失败"))
            }
        }
    }

    override fun attachDataSource(handle: EngineHandle, ds: IDataSource) {
        requireActive(handle)
        // §61.108 语义定稿（03-06 §4）：B2 主路径字节流由 EngineSource（C-05）与
        // 回环代理解析供给；本方法是**预留通用注入端口**——保存引用并在 release
        // 时关闭。M3 期「桥接成 RangeCacheDataSource」的旧计划不再挂承诺；
        // 若未来需要自定义字节流（如自定义缓存），以独立演进批次立项，不在此处
        // 伪造接线。生产调用方当前为 0（契约位 + 单测覆盖）。
        dataSource = ds
    }

    override fun play(handle: EngineHandle) = guard(handle) {
        it.play()
        _state.value = EngineState.PLAYING
    }

    override fun pause(handle: EngineHandle) = guard(handle) {
        it.pause()
        _state.value = EngineState.PAUSED
    }

    override fun stop(handle: EngineHandle) = guard(handle) {
        it.seekTo(0L)
        it.stop()
        it.clearMediaItems()
        _state.value = EngineState.IDLE
    }

    override fun seek(handle: EngineHandle, positionMs: Long) = guard(handle) {
        val d = it.duration
        val target = if (d != C.TIME_UNSET && d >= 0) {
            positionMs.coerceIn(0L, d)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        // §61.108 F3 补强：seek 落点的可观测性——l3_f3_matrix_run.py --enhanced
        // 以此日志 + onPositionDiscontinuity 落点行验证 seek 判据（工具侧判据，
        // 不改变播放行为）。
        Log.i(TAG, "seek toMs=%d durMs=%s".format(
            target, if (d == C.TIME_UNSET) "unset" else d.toString()))
        it.seekTo(target)
    }

    /**
     * §61.105：用户音量作为 ReplayGain 的基准幅度，实际下发值由
     * [ReplayGainController] 计算（无标签 / OFF 时等价于原实现）。
     */
    override fun setVolume(handle: EngineHandle, volume: Float) {
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        guard(handle) { replayGain.onUserVolume(volume) }
    }

    override fun positionMs(handle: EngineHandle): Long {
        requireActive(handle)
        // §61.87：getter 也受单线程约束 → 主线程同步读（1s 超时回退 0）。
        return onMainSync(1L, 0L) {
            val p = player ?: return@onMainSync 0L
            val pos = p.currentPosition
            if (pos == C.TIME_UNSET) 0L else pos
        }
    }

    override fun durationMs(handle: EngineHandle): Long {
        requireActive(handle)
        return onMainSync(1L, -1L) {
            val p = player ?: return@onMainSync -1L
            val d = p.duration
            if (d == C.TIME_UNSET || d < 0) -1L else d
        }
    }

    override fun state(): Flow<EngineState> = _state.asStateFlow()

    /** 03-06 §5：切质量 V0.1 不启用；A 路线 `EngineAdapter` 亦为空实现。 */
    override fun setQuality(handle: EngineHandle, quality: Int) {
        requireActive(handle)
    }

    /**
     * §61.93（用户拍板：音效必须支持，推翻 §61.80 C7 降级假设）：
     * effectId > 0 → 启用系统 audiofx 基础件（Equalizer + BassBoost + Virtualizer）
     * 挂当前播放的 audioSessionId（对 B2 全输出生效）；effectId = 0 → 全部释放。
     * 竞品 103 JNI 音效（NCAE / MNN / 空间音频）的挂接方式评估属 T5，另行接线。
     */
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var virtualizer: android.media.audiofx.Virtualizer? = null

    override fun attachAudioEffect(handle: EngineHandle, effectId: Int) {
        requireActive(handle)
        onMainSync(5L, Unit) {
            val p = player ?: return@onMainSync
            val sessionId = p.audioSessionId
            if (effectId <= 0) {
                runCatching { equalizer?.enabled = false }
                runCatching { bassBoost?.enabled = false }
                runCatching { virtualizer?.enabled = false }
                Log.i(TAG, "audiofx detached session=%d".format(sessionId))
                return@onMainSync
            }
            runCatching {
                val eq = equalizer ?: android.media.audiofx.Equalizer(0, sessionId)
                    .also { equalizer = it }
                eq.enabled = true
                // effectId 作为基础档位（1~5）：全频段增益按档位线性抬升。
                val bandRange = eq.numberOfBands - 1
                for (b in 0..bandRange) {
                    val level = (effectId.coerceIn(1, 5) - 1) * 200
                    eq.setBandLevel(b.toShort(), level.toShort())
                }
                val bb = bassBoost ?: android.media.audiofx.BassBoost(0, sessionId)
                    .also { bassBoost = it }
                bb.enabled = true
                bb.setStrength((effectId.coerceIn(1, 5) * 200).toShort())
                val vz = virtualizer ?: android.media.audiofx.Virtualizer(0, sessionId)
                    .also { virtualizer = it }
                vz.enabled = true
                vz.setStrength((effectId.coerceIn(1, 5) * 200).toShort())
                Log.i(TAG, "audiofx attached session=%d level=%d bands=%d"
                    .format(sessionId, effectId, eq.numberOfBands))
            }.onFailure {
                Log.i(TAG, "audiofx attach failed: %s".format(it.message))
            }
        }
    }

    override fun release(handle: EngineHandle) {
        requireActive(handle)
        Log.i(TAG, "release handle=%d".format(handle.raw))
        // §61.87：player.release() 也必须在 application thread（主 Looper）。
        onMainSync(5L, Unit) {
            try {
                player?.removeListener(listener)
                player?.release()
            } catch (t: Throwable) {
                fail(CloudMusicError.DsUpstream("ENGINE_RELEASE", t.message ?: "释放失败"))
            } finally {
                player = null
            }
        }
        // §15 T09：release 同时关闭 IDataSource（无线程约束，调用线程直接做）。
        dataSource?.close()
        dataSource = null
        activeHandle = null
        error = null
        _state.value = EngineState.IDLE
    }

    override fun lastError(): CloudMusicError? = error

    /**
     * §61.88（B2 M3）：URL 态走官方 `CacheDataSource + SimpleCache`——HTTP Range、
     * 磁盘缓存、离线命中、`FLAG_IGNORE_CACHE_ON_ERROR` 失败回落直连，全部是
     * 官方 DataSource 语义（施工单 §4.2），**不移植 A 路线的 vfile / commit /
     * 代次门控私有语义**（F4 缺陷层在 B2 中不存在）。LOCAL 态直读文件不缓存。
     * 缓存目录在 App 内部缓存（卸载即清，与 A 路线 SetConfig 根目录口径一致），
     * LRU 上限 256MiB。
     */
    @Volatile
    private var mediaCache: SimpleCache? = null

    private fun ensureCache(ctx: Context): SimpleCache {
        mediaCache?.let { return it }
        synchronized(this) {
            mediaCache?.let { return it }
            val dir = File(ctx.cacheDir, "b2_media_cache")
            dir.mkdirs()
            val cache = SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(ctx),
            )
            mediaCache = cache
            Log.i(TAG, "media cache ready dir=%s cap=256MiB".format(dir.absolutePath))
            return cache
        }
    }

    companion object {
        /** §61.86：F3 双引擎判据的 B2 侧 logcat tag（tools/l3_f3_matrix_run.py 解析）。 */
        const val TAG = "CM-Media3"
    }
}
