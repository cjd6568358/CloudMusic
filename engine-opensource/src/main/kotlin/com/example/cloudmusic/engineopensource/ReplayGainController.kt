package com.example.cloudmusic.engineopensource

import com.example.cloudmusic.model.GainMode
import com.example.cloudmusic.model.ReplayGain

/**
 * `docs/12 §61.105` / B2 侧 ReplayGain 接线的**纯 JVM 计算层**。
 *
 * 上游事实（`12 §61.99`）：竞品 `libneaudioeffects.so` 已有
 * `LoudnessEvaluator.init/push/getReplayGain`，即增益计算引擎是现成的；
 * `core-model/PlaybackGain.kt`（C-18）已把 dB → 目标音量的换算沉淀为值对象，
 * 但当时**无生产消费方**。本类把该值对象接到 B2 播放链：
 *
 * `Media3EngineAdapter` 的 `Player.Listener.onMetadata` 把容器标签转成
 * `List<String>` → [ReplayGainController.onMetadataLines] → [ReplayGain] →
 * [effectiveVolume] → `ExoPlayer.volume`。门面 14 方法签名零改动，
 * `setVolume` 的用户音量作为基准，与增益相乘后钳制在 `[0,1]`。
 *
 * **诚实边界**（与 `§61.101` C-18 一脉相承）：
 * - 本类只消费**容器自带的 ReplayGain 标签**（VorbisComment / ID3 TXXX / COMM）；
 *   不做音频扫描、不接 `LoudnessEvaluator`、不写曲库持久化；
 * - 无标签 / [GainMode.OFF] / 增益恒 0 时退化为原始用户音量，行为与接线前一致；
 * - 真机音量效果 / 逐格式标签覆盖率 **NOT RUN**（设备离线，§61.105），
 *   JVM 单测只验证换算与接线契约，不写成「ReplayGain 真机已生效」。
 */
internal class ReplayGainController(
    /** 由 adapter 注入：把有效音量写到 `ExoPlayer.volume`（§61.87 单线程约束内）。 */
    private val applyVolume: (Float) -> Unit,
) {

    /** 有标签时默认按 TRACK 模式生效；可由上层显式改为 ALBUM / OFF。 */
    var mode: GainMode = GainMode.TRACK

    /** 用户预放大（dB），与标签增益相加后一起换算。 */
    var preampDb: Float = 0f

    private var userVolume = 1f
    private var gain: ReplayGain? = null

    /** 容器标签就绪（可能多次回调）；解析不到任何增益标签时保持无增益态。 */
    fun onMetadataLines(lines: List<String>) {
        gain = parse(lines)
        refresh()
    }

    /** 门面 `setVolume` 的用户音量，作为增益的基准幅度。 */
    fun onUserVolume(volume: Float) {
        require(volume in 0f..1f) { "volume must be 0f..1f (03-06 §4)" }
        userVolume = volume
        refresh()
    }

    /** 切歌 / prepare：清掉上一曲的标签，避免增益串曲。 */
    fun reset() {
        gain = null
        userVolume = 1f
        refresh()
    }

    /** 实际下发到 `ExoPlayer.volume` 的值，恒在 `[0f, 1f]`。 */
    fun effectiveVolume(): Float {
        val g = gain
        if (mode == GainMode.OFF || g == null || !g.hasGain()) return userVolume
        return (userVolume * g.targetVolume(mode, preampDb)).coerceIn(0f, 1f)
    }

    /** 当前解析到的标签（供日志，不表示真机已生效）。 */
    fun parsedGain(): ReplayGain? = gain

    private fun refresh() {
        applyVolume(effectiveVolume())
    }

    companion object {
        private const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
        private const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
        private const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
        private const val ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

        /**
         * 解析 `KEY=VALUE` 行（VorbisComment 原样 / ID3 TXXX 转成
         * `description=value` / COMM 转成 `description=text`）。
         * 大小写与 `dB` 后缀不敏感；无效数值整行忽略；四个键至少一个有效才返回非 null。
         */
        fun parse(lines: List<String>): ReplayGain? {
            var trackDb: Float? = null
            var albumDb: Float? = null
            var trackPeak: Float? = null
            var albumPeak: Float? = null
            var hits = 0
            for (line in lines) {
                val sep = line.indexOfFirst { it == '=' || it == ':' }
                if (sep <= 0) continue
                val key = line.substring(0, sep).trim()
                    .uppercase()
                    .replace(' ', '_')
                    .replace('-', '_')
                val raw = line.substring(sep + 1).trim()
                when (key) {
                    TRACK_GAIN -> number(raw)?.let { trackDb = it; hits++ }
                    ALBUM_GAIN -> number(raw)?.let { albumDb = it; hits++ }
                    TRACK_PEAK -> positiveNumber(raw)?.let { trackPeak = it; hits++ }
                    ALBUM_PEAK -> positiveNumber(raw)?.let { albumPeak = it; hits++ }
                    else -> Unit
                }
            }
            if (hits == 0) return null
            return ReplayGain(
                trackGainDb = trackDb ?: 0f,
                albumGainDb = albumDb ?: 0f,
                trackPeak = trackPeak ?: 1f,
                albumPeak = albumPeak ?: 1f,
            )
        }

        /** `"-6.54 dB"` / `"+3.0 dBFS"` / `"0.5"` → Float；非法值返回 null。 */
        private fun number(raw: String): Float? {
            val cleaned = raw
                .replace(Regex("(?i)\\s*db(fs)?\\s*$"), "")
                .trim()
            val v = cleaned.toFloatOrNull() ?: return null
            return if (v.isNaN() || v.isInfinite()) null else v
        }

        private fun positiveNumber(raw: String): Float? {
            val v = number(raw) ?: return null
            return if (v > 0f) v else null
        }
    }
}

