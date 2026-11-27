package com.ccmsc.cloudmusic.engineopensource

/**
 * §61.378（U1-C）：缓存预热决策——纯逻辑、可 JVM 单测。
 *
 * 旧判据 `upstreamLen > cache.cacheSpace` 的两处语义错误（真机 2026-11-26/27 一手，
 * analysis/review-2026-11-26 M6）：
 * 1. `SimpleCache.getCacheSpace()` 是**已存储总量**而非剩余容量——空缓存（0B）下
 *    3.4MB MP3 都被跳过，warmer「分段下载」在冷装设备上形同虚设；
 * 2. 长度未知（-1）时守卫失效放行——292MB FLAC（>256MiB cap）照样硬暖，LRU 边写
 *    边淘汰，跑完只剩 11MB，预热带宽白烧（§61.320 想拦的就是这类）。
 *
 * 修正口径：与**剩余容量**（cap − 已存量）比较；长度未知一律不暖。
 */
object WarmPolicy {

    data class Decision(
        val warm: Boolean,
        val reason: String,
        val upstreamLen: Long,
        val storedBytes: Long,
    )

    /**
     * @param upstreamLen 上游文件总长（ContentMetadata）；未知（≤0）不暖——容量无从判定。
     * @param storedBytes `SimpleCache.getCacheSpace()`（已存储总量）。
     * @param capBytes    evictor 容量（[Media3EngineAdapter.CACHE_CAP_BYTES]）。
     */
    fun decide(upstreamLen: Long, storedBytes: Long, capBytes: Long): Decision {
        val remaining = (capBytes - storedBytes).coerceAtLeast(0L)
        return when {
            upstreamLen <= 0L -> Decision(false, "len-unknown", upstreamLen, storedBytes)
            upstreamLen > remaining -> Decision(false, "over-remaining", upstreamLen, storedBytes)
            else -> Decision(true, "fit", upstreamLen, storedBytes)
        }
    }
}
