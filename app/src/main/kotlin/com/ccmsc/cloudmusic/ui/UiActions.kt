package com.ccmsc.cloudmusic.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ccmsc.cloudmusic.ui.player.loadLegacyTypeface
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccmsc.cloudmusic.SurfaceNav
import com.ccmsc.cloudmusic.di.playback.RuntimeState
import com.ccmsc.cloudmusic.model.SmartPlaylist
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.ErrorText
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.model.TrackInfo
import com.ccmsc.cloudmusic.output.dlna.DmrDevice
import com.ccmsc.cloudmusic.output.dlna.DeviceSource
import com.ccmsc.cloudmusic.output.dlna.ManualDmrSpec
import com.ccmsc.cloudmusic.output.router.ActiveOutput
import com.ccmsc.cloudmusic.output.router.OutputDeviceState
import com.ccmsc.cloudmusic.ui.player.LegacyPlayerPage
import com.ccmsc.cloudmusic.ui.player.PlayerStateBridge
import com.ccmsc.cloudmusic.player.PlayerPhase
import com.ccmsc.cloudmusic.player.PlayerState
import com.ccmsc.cloudmusic.player.QueueItem
import com.ccmsc.cloudmusic.storage.AppSettings
import com.ccmsc.cloudmusic.storage.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * §61.343（B-5 收尾）：组合根 60 参数的单一入口。
 *
 * 此前 `CloudMusicApp(...)` 有 60 个命名参数（99 行签名），MainActivity 调用点 279 行，
 * 加一个参数就要同时改签名与调用点、且调用点只能靠位置/名字对齐——参数漂移不会被编译发现。
 * 现将签名收敛为一个 [UiActions]，字段与默认值逐字照搬；`CloudMusicApp` 用
 * `with(actions)` 包裹既有函数体，函数体零改动（无 `this@CloudMusicApp` 引用，已核）。
 */
