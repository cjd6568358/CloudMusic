package com.example.cloudmusic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.example.cloudmusic.common.NetworkPlaybackGate
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.engine.api.EngineState
import com.example.cloudmusic.engine.api.IMusicEngine
import com.example.cloudmusic.media.FocusChange
import com.example.cloudmusic.media.IMediaControl
import com.example.cloudmusic.media.ObservableMediaControl
import com.example.cloudmusic.media.SessionBridge
import com.example.cloudmusic.output.router.BluetoothEvent
import com.example.cloudmusic.output.router.BluetoothInfo
import com.example.cloudmusic.output.router.BluetoothOutputPolicy
import com.example.cloudmusic.output.router.BluetoothSettings
import com.example.cloudmusic.output.router.OutputRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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
 *
 * **第五十六批（`12 §61.29`，T-L L2 收口）**：`BLUETOOTH_CONNECT` 本就在既有 11 项权限内，
 * 现按 `03-11 §2` 已定义的 `SHOW_BLUETOOTH_NAME` 动作（用户打开输出设备弹窗时）申请，
 * 由 [PermissionDecision] / `DegradationPolicy` 裁决拒绝降级；`permissionGranted` 改为
 * 运行时真值，已授权才动态注册 ACL 广播并翻译成 [BluetoothEvent] 交策略，未授权仍按
 * `03-09 §8` B01 只保留无需权限的 BECOMING_NOISY。设备名/地址不写日志（§10 B12）。
 * **零新增权限 / 零启动阶段申请**。
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject
    lateinit var runtime: PlaybackRuntime

    /** 焦点 / 断连的唯一裁决入口；句柄由 `PlayerController.init` 绑定（`03-07 §3`）。 */
    @Inject
    lateinit var mediaControl: IMediaControl

    /**
     * §61.240（V3-M1 第一刀）：发布链观察口——与 [mediaControl] 同一实例的第二个注入面。
     * `metadata()/playback()` StateFlow 即系统会话元数据与进度的唯一真值
     * （§61.239 起分轨相对口径免费继承）。
     */
    @Inject
    lateinit var observableControl: ObservableMediaControl

    /**
     * §61.240：系统会话桥（纯 JVM 判定在 `:core-media`，框架常量映射在本服务两写口）。
     * 判定规则见 [SessionBridge] KDoc；封面位图解码不在本刀范围（登记 V0.3 后续）。
     */
    private val sessionBridge = SessionBridge(
        playbackWriter = { playing, pos, dur -> writeSessionTransport(playing, pos, dur) },
        metadataWriter = { title, artist, album, dur, cover -> writeSessionMetadata(title, artist, album, dur, cover) },
    )

    /** §61.240：最近发布位置——引擎态回退链（§61.25）写 PAUSED 时保位不再清零。 */
    @Volatile
    private var lastPublishedPosition = 0L

    /** `12 §46`：读取 `autoPauseOnDisconnect` 的当前值（DataStore Flow 的最新快照）。 */
    @Inject
    lateinit var settings: SettingsAccess

    /** `12 §61.28` / `03-02 §344`：与编排层同一个 @Singleton 闸门实例。 */
    @Inject
    lateinit var networkGate: NetworkPlaybackGate

    /** `12 §46`：`03-09 §7` 唯一裁决者，用于投屏（DLNA）豁免判定。 */
    @Inject
    lateinit var outputRouter: OutputRouter

    /**
     * §61.25（E24 真机暴露）：与 [runtime] 内的 PlayerController 共享同一 @Singleton
     * [IMusicEngine]（AppBindings EngineModule）。这里只读 `state()` 流做
     * MediaSession PlaybackState 回退同步：播放完成（msg=2 → 引擎回 IDLE）或
     * 引擎错误（msg=100 → ERROR）后，若此前观测过 PLAYING，则把会话态同步为
     * PAUSED——E24 实证完成后 state=3 (PLAYING) 卡死在锁屏 / 通知控件上。
     */
    @Inject
    lateinit var engine: IMusicEngine

    /** §61.25：当前播放段内是否观测过引擎 PLAYING（完成 / 错误回退的判据）。 */
    private var playingObserved = false

    /**
     * 12 §61.47 / F2：`ACTION_PLAY` 的可选本地注入路径（验收期调试入口）。
     * 无此 extra 时恒为 null，`performPlay()` 保持原行为零回归。
     */
    private var pendingLocalPath: String? = null

    /**
     * 12 §61.49 / F3：`ACTION_PLAY` 的可选 REMOTE（回环 URL）注入入口。
     * 与 [pendingLocalPath] 互斥使用；两者皆空时 `runtime.play()` 原行为零回归。
     * 仅用于逐格式 × REMOTE/LOCAL 双路径真机矩阵，随 `12 §61.33` 收口。
     */
    private var pendingSourceUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** §61.115：位置观测通道节流戳（长稳/矩阵工具的可靠取数口）。 */
    private var lastPositionLogAt = 0L
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager

    /** §61.306（K3b）：保活锁持有者（PARTIAL_WAKE_LOCK + WifiLock，onCreate 装配）。 */
    private lateinit var lockHolder: PlaybackLockHolder
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    /** §61.29：ACL 广播仅在 BLUETOOTH_CONNECT 已授予时注册（B01 降级）。 */
    private var aclRegistered = false
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
            // §61.29：读运行时真值；未授权时策略仍放行 BECOMING_NOISY（B01）。
            disconnectPolicy.updateSettings(bluetoothSettings(bluetoothConnectGranted()))
            val result = disconnectPolicy.onEvent(BluetoothEvent.BECOMING_NOISY)
            if (result.pauseDispatched) {
                Log.d(SESSION_TAG, "BECOMING_NOISY -> FocusChange.LOSS (autoPause on / LOCAL)")
                abandonAudioFocus()
            } else {
                Log.d(SESSION_TAG, "BECOMING_NOISY ignored (autoPause off / DLNA / in-flight)")
            }
        }
    }

    /**
     * §61.29 / 03-09 §4：A2DP 连接状态 / 播放状态 / 适配器开关广播。
     * 只在 `BLUETOOTH_CONNECT` 已授予时注册；事件翻译交给纯 JVM 的
     * [BluetoothBroadcastAction]，裁决仍统一走 [disconnectPolicy]（§5 正式链）。
     */
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::disconnectPolicy.isInitialized || !bluetoothConnectGranted()) return
            val state = intent?.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) ?: -1
            val event = BluetoothBroadcastAction.resolve(intent?.action, state) ?: return
            // BECOMING_NOISY 由 noisyReceiver 单独处理（未授权路径），避免重复裁决。
            if (event == BluetoothEvent.BECOMING_NOISY) return
            disconnectPolicy.updateSettings(bluetoothSettings(true))
            val info = if (event == BluetoothEvent.CONNECTED) bluetoothInfoFrom(intent) else null
            val result = disconnectPolicy.onEvent(event, info)
            if (result.pauseDispatched) {
                Log.d(SESSION_TAG, "bluetooth ${event.name} -> FocusChange.LOSS (LOCAL / autoPause on)")
                abandonAudioFocus()
            }
            // 03-09 §10 B12：设备名 / 地址一律不写日志、不上传、不进曲库 DB。
        }
    }

    override fun onCreate() {
        super.onCreate()
        // §61.115：位置观测通道——G08 长稳等工具的可靠取数口（MIUI dumpsys
        // media_session 无标准 PlaybackState 明细、会话栈恒 0，不可用作监控）。
        // §61.120 教训：必须放 super.onCreate()（Hilt inject）之后——Main.immediate
        // 会让协程同步执行，注入前访问 lateinit runtime 即崩溃（真机一手）。
        scope.launch {
            if (!::runtime.isInitialized) return@launch
            runtime.playerState().collect { s ->
                if (s.phase == PlayerPhase.PLAYING) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPositionLogAt >= 10_000L) {
                        lastPositionLogAt = now
                        Log.i("CM-Player", "positionMs=%d".format(s.positionMs))
                    }
                }
            }
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // §61.306（K3b）：PARTIAL_WAKE_LOCK + WifiLock 服务层统一持有（lx 组合拳对齐，
        // 方案 §61.299 §3.2-8）——网络曲 PLAYING/BUFFERING 持有、暂停/结束/销毁即释。
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "CloudMusic:playback",
        ).apply { setReferenceCounted(false) }
        val wifiLock = wifiManager.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CloudMusic:wifi",
        ).apply { setReferenceCounted(false) }
        lockHolder = PlaybackLockHolder(
            wakeLockPort = object : LockPort {
                override fun acquire() {
                    runCatching { wakeLock.acquire() }
                }

                override fun release() {
                    runCatching { if (wakeLock.isHeld) wakeLock.release() }
                }
            },
            wifiLockPort = object : LockPort {
                override fun acquire() {
                    runCatching { wifiLock.acquire() }
                }

                override fun release() {
                    runCatching { if (wifiLock.isHeld) wifiLock.release() }
                }
            },
        )
        // 12 §46：必须在注册 receiver 之前建好策略，避免 onCreate 前收到广播时无裁决者。
        disconnectPolicy = BluetoothOutputPolicy(
            settings = bluetoothSettings(bluetoothConnectGranted()),
            mediaControl = mediaControl,
            router = outputRouter,
        )
        createNotificationChannel()
        registerNoisyReceiver()
        refreshBluetoothPermission()
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

                override fun onStop() {
                    // §61.306（K3a）：RemoteStop（通知划掉）= **停播 + 停服，不退 App**
                    // （§10-E 拍板默认；lx 的「RemoteStop=退出整个 App」不采——退出权
                    // 留给用户）。此前 onStop 未覆写：划掉通知后播放继续无声进行
                    // （lx FAQ「后台清理仍在播放」同族问题）。performPause 暂停引擎 +
                    // 落游标（pause 三口之一）+ 放弃焦点 + 会话回 PAUSED；stopSelf 走
                    // onDestroy 释放会话/前台通知/保活锁，下次冷启动按 restore 链原地恢复。
                    performPause()
                    stopSelf()
                }
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
        // §61.25（E24 真机暴露）：引擎态 → MediaSession 回退同步。播放完成
        // （msg=2 → 引擎回 IDLE）或引擎错误（msg=100 → ERROR）后，本播放段若
        // 观测过 PLAYING，则把会话态回退 PAUSED——否则 state=3 卡死在锁屏 /
        // 通知控件（E24 dumpsys 实证）。切歌（release→prepare→play）途中的
        // 瞬时 IDLE 会被随后的 PLAYING 覆盖，无观察级影响。
        scope.launch {
            engine.state().collect { s ->
                // §61.306（K3b）：保活锁随引擎态/音源网络态进出（网络 × PLAYING|BUFFERING
                // 才持有；holder 内部幂等去重，2s 快照链零重复触碰）。
                lockHolder.update(s, runtime.currentNetworkSource())
                when (s) {
                    EngineState.PLAYING -> {
                        playingObserved = true
                        updateSessionPlaybackState(PlaybackState.STATE_PLAYING)
                    }
                    // §61.301（H0-1）：缓冲直写系统会话 STATE_BUFFERING——speed 随
                    // updateSessionPlaybackState 的非播放口径落 0f，锁屏/通知/车机
                    // 的墙钟外推就此停走（「缓冲中计时照走」根因）；BUFFERING 结束
                    // 由 PLAYING/PAUSED 分支回正。
                    EngineState.BUFFERING -> updateSessionPlaybackState(PlaybackState.STATE_BUFFERING)
                    EngineState.IDLE, EngineState.ENDED, EngineState.ERROR -> {
                        if (playingObserved) {
                            playingObserved = false
                            updateSessionPlaybackState(PlaybackState.STATE_PAUSED)
                        }
                    }
                    else -> Unit
                }
            }
        }
        // §61.240：系统会话补全第一刀——发布链 metadata/playback 直通系统会话。
        // 快照链（§61.239 相对口径）成为会话真值：锁屏 / 通知 / AVRCP 拿到标题与
        // 进度，分轨曲目显示相对时间。引擎态回退链（上方 §61.25）保留兜底语义，
        // 但位置改为最近发布值（防回退写清零进度）。
        scope.launch {
            observableControl.playback().collect { snap ->
                snap?.positionMs?.takeIf { it >= 0 }?.let { lastPublishedPosition = it }
                sessionBridge.onPlayback(snap)
            }
        }
        scope.launch {
            observableControl.metadata().collect { md -> sessionBridge.onMetadata(md) }
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
        // §61.26（G03 真机暴露）：`startForegroundService()` 的每次启动调用都要求
        // 服务随后确认前台态，否则 `ForegroundServiceDidNotStartInTimeException`
        // 带崩进程（MIUI/A13 实测：服务已在运行时再发 ACTION_SEEK 未确认 → crash）。
        // onCreate 已 post 过一次；这里每次启动重 post（幂等）。
        startForeground(NOTIFICATION_ID, buildNotification("播放服务运行中"))
        // 12 §61.47 / §61.49：可选调试注入；两个 extra 皆空时原播放链零回归。
        pendingLocalPath = intent?.getStringExtra(EXTRA_LOCAL_PATH)
        pendingSourceUrl = intent?.getStringExtra(EXTRA_SOURCE_URL)
        when (intent?.action) {
            ACTION_PLAY -> performPlay()
            ACTION_PAUSE -> performPause()
            ACTION_SEEK -> {
                val ms = intent.getLongExtra(EXTRA_SEEK_POSITION_MS, -1L)
                if (ms >= 0) runtime.seekTo(ms)
            }
            // §61.93（音效必须支持）：外部音效档位入口，0 = 释放基础音效链。
            ACTION_EFFECT -> runtime.attachEffect(
                intent.getIntExtra(EXTRA_EFFECT_LEVEL, 0))
            // §61.122（T5-2）：ReplayGain 近似分析入口（LOCAL 未分析曲目，cap 50）——
            // 真机可验证性通道（设置页按钮同源；logcat CM-RG 可观测进度）。
            ACTION_ANALYZE_RG -> runtime.analyzeLibraryReplayGain(
                intent.getIntExtra(EXTRA_ANALYZE_MAX, 50),
                intent.getBooleanExtra(EXTRA_ANALYZE_FORCE, false),
            )
            // §61.129（T-V #9）：重扫触发入口（refresh=true 冷扫口径；性能基线实测用）。
            ACTION_RESCAN -> runtime.rescanAll()
            // §61.29：Activity 侧 SHOW_BLUETOOTH_NAME 申请结果出来后，通知 Service
            // 重读权限并按需注册 / 注销 ACL 广播（同 App 显式 Intent，不需要 exported）。
            ACTION_BLUETOOTH_PERMISSION_CHANGED -> refreshBluetoothPermission()
        }
        return START_STICKY
    }

    private fun performPlay() {
        // 03-02 §344：播放前同步检查「默认仅 WiFi 播放」。编排层的闸门在协程里，
        // 若只在那里拒绝，本方法随后仍会把 MediaSession 置成 PLAYING（§61.27 假报
        // 同类问题），故此处用同一 @Singleton 实例先判一次：拒绝就停在 PAUSED +
        // 明确原因提示，不申请焦点、不下发 play。
        val reason = networkGate.rejectReason(runtime.currentNetworkSource())
        if (reason != null) {
            updateSessionPlaybackState(PlaybackState.STATE_PAUSED)
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }
        // 焦点未授予时不启动播放，避免与其他应用抢音频输出。
        if (requestAudioFocus()) {
            // 12 §61.47 / §61.49：URL 优先于本地路径，两者皆空则保持原播放链。
            val injectedPath = pendingLocalPath
            val injectedUrl = pendingSourceUrl
            pendingLocalPath = null
            pendingSourceUrl = null
            when {
                !injectedUrl.isNullOrBlank() -> runtime.playUrl(injectedUrl)
                !injectedPath.isNullOrBlank() -> runtime.playLocal(injectedPath)
                else -> runtime.play()
            }
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
            // §61.240：回退链位置不再恒 0（§61.25 原实现）——用最近发布位置保位；
            // 速度按态修正（播放 1f / 非播放 0f，非播放期位置不漂移）。
            val playing = state == PlaybackState.STATE_PLAYING
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(state, lastPublishedPosition, if (playing) 1f else 0f)
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

    /**
     * §61.240：快照链写口——发布链 playback 快照 → 系统 PlaybackState。
     * 位置 = §61.239 相对口径（分轨曲目从 0 起算）；速度 1f/0f 按态修正。
     */
    private fun writeSessionTransport(playing: Boolean, positionMs: Long, durationMs: Long) {
        val session = mediaSession ?: return
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f,
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .build(),
        )
    }

    /**
     * §61.240：快照链写口——发布链 metadata → 系统会话元数据（标题/艺人/专辑/时长）。
     * 封面位图解码不在本刀（AVRCP 封面显示登记 V0.3 后续）；时长 0 = 未知原样写
     * （会话侧表现为无时长，与 §61.185 真值优先口径一致）。
     */
    private fun writeSessionMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverPath: String?,
    ) {
        val session = mediaSession ?: return
        // §61.249：基础元数据立即写（标题/艺人/专辑/时长）；封面位图异步补写
        //（decode 是 IO 成本，不在会话写口同步路径上阻塞）。失败不伪造（无封面态）。
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .build(),
        )
        coverArtJob?.cancel()
        coverArtJob = scope.launch {
            val art = runCatching {
                withContext(Dispatchers.IO) { decodeCoverArt(coverPath) }
            }.getOrNull() ?: return@launch
            // 会话可能已被销毁（异步窗口）——空判 + try 兜底。
            runCatching {
                mediaSession?.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                        .build(),
                )
            }
        }
    }

    /**
     * §61.249：封面位图解码（IO 线程调用）——max edge ≤ [COVER_ART_MAX_EDGE] 的
     * inSampleSize 采样（锁屏/AVRCP 显示足够，省内存）；文件/相对 cacheDir ref 可解，
     * content:// 走 ContentResolver；解不出返回 null（无封面态，不伪造）。
     */
    private fun decodeCoverArt(ref: String?): android.graphics.Bitmap? {
        if (ref.isNullOrBlank()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (ref.startsWith("content://")) {
            android.graphics.BitmapFactory.decodeStream(
                contentResolver.openInputStream(android.net.Uri.parse(ref)), null, bounds,
            )
        } else {
            val file = coverArtFile(ref, cacheDir) ?: return null
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_ART_MAX_EDGE) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return if (ref.startsWith("content://")) {
            android.graphics.BitmapFactory.decodeStream(
                contentResolver.openInputStream(android.net.Uri.parse(ref)), null, options,
            )
        } else {
            val file = coverArtFile(ref, cacheDir) ?: return null
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }

    /** §61.249：异步封面解码句柄（换曲取消上一张，防过期位图覆盖新元数据）。 */
    private var coverArtJob: kotlinx.coroutines.Job? = null

    /** D-93（12 §33）：Activity 与 Service 注入同一 @Singleton PlaybackRuntime，无需 Binder。 */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        unregisterAclReceiver()
        abandonAudioFocus()
        if (::lockHolder.isInitialized) lockHolder.releaseAll() // §61.306（K3b）：销毁兜底释锁
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

    /** 03-11 §2 B09：API <31 无此运行时权限，视为已可用（走无需权限的旧路径）。 */
    private fun bluetoothConnectGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** 每次事件读最新设置快照（自动暂停开关 / 连接提示热更新）+ 运行时权限真值。 */
    private fun bluetoothSettings(permissionGranted: Boolean): BluetoothSettings {
        val playback = settings.current().playback
        return BluetoothSettings(
            autoPauseOnDisconnect = playback.autoPauseOnDisconnect,
            showConnectedToast = playback.showConnectedToast,
            permissionGranted = permissionGranted,
        )
    }

    /** 权限变化后幂等刷新：已授权注册 ACL，未授权注销并保留 B01 降级路径。 */
    private fun refreshBluetoothPermission() {
        val granted = bluetoothConnectGranted()
        disconnectPolicy.updateSettings(bluetoothSettings(granted))
        if (granted) registerAclReceiver() else unregisterAclReceiver()
        Log.d(SESSION_TAG, "BLUETOOTH_CONNECT granted=$granted; ACL receiver ${if (granted) "on" else "off"}")
    }

    private fun registerAclReceiver() {
        if (aclRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(aclReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(aclReceiver, filter)
        }
        aclRegistered = true
    }

    private fun unregisterAclReceiver() {
        if (!aclRegistered) return
        unregisterReceiver(aclReceiver)
        aclRegistered = false
    }

    /** 读设备名 / 地址需 BLUETOOTH_CONNECT；已授权分支内调用，仍用 runCatching 防回收竞态。 */
    private fun bluetoothInfoFrom(intent: Intent?): BluetoothInfo? {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return null
        val name = runCatching { device.name.orEmpty() }.getOrDefault("")
        // B12：地址只在内存里用于 B14 去重，不写日志 / 不上传 / 不进 DB。
        val address = runCatching { device.address.orEmpty() }.getOrDefault("")
        return BluetoothInfo(name = name, address = address, connectedAt = System.currentTimeMillis())
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

        /** §61.249：会话封面位图 max edge（锁屏/AVRCP 显示足够，内存可控）。 */
        internal const val COVER_ART_MAX_EDGE: Int = 512

        /**
         * §61.249：封面 ref → 本地文件（纯函数可测）。绝对路径原样；相对路径锚
         * cacheDir（§61.223 cacheDirPrefix 教训：相对 ref 必须显式锚定）；
         * content:// / 空文件 / 不存在 = null（调用方走 ContentResolver 或无封面态）。
         */
        internal fun coverArtFile(ref: String?, cacheDir: java.io.File): java.io.File? {
            if (ref.isNullOrBlank() || ref.startsWith("content://")) return null
            // 跨平台绝对判断（File.isAbsolute 吃 Windows 盘符——startsWith("/") 会漏）。
            val candidate = java.io.File(ref)
            val file = if (candidate.isAbsolute) candidate else java.io.File(cacheDir, ref)
            return file.takeIf { it.isFile }
        }
        /** 12 §50：媒体按键兜底 / 通知动作的标准入口，由本应用内部组件转发。 */
        const val ACTION_PLAY = "com.example.cloudmusic.action.PLAY"
        const val ACTION_PAUSE = "com.example.cloudmusic.action.PAUSE"

        /** §61.26（G03 验收）：外部 seek 入口，`EXTRA_SEEK_POSITION_MS`（Long，毫秒）。 */
        const val ACTION_SEEK = "com.example.cloudmusic.action.SEEK"

        /** §61.122（T5-2）：ReplayGain 近似分析入口（`EXTRA_ANALYZE_MAX` 上限，默认 50）。 */
        const val ACTION_ANALYZE_RG = "com.example.cloudmusic.action.ANALYZE_RG"
        const val EXTRA_ANALYZE_MAX = "com.example.cloudmusic.extra.ANALYZE_MAX"

        /** §61.129（T-V #9）：重扫触发入口（refresh=true 冷扫口径）。 */
        const val ACTION_RESCAN = "com.example.cloudmusic.action.RESCAN"
        const val EXTRA_ANALYZE_FORCE = "com.example.cloudmusic.extra.ANALYZE_FORCE"

        /** §61.93（音效必须支持）：外部音效档位入口，`EXTRA_EFFECT_LEVEL`（Int，0=释放）。 */
        const val ACTION_EFFECT = "com.example.cloudmusic.action.EFFECT"
        const val EXTRA_EFFECT_LEVEL = "com.example.cloudmusic.extra.EFFECT_LEVEL"
        const val EXTRA_SEEK_POSITION_MS = "com.example.cloudmusic.extra.SEEK_POSITION_MS"

        /** §61.29：输出弹窗按动作申请 BLUETOOTH_CONNECT 后，通知 Service 刷新 ACL 注册。 */
        const val ACTION_BLUETOOTH_PERMISSION_CHANGED = "com.example.cloudmusic.action.BT_PERMISSION_CHANGED"

        /**
         * 12 §61.47 / F2：验收期 LOCAL 源注入 extra。值为 App 私有目录内的绝对文件路径，
         * 不携带任何权限需求；复用既有 `ACTION_PLAY`，不新增 Manifest action。
         * 生产形态随 `12 §61.33` 的 `ACTION_PLAY` 一并收口。
         */
        const val EXTRA_LOCAL_PATH = "com.example.cloudmusic.extra.LOCAL_PATH"

        /**
         * 12 §61.49 / F3：验收期 REMOTE 源注入 extra。值为 `http://127.0.0.1:8765/...`
         * 回环直链（`adb reverse` + PC `http.server`，沿用 A3 G01 口径）。
         * 复用既有 `ACTION_PLAY`，不新增 Manifest action / 权限 / 模块；
         * 生产形态随 `12 §61.33` 一并收口。
         */
        const val EXTRA_SOURCE_URL = "com.example.cloudmusic.extra.SOURCE_URL"
    }
}
