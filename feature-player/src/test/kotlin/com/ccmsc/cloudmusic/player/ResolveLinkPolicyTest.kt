package com.ccmsc.cloudmusic.player

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §61.304（K1b）：link() 三级容错策略纯 JVM 用例——
 * 常规失败重试 1 次 / 429 限流随机退避 / 二次失败传播 / 限流判定。
 */
class ResolveLinkPolicyTest {

    private fun track() = Track(
        id = TrackId("id-1"), title = "t", artist = "a", album = "b",
        durationMs = 1_000L,
        sourceRef = com.ccmsc.cloudmusic.model.SourceRef("s", "/p.flac"),
        sourceType = com.ccmsc.cloudmusic.model.SourceType.LOCAL,
    )

    private class FlakyResolver(
        private val failures: Int,
        private val error: Exception,
        private val link: Link = Link(localPath = "/a.flac"),
    ) : TrackSourceResolver {
        var attempts = 0
        override suspend fun resolve(track: Track): Link {
            attempts++
            if (attempts <= failures) throw error
            return link
        }
    }

    @Test
    @DisplayName("§61.304 K1b · 常规失败重试 1 次成功（非限流不退避）")
    fun retryOnceOnPlainFailure() = runTest {
        val inner = FlakyResolver(1, CloudMusicError.DsUnreachable("down"))
        val delays = mutableListOf<Long>()
        val policy = ResolveLinkPolicy(
            inner,
            backoffRandom = { 3_333L },
            delayFn = { delays.add(it) },
        )
        assertEquals("/a.flac", policy.resolve(track()).localPath)
        assertEquals(2, inner.attempts, "失败 1 次 + 重试 1 次")
        assertTrue(delays.isEmpty(), "非限流失败立即重试（无退避）")
    }

    @Test
    @DisplayName("§61.304 K1b · 429 限流退避 2~6s 后重试成功")
    fun rateLimitBackoffThenRetry() = runTest {
        val inner = FlakyResolver(1, CloudMusicError.DsUpstream("IO", "音源 HTTP 429"))
        val delays = mutableListOf<Long>()
        val policy = ResolveLinkPolicy(
            inner,
            backoffRandom = { 4_200L },
            delayFn = { delays.add(it) },
        )
        assertEquals("/a.flac", policy.resolve(track()).localPath)
        assertEquals(2, inner.attempts)
        assertEquals(listOf(4_200L), delays, "限流走退避（注入固定随机值）")
    }

    @Test
    @DisplayName("§61.304 K1b · 二次失败传播末次错误")
    fun doubleFailurePropagates() = runTest {
        val inner = FlakyResolver(99, CloudMusicError.DsUnreachable("always down"))
        val policy = ResolveLinkPolicy(inner, backoffRandom = { 2_000L }, delayFn = { })
        val e = runCatching { policy.resolve(track()) }.exceptionOrNull()
        assertTrue(e is CloudMusicError, "末次错误原样抛出")
        assertEquals(2, inner.attempts, "1 次初始 + 1 次重试，不多不少")
    }

    @Test
    @DisplayName("§61.304 K1b · 限流判定：HTTP 429 文案 / RATE 码 / 非 429 不误判")
    fun rateLimitClassification() {
        assertTrue(ResolveLinkPolicy.isRateLimited(CloudMusicError.DsUpstream("IO", "音源 HTTP 429")))
        assertTrue(ResolveLinkPolicy.isRateLimited(CloudMusicError.DsUpstream("E_RATE", "限流")))
        assertTrue(ResolveLinkPolicy.isRateLimited(java.io.IOException("HTTP 429")))
        assertFalse(ResolveLinkPolicy.isRateLimited(CloudMusicError.DsUnreachable("down")))
        assertFalse(ResolveLinkPolicy.isRateLimited(CloudMusicError.DsUpstream("IO", "音源 HTTP 500")))
    }
}
