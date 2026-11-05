package com.example.cloudmusic.di

import com.example.cloudmusic.datasource.DriverFactory
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.playlist.PlaylistDriver
import com.example.cloudmusic.strm.StrmDriver
import com.example.cloudmusic.webdav.OkHttpStack
import com.example.cloudmusic.webdav.WebDavDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * docs/03-01 §4.1：**全项目唯一的 Driver 注册点**。
 * 03-02 / 03-03 只提供驱动构造，不另建 @Module；V0.2 的 sftp / ftp / nfs 在此追加即可。
 *
 * `local` 键暂缺：`:datasource-local`（SAF / MediaStore 双通道）依赖 ContentResolver，
 * 属 Android 侧后续批次（12 §11.4）。落地时只需在此增加一个 @IntoMap 提供者，
 * 其余模块不改。契约测试 T16 的 4 个注册键由纯 JVM fake 覆盖，不依赖本 Module。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    const val KEY_WEBDAV = "webdav"
    const val KEY_STRM = "strm"
    const val KEY_PLAYLIST = "playlist"
    const val KEY_LOCAL = "local" // 预留，尚未注册

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @IntoMap
    @StringKey(KEY_WEBDAV)
    fun provideWebDavFactory(client: OkHttpClient): DriverFactory = { storage, addition ->
        WebDavDriver(storage, addition.raw, OkHttpStack(client))
    }

    @Provides
    @IntoMap
    @StringKey(KEY_STRM)
    fun provideStrmFactory(): DriverFactory = { storage, addition ->
        StrmDriver(storage, addition.raw, DriverRegistry)
    }

    @Provides
    @IntoMap
    @StringKey(KEY_PLAYLIST)
    fun providePlaylistFactory(): DriverFactory = { storage, addition ->
        PlaylistDriver(storage, addition.raw, DriverRegistry)
    }
}

/**
 * 把 Hilt multibindings 的 `Map<String, DriverFactory>` 一次性灌进 [DriverRegistry]。
 * 由 `CloudMusicApplication.onCreate()` 调用；重复启动不会重复注册（T16 的
 * `register` 对同 key 抛异常），因此这里按已有 key 做幂等过滤。
 */
@Singleton
class DriverRegistrar @Inject constructor(
    private val factories: Map<String, @JvmSuppressWildcards DriverFactory>,
) {
    /** 返回本次实际注册的 key，便于启动自检；不含任何凭据或路径。 */
    fun registerAll(): Set<String> {
        val already = DriverRegistry.types()
        return factories.keys.filterTo(LinkedHashSet()) { it !in already }
            .onEach { type -> DriverRegistry.register(type, factories.getValue(type)) }
    }

    /** 供诊断使用：当前 Hilt 提供的驱动 key（不触发注册）。 */
    fun providedKeys(): Set<String> = factories.keys.toSet()
}
