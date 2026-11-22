package com.example.cloudmusic.player

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Track
import kotlin.random.Random

/**
 * §61.304（K1b）：link() 三级容错策略（lx 取 URL 容错对齐，方案 §6-K1b）。
 *
 * 链路：常规失败重试 [KernelTuning.LINK_RETRY_ONCE] 次 → 429/限流按
 * [KernelTuning.RATE_LIMIT_BACKOFF_MIN_MS]~[KernelTuning.RATE_LIMIT_BACKOFF_MAX_MS]
 * 随机退避后重试 → 直链失效重签（resolve 无状态：每次重试即产出新签链，§61.268 回环
 * 代理口径）。总超时由调用方 `withTimeoutOrNull(KernelTuning.LINK_TOTAL_TIMEOUT_MS)`
 * 兜底；竞态指纹由调用方 prepare 代次承载（本类只管容错重试）。
 *
 * 纯 JVM：随机源与延迟均可注入（单测用固定值 / 虚拟时间）。
 */
class ResolveLinkPolicy(
    private val inner: TrackSourceResolver,
    /** 退避随机源（测试注入固定值；生产均匀分布 [MIN, MAX)）。 */
    private val backoffRandom: () -> Long = {
        KernelTuning.RATE_LIMIT_BACKOFF_MIN_MS +
            Random.nextLong(
                KernelTuning.RATE_LIMIT_BACKOFF_MAX_MS - KernelTuning.RATE_LIMIT_BACKOFF_MIN_MS,
            )
    },
    /** 延迟注入（测试虚拟时间 / 生产协程 delay）。 */
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : TrackSourceResolver {

    override suspend fun resolve(track: Track): Link {
        var lastError: Exception? = null
        for (attempt in 0..KernelTuning.LINK_RETRY_ONCE) {
            try {
                return inner.resolve(track)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 总超时 / 协程取消不得被重试循环吞掉（否则 withTimeoutOrNull 永不返回）
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < KernelTuning.LINK_RETRY_ONCE && isRateLimited(e)) {
                    delayFn(backoffRandom())
                }
                // 非限流失败：立即重试（重试即重签——resolve 每次调用产出新链）
            }
        }
        throw lastError ?: IllegalStateException("resolve 链耗尽未返回")
    }

    companion object {
        /**
         * 429/限流判定：生产 HTTP 栈的限流直报文案（`音源 HTTP 429`，OkHttpAdapters
         * `音源 HTTP ${resp.code}` 口径）或错误码显式 RATE 标注。
         */
        internal fun isRateLimited(e: Exception): Boolean {
            if (e !is CloudMusicError) return e.message?.contains("429") == true
            return e.code.contains("429") || e.code.contains("RATE") ||
                e.message?.contains("429") == true
        }
    }
}
