package com.example.cloudmusic.player

/**
 * §61.303（Kernel 2.0 K1 / §61.299 方案 X2 风险项对策）：K 线定长参数**唯一出口**。
 * lx-music-mobile 行为对齐的可否决表（§61.299 §10-E 已拍板默认值，无否决）——
 * 一次拍板可全局改：所有 K 线常量只在此定义，消费方不得自造魔法数。
 */
object KernelTuning {
    /** K1a：手动切歌防抖（lx 800ms，本项目取更灵敏的 500ms，§10-E）。 */
    const val MANUAL_SWITCH_DEBOUNCE_MS = 500L

    /** K1d：通知元数据发布防抖（lx debounceUpdateMetaInfoTools 500ms，治快速切歌通知栏错乱）。 */
    const val METADATA_DEBOUNCE_MS = 500L

    /** K1c：自动跳曲延迟窗（lx delayNextTimeout 5000ms；窗内人工干预立即让位）。 */
    const val AUTO_SKIP_DELAY_MS = 5_000L

    /** K1f：同一会话连续 autoSkip 失败护栏阈值（第 N 次起停止自动跳曲并人话提示）。 */
    const val CONSECUTIVE_SKIP_FAIL_LIMIT = 5

    // ---- K1b（下一批 §61.304）在此补位：限流退避 2~6s / link 总超时 100s ----
}
