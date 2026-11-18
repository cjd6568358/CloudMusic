package com.example.cloudmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.di.db.LegacyTextRepairRunner
import com.example.cloudmusic.di.db.TrackDao
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.di.settings.SettingsStore
import com.example.cloudmusic.output.dlna.DlnaCastCoordinator
import com.example.cloudmusic.output.router.ActiveOutput
import com.example.cloudmusic.output.router.OutputRouter
import com.example.cloudmusic.di.lyrics.OnlineLyricsFetcher
import com.example.cloudmusic.output.dlna.DescriptionFetcher
import com.example.cloudmusic.output.dlna.DmrDevice
import com.example.cloudmusic.output.dlna.DmrDeviceSelector
import com.example.cloudmusic.output.dlna.ManualDmrProbe
import com.example.cloudmusic.output.dlna.ManualDmrSpec
import com.example.cloudmusic.player.QueueRepository
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.DuplicateFolder
import com.example.cloudmusic.storage.LyricFileStore
import com.example.cloudmusic.storage.PlaylistRepository
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import com.example.cloudmusic.ui.CloudMusicApp
import com.example.cloudmusic.ui.CloudMusicTheme
import com.example.cloudmusic.ui.SplashScreen
import com.example.cloudmusic.ui.player.PlayerStateBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    /** §61.234：按 settings.theme + 系统深色计算 isDark 并切 Comp/FnComp token。 */
    private fun applyThemeFromSettings() {
        val mode = runCatching { settingsStore.current().theme }
            .getOrDefault(com.example.cloudmusic.storage.ThemeMode.DARK_FOLLOW_SYSTEM)
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isDark = when (mode) {
            com.example.cloudmusic.storage.ThemeMode.LIGHT -> false
            com.example.cloudmusic.storage.ThemeMode.DARK -> true
            com.example.cloudmusic.storage.ThemeMode.FOLLOW_SYSTEM -> systemDark
            else -> true   // DARK_FOLLOW_SYSTEM（默认）与历史行为一致：深色优先
        }
        com.example.cloudmusic.ui.applyThemeCompat(isDark)
    }

    private val playerBridge by lazy {
        // §61.198：歌词四级取数（本地缓存 → 在线 provider → 空）经桥接注入，
        // Activity/UI 不接触 OkHttp / 文件系统；Hilt 两个单例由 ScanModule 提供。
        PlayerStateBridge(runtime, tracks, queue, lyricStore, onlineLyrics)
            .apply { attachTrackCache(lifecycleScope) }
    }

    /** §61.200：输出弹窗的 DMR 设备列表（用户打开弹窗时才发现，不常驻扫网）。 */
    private val dlnaDevices = MutableStateFlow<List<DmrDevice>>(emptyList())

    /** §61.200：上次成功投屏的 USN，列表里仍在线则优先选中。 */
    private var rememberedDmrUsn: String? = null

    /** §61.201：手动 DMR 探测结果（null=未探测；Result 失败带明确原因）。 */
    private val manualDmrResult = MutableStateFlow<Result<DmrDevice>?>(null)

    /** §61.201：手动探测走既有 [ManualDmrProbe] + 生产 DescriptionFetcher，不做第二套解析。 */
    private fun probeManualDmr(spec: ManualDmrSpec) {
        manualDmrResult.value = null
        lifecycleScope.launch {
            val result = runCatching { ManualDmrProbe.probe(spec, descriptionFetcher) }
            manualDmrResult.value = result
            result.onSuccess { device ->
                dlnaDevices.value = DmrDeviceSelector.ranked(dlnaDevices.value + device)
                output.updateRouteHint("手动设备已找到：${device.friendlyName}")
            }.onFailure {
                output.updateRouteHint("手动 DMR 探测失败：${it.message}")
            }
        }
    }

    /** §61.200：打开输出弹窗 / 点「重新扫描」时刷新设备列表（发现结果经选择器排序去重）。 */
    private fun refreshDlnaDevices() {
        lifecycleScope.launch {
            output.updateRouteHint("正在发现局域网 DMR…")
            val found = runCatching { castCoordinator.discover() }.getOrElse { emptyList() }
            dlnaDevices.value = DmrDeviceSelector.ranked(found)
            if (found.isEmpty()) output.updateRouteHint("未发现 DLNA 设备（保持本机播放）")
        }
    }

    /** §61.200：把当前曲目推到用户点选的设备（原自动选择逻辑收口到此，两个入口共用）。 */
    private fun castTo(device: DmrDevice) {
        lifecycleScope.launch {
            val currentTrackId = runtime.playerState().value.currentTrackId
            val current = currentTrackId?.let { tracks.get(it) }
            if (current == null) {
                output.updateRouteHint("无正在播放的曲目，无法投屏")
                return@launch
            }
            runCatching {
                val result = castCoordinator.startCast(current, device)
                if (result.started) {
                    // §61.193：记住投屏目标 → 切歌保持（runtime dlnaRecastLoop）。
                    runtime.onDlnaDeviceSelected(device)
                    rememberedDmrUsn = device.usn
                    // T09：本机静音 + 媒体控件停用（03-08 §10.1 / §14 #9 成对契约）。
                    output.setActive(ActiveOutput.DLNA)
                    runtime.onOutputChanged(true)
                }
            }.onFailure {
                output.updateRouteHint("投屏失败：${it.message ?: "未知错误"}（保持本机播放）")
            }
        }
    }

    /** §61.198：歌词缓存（内嵌 USLT / 同目录 lrc / 在线命中共用）。 */
    @Inject
    lateinit var lyricStore: LyricFileStore

    /** §61.198：docs/01 §0 拍板的在线歌词能力（本地缺失才触发）。 */
    @Inject
    lateinit var onlineLyrics: OnlineLyricsFetcher

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

    /** §61.201：手动指定 DMR 的描述 XML 抓取端口（生产 = OkHttpDescriptionFetcher）。 */
    @Inject
    lateinit var descriptionFetcher: DescriptionFetcher

    /**
     * §61.173：存量乱码标题修复需要直接操作 DAO —— 逐行文本清洗 + 条件 UPDATE，
     * 不是 Repository 的领域语义，故不进 `TrackRepository` 接口（避免污染跨模块契约）。
     */
    @Inject
    lateinit var trackDao: TrackDao

    /** §61.173：存量文本修复器（纯数据维护，无播放/扫描副作用）。 */
    private val textRepair by lazy { LegacyTextRepairRunner(trackDao) }

    /**
     * §61.173（2026-11-16 用户问「乱码的问题什么时候修」）：**存量**脏标题一次性改写。
     *
     * 背景：§61.170 已修「入库路径」，但 `ScanCoordinator` 对 size/mtime 未变的文件直接跳过，
     * 重扫**不会**重读这些文件的标签 ⇒ 已落库的 52 条 GBK 误读标题只能靠本通道改写。
     *
     * 幂等：`LegacyTextRepairRunner.run()` 无脏行时零写入；本方法另用 SharedPreferences 记
     * `legacy_text_repair_v1_done`，避免每次冷启动白读全库文本。失败（异常）不写标记，下次重试。
     */
    private fun repairLegacyTextOnce() {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TEXT_REPAIR_V2, false)) return
        lifecycleScope.launch {
            val result = runCatching { textRepair.run() }
                .onFailure { Log.w("CM-Repair", "legacy text repair failed: ${it.message}") }
                .getOrNull() ?: return@launch
            prefs.edit().putBoolean(KEY_TEXT_REPAIR_V2, true).apply()
            Log.i("CM-Repair", "legacy text repair scanned=${result.scanned} repaired=${result.repaired}")
        }
    }

    /**
     * §61.183（三层守护网之③）：验收期画面证据通道 —— intent 导航 surface 的请求流。
     * MIUI 禁 `input tap`（INJECT_EVENTS）导致验收 runbook 无法自动走到播放页截图；
     * 本通道以显式组件 intent（`am start -n …MainActivity -a <ACTION> --activity-single-top
     * --activity-clear-top`）直达 surface，纯取证、不注入事件。常规 LAUNCHER 启动
     * parse 返回 null，零行为变化；收口随 `12 §61.33` 的 PlaybackService exported 一并评估。
     */
    private val surfaceRequests = MutableStateFlow<SurfaceNav.Surface?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §61.183：冷启动携带导航 action（am start -a OPEN_PLAYER）→ 粘性请求，
        // 启动页结束由 CloudMusicApp 内收集器兑现。
        surfaceRequests.value = SurfaceNav.parse(intent?.action)
        repairLegacyTextOnce()
        // §61.234（V0.2 主题化）：启动时按 settings.theme + 系统深色切 token——
        // recreate 后 token 重新初始化，设置页改主题即 recreate 生效。
        applyThemeFromSettings()
        setContent {
            CloudMusicTheme {
                // docs/01 §0（2026-11-16 拍板）：启动页严格像素级复刻竞品。
                // 作为覆盖层显示 1.2s（或点击跳过），结束后露出首屏。
                // §61.169：CloudMusicApp 冷启动已由「播放页」改为「首页」（l3_visual_replica_spec
                // 同步钉死 showPlayer=false），与本启动页一起构成「启动页 → 首页」的竞品口径。
                // §61.171（2026-11-16 用户一手）：必须用 rememberSaveable —— 用 remember 时
                // 横竖屏旋转会重建 Activity，状态归位到 true，启动页被**重放**一次
                // （用户实测：旋转又看到网易云启动页）。启动页是「每次冷启动一次」的语义，
                // 不是「每次重组一次」。
                var showSplash by rememberSaveable { mutableStateOf(true) }
                if (showSplash) {
                    LaunchedEffect(Unit) {
                        delay(1200L)
                        showSplash = false
                    }
                    SplashScreen(onFinish = { showSplash = false })
                } else {
                val appSettingsState = settingsStore.settings.collectAsState(
                    initial = AppSettings())
                CloudMusicApp(
                    // §61.208（A4）：同 URL 双源折叠（mergeDuplicates 开关的消费方）——
                    // UI 展示层折叠，不改库不删行（曲库行是用户资产）。
                    tracks = combine(
                        tracks.observeAll(),
                        settingsStore.settings.map { it.scan.mergeDuplicates },
                    ) { list, merge ->
                        DuplicateFolder.fold(list, merge)
                    },
                    storages = storages.observe(),
                    playlists = playlists.observePlaylists(),
                    queue = queue.observe(),
                    outputState = output.state(),
                    settings = settingsStore.settings,
                    // §61.208：搜索结果同口径折叠。
                    search = { query ->
                        combine(
                            tracks.search(query),
                            settingsStore.settings.map { it.scan.mergeDuplicates },
                        ) { list, merge -> DuplicateFolder.fold(list, merge) }
                    },
                    runtimeState = runtime.state,
                    playerState = runtime.playerState(),
                    onRescanAll = runtime::rescanAll,
                    onRetryUnavailable = runtime::retryUnavailable,
                    // §61.207（A2-③）：单源刷新（数据源管理页「刷新此源」）——定向增量扫。
                    onRefreshStorage = { id ->
                        runtime.refreshStorage(id)
                        Toast.makeText(this@MainActivity, "已发起该源扫描", Toast.LENGTH_SHORT).show()
                    },
                    // §61.208（A4）：合并重复项开关（scan.mergeDuplicates 的 UI 消费口径）。
                    mergeDuplicates = appSettingsState.value.scan.mergeDuplicates,
                    onMergeDuplicatesChanged = { value ->
                        lifecycleScope.launch {
                            settingsStore.update {
                                it.copy(scan = it.scan.copy(mergeDuplicates = value))
                            }
                        }
                    },
                    // §61.211（G09 拍板 A / 预研报告 §4）：MIUI 自启动引导——跳系统
                    // 应用详情页（无敏感权限，PRD §9 不申请电池优化豁免口径不变）。
                    onOpenAutostartSettings = {
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddSaf = { treeLauncher.launch(null) },
                    onAddMediaStore = { localSourceAdder.addMediaStore() },
                    // §61.132：WebDAV 源添加——StorageRepository.add 落库 + rescanAll 触发扫描。
                    onAddWebDav = { url, username, password, rootPath ->
                        lifecycleScope.launch {
                            runCatching {
                                val addition = org.json.JSONObject().apply {
                                    put("address", url)
                                    put("root_folder_path", rootPath)
                                    if (username.isNotBlank()) put("username", username)
                                    if (password.isNotBlank()) put("password", password)
                                }.toString()
                                storages.add("webdav", addition)
                            }.onSuccess {
                                runtime.rescanAll()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "WebDAV 添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    // §61.151：STRM 独立源添加——StorageRepository.add 落库 + rescanAll。
                    onAddStrm = { delegateId, paths ->
                        lifecycleScope.launch {
                            runCatching {
                                val addition = org.json.JSONObject().apply {
                                    put("delegate_storage_id", delegateId)
                                    put("paths", paths)
                                }.toString()
                                storages.add("strm", addition)
                            }.onSuccess {
                                runtime.rescanAll()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "STRM 添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    // V0.2 §61.216：SFTP 源——凭据只写 Addition.raw（DataStore），不进日志 / Toast。
                    // V0.2 §61.228：FTP 源——凭据只写 Addition.raw（DataStore），不进日志 / Toast。
                    onAddFtp = { host, port, user, pass, root ->
                        lifecycleScope.launch {
                            runCatching {
                                val addition = org.json.JSONObject().apply {
                                    put("host", host)
                                    put("port", port)
                                    if (user.isNotBlank()) put("username", user)
                                    if (pass.isNotBlank()) put("password", pass)
                                    put("root_folder_path", root.ifBlank { "/" })
                                }.toString()
                                storages.add("ftp", addition)
                            }.onSuccess {
                                runtime.rescanAll()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "FTP 添加失败（请检查地址与凭据）", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAddSftp = { host, port, user, pass, root, privateKey, passphrase ->
                        lifecycleScope.launch {
                            runCatching {
                                val addition = org.json.JSONObject().apply {
                                    put("host", host)
                                    put("port", port)
                                    put("username", user)
                                    put("password", pass)
                                    put("root_folder_path", root.ifBlank { "/" })
                                    if (privateKey.isNotBlank()) put("private_key", privateKey)
                                    if (passphrase.isNotBlank()) put("passphrase", passphrase)
                                }.toString()
                                storages.add("sftp", addition)
                            }.onSuccess {
                                runtime.rescanAll()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "SFTP 添加失败（请检查地址与凭据）", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    // §61.152：移除数据源——markOrphan（曲目标记不可用）+ rescanAll。
                    onRemoveStorage = { id ->
                        lifecycleScope.launch {
                            runCatching {
                                storages.remove(id)
                            }.onSuccess {
                                runtime.rescanAll()
                                Toast.makeText(this@MainActivity, "数据源已移除", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "移除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
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
                    // §61.176（用户一手：点击歌曲根本播放不了）——根因是这条线**从未接线**：
                    // `CloudMusicApp.onPlayTracks` 自 §61.113 引入起就只有默认空实现
                    // （`{ _, _ -> }`），MainActivity 侧一直没传，于是首页 / 音乐库 / 搜索 /
                    // 我的音乐 / 歌单里所有「点某一首」都落在空 lambda 上，UI 无反馈也无日志。
                    // `PlaybackRuntime.playTracks`（§61.113 已实现：setAll → setQueue →
                    // playAt）恰好就是该回调的语义，直接绑定即闭环。
                    // 权限口径与 `onPlay` 一致：首次播放前按动作申请通知权限，B03 被拒仍照常播放
                    // （只少通知卡片），不阻塞点歌。
                    onPlayTracks = { items, startAt ->
                        permissionRequester.request(PermissionGate.UserAction.START_PLAYBACK) {
                            runtime.playTracks(items, startAt)
                        }
                    },
                    onWifiOnlyChanged = { value ->
                        // 03-11 §4：写入后由 Flow 即时生效；B14 飞行模式下也照常保存、不联网校验。
                        lifecycleScope.launch {
                            settingsStore.update {
                                it.copy(network = it.network.copy(wifiOnly = value))
                            }
                        }
                    },
                    onOpenOutput = {
                        // §61.200：用户主动打开弹窗才发现 DMR（不常驻扫网），结果喂给设备列表。
                        refreshDlnaDevices()
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
                                    // §61.200：自动选择入口用「记住设备优先 + 排序第一台」；
                                    // 弹窗里也可直接点具体设备（onPickDmr → castTo）。
                                    val device = DmrDeviceSelector.pick(dlnaDevices.value, rememberedDmrUsn)
                                    if (device == null) {
                                        refreshDlnaDevices()
                                        output.updateRouteHint("未发现 DLNA 设备（保持本机播放）")
                                        return@launch
                                    }
                                    castTo(device)
                                }
                            }
                        }
                    },
                    // §61.200：弹窗设备列表（打开时/重新扫描时刷新）+ 点具体设备直接投屏。
                    dlnaDevices = dlnaDevices,
                    // §61.229：投屏态 DMR 音量（输出弹窗滑杆；打开弹窗时顺带 GetVolume 刷新）。
                    castVolume = runtime.dlnaVolume(),
                    onCastVolumeChanged = { v -> runtime.setCastVolume(v); runtime.refreshCastVolume() },
                    onPickDmr = { device -> castTo(device) },
                    onScanDlna = { refreshDlnaDevices() },
                    manualDmrResult = manualDmrResult,
                    onProbeManualDmr = { spec -> probeManualDmr(spec) },
                    onClearManualDmrResult = { manualDmrResult.value = null },
                    // §61.116（M6）：维护段——清缓存（播放中由 runtime 拒绝并返回 -1）
                    // + 扫描最短时长（写入后下次扫描生效）。
                    onClearCache = {
                        val freed = runtime.clearMediaCache()
                        val msg = when {
                            freed < 0 -> "播放中，暂不能清理缓存"
                            else -> "已释放 %.1f MB 播放缓存".format(freed / 1024.0 / 1024.0)
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    },
                    onAnalyzeReplayGain = { runtime.analyzeLibraryReplayGain(50) },
                    fadeEnabled = runtime.fadeEnabled().collectAsState(false).value,
                    onFadeEnabledChanged = { on -> runtime.updateFadeEnabled(on) },
                    themeMode = settingsStore.current().theme,
                    onThemeChanged = { mode ->
                        lifecycleScope.launch {
                            runCatching { runtime.updateTheme(mode) }
                                .onSuccess { recreate() }
                        }
                    },
                    eqEnabled = settingsStore.current().audiofx.enabled,
                    eqEffectLevel = settingsStore.current().audiofx.effectLevel,
                    eqBandsMillibel = settingsStore.current().audiofx.bandsMillibel,
                    onApplyEqualizer = { en, lvl, bands -> runtime.applyEqualizer(en, lvl, bands) },
                    scanMinDurationMs = appSettingsState.value.scan.minDurationMs,
                    onScanMinDurationChanged = { v ->
                        lifecycleScope.launch {
                            settingsStore.update {
                                it.copy(scan = it.scan.copy(minDurationMs = v))
                            }
                        }
                    },
                    scanIgnoreDirs = appSettingsState.value.scan.ignoreDirs,
                    onScanIgnoreDirsChanged = { dirs ->
                        lifecycleScope.launch {
                            settingsStore.update {
                                it.copy(scan = it.scan.copy(ignoreDirs = dirs))
                            }
                        }
                    },
                    bridge = playerBridge,
                    surfaceRequests = surfaceRequests,
                )
                } // end else (showSplash)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // minSdk 26 → startForegroundService 恒可用；Service 已存在时只回调 onStartCommand。
        startForegroundService(Intent(this, PlaybackService::class.java))
    }

    /** §61.183：热启动导航（--activity-single-top 复用栈顶实例时经此兑现）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        surfaceRequests.value = SurfaceNav.parse(intent?.action)
    }

    private companion object {
        /** §61.173：数据维护标记库（与 `08` 用户配置项无关，属内部一次性任务状态）。 */
        const val MAINTENANCE_PREFS = "maintenance"
        /** v2（§61.173）：v1 只修 GBK，v2 追加百分号编码 —— 换 key 以确保存量再跑一轮。 */
        const val KEY_TEXT_REPAIR_V2 = "legacy_text_repair_v2_done"
    }
}
