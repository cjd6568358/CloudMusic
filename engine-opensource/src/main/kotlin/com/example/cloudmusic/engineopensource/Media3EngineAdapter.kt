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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import kotlinx.coroutines.flow.StateFlow
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
 *   跨曲队列自 §61.131 起由门面增量 prepareQueue + §61.139 播放链接线：
 *   同一 ExoPlayer 实例的多 MediaItem playlist 可达，详见 [GaplessPolicy]。
 * - `setQuality` 仍为门面轻量实现；[EngineConfig] 的 `sampleRateHz` /
 *   `preferHardwareDecoder` / `autoSkipOnError` 不由本类逐项消费
 *   （平台 MediaCodec 优先），不写成「已支持」。`bufferMs` 自 §61.302（H0-5）
 *   起消费为 LoadControl 的起播/复播阈值（原为死参数）。
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
    private val _queueIndex = MutableStateFlow(-1)

    /** §61.317：缓存预热线程引用——prepare 新曲时 interrupt 上一条。 */
    private var cacheWarmerThread: Thread? = null

    /**
     * §61.87（F3 首轮真机暴露）：ExoPlayer 强制「单线程（application thread）」
     * 访问——构建线程必须带 Looper，且所有后续调用（含 getter）都在同一线程，
     * 否则 `ExoPlayerImpl.verifyApplicationThread` 拒绝执行、命令静默丢弃
     * （prepare 永不 READY，F3 首轮 22 格全 FAIL 的根因）。门面调用来自
     * PlayerController 的 Dispatchers.Default 协程 → 统一 marshal 到主线程：
     * 命令 fire-and-forget（[onMain]），读值 post+latch（[onMainSync]）。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * §61.301（H0-2）：最近一次成功读到的引擎位置。`positionMs()` 的主线程同步读
     * 有 1s 超时兜底，此前超时返回 0L——缓冲/重解码恰是主线程最忙窗口，UI 瞬跳
     * 00:00 再弹回（「快进了一下」的根因之一）。超时改保留本值（volatile：
     * ticker 在 Dispatchers.Default 写读，主线程在 seek/prepare 写）。
     */
    @Volatile
    private var lastKnownPositionMs: Long = 0L

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
                    // §61.111：key 用 TXXX 的 description；普通帧（TIT2 等）description
                    // 为 null → 回退 frame id（"TIT2=…"），避免空键噪声行。
                    // `value` 单值字段已标记 deprecated；`values` 为 ID3 多值列表。
                    "${entry.description ?: entry.id}=${entry.values.firstOrNull() ?: ""}")
                is CommentFrame -> lines.add("${entry.description}=${entry.text}")
                else -> Unit
            }
        }
        return lines
    }

    private val listener = object : Player.Listener {
        /**
         * §61.139：playlist 自动切曲 / 手动跳播 → 上报当前 MediaItem 索引，
         * PlayerController 据此同步业务游标与媒体会话元数据。切曲同时清掉
         * 上一曲的 ReplayGain 标签与库值兜底，避免增益串曲（队列内库值增益
         * 仅起播曲生效，其余曲目靠容器标签，限制见 12 §61.139）。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = player?.currentMediaItemIndex ?: -1
            _queueIndex.value = idx
            replayGain.reset()
            replayGain.onLibraryGain(null)
            Log.i(TAG, "queue transition index=%d reason=%d".format(idx, reason))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val d = player?.duration ?: C.TIME_UNSET
                    Log.i(
                        TAG,
                        "state=READY durationMs=%d".format(
                            if (d == C.TIME_UNSET) -1L else d),
                    )
                    // §61.301（H0-1）：READY 是 BUFFERING 的唯一收敛点——按真实
                    // 播放意图回正 PLAYING/PAUSED（缓冲结束恢复计时；暂停中缓冲
                    // 完成不伪造播放）。此前 READY 只打日志，BUFFERING 一旦发布
                    // 就没人回正。
                    player?.let { p ->
                        _state.value =
                            if (p.playWhenReady && p.isPlaying) EngineState.PLAYING
                            else EngineState.PAUSED
                    }
                }
                Player.STATE_ENDED -> {
                    Log.i(TAG, "state=ENDED positionMs=%d".format(positionNow()))
                    _state.value = EngineState.ENDED
                }
                Player.STATE_IDLE -> Log.i(TAG, "state=IDLE")
                // §61.301（H0-1）：BUFFERING 透传——此前只打日志，业务层照报
                // PLAYING、系统会话按 speed=1f 墙钟外推（「缓冲中计时照走」根因）。
                // 仅在播放意图存在时发布：暂停态的重缓冲保持 PAUSED 不伪装。
                Player.STATE_BUFFERING -> {
                    Log.i(TAG, "state=BUFFERING")
                    player?.takeIf { it.playWhenReady }?.let { _state.value = EngineState.BUFFERING }
                }
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
            // §61.111 真机诊断（照 §61.106 diag 先例）：gain=none 时需要看到
            // 适配层产出的原始行才能定位解析不命中的原因；保留用于复跑。
            Log.i(TAG, "replaygain(%s) tags=%d gain=%s volume=%.3f mode=%s diag=%s".format(
                source,
                lines.size,
                replayGain.parsedGain()?.let { "%.2f dB".format(it.trackGainDb) } ?: "none",
                replayGain.effectiveVolume(),
                replayGain.mode,
                lines.joinToString(";").take(360),
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

    override fun prepare(source: EngineSource, config: EngineConfig): EngineHandle {
        // §61.120（T5-2 前置）：曲库分析增益随 config 注入（标签缺失时的兜底源）。
        replayGain.onLibraryGain(config.trackGainDb)
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
        // §61.139：单曲 prepare 走非队列路径，索引恒 -1。
        _queueIndex.value = -1
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
        // §61.126（G08 一手观察）：ape 走平台硬解长播 ~13.9min 处 codec error
        //（MediaCodecAudioRenderer 自动恢复，进程未断）→ 平台选择器对
        // audio/x-ape 返回空，ape **强制走 FFmpeg 软解**（F3 22 格已验证
        // ffmpeg ape 链路；平台 ape 支持随厂商参差，软解行为确定性优先）。
        // 例外记录：MIUI/高通平台可解 ape（§61.122 分析管线实测）。
        // §61.163（T5-3 路径 B）：DefaultRenderersFactory 子类覆写 buildAudioSink，
        // 经 DefaultAudioSink.Builder 注入声场展宽处理器（AudioEffectsBus 驱动）。
        val renderersFactory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(SpatialWidenProcessor()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // §61.127：ape **强制 FFmpeg 软解**（恢复强制选择器）——深水区第四缺
            //（sample_rate 预置缺失）修复后，ape local/remote 双路径真机 PASS
            //（§61.127），G08 观察的平台硬解长播 codec error（13.9min，§61.125）
            // 就此消除。平台 ape 支持随厂商参差，软解行为跨设备确定性优先。
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType == "audio/x-ape") {
                    emptyList()
                } else {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                }
            }
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
        // §61.302（H0-4）：缓存 key 与回环 URL 解耦——key 固定为 `b2track:<trackId>`。
        // 此前 key=URL 原文，回环 URL 含每 publish 随机生成的 token → 缓存 key 每次
        // 全新 → 256MiB SimpleCache 对远端源跨曲/跨重启命中率恒 0（形同虚设）。
        // 同 trackId 视为同一内容（重签/换镜像由重新扫描刷新，LRU 自然淘汰旧段）。
        val cacheKey = "b2track:" + source.trackId
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
            if (url != null) {
                CacheDataSource.Factory()
                    .setCache(ensureCache(ctx))
                    .setCacheKeyFactory(CacheKeyFactory { cacheKey })
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
            val p = ExoPlayer.Builder(ctx, renderersFactory, mediaSourceFactory)
                // §61.302（H0-5）：LoadControl 显式化——bufferMs 消费为起播阈值
                // （默认 2000ms，原 DefaultLoadControl 2500ms），复播阈值取两倍且
                // 不低于官方默认；min/max 维持官方 50s 不变。原实现全默认且
                // bufferMs 为死参数，缓冲行为无人可调。
                .setLoadControl(loadControl(config.bufferMs))
                .build()
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
            // §61.301（H0-2）：新曲位置真值从 0 起算，清上一曲残留兜底值。
            lastKnownPositionMs = 0L
        }
        // §61.317（方案二）：CUE/APE 大文件后台缓存预热——网络源在播放的同时预读
        // 全文件到 SimpleCache，后续 seek/切曲/同一镜像其他分轨全部走缓存（秒开）。
        // 用户诉求「分段下载」= 此处对上游发起全量 Range 读，CacheDataSource 自动
        // 分段写入缓存。上一曲预热线程 interrupt 取消（防带宽浪费）。
        cacheWarmerThread?.interrupt()
        cacheWarmerThread = if (url != null) {
            Thread({
                try {
                    val warmFactory = CacheDataSource.Factory()
                        .setCache(ensureCache(ctx))
                        .setCacheKeyFactory(CacheKeyFactory { cacheKey })
                        .setUpstreamDataSourceFactory(
                            DefaultDataSource.Factory(
                                ctx,
                                DefaultHttpDataSource.Factory()
                                    .setAllowCrossProtocolRedirects(true),
                            ),
                        )
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    val ds = warmFactory.createDataSource()
                    ds.open(DataSpec(Uri.parse(url!!), 0, C.LENGTH_UNSET.toLong()))
                    val buf = ByteArray(64 * 1024)
                    while (ds.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT &&
                        !Thread.currentThread().isInterrupted
                    ) { /* CacheDataSource writes to cache during read */ }
                    ds.close()
                    Log.i(TAG, "cache warmer complete: $cacheKey")
                } catch (e: Exception) {
                    Log.i(TAG, "cache warmer ended: ${e?.message ?: "interrupted"}")
                }
            }, "CacheWarmer-$cacheKey").also { it.start() }
        } else null
        return handle
    }

    private fun requireActive(handle: EngineHandle) {
        check(activeHandle == handle) {
            "invalid or released EngineHandle(${handle.raw}); active=${activeHandle?.raw}"
        }
    }

    /**
     * §61.302（H0-5）：显式 LoadControl——[EngineConfig.bufferMs] 消费为起播阈值
     * （钳 500~15000ms），复播阈值 = 2×bufferMs 且不低于官方默认 5000ms；
     * min/maxBuffer 维持官方默认 50s（只增不减的填充目标，防缓冲反复跌落）。
     * 参数单一出口，后续 KernelTuning 可否决表只改这里。
     */
    private fun loadControl(bufferMs: Int): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                /* maxBufferMs= */ DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                /* bufferForPlaybackMs= */ bufferMs.coerceIn(500, 15_000),
                /* bufferForPlaybackAfterRebufferMs= §61.317 复播阈值 = 起播阈值（不翻倍，
                15s 起播 15s 复播——2×30s 对大 APE 来说缓冲等待过长）*/
                bufferMs.coerceAtLeast(
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                ),
            )
            .build()

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
        // §61.309（K-02 真机一手）：prepare 期 BUFFERING 事件（34.744）→ prepare 收尾
        // PAUSED（34.755）→ play() 乐观 PLAYING（34.758）在 14ms 内连环覆盖，控制器
        // collect 只见 PLAYING → isBuffering 永不置位 → 节拍器门禁失效 + 系统会话
        // 按 speed=1f 走钟 5s（真机 Avrcp 逐条证据）。真值守卫：媒体尚在 BUFFERING
        // 时不得盲写 PLAYING（READY 收敛点会回正，§61.301）。
        _state.value =
            if (it.playbackState == Player.STATE_BUFFERING) EngineState.BUFFERING
            else EngineState.PLAYING
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
        // §61.301（H0-2）：seek 落点即刻成为已知位置（主线程忙时 positionMs()
        // 超时兜底不回退到 seek 前旧值）。
        lastKnownPositionMs = target
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
        // §61.87：getter 也受单线程约束 → 主线程同步读。
        // §61.301（H0-2）：超时/异常兜底改保留 [lastKnownPositionMs]（-1 作
        // 「本次未读到」哨兵），不再返回 0——缓冲期主线程最忙，瞬跳 00:00 再
        // 弹回 = 「快进了一下」的观感根因之一。
        val pos = onMainSync(1L, -1L) {
            val p = player ?: return@onMainSync -1L
            val v = p.currentPosition
            if (v == C.TIME_UNSET || v < 0) -1L else v
        }
        return if (pos >= 0L) {
            lastKnownPositionMs = pos
            pos
        } else {
            lastKnownPositionMs
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

    /** §61.139：队列模式下的当前 MediaItem 索引（非队列恒 -1）。 */
    override fun queueIndex(): StateFlow<Int> = _queueIndex.asStateFlow()

    /** §61.139：队列内跳播——ExoPlayer 原生 seekTo(itemIndex)，不重建 player。 */
    override fun seekToQueueIndex(handle: EngineHandle, index: Int) = guard(handle) {
        val count = it.mediaItemCount
        if (count > 0 && index in 0 until count) {
            it.seekTo(index, 0L)
            _queueIndex.value = index
            lastKnownPositionMs = 0L   // §61.301（H0-2）：换曲位置真值归零。
        }
    }

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

    /**
     * §61.118（T5-1 capstone）：分频段均衡器电平。equalizer 实例未挂载时静默
     * 忽略（attachAudioEffect 先行）；band 数不匹配取 min；millibel 钳制
     * [-1500, 1500]（系统 Equalizer 合法范围 ±15dB）。
     */
    /**
     * §61.131（gapless 队列化施工单）：多曲目 gapless 播放——B2 走 ExoPlayer
     * 原生 playlist（setMediaItems），同格式相邻曲目无缝衔接。
     * 复用 prepare() 的 renderersFactory/mediaSourceFactory/cache 逻辑；
     * startIndex 为 ExoPlayer 的起始 MediaItem 索引。
     * 单曲路径（prepare）零回归——PlayerController 按队列大小分支。
     */
    override fun prepareQueue(
        sources: List<EngineSource>,
        startIndex: Int,
        config: EngineConfig,
    ): EngineHandle {
        check(activeHandle == null) {
            "single handle invariant: release() before a new prepareQueue() (03-06 §4)"
        }
        val ctx = appContext
            ?: throw CloudMusicError.DsUpstream(
                "MEDIA3_CONTEXT", "appContext 未注入")
        error = null
        replayGain.reset()
        // §61.139 / §61.120：库值增益只对起播曲注入；切曲时由 listener 清空。
        replayGain.onLibraryGain(config.trackGainDb)
        _queueIndex.value = startIndex.coerceIn(0, sources.lastIndex)
        val items = sources.map { source ->
            val uri: Uri = when {
                source.url != null -> Uri.parse(source.url)
                source.localPath != null -> Uri.fromFile(File(source.localPath))
                else -> throw CloudMusicError.DsUpstream(
                    "MEDIA3_SOURCE", "EngineSource 既无 url 也无 localPath")
            }
            MediaItem.Builder().setUri(uri).setMediaId(source.trackId).build()
        }
        val first = sources.first()
        Log.i(TAG, "prepareQueue count=%d startIndex=%d first=%s".format(
            sources.size, startIndex, first.trackId))
        // §61.139 施工单步骤 4：队列路径传入真实判据，cross_track 可达。
        val gapless = GaplessPolicy.assess(
            playerReused = true,
            queuedItemCount = sources.size,
            pauseAtEndOfMediaItems = false,
        )
        Log.i(TAG, "gapless queue in_file=%s cross_track=%s blocker=%s".format(
            gapless.inFileTrimmingAvailable,
            gapless.crossTrackAvailable,
            gapless.blocker ?: "none",
        ))
        // §61.163（T5-3 路径 B）：DefaultRenderersFactory 子类覆写 buildAudioSink，
        // 经 DefaultAudioSink.Builder 注入声场展宽处理器（AudioEffectsBus 驱动）。
        val renderersFactory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(SpatialWidenProcessor()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                if (mimeType == "audio/x-ape") emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
        // 队列播放用 DefaultDataSource.Factory（同时处理 local 与 http，
        // 不做 CacheDataSource 包装——gapless 场景缓存收益低于复杂度成本）。
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(ctx)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            androidx.media3.extractor.ape.ApeExtractor.factoryWithApe(),
        )
        val handle = EngineHandle(nextRaw++)
        onMainSync(10L, Unit) {
            val p = ExoPlayer.Builder(ctx, renderersFactory, mediaSourceFactory)
                // §61.302（H0-5）：队列路径同口径显式 LoadControl（本地源为主，
                // bufferMs 语义一致）。
                .setLoadControl(loadControl(config.bufferMs))
                .build()
            player = p
            p.addListener(listener)
            p.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            p.setPauseAtEndOfMediaItems(false)
            Log.i(TAG, "gapless playlist: %d items startIndex=%d".format(items.size, startIndex))
            p.prepare()
            activeHandle = handle
            _state.value = EngineState.PAUSED
        }
        return handle
    }

    override fun setEqualizerBands(handle: EngineHandle, levelsMillibel: List<Int>) {
        requireActive(handle)
        onMainSync(5L, Unit) {
            val eq = equalizer ?: return@onMainSync
            runCatching {
                val n = eq.numberOfBands.toInt()
                levelsMillibel.take(n).forEachIndexed { i, level ->
                    eq.setBandLevel(i.toShort(), level.coerceIn(-1500, 1500).toShort())
                }
                Log.i(TAG, "eq bands applied=%d/%d".format(
                    minOf(levelsMillibel.size, n), n))
            }.onFailure {
                Log.i(TAG, "eq bands failed: %s".format(it.message))
            }
        }
    }

    override fun release(handle: EngineHandle) {
        requireActive(handle)
        Log.i(TAG, "release handle=%d".format(handle.raw))
        cacheWarmerThread?.interrupt() // §61.317：释放时取消预热线程
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
        _queueIndex.value = -1
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