data class UiActions(
    val tracks: Flow<List<Track>>,
    val storages: Flow<List<Storage>>,
    val playlists: Flow<List<Playlist>>,
    val queue: Flow<List<QueueItem>>,
    val outputState: Flow<OutputDeviceState>,
    val settings: Flow<AppSettings>,
    val search: (String) -> Flow<List<Track>>,
    val runtimeState: StateFlow<RuntimeState>,
    val playerState: StateFlow<PlayerState>,
    val onRescanAll: () -> Unit,
    val onRetryUnavailable: () -> Unit,
    /** §61.207（A2-③）：单源刷新（数据源管理页「刷新此源」行内动作）——定向增量扫。 */
    val onRefreshStorage: (String) -> Unit = { _ -> },
    /** §61.208（A4）：合并重复项开关（scan.mergeDuplicates 的 UI 消费口径）。 */
    val mergeDuplicates: Boolean = false,
    val onMergeDuplicatesChanged: (Boolean) -> Unit = { _ -> },
    /** §61.211（G09 拍板 A）：MIUI 自启动引导跳转（仅 MIUI 系设备显示行）。 */
    val onOpenAutostartSettings: () -> Unit = {},
    val onAddSaf: () -> Unit,
    val onAddMediaStore: () -> Unit,
    /** §61.132：添加 WebDAV 源（url / username / password / rootPath）。 */
    val onAddWebDav: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** §61.151：添加 STRM 独立源（delegateStorageId + paths 多行，空=委托根目录）。 */
    val onAddStrm: (String, String) -> Unit = { _, _ -> },
    /** V0.2 §61.216：添加 SFTP 源（host / port / username / password / rootPath）。 */
    val onAddSftp: (String, Int, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    /** V0.2 §61.228：添加 FTP 源（host / port / username / password / rootPath / ssl）。 */
    val onAddFtp: (String, Int, String, String, String, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    /** §61.152：移除数据源（markOrphan → 曲目标记不可用，文件不删）。 */
    val onRemoveStorage: (String) -> Unit = { _ -> },
    val onPlay: () -> Unit,
    val onPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrev: () -> Unit,
    val onWifiOnlyChanged: (Boolean) -> Unit,
    val onOpenOutput: () -> Unit,
    /** §61.110：输出弹窗选择（本机 / DLNA）——DLNA 走发现 → 推流 → setActive 闭环。 */
    val onOutputSelected: (ActiveOutput) -> Unit = {},
    /** §61.200：输出弹窗的 DMR 设备列表（MainActivity 打开弹窗时发现并排序去重）。 */
    val dlnaDevices: StateFlow<List<DmrDevice>> = MutableStateFlow(emptyList()),
    /** §61.241（V3-M2）：上次投屏设备 USN——弹窗里给该设备标「上次投屏」。 */
    val lastDmrUsn: StateFlow<String?> = MutableStateFlow(null),
    /** §61.229：投屏态 DMR 音量（输出弹窗滑杆）；回调转发 runtime.setCastVolume。 */
    val castVolume: StateFlow<Int> = MutableStateFlow(50),
    val onCastVolumeChanged: (Int) -> Unit = { _ -> },
    /** §61.200：点具体设备直接投屏（跳过自动选择）。 */
    val onPickDmr: (DmrDevice) -> Unit = {},
    /** §61.200：弹窗内「重新扫描」触发重新发现。 */
    val onScanDlna: () -> Unit = {},
    /** §61.201：手动指定 DMR 的探测结果（null = 未探测）。 */
    val manualDmrResult: StateFlow<Result<DmrDevice>?> = MutableStateFlow(null),
    /** §61.201：提交手动探测（IP / 端口 / 描述地址）。 */
    val onProbeManualDmr: (ManualDmrSpec) -> Unit = {},
    /** §61.201：打开手动面板时清掉上次结果，避免旧状态误导。 */
    val onClearManualDmrResult: () -> Unit = {},
    /** §61.113（M2-1）：曲库分组视角播放入口——分组曲目替换业务队列并从 index 起播。 */
    val onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    /** §61.116（M6-3）：清理 B2 播放缓存（播放中由 runtime 侧拒绝）。 */
    val onClearCache: () -> Unit = {},
    /** §61.122（T5-2）：ReplayGain 近似分析（LOCAL 未分析曲目，cap 50）。 */
    val onAnalyzeReplayGain: () -> Unit = {},
    /** §61.233：封面在线刮削批处理（限数 50，toast 汇报）。 */
    val onScrapeCovers: () -> Unit = {},
    /** §61.247（V0.3 M5 / A1 Tier2）：重复曲目检测（设置页触发 → 结果面板）。 */
    val onFindDuplicates: () -> Unit = {},
    /** §61.247：检测结果流（null = 未检测）。 */
    val duplicateReport: StateFlow<com.ccmsc.cloudmusic.model.DuplicateReport?> = MutableStateFlow(null),
    /** §61.247：关闭结果面板时清空报告（下次触发重算）。 */
    val onClearDuplicates: () -> Unit = {},
    /** §61.230：淡入淡出开关（AudiofxSettings.fadeEnabled 热更新）。 */
    val fadeEnabled: Boolean = false,
    val onFadeEnabledChanged: (Boolean) -> Unit = { _ -> },
    /** §61.231：均衡器面板（AudiofxSettings + applyEqualizer 全链已接）。 */
    val eqEnabled: Boolean = false,
    val eqEffectLevel: Int = 0,
    val eqBandsMillibel: List<Int> = emptyList(),
    val onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit = { _, _, _ -> },
    /** §61.234：主题模式（设置页四选项；改动即 recreate）。 */
    val themeMode: String = "DARK_FOLLOW_SYSTEM",
    /**
     * §61.291：主题计算结果（MainActivity `applyThemeFromSettings()` 的返回值）。
     * 系统栏明暗靠它决定——UI 侧不重算 `uiMode`，避免两处判断漂移。
     */
    val darkTheme: Boolean = true,
    val onThemeChanged: (String) -> Unit = { _ -> },
    /** §61.116（M2-2）：扫描最短时长下限（ms），下次扫描生效。 */
    val scanMinDurationMs: Long = 10_000L,
    val onScanMinDurationChanged: (Long) -> Unit = {},
    /** §61.117（M2-2）：扫描忽略目录（小写比对），下次扫描生效。 */
    val scanIgnoreDirs: List<String> = emptyList(),
    val onScanIgnoreDirsChanged: (List<String>) -> Unit = {},
    /** §61.119（M3/M8）：歌单与队列交互（bridge 直达）。 */
    val bridge: PlayerStateBridge,
    /**
     * §61.183（三层守护网之③）：验收期画面证据通道 —— intent 导航 surface 请求流
     * （MainActivity 从 `SurfaceNav.parse` 粘性下发；默认空流 = 常规启动零行为变化）。
     * MIUI 禁 `input tap`，runbook 由此免注入直达播放页等 surface 截图取证。
     */
    val surfaceRequests: StateFlow<SurfaceNav.Surface?> = MutableStateFlow(null),
)
