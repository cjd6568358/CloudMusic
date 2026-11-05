package com.example.cloudmusic.datasource

/**
 * 生命周期状态机（docs/03-01 §4.2）：
 *
 * ```
 * NEW ──init 成功──▶ READY ──drop──▶ CLOSED
 *  │                    │                ▲
 *  └──init 失败──▶ FAILED ──drop────────┘
 *                       │
 *                       └── 可重试 init（先 drop）
 * ```
 *
 * drop() 幂等；FAILED 状态下调用不得抛异常（B02 / T14）。
 */
class DriverLifecycle(private val driver: Driver) {

    enum class State { NEW, READY, FAILED, CLOSED }

    @Volatile
    var state: State = State.NEW
        private set

    /** init 前若非 NEW 一律先 drop（B01）。失败时把上游消息留在 ctx.errDetail 由上层落库。 */
    suspend fun init(ctx: DriverCtx) {
        if (state != State.NEW) drop()
        try {
            driver.init(ctx)
            state = State.READY
        } catch (t: Throwable) {
            state = State.FAILED
            throw t
        }
    }

    /** 幂等；吞掉驱动 drop 的异常以保证 B02 / T14「不抛异常」。 */
    fun drop() {
        if (state == State.CLOSED) return
        runCatching { driver.drop() }
        state = State.CLOSED
    }
}
