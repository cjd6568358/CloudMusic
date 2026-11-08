package com.example.cloudmusic.di.playback

import android.util.Log
import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.di.adapters.PrePublishedCastProxy
import com.example.cloudmusic.di.scan.SettingsEffectsRunner
import com.example.cloudmusic.di.settings.SettingsEffectsBus
import com.example.cloudmusic.di.settings.SettingsProjection
import com.example.cloudmusic.di.settings.SettingsStore
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.output.dlna.LinkProvider
import com.example.cloudmusic.output.localproxy.ProxyStartPolicy
import com.example.cloudmusic.player.PlayerController
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.player.QueueSync
import com.example.cloudmusic.player.TrackLookup
import com.example.cloudmusic.player.TrackSourceResolver
import com.example.cloudmusic.scanner.ScanCoordinator
import com.example.cloudmusic.scanner.ScanProgress
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Room 是曲库唯一真相源，播放编排层按 TrackId 反查即可（03-05 / 03-06 §11）。 */
class RoomTrackLookup(private val tracks: TrackRepository) : TrackLookup {
    override suspend fun get(id: TrackId): Track? = tracks.get(id)
}

/**
 * V0.1 的 native 队列仍是**只读镜像**（03-06 §8 / T-L L1 待落地）：
 * 这里只记录每次「全量、单向」下发，禁止反向回写业务队列。
 * 照搬舱落地后只需替换本 `@Provides`，上层零改动（D-08 同型）。
 */
class SandboxQueueSync : QueueSync {
    @Volatile
    var lastPush: Pair<List<QueueItem>, PlayMode>? = null
        private set

    override suspend fun push(items: List<QueueItem>, mode: PlayMode, startAt: Int) {
        lastPush = items.toList() to mode
    }
}

/**
 * 03-06 §9 `resolve` 的生产实现：
 * - `URL` / `LOCAL` 态按 `RegistryLinkProvider` 重建的 Link 原样交给 `EngineSource`（C-05）；
 * - `PROXIED` / `TEXT` 态先 `proxy.publish(trackId, link)` 拿**局域网代理 URL**，
 *   复制成 URL 态 Link 再返回，供引擎回环自取（16 §3.2 触发器 ④）。
 * 原始 PROXIED Link（含 C-07 `proxySourceUrl` 与鉴权头）仍交给代理回源，二者不混淆。
 */
class ProductionTrackSourceResolver(
    private val links: LinkProvider,
    private val proxy: PrePublishedCastProxy,
) : TrackSourceResolver {
    override suspend fun resolve(track: Track): Link {
        val link = links.link(track)
        return when (link.kind) {
            Link.Kind.PROXIED, Link.Kind.TEXT -> {
                val url = proxy.publish(track.id.value, link)
                link.copy(url = url, proxy = false, requireReference = false)
            }
            else -> link
        }
    }
}

/** Service 通知 / 诊断用的最小运行时状态，不承载播放状态（由 PlayerController.state 提供）。 */
data class RuntimeState(
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val errors: Int = 0,
    val lastEffect: String = "",
    val proxyRunning: Boolean = false,
)

/**
 * T-A5（`12 §31`）· `PlaybackService` 与 `:core-di` 之间唯一的编排入口。
 *
 * 把四件此前「有对象、无调用方」的事接起来：
 * ① 冷启动轻量增量扫描（[ScanCoordinator.scan] 是冷流，必须长期 collect）；
 * ② 消费 [SettingsEffectsBus] → [SettingsEffectsRunner.apply]（C-14 的 effects 执行者）；
 * ③ `ProxyStartPolicy.shouldStart()` 的生产调用方（03-08 §8.4 ①~⑤，含 C-06 ④）；
 * ④ 持有 [PlayerController]，供媒体按键 / 通知动作转发。
 *
 * 边界（第二十七批 `12 §36` 起）：`IMusicEngine` **已换绑舱内 `EngineAdapter`**
 * （D-08 只改 `EngineModule` 的一个 `@Provides`，本类零改动）；真机 G01/G02 与
 * native 回调字段校准仍属 T-L L1，**编译 / DI 接通 ≠ 真机出声**。
 */
