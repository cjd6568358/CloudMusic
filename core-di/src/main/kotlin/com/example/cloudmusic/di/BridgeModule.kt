package com.example.cloudmusic.di

import android.content.Context
import com.example.cloudmusic.bridge.DriverStreamFactory
import com.example.cloudmusic.bridge.FdOpener
import com.example.cloudmusic.bridge.RangeHttpStack
import com.example.cloudmusic.di.adapters.ConservativeDirectLinkProvider
import com.example.cloudmusic.di.adapters.ContentResolverFdOpener
import com.example.cloudmusic.di.adapters.DataSourceFactory
import com.example.cloudmusic.di.adapters.DatagramMulticastTransport
import com.example.cloudmusic.di.adapters.OkHttpDescriptionFetcher
import com.example.cloudmusic.di.adapters.OkHttpHeadProbe
import com.example.cloudmusic.di.adapters.OkHttpProxiedStreamFactory
import com.example.cloudmusic.di.adapters.OkHttpRangeHttpStack
import com.example.cloudmusic.di.adapters.OkHttpSoapTransport
import com.example.cloudmusic.di.adapters.PrePublishedCastProxy
import com.example.cloudmusic.di.adapters.RegistryDirectLinkProvider
import com.example.cloudmusic.di.adapters.RegistryLinkProvider
import com.example.cloudmusic.di.adapters.WifiMulticastLockPort
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.output.dlna.CachingHeadProbe
import com.example.cloudmusic.output.dlna.CastProxyPort
import com.example.cloudmusic.output.dlna.CastUriResolver
import com.example.cloudmusic.output.dlna.DescriptionFetcher
import com.example.cloudmusic.output.dlna.DirectLinkProvider
import com.example.cloudmusic.output.dlna.HeadProbe
import com.example.cloudmusic.output.dlna.LinkProvider
import com.example.cloudmusic.output.dlna.MulticastLockPort
import com.example.cloudmusic.output.dlna.MulticastTransport
import com.example.cloudmusic.output.dlna.SoapTransport
import com.example.cloudmusic.output.dlna.SsdpScanner
import com.example.cloudmusic.di.proxy.AndroidLanAddressProvider
import com.example.cloudmusic.di.proxy.LanAddressProvider
import com.example.cloudmusic.di.proxy.NanoHttpStreamProxy
import com.example.cloudmusic.di.settings.SettingsProjection
import com.example.cloudmusic.di.settings.SettingsStore
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.output.localproxy.InMemoryLocalHttpStreamProxy
import com.example.cloudmusic.output.localproxy.LocalHttpStreamProxy
import com.example.cloudmusic.output.localproxy.ProxySettings
import com.example.cloudmusic.output.localproxy.TokenRegistry
import com.example.cloudmusic.storage.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * docs/12 §25（第十六批）· **T-P2 / T-P3 端口的唯一生产接线点**。
 *
 * 03-01 §4.1 规定 Hilt 注册点只能在 `:core-di`，本 Module 与 `DataSourceModule` / `AppBindings`
 * 同处该模块；`OkHttpClient` 复用 [DataSourceModule.provideOkHttpClient]，不重复绑定。
 *
 * 边界：nanohttpd 服务本体 / 局域网 IP / `PlaybackService` / Room / DataStore 仍属 T-A 后续批次；
 * 本批只把「端口 → 真实实现 → 组合对象」这条链路接通，UI 与 Service 调用方零改动即可替换。
 */
@Module
@InstallIn(SingletonComponent::class)
object BridgeModule {

    // ---- T-P2：IDataSource 四态的三个外部端口 --------------------------------

    @Provides
    @Singleton
    fun provideRangeHttpStack(client: OkHttpClient): RangeHttpStack = OkHttpRangeHttpStack(client)

    @Provides
    @Singleton
    fun provideFdOpener(@ApplicationContext context: Context): FdOpener =
        ContentResolverFdOpener(context.contentResolver)

    @Provides
    @Singleton
    fun provideDriverStreamFactory(client: OkHttpClient): DriverStreamFactory =
        OkHttpProxiedStreamFactory(client)

    @Provides
    @Singleton
    fun provideDataSourceFactory(
        http: RangeHttpStack,
        fd: FdOpener,
        streams: DriverStreamFactory,
    ): DataSourceFactory = DataSourceFactory.production(http, fd, streams)

    // ---- T-P3：DLNA 投屏的端口 ----------------------------------------------

    @Provides
    @Singleton
    fun provideCachingHeadProbe(client: OkHttpClient): CachingHeadProbe =
        CachingHeadProbe(OkHttpHeadProbe(client))

    @Provides
    fun provideHeadProbe(probe: CachingHeadProbe): HeadProbe = probe

    @Provides
    @Singleton
    fun provideDescriptionFetcher(client: OkHttpClient): DescriptionFetcher =
        OkHttpDescriptionFetcher(client)

