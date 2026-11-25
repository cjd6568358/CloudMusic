package com.ccmsc.cloudmusic.output.dlna

/**
 * docs/03-08 §10.2 / §11（T18 断连回落本机）：DLNA 会话健康判定。
 *
 * 纯 JVM、不触网、不持有时钟：调用方负责按周期读 `GetPositionInfo` 并把结果喂进来。
 * 口径钉死在 03-08 §10.2「轮询 3 次超时 / SOAP 失败 → 回落本机」：
 * - 读到有效进度（≥0）→ HEALTHY，刷新最后进度并清零失败计数；
 * - 连续失败未到阈值 → DEGRADED（不动作，避免一次抖动就抢占播放）；
 * - 第 3 次失败 → DISCONNECTED，[Verdict.shouldFallBack] **仅首次为 true**，
 *   回落执行方据此做一次 Stop → LOCAL → seek 续播；重复轮询不重复抢占。
 * - 新会话（重新投屏）由调用方 [reset]，避免上次的断连标记污染新设备。
 *
 * 最后进度是回落续播的唯一依据（§10.2「seek 到 DMR 最后进度」）——只记录成功读到的值，
 * 失败时原样保留，绝不把 0 当成「用户在开头」而从头播放。
 */
class DlnaSessionMonitor(private val failureLimit: Int = DEFAULT_FAILURE_LIMIT) {
    enum class State { HEALTHY, DEGRADED, DISCONNECTED }

    data class Verdict(
        val state: State,
        /** 最近一次成功读取的 DMR 进度（ms）；从未成功为 0。 */
        val lastPositionMs: Long,
        /** 仅首次判定断连为 true；回落执行方消费后不应再次触发。 */
        val shouldFallBack: Boolean,
    )

    private var failures = 0
    private var lastPositionMs = 0L
    private var fallbackIssued = false

    fun observe(positionMs: Long?): Verdict {
        if (positionMs != null && positionMs >= 0L) {
            failures = 0
            lastPositionMs = positionMs
            return Verdict(State.HEALTHY, lastPositionMs, false)
        }
        failures++
        if (failures >= failureLimit.coerceAtLeast(1)) {
            val first = !fallbackIssued
            fallbackIssued = true
            return Verdict(State.DISCONNECTED, lastPositionMs, first)
        }
        return Verdict(State.DEGRADED, lastPositionMs, false)
    }

    /** 新投屏会话复位：清失败计数 / 断连标记 / 旧进度。 */
    fun reset() {
        failures = 0
        lastPositionMs = 0L
        fallbackIssued = false
    }

    companion object {
        /** 03-08 §10.2 明确「轮询 3 次超时」。 */
        const val DEFAULT_FAILURE_LIMIT = 3
    }
}
