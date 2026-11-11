package com.example.cloudmusic.engineopensource

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
 * docs/12 §61.81 / B2 M1 · `IMusicEngine` 的 Media3/ExoPlayer 实现骨架。
 *
 * 边界（**M1 骨架 ≠ B2 完成**）：
 * - 门面 [IMusicEngine] 14 方法签名逐字不变（03-06 §4），只新增实现；
 *   单点换绑在 `EngineModule.provideMusicEngine`，`:engine-legacy` /
 *   `EngineAdapter` 与 18 个私有 so 保留为回退（C5 本批不移除）。
 * - 本批只接 `url` / `localPath` 的官方 `MediaItem` + `ExoPlayer` 基础播放；
 *   `RangeCacheDataSource`（自研缓存 / Range / 代理回落）属 M3，此处只保存
 *   [IDataSource] 引用，**不伪造接线**。
 * - `setQuality` 与 `attachAudioEffect` 按 C7 默认假设留空实现并挂账
 *   （自研 DSP / 103 JNI 音效链不在 B2 复刻范围，基础 EQ 属 M4 / P4）。
 * - [EngineConfig] 的 `sampleRateHz` / `preferHardwareDecoder` / `bufferMs` /
 *   `autoSkipOnError` 本批不消费：平台 MediaCodec 硬解优先、缓冲由 ExoPlayer
 *   管理；精确语义属 M2 / M4，不写成「已支持」。
 * - 媒体会话 / 音频焦点 / 媒体按键**不**由 Media3 主导（C1），仍走
 *   `:core-media` + `:media-legacy` + 平台 API；本模块不引 `media3-session`。
 *
 * 状态机：PREPARING / PLAYING / PAUSED / IDLE 由门面调用即时驱动（与
 * `InMemoryMusicEngine` / `EngineAdapter` 一致），ENDED / ERROR 由
 * [Player.Listener] 驱动；M4 再按 §61.74 / §61.75 多信号口径收紧。
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

        override fun onPlayerError(e: PlaybackException) {
            Log.i(TAG, "error=%s code=%s".format(e.message ?: "", e.errorCodeName))
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
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(source.trackId)
            .apply {
                // mimeHint 可能是 MIME（audio/mpeg）或真实扩展名（mp3，F2-b）。
                // 只在确认为 MIME 时透传，其余交给 Media3 内容探测，不编造。
                if (source.mimeHint.contains('/')) setMimeType(source.mimeHint)
            }
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
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        // §61.87：ExoPlayer 的构建 + prepare 必须在 application thread（主 Looper）。
        onMainSync(10L, Unit) {
            val p = ExoPlayer.Builder(ctx, renderersFactory, mediaSourceFactory).build()
            p.addListener(listener)
            p.setMediaItem(item)
            p.prepare()
            player = p
            activeHandle = handle
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
        // M3 才把 IDataSource 桥接成 RangeCacheDataSource（官方 DataSource 语义）。
        // 本批只保存引用并在 release 时关闭，不伪造缓存 / Range 接线。
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
        it.seekTo(target)
    }

    override fun setVolume(handle: EngineHandle, volume: Float) {
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        guard(handle) { it.volume = volume }
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
     * C7 音效降级：自研 PCM DSP 链（103 JNI / EQ / NCAE / MNN）不在 B2 复刻范围，
     * 本批留空实现并挂账 P4；基础 EQ / `android.media.audiofx.Equalizer` 属 M4。
     */
    override fun attachAudioEffect(handle: EngineHandle, effectId: Int) {
        requireActive(handle)
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
