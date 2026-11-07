package com.example.cloudmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.output.router.OutputRouter
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import com.example.cloudmusic.ui.CloudMusicApp
import com.example.cloudmusic.ui.CloudMusicTheme
import dagger.hilt.android.AndroidEntryPoint
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
