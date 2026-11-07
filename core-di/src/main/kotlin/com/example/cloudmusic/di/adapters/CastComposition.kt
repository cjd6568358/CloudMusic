package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.bridge.DataSourceAdapter
import com.example.cloudmusic.bridge.DriverStreamFactory
import com.example.cloudmusic.bridge.FdOpener
import com.example.cloudmusic.bridge.HttpDataSource
import com.example.cloudmusic.bridge.RangeHttpStack
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.DirectLinkCapability
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.output.dlna.CastProxyPort
import com.example.cloudmusic.output.dlna.DirectLinkProvider
import com.example.cloudmusic.output.dlna.LinkProvider
import com.example.cloudmusic.output.localproxy.InMemoryLocalHttpStreamProxy
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 第十六批：把 T-P2 / T-P3 的端口收口成**组合层可用的对象**（`12 §25`）。
 * 全部纯 JVM，不引用 Android 框架；`BridgeModule` 负责注入与生命周期。
 */

/** `DataSourceAdapter.from(Link)` 的生产工厂：固化三个端口，供代理每请求回源使用。 */
fun interface DataSourceFactory {
    fun create(link: Link, args: LinkArgs): IDataSource

    companion object {
        /** 显式 object 实现而非伴生对象内的 SAM 简写，避免 Kotlin 名称解析歧义。 */
        fun production(
            http: RangeHttpStack,
            fd: FdOpener,
            streams: DriverStreamFactory,
        ): DataSourceFactory = object : DataSourceFactory {
            override fun create(link: Link, args: LinkArgs): IDataSource =
                DataSourceAdapter.from(link, args, http, fd, streams)
        }
    }
}

/**
 * **C-07 的直接消费方**：PROXIED 态的 `Link` 不把回源 URL 放在 `url` 字段（否则 kind 变 URL），
 * 而放在 `proxySourceUrl`。这里复制成 URL 态 Link（保留鉴权 headers）交给 [HttpDataSource]，
 * 由 OkHttp 带 Range 回源 —— 不新建第二套网络栈（03-08 §8.3 / 架构 §7.2）。
 */
class OkHttpProxiedStreamFactory(private val client: OkHttpClient) : DriverStreamFactory {
    override fun open(link: Link): IDataSource {
        val origin = link.proxySourceUrl?.takeIf { it.isNotBlank() }
            ?: throw CloudMusicError.DsUnreachable(
                "PROXIED Link 缺少 proxySourceUrl，无法回源（C-07：驱动 link() 必须填入）",
            )
        val sourceLink = link.copy(url = origin, proxy = false, requireReference = false)
        return HttpDataSource(sourceLink, LinkArgs(), OkHttpRangeHttpStack(client))
    }
}

/**
 * 03-08 §3.2：`Link` 必须重新构建、不缓存。已初始化的驱动按 `SourceRef.storageId` 取出并
 * `get(path) → link(obj)`；strm / 播放列表等 URL 条目无驱动实例时按 `SourceRef.url` 重建。
 * 异常信息不含路径、URL 与凭据（`07 §8`）。
 */
class RegistryLinkProvider : LinkProvider {
    override suspend fun link(track: Track): Link {
        val ref = track.sourceRef
        val driver = DriverRegistry.get(ref.storageId)
        if (driver != null) {
            val obj = driver.get(ref.path)
                ?: Obj(name = ref.path.substringAfterLast('/'), path = ref.path)
            return driver.link(obj)
        }
        ref.url?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return Link(url = it) }
        throw CloudMusicError.DsUnreachable("数据源尚未初始化，无法重建 Link")
    }
}

/**
 * 03-08 §3.1 第 3 行的 `DirectLinkCapability.directUrl(obj)` 需要 `(driver, Obj)` 上下文，
 * 而 `DirectLinkProvider` 只收 `Link`。V0.1 组合层先采取**保守策略**：PROXIED 返回 null
 * → 一律走本地代理（决策表第 4 行，行为正确只是少一次直链优化）；匿名直链探测待 T-A5
 * 把 Track 上下文透传后启用（已在 `12 §25` 登记为 C-08）。
 */
class ConservativeDirectLinkProvider : DirectLinkProvider {
    override suspend fun directUrl(link: Link): String? =
        if (link.kind == Link.Kind.URL) link.url.takeIf { it.isNotBlank() } else null
}

