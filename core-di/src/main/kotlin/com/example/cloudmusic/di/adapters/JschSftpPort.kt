package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.sftp.SftpClientFactory
import com.example.cloudmusic.sftp.SftpClientPort
import com.example.cloudmusic.sftp.SftpConfig
import com.example.cloudmusic.sftp.SftpEntry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Vector

/**
 * V0.2 / §61.216：SFTP 生产传输端口（JSch）。
 *
 * 纪律：① 凭据只在 [Session] 内存中，不写日志、不放 Obj / TrackId / 缓存文件名；
 * ② 连接与读写超时固定，服务器无响应必须失败而不是挂死（§61.178 教训）；
 * ③ ChannelSftp 非线程安全，用 [Mutex] 串行化；④ 任何异常收敛为端口契约的
 * false / null / 空列表，由驱动决定降级，不把 JSch 异常外泄到扫描链。
 */
class JschSftpClientFactory : SftpClientFactory {
    override fun create(config: SftpConfig): SftpClientPort = JschSftpPort(config)
}

private class JschSftpPort(private val config: SftpConfig) : SftpClientPort {

    private val mutex = Mutex()
    private var session: Session? = null
    private var channel: ChannelSftp? = null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val s = jsch.getSession(config.username, config.host, config.port)
            // V0.2 §61.219：私钥优先（与 password 二选一），口令只在内存中交给 JSch。
            val key = config.privateKey?.takeIf { it.isNotBlank() }
            if (key != null) {
                jsch.addIdentity(
                    "cloudmusic-sftp",
                    key.toByteArray(Charsets.UTF_8),
                    null,
                    config.passphrase?.toByteArray(Charsets.UTF_8),
                )
            } else {
                s.setPassword(config.password)
            }
            val props = java.util.Properties().apply {
                put("StrictHostKeyChecking", "no")
                put(
                    "PreferredAuthentications",
                    if (key != null) "publickey,keyboard-interactive,password"
                    else "password,keyboard-interactive,publickey",
                )
            }
            s.setConfig(props)
            s.connect(CONNECT_TIMEOUT_MS)
            val c = s.openChannel("sftp") as ChannelSftp
            c.connect(CHANNEL_TIMEOUT_MS)
            session = s
            channel = c
            true
        }.onFailure { e ->
            // §61.223：连接/认证失败必须可见——吞异常无法归因（§61.178 教训同族）。
            runCatching {
                android.util.Log.w("CM-Scan", "sftp connect 失败: ${e::class.java.simpleName}: ${e.message}")
            }
        }.getOrDefault(false)
    }

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = channel ?: return@withLock emptyList()
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val raw = c.ls(path) as Vector<ChannelSftp.LsEntry>
                raw.mapNotNull { e ->
                    val name = e.filename
                    if (name == "." || name == "..") null
                    else SftpEntry(
                        name = name,
                        path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                        size = e.attrs.size,
                        isDir = e.attrs.isDir,
                        modified = e.attrs.mTime.toLong(),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun stat(path: String): SftpEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = channel ?: return@withLock null
            runCatching {
                val attrs = c.lstat(path)
                SftpEntry(
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = attrs.size,
                    isDir = attrs.isDir,
                    modified = attrs.mTime.toLong(),
                )
            }.getOrNull()
        }
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val c = channel ?: return@withLock null
                if (offset < 0 || length <= 0) return@withLock null
                // JSch 的 get(String, monitor, skip) 返回从 skip 起的只读流；
                // 在锁内读完，避免并发元数据线程共用 ChannelSftp。
                runCatching {
                    c.get(path, null, offset).use { it.readNBytes(length) }
                }.getOrNull()
            }
        }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val CHANNEL_TIMEOUT_MS = 15_000
    }
}
