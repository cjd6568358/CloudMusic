package com.ccmsc.cloudmusic.datasource

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.common.HashUtil
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.LinkArgs
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.Storage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * §61.338（B-6）：远程只读驱动的共用「缓存落地 + 路径沙箱 + 文本回退」层。
 *
 * SFTP（§61.216）与 FTP（§61.228）此前各自逐字实现 `list/get/readBytes/readText/link/
 * readAll/cacheName/full/toObj/upstream/doDrop`，约 130 行 × 2。现统一在这里实现一次，
 * 子类只剩「端口差异」：配置读取、建连、三个远端动作与 Entry 投影。NFS（V1.0）后续
 * 接入时只需再提供一个端口实现，不再复制缓存链。
 *
 * 行为等价是硬约束：错误前缀 `PROTOCOL_ACTION`、缓存子目录 `protocol.lowercase()`、
 * 文件名哈希种子、256KiB 分块、size 校验、UTF-8 → GB18030 回退全部照搬原实现。
 */

/** 各端口 Entry 的最小公共投影；子类在远端动作里做一次转换。 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val modified: Long,
)

abstract class CachedRemoteDriver(
    storage: Storage,
    additionJson: String,
    private val cacheDir: File,
    /** SFTP / FTP：同时决定错误前缀、缓存子目录与未初始化提示。 */
    protected val protocol: String,
    override val config: DriverConfig,
) : AbstractDriver(storage, additionJson), ByteRangeCapability, TextReadCapability {

    /** 服务端根目录（Addition.root_folder_path / root_path），空串表示根。 */
    protected abstract val rootPath: String

    /** 客户端是否就绪；三个远端动作与 link 的就绪判定都由这里给。 */
    protected abstract val isReady: Boolean
    protected abstract suspend fun remoteList(path: String): List<RemoteEntry>
    protected abstract suspend fun remoteStat(path: String): RemoteEntry?
    protected abstract suspend fun remoteRead(path: String, offset: Long, length: Int): ByteArray?
    protected abstract fun closeClient()

    protected fun checkReady() {
        if (!isReady) throw notReady()
    }

    final override fun doDrop() {
        runCatching { closeClient() }
    }

    final override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        checkReady()
        val path = full(dir.path)
        return runCatching { remoteList(path) }.getOrElse { throw upstream("LIST", it) }
            .filter { bool("show_hidden") || !it.name.startsWith(".") }
            .map { it.toObj() }
            .sortedBy { it.name.lowercase() }
    }

    final override suspend fun get(path: String): Obj? {
        checkReady()
        val entry = runCatching { remoteStat(full(path)) }.getOrElse { throw upstream("STAT", it) }
        return entry?.toObj()
    }

    final override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        if (!isReady) return null
        return runCatching { remoteRead(full(file.path), offset, length) }.getOrNull()
    }

    final override suspend fun readText(file: Obj): String {
        val bytes = readAll(file.path) ?: throw CloudMusicError.DsNotFound(file.path)
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.any { it.code == 0xFFFD }) String(bytes, Charset.forName("GB18030")) else utf8
    }

    /** 远端 → 本地缓存 → LOCAL Link；命中且 size 一致时零远端读取。 */
    final override suspend fun link(file: Obj, args: LinkArgs): Link {
        checkReady()
        val remote = full(file.path)
        val dir = File(cacheDir, protocol.lowercase())
        val cached = File(dir, cacheName(file))
        if (cached.isFile && (file.size <= 0L || cached.length() == file.size)) {
            return localLink(cached, file.size.takeIf { it > 0 } ?: cached.length())
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw upstream("CACHE", IllegalStateException("缓存目录创建失败"))
        }
        val tmp = File(dir, "${cached.name}.part")
        try {
            FileOutputStream(tmp).use { out ->
                var offset = 0L
                while (true) {
                    val chunk = runCatching { remoteRead(remote, offset, CHUNK) }
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
        if (!isReady) return null
        val remote = full(path)
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = runCatching { remoteRead(remote, offset, CHUNK) }.getOrNull() ?: return null
            if (chunk.isEmpty()) break
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray()
    }

    /** 文件名只含 storage id + 相对路径 + size/modified 的哈希，不含 host / 凭据。 */
    private fun cacheName(file: Obj): String {
        val seed = "${storage.id}|${PathRule.normalizePath(file.path)}|${file.size}|${file.modified}"
        val hash = HashUtil.sha256(seed).take(32)
        val ext = file.name.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        return if (ext == null) hash else "$hash.$ext"
    }

    private fun localLink(file: File, size: Long) =
        Link(localPath = file.absolutePath, localSize = size)

    /** 相对路径 → 服务端绝对路径；越界路径直接抛 DsNotSupport（PathRule 二次校验）。 */
    protected fun full(relative: String): String {
        val rel = PathRule.normalizePath(relative).trimStart('/')
        val root = rootPath
        val result = if (root.isEmpty()) "/$rel" else "$root/$rel".trimEnd('/')
        if (root.isNotEmpty()) PathRule.requirePathWithin(root, result)
        return result
    }

    protected fun RemoteEntry.toObj(): Obj = Obj(
        name = name,
        size = size,
        isDir = isDir,
        modified = modified,
        path = if (rootPath.isEmpty()) PathRule.normalizePath(path).trimStart('/')
        else PathRule.relativeTo(rootPath, path),
    )

    protected fun notReady() = IllegalStateException("$protocol driver 未初始化: ${storage.id}")

    protected fun upstream(action: String, e: Throwable) =
        CloudMusicError.DsUpstream("${protocol}_$action", e.message ?: "$protocol $action 失败")

    protected companion object {
        const val CHUNK = 256 * 1024
    }
}
