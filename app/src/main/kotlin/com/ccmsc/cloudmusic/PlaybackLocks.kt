package com.ccmsc.cloudmusic

import com.ccmsc.cloudmusic.engine.api.EngineState

/**
 * §61.306（K3b）：播放保活锁——lx 组合拳（PARTIAL_WAKE_LOCK + WifiLock 服务层统一
 * 持有，方案 §61.299 §3.2-8 对齐）。
 *
 * 持有口径 = **网络音源且（PLAYING 或 BUFFERING）**：本地曲无需网络锁（音频渲染
 * 自带 CPU 保活）；DLNA 投屏本机 PAUSED 自然释放；暂停/结束/出错即释（lx「暂停
 * 即释」口径）。FGS 只保进程存活、不保 CPU/Wi-Fi 唤醒（ExoPlayer wakeMode=NONE
 * 延续），灭屏流媒体断流（lx FAQ 同类问题）由此组合拳治理。
 */
object PlaybackLockPolicy {
    fun shouldHold(state: EngineState, isNetworkSource: Boolean): Boolean =
        isNetworkSource && (state == EngineState.PLAYING || state == EngineState.BUFFERING)
}

/** 系统锁端口（Android 实现注入；测试 fake 记录调用）。 */
interface LockPort {
    fun acquire()
    fun release()
}

/**
 * 服务层锁持有者（单一出口、幂等去重）：判定结果变化才触碰锁——引擎态快照链
 * 每 2s 一次，重复 acquire/release 既是系统调用税也可能打断长持有。
 * 无引用计数（setReferenceCounted(false) 语义），释放幂等。
 */
class PlaybackLockHolder(
    private val wakeLockPort: LockPort,
    private val wifiLockPort: LockPort,
) {
    private var holding = false

    fun update(state: EngineState, isNetworkSource: Boolean) {
        val should = PlaybackLockPolicy.shouldHold(state, isNetworkSource)
        if (should == holding) return
        holding = should
        if (should) {
            wakeLockPort.acquire()
            wifiLockPort.acquire()
        } else {
            wakeLockPort.release()
            wifiLockPort.release()
        }
    }

    /** 服务销毁兜底释放（幂等；未持有为 no-op）。 */
    fun releaseAll() {
        if (!holding) return
        holding = false
        wakeLockPort.release()
        wifiLockPort.release()
    }
}
