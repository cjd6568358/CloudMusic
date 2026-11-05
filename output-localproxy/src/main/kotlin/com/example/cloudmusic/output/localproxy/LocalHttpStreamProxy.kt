package com.example.cloudmusic.output.localproxy

import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link

/**
 * docs/03-08 §8「nanohttpd 本地流代理」的**端口接口**。
 * Android 侧用 `class LocalHttpStreamProxy : NanoHTTPD(0)` 实现；
 * 本模块的 [InMemoryLocalHttpStreamProxy] 只负责纯 JVM 可测的路由 / 头部 / token 语义。
 */
interface LocalHttpStreamProxy {
    fun ensureStarted()
    fun stop()
    fun isRunning(): Boolean
    /** `http://{局域网IP}:{port}/t/{token}`，不暴露真实路径。 */
    fun urlFor(trackId: String): String
    fun serve(request: ProxyRequest): ProxyResponse
}

/**
 * docs/03-08 §8.4 启动条件（2026-11-06 修正版）：纯直链投屏不启动代理，手机只做遥控器
 * （§13 T15 / T16 的静态部分）；**④ 本机播放 `proxy` 态必须启动**（C-06 / `16 §3.2`）。
 *
 * [localPlaybackKind] 是 C-06 新增的第 ④ 输入维度：本机播放曲目的 [Link.Kind]。
 * `null` 表示当前没有本机播放（纯投屏场景），此时行为与第十一批完全一致，
 * 保证投屏侧 ①②③⑤ 的既有判定零回归。
 */
object ProxyStartPolicy {
    fun shouldStart(
        queueKinds: List<Link.Kind>,
        anyViaProxy: Boolean,
        fallbackNeeded: Boolean,
        settings: ProxySettings = ProxySettings(),
        localPlaybackKind: Link.Kind? = null,
    ): Boolean {
        // ④ 本机播放 PROXIED / TEXT：引擎舱无字节流入口，只能经回环代理 URL 自取（13 §3）。
        // 与投屏侧决策相互独立，故优先于 ①②③⑤ 判定；TEXT 按 PROXIED 处理（16 §3.2）。
        if (localPlaybackKind == Link.Kind.PROXIED || localPlaybackKind == Link.Kind.TEXT) return true

        // ⑤ 调试开关 / ②投屏直链失败回落 / ①投屏队列含 LOCAL 或 TEXT
        if (!settings.preferDirectLink || settings.forceWebdavProxy) return true
        if (fallbackNeeded || anyViaProxy) return true
        return queueKinds.any { it == Link.Kind.LOCAL || it == Link.Kind.TEXT }
    }
}

/**
 * 纯 JVM 参考实现：把 nanohttpd 的 `serve(session)` 拆成可单测的 [serve]。
 *
 * 硬约束（§8.3 / `10 §2 R8`）：**每个 HTTP 请求各自新建一个 `IDataSource`**，
 * 绝不跨请求共享这个有状态的单流对象；响应结束即 `close()`。
 */
class InMemoryLocalHttpStreamProxy(
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val registry: TokenRegistry = TokenRegistry(),
    private val dataSourceFactory: (Link) -> IDataSource,
    private val coverProvider: (String) -> ByteArray? = { null },
) : LocalHttpStreamProxy {

    private var running = false
    private val urls = LinkedHashMap<String, String>()
    private val tokens = LinkedHashMap<String, String>()

    var openedCount = 0
        private set
    var closedCount = 0
        private set

    override fun ensureStarted() {
        running = true
    }

    override fun stop() {
        running = false
        registry.invalidateAll()
        urls.clear()
        tokens.clear()
    }

    override fun isRunning(): Boolean = running

    override fun urlFor(trackId: String): String =
        urls[trackId] ?: error("track not published: $trackId")

    /** 注册曲目并返回可推给 DMR 的代理 URL（按需启动服务）。 */
    fun publish(trackId: String, link: Link): String {
        ensureStarted()
        val key = registry.register(trackId, link)
        tokens[trackId] = key
        val url = "http://$host:$port/t/$key"
        urls[trackId] = url
        return url
    }

    override fun serve(request: ProxyRequest): ProxyResponse {
        if (!running) return RangeResponder.notFound()
        val path = request.path
        val token = when {
            path.startsWith("/t/") -> path.removePrefix("/t/")
            path.startsWith("/cover/") -> path.removePrefix("/cover/")
            else -> return RangeResponder.notFound()
        }
        if (request.method != ProxyMethod.GET && request.method != ProxyMethod.HEAD) {
            return RangeResponder.methodNotAllowed()
        }
        val entry = registry.resolve(token) ?: return RangeResponder.notFound()

        if (path.startsWith("/cover/")) {
            val bytes = coverProvider(entry.trackId) ?: return RangeResponder.notFound()
            val headers = mapOf(
                "Content-Type" to "image/jpeg",
                "Content-Length" to bytes.size.toString(),
            )
            return ProxyResponse(
                ProxyStatus.OK, headers,
                if (request.method == ProxyMethod.HEAD) 0L else bytes.size.toLong(),
            )
        }

        // 每个请求独立开流、独立关闭（禁止跨请求共享 IDataSource）
        val source = dataSourceFactory(entry.link)
        openedCount++
        try {
            val total = source.open(0)
            val mime = source.mimeHint().ifBlank { "application/octet-stream" }
            return RangeResponder.respond(request, total, mime)
        } finally {
            source.close()
            closedCount++
        }
    }
}
