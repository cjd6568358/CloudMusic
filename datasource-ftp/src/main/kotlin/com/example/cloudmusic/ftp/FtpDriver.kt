package com.example.cloudmusic.ftp

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.CachedRemoteDriver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.RemoteEntry
import com.example.cloudmusic.model.Storage
import java.io.File

/**
 * V0.2 FTP 数据源（docs/03-02 §V0.2，12 §61.228）：只读驱动，§61.216 SFTP 同型。
 *
 * - 传输经 [FtpClientFactory] 注入，本类纯 JVM、零网络依赖（commons-net 在 :core-di 生产端口）；
 * - `list/get/readBytes/readText/link` 的缓存落地 / 路径沙箱 / 编码回退共用
 *   [CachedRemoteDriver]（§61.338 B-6）；本类只保留端口差异：配置、建连、三个远端动作；
 * - FTP 无私钥（§61.219 对应物不存在）；username 空按 anonymous（口令任意）。
 */
class FtpDriver(
    storage: Storage,
    additionJson: String,
    private val clientFactory: FtpClientFactory,
    cacheDir: File,
) : CachedRemoteDriver(
    storage, additionJson, cacheDir, "FTP",
    DriverConfig(name = "FTP", localSort = true, preferProxy = false),
) {

    @Volatile
    private var client: FtpClientPort? = null

    private val host: String get() = strCompat("host", "address").trim()
    private val port: Int get() = int("port", 21).takeIf { it in 1..65535 } ?: 21
    private val username: String get() = str("username").trim().ifBlank { "anonymous" }
    private val password: String get() = str("password")
    override val rootPath: String get() = strCompat("root_folder_path", "root_path").trim().trimEnd('/')
    /** §61.235：显式 TLS（FTPS）。 */
    private val ssl: Boolean get() = bool("ssl")

    override val isReady: Boolean get() = client != null

    override suspend fun doInit(ctx: DriverCtx) {
        if (host.isEmpty()) throw CloudMusicError.DsInvalidConfig("FTP host 为空")
        val created = clientFactory.create(
            FtpConfig(
                host = host, port = port, username = username,
                password = password, root = rootPath, ssl = ssl,
            ),
        )
        if (!runCatching { created.connect() }.getOrDefault(false)) {
            runCatching { created.close() }
            throw CloudMusicError.DsUpstream("CONNECT", "FTP 连接或认证失败: $host:$port")
        }
        client = created
        ctx.setErrDetail("FTP 已就绪: $host:$port")
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

    private fun FtpEntry.toRemote() = RemoteEntry(name, path, size, isDir, modified)
}
