package com.ccmsc.cloudmusic.di.playback

import android.util.Log
import com.ccmsc.cloudmusic.di.settings.SettingsStore
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.output.dlna.DlnaCastCoordinator
import com.ccmsc.cloudmusic.output.dlna.DmrDevice
import com.ccmsc.cloudmusic.output.router.ActiveOutput
import com.ccmsc.cloudmusic.output.router.OutputRouter
import com.ccmsc.cloudmusic.player.PlayerController
import com.ccmsc.cloudmusic.player.PlayerPhase
import com.ccmsc.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DLNA_POLL_INTERVAL_MS = 5_000L

/**
 * §61.324（B-4 拆域）：DLNA 会话域。
 *
 * 从 `PlaybackRuntime`（原 1301 行、12 类职责）切出的三域之一，边界是
 * **「投屏目标设备 + DMR 进度/音量 + 断连回落」**：
 * - 切歌保持 re-cast（[recastLoop]，§61.193）
 * - 断连回落本机（[sessionLoop]，§61.199）
 * - DMR 音量下发/读回（§61.229）
 * - 播放页进度显示的投屏分支（[displayPositionMs]，§61.202）
 *
 * 与 `PlaybackRuntime` 的耦合只剩三处**单向**依赖：`controller`（读状态 / 回落
 * seek+resume / `onOutputChanged`）、`output`（输出路由唯一裁决者）、
 * `castCoordinator`（投屏执行者）。不读 `RuntimeState`，故可独立构造与测试。
 */
