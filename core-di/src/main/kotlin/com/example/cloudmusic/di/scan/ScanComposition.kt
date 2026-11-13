package com.example.cloudmusic.di.scan

import android.content.Context
import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverFactory
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.di.settings.toModel
import com.example.cloudmusic.local.LocalAddition
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.scanner.Candidate
import com.example.cloudmusic.scanner.CoverResolver
import com.example.cloudmusic.scanner.HeadTagReader
import com.example.cloudmusic.scanner.ScanCoordinator
import com.example.cloudmusic.scanner.ScanSnapshotStore
import com.example.cloudmusic.scanner.StorageProvider
import com.example.cloudmusic.scanner.TagReader
import com.example.cloudmusic.scanner.TrackFactory
import com.example.cloudmusic.scanner.TrackSink
import com.example.cloudmusic.storage.ScanReconciler
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * docs/03-04 §4 / §12 的**生产组合层**（第二十批 / T-A4）。
 *
 * 把 `ScanCoordinator` 的五个端口全部接上真实实现，让扫描器第一次能真正跑：
 * `StorageProvider`（DataStore）→ `StorageDriverBinder`（Driver 实例）→ `TrackSink`
 * （Room + C-04 恢复通道）→ `ScanSnapshotStore`（Room）→ `TagReader`（Android）。
 */

data class DriverSyncResult(
    val removed: List<String>,
    val created: List<String>,
    val failed: List<String>,
)

/**
 * **实测补上的生产缺口（C-10）**：`DriverRegistry.get(storageId)` 取的是**实例**，
 * 而 `DriverRegistrar.registerAll()` 只把 factory 灌进注册表。若不建实例，
 * `ScanCoordinator.scanStorage()` 会对每个 Storage 报 `E_SCAN_STORAGE_UNREACHABLE`。
 *
 * 这里按 `Storage.modified`（03-11 §5 / 08 §7）做幂等同步：新增/变更先 remove
 * 再 create + init + put，配置变更即等价于 `REBUILD_DRIVER_AND_FULL_RESCAN` 的重建部分。
 */
class StorageDriverBinder(
    private val factories: Map<String, @JvmSuppressWildcards DriverFactory>,
) {
    private val known = ConcurrentHashMap<String, Long>()

    @Volatile
    private var factoriesRegistered = false

    suspend fun sync(active: List<Storage>): DriverSyncResult {
        ensureFactories()
        val plan = DriverSyncPlanner.plan(active, known.toMap())
        plan.toRemove.forEach { id ->
            DriverRegistry.remove(id)
            known.remove(id)
        }
        val failed = mutableListOf<String>()
        val created = mutableListOf<String>()
        plan.toCreate.forEach { storage ->
            val factory = factories[storage.driverType]
            if (factory == null) {
                failed += storage.id
                return@forEach
            }
            val driver = runCatching { factory(storage, storage.addition) }.getOrNull()
            if (driver == null) {
                failed += storage.id
                return@forEach
            }
            val ok = runCatching {
                driver.init(DriverCtx(tag = "[scan:${storage.driverType}]", caller = "scan"))
                DriverRegistry.put(storage.id, driver)
                known[storage.id] = storage.modified
            }.isSuccess
            if (ok) {
                created += storage.id
            } else {
                runCatching { driver.drop() }
                failed += storage.id
            }
        }
        return DriverSyncResult(removed = plan.toRemove, created = created, failed = failed)
    }

    /** 与 `DriverRegistrar.registerAll()` 幂等共存：只补注册表里缺的 key。 */
    private fun ensureFactories() {
        if (factoriesRegistered) return
        synchronized(this) {
            if (factoriesRegistered) return
            val existing = DriverRegistry.types()
            factories.forEach { (type, factory) ->
                if (type !in existing) DriverRegistry.register(type, factory)
            }
            factoriesRegistered = true
        }
    }
}

/** 只扫描 `Storage.isWork` 的数据源，并在返回前把对应 Driver 实例准备好。 */
class DataStoreStorageProvider(
    private val access: SettingsAccess,
    private val binder: StorageDriverBinder,
) : StorageProvider {
    override suspend fun active(): List<Storage> {
        val active = access.snapshot().storages.map { it.toModel() }.filter { it.isWork }
        val result = binder.sync(active)
        val failed = result.failed.toSet()
        return active.filterNot { it.id in failed }
    }
}

/**
 * C-04 显式恢复通道：`upsertBatch` 必须走 `ScanReconciler.upsertFromScan`
 * （先 upsert 再对本轮确实扫到的曲目置回 OK），不能直接写 Repository。
 */
