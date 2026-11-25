package com.ccmsc.cloudmusic.ftp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.243（V0.3 M4）：FTP 断线重连退避——策略纯函数 + 装饰器行为的纯 JVM 契约。
 * 全部走 runTest 虚拟时钟：退避 delay 即时跳过，累计虚拟时间断言退避序列。
 */
class FtpReconnectTest {

    /** 脚本化假端口：连接结果与 list 结果按序弹出；记录重连次数。 */
    private class ScriptedPort(
        val connectResults: ArrayDeque<FtpConnectResult>,
        val listScript: ArrayDeque<Result<List<FtpEntry>>>,
    ) : FtpClientPort {
        val connectCalls = mutableListOf<Int>()
        val listCalls = mutableListOf<Int>()
        override suspend fun connect(): Boolean = connectDetailed() == FtpConnectResult.OK
        override suspend fun connectDetailed(): FtpConnectResult {
            connectCalls += connectCalls.size
            return connectResults.removeFirstOrNull() ?: FtpConnectResult.NETWORK_ERROR
        }
        override suspend fun list(path: String): List<FtpEntry> {
            listCalls += listCalls.size
            return listScript.removeFirstOrNull()!!.getOrThrow()
        }
        override suspend fun stat(path: String): FtpEntry? = null
        override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? = null
        override fun close() {}
    }

    private fun port(
        connects: List<FtpConnectResult>,
        lists: List<Result<List<FtpEntry>>>,
    ) = ScriptedPort(ArrayDeque(connects), ArrayDeque(lists))

    @Test
    @DisplayName("§61.243 P1 · 策略：指数退避 1s→2s→4s… 封顶 30s，attempt 钳 1..16")
    fun policyDelays() {
        assertEquals(1_000L, FtpReconnectPolicy.delayMs(1))
        assertEquals(2_000L, FtpReconnectPolicy.delayMs(2))
        assertEquals(4_000L, FtpReconnectPolicy.delayMs(3))
        assertEquals(8_000L, FtpReconnectPolicy.delayMs(4))
        assertEquals(FtpReconnectPolicy.MAX_DELAY_MS, FtpReconnectPolicy.delayMs(6))
        assertEquals(FtpReconnectPolicy.delayMs(1), FtpReconnectPolicy.delayMs(0), "越界钳 1")
        assertEquals(FtpReconnectPolicy.delayMs(16), FtpReconnectPolicy.delayMs(99), "越界钳 16")
        assertEquals(3, FtpReconnectPolicy.MAX_ATTEMPTS)
    }

    @Test
    @DisplayName("§61.243 R1 · list 断线 → 退避 1s 重连 → 重试成功；成功后零重连")
    fun listRecoversAfterBackoff() = runTest {
        val fake = port(
            connects = listOf(FtpConnectResult.OK, FtpConnectResult.OK),
            lists = listOf(
                Result.failure(IOException("connection lost")),
                Result.success(listOf(FtpEntry("a.mp3", "/a.mp3", 100L, false, 0L))),
                Result.success(listOf(FtpEntry("a.mp3", "/a.mp3", 100L, false, 0L))),
            ),
        )
        val decorated = ReconnectingFtpClientPort(fake)
        val before = testScheduler.currentTime
        val out = decorated.list("/x")
        assertEquals(listOf("a.mp3"), out.map { it.name }, "重连后重试成功")
        assertEquals(1_000L, testScheduler.currentTime - before, "单轮退避 1s（首重试即成功）")
        assertEquals(2, fake.listCalls.size, "首败 + 重试一次")
        assertEquals(1, fake.connectCalls.size, "恰好一次重连")
        // 连接已愈 → 后续操作零重连零退避
        val t0 = testScheduler.currentTime
        assertEquals(listOf("a.mp3"), decorated.list("/x").map { it.name })
        assertEquals(0L, testScheduler.currentTime - t0, "成功路径零退避")
        assertEquals(1, fake.connectCalls.size, "不再重连（连接已愈）")
    }

    @Test
    @DisplayName("§61.243 R2 · 认证失败不重试：首轮退避后发现即停，不烧后续轮次")
    fun authFailureGivesUpImmediately() = runTest {
        val fake = port(
            connects = listOf(FtpConnectResult.AUTH_FAILED),
            lists = listOf(Result.failure(IOException("broken pipe"))),
        )
        val decorated = ReconnectingFtpClientPort(fake)
        val before = testScheduler.currentTime
        val out = decorated.list("/x")
        assertEquals(emptyList(), out, "回落原 fallback（驱动既有 runCatching 语义不变）")
        assertEquals(1_000L, testScheduler.currentTime - before, "口径：退避先于连接判定——AUTH 在首轮退避后发现即停（快速可见，不烧 2/4s 轮次）")
        assertEquals(1, fake.connectCalls.size, "只试一次连接")
        assertEquals(1, fake.listCalls.size)
    }

    @Test
    @DisplayName("§61.243 R3 · 网络错误耗尽 3 次 → 回落；总退避 1s+2s+4s")
    fun networkErrorExhaustsBackoff() = runTest {
        val fake = port(
            connects = List(3) { FtpConnectResult.NETWORK_ERROR },
            lists = listOf(Result.failure(IOException("timeout"))),
        )
        val decorated = ReconnectingFtpClientPort(fake)
        val before = testScheduler.currentTime
        assertEquals(emptyList(), decorated.list("/x"), "耗尽回落")
        assertEquals(1_000L + 2_000L + 4_000L, testScheduler.currentTime - before, "总退避 7s（不拖成长挂）")
        assertEquals(3, fake.connectCalls.size, "恰好 MAX_ATTEMPTS 次重连")
        assertEquals(1, fake.listCalls.size, "重连失败不再重试操作")
    }

    @Test
    @DisplayName("§61.243 R4 · 重连成功但操作再败 → 进入下一轮退避（次轮重试成功）")
    fun retryOpFailsThenNextRoundSucceeds() = runTest {
        val fake = port(
            connects = List(3) { FtpConnectResult.OK },
            lists = listOf(
                Result.failure(IOException("e1")),
                Result.failure(IOException("e2")),
                Result.success(emptyList()),
            ),
        )
        val decorated = ReconnectingFtpClientPort(fake)
        assertEquals(emptyList(), decorated.list("/x"))
        assertEquals(3, fake.listCalls.size, "首败 + 两次重试")
        assertEquals(2, fake.connectCalls.size, "每轮重试前各一次重连判定（1+2s 退避）")
    }

    @Test
    @DisplayName("§61.243 R5 · 空结果 / null（服务器语义）原样透传，不触发重连")
    fun serverSemanticsPassThrough() = runTest {
        val fake = port(
            connects = emptyList(),
            lists = listOf(Result.success(emptyList())),
        )
        val decorated = ReconnectingFtpClientPort(fake)
        val before = testScheduler.currentTime
        assertEquals(emptyList(), decorated.list("/missing"), "空目录（550 语义）不重连")
        assertEquals(0L, testScheduler.currentTime - before)
        assertTrue(fake.connectCalls.isEmpty())
        assertNullish(decorated.readBytes("/f", 0, 10))
    }

    private fun assertNullish(v: ByteArray?) = assertEquals(null, v)
}
