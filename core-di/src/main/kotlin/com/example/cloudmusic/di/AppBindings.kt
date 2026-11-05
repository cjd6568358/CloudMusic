package com.example.cloudmusic.di

import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.engine.api.InMemoryMusicEngine
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.media.InMemoryMediaControl
import com.example.cloudmusic.storage.FavoriteRepository
import com.example.cloudmusic.storage.HistoryRepository
import com.example.cloudmusic.storage.InMemoryFavoriteRepository
import com.example.cloudmusic.storage.InMemoryHistoryRepository
import com.example.cloudmusic.storage.InMemoryLibraryStore
import com.example.cloudmusic.storage.InMemoryPlaylistRepository
import com.example.cloudmusic.storage.InMemoryStorageRepository
import com.example.cloudmusic.storage.InMemoryTrackRepository
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * docs/12 §14 / §15 / §13 的 Hilt 绑定（15 §7.5 #4）。
 *
 * V0.1 开发期一律绑定**纯 JVM 内存实现**，让 UI / 组合层能在照搬舱落地前先跑通链路。
 * `:engine-legacy/EngineAdapter` 与 `:media-legacy/MediaLegacyAdapter` 落地后，
 * 只需替换这两个 @Provides 的返回值，**不改任何调用方、不改门面契约**。
 * Room / DataStore 适配落地后同理替换 [LibraryModule] 的实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideMusicEngine(): IMusicEngine = InMemoryMusicEngine()
}

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    @Singleton
    fun provideMediaControl(): IMediaControl = InMemoryMediaControl()
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    /** 5 个 Repository 必须共享同一个 store，否则曲库 / 歌单 / 收藏会各看一份状态。 */
    @Provides
    @Singleton
    fun provideLibraryStore(): InMemoryLibraryStore = InMemoryLibraryStore()

    @Provides
    @Singleton
    fun provideTrackRepository(store: InMemoryLibraryStore): TrackRepository =
        InMemoryTrackRepository(store)

    @Provides
    @Singleton
    fun providePlaylistRepository(store: InMemoryLibraryStore): PlaylistRepository =
        InMemoryPlaylistRepository(store)

    @Provides
    @Singleton
    fun provideFavoriteRepository(store: InMemoryLibraryStore): FavoriteRepository =
        InMemoryFavoriteRepository(store)

    @Provides
    @Singleton
    fun provideHistoryRepository(store: InMemoryLibraryStore): HistoryRepository =
        InMemoryHistoryRepository(store)

    @Provides
    @Singleton
    fun provideStorageRepository(
        store: InMemoryLibraryStore,
        tracks: TrackRepository,
    ): StorageRepository = InMemoryStorageRepository(store, tracks)
}