/**
 * **C-08 的生产落地（`12 §31`）**：`CastUriResolver.resolve(track)` 本来就有 Track 上下文，
 * 这里按 `track.sourceRef` 反查 `(DriverRegistry 实例, Obj)`，再调
 * [DirectLinkCapability.directUrl] 做**不带鉴权头**的匿名直链探测。
 *
 * - `URL` 态原样直推（决策表第 2 行，与保守实现一致）；
 * - `PROXIED` 且驱动实现该能力 → 真实探测；未实现 / `preferProxy` / 异常 → 返回 null，
 *   由 `CastUriResolver` 走代理（决策表第 4 行），行为正确只是少一次优化；
 * - 不缓存 Link、不记录 URL 或凭据（07 §8）。
 */
class RegistryDirectLinkProvider : DirectLinkProvider {
    override suspend fun directUrl(link: Link): String? =
        if (link.kind == Link.Kind.URL) link.url.takeIf { it.isNotBlank() } else null

    override suspend fun directUrl(link: Link, track: Track?): String? {
        if (track == null || link.kind != Link.Kind.PROXIED) return directUrl(link)
        val ref = track.sourceRef
        val driver = DriverRegistry.get(ref.storageId) ?: return null
        // 03-02 §4.6 / 10 §2 R6：驱动级「强制代理」优先于能力探测。
        if (driver.config.preferProxy) return null
        val capability = driver as? DirectLinkCapability ?: return null
        val obj = try {
            driver.get(ref.path)
        } catch (e: Exception) {
            null
        } ?: Obj(name = ref.path.substringAfterLast('/'), path = ref.path)
        return try {
            capability.directUrl(obj)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * C-13（`12 §31`）：把「代理后端」抽象出来，让同一份 [PrePublishedCastProxy] 组合逻辑
 * 既能接第十六批的内存实现（纯 JVM 单测 / 保守降级），也能接 T-A5 的 nanohttpd 本体。
 * `coverUrlFor` 对应 03-08 §6.3 的 DIDL-Lite `albumArtURI`。
 */
interface CastProxyBackend {
    fun publish(trackId: String, link: Link): String
    fun coverUrlFor(trackId: String): String?
    fun ensureStarted()
    fun urlFor(trackId: String): String
    fun stop()
    fun isRunning(): Boolean
}

/** 内存后端适配器：`InMemoryLocalHttpStreamProxy` 不提供封面字节源，故封面 URL 为 null。 */
class InMemoryCastProxyBackend(
    private val delegate: InMemoryLocalHttpStreamProxy,
) : CastProxyBackend {
    override fun publish(trackId: String, link: Link): String = delegate.publish(trackId, link)
    override fun coverUrlFor(trackId: String): String? = null
    override fun ensureStarted() = delegate.ensureStarted()
    override fun urlFor(trackId: String): String = delegate.urlFor(trackId)
    override fun stop() = delegate.stop()
    override fun isRunning(): Boolean = delegate.isRunning()
}

/**
 * `CastProxyPort` 与本地代理后端的桥梁：后端要求先 `publish(trackId, link)` 才有 `urlFor`，
 * 而 `CastUriResolver` 只认 trackId。组合层在切歌时调 [publish]，
 * `resolve` 即可拿到稳定代理 URL；未发布时显式报错，不静默给错 URL（03-08 §14 #1）。
 */
class PrePublishedCastProxy(
    private val backend: CastProxyBackend,
) : CastProxyPort {

    private val published = ConcurrentHashMap<String, Link>()

    fun publish(trackId: String, link: Link): String {
        published[trackId] = link
        return backend.publish(trackId, link)
    }

    override fun ensureStarted() = backend.ensureStarted()

    override fun urlFor(trackId: String): String {
        // ConcurrentHashMap 的 `contains` 在 Kotlin 中语义模糊（KT-18053），显式用 containsKey。
        if (!published.containsKey(trackId)) {
            throw CloudMusicError.DsUnreachable("曲目尚未发布到本地代理（需先 publish）")
        }
        return backend.urlFor(trackId)
    }

    override fun coverUrlFor(trackId: String): String? =
        if (!published.containsKey(trackId)) null else backend.coverUrlFor(trackId)

    fun stop() {
        published.clear()
        backend.stop()
    }

    fun isRunning(): Boolean = backend.isRunning()
}
