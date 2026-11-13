package com.example.cloudmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.di.settings.SettingsStore
import com.example.cloudmusic.output.dlna.DlnaCastCoordinator
import com.example.cloudmusic.output.router.ActiveOutput
import com.example.cloudmusic.output.router.OutputRouter
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import com.example.cloudmusic.ui.CloudMusicApp
import com.example.cloudmusic.ui.CloudMusicTheme
import com.example.cloudmusic.ui.player.PlayerStateBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * T-A6（`12 §33`）/ 第三十一批（`12 §40`）：Compose 应用入口。
 *
 * `PlaybackRuntime` 是 `@Singleton`，与 `PlaybackService` 共享同一实例，
 * 因此 UI 无需 `onBind()` / AIDL 即可拿到扫描进度、播放状态与播放控制（03-06 §10 单进程模型）。
 * 取数一律经 Repository / StateFlow（03-10 §11 #4），UI 层不接触 Driver / Room / OkHttp；
 * 搜索以 `(String) -> Flow` 形式传入，避免 UI 直接持有 Repository 实例。
 *
 * **第三十九批真机验收修复（`12 §49`）**：`PlaybackService` 虽在 Manifest 声明为
 * `android:exported="false"`，但此前**没有任何生产启动点**（`adb shell` 也无法拉起未导出组件）。
 * Activity 进入前台时启动它，才会真正创建 FGS 通知 / MediaSession / 音频焦点 /
 * BECOMING_NOISY receiver 并触发 `PlaybackRuntime.start()` 扫描。
 *
 * **第四十三批（`12 §52`，`§50.7 #3`）**：接入按动作权限闸门与本地数据源添加闭环。
 * `onPlay` 首次播放前申请 `POST_NOTIFICATIONS`（拒绝不阻断播放，B03）；
 * 数据源页新增 SAF 目录 / 媒体库导入两个入口，写入 Storage 后立即
 * `PlaybackRuntime.rescanAll()`，真机不再需要 `run-as` 灌 Room 测试夹具。
 * 两个 ActivityResult launcher 在**构造期**注册（lifecycle INITIALIZED），
 * 注入对象只在回调触发时才访问，故 `localSourceAdder` 用 `by lazy`。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 构造期注册；启动阶段零申请。 */
    private val permissionRequester = PermissionRequester(this)

    /** 构造期注册；回调里才访问 Hilt 注入的 Repository / Runtime。 */
    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) localSourceAdder.addSaf(uri)
    }

    private val localSourceAdder by lazy {
        LocalSourceAdder(this, storages, runtime, permissionRequester)
    }

    /**
     * `12 §61.40` / `03-10 §6`：播放页状态桥。用已注入的 `PlaybackRuntime` /
     * `TrackRepository` / `QueueRepository` 现场构造，不改 Hilt 注册图、不新建模块。
     */
    private val playerBridge by lazy {
        PlayerStateBridge(runtime, tracks, queue)
    }

    @Inject
    lateinit var tracks: TrackRepository

    @Inject
    lateinit var storages: StorageRepository

    @Inject
    lateinit var playlists: PlaylistRepository

    @Inject
    lateinit var queue: QueueRepository

    @Inject
    lateinit var output: OutputRouter

    @Inject
    lateinit var runtime: PlaybackRuntime

    /** 01 §6.1：设置页「允许移动数据播放」开关写入 network.wifiOnly（08 §6 单一来源）。 */
    @Inject
    lateinit var settingsStore: SettingsStore

    /** §61.110：DLNA 第一个生产调用点——输出弹窗 → 发现 → 推流起播（03-08 §10.1 最小闭环）。 */
    @Inject
    lateinit var castCoordinator: DlnaCastCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudMusicTheme {
                CloudMusicApp(
                    tracks = tracks.observeAll(),
                    storages = storages.observe(),
                    playlists = playlists.observePlaylists(),
                    queue = queue.observe(),
                    outputState = output.state(),
                    settings = settingsStore.settings,
                    search = tracks::search,
                    runtimeState = runtime.state,
                    playerState = runtime.playerState(),
                    onRescanAll = runtime::rescanAll,
                    onRetryUnavailable = runtime::retryUnavailable,
                    onAddSaf = { treeLauncher.launch(null) },
                    onAddMediaStore = { localSourceAdder.addMediaStore() },
                    onPlay = {
                        // 03-11 §2：首次播放前按动作申请通知权限。B03：被拒仍照常播放，
                        // 只少了通知卡片；核心能力不受权限拒绝影响。
                        permissionRequester.request(PermissionGate.UserAction.START_PLAYBACK) {
                            runtime.play()
                        }
                    },
                    onPause = runtime::pause,
                    onNext = runtime::next,
                    onPrev = runtime::prev,
                    onWifiOnlyChanged = { value ->
                        // 03-11 §4：写入后由 Flow 即时生效；B14 飞行模式下也照常保存、不联网校验。
                        lifecycleScope.launch {
                            settingsStore.update {
                                it.copy(network = it.network.copy(wifiOnly = value))
                            }
                        }
                    },
                    onOpenOutput = {
                        // §61.29 / 03-11 §2 SHOW_BLUETOOTH_NAME：只在用户主动打开输出设备
                        // 弹窗时按动作申请，启动阶段零申请；拒绝由 PermissionDecision 降级
                        // （B01：不显示设备名但仍可播放、有线拔出仍能暂停），不循环弹窗。
                        permissionRequester.request(
                            PermissionGate.UserAction.SHOW_BLUETOOTH_NAME,
                        ) { _ ->
                            // 同 App 显式 Intent：让 Service 重读权限并按需注册 / 注销 ACL 广播。
                            startService(
                                Intent(this@MainActivity, PlaybackService::class.java)
                                    .setAction(PlaybackService.ACTION_BLUETOOTH_PERMISSION_CHANGED),
                            )
                        }
                    },
                    // §61.110：DLNA 第一个生产调用点——发现 → 首台设备推流起播 →
                    // setActive(DLNA) 仅在 started=true 时写入（03-08 §14 #9 唯一裁决者）；
                    // 无设备 / 无曲目 / 失败都如实写 routeHint 并保持本机，不伪造投屏态。
                    // 设备选择列表 / 本机静音 / 断连回落 / 切歌保持 = 会话引擎（挂账）。
                    onOutputSelected = { target ->
                        lifecycleScope.launch {
                            when (target) {
                                // 03-08 §14 #9：setActive 与 onOutputChanged 必须成对
                                // （守护脚本钉死计数相等）；回落恢复本机音量与媒体控件。
                                ActiveOutput.LOCAL -> {
                                    output.setActive(ActiveOutput.LOCAL)
                                    runtime.onOutputChanged(false)
                                }
                                ActiveOutput.DLNA -> {
                                    output.updateRouteHint("正在发现局域网 DMR…")
                                    val devices = runCatching { castCoordinator.discover() }
                                        .getOrElse { emptyList() }
                                    if (devices.isEmpty()) {
                                        output.updateRouteHint("未发现 DLNA 设备（保持本机播放）")
                                        return@launch
                                    }
                                    // 挂账：多设备选择列表（03-08 §4.1）；本批先取第一台并明示设备名。
                                    val device = devices.first()
                                    val currentTrackId =
                                        runtime.playerState().value.currentTrackId
                                    val current = currentTrackId?.let { tracks.get(it) }
                                    if (current == null) {
                                        output.updateRouteHint("无正在播放的曲目，无法投屏")
                                        return@launch
                                    }
                                    runCatching {
                                        val result = castCoordinator.startCast(current, device)
                                        if (result.started) {
                                            // T09：本机静音 + 媒体控件停用（03-08 §10.1 的
                                            // 本机侧动作——API 已在，随本批接线）。
                                            output.setActive(ActiveOutput.DLNA)
                                            runtime.onOutputChanged(true)
                                        }
                                    }.onFailure {
                                        output.updateRouteHint(
                                            "投屏失败：${it.message ?: "未知错误"}（保持本机播放）",
                                        )
                                    }
                                }
                            }
                        }
                    },
                    bridge = playerBridge,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // minSdk 26 → startForegroundService 恒可用；Service 已存在时只回调 onStartCommand。
        startForegroundService(Intent(this, PlaybackService::class.java))
    }
}