class DlnaSessionFacade(
    private val controller: PlayerController,
    private val output: OutputRouter,
    private val castCoordinator: DlnaCastCoordinator,
    private val tracks: TrackRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    /**
     * §61.202：DMR 最近一次上报的进度（ms）。只在会话循环里写，回本机即清零；
     * 读失败时保留旧值（不把进度打回 0）。
     */
    private val _positionMs = MutableStateFlow(0L)
    fun positionMs(): StateFlow<Long> = _positionMs.asStateFlow()

    /**
     * §61.229（V0.2 M7）：投屏态 DMR 音量——RenderingControl:1 Master channel。
     * 值由 UI 写入（输出弹窗滑杆）与 GetVolume 读回；非投屏态写操作是 no-op。
     */
    private val _volume = MutableStateFlow(50)
    fun volume(): StateFlow<Int> = _volume.asStateFlow()

    /**
     * §61.193：投屏目标设备（UI 首次投屏成功时经 [onDeviceSelected] 写入）。
     * 回落 LOCAL 即清；进程生命周期内记忆，不落盘（落盘的是「上次设备」记忆）。
     */
    @Volatile
    private var castingDevice: DmrDevice? = null

    /** §61.199：断连判定器（纯 JVM），只被会话循环访问。 */
    private val session = com.ccmsc.cloudmusic.output.dlna.DlnaSessionMonitor()

    /** 启动两个常驻循环（由 PlaybackRuntime.init 调用一次）。 */
    fun start() {
        recastLoop()
        sessionLoop()
    }

    /** UI 首次投屏成功时写入目标设备（CloudMusicApp onOutputSelected DLNA 分支）。 */
    fun onDeviceSelected(device: DmrDevice) {
        castingDevice = device
        session.reset()   // 新会话：清上次断连标记与旧进度
        _positionMs.value = 0L
        // §61.241（V3-M2）：上次投屏设备持久化——进程死亡后仍记忆（回本机不清）。
        scope.launch {
            runCatching {
                settings.update { it.copy(dlna = it.dlna.withLastDmr(device.usn, device.friendlyName)) }
            }.onFailure { Log.w("CM-DLNA", "上次投屏设备持久化失败: ${it.message}") }
        }
    }

    /** §61.229：DMR 音量下发（失败只记日志、不伪造生效）。 */
    fun setVolume(volume: Int) = scope.launch {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            val ok = runCatching { castCoordinator.setVolume(device, volume) }.getOrDefault(false)
            if (ok) _volume.value = volume.coerceIn(0, 100)
            else Log.w("CM-DLNA", "DMR SetVolume 未确认 vol=$volume")
        } else {
            Log.w("CM-DLNA", "非投屏态忽略 DMR 音量写入 vol=$volume")
        }
    }

    /** §61.229：从 DMR 读回当前音量（打开弹窗时刷新显示；失败保持上次值）。 */
    fun refreshVolume() = scope.launch {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            castCoordinator.getVolume(device)?.let { _volume.value = it.coerceIn(0, 100) }
        }
    }

    /**
     * §61.202 播放页进度显示的单一入口：DLNA 活跃时用 DMR 真值（未读到时暂用
     * 本机引擎冻结的进度），否则用引擎实时进度。UI 不再自己判断输出类型。
     */
    fun displayPositionMs(): Flow<Long> = combine(
        // §61.384：本机侧改用**高频进度流**（500ms）——`controller.state.positionMs` 只在
        // seek/暂停时写，播放中恒为旧值，UI 进度条看起来卡死。
        controller.displayPositionMs(), positionMs(), output.state(),
    ) { local, dmr, o ->
        if (o.activeOutput == ActiveOutput.DLNA && dmr > 0L) dmr else local
    }

    /**
     * §61.26（G03 验收）+ §61.202：**投屏态**把外部 seek 转发给 DMR。
     *
     * @return true = 已由投屏会话接管（无论 DMR 是否确认，本机引擎都不动——它此时静音，
     *         seek 它不会改变用户听到的进度）；false = 非投屏态，调用方走本机引擎。
     *         行为与 §61.324 拆域前逐字一致（失败只记日志、不假装生效）。
     */
    suspend fun seekIfCasting(ms: Long): Boolean {
        val device = castingDevice
        if (output.activeOutput == ActiveOutput.DLNA && device != null) {
            val ok = runCatching { castCoordinator.seekTo(device, ms) }.getOrDefault(false)
            if (ok) _positionMs.value = ms
            else Log.w("CM-DLNA", "DMR seek 未确认 ms=$ms")
            return true
        }
        return false
    }

    /**
     * §61.193：DLNA 切歌保持（会话引擎第一刀）。
     *
     * 观察播放状态 × 输出路由：**DLNA 为活跃输出且换曲**时，自动把新曲目 re-cast
     * 到该设备；回落 LOCAL 即清设备。断连回落由 [sessionLoop] 承担。
     */
    private fun recastLoop() {
        scope.launch {
            // §61.383（真机 2026-11-27）：变量语义从「最后**看到**的曲目」改为「最后**投过**的曲目」。
            //
            // 原实现每轮 collect 都写 `lastTrackId = trackId`，但真正 re-cast 还要等
            // `phase == PLAYING`。切歌瞬间 phase 是 PREPARING/BUFFERING —— 这一帧 id 已被记住、
            // cast 却被跳过，之后 phase 变 PLAYING 时 `trackId == lastTrackId` 恒成立，
            // **这首就永远推不过去**。真机表现：手机播 B、天猫精灵还在放 A（用户一手「mini 播放条
            // 和天猫精灵播放的不是一个」）。
            //
            // 现在只有 **cast 真的发出去**才更新该值：phase 不满足时保持旧值，等下一次 emit 重试；
            // 失败也不更新，网络抖动后可自然重试同一首。
            var lastCastTrackId: String? = null
            combine(controller.state, output.state()) { s, o -> s to o }.collect { (s, o) ->
                // 回落本机：清投屏目标（下次投屏重新选设备）。
                if (o.activeOutput == ActiveOutput.LOCAL && castingDevice != null) {
                    castingDevice = null
                    session.reset()   // 用户主动回本机：会话结束，不留断连标记
                    _positionMs.value = 0L   // §61.202：显示进度交还本机引擎
                }
                val trackId = s.currentTrackId?.value
                val device = castingDevice
                if (o.activeOutput == ActiveOutput.DLNA &&
                    trackId != null && trackId != lastCastTrackId &&
                    s.phase == PlayerPhase.PLAYING && device != null
                ) {
                    val track = runCatching { tracks.get(TrackId(trackId)) }.getOrNull()
                    if (track != null) {
                        val ok = runCatching { castCoordinator.startCast(track, device) }
                            .onFailure { Log.w("CM-DLNA", "切歌保持 re-cast 失败: ${it.message}") }
                            .getOrNull()
                        if (ok != null) {
                            lastCastTrackId = trackId
                            Log.i("CM-DLNA", "切歌重推 → ${device.friendlyName}: ${track.title}")
                        }
                    }
                }
            }
        }
    }

    /**
     * §61.199（03-08 §10.2 T18）：DLNA 断连回落本机。
     *
     * 投屏活跃且记住了目标设备时，每 [DLNA_POLL_INTERVAL_MS] 读一次 DMR 进度；
     * 判定器连续 3 次失败 → Stop DMR（尽力）→ OutputRouter 切回 LOCAL →
     * 解除本机静音 / 恢复 MediaSession → seek 到最后已知进度并续播 → UI 提示。
     * 不投屏时不读网、不抢占；回落只发一次（判定器保证），避免反复 seek。
     */
    private fun sessionLoop() {
        scope.launch {
            while (isActive) {
                val device = castingDevice
                if (output.activeOutput == ActiveOutput.DLNA && device != null) {
                    val position = runCatching { castCoordinator.positionMs(device) }.getOrNull()
                    // §61.202：读成功即刷新显示进度；失败保留旧值（判定器负责计失败次数）。
                    if (position != null && position >= 0L) _positionMs.value = position
                    val verdict = session.observe(position)
                    if (verdict.shouldFallBack) {
                        runCatching { castCoordinator.stopCast(device) }
                            .onFailure { Log.w("CM-DLNA", "回落 Stop 失败: ${it.message}") }
                        // OutputRouter 是输出状态唯一裁决者（03-08 §14 #9）；先切状态再恢复本机控件。
                        output.setActive(ActiveOutput.LOCAL)
                        output.updateRouteHint("已回到本机播放")
                        controller.onOutputChanged(false)
                        val resumed = runCatching {
                            // 从未读到有效进度时不 seek（避免把用户带回开头）。
                            if (verdict.lastPositionMs > 0L) controller.seek(verdict.lastPositionMs)
                            controller.resume()
                        }
                        if (resumed.isFailure) {
                            Log.w("CM-DLNA", "回落 seek/resume 失败: ${resumed.exceptionOrNull()?.message}")
                        }
                        castingDevice = null
                        session.reset()
                        _positionMs.value = 0L
                        Log.i("CM-DLNA", "断连回落本机 lastPositionMs=${verdict.lastPositionMs} resumed=${resumed.isSuccess}")
                    }
                }
                delay(DLNA_POLL_INTERVAL_MS)
            }
        }
    }
}
