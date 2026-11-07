package com.example.cloudmusic.di

import android.content.Context
import com.example.cloudmusic.di.db.LibraryDatabase
import com.example.cloudmusic.di.db.RoomFavoriteRepository
import com.example.cloudmusic.di.db.RoomFailureLog
import com.example.cloudmusic.di.db.RoomHistoryRepository
import com.example.cloudmusic.di.db.RoomPlaylistRepository
import com.example.cloudmusic.di.db.RoomQueueRepository
import com.example.cloudmusic.di.db.RoomScanSnapshotStore
import com.example.cloudmusic.di.db.RoomTrackRepository
import com.example.cloudmusic.di.settings.DataStoreStorageRepository
import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.di.settings.SettingsStore
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.enginelegacy.EngineAdapter
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.media.InMemoryMediaControl
import com.example.cloudmusic.output.router.InMemoryOutputRouter
import com.example.cloudmusic.output.router.OutputRouter
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.scanner.ScanSnapshotStore
import com.example.cloudmusic.storage.FavoriteRepository
import com.example.cloudmusic.storage.HistoryRepository
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * docs/12 §14 / §15 / §13 的 Hilt 绑定（15 §7.5 #4）。
 *
 * V0.1 的开发期内存绑定已按 D-08 逐个替换：
 * - 第十七批（`12 §26`，T-A1）：曲库 4 个 Repository / 业务队列 / 扫描快照改绑 Room 实现。
 * - 第十八批（`12 §27`，T-A2）：`StorageRepository` 改绑 `DataStoreStorageRepository`，
 *   `SettingsStore` 成为 `AppSettings` 的唯一持久入口；凭据只在 DataStore、不进 Room（§14 #2）。
 *   每次写入返回 `SettingsUpdate.effects`（08 §7），`remove()` 仍委托 Room 的 `markOrphan()`。
 * - 第二十七批（`12 §36`，T-L L1）：`IMusicEngine` 改绑舱内 `EngineAdapter`
 *   （D-08 换绑点，调用方与门面签名零改动）。EngineAdapter 构造不触发 native 加载
 *   （只有 prepare 才实例化 PlaybackEngine），因此 JVM 单测里的 Hilt 图仍可安全注入。
 * - 第三十四批（`12 §43`，T-L L2 自研侧接线收口）：`IMediaControl` 仍为
 *   `InMemoryMediaControl`，但 **bind 由 `PlayerController.init` 用内部
 *   `HandleAdapter` 完成**（`03-07 §3` 正式链）；本模块只提供 `@Singleton` 实例，
 *   不再注入 `PlaybackRuntime` —— 否则
 *   `IMediaControl → PlaybackRuntime → PlayerController → IMediaControl`
 *   构成 Dagger 依赖环。焦点 / 断连经 `FocusPolicy` 裁决后回调 `HandleAdapter`，
 *   其 `duck/unduck` 走 `IMusicEngine.setVolume`、`seekRelative` 走 `seek`，
 *   不再使用能力更弱的 `RuntimeMediaControlHandle`（已撤销）。
 * 调用方与 `:core-storage` 的契约签名自始至终零改动（D-08）。
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    // 12 §57：注入 ApplicationContext 供照搬舱创建 NMVirtualFileManager（cacheManagerHandle 实验）。
    // 用 `also` 设置属性而非改构造签名，保持 12 §36 的「零参数换绑」守护语义。
    fun provideMusicEngine(@ApplicationContext context: Context): IMusicEngine =
        EngineAdapter().also { it.appContext = context }
}

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    /**
     * `03-07 §3` 正式链：本模块只提供 `@Singleton` 的 [IMediaControl]，**不在此 bind**。
     * `PlayerController` 构造时会自动执行 `media?.bind(controllerHandle)`，把内部
     * `HandleAdapter` 注册为唯一句柄；`PlaybackService` 注入同一实例并派发
     * `FocusChange.*`，由 `FocusPolicy` 裁决后回到 `PlayerController`。
     *
     * 第三十三批（`12 §42`）曾在此注入 `PlaybackRuntime` 并 bind 自建
     * `RuntimeMediaControlHandle`，造成 **P0 DI 依赖环**且与既有 bind 重复；
     * 本批按 `§42.3 T1` 撤销，改回零参数绑定。
     */
    @Provides
    @Singleton
    fun provideMediaControl(): IMediaControl = InMemoryMediaControl()

    /**
     * 第三十一批（12 §40）：`03-09 §7` 的「OutputRouter 是唯一裁决者」此前没有 DI 绑定，
     * UI 无法读取输出状态。这里补唯一的 @Singleton 入口；V0.1 仍为内存实现，
     * 真机蓝牙 / DLNA 状态上报属 T-L L2。
     */
    @Provides
    @Singleton
    fun provideOutputRouter(): OutputRouter = InMemoryOutputRouter()
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    /** 唯一 DB 实例；`build()` 内部注册 1→2 Migration 且禁止破坏性重建（03-05 §10）。 */
    @Provides
    @Singleton
    fun provideLibraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
        LibraryDatabase.build(context)

    @Provides
    @Singleton
    fun provideTrackRepository(db: LibraryDatabase): TrackRepository = RoomTrackRepository(db)

    @Provides
    @Singleton
    fun providePlaylistRepository(db: LibraryDatabase): PlaylistRepository =
        RoomPlaylistRepository(db)

    @Provides
    @Singleton
    fun provideFavoriteRepository(db: LibraryDatabase): FavoriteRepository =
        RoomFavoriteRepository(db)

    @Provides
    @Singleton
    fun provideHistoryRepository(db: LibraryDatabase): HistoryRepository =
        RoomHistoryRepository(db)

    @Provides
    @Singleton
    fun provideQueueRepository(db: LibraryDatabase): QueueRepository = RoomQueueRepository(db)

    @Provides
    @Singleton
    fun provideScanSnapshotStore(db: LibraryDatabase): ScanSnapshotStore =
        RoomScanSnapshotStore(db)

    @Provides
    @Singleton
    fun provideFailureLog(db: LibraryDatabase): RoomFailureLog = RoomFailureLog(db)

    /** 第十八批（12 §27，T-A2）：设置访问端口绑定到唯一的 DataStore 实例。 */
    @Provides
    @Singleton
    fun provideSettingsAccess(store: SettingsStore): SettingsAccess = store

    /**
     * T-A2：Storage（含 `additionRaw` 明文凭据）改由 DataStore 持久化，
     * `remove()` 的 `markOrphan()` 仍委托 Room 的 `TrackRepository`（03-05 §2 / §9）。
     */
    @Provides
    @Singleton
    fun provideStorageRepository(
        access: SettingsAccess,
        tracks: TrackRepository,
    ): StorageRepository = DataStoreStorageRepository(access, tracks)
}
