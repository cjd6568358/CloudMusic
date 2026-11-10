package com.example.cloudmusic.di.playback

import android.util.Log
import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.common.PathNormalizer
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.local.LocalChannel
import com.example.cloudmusic.local.LocalDirectorySource
import com.example.cloudmusic.local.LocalDriver
import com.example.cloudmusic.local.LocalEntry
import com.example.cloudmusic.local.LocalRoot
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Storage
import java.io.File
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

/**
 * 12 §61.47 / F2：验收期零权限本地曲库注入的固定 Storage id。
 * 与 DataStore 中任何真实 Storage 都不冲突；仅供 adb 注入通道使用。
 */
private const val LOCAL_FIXTURE_STORAGE_ID = "debug-local-fixture"

/**
 * 12 §61.49 / F3：REMOTE 注入用的 storageId。**故意不注册 DriverRegistry 实例** ——
 * `RegistryLinkProvider.link()` 在查不到 driver 时会按 `SourceRef.url` 产出
 * `Link(url=…)`，进而由 `engineSourceOf()` 得到 `EngineSource(url=…, localPath=null)`，
 * 这正是 F3 需要验证的 REMOTE 取流路径（与 §61.47 的 LOCAL 路径互补）。
 */
private const val REMOTE_FIXTURE_STORAGE_ID = "debug-remote-fixture"

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
    /** 12 §61.47：F2 零权限本地曲库注入时把曲目写进 Room，使曲库 / 队列真实可见。 */
    private val tracks: TrackRepository,
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

    /**
     * 12 §61.47 / F2 真机取证通道：**零权限本地曲库注入**。
     *
     * 真机实证：`READ_MEDIA_AUDIO` 恒 `granted=false`，MIUI 无线 adb 拒绝 `pm grant`
     * （`GRANT_RUNTIME_PERMISSIONS`）与 `input tap`（`INJECT_EVENTS`）→ UI 上的
     * 「添加媒体库 / SAF 目录」入口无法由脚本触发；`DataStore.storages` 恒空 →
     * `ScanCoordinator` 无数据源可扫、曲库 0 首，且 `RegistryLinkProvider` 因
     * `DriverRegistry` 无实例而无法把 Track 解析成 `Link.Kind.LOCAL`。
     *
     * 本入口把音频放进 App 私有目录（`run-as ... cp`，无需任何读取权限），由 adb
     * `ACTION_PLAY --es ...LOCAL_PATH <path>` 注入：
     * ① 注册以该目录为沙箱根的 `LocalDriver` 实例（`java.io.File` 实现
     *    `LocalDirectorySource`，零权限），使播放链能产出 `Link.Kind.LOCAL`；
     * ② `TrackRepository.upsertBatch` + `QueueRepository.setAll` 让曲库 / 队列
     *    **真实有曲目**（不是只读夹具，UI 可见）；
     * ③ 走 `PlayerController.setQueue / playAt` 完整生产链 →
     *    `EngineSource(localPath=…, url=null)`，真机日志可对照
     *    `resourceInfo extension=...` 与 `Open codec` 判定 F2 假设。
     *
     * 这是**验收期调试入口**：复用既有 `ACTION_PLAY`，不新增 Manifest action /
     * 权限 / 依赖 / 模块，P0 引擎舱（20 so / EngineAdapter / EngineModule）零改动；
     * 生产形态随 `12 §61.33` 的 `ACTION_PLAY` 一并收口。
     */
    fun playLocal(path: String) = scope.launch {
        val file = File(path)
        if (!file.isFile) {
            Log.w("CloudMusicRuntime", "playLocal: 文件不存在或不是普通文件")
            return@launch
        }
        val root = file.parentFile?.absolutePath ?: return@launch
        runCatching { ensureLocalFixtureDriver(root) }
            .onFailure { Log.w("CloudMusicRuntime", "playLocal: driver 注册失败", it) }
        val name = file.name
        val track = Track(
            id = TrackId.of(LOCAL_FIXTURE_STORAGE_ID, name),
            title = name.substringBeforeLast('.', name),
            artist = "本地音源",
            album = "零权限注入",
            durationMs = 0L,
            sourceRef = SourceRef(storageId = LOCAL_FIXTURE_STORAGE_ID, path = name, url = null),
            sourceType = SourceType.LOCAL,
        )
        runCatching {
            tracks.upsertBatch(listOf(track))
            queueRepository.setAll(listOf(track))
            val items = queueRepository.observe().first()
            controller.setQueue(items, PlayMode.SEQUENCE, 0)
            controller.playAt(0)
        }.onFailure { Log.w("CloudMusicRuntime", "playLocal: 注入播放失败", it) }
    }

    /**
     * 12 §61.49 / F3：`ACTION_PLAY` + `EXTRA_SOURCE_URL` 的 REMOTE 注入（验收期调试入口）。
     *
     * 不注册 driver、不碰 `DriverRegistry`，让 `RegistryLinkProvider` 走 `ref.url`
     * 直推分支；URL 后缀保留真实扩展名，使 F2-b 的 `AudioFormats.extOfSource` 能给出
     * 与 LOCAL 路径同源的结果。曲库 / 队列仍经 `upsertBatch` / `setAll` 真实落库，
     * 与 `playLocal` 完全对称。生产形态随 `12 §61.33` 收口。
     */
    fun playUrl(url: String) = scope.launch {
        val name = url.substringBefore('?').substringAfterLast('/')
        val track = Track(
            id = TrackId.of(REMOTE_FIXTURE_STORAGE_ID, name),
            title = name.substringBeforeLast('.', name),
            artist = "回环音源",
            album = "F3 双路径矩阵",
            durationMs = 0L,
            sourceRef = SourceRef(
                storageId = REMOTE_FIXTURE_STORAGE_ID, path = name, url = url,
            ),
            sourceType = SourceType.STRM,
        )
        runCatching {
            tracks.upsertBatch(listOf(track))
            queueRepository.setAll(listOf(track))
            val items = queueRepository.observe().first()
            controller.setQueue(items, PlayMode.SEQUENCE, 0)
            controller.playAt(0)
        }.onFailure { Log.w("CloudMusicRuntime", "playUrl: 注入播放失败", it) }
    }

    /** 以 App 私有目录为沙箱根，用平台 `java.io.File` 实现 `LocalDirectorySource`（零权限）。 */
    private suspend fun ensureLocalFixtureDriver(root: String) {
        if (DriverRegistry.get(LOCAL_FIXTURE_STORAGE_ID) != null) return
        val escaped = root.replace("\\", "\\\\").replace("\"", "\\\"")
        val additionJson = "{\"root_folder_path\":\"$escaped\",\"show_hidden\":true}"
        val storage = Storage(
            id = LOCAL_FIXTURE_STORAGE_ID,
            mountPath = "/local",
            driverType = "local",
            addition = Addition(additionJson),
        )
        val driver = LocalDriver(
            storage = storage,
            additionJson = additionJson,
            saf = null,
            mediaStore = FileDirectorySource,
        )
        driver.init(DriverCtx(tag = "[play:local]", caller = "play"))
        DriverRegistry.put(LOCAL_FIXTURE_STORAGE_ID, driver)
    }

    private object FileDirectorySource : LocalDirectorySource {
        override val channel: LocalChannel = LocalChannel.MEDIA_STORE

        override suspend fun list(root: LocalRoot, absolutePath: String): List<LocalEntry> =
            File(absolutePath).listFiles().orEmpty().map(::entry)

        override suspend fun find(root: LocalRoot, absolutePath: String): LocalEntry? {
            val f = File(absolutePath)
            return if (f.isFile) entry(f) else null
        }

        private fun entry(f: File): LocalEntry = LocalEntry(
            name = f.name,
            path = PathNormalizer.normalize(f.absolutePath),
            isDir = f.isDirectory,
            size = if (f.isFile) f.length() else 0L,
            modified = f.lastModified() / 1000L,
            uri = f.absolutePath,
        )
    }

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
