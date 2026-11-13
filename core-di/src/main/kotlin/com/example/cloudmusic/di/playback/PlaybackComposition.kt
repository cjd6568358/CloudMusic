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
import com.example.cloudmusic.di.scan.CoverPolicy
import com.example.cloudmusic.di.scan.CoverWriterPort
import com.example.cloudmusic.di.scan.JAudioTaggerPort
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
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.player.QueueSync
import com.example.cloudmusic.player.TrackLookup
import com.example.cloudmusic.player.TrackSourceResolver
import com.example.cloudmusic.scanner.ScanCoordinator
import com.example.cloudmusic.scanner.ScanProgress
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.PlaylistRepository
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
import kotlinx.coroutines.flow.Flow
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
 * 边界（第二十七批 `12 §36` 起）：`IMusicEngine` 换绑舱内实现只改 `EngineModule`
 * 的一个 `@Provides`，本类零改动——先是 `EngineAdapter`（D-08，A 路线），
 * 11-11 起为 `Media3EngineAdapter`（B2 开源引擎，`12 §61.65~61.66`，门面 14
 * 方法签名 0 改动 0 提交）；本类至今仍只引用 `IMusicEngine` 契约。
 * **编译 / DI 接通 ≠ 真机出声**（现行真机口径见 `12 §61.106`）。
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
    /** §61.119（M3）：歌单闭环的落库口。 */
    private val playlists: PlaylistRepository,
    private val proxy: PrePublishedCastProxy,
    private val settings: SettingsStore,
    /** §61.112：playLocal 调试注入复用扫描管线的真实标签 / 封面件（03-04 §6）。 */
    private val tagReader: JAudioTaggerPort,
    private val coverWriter: CoverWriterPort,
    /** §61.116（M6-3）：缓存清理需要 cacheDir 路径。 */
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RuntimeState())

    init {
        // §61.118：换曲后按持久值恢复均衡器（T5-1 持久化的生产执行点）。
        restoreEqualizerLoop()
    }
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    @Volatile
    private var scanJob: Job? = null

    fun start() {
        // 12 §50（真机 A3 暴露）：03-06 §8.2 的冷启动恢复此前只有单测调用方，
        // 生产侧从未接线 → Room 里的队列 / 光标永远不会被读，engine.prepare() 也不会触发。
        // 恢复只 prepare + seek，保持 PAUSED（PRD §6.1 不自动开播）。
        scope.launch {
            val snapshot = queueRepository.restore() ?: run {
                Log.w("CloudMusicRuntime", "cold-start restore: playback_state 缺失，跳过恢复")
                return@launch
            }
            val items = queueRepository.observe().first()
            if (items.isEmpty()) {
                // §61.94（真机排障）：空队列恢复曾静默 no-op（无任何日志），外部触发
                // play 时表现为「假 PLAYING」（session 态更新但引擎零动作）。此处显式
                // 留痕，让恢复链的每一条出口都可观测。
                Log.w("CloudMusicRuntime", "cold-start restore: 队列为空，保持 PAUSED")
                return@launch
            }
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
     * 权限 / 依赖 / 模块，P0 引擎舱（EngineModule 的 `@Provides` 换绑点）零改动；
     * 生产形态随 `12 §61.33` 的 `ACTION_PLAY` 一并收口。
     * §61.108 口径注：该批历史表述「20 so / EngineAdapter 零改动」是当时事实；
     * 当前舱内生产绑定为 `Media3EngineAdapter`（B2，`12 §61.65~61.66`），
     * 本入口对两种绑定同样生效（只经 `IMusicEngine` 契约）。
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
        // §61.112：此前手工构造 Track（durationMs=0 / 无 coverRef），真机一手证据
        // 是播放页进度条 00:00/00:00 与唱片永远回退红盘。改为复用扫描管线同一套
        // 真实标签链（JAudioTaggerFilePort 读 durationMs/封面，AndroidCoverWriter
        // 落盘 512×512 WebP 回 coverRef）；任一失败回落原最小构造，零回归。
        val trackId = TrackId.of(LOCAL_FIXTURE_STORAGE_ID, name)
        val track = runCatching {
            val tags = tagReader.read(file.absolutePath)
            val coverRef = tags.embeddedCover
                ?.takeIf { CoverPolicy.accepts(it) }
                ?.let { coverWriter.write(trackId.value, it) }
            Track(
                id = trackId,
                title = tags.title?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.', name),
                artist = tags.artist?.takeIf { it.isNotBlank() } ?: "本地音源",
                album = tags.album?.takeIf { it.isNotBlank() } ?: "零权限注入",
                durationMs = tags.durationMs ?: 0L,
                sourceRef = SourceRef(storageId = LOCAL_FIXTURE_STORAGE_ID, path = name, url = null),
                sourceType = SourceType.LOCAL,
                coverRef = coverRef,
            )
        }.getOrElse {
            Log.w("CloudMusicRuntime", "playLocal: 标签读取失败，回落最小构造", it)
            Track(
                id = trackId,
                title = name.substringBeforeLast('.', name),
                artist = "本地音源",
                album = "零权限注入",
                durationMs = 0L,
                sourceRef = SourceRef(storageId = LOCAL_FIXTURE_STORAGE_ID, path = name, url = null),
                sourceType = SourceType.LOCAL,
            )
        }
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
    /**
     * §61.113（M2-1）：曲库分组视角的播放入口——把分组曲目设为业务队列并从
     * [startAt] 起播（正常音乐应用语义：点专辑内曲目 = 队列替换为该专辑）。
     * 曲目已由扫描落库（不再 upsert）；网络闸门在 controller/Service 层照常生效。
     */
    fun playTracks(items: List<Track>, startAt: Int) {
        if (items.isEmpty()) return
        scope.launch {
            runCatching {
                queueRepository.setAll(items)
                val queueItems = queueRepository.observe().first()
                controller.setQueue(queueItems, PlayMode.SEQUENCE, startAt)
                controller.playAt(startAt)
            }.onFailure { Log.w("CloudMusicRuntime", "playTracks: 分组播放失败", it) }
        }
    }

    /** §61.119（M8）：队列点击跳播——当前队列第 [index] 项起播。 */
    fun jumpTo(index: Int) = scope.launch {
        runCatching { controller.playAt(index) }
            .onFailure { Log.w("CloudMusicRuntime", "jumpTo 失败", it) }
    }

    /** §61.119（M3）：创建歌单，返回 id（经回调交付 UI）。 */
    fun createPlaylist(name: String, onCreated: (String) -> Unit = {}) {
        scope.launch {
            runCatching { playlists.create(name) }
                .onSuccess { id -> onCreated(id) }
                .onFailure { Log.w("CloudMusicRuntime", "createPlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：加入歌单（跨来源混合，契约不变）。 */
    fun addToPlaylist(playlistId: String, trackId: TrackId) {
        scope.launch {
            runCatching { playlists.addTracks(playlistId, listOf(trackId)) }
                .onFailure { Log.w("CloudMusicRuntime", "addToPlaylist 失败", it) }
        }
    }

    /** §61.121（M3 小账）：歌单内曲目移动（契约 move 收口，removeAt/add 语义）。 */
    fun moveInPlaylist(id: String, from: Int, to: Int) {
        scope.launch {
            runCatching { playlists.move(id, from, to) }
                .onFailure { Log.w("CloudMusicRuntime", "moveInPlaylist 失败", it) }
        }
    }

    /** §61.121（M3 小账）：歌单内移除单曲（契约 remove 收口）。 */
    fun removeFromPlaylist(id: String, position: Int) {
        scope.launch {
            runCatching { playlists.remove(id, position) }
                .onFailure { Log.w("CloudMusicRuntime", "removeFromPlaylist 失败", it) }
        }
    }

    /** §61.121：歌单曲目流透传（UI 展开视图取数；03-10 §11 #4 经 runtime 通道）。 */
    fun observePlaylistTracks(id: String): Flow<List<Track>> =
        playlists.observeTracks(id)

    /** §61.120（M3 小账）：重命名歌单（契约 rename 收口）。 */
    fun renamePlaylist(id: String, name: String) {
        scope.launch {
            runCatching { playlists.rename(id, name) }
                .onFailure { Log.w("CloudMusicRuntime", "renamePlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：删除歌单。 */
    fun deletePlaylist(id: String) {
        scope.launch {
            runCatching { playlists.delete(id) }
                .onFailure { Log.w("CloudMusicRuntime", "deletePlaylist 失败", it) }
        }
    }

    /** §61.119（M3）：播放歌单——歌单曲目替换业务队列并从头起播。 */
    fun playPlaylist(id: String) {
        scope.launch {
            runCatching {
                val list = playlists.observeTracks(id).first()
                if (list.isEmpty()) return@runCatching
                queueRepository.setAll(list)
                val queueItems = queueRepository.observe().first()
                controller.setQueue(queueItems, PlayMode.SEQUENCE, 0)
                controller.playAt(0)
            }.onFailure { Log.w("CloudMusicRuntime", "playPlaylist 失败", it) }
        }
    }

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

    /** §61.93（音效必须支持）：外部音效档位入口（0 = 释放基础音效链）。 */
    fun attachEffect(effectId: Int) = scope.launch { controller.attachEffect(effectId) }

    /**
     * §61.118（T5-1）：均衡器即时下发 + 持久化（AudiofxSettings）。
     * enabled=false 走 attachEffect(0) 释放；换曲恢复由 [restoreEqualizerLoop] 收集器。
     */
    fun applyEqualizer(enabled: Boolean, effectLevel: Int, bands: List<Int>) {
        scope.launch {
            runCatching {
                if (enabled) {
                    if (effectLevel > 0) controller.attachEffect(effectLevel)
                    if (bands.isNotEmpty()) controller.setEqualizerBands(bands)
                } else {
                    controller.attachEffect(0)
                }
                settings.update {
                    it.copy(audiofx = com.example.cloudmusic.storage.AudiofxSettings(
                        enabled = enabled,
                        effectLevel = effectLevel,
                        bandsMillibel = bands,
                    ))
                }
            }.onFailure { Log.w("CloudMusicRuntime", "applyEqualizer 失败", it) }
        }
    }

    /** §61.118：换曲后按持久值恢复均衡器（READY 且曲目变化时一次性应用）。 */
    private fun restoreEqualizerLoop() {
        scope.launch {
            var appliedFor: String? = null
            controller.state.collect { s ->
                val fx = settings.current().audiofx
                if (!fx.enabled) return@collect
                val trackKey = s.currentTrackId?.value
                if (s.phase == PlayerPhase.PLAYING &&
                    trackKey != null && trackKey != appliedFor
                ) {
                    appliedFor = trackKey
                    if (fx.effectLevel > 0) controller.attachEffect(fx.effectLevel)
                    if (fx.bandsMillibel.isNotEmpty()) {
                        controller.setEqualizerBands(fx.bandsMillibel)
                    }
                }
            }
        }
    }

    /**
     * §61.116（M6-3 / 子清单 #6）：清理 B2 播放缓存（`cacheDir/b2_media_cache`，
     * SimpleCache 256MiB LRU）。播放 / 预备中直接拒绝（SimpleCache 实例在引擎内
     * 活跃写盘，删一半会造成 CacheDataSource 读错窗口）；返回释放字节数，
     * -1 = 播放中拒绝，-2 = 目录不存在。索引库（StandaloneDatabaseProvider）
     * 保留——Media3 对缺失 span 走 FLAG_IGNORE_CACHE_ON_ERROR 直连回落。
     */
    fun clearMediaCache(): Long {
        val phase = controller.state.value.phase
        if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.PREPARING) return -1L
        val dir = java.io.File(appContext.cacheDir, "b2_media_cache")
        if (!dir.isDirectory) return -2L
        var freed = 0L
        dir.walkBottomUp().filter { it.isFile }.forEach { f ->
            freed += f.length()
            runCatching { f.delete() }
        }
        Log.i("CloudMusicRuntime", "clearMediaCache: freed %d bytes".format(freed))
        return freed
    }

    /**
     * §61.110：输出切换的另一半契约（03-08 §14 #9 / 03-09 §7 成对联动）——
     * 切 DLNA 本机内核静音 + 媒体控件停用（T09），回落恢复。与
     * `OutputRouter.setActive` 必须成对调用（守护脚本钉死计数相等）。
     */
    fun onOutputChanged(dlna: Boolean) = controller.onOutputChanged(dlna)

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