    @Provides
    @Singleton
    fun provideSoapTransport(client: OkHttpClient): SoapTransport = OkHttpSoapTransport(client)

    @Provides
    @Singleton
    fun provideMulticastTransport(): MulticastTransport = DatagramMulticastTransport()

    @Provides
    @Singleton
    fun provideMulticastLock(@ApplicationContext context: Context): MulticastLockPort =
        WifiMulticastLockPort(context)

    @Provides
    @Singleton
    fun provideSsdpScanner(
        transport: MulticastTransport,
        lock: MulticastLockPort,
    ): SsdpScanner = SsdpScanner(transport, lock)

    // ---- 组合对象 ------------------------------------------------------------

    /**
     * 08 §6 / 03-05 §4：T-A2 起 `ProxySettings` 是 `AppSettings` 的运行时投影，
     * 持久值仍只存一份（`network.forceWebdavProxy` / `dlna.preferDirectLink`）。
     */
    @Provides
    @Singleton
    fun provideProxySettings(store: SettingsStore): ProxySettings =
        SettingsProjection.proxy(store.current())

    /** C-13：publish / resolve 必须共享同一个 token 实例，故由 Hilt 单点提供。 */
    @Provides
    @Singleton
    fun provideTokenRegistry(): TokenRegistry = TokenRegistry()

    @Provides
    @Singleton
    fun provideLanAddress(provider: AndroidLanAddressProvider): LanAddressProvider = provider

    /** 第十六批的内存实现保留给纯 JVM 语义单测与保守降级，不再进入生产主链。 */
    @Provides
    @Singleton
    fun provideInMemoryProxy(factory: DataSourceFactory): InMemoryLocalHttpStreamProxy =
        InMemoryLocalHttpStreamProxy(
            dataSourceFactory = { link -> factory.create(link, LinkArgs()) },
        )

    @Provides
    @Singleton
    fun provideNanoHttpStreamProxy(
        registry: TokenRegistry,
        factory: DataSourceFactory,
        lan: LanAddressProvider,
        tracks: TrackRepository,
    ): NanoHttpStreamProxy = NanoHttpStreamProxy(
        registry = registry,
        dataSourceFactory = { link -> factory.create(link, LinkArgs()) },
        coverProvider = { trackId -> readCover(tracks, trackId) },
        lanAddress = lan,
    )

    /** C-13：生产换绑 nanohttpd 本体（局域网 IP + 真实端口 + Range 字节流）。 */
    @Provides
    fun provideLocalHttpStreamProxy(proxy: NanoHttpStreamProxy): LocalHttpStreamProxy = proxy

    @Provides
    @Singleton
    fun providePrePublishedCastProxy(proxy: NanoHttpStreamProxy): PrePublishedCastProxy =
        PrePublishedCastProxy(proxy)

    @Provides
    fun provideCastProxyPort(proxy: PrePublishedCastProxy): CastProxyPort = proxy

    /**
     * 03-08 §8.1 `/cover/{token}`：读 T-A4 落盘的 WebP。
     * `Track.coverRef` 是 DB 里存的本地路径；HTTP 线程上是本地 Room 查询，
     * 不涉及网络、不写日志（07 §8）。查不到或读失败返回 null → 404 回落。
     */
    private fun readCover(tracks: TrackRepository, trackId: String): ByteArray? {
        val path = runCatching {
            runBlocking(Dispatchers.IO) { tracks.get(TrackId(trackId)) }?.coverRef
        }.getOrNull() ?: return null
        if (path.isBlank()) return null
        return runCatching { File(path).readBytes() }.getOrNull()
    }

    @Provides
    @Singleton
    fun provideLinkProvider(): LinkProvider = RegistryLinkProvider()

    @Provides
    @Singleton
    // C-08（12 §31）：改用带 Track 上下文的真实 directUrl(obj) 探测；
    // ConservativeDirectLinkProvider 仍保留在源码中，供纯 JVM 保守语义测试使用。
    fun provideDirectLinkProvider(): DirectLinkProvider = RegistryDirectLinkProvider()

    @Provides
    @Singleton
    fun provideCastUriResolver(
        settings: ProxySettings,
        proxy: CastProxyPort,
        links: LinkProvider,
        directLinks: DirectLinkProvider,
        probe: HeadProbe,
        store: SettingsStore,
    ): CastUriResolver = CastUriResolver(
        settings = settings,
        proxy = proxy,
        links = links,
        directLinks = directLinks,
        headProbe = probe,
        // C-09（12 §27）：每次 resolve 读当前设置投影，让 08 §7 的
        // forceWebdavProxy / preferDirectLink 变更在「下一首曲目」即时生效。
        settingsSource = { SettingsProjection.proxy(store.current()) },
    )
}
