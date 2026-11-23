package com.example.cloudmusic.model

/**
 * §61.239：CUE 分轨相对时间三口纯函数——**唯一真值口径**。
 *
 * §61.226 把三口放在 app 侧 [com.example.cloudmusic.ui.player.PlayerStateBridge]（UI 显示面）；
 * §61.239 把同一口径下沉到 core-model，供会话快照侧（feature-player 的
 * `media?.setPlaybackState` / `publishMetadata` 发布链）共用，避免双实现漂移。
 * app 侧三口改为委托本对象，签名与判据零变化。
 */
object CueEnvelope {

    /**
     * 分轨显示时长 = 包络长（end - start）；整轨（包络全 0）/ 无界 / 异常包络
     * （end ≤ 0 或 end > base）原样返回 [baseMs]，零行为变化。
     */
    fun displayDuration(baseMs: Long, cueStartMs: Long, cueEndMs: Long): Long = when {
        cueStartMs <= 0 -> baseMs
        cueEndMs > cueStartMs && cueEndMs <= baseMs -> cueEndMs - cueStartMs
        // §61.320（真机 2026-11-24）：末轨 cue 无终点（end=0）时，旧实现原样返回
        // baseMs（= 整个镜像时长）——播放页把 73:45 的整文件时长当本轨时长显示
        // （「好人好梦」实测 73:45，实际应为 4:17）。end=0 = 播到文件尾，
        // 只有这一种情形用 baseMs - cueStartMs 兜底。
        cueEndMs <= 0 && cueStartMs < baseMs -> baseMs - cueStartMs
        // end 超过引擎真值（数据异常）：保持 §61.226 原样，不裁剪。
        else -> baseMs
    }

    /** 分轨显示位置 = absolute - cueStart（0 起点）；整轨 / 包络前原样。 */
    fun displayPosition(posMs: Long, cueStartMs: Long): Long =
        if (cueStartMs > 0 && posMs >= cueStartMs) posMs - cueStartMs else posMs

    /** 显示相对时间 → absolute（+cueStart）；引擎侧 clampToCue 双向兜底。 */
    fun absoluteSeek(displayMs: Long, cueStartMs: Long): Long = displayMs + cueStartMs

    /** [Track] 便捷重载：显示时长（整轨原样）。 */
    fun displayDuration(baseMs: Long, track: Track?): Long =
        if (track == null) baseMs else displayDuration(baseMs, track.cueStartMs, track.cueEndMs)

    /** [Track] 便捷重载：显示位置（整轨原样）。 */
    fun displayPosition(posMs: Long, track: Track?): Long =
        if (track == null) posMs else displayPosition(posMs, track.cueStartMs)

    /** [Track] 便捷重载：相对 → absolute。 */
    fun absoluteSeek(displayMs: Long, track: Track?): Long =
        displayMs + (track?.cueStartMs ?: 0L)
}
