package com.example.cloudmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
                    onPlay = runtime::play,
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
