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
    fun displayDuration(baseMs: Long, cueStartMs: Long, cueEndMs: Long): Long =
        if (cueEndMs > 0 && cueEndMs <= baseMs) cueEndMs - cueStartMs else baseMs

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