@Singleton
class PlaybackRuntime @Inject constructor(
    private val coordinator: ScanCoordinator,
    private val effects: SettingsEffectsRunner,
    private val bus: SettingsEffectsBus,
    private val controller: PlayerController,
    private val queueRepository: QueueRepository,
    private val proxy: PrePublishedCastProxy,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile
    private var scanJob: Job? = null

    fun start() {
        // 12 §50（真机 A3 暴露）：03-06 §8.2 的冷启动恢复此前只有单测调用方，
        // 生产侧从未接线 → Room 里的队列 / 光标永远不会被读，engine.prepare() 也不会触发。
        // 恢复只 prepare + seek，保持 PAUSED（PRD §6.1 不自动开播）。
        scope.launch {
            val snapshot = queueRepository.restore() ?: return@launch
            val items = queueRepository.observe().first()
            runCatching { controller.restore(snapshot, items) }
                .onFailure { Log.w("CloudMusicRuntime", "cold-start restore failed", it) }
        }
        scope.launch {
            bus.updates.collect { update ->
                val actions = runCatching { effects.apply(update) }.getOrDefault(emptySet())
                _state.update {
                    it.copy(lastEffect = actions.joinToString(",") { action -> action.name })
                }
            }
        }
        runScan(ScanRequest())   // 03-04 §4：App 启动 → 轻量增量（≤10s）
        _state.update { it.copy(proxyRunning = proxy.isRunning()) }
    }

    /** 手动「重新扫描」/ 设置变更后的全量重扫入口。 */
    fun rescanAll() = runScan(ScanRequest(refresh = true))

    /** 网络恢复 / 重试失败项：C-14 定向增量。 */
    fun retryUnavailable() = runScan(ScanRequest(onlyUnavailable = true))

    private fun runScan(request: ScanRequest) {
        scanJob?.cancel()
        scanJob = scope.launch {
            _state.update { it.copy(scanning = true, scanned = 0, total = 0, errors = 0) }
            try {
                coordinator.scan(request).collect { progress: ScanProgress ->
                    _state.update {
                        it.copy(
                            scanned = progress.scanned,
                            total = progress.total,
                            errors = progress.errors.size,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(lastEffect = "SCAN_FAILED") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    /**
     * C-13 / C-06：本机播放 `proxy` 态曲目时必须启动代理（03-08 §8.4 ④）；
     * 纯直链队列 / 本机 url 态不启动，省电目标不变。
     */
    fun ensureProxy(localPlaybackKind: Link.Kind?) {
        val shouldStart = ProxyStartPolicy.shouldStart(
            queueKinds = emptyList(),
            anyViaProxy = false,
            fallbackNeeded = false,
            settings = SettingsProjection.proxy(settings.current()),
            localPlaybackKind = localPlaybackKind,
        )
        if (shouldStart) proxy.ensureStarted()
        _state.update { it.copy(proxyRunning = proxy.isRunning()) }
    }

    fun play() = scope.launch { controller.resume() }

    /** 03-02 §344：供 PlaybackService 同步判定闸门，避免拒绝后会话假报 PLAYING。 */
    fun currentNetworkSource(): Boolean = controller.currentSourceIsNetwork()

    fun pause() = scope.launch { controller.pause() }
    fun next() = scope.launch { controller.next() }
    fun prev() = scope.launch { controller.prev() }

    /** §61.26（G03 验收）：外部 seek 入口（通知栏进度条 / 验收 intent 共用同一裁决）。 */
    fun seekTo(ms: Long) = scope.launch { controller.seek(ms) }

    /** T-A6（12 §33）：UI 需要具体类型来显示播放阶段 / 当前曲目 / 进度。 */
    fun playerState(): StateFlow<PlayerState> = controller.state

    fun stop() {
        scanJob?.cancel()
        scope.cancel()
        proxy.stop()
        _state.update { it.copy(proxyRunning = false, scanning = false) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun provideTrackLookup(tracks: TrackRepository): TrackLookup = RoomTrackLookup(tracks)

    @Provides
    @Singleton
    fun provideQueueSync(): QueueSync = SandboxQueueSync()

    /** 03-02 §344：把 Android 平台实现绑定到纯 JVM 端口（零新增权限）。 */
    @Provides
    @Singleton
    fun provideNetworkPlaybackGate(impl: AndroidNetworkPlaybackGate): NetworkPlaybackGate = impl

    @Provides
    @Singleton
    fun provideTrackSourceResolver(
        links: LinkProvider,
        proxy: PrePublishedCastProxy,
    ): TrackSourceResolver = ProductionTrackSourceResolver(links, proxy)

    @Provides
    @Singleton
    fun providePlayerController(
        engine: IMusicEngine,
        sync: QueueSync,
        lookup: TrackLookup,
        resolver: TrackSourceResolver,
        media: IMediaControl,
        settings: SettingsStore,
        networkGate: NetworkPlaybackGate,
    ): PlayerController = PlayerController(
        engine = engine,
        sync = sync,
        lookup = lookup,
        resolver = resolver,
        media = media,
        config = SettingsProjection.playerConfig(settings.current()),
        networkGate = networkGate,
    )
}
