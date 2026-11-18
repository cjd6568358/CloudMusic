package com.example.cloudmusic.sftp

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.common.HashUtil
import com.example.cloudmusic.datasource.AbstractDriver
import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.PathRule
import com.example.cloudmusic.datasource.TextReadCapability
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * V0.2 SFTP 数据源（docs/03-02 §V0.2，12 §61.216）：只读驱动。
 *
 * - 传输经 [SftpClientFactory] 注入，本类纯 JVM、零网络依赖（JSch 在 :core-di 生产端口）；
 * - `list/get` 只读目录与 stat；`readBytes/readText` 支撑扫描期 head tag 与 .lrc；
 * - `link` 把远端文件流式拉到 `cacheDir/sftp/<hash>` 后返回 **LOCAL Link**——
 *   本机播放与 DLNA 本地代理都能复用（PROXIED 的 `proxySourceUrl` 只支持 http 回源，
 *   SFTP 不能套用，故不伪造 PROXIED）；
 * - 缓存文件名只含 storage id + 相对路径 + size/modified 的哈希，不含 host / 凭据。
 */
class SftpDriver(
    storage: Storage,
    additionJson: String,
    private val clientFactory: SftpClientFactory,
    /** 本地落地缓存目录（由 Hilt 传 context.cacheDir；单测传 @TempDir）。 */
    private val cacheDir: File,
) : AbstractDriver(storage, additionJson), ByteRangeCapability, TextReadCapability {

    override val config = DriverConfig(name = "SFTP", localSort = true, preferProxy = false)

    @Volatile
    private var client: SftpClientPort? = null

    private val host: String get() = strCompat("host", "address").trim()
    private val port: Int get() = int("port", 22).takeIf { it in 1..65535 } ?: 22
    private val username: String get() = str("username")
    private val password: String get() = str("password")
    private val rootPath: String get() = strCompat("root_folder_path", "root_path").trim().trimEnd('/')
    /** PEM 私钥：Addition 里允许把换行写成 `\n` 以便单行保存。 */
    private val privateKey: String get() = str("private_key").replace("\\n", "\n").trim()
    private val passphrase: String get() = str("passphrase")

    override suspend fun doInit(ctx: DriverCtx) {
        if (host.isEmpty()) throw CloudMusicError.DsInvalidConfig("SFTP host 为空")
        if (username.isEmpty()) throw CloudMusicError.DsInvalidConfig("SFTP username 为空")
        if (password.isEmpty() && privateKey.isEmpty()) {
            throw CloudMusicError.DsInvalidConfig("SFTP 需提供 password 或 private_key 之一")
        }
        val created = clientFactory.create(
            SftpConfig(
                host = host, port = port, username = username, password = password, root = rootPath,
                privateKey = privateKey.takeIf { it.isNotEmpty() },
                passphrase = passphrase.takeIf { it.isNotEmpty() },
            ),
        )
        if (!runCatching { created.connect() }.getOrDefault(false)) {
            runCatching { created.close() }
            throw CloudMusicError.DsUpstream("CONNECT", "SFTP 连接或认证失败: $host:$port")
        }
        client = created
        ctx.setErrDetail("SFTP 已就绪: $host:$port")
    }

    override fun doDrop() {
        runCatching { client?.close() }
        client = null
    }

    override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        val port = client ?: throw notReady()
        val path = full(dir.path)
        return runCatching { port.list(path) }.getOrElse { throw upstream("LIST", it) }
            .filter { bool("show_hidden") || !it.name.startsWith(".") }
            .map { it.toObj() }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun get(path: String): Obj? {
        val port = client ?: throw notReady()
        val entry = runCatching { port.stat(full(path)) }.getOrElse { throw upstream("STAT", it) }
        return entry?.toObj()
    }

    override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        val port = client ?: return null
        return runCatching {
            port.readBytes(full(file.path), offset, length)
        }.getOrNull()
    }

    override suspend fun readText(file: Obj): String {
        val bytes = readAll(file.path) ?: throw CloudMusicError.DsNotFound(file.path)
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, Charset.forName("GB18030")) else utf8
    }

    override suspend fun link(file: Obj, args: LinkArgs): Link {
        val port = client ?: throw notReady()
        val remote = full(file.path)
        val dir = File(cacheDir, "sftp")
        val cached = File(dir, cacheName(file))
        if (cached.isFile && (file.size <= 0L || cached.length() == file.size)) {
            return localLink(cached, file.size.takeIf { it > 0 } ?: cached.length())
        }
        if (!dir.exists() && !dir.mkdirs()) throw upstream("CACHE", IllegalStateException("缓存目录创建失败"))
        val tmp = File(dir, "${cached.name}.part")
        try {
            FileOutputStream(tmp).use { out ->
                var offset = 0L
                while (true) {
                    val chunk = runCatching { port.readBytes(remote, offset, CHUNK) }
                        .getOrElse { throw upstream("READ", it) }
                        ?: throw upstream("READ", IllegalStateException("读取返回 null"))
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    offset += chunk.size
                    if (file.size > 0 && offset >= file.size) break
                }
            }
            if (file.size > 0 && tmp.length() != file.size) {
                throw upstream("READ", IllegalStateException("尺寸不符: ${tmp.length()} != ${file.size}"))
            }
            if (!tmp.renameTo(cached) && !cached.isFile) {
                throw upstream("CACHE", IllegalStateException("缓存落盘失败"))
            }
        } finally {
            runCatching { tmp.delete() }
        }
        return localLink(cached, file.size.takeIf { it > 0 } ?: cached.length())
    }

    private suspend fun readAll(path: String): ByteArray? {
        val port = client ?: return null
        val remote = full(path)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = runCatching { port.readBytes(remote, offset, CHUNK) }.getOrNull() ?: return null
            if (chunk.isEmpty()) break
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray()
    }

    private fun cacheName(file: Obj): String {
        val seed = "${storage.id}|${PathRule.normalizePath(file.path)}|${file.size}|${file.modified}"
        val hash = HashUtil.sha256(seed).take(32)
        val ext = file.name.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        return if (ext == null) hash else "$hash.$ext"
    }

    private fun localLink(file: File, size: Long) = Link(localPath = file.absolutePath, localSize = size)

    /** 相对路径 → 服务端绝对路径；越界路径直接抛 DsNotSupport（PathRule 二次校验）。 */
    private fun full(relative: String): String {
        val rel = PathRule.normalizePath(relative).trimStart('/')
        val root = rootPath
        val result = if (root.isEmpty()) "/$rel" else "$root/$rel".trimEnd('/')
        if (root.isNotEmpty()) PathRule.requirePathWithin(root, result)
        return result
    }

    private fun SftpEntry.toObj(): Obj = Obj(
        name = name,
        size = size,
        isDir = isDir,
        modified = modified,
        path = if (rootPath.isEmpty()) PathRule.normalizePath(path).trimStart('/')
        else PathRule.relativeTo(rootPath, path),
    )

    private fun notReady() = IllegalStateException("SFTP driver 未初始化: ${storage.id}")
    private fun upstream(action: String, e: Throwable) =
        CloudMusicError.DsUpstream("SFTP_$action", e.message ?: "SFTP $action 失败")

    private companion object {
        const val CHUNK = 256 * 1024
    }
}
