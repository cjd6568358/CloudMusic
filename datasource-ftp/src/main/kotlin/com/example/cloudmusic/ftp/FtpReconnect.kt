package com.example.cloudmusic.ftp

import kotlinx.coroutines.delay

/**
 * §61.243（V0.3 M4）：FTP 断线重连退避——**纯 JVM 判定 + 装饰器**（§61.228 挂账收口）。
 *
 * 背景（§61.228 生产端口一手）：commons-net 端口此前把传输异常吞掉（list → 空表 /
 * readBytes → null）且连接死了永不重连——断线后扫描悄悄得到空目录、tag 全缺、link 中途
 * 失败，用户看到的是「曲库清空」而不是错误。本件把「传输失败」变成可区分、可自愈的信号：
 *
 * - **判定**（[FtpReconnectPolicy]，纯 JVM 可测）：指数退避 1s → 2s → 4s（2^n 上限 30s），
 *   最多 [FtpReconnectPolicy.maxAttempts] 次重连；认证失败（[FtpConnectResult.AUTH_FAILED]）
 *   **不重试**（凭据错重试无意义，快速可见）；总退避上限 7s/操作（3 次），不把扫描拖成
 *   长挂（§61.155 长稳教训）。
 * - **装饰器**（[ReconnectingFtpClientPort]）：包住任意 [FtpClientPort]。操作抛异常
 *   （= 传输失败；空结果 / null 是服务器语义，不算失败）→ 按策略重连 → 重试；重连失败
 *   继续退避；耗尽次数回落原 fallback（驱动既有 runCatching 语义不变）。
 *
 * 生产装配（:core-di）：`ReconnectingFtpClientPort(CommonsNetFtpPort(config))`；
 * CommonsNetFtpPort 侧传输异常从吞掉改为**传播**（list/stat 的 null = 服务器 550 语义
 * 保持不变），驱动侧 runCatching → DsUpstream 链零改动。
 */
object FtpReconnectPolicy {

    /** 最多重连尝试次数（含失败后的下轮；不含首次操作）。 */
    const val MAX_ATTEMPTS: Int = 3

    /** 单次退避上限（未来调参防抖）。 */
    const val MAX_DELAY_MS: Long = 30_000L

    /** 指数退避：attempt 1 → 1s，2 → 2s，3 → 4s…（2^n，封顶 [MAX_DELAY_MS]）。 */
    fun delayMs(attempt: Int): Long {
        val n = attempt.coerceIn(1, 16)
        return (1_000L shl (n - 1)).coerceAtMost(MAX_DELAY_MS)
    }
}

/**
 * 断线自愈装饰器（纯 JVM）：传输异常 → 退避重连 → 重试；耗尽回落 [fallback]。
 * 空结果 / null（服务器语义）原样透传，不触发重连。
 */
class ReconnectingFtpClientPort(
    private val delegate: FtpClientPort,
    private val maxAttempts: Int = FtpReconnectPolicy.MAX_ATTEMPTS,
) : FtpClientPort {

    override suspend fun connect(): Boolean = delegate.connect()

    override suspend fun connectDetailed(): FtpConnectResult = delegate.connectDetailed()

    override suspend fun list(path: String): List<FtpEntry> =
        withReconnect(fallback = emptyList()) { delegate.list(path) }

    override suspend fun stat(path: String): FtpEntry? =
        withReconnect(fallback = null) { delegate.stat(path) }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? =
        withReconnect(fallback = null) { delegate.readBytes(path, offset, length) }

    override fun close() = delegate.close()

    private suspend fun <T> withReconnect(fallback: T, op: suspend () -> T): T {
        try {
            return op()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce   // 结构化并发：取消必须透传，重连循环不得吞掉 scope.cancel。
        } catch (first: Exception) {
            // 传输失败 → 退避重连循环；认证失败立即回落（凭据错重试无意义）。
            var attempt = 1
            while (attempt <= maxAttempts) {
                delay(FtpReconnectPolicy.delayMs(attempt))
                when (delegate.connectDetailed()) {
                    FtpConnectResult.AUTH_FAILED -> return fallback
                    FtpConnectResult.OK -> {
                        try {
                            return op()
                        } catch (ce2: kotlinx.coroutines.CancellationException) {
                            throw ce2
                        } catch (retryFailed: Exception) {
                            // 重连成功但操作再败 → 进入下一轮退避。
                        }
                    }
                    FtpConnectResult.NETWORK_ERROR -> Unit
                }
                attempt++
            }
            return fallback
        }
    }
}
