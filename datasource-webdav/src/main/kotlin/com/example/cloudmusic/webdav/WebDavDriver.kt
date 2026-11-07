package com.example.cloudmusic.webdav

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.AbstractDriver
import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DirectLinkCapability
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.PathRule
import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * docs/03-02 §4 · V0.1 只读 WebDAV 驱动。
 * 首次请求恒不带 Authorization；401 后按 WWW-Authenticate 协商 BASIC / DIGEST。
 * 本机播放默认返回 PROXIED（带鉴权流，须经本地代理回环 URL 供引擎自取，`16 §3.2` 触发器 ④）；
 * 投屏由 directUrl() 匿名探测后直推。
 */
class WebDavDriver(
    storage: Storage,
    addition: String,
    private val http: HttpStack,
    /** 08 配置项：用户「WebDAV 强制走代理」开关。 */
    private val forceWebdavProxy: Boolean = false,
) : AbstractDriver(storage, addition), DirectLinkCapability, ByteRangeCapability {

    override val config = DriverConfig(name = "WebDAV", localSort = true, preferProxy = false)

    @Volatile
    private var authMode = AuthMode.NONE
    private var challenge: DigestChallenge? = null
    private var nc = 0
    private val probeCache = ConcurrentHashMap<String, Boolean>()

    private val address: String get() = strCompat("address", "url").trimEnd('/')
    private val rootPath: String get() = strCompat("root_folder_path", "root_path")
    private val username: String get() = str("username")
    private val password: String get() = str("password")

    override suspend fun doInit(ctx: DriverCtx) {
        if (address.isEmpty()) throw CloudMusicError.DsInvalidConfig("WebDAV address 为空")
        authMode = AuthMode.NONE
        challenge = null
        nc = 0
        ctx.setErrDetail("WebDAV 已就绪: $address")
    }

    override fun doDrop() {
        authMode = AuthMode.NONE
        challenge = null
        nc = 0
        probeCache.clear()
    }

    override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        ensureReady()
        val body = propfind(dir.path, depth = 1)
        return PropfindParser.parse(body).map { it.toObj() }
            .filter { bool("show_hidden") || !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun get(path: String): Obj? {
        ensureReady()
        return runCatching {
            PropfindParser.parse(propfind(path, depth = 0), skipFirst = false).firstOrNull()?.toObj()
        }.getOrElse { e -> if (e is CloudMusicError.DsNotFound) null else throw e }
    }

    override suspend fun link(file: Obj, args: LinkArgs): Link {
        ensureReady()
        val url = buildUrl(file.path)
        if (args.redirect) {
            return Link(url = resolve302(url, args.headers), headers = args.headers, contentLength = file.size)
        }
        return Link(
            url = "",
            headers = authHeaders("GET", url),
            proxy = true,
            contentLength = file.size,
            requireReference = true,
            // C-07：PROXIED 必须携带回源 URL，否则本地代理每请求回源时无从定位。
            // url 字段留空是为了让 kind 仍判定为 PROXIED（03-08 §3.1 决策表）。
            proxySourceUrl = url,
        )
    }

    /** 03-02 §4.6：匿名 HEAD 探一次，200 才直推；结果按 (path, modified) 缓存。 */
    override suspend fun directUrl(file: Obj): String? {
        if (forceWebdavProxy) return null
        if (authMode != AuthMode.NONE) return null
        val candidate = buildUrl(file.path)
        val key = "${file.path}|${file.modified}"
        probeCache[key]?.let { cached -> return if (cached) candidate else null }
        val ok = runCatching {
            http.execute(WebDavRequest("HEAD", candidate)).status in 200..299
        }.getOrDefault(false)
        probeCache[key] = ok
        return if (ok) candidate else null
    }

    /**
     * C-12（`12 §31` / `03-04 §3`）：扫描网络音源时**只取指定区间**，禁止下载整文件。
     *
     * 复用 [executeWithAuth] 的 BASIC / DIGEST 三态协商（Range 请求同样需要鉴权头），
     * 不新建第二套网络栈。服务端忽略 Range 返回 200 时，从完整响应里按 offset 切片，
     * 避免把整个文件当成头部交给解析器。失败一律返回 null，由 `HeadTagReader` 降级（B01 / B16）。
     */
    override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        ensureReady()
        val request = WebDavRequest(
            method = "GET",
            url = buildUrl(file.path),
            headers = mapOf("Range" to "bytes=$offset-${offset + length - 1}"),
        )
        val response = runCatching { executeWithAuth(request) }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        val bytes = response.bodyBytes ?: return null
        if (response.status == 206) return bytes
        // 200：服务端不支持 Range，只能本地切片；仍不把整个文件交给上层。
        val from = offset.coerceAtMost(bytes.size.toLong()).toInt()
        val to = (offset + length).coerceAtMost(bytes.size.toLong()).toInt()
        return if (from >= to) ByteArray(0) else bytes.copyOfRange(from, to)
    }

    // ------------------------------------------------------------------
    private suspend fun propfind(path: String, depth: Int): String {
        val request = WebDavRequest(
            method = "PROPFIND",
            url = buildUrl(sandbox(path)),
            headers = mapOf("Depth" to depth.toString(), "Content-Type" to "application/xml"),
            body = PROPFIND_BODY,
        )
        val response = executeWithAuth(request)
        if (response.status == 404) throw CloudMusicError.DsNotFound(path)
        if (response.status !in 200..299) {
            throw CloudMusicError.DsUpstream("${response.status}", "PROPFIND 失败, status=${response.status}")
        }
        return response.body
    }

    /** 三态协商：NONE → (Basic|Digest) → 再遇 401 回退 NONE，最多 3 次。 */
    private suspend fun executeWithAuth(request: WebDavRequest): WebDavResponse {
        repeat(3) {
            val response = http.execute(withAuth(request))
            if (response.status != 401) return response
            val header = response.header("WWW-Authenticate")
                ?: throw CloudMusicError.DsAuthFailed("401 且缺少 WWW-Authenticate, status=401")
            if (authMode != AuthMode.NONE) {
                authMode = AuthMode.NONE
                challenge = null
                nc = 0
            } else {
                when {
                    header.startsWith("Basic", ignoreCase = true) -> authMode = AuthMode.BASIC
                    header.startsWith("Digest", ignoreCase = true) -> {
                        challenge = DigestChallenge.parse(header)
                            ?: throw CloudMusicError.DsAuthFailed("Digest 挑战解析失败")
                        authMode = AuthMode.DIGEST
                    }
                    else -> throw CloudMusicError.DsAuthFailed("不支持的认证方式: ${header.take(24)}")
                }
            }
        }
        throw CloudMusicError.DsAuthFailed("401 协商 3 次仍失败")
    }

    private fun withAuth(request: WebDavRequest): WebDavRequest {
        val headers = request.headers.toMutableMap()
        when (authMode) {
            AuthMode.NONE -> Unit
            AuthMode.BASIC -> headers["Authorization"] = DigestAuth.basic(username, password)
            AuthMode.DIGEST -> {
                val ch = challenge ?: return request.copy(headers = headers)
                nc++
                val uri = runCatching { URI(request.url).rawPath }.getOrDefault("/")
                headers["Authorization"] = DigestAuth.header(
                    ch, username, password, request.method, uri, nc,
                    UUID.randomUUID().toString().take(8),
                )
            }
        }
        return request.copy(headers = headers)
    }

    private fun authHeaders(method: String, url: String): List<Header> =
        withAuth(WebDavRequest(method, url)).headers
            .filterKeys { it.equals("Authorization", ignoreCase = true) }
            .map { (key, value) -> Header(key, value) }

    /** 只接受 302/307/308；无 Location 或超过 5 跳抛 REDIRECT_FAILED。 */
    private suspend fun resolve302(url: String, extra: List<Header>): String {
        var current = url
        val headers = extra.associate { it.name to it.value }
        repeat(5) {
            val response = http.execute(WebDavRequest("HEAD", current, headers))
            val location = response.header("Location")
            if (location.isNullOrEmpty()) {
                if (response.status in 300..399) {
                    throw CloudMusicError.DsUpstream("REDIRECT_FAILED", "重定向失败: 302 无 Location")
                }
                return current
            }
            current = runCatching { URI(current).resolve(location).toString() }.getOrDefault(location)
        }
        throw CloudMusicError.DsUpstream("REDIRECT_FAILED", "重定向超过 5 跳")
    }

    private fun sandbox(path: String): String {
        val root = rootPath.trim('/')
        val clean = path.trim('/')
        val full = when {
            root.isEmpty() || clean == root || clean.startsWith("$root/") -> clean
            else -> "$root/$clean"
        }
        val normalized = PathRule.normalizePath("/$full")
        PathRule.requirePathWithin("/$root", normalized)
        return normalized.trimStart('/')
    }

    private fun buildUrl(path: String): String {
        val clean = path.trim('/')
        if (clean.isEmpty()) return "$address/"
        val encoded = clean.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$address/$encoded"
    }

    private fun PropfindItem.toObj(): Obj {
        val path = if (href.startsWith("http", ignoreCase = true)) {
            runCatching { URI(href).path }.getOrDefault(href)
        } else href
        return Obj(
            name = name,
            size = size,
            isDir = isDir,
            modified = modified,
            path = path,
            priv = contentType,
        )
    }

    companion object {
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop><D:resourcetype/><D:getcontentlength/>
                <D:getlastmodified/><D:getcontenttype/></D:prop>
            </D:propfind>
        """.trimIndent()
    }
}
