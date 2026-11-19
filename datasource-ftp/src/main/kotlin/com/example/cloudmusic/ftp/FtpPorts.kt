package com.example.cloudmusic.ftp

/**
 * docs/03-02 §V0.2 · FTP 传输端口（纯 JVM，不触网；12 §61.228 对齐 §61.216 SFTP 同型）。
 *
 * 驱动只认这几个动作；生产实现由 `:core-di` 用 commons-net FTPClient 提供，
 * JVM 单测用 fake。凭据只出现在 [FtpConfig]，禁止进任何日志 / Obj 字段 / TrackId。
 */
data class FtpConfig(
    val host: String,
    val port: Int = 21,
    val username: String,
    val password: String,
    val root: String,
    /** §61.235：显式 TLS（FTPS）——false = 明文 FTP。 */
    val ssl: Boolean = false,
)

/** 一次目录项的最小投影；路径为服务端绝对路径（含 root 前缀）。 */
data class FtpEntry(
    val name: String,
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val modified: Long,
)

/** §61.243（V0.3 M4）：连接结果细分——重连策略据此区分「认证失败不重试」与「网络错误退避重试」。 */
enum class FtpConnectResult { OK, AUTH_FAILED, NETWORK_ERROR }

interface FtpClientPort {
    /** 建连 + 认证（PASV + binary）；失败返回 false（驱动抛 DsUpstream，不静默降级空曲库）。 */
    suspend fun connect(): Boolean

    /**
     * §61.243：连接结果细分。默认实现回落布尔口径（false 归 NETWORK_ERROR——
     * 生产端口覆写以区分认证失败；不覆写时重连策略最多多试几轮，语义保守不破坏）。
     */
    suspend fun connectDetailed(): FtpConnectResult =
        if (connect()) FtpConnectResult.OK else FtpConnectResult.NETWORK_ERROR

    suspend fun list(path: String): List<FtpEntry>
    suspend fun stat(path: String): FtpEntry?
    /** 读 [offset, offset+length)（REST 命令）；到达文件尾返回空数组，失败返回 null。 */
    suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray?
    fun close()
}

/** 每次 init 新建一个客户端（驱动不跨 Storage 复用连接，凭据隔离）。 */
fun interface FtpClientFactory {
    fun create(config: FtpConfig): FtpClientPort
}
