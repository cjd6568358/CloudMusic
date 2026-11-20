package com.example.cloudmusic.di.playback

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import com.example.cloudmusic.output.router.ActiveOutput
import com.example.cloudmusic.output.router.OutputRouter
import com.example.cloudmusic.player.PlayerController
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.PlayMode
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.player.QueueSync
import com.example.cloudmusic.player.TrackLookup
import com.example.cloudmusic.player.TrackSourceResolver
import com.example.cloudmusic.player.TrackDurationWriter
import com.example.cloudmusic.player.CursorWriter
import com.example.cloudmusic.scanner.NameParser
import com.example.cloudmusic.scanner.ScanCoordinator
import com.example.cloudmusic.scanner.ScanProgress
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.FavoriteRepository
import com.example.cloudmusic.storage.HistoryRepository
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.ensureActive
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

/** §61.199 / 03-08 §10.2：投屏会话进度轮询周期（失败 3 次 ≈15s 后回落）。 */
private const val DLNA_POLL_INTERVAL_MS = 5_000L

/**
 * §61.182：注入曲目的命名与**扫描链同源**（`NameParser`：%XX 解码 + GBK 修复，
 * §61.170 口径）——此前曲名直接取 URL 原始路径段，真机一手天书标题
 * `V.A.%20-%20Love%20Letter%20%28Piano%29`（用户判读 §61.181 时发现）。
 * 与扫描管线同一清洗规则后，`艺人 - 标题` 拆分 / 百分号解码 / GBK 修复行为一致；
 * 解不动（纯英文字面 `+` 等）按 NameParser 契约原样保留。
 * album 保留 `F3 双路径矩阵` 夹具标记，供曲库审计识别（§61.177 口径）；
 * id 仍用原始路径段 —— 同一 URL 稳定映射同一行，避免历史 DB 行漂移。
 * 纯 JVM 顶层函数（不触 Android 类），供命名契约单测直接断言。
 */
internal fun remoteFixtureTrack(url: String): Track {
    val path = url.substringBefore('?')
    val fileName = path.substringAfterLast('/')
    val parentDir = path.substringBeforeLast('/', "").substringAfterLast('/')
    val fallback = NameParser.parse(fileName, parentDir)
    return Track(
        id = TrackId.of(REMOTE_FIXTURE_STORAGE_ID, fileName),
        title = fallback.title.ifBlank { fileName.substringBeforeLast('.', fileName) },
        artist = fallback.artist.ifBlank { "回环音源" },
        album = "F3 双路径矩阵",
        durationMs = 0L,
        sourceRef = SourceRef(
            storageId = REMOTE_FIXTURE_STORAGE_ID, path = fileName, url = url,
        ),
        sourceType = SourceType.STRM,
    )
}

/**
 * §61.184（三层守护网② 审计揪出的 `StorageRepository.setError` 断链的接线）：
 * Storage 级扫描错误 → **数据源错误态**（设置页 `StorageDto.errorMsg` 有读无写
 * —— 扫描失败恒只有 CM-Scan 日志，用户在数据源页永远看不到「连接失败」）。
 *
 * 归类规则：`ScanError.path == storageId`（ScanCoordinator 对 Storage 级失败以
 * storageId 为 path 上报，`ScanCoordinator.kt` ScanState.error）→ 置
 * `"code: detail"`（detail 截 120 字防天书刷屏）；文件级错误 path 恒含 `/`，
 * 与 storageId（UUID / 夹具 id）天然不撞。本轮**跑过且未失败**的 storage 清
 * 陈旧 errorMsg（幂等空串——恢复后错误态自动消失）；本轮没跑过的保持原样
 * （不伪造「正常」）。纯 JVM 顶层函数，单测直测。
 */
internal fun storageErrorStates(
    errors: List<com.example.cloudmusic.scanner.ScanError>,
    storageIds: Set<String>,
    seenStorages: Set<String>,
): Map<String, String> {
    val failed = errors.filter { it.path in storageIds }
    val out = LinkedHashMap<String, String>()
    failed.forEach { e ->
        out[e.path] = "%s: %s".format(e.code, e.detail.take(120))
    }
    seenStorages.asSequence()
        .filter { it in storageIds && failed.none { f -> f.path == it } }
        .forEach { out[it] = "" }
    return out
}

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
 *
 * §61.268（本地 WebDAV 播放无声）：扫描期为 WebDAV 音频写入的**带签名直链**（`?sign=...`）
 * 经 `RegistryLinkProvider` 第 77 行以 URL 态直推，Media3 `DefaultHttpDataSource` 直连该
 * 签名链接 → 服务端 `EOFException: unexpected end of stream` → preparation 失败无声
 * （真机 2026-11-21 MP3/APE 均复现）。此类直链改走**本机回环代理**（OkHttp 稳定回源）。
 * 只针对签名直链，普通 strm/歌单 URL（无 sign）保持 §61.252 原样，零回归。
 */
