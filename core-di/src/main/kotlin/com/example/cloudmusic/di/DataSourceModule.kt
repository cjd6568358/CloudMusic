package com.example.cloudmusic.di

import android.content.Context
import com.example.cloudmusic.di.local.MediaStoreDirectorySource
import com.example.cloudmusic.di.local.SafDirectorySource
import com.example.cloudmusic.datasource.DriverFactory
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.local.LocalDriver
import com.example.cloudmusic.playlist.PlaylistDriver
import com.example.cloudmusic.strm.StrmDriver
import com.example.cloudmusic.webdav.OkHttpStack
import com.example.cloudmusic.webdav.WebDavDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * **第十九批（`12 §28`，T-A3）已补 `local` 键**：`:datasource-local` 的 `LocalDriver`
 * 是纯 JVM 驱动，SAF / MediaStore 两个 Android 端口由 `:core-di/local` 注入；
 * `DriverRegistry` 的 4 个 V0.1 键（webdav / strm / playlist / local）现已齐备。
 * 契约测试 T16 的 4 个注册键由纯 JVM fake 覆盖，不依赖本 Module。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    const val KEY_WEBDAV = "webdav"
    const val KEY_STRM = "strm"
    const val KEY_PLAYLIST = "playlist"
    const val KEY_LOCAL = "local"

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

    /** T-A3：SAF 优先 / MediaStore 兜底，两个端口都只依赖 `ContentResolver`，不含个人路径。 */
    @Provides
    @IntoMap
    @StringKey(KEY_LOCAL)
    fun provideLocalFactory(@ApplicationContext context: Context): DriverFactory = { storage, addition ->
        LocalDriver(
            storage = storage,
            additionJson = addition.raw,
            saf = SafDirectorySource(context.contentResolver),
            mediaStore = MediaStoreDirectorySource(context.contentResolver),
        )
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
