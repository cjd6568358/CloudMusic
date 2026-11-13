package com.example.cloudmusic.model

import kotlin.math.pow

/**
 * `docs/12 §61.101` / C-18 · ReplayGain 的**纯 JVM 值语义**（B2 侧接线尚未实施）。
 *
 * 来源：`12 §61.99` 实证竞品 `libneaudioeffects.so` 内已有
 * `LoudnessEvaluator.init(III)Z` / `push([B I Z)V` / `getReplayGain(I)F`，
 * 即 ReplayGain 计算引擎是现成的；B2 接回需跨层链路
 * （扫描解码 → push → gain → 曲库持久化 → 播放 setVolume）。
 *
 * **边界（务必遵守，防伪造接线）**：
 * - 本文件只定义「增益值 → 目标音量」的确定性换算，是纯值对象；
 * - **当前无生产消费方**：`IMusicEngine` 14 方法门面零改动、
 *   `Media3EngineAdapter` / `PlayerController` 未接入、Room 无 gain 字段；
 * - 不宣称「ReplayGain 已接回 / 已生效」，落地需单独批次按施工单接线并真机回归；
 * - `setVolume` 契约（`03-06 §4`）要求 `0f..1f`，故 [targetVolume] 恒在此区间。
 *
 * 换算：dB → 线性幅度 `10^(dB/20)`；最终音量 =
 * `min(10^((gainDb + preampDb)/20), 1/peak)` 再钳制到 `[0,1]`。
 * `1/peak` 是削波保护（peak 为归一化峰值，可 >1 表示源已削波）。
 */
enum class GainMode { OFF, TRACK, ALBUM }

data class ReplayGain(
    /** 曲目增益（dB），负值表示需要衰减。 */
    val trackGainDb: Float = 0f,
    /** 专辑增益（dB）。 */
    val albumGainDb: Float = 0f,
    /** 曲目峰值（归一化幅度，>0）。 */
    val trackPeak: Float = 1f,
    /** 专辑峰值（归一化幅度，>0）。 */
    val albumPeak: Float = 1f,
) {
    init {
        require(isFinite(trackGainDb)) { "trackGainDb 必须是有限值" }
        require(isFinite(albumGainDb)) { "albumGainDb 必须是有限值" }
        require(isFinite(trackPeak) && trackPeak > 0f) { "trackPeak 必须 > 0 且有限" }
        require(isFinite(albumPeak) && albumPeak > 0f) { "albumPeak 必须 > 0 且有限" }
    }

    /** 按模式取增益 dB；OFF 恒为 0。 */
    fun gainDb(mode: GainMode): Float = when (mode) {
        GainMode.OFF -> 0f
        GainMode.TRACK -> trackGainDb
        GainMode.ALBUM -> albumGainDb
    }

    /** 按模式取峰值；OFF 恒为 1（不施加额外保护）。 */
    fun peak(mode: GainMode): Float = when (mode) {
        GainMode.OFF -> 1f
        GainMode.TRACK -> trackPeak
        GainMode.ALBUM -> albumPeak
    }

    /**
     * 目标播放音量，恒在 `[0f, 1f]`。
     *
     * @param preampDb 用户预放大（dB），与增益相加后一起换算。
     */
    fun targetVolume(mode: GainMode, preampDb: Float = 0f): Float {
        require(isFinite(preampDb)) { "preampDb 必须是有限值" }
        val linear = pow10((gainDb(mode) + preampDb) / 20f)
        val ceiling = 1f / peak(mode)
        return minOf(linear, ceiling).coerceIn(0f, 1f)
    }

    /** 是否有非零增益信息（供上层决定是否持久化，不表示已生效）。 */
    fun hasGain(): Boolean = trackGainDb != 0f || albumGainDb != 0f

    private companion object {
        fun isFinite(v: Float): Boolean = !v.isNaN() && !v.isInfinite()

        /** `10^x`，用 kotlin.math 的 Double 扩展，避免引入额外依赖。 */
        fun pow10(x: Float): Float = 10.0.pow(x.toDouble()).toFloat()
    }
}