class ProductionTrackSourceResolver(
    private val links: LinkProvider,
    private val proxy: PrePublishedCastProxy,
) : TrackSourceResolver {
    override suspend fun resolve(track: Track): Link {
        val link = links.link(track)
        return when {
            link.kind == Link.Kind.PROXIED || link.kind == Link.Kind.TEXT ||
                (link.kind == Link.Kind.URL && needsLoopbackProxy(link.url)) -> {
                val url = proxy.publish(track.id.value, link)
                link.copy(url = url, proxy = false, requireReference = false)
            }
            else -> link
        }
    }

    internal companion object {
        /**
         * 需要转本机代理的直链特征：OpenList / WebDAV 的 `?sign=` 签名直链。
         * 签名直链有有效期且服务端连接不稳（真机 EOF），回环代理用持久凭据回源更稳。
         * 纯函数，便于 JVM 单测 A/B 反证（无 sign 的普通 URL 必须为 false = 保持原行为）。
         */
        fun needsLoopbackProxy(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val q = url.substringAfter('?', "")
            return q.split('&').any { it.startsWith("sign=", ignoreCase = true) }
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
 * §61.206（A2-①）：数据源变更订阅端口。纯 JVM 默认 [NoOpMediaChangeSource]
 * （注册即返回空注销，测试/桌面零影响）；Android 生产实现 = [MediaStoreChangeSource]。
 */
fun interface MediaChangeSource {
    /** 注册变更回调；返回注销动作（失败/不可用返回空注销，不抛）。 */
    fun register(onChange: () -> Unit): () -> Unit
}

/** 测试 / 桌面默认：空订阅。 */
object NoOpMediaChangeSource : MediaChangeSource {
    override fun register(onChange: () -> Unit): () -> Unit = {}
}

/**
 * §61.206（A2-①）：MediaStore ContentObserver 生产实现——本地媒体库子树
 * （`content://media/external/audio`）的文件级变更（新增/删除/修改都会触发行变更）。
 * 回调落在主线程 Handler，只做一次 `tryEmit`，重活全部在 runtime 协程内。
 */
class MediaStoreChangeSource(private val context: android.content.Context) : MediaChangeSource {
    override fun register(onChange: () -> Unit): () -> Unit = runCatching {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        val unregister: () -> Unit = { runCatching { resolver.unregisterContentObserver(observer) } }
        unregister
    }.getOrElse { NoOpMediaChangeSource.register(onChange) }
}

/**
 * §61.206（A2-①）：MediaStore 变更 → 定向增量扫的判定核（纯 JVM，RESCAN 幂等口径）。
 * - 扫描运行中（手动 RESCAN / 设置变更全量）⇒ 事件**丢弃不排队**：打断手动扫描是
 *   破坏（§61.178 取消语义），排队则与防抖目的相悖；下次变更自然再触发。
 * - 本地存储集合为空（全部数据源是网络源）⇒ 不扫（订阅空转守卫，零扫描动作）。
 * 防抖 [DEBOUNCE_MS]：文件复制是成簇的行变更，3s 静默期后才发一次定向增量。
 */
object MediaChangeScanPolicy {
    const val DEBOUNCE_MS = 3_000L

    fun shouldScan(scanActive: Boolean, localIds: Set<String>): Boolean =
        !scanActive && localIds.isNotEmpty()
}

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
    /** §61.184：Storage 级扫描错误 → 数据源错误态（setError 断链接线，审计发现）。 */
    private val storages: StorageRepository,
    /** §61.119（M3）：歌单闭环的落库口。 */
    private val playlists: PlaylistRepository,
    /** §61.130（M5）：收藏 / 最近播放的落库口。 */
    private val favorites: FavoriteRepository,
    private val history: HistoryRepository,
    private val proxy: PrePublishedCastProxy,
    private val settings: SettingsStore,
    /** §61.112：playLocal 调试注入复用扫描管线的真实标签 / 封面件（03-04 §6）。 */
    private val tagReader: JAudioTaggerPort,
    private val coverWriter: CoverWriterPort,
    /** §61.116（M6-3）：缓存清理需要 cacheDir 路径。 */
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
    /** §61.233：刮削 HTTP（生产 OkHttp；测试 fake CoverHttp 不走此处）。 */
    private val scrapeHttp: com.example.cloudmusic.di.scan.CoverHttp =
        com.example.cloudmusic.di.scan.CoverHttp { url ->
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val req = okhttp3.Request.Builder().url(url).build()
                okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
            }
        },
    /** §61.193：DLNA 会话引擎第一刀——输出路由状态（活跃输出判定）。 */
    private val output: OutputRouter,
    /** §61.193：切歌保持投屏的执行者（发现/推流/setActive 闭环 §61.110 已有）。 */
    private val castCoordinator: com.example.cloudmusic.output.dlna.DlnaCastCoordinator,
    /** §61.206（A2-①）：MediaStore 变更订阅（生产 MediaStoreChangeSource，测试 NoOp）。 */
    private val mediaChangeSource: MediaChangeSource = NoOpMediaChangeSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RuntimeState())

    init {
        // §61.118：换曲后按持久值恢复均衡器（T5-1 持久化的生产执行点）。
        restoreEqualizerLoop()
        // §61.130（M5）：播放历史记录（每曲一次）。
        historyLoop()
        // §61.193：DLNA 切歌保持（会话引擎第一刀）。
        dlnaRecastLoop()
        // §61.199：DLNA 断连回落本机（会话引擎第二刀）。
        dlnaSessionLoop()
    }
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /**
     * §61.202：DMR 最近一次上报的进度（ms）。只在投屏会话循环里写，回本机即清零；
     * 读失败时保留旧值（不把进度打回 0）。
     */
    private val _dlnaPositionMs = MutableStateFlow(0L)
    fun dlnaPositionMs(): StateFlow<Long> = _dlnaPositionMs.asStateFlow()

    /**
     * §61.229（V0.2 M7）：投屏态 DMR 音量——RenderingControl:1 Master channel。
     * 值由 UI 写入（输出弹窗滑杆）与 GetVolume 读回；非投屏态写操作是 no-op
     * （本机音量语义 = 系统音量，App 内不接管——§61.93 音效链路正交）。
     */
    private val _dlnaVolume = MutableStateFlow(50)
    fun dlnaVolume(): StateFlow<Int> = _dlnaVolume.asStateFlow()

    /** §61.229：DMR 音量下发（投屏态转发 RenderingControl；失败只记日志、不伪造生效）。 */
    fun setCastVolume(volume: Int) = scope.launch {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            val ok = runCatching { castCoordinator.setVolume(device, volume) }.getOrDefault(false)
            if (ok) _dlnaVolume.value = volume.coerceIn(0, 100)
            else Log.w("CM-DLNA", "DMR SetVolume 未确认 vol=$volume")
        } else {
            Log.w("CM-DLNA", "非投屏态忽略 DMR 音量写入 vol=$volume")
        }
    }

    /** §61.229：从 DMR 读回当前音量（打开弹窗时刷新显示；失败保持上次值）。 */
    fun refreshCastVolume() = scope.launch {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            castCoordinator.getVolume(device)?.let { _dlnaVolume.value = it.coerceIn(0, 100) }
        }
    }

    /**
     * §61.202 播放页进度显示的单一入口：DLNA 活跃时用 DMR 真值（未读到时暂用
     * 本机引擎冻结的进度），否则用引擎实时进度。UI 不再自己判断输出类型。
     */
    fun displayPositionMs(): Flow<Long> = combine(
        playerState(), dlnaPositionMs(), output.state(),
    ) { s, dmr, o ->
        if (o.activeOutput == ActiveOutput.DLNA && dmr > 0L) dmr else s.positionMs
    }

    @Volatile
    private var scanJob: Job? = null

    /** §61.206（A2-①）：MediaStore 变更事件流（tryEmit，防抖后触发定向增量扫）。 */
    private val mediaChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    @Volatile
    private var mediaChangeRegistered = false

    fun start() {
        registerMediaChangeSubscription()
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

    /**
     * §61.207（A2-③）：单源刷新（数据源管理页「刷新此源」行内动作）——
     * 定向增量扫（refresh=false 快照 diff，有变才重处理；与 §61.204 statHit 语义
     * 自然衔接）。与手动 RESCAN 同通道重入语义（后触发者取消前者）。
     */
    fun refreshStorage(storageId: String) = runScan(ScanRequest(storageIds = setOf(storageId)))

    /**
     * §61.206（A2-①）：MediaStore 变更订阅。幂等注册（start 可能被 Service 重建
     * 多次调用）；变更 → 防抖 [MediaChangeScanPolicy.DEBOUNCE_MS] → 定向增量扫
     * （仅 driverType=local 的存储，网络源由 RESCAN/设置页手动口径覆盖）。
     * 运行时为 @Singleton 进程生命周期，注销动作不持有（与进程同生共死）。
     */
    private fun registerMediaChangeSubscription() {
        if (mediaChangeRegistered) return
        mediaChangeRegistered = true
        scope.launch {
            mediaChanges
                .debounce(MediaChangeScanPolicy.DEBOUNCE_MS)
                .collect {
                    val localIds = runCatching {
                        storages.observe().first()
                            .filter { it.driverType == "local" }
                            .mapTo(HashSet()) { it.id }
                    }.getOrDefault(emptySet())
                    if (!MediaChangeScanPolicy.shouldScan(scanJob?.isActive == true, localIds)) {
                        return@collect   // RESCAN 幂等 / 订阅空转守卫：零扫描动作
                    }
                    Log.i("CM-Scan", "MediaStore 变更 → 定向增量扫（本地源 ${localIds.size} 个）")
                    runScan(ScanRequest(storageIds = localIds))
                }
        }
        mediaChangeSource.register { mediaChanges.tryEmit(Unit) }
    }

    private fun runScan(request: ScanRequest) {
        // §61.178：重入可见化——扫描被 cancel 后真机只看到「正在扫描曲库」消失，
        // 无法判断是重入取消、Service 重建还是 scope 销毁。
        Log.i("CM-Scan", "runScan 触发 refresh=${request.refresh} 调用链=" +
            Throwable().stackTrace.take(6).joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" })
        scanJob?.cancel()
        scanJob = scope.launch {
            // §61.129（T-V #9）：扫描时长观测（冷扫 ≤60s / 增量 ≤10s 基线判据）。
            val scanStart = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(scanning = true, scanned = 0, total = 0, errors = 0) }
            try {
                var lastProgress: ScanProgress? = null
                // §61.184：per-storage 跑动集合（供「本轮干净 → 清陈旧错误态」判定）。
                val seenStorages = mutableSetOf<String>()
                coordinator.scan(request).collect { progress: ScanProgress ->
                    lastProgress = progress
                    seenStorages += progress.storageId
                    _state.update {
                        it.copy(
                            scanned = progress.scanned,
                            total = progress.total,
                            errors = progress.errors.size,
                        )
                    }
                }
                Log.i("CM-Scan", "scan done: %d ms (refresh=%s files≈%d)".format(
                    android.os.SystemClock.elapsedRealtime() - scanStart,
                    request.refresh, _state.value.scanned))
                // §61.184：Storage 级扫描失败 → 数据源错误态（`StorageRepository.setError`
                // 此前零生产写方，审计揪出的断链）——设置页数据源行现在能看到「连接失败」；
                // 本轮跑过且未失败的 storage 清陈旧 errorMsg（幂等空串）。写侧失败只记日志，
                // 不影响扫描主流程。
                runCatching {
                    val storageIds = storages.observe().first().mapTo(HashSet()) { it.id }
                    storageErrorStates(
                        lastProgress?.errors.orEmpty(), storageIds, seenStorages,
                    ).forEach { (id, msg) ->
                        storages.setError(id, msg)
                        Log.i("CM-Scan", "storage error-state: %s → %s".format(id, msg.ifBlank { "已清" }))
                    }
                }.onFailure {
                    Log.w("CM-Scan", "storage error-state write failed: ${it.message}")
                }
                // §61.153：扫描错误可见化——此前 B07/storage 级错误静默吞掉，
                // WebDAV/STRM 扫描 0 文件无法归因（真机一手）。前 5 条细节 WARN。
                lastProgress?.errors?.take(5)?.forEach {
                    Log.w("CM-Scan", "scan error: %s %s %s".format(it.path, it.code, it.detail))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // §61.178：取消（`scanJob?.cancel()` 重入 / 离开页面）不是扫描失败。
                // 旧实现把 CancellationException 一并按 Exception 吞成 SCAN_FAILED，
                // 通知栏出现「设置已生效：SCAN_FAILED」而日志零输出。
                Log.i("CM-Scan", "scan cancelled: ${e.javaClass.name}: ${e.message} cause=${e.cause}")
                Log.i("CM-Scan", "cancel stack=" + e.stackTrace.take(6)
                    .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                throw e
            } catch (e: Exception) {
                // §61.178（可观测性）：此前只置 lastEffect 不打日志，真机「扫描 0 首」
                // 完全无法归因（与 §61.176 点歌静默失效同型：失败与没触发同形）。
                Log.w("CM-Scan", "scan failed: ${e::class.java.name}: ${e.message}", e)
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
        // §61.176（点歌接线 / 用户一手）：本方法是 UI「点某一首」的唯一落点。
        // 加三段留痕：PlayerController.prepareAt 的失败分支走 `fail()`（只改状态、不打日志），
        // 缺这段可观测性时，「resolve 失败」与「点击没接上」在 logcat 里完全同形（都是静默）。
        if (items.isEmpty()) {
            Log.w("CloudMusicRuntime", "playTracks: 空列表，忽略（startAt=$startAt）")
            return
        }
        scope.launch {
            runCatching {
                Log.i(
                    "CloudMusicRuntime",
                    "playTracks: n=${items.size} startAt=$startAt first=${items.getOrNull(startAt)?.title}",
                )
                queueRepository.setAll(items)
                val queueItems = queueRepository.observe().first()
                controller.setQueue(queueItems, PlayMode.SEQUENCE, startAt)
                controller.playAt(startAt)
                val s = controller.state.value
                Log.i(
                    "CloudMusicRuntime",
                    "playTracks: 结果 phase=${s.phase} idx=${s.currentIndex} " +
                        "err=${s.lastError?.let { e -> e::class.simpleName } ?: "-"}",
                )
            }.onFailure { Log.w("CloudMusicRuntime", "playTracks: 分组播放失败", it) }
        }
    }

    /** §61.130（M5）：收藏集合流（播放页 ♡ 状态 + 我的音乐页取数）。 */
    fun observeFavorites(): Flow<Set<TrackId>> = favorites.observe()

    /** §61.130（M5）：最近播放流（HistoryDao JOIN tracks，按 last_played DESC）。 */
    fun observeHistory(): Flow<List<Track>> = history.observe()

    /** §61.130（M5）：收藏切换（播放页 ♡ 按钮的唯一生产入口）。 */
    fun toggleFavorite(trackId: TrackId) {
        scope.launch {
            runCatching { favorites.toggle(trackId) }
                .onFailure { Log.w("CloudMusicRuntime", "toggleFavorite 失败", it) }
        }
    }

    /** §61.130（M5）：播放历史记录（PLAYING 时每曲一次，HistoryDao playCount 递增）。 */
    fun recordHistory(trackId: TrackId) {
        scope.launch {
            runCatching { history.record(trackId) }
                .onFailure { Log.w("CloudMusicRuntime", "recordHistory 失败", it) }
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

    /**
     * §61.122（T5-2 分析管线第一版）：曲库 ReplayGain 近似分析——仅 LOCAL、
     * 未分析（replayGainDb==null）、上限 [maxCount] 首；逐曲解码（IO 线程）、
     * 落库 + logcat 进度（CM-RG tag，真机可观测）。耗时随曲库线性增长，
     * 由设置页显式触发（opt-in）。
     */
    fun analyzeLibraryReplayGain(maxCount: Int = 50, force: Boolean = false) {
        scope.launch {
            runCatching {
                val all = tracks.observeAll().first()
                    .filter { it.sourceType == SourceType.LOCAL &&
                        (force || it.replayGainDb == null) }
                    .take(maxCount)
                if (all.isEmpty()) {
                    Log.i("CM-RG", "analyze: nothing to analyze")
                    return@runCatching
                }
                Log.i("CM-RG", "analyze start: %d tracks".format(all.size))
                var done = 0
                var gained = 0
                for (track in all) {
                    ensureActive()
                    // §61.122 真机一手：playLocal 注入曲目的 sourceRef.path 是文件名
                    // （非绝对路径）→ 候选 = 原值 + filesDir/music/<name>，取存在的。
                    val candidates = listOfNotNull(
                        track.sourceRef.path,
                        appContext.filesDir.resolve("music/${track.sourceRef.path}")
                            .absolutePath,
                    )
                    val path = candidates.firstOrNull {
                        java.io.File(it).isFile
                    }
                    if (path == null) {
                        Log.i("CM-RG", "analyze %s → 文件不存在（%s）".format(
                            track.title.take(20), track.sourceRef.path))
                        done++
                        continue
                    }
                    val result = com.example.cloudmusic.di.scan.ReplayGainAnalyzer
                        .analyze(path)
                    if (result != null) {
                        tracks.markReplayGain(track.id, result.gainDb)
                        gained++
                        Log.i("CM-RG", "analyze %s → %.2f dB (%s)".format(
                            track.title.take(20), result.gainDb, result.path))
                    } else {
                        Log.i("CM-RG", "analyze %s → NO_SAMPLE（平台不可解或过短）".format(
                            track.title.take(20)))
                    }
                    done++
                }
                Log.i("CM-RG", "analyze done: %d/%d gained".format(gained, done))
            }.onFailure { Log.w("CM-RG", "analyze 失败", it) }
        }
    }

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
        val track = remoteFixtureTrack(url)
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
        // §61.143：强制重建为 file 通道实例——C-10 的 StorageDriverBinder 会按 DataStore
        // storage 先建 MediaStore 通道实例（MediaStore 不索引应用私有目录，list 恒空），
        // 播放用 `LocalDriver.link()` 纯路径产出不受影响，但 **T-V #9 的扫描 list 必须
        // 用 file 通道**。先 remove（幂等 drop，B02）再 put 覆盖。
        runCatching { DriverRegistry.remove(LOCAL_FIXTURE_STORAGE_ID) }
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

    /**
     * §61.26（G03 验收）+ §61.202：外部 seek 入口（播放页进度条 / 通知栏 / 验收 intent 共用）。
     * 投屏活跃时转发到 DMR（本机引擎此时静音、seek 它不会改变用户听到的进度）；
     * 下发成功同步刷新显示进度，失败只记日志、不假装生效。非投屏态仍走本机引擎。
     */
    fun seekTo(ms: Long) = scope.launch {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            val ok = runCatching { castCoordinator.seekTo(device, ms) }.getOrDefault(false)
            if (ok) _dlnaPositionMs.value = ms
            else Log.w("CM-DLNA", "DMR seek 未确认 ms=$ms")
        } else {
            controller.seek(ms)
        }
    }

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
                    // §61.230：保留 fade 字段（AudiofxSettings 新增成员不在此链被重置）
                    it.copy(audiofx = it.audiofx.copy(
                        enabled = enabled,
                        effectLevel = effectLevel,
                        bandsMillibel = bands,
                    ))
                }
            }.onFailure { Log.w("CloudMusicRuntime", "applyEqualizer 失败", it) }
        }
    }

    /**
     * §61.233（V0.2 刮削）：封面在线刮削批处理（设置页触发，同 §61.130 RG 分析模式；
     * 手动 + 限数 50，不挂扫描期）。结果 toast 汇报尝试/命中数。
     */
    fun scrapeMissingCovers(maxCount: Int = 50) = scope.launch {
        runCatching {
            val session = com.example.cloudmusic.di.scan.CoverScrapeSession(
                tracks = tracks,
                writer = coverWriter,
                http = scrapeHttp,
            )
            val report = session.scrapeMissing(maxCount)
            Log.i("CM-SCRAPER", "scrape done: attempted=%d hit=%d".format(report.attempted, report.hit))
        }.onFailure { Log.w("CM-SCRAPER", "scrape 失败", it) }
    }

    /** §61.250：跨次指纹记忆（key="storageId|path"）——限数逐次推进 + 命中零 IO 复用。 */
    private val duplicateHashMemo =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    /** §61.247：重复曲目检测报告（null = 未检测；设置页触发后由 UI 消费展示）。 */
    private val _duplicateReport = kotlinx.coroutines.flow.MutableStateFlow<
        com.example.cloudmusic.model.DuplicateReport?>(null)
    fun duplicateReport(): kotlinx.coroutines.flow.StateFlow<
        com.example.cloudmusic.model.DuplicateReport?> = _duplicateReport.asStateFlow()

    /** §61.247：关闭报告面板时清空（下次触发重算）。 */
    fun clearDuplicateReport() {
        _duplicateReport.value = null
        duplicateHashMemo.clear()   // §61.250：面板关闭清记忆（下次触发从头推进）
    }

    /**
     * §61.247（V0.3 M5 / A1 Tier2）：重复曲目检测（设置页触发；零 IO 读曲库内存分组，
     * 不挂扫描期）。结果进 [duplicateReport] 由设置页结果面板展示；处置用户手动。
     */
    fun findDuplicates() = scope.launch {
        runCatching {
            // §61.248：Tier2 分组 + Tier1 哈希确认趟（候选限数 50 文件/次）。
            val session = com.example.cloudmusic.di.scan.DuplicateHashSession(
                tracks = tracks,
                hasher = com.example.cloudmusic.di.scan.DriverContentHasher(),
            )
            // §61.250：跨次指纹记忆（runtime 持有，重跑推进剩余候选；换库全量重扫时
            // 由调用方 clearDuplicateReport 清——记忆按 storageId|path 键，路径变化自然失效）。
            var report = session.detect(maxHashCount = 50, memo = duplicateHashMemo)
            // §61.256：Tier3 声纹趟——对哈希未确认的疑似组解码匹配（转码/异格式拷贝升级）。
            if (report.groups.isNotEmpty()) {
                val fpSession = com.example.cloudmusic.di.scan.FingerprintSession(
                    tracks = tracks,
                    decoder = com.example.cloudmusic.di.adapters.MediaPcmDecoder(),
                )
                report = fpSession.detect(report, maxCount = 20)
            }
            _duplicateReport.value = report
            Log.i("CM-DUP", "duplicate scan done: scanned=%d suspected=%d confirmed=%d fpConfirmed=%d hashed=%d failed=%d"
                .format(report.scanned, report.groups.size, report.confirmed.size,
                    report.fingerprintConfirmed.size, report.hashedCount, report.hashFailed))
        }.onFailure { Log.w("CM-DUP", "duplicate scan 失败", it) }
    }

    /**
     * §61.230（V0.2 M6）：淡入淡出开关持久化（AudiofxSettings.fadeEnabled）。
     * 即时下发由 restoreEqualizerLoop 的 settings.collect 转发 controller.updateFade。
     */
    /** §61.234：主题模式持久化（ThemeMode 四值；MainActivity 改后 recreate 生效）。 */
    fun updateTheme(mode: String) {
        scope.launch {
            runCatching { settings.update { it.copy(theme = mode) } }
                .onFailure { Log.w("CloudMusicRuntime", "updateTheme 失败", it) }
        }
    }

    /** §61.226 同族：设置流投影（MainActivity 设置页的 fade 开关显示源）。 */
    fun fadeEnabled(): kotlinx.coroutines.flow.StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(settings.current().audiofx.fadeEnabled).also { flow ->
            scope.launch {
                settings.settings.collect { fx -> (flow as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = fx.audiofx.fadeEnabled }
            }
        }

    fun updateFadeEnabled(enabled: Boolean) {
        scope.launch {
            runCatching {
                settings.update { it.copy(audiofx = it.audiofx.copy(fadeEnabled = enabled)) }
                controller.updateFade(enabled, settings.current().audiofx.fadeDurationMs)
            }.onFailure { Log.w("CloudMusicRuntime", "updateFadeEnabled 失败", it) }
        }
    }

    /** §61.118：换曲后按持久值恢复均衡器（READY 且曲目变化时一次性应用）。 */
    private fun restoreEqualizerLoop() {
        // §61.160：音效档位同步到声场展宽总线（audiofx enabled 且 effectLevel>0 时
        // 按 0~5 档映射 0~0.8 展宽量；0/off 直通）。AudioEffectsBus 进程级单例。
        scope.launch {
            settings.settings.collect { fx ->
                val widen = if (fx.audiofx.enabled && fx.audiofx.effectLevel > 0) {
                    (fx.audiofx.effectLevel.coerceAtMost(5) / 5f) * 0.8f
                } else 0f
                com.example.cloudmusic.engineopensource.AudioEffectsBus.widenAmount = widen
                // §61.230：淡入淡出配置热更新（起播渐入 + 分轨终点渐出交棒）。
                val fadeMs = if (fx.audiofx.fadeDurationMs in 200..5000) fx.audiofx.fadeDurationMs else 2000
                controller.updateFade(fx.audiofx.fadeEnabled, fadeMs)
            }
        }
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

    /** §61.130（M5）：PLAYING 且曲目变化时记录播放历史（每曲一次）。 */
    private fun historyLoop() {
        scope.launch {
            var recordedFor: String? = null
            controller.state.collect { s ->
                val trackKey = s.currentTrackId?.value
                if (s.phase == PlayerPhase.PLAYING &&
                    trackKey != null && trackKey != recordedFor
                ) {
                    recordedFor = trackKey
                    recordHistory(TrackId(trackKey))
                }
            }
        }
    }

    /**
     * §61.193：DLNA 切歌保持（会话引擎第一刀，用户「说好的 DLNA 全都没有」）。
     *
     * 投屏目标设备由 UI 首次投屏成功时经 [onDlnaDeviceSelected] 写入；本收集器观察
     * 播放状态 × 输出路由：**DLNA 为活跃输出且换曲**时，自动把新曲目 re-cast 到该设备
     * （SetAVTransportURI + Play，hint 走 routeHint → 输出弹窗可见）；回落 LOCAL 即清设备。
     * §61.199：断连回落由 [dlnaSessionLoop] 承担——3 次进度读取失败即 Stop + 切回
     * LOCAL + seek 到最后已知进度续播。挂账：多设备列表（capstone 另批）。
     */
    @Volatile
    private var castingDevice: com.example.cloudmusic.output.dlna.DmrDevice? = null

    /** §61.199：断连判定器（纯 JVM），只被本类的会话循环访问。 */
    private val dlnaSession = com.example.cloudmusic.output.dlna.DlnaSessionMonitor()

    /** UI 首次投屏成功时写入目标设备（CloudMusicApp onOutputSelected DLNA 分支）。 */
    fun onDlnaDeviceSelected(device: com.example.cloudmusic.output.dlna.DmrDevice) {
        castingDevice = device
        dlnaSession.reset()   // 新会话：清上次断连标记与旧进度
        _dlnaPositionMs.value = 0L
        // §61.241（V3-M2）：上次投屏设备持久化——进程死亡后仍记忆（回本机不清，
        // 是记忆不是活跃态）。scope.launch 与 updateTheme 同型（settings.update 是
        // suspend）；失败只记日志不伪造已记。
        scope.launch {
            runCatching { settings.update { it.copy(dlna = it.dlna.withLastDmr(device.usn, device.friendlyName)) } }
                .onFailure { Log.w("CM-DLNA", "上次投屏设备持久化失败: ${it.message}") }
        }
    }

    private fun dlnaRecastLoop() {
        scope.launch {
            var lastTrackId: String? = null
            combine(controller.state, output.state()) { s, o -> s to o }.collect { (s, o) ->
                // 回落本机：清投屏目标（下次投屏重新选设备）。
                if (o.activeOutput == ActiveOutput.LOCAL && castingDevice != null) {
                    castingDevice = null
                    dlnaSession.reset()   // 用户主动回本机：会话结束，不留断连标记
                    _dlnaPositionMs.value = 0L   // §61.202：显示进度交还本机引擎
                }
                val trackId = s.currentTrackId?.value
                val device = castingDevice
                if (o.activeOutput == ActiveOutput.DLNA &&
                    trackId != null && trackId != lastTrackId &&
                    s.phase == PlayerPhase.PLAYING && device != null
                ) {
                    val track = runCatching { tracks.get(TrackId(trackId)) }.getOrNull()
                    if (track != null) {
                        runCatching { castCoordinator.startCast(track, device) }
                            .onFailure { Log.w("CM-DLNA", "切歌保持 re-cast 失败: ${it.message}") }
                    }
                }
                lastTrackId = trackId
            }
        }
    }

    /**
     * §61.199（03-08 §10.2 T18）：DLNA 断连回落本机。
     *
     * 投屏活跃且记住了目标设备时，每 [DLNA_POLL_INTERVAL_MS] 读一次 DMR 进度；
     * 判定器连续 3 次失败 → Stop DMR（尽力）→ OutputRouter 切回 LOCAL →
     * 解除本机静音 / 恢复 MediaSession → seek 到最后已知进度并续播 → UI 提示。
     * 不投屏时不读网、不抢占；回落只发一次（判定器保证），避免反复 seek。
     * 真机 E2E pending-device：无设备不施工、不伪造 PASS。
     */
    private fun dlnaSessionLoop() {
        scope.launch {
            while (isActive) {
                val device = castingDevice
                if (output.activeOutput == ActiveOutput.DLNA && device != null) {
                    val position = runCatching { castCoordinator.positionMs(device) }.getOrNull()
                    // §61.202：读成功即刷新显示进度；失败保留旧值（判定器负责计失败次数）。
                    if (position != null && position >= 0L) _dlnaPositionMs.value = position
                    val verdict = dlnaSession.observe(position)
                    if (verdict.shouldFallBack) {
                        runCatching { castCoordinator.stopCast(device) }
                            .onFailure { Log.w("CM-DLNA", "回落 Stop 失败: ${it.message}") }
                        // OutputRouter 是输出状态唯一裁决者（03-08 §14 #9）；先切状态再恢复本机控件。
                        output.setActive(ActiveOutput.LOCAL)
                        output.updateRouteHint("已回到本机播放")
                        this@PlaybackRuntime.onOutputChanged(false)
                        val resumed = runCatching {
                            // 从未读到有效进度时不 seek（避免把用户带回开头）。
                            if (verdict.lastPositionMs > 0L) controller.seek(verdict.lastPositionMs)
                            controller.resume()
                        }
                        if (resumed.isFailure) {
                            Log.w("CM-DLNA", "回落 seek/resume 失败: ${resumed.exceptionOrNull()?.message}")
                        }
                        castingDevice = null
                        dlnaSession.reset()
                        _dlnaPositionMs.value = 0L
                        Log.i("CM-DLNA", "断连回落本机 lastPositionMs=${verdict.lastPositionMs} resumed=${resumed.isSuccess}")
                    }
                }
                delay(DLNA_POLL_INTERVAL_MS)
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

    /** §61.206（A2-①）：MediaStore ContentObserver 订阅（注册失败静默回落空注销）。 */
    @Provides
    @Singleton
    fun provideMediaChangeSource(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): MediaChangeSource = MediaStoreChangeSource(context)

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
        tracks: TrackRepository,
        queueRepository: QueueRepository,
    ): PlayerController = PlayerController(
        engine = engine,
        sync = sync,
        lookup = lookup,
        resolver = resolver,
        media = media,
        config = SettingsProjection.playerConfig(settings.current()),
        networkGate = networkGate,
        // §61.181（B 案）：引擎解出真值时长且曲库缺值（STRM / 无标签）→ 回填曲库，
        // 下次进页 / 重扫不再依赖引擎在场。
        durationWriter = TrackDurationWriter { id, dur -> tracks.updateDuration(id, dur) },
        // §61.182：播放游标写侧复活（saveCursor 此前生产链零写方 ⇒ 冷启动恢复死路，
        // 真机一手 `playback_state 缺失`）——切歌 / 暂停 / PLAYING 每 5s 落 playback_state。
        cursorWriter = CursorWriter { id, pos -> queueRepository.saveCursor(id, pos) },
        // §61.253：源级失败 → 曲库可用性如实标记（PRD §4 离线优先）。
        availabilityWriter = com.example.cloudmusic.player.TrackAvailabilityWriter { id, ok ->
            tracks.markAvailability(id, if (ok) com.example.cloudmusic.model.Availability.OK
            else com.example.cloudmusic.model.Availability.UNREACHABLE)
        },
    )
}
