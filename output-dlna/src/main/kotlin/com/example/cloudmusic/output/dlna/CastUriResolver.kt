package com.example.cloudmusic.output.dlna

import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.output.localproxy.ProxySettings

/**
 * docs/03-08 §3.1 / §3.2 **决策表（★核心）**。
 *
 * 端口隔离：`Link` 重建、直链能力探测、匿名 HEAD 预探测、代理 URL 生成全部可注入 fake，
 * 纯 JVM 不触网、不依赖 Android / nanohttpd 本体（`16 §5.2` 判定 T-P3 可先行）。
 * 设置直接复用 [ProxySettings]：`08 §6` 规定 `forceWebdavProxy` 以 `network` 为唯一持久来源，
 * 此处是它的运行时投影，不另存一份。
 */
class CastUriResolver(
    private val settings: ProxySettings,
    private val proxy: CastProxyPort,
    private val links: LinkProvider,
    private val directLinks: DirectLinkProvider,
    private val headProbe: HeadProbe,
    /** 驱动级 `DriverConfig.preferProxy`（03-02 §4.6 / 10 §2 R6）。 */
    private val driverPreferProxy: Boolean = false,
) {
    suspend fun resolve(track: Track): CastUri {
        val link = links.link(track)
        return when {
            // §3.1：LOCAL 必须代理（DMR 读不到手机文件系统）；TEXT 退化为代理
            link.kind == Link.Kind.LOCAL || link.kind == Link.Kind.TEXT -> viaProxy(track)

            // T06：preferDirectLink=false 时除 LOCAL 外全部强制代理（调试开关）
            !settings.preferDirectLink -> viaProxy(track)

            // T02：URL 态原样直推，探测失败只标回落候选，不阻止直推
            link.kind == Link.Kind.URL -> direct(link.url)

            // PROXIED：先按开关决定是否探测 directUrl()
            else -> {
                if (settings.forceWebdavProxy || driverPreferProxy) {
                    viaProxy(track)
                } else {
                    val url = directLinks.directUrl(link)
                    if (url.isNullOrBlank()) viaProxy(track) else direct(url)
                }
            }
        }
    }

    private suspend fun direct(url: String): CastUri {
        val passed = headProbe.preProbe(url)
        return CastUri(
            uri = url,
            viaProxy = false,
            fallback = if (passed) CastFallback.NONE else CastFallback.PROXY_ON_FAILURE,
            directProbePassed = passed,
        )
    }

    private fun viaProxy(track: Track): CastUri {
        proxy.ensureStarted()
        return CastUri(uri = proxy.urlFor(track.id.value), viaProxy = true)
    }
}

/** 本地代理端口：`ensureStarted` / `urlFor` 由 `:output-localproxy` 或 Android 侧实现。 */
interface CastProxyPort {
    fun ensureStarted()
    fun urlFor(trackId: String): String
}

/** 重新构建 `Link`，不缓存（§3.2）。 */
fun interface LinkProvider {
    suspend fun link(track: Track): Link
}

/** `DirectLinkCapability.directUrl()` 的端口；null 表示不具备匿名直连条件。 */
fun interface DirectLinkProvider {
    suspend fun directUrl(link: Link): String?
}

/**
 * `preProbe(url)`：不带任何鉴权头的 HEAD，2s 超时，2xx/3xx 才算通过。
 * 探测失败**不阻止直推**（部分 DMR 能拉、手机拉不通），只把 fallback 标成回代理。
 *
 * `03-08 §3.2` 提到的 `(url, track.updatedAt)` 缓存键中，`Track` 模型并无 `updatedAt` 字段；
 * 纯 JVM 阶段按 url 缓存放在实现侧（如 [CachingHeadProbe]），设置项变更时由调用方清空。
 */
fun interface HeadProbe {
    suspend fun preProbe(url: String): Boolean
}

/** 可选缓存装饰器：按 url 记忆探测结果，避免每首歌都 HEAD 一次。 */
class CachingHeadProbe(private val delegate: HeadProbe) : HeadProbe {
    private val cache = HashMap<String, Boolean>()

    override suspend fun preProbe(url: String): Boolean =
        cache.getOrPut(url) { delegate.preProbe(url) }

    fun invalidate(url: String? = null) {
        if (url == null) cache.clear() else cache.remove(url)
    }
}
