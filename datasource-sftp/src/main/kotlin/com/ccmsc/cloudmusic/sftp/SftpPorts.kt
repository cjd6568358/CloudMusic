package com.ccmsc.cloudmusic.sftp

/**
 * docs/03-02 §V0.2 · SFTP 传输端口（纯 JVM，不触网）。
 *
 * 驱动只认这四个动作；生产实现由 `:core-di` 用 JSch 提供，JVM 单测用 fake。
 * 凭据只出现在 [SftpConfig]，禁止进任何日志 / Obj 字段 / TrackId。
 */
data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val root: String,
    /** PEM 私钥内容（V0.2 §61.219）；与 password 二选一，私钥优先。 */
    val privateKey: String? = null,
    /** 私钥口令（passphrase），仅在私钥认证时使用。 */
    val passphrase: String? = null,
)

/** 一次目录项的最小投影；路径为服务端绝对路径（含 root 前缀）。 */
data class SftpEntry(
    val name: String,
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val modified: Long,
)

interface SftpClientPort {
    /** 建连 + 认证；失败返回 false（驱动抛 DsUpstream，不静默降级为空曲库）。 */
    suspend fun connect(): Boolean
    suspend fun list(path: String): List<SftpEntry>
    suspend fun stat(path: String): SftpEntry?
    /** 读 [offset, offset+length)；到达文件尾返回空数组，失败返回 null。 */
    suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray?
    fun close()
}

/** 每次 init 新建一个客户端（驱动不跨 Storage 复用连接，凭据隔离）。 */
fun interface SftpClientFactory {
    fun create(config: SftpConfig): SftpClientPort
}
