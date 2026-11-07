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
 *
 * **契约演进 C-09（2026-11-06 第十八批 / `12 §27`，T-A2）**：末尾新增可选 [settingsSource]。
 * DataStore 落地后设置会变，而 Hilt 注入的 [settings] 是启动快照；提供该 lambda 后
 * `resolve()` 每次读取当前投影，使 08 §7 的 `INVALIDATE_DIRECT_URL_CACHE` /
 * `RESOLVE_NEXT_TRACK` 在下一首曲目即时生效。默认 `null` → 行为与第十五批逐字一致
 * （不改 14 方法门面、不改任何既有测试）。
 */
class CastUriResolver(
    private val settings: ProxySettings,
    private val proxy: CastProxyPort,
    private val links: LinkProvider,
    private val directLinks: DirectLinkProvider,
    private val headProbe: HeadProbe,
    /** 驱动级 `DriverConfig.preferProxy`（03-02 §4.6 / 10 §2 R6）。 */
    private val driverPreferProxy: Boolean = false,
    /** C-09：动态设置投影；null 时用 [settings] 启动快照（零回归）。 */
    private val settingsSource: (() -> ProxySettings)? = null,
) {
    suspend fun resolve(track: Track): CastUri {
        val link = links.link(track)
        val current = settingsSource?.invoke() ?: settings
        return when {
            // §3.1：LOCAL 必须代理（DMR 读不到手机文件系统）；TEXT 退化为代理
            link.kind == Link.Kind.LOCAL || link.kind == Link.Kind.TEXT -> viaProxy(track)

            // T06：preferDirectLink=false 时除 LOCAL 外全部强制代理（调试开关）
            !current.preferDirectLink -> viaProxy(track)

            // T02：URL 态原样直推，探测失败只标回落候选，不阻止直推
            link.kind == Link.Kind.URL -> direct(link.url)

            // PROXIED：先按开关决定是否探测 directUrl()
            else -> {
                if (current.forceWebdavProxy || driverPreferProxy) {
                    viaProxy(track)
                } else {
                    // C-08：把 Track 上下文透传给端口，让真实 directUrl(obj) 探测可用。
                    val url = directLinks.directUrl(link, track)
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

    /**
     * C-13（`12 §31`）：DIDL-Lite `albumArtURI`（03-08 §6.3 / §8.1 `/cover/{token}`）。
     * 默认 null → 既有 fake 零回归；生产由本地代理给出与曲目同一 token 的封面 URL。
     */
    fun coverUrlFor(trackId: String): String? = null
}

/** 重新构建 `Link`，不缓存（§3.2）。 */
fun interface LinkProvider {
    suspend fun link(track: Track): Link
}

/**
 * `DirectLinkCapability.directUrl()` 的端口；null 表示不具备匿名直连条件。
 *
 * **C-08（`12 §31`）**：能力接口需要 `(driver, Obj)`，而单参数版本只拿得到 [Link]。
 * 增加带 [Track] 的默认重载（默认转发单参数 → 既有 fake / 保守实现零回归）；
 * 生产实现按 `track.sourceRef` 反查 `(driver, Obj)` 后调真实能力，
 * 不改变本类任何决策分支（03-08 §3.1）。
 */
fun interface DirectLinkProvider {
    suspend fun directUrl(link: Link): String?

    suspend fun directUrl(link: Link, track: Track?): String? = directUrl(link)
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