class RoomTrackSink(
    private val tracks: TrackRepository,
) : TrackSink {
    private val reconciler = ScanReconciler(tracks)

    override suspend fun upsertBatch(items: List<Track>) = reconciler.upsertFromScan(items)

    override suspend fun markUnavailable(ids: List<TrackId>) = reconciler.markUnavailable(ids)

    /**
     * C-14（`12 §31`）：定向增量的输入。Room 的 `observeAll()` 是唯一跨模块可用的读接口，
     * 取一次快照后按 `(storageId, availability != OK)` 投影路径；失败返回空集，
     * `ScanCoordinator` 会整轮跳过而不误标缺失（B14 / §15 #3）。
     */
    override suspend fun unavailablePaths(storageId: String): Set<String> =
        runCatching {
            tracks.observeAll().first()
                .asSequence()
                .filter { it.sourceRef.storageId == storageId && it.availability != Availability.OK }
                .map { it.sourceRef.path }
                .toSet()
        }.getOrDefault(emptySet())
}

@Module
@InstallIn(SingletonComponent::class)
object ScanModule {

    @Provides
    @Singleton
    fun provideStorageDriverBinder(
        factories: Map<String, @JvmSuppressWildcards DriverFactory>,
    ): StorageDriverBinder = StorageDriverBinder(factories)

    @Provides
    @Singleton
    fun provideStorageProvider(
        access: SettingsAccess,
        binder: StorageDriverBinder,
    ): StorageProvider = DataStoreStorageProvider(access, binder)

    @Provides
    @Singleton
    fun provideTrackSink(tracks: TrackRepository): TrackSink = RoomTrackSink(tracks)

    /** §61.112：标签端口提为单例 @Provides——playLocal 调试注入路径复用同一条真实标签链。 */
    @Provides
    @Singleton
    fun provideJAudioTaggerPort(): JAudioTaggerPort = JAudioTaggerFilePort()

    @Provides
    @Singleton
    fun provideCoverWriter(@ApplicationContext context: Context): CoverWriterPort =
        AndroidCoverWriter(context)

    @Provides
    @Singleton
    fun provideCoverLookupCache(): CoverLookupCache = CoverLookupCache()

    @Provides
    @Singleton
    fun provideLinkLocator(): LinkLocator = RegistryLinkLocator()

    @Provides
    @Singleton
    fun provideTagReader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        locator: LinkLocator,
        writer: CoverWriterPort,
        cache: CoverLookupCache,
    ): TagReader {
        // C-12（12 §31）：此前生产 rangeFor 恒为 null（没有任何 Driver 实现该能力），
        // 网络音源标签 + 同目录封面两条链路同时失效。现在两级回落：
        //   ① Driver 自身实现 ByteRangeCapability（WebDAV 复用鉴权协商）；
        //   ② strm / 播放列表的 URL 条目按 Candidate.directUrl 走 OkHttp Range GET。
        // 两处（HeadTagReader 与 DirectoryCoverLookup）共用同一个 rangeFor，语义一致。
        val rangeFor: (Candidate) -> ByteRangeCapability? = { candidate ->
            DriverRegistry.get(candidate.storage.id) as? ByteRangeCapability
                ?: candidate.directUrl
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?.let { UrlByteRangeSource(it, emptyList(), OkHttpRangeRequester(client)) }
        }
        val directoryLookup = DirectoryCoverLookup(writer, cache, rangeFor)
        return AndroidTagReader(
            media = AndroidMediaMetadataPort(context),
            jaudio = JAudioTaggerFilePort(),
            locator = locator,
            // 必须命名参数：HeadTagReader 的尾随 lambda 会匹配最后一个 Int(headSize) 参数
            head = HeadTagReader(rangeFor = rangeFor),
            directoryCover = directoryLookup::invoke,
        )
    }

    @Provides
    @Singleton
    fun provideCoverResolver(
        writer: CoverWriterPort,
        cache: CoverLookupCache,
    ): CoverResolver = AndroidCoverResolver(writer, cache)

    @Provides
    @Singleton
    fun provideTrackFactory(cover: CoverResolver): TrackFactory = TrackFactory(cover)

    @Provides
    @Singleton
    fun provideScanCoordinator(
        provider: StorageProvider,
        sink: TrackSink,
        snapshots: ScanSnapshotStore,
        tags: TagReader,
        factory: TrackFactory,
    ): ScanCoordinator = ScanCoordinator(
        storageProvider = provider,
        driverRegistry = DriverRegistry,
        trackSink = sink,
        snapshotStore = snapshots,
        tagReader = tags,
        trackFactory = factory,
        rootPathFor = { storage -> LocalAddition.parse(storage.addition.raw).rootFolderPath },
    )
}