/**
 * `docs/12 §61.105` / gapless 的**契约判定层**（纯 JVM，不伪造队列接线）。
 *
 * 两层语义必须分开：
 * - **文件内 gapless**：MP3 LAME/Xing、MP4 iTunSMPB 的 encoder delay / padding
 *   由 Media3 官方 Extractor + AudioSink 自动裁剪，B2 无需额外代码即具备；
 *   是否真机生效仍需逐格式验证 → NOT RUN。
 * - **跨曲 gapless**：要求同一 `ExoPlayer` 实例上的连续 `MediaItem` 队列且
 *   曲目结束不暂停。当前门面 single-handle 契约（`03-06 §4` / `§15 T06`）要求
 *   新 `prepare()` 前必须 `release()`，而 adapter 的 `release()` 会
 *   `player.release()`、下次 `prepare()` 重新 `ExoPlayer.Builder().build()`，
 *   **player 不复用、队列长度恒为 1** → 跨曲 gapless 架构阻断，判 BLOCKED。
 *   解锁前置是门面队列化演进（连续 prepare / 复用 player），本批不擅自改契约。
 */
internal data class GaplessAssessment(
    /** 官方 Extractor + AudioSink 的文件内 trimming 能力（静态事实，真机 NOT RUN）。 */
    val inFileTrimmingAvailable: Boolean,
    /** 跨曲无缝的运行时可达性。 */
    val crossTrackAvailable: Boolean,
    /** 不可达时的具体原因，可达时为 null。 */
    val blocker: String?,
)

internal object GaplessPolicy {
    const val IN_FILE_TRIMMING_BY_OFFICIAL_EXTRACTOR = true
    const val CROSS_TRACK_BLOCKER =
        "single-handle 契约：prepare 前 release → ExoPlayer 重建；需门面队列化 + player 复用"

    fun assess(
        playerReused: Boolean,
        queuedItemCount: Int,
        pauseAtEndOfMediaItems: Boolean,
    ): GaplessAssessment {
        val crossTrack = playerReused && queuedItemCount >= 2 && !pauseAtEndOfMediaItems
        return GaplessAssessment(
            inFileTrimmingAvailable = IN_FILE_TRIMMING_BY_OFFICIAL_EXTRACTOR,
            crossTrackAvailable = crossTrack,
            blocker = if (crossTrack) null else CROSS_TRACK_BLOCKER,
        )
    }
}
