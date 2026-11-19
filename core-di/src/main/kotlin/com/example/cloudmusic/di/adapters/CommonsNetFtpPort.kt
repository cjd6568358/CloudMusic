package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.ftp.FtpClientFactory
import com.example.cloudmusic.ftp.FtpClientPort
import com.example.cloudmusic.ftp.FtpConfig
import com.example.cloudmusic.ftp.FtpConnectResult
import com.example.cloudmusic.ftp.FtpEntry
import com.example.cloudmusic.ftp.ReconnectingFtpClientPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File

/**
 * §61.228：commons-net FTP 生产端口（对齐 §61.216 JschSftpPort 同族纪律）。
 *
 * - 连接 / 读写超时固定（§61.178 教训：无响应必须失败而不是挂死）；
 * - 被动模式（PASV，客户端侧防火墙友好）+ binary 传输（音频是二进制）；
 * - FTPClient 非线程安全：[Mutex] 串行化；
 * - 区间读走 REST（setRestartOffset）——readAll/link 缓存链的 offset 分块依赖它；
 * - 凭据只在 FTPClient 内存，不写日志（07 §8）。
 */
class CommonsNetFtpClientFactory : FtpClientFactory {
    /** §61.243：生产端口包断线自愈装饰器（传输异常退避重连；服务器语义原样透传）。 */
    override fun create(config: FtpConfig): FtpClientPort =
        ReconnectingFtpClientPort(CommonsNetFtpPort(config))
}

private class CommonsNetFtpPort(private val config: FtpConfig) : FtpClientPort {

    private val mutex = Mutex()
    private var client: FTPClient? = null

    override suspend fun connect(): Boolean =
        connectDetailed() == FtpConnectResult.OK

    /** §61.243：结果细分——登录失败=认证错（重连策略不重试）；连接/握手失败=网络错。 */
    override suspend fun connectDetailed(): FtpConnectResult = withContext(Dispatchers.IO) {
        runCatching {
            // §61.235：ssl=true 走 FTPSClient（显式 TLS，AUTH TLS → PBSZ → PROT）；false = 明文。
            val c = if (config.ssl) org.apache.commons.net.ftp.FTPSClient(true) else FTPClient()
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.defaultTimeout = CONNECT_TIMEOUT_MS
            c.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                c.disconnect()
                return@runCatching FtpConnectResult.NETWORK_ERROR
            }
            if (!c.login(config.username, config.password)) {
                c.disconnect()
                return@runCatching FtpConnectResult.AUTH_FAILED
            }
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            c.setConnectTimeout(CONNECT_TIMEOUT_MS)
            c.setDataTimeout(DATA_TIMEOUT_MS)
            client = c
            FtpConnectResult.OK
        }.getOrElse { FtpConnectResult.NETWORK_ERROR }
    }

    override suspend fun list(path: String): List<FtpEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = client ?: return@withLock emptyList()
            // §61.243：传输异常（IOException）**传播**给装饰器重连；listFiles 返回 null =
            // 服务器 550 语义（路径不存在）→ 空表，两者不再混淆。
            runCatching {
                val files = c.listFiles(path) ?: return@runCatching emptyList<FtpEntry>()
                files.mapNotNull { f ->
                    val name = f.name
                    if (name == "." || name == "..") null
                    else FtpEntry(
                        name = name,
                        path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                        size = f.size,
                        isDir = f.isDirectory,
                        modified = f.timestamp?.time?.time ?: 0L,
                    )
                }
            }.getOrElse { throw it }
        }
    }

    override suspend fun stat(path: String): FtpEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = client ?: return@withLock null
            // §61.243：传输异常传播；null（路径不存在 / 无条目）= 服务器语义保持。
            runCatching {
                // FTP 无独立 stat 命令口径的通用实现——listFiles 单项（服务器支持 MDTM/SIZE
                // 时 commons-net 走精确命令；否则落 LIST 解析）。
                c.listFiles(path).firstOrNull()?.let { f ->
                    FtpEntry(
                        name = f.name,
                        path = path,
                        size = f.size,
                        isDir = f.isDirectory,
                        modified = f.timestamp?.time?.time ?: 0L,
                    )
                }
            }.getOrElse { throw it }
        }
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val c = client ?: return@withLock null
                if (offset < 0 || length <= 0) return@withLock null
                // §61.243：传输异常传播（装饰器重连）；null（completePendingCommand false /
                // 流为空）= 服务器语义保持。
                runCatching {
                    c.setRestartOffset(offset)
                    c.retrieveFileStream(path)?.use { stream ->
                        val data = stream.readNBytes(length)
                        // 读取完成后必须吃掉响应（226），否则下一条命令读到脏流
                        if (!c.completePendingCommand()) return@withLock null
                        data
                    }
                }.getOrElse { throw it }
            }
        }

    override fun close() {
        runCatching {
            client?.logout()
            client?.disconnect()
        }
        client = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val DATA_TIMEOUT_MS = 30_000
    }
}

/** 缓存目录口径与 SFTP 同族（12 §61.216）：cacheDir/ftp/<hash>。 */
internal fun ftpCacheDir(base: File): File = File(base, "ftp")
