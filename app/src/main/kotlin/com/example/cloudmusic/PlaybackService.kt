package com.example.cloudmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.media.FocusChange
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.output.router.BluetoothEvent
import com.example.cloudmusic.output.router.BluetoothOutputPolicy
import com.example.cloudmusic.output.router.BluetoothSettings
import com.example.cloudmusic.output.router.OutputRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * T-A5（`12 §31` / `03-06 §10` / `03-11 §1.1`）· 单进程前台播放服务。
 *
 * 落点按第二十一批核对结论：`:feature-player` 是纯 JVM 模块、没有 Manifest，
 * 因此 Service 类与 `<service>` 声明都在 `:app`，生产组合对象由 `:core-di` 的
 * [PlaybackRuntime] 提供，**不新建模块**。
 *
 * 职责边界：只做 Android 生命周期（FGS 通知、媒体会话、音频焦点、按键入口），
 * 扫描 / effects / 代理启停 / 播放编排全部委托 [PlaybackRuntime]。
 *
 * **第三十二批（`12 §41`）**：补齐音频焦点（`AudioFocusRequest` / `OnAudioFocusChangeListener`）
 * 与 `ACTION_AUDIO_BECOMING_NOISY` 的接线，正好是 `03-09 §8`「未授 BLUETOOTH_CONNECT 时
 * 只监听 BECOMING_NOISY」的降级路径（**不新增权限、不新增依赖**，全用平台 API）。
 *
 * **第三十四批（`12 §43`，T-L L2 自研侧接线收口）**：焦点 / 断连事件统一派发到
 * [IMediaControl.onFocusChange]，由 `FocusPolicy` 裁决后回调 `PlayerController`
 * 内部的 `HandleAdapter`（`03-07 §3` / `03-09 §5` 正式链）；该 bind 由
 * `PlayerController.init` 完成，`MediaModule` 只提供 `@Singleton` 实例，
 * 不再自建句柄、不再注入 [PlaybackRuntime]（避免 DI 依赖环）。
 * `onBind()` 返回 null 是 D-93 的单进程共享设计（无需 Binder/AIDL）。
 *
 * **第三十七批（`12 §46`，T-L L2 自研侧断连裁决）**：`ACTION_AUDIO_BECOMING_NOISY`
 * 不再无条件暂停，改由 [BluetoothOutputPolicy] 按 `03-09 §5` 裁决
 * （`autoPauseOnDisconnect` 开关 / `OutputRouter.activeOutput == DLNA` 豁免 / 连续断开去重）。
 * **`BLUETOOTH_CONNECT` 已在 Manifest 声明（既有 11 项之一），但 V0.1 未做运行时动态申请、
 * 也未接蓝牙 ACL 广播**：按 `03-09 §8`「未授予时降级」以 `permissionGranted=false`
 * 走 B01 路径，只处理 BECOMING_NOISY；C3 仍 11 项权限、零新增权限。
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject
    lateinit var runtime: PlaybackRuntime

    /** 焦点 / 断连的唯一裁决入口；句柄由 `PlayerController.init` 绑定（`03-07 §3`）。 */
    @Inject
    lateinit var mediaControl: IMediaControl

    /** `12 §46`：读取 `autoPauseOnDisconnect` 的当前值（DataStore Flow 的最新快照）。 */
    @Inject
    lateinit var settings: SettingsAccess

    /** `12 §46`：`03-09 §7` 唯一裁决者，用于投屏（DLNA）豁免判定。 */
    @Inject
    lateinit var outputRouter: OutputRouter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    /** `12 §46`：断连策略与 Service 同生命周期，保留 B04 去重状态。 */
    private lateinit var disconnectPolicy: BluetoothOutputPolicy

    /**
     * 平台焦点常量逐项映射到 `FocusChange`，由 `IMediaControl` → `FocusPolicy`
     * 裁决（LOSS 不自动恢复、LOSS_TRANSIENT 自动恢复、CAN_DUCK 只降音量）——
     * 不再由 Service 一刀切暂停（`12 §41` 的 V0.1 简化语义已由 `§43` 替换）。
     *
     * T3（`12 §45`）：`03-07 §3` 的三个 GAIN 枚举变体**逐个对映**，不再压平成
     * `FocusChange.GAIN`；`FocusPolicy` 当前对三者同为 `onGain`，但保留事件语义，
     * 便于后续按 `GAIN_TRANSIENT_MAY_DUCK` 做差异化裁决。
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val event = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> FocusChange.GAIN_TRANSIENT
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> FocusChange.GAIN_TRANSIENT_MAY_DUCK
            else -> null
        }
        if (event != null) {
            Log.d(SESSION_TAG, "audio focus: $change -> $event")
            mediaControl.onFocusChange(event)
        }
    }

    /**
     * 有线耳机拔出 / 蓝牙断开的等价事件；无需任何权限（03-09 §8）。
     *
     * `12 §46`：不再无条件暂停，交给 [BluetoothOutputPolicy] 按 `03-09 §5` 裁决：
     * `autoPauseOnDisconnect` 开关、`activeOutput == DLNA` 豁免、连续断开去重。
     * 设置每次从 [settings] 读最新快照，用户在设置页改动可立即生效。
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!::disconnectPolicy.isInitialized) return // onCreate 之前不处理
            val playback = settings.current().playback
            disconnectPolicy.updateSettings(
                BluetoothSettings(
                    autoPauseOnDisconnect = playback.autoPauseOnDisconnect,
                    showConnectedToast = playback.showConnectedToast,
                    // V0.1 未做运行时动态申请 → 按「未授予」处理（03-09 §8 B01 降级）
                    permissionGranted = false,
                ),
            )
            val result = disconnectPolicy.onEvent(BluetoothEvent.BECOMING_NOISY)
            if (result.pauseDispatched) {
                Log.d(SESSION_TAG, "BECOMING_NOISY -> FocusChange.LOSS (autoPause on / LOCAL)")
                abandonAudioFocus()
            } else {
                Log.d(SESSION_TAG, "BECOMING_NOISY ignored (autoPause off / DLNA / in-flight)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 12 §46：必须在注册 receiver 之前建好策略，避免 onCreate 前收到广播时无裁决者。
        disconnectPolicy = BluetoothOutputPolicy(
            settings = BluetoothSettings(permissionGranted = false),
            mediaControl = mediaControl,
            router = outputRouter,
        )
        createNotificationChannel()
        registerNoisyReceiver()
        mediaSession = MediaSession(this, SESSION_TAG).apply {
            // 03-07 §5 B12：targetSdk 35 下以 MediaSession.Callback 为主入口，
            // MediaButtonEventReceiver 只保留兼容声明，不重复消费按键。
            // FLAG_HANDLES_MEDIA_BUTTONS / TRANSPORT_CONTROLS 在 API 26+ 已弃用，
            // 设置 Callback 后由系统默认分发，不再显式声明。
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = performPlay()
                override fun onPause() = performPause()
                override fun onSkipToNext() { runtime.next() }
                override fun onSkipToPrevious() { runtime.prev() }
            })
            // 12 §50（真机 A3 暴露）：此前只 setCallback + isActive，从未 setPlaybackState，
            // dumpsys media_session 显示 state=null → 系统媒体键 / 蓝牙按键无会话可路由，
            // 锁屏与通知控件也拿不到播放态。这里给一个 PAUSED 初始态并声明支持的动作。
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .build(),
            )
            // 12 §50（真机 A3 暴露）：只 setPlaybackState 仍不够，dumpsys 显示
            // mediaButtonReceiver=null / "Media button session is null"，系统媒体键
            // 与 cmd media_session dispatch 都会丢弃按键。声明接收器后本会话才会被
            // 登记为 media button session；Session 已 active 时按键走 Callback，
            // 该 PendingIntent 只在应用未唤醒时兜底，不会与 onPlay 重复消费。
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@PlaybackService,
                    0,
                    Intent(this@PlaybackService, MediaButtonEventReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            isActive = true
        }
        startForeground(NOTIFICATION_ID, buildNotification("曲库就绪"))
        runtime.start()   // 冷启动轻量增量扫描 + 订阅 effects
        scope.launch {
            runtime.state.collect { state -> updateNotification(state) }
        }
    }

    /**
     * 12 §50（真机 A3 暴露）：`MediaButtonEventReceiver` 原为空实现，系统媒体键 /
     * 线控按键到达后无人消费。这里提供标准的 action 入口，由接收器（或日后的通知
     * PendingIntent）转发；Session.Callback 与 action 共用同一份播放 / 暂停裁决。
     *
     * 03-06 §12.6：单进程 native crash 会带走整个进程，START_STICKY 是约定的恢复入口。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> performPlay()
            ACTION_PAUSE -> performPause()
        }
        return START_STICKY
    }

    private fun performPlay() {
        // 焦点未授予时不启动播放，避免与其他应用抢音频输出。
        if (requestAudioFocus()) {
            runtime.play()
            // C-15（12 §44）：把「用户主动播放」回传给 FocusPolicy。
            mediaControl.onPlaying()
            // 12 §46 / B04：用户重新播放后允许下一次断开再次暂停（回连本身不复位）。
            if (::disconnectPolicy.isInitialized) disconnectPolicy.notifyPlaybackResumed()
            // 12 §61.19（第五十一批续）：同步 MediaSession PlaybackState → PLAYING，
            // 让系统正确设置 media button session 并在锁屏 / 通知中显示播放态。
            updateSessionPlaybackState(PlaybackState.STATE_PLAYING)
        }
    }

    private fun performPause() {
        // C-15（12 §44）：先清「因焦点而暂停」标记，否则来电 / 导航结束后
        // 收到 GAIN 会被误判为自动恢复（03-07 §12 T03）。
        mediaControl.onManualPause()
        runtime.pause()
        abandonAudioFocus()
        updateSessionPlaybackState(PlaybackState.STATE_PAUSED)
    }

    /** 12 §61.19：同步 MediaSession PlaybackState（保持 actions 不变）。 */
    private fun updateSessionPlaybackState(state: Int) {
        mediaSession?.let { session ->
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(state, 0L, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .build(),
            )
        }
    }

    /** D-93（12 §33）：Activity 与 Service 注入同一 @Singleton PlaybackRuntime，无需 Binder。 */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        abandonAudioFocus()
        runtime.stop()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---- 音频焦点 / 外部断连 -------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        // minSdk 26 → AudioFocusRequest / requestAudioFocus / abandonAudioFocusRequest 恒可用。
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    // ---- 通知 ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放与曲库",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台播放与曲库扫描状态"
            setSound(null, null)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // minSdk 26 → NotificationChannel 与带 channelId 的 Builder 恒可用，不需版本分支。
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("CloudMusic")
            .setContentText(text)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .apply {
                mediaSession?.sessionToken?.let {
                    setStyle(Notification.MediaStyle().setMediaSession(it))
                }
            }
            .build()
    }

    private fun updateNotification(state: RuntimeState) {
        val text = when {
            state.scanning -> "正在扫描曲库 ${state.scanned}/${state.total}"
            state.errors > 0 -> "扫描完成，${state.errors} 项失败"
            state.lastEffect.isNotBlank() -> "设置已生效：${state.lastEffect}"
            else -> "曲库就绪"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val CHANNEL_ID = "cloudmusic.playback"
        const val SESSION_TAG = "CloudMusic"
        const val NOTIFICATION_ID = 1001
        /** 12 §50：媒体按键兜底 / 通知动作的标准入口，由本应用内部组件转发。 */
        const val ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
        const val ACTION_PAUSE = "com.example.cloudmusic.action.PAUSE"
    }
}
