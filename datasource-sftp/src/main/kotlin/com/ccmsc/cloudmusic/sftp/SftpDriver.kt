package com.ccmsc.cloudmusic.sftp

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.datasource.CachedRemoteDriver
import com.ccmsc.cloudmusic.datasource.DriverConfig
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.datasource.RemoteEntry
import com.ccmsc.cloudmusic.model.Storage
import java.io.File

/**
 * V0.2 SFTP 数据源（docs/03-02 §V0.2，12 §61.216）：只读驱动。
 *
 * - 传输经 [SftpClientFactory] 注入，本类纯 JVM、零网络依赖（JSch 在 :core-di 生产端口）；
 * - `list/get/readBytes/readText/link` 的缓存落地 / 路径沙箱 / 编码回退共用
 *   [CachedRemoteDriver]（§61.338 B-6）；本类只保留端口差异：配置、建连、三个远端动作；
 * - 缓存文件名只含 storage id + 相对路径 + size/modified 的哈希，不含 host / 凭据。
 */
class SftpDriver(
    storage: Storage,
    additionJson: String,
    private val clientFactory: SftpClientFactory,
    cacheDir: File,
) : CachedRemoteDriver(
    storage, additionJson, cacheDir, "SFTP",
    DriverConfig(name = "SFTP", localSort = true, preferProxy = false),
) {

    @Volatile
    private var client: SftpClientPort? = null

    private val host: String get() = strCompat("host", "address").trim()
    private val port: Int get() = int("port", 22).takeIf { it in 1..65535 } ?: 22
    private val username: String get() = str("username")
    private val password: String get() = str("password")
    /** PEM 私钥：Addition 里允许把换行写成 `\n` 以便单行保存。 */
    private val privateKey: String get() = str("private_key").replace("\\n", "\n").trim()
    private val passphrase: String get() = str("passphrase")
    override val rootPath: String get() = strCompat("root_folder_path", "root_path").trim().trimEnd('/')

    override val isReady: Boolean get() = client != null

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

    override fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    override suspend fun remoteList(path: String): List<RemoteEntry> =
        (client ?: throw notReady()).list(path).map { it.toRemote() }

    override suspend fun remoteStat(path: String): RemoteEntry? =
        (client ?: throw notReady()).stat(path)?.toRemote()

    override suspend fun remoteRead(path: String, offset: Long, length: Int): ByteArray? {
        val c = client ?: return null
        return c.readBytes(path, offset, length)
    }

    private fun SftpEntry.toRemote() = RemoteEntry(name, path, size, isDir, modified)
}
