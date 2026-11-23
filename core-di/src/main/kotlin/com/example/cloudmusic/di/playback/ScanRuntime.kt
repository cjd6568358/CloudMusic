package com.example.cloudmusic.di.playback

import android.util.Log
import com.example.cloudmusic.scanner.ScanProgress
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * §61.324（B-4 拆域）：扫描域（触发 / 批量状态 / MediaStore 订阅）。
 *
 * 从 `PlaybackRuntime`（原 1301 行、12 类职责）切出的三域之一，边界是
 * **「什么时候扫、扫完把批量进度写进哪」**——不含单个文件的走查与标签解析
 * （那在 `:scanner` 的 `ScanCoordinator`）。
 *
 * 与其余域的耦合只有 `_state`（[RuntimeState] 是 UI 的唯一聚合态，由组合根持有）：
 * 本域只写 `scanning / scanned / total / errors / lastEffect` 五个字段，读写均单向。
 */
class ScanRuntime(
    private val coordinator: com.example.cloudmusic.scanner.ScanCoordinator,
    private val storages: StorageRepository,
    private val settings: com.example.cloudmusic.di.settings.SettingsStore,
    private val networkProbe: AndroidNetworkPlaybackGate,
    private val mediaChangeSource: MediaChangeSource,
    /** §61.329（A-6）：扫描收尾的 CUE 分轨封面继承（幂等，只写缺封面行）。 */
    private val tracks: com.example.cloudmusic.storage.TrackRepository,
    private val _state: MutableStateFlow<RuntimeState>,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var scanJob: Job? = null

    /**
     * §61.285：扫描代次（last-wins 的"谁有权收标志"）。
     *
     * `runScan` 取消旧任务后立即起新任务，旧任务的 `finally` 可能在新任务起跑之后才执行；
     * 只比 `scanJob` 身份在"扫描零步同步跑完"的极端情况下会误判（finally 早于赋值），
     * 代次是**起跑前**就定下的值，比较结果与调度顺序无关。
     */
    @Volatile
    private var scanGeneration: Int = 0

    /** §61.206（A2-①）：MediaStore 变更事件流（tryEmit，防抖后触发定向增量扫）。 */
    private val mediaChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    @Volatile
    private var mediaChangeRegistered = false

    /** 启动扫描侧接线并触发一次 App 启动增量扫（03-04 §4：≤10s）。 */
    fun start() {
        registerMediaChangeSubscription()
        runScan(ScanRequest())
    }

    /** 手动「重新扫描」/ 设置变更后的全量重扫入口。 */
    fun rescanAll() = runScan(ScanRequest(refresh = true))

    /** §61.324：进程收尾——取消在跑的扫描（原 `PlaybackRuntime.stop()` 的 `scanJob?.cancel()`）。 */
    fun cancel() {
        scanJob?.cancel()
    }

    /** 网络恢复 / 重试失败项：C-14 定向增量。 */
    fun retryUnavailable() = runScan(ScanRequest(onlyUnavailable = true))

    /**
     * §61.207（A2-③）：单源刷新（数据源管理页「刷新此源」行内动作）——
     * 定向增量扫（refresh=false 快照 diff，有变才重处理；与 §61.204 statHit 语义
     * 自然衔接）。与手动 RESCAN 同通道重入语义（后触发者取消前者）。
     */
    fun refreshStorage(storageId: String) = runScan(ScanRequest(storageIds = setOf(storageId)))

    /**
     * §61.206（A2-①）：MediaStore 变更订阅。幂等注册（start 可能被 Service 重建
     * 多次调用）；变更 → 防抖 [MediaChangeScanPolicy.DEBOUNCE_MS] → 定向增量扫
     * （仅 driverType=local 的存储，网络源由 RESCAN/设置页手动口径覆盖）。
     */
    private fun registerMediaChangeSubscription() {
        if (mediaChangeRegistered) return
        mediaChangeRegistered = true
        scope.launch {
            mediaChanges
                .debounce(MediaChangeScanPolicy.DEBOUNCE_MS)
                .collect {
                    val localIds = runCatching {
                        storages.observe().first()
                            .filter { it.driverType == "local" }
                            .mapTo(HashSet()) { it.id }
                    }.getOrDefault(emptySet())
                    if (!MediaChangeScanPolicy.shouldScan(scanJob?.isActive == true, localIds)) {
                        return@collect   // RESCAN 幂等 / 订阅空转守卫：零扫描动作
                    }
                    Log.i("CM-Scan", "MediaStore 变更 → 定向增量扫（本地源 ${localIds.size} 个）")
                    runScan(ScanRequest(storageIds = localIds))
                }
        }
        mediaChangeSource.register { mediaChanges.tryEmit(Unit) }
    }

    private fun runScan(rawRequest: ScanRequest) {
        // §61.323（B-2）：两条「有字段、无写方」的断链在此接线——
        // ① `onlyWifiMetadata`：设置页 `network.metadataWifiOnly` 只被 codec 读写，
        //    从未传进请求 ⇒ 恒为默认 true（与 wifiAvailable 是同型断链）；
        // ② `wifiAvailable`：全仓零写方、恒 true ⇒ ①的判定永远放行，开关形同虚设。
        // 每次触发读一次（非热路径）；纯 JVM 单测仍走 ScanRequest 默认值，不引 Android 依赖。
        val kind = networkProbe.currentKind()
        val request = effectiveScanRequest(
            rawRequest,
            metadataWifiOnly = settings.current().network.metadataWifiOnly,
            kind = kind,
        )
        Log.i("CM-Scan", "网络事实位：wifi=${request.wifiAvailable} onlyWifi=${request.onlyWifiMetadata} kind=$kind")
        // §61.178：重入可见化——扫描被 cancel 后真机只看到「正在扫描曲库」消失，
        // 无法判断是重入取消、Service 重建还是 scope 销毁。
        Log.i("CM-Scan", "runScan 触发 refresh=${request.refresh} 调用链=" +
            Throwable().stackTrace.take(6).joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" })
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = scope.launch {
            // §61.129（T-V #9）：扫描时长观测（冷扫 ≤60s / 增量 ≤10s 基线判据）。
            val scanStart = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(scanning = true, scanned = 0, total = 0, errors = 0) }
            try {
                var lastProgress: ScanProgress? = null
                // §61.184：per-storage 跑动集合（供「本轮干净 → 清陈旧错误态」判定）。
                val seenStorages = mutableSetOf<String>()
                coordinator.scan(request).collect { progress: ScanProgress ->
                    lastProgress = progress
                    seenStorages += progress.storageId
                    _state.update {
                        it.copy(
                            scanned = progress.scanned,
                            total = progress.total,
                            errors = progress.errors.size,
                        )
                    }
                }
                Log.i("CM-Scan", "scan done: %d ms (refresh=%s files≈%d)".format(
                    android.os.SystemClock.elapsedRealtime() - scanStart,
                    request.refresh, _state.value.scanned))
                // §61.329（A-6）：CUE 分轨原本永远没有封面——阶段 B 对分轨跳过 tag 读取
                // （§61.221），`embeddedCover` 恒 null；同目录封面又只落在整轨候选的缓存键上，
                // 同块并行谁先谁后不定。结果：分轨在播放页只能回退黑胶占位，背景取色链整条断。
                // 这里在扫描收尾按 (storageId, sourcePath) 亲缘把父音频行的封面回填给分轨行；
                // 幂等、只写空封面行，旧库也被这一趟就地修好（不依赖「下次重扫」）。
                runCatching { tracks.inheritCueSplitCovers() }
                    .onSuccess { n ->
                        if (n > 0) Log.i("CM-Scan", "cue 分轨封面继承: %d 行".format(n))
                    }
                    .onFailure { Log.w("CM-Scan", "cue 分轨封面继承失败: ${it.message}") }
                // §61.184：Storage 级扫描失败 → 数据源错误态（`StorageRepository.setError`
                // 此前零生产写方，审计揪出的断链）——设置页数据源行现在能看到「连接失败」；
                // 本轮跑过且未失败的 storage 清陈旧 errorMsg（幂等空串）。写侧失败只记日志，
                // 不影响扫描主流程。
                runCatching {
                    val storageIds = storages.observe().first().mapTo(HashSet()) { it.id }
                    storageErrorStates(
                        lastProgress?.errors.orEmpty(), storageIds, seenStorages,
                    ).forEach { (id, msg) ->
                        storages.setError(id, msg)
                        Log.i("CM-Scan", "storage error-state: %s → %s".format(id, msg.ifBlank { "已清" }))
                    }
                }.onFailure {
                    Log.w("CM-Scan", "storage error-state write failed: ${it.message}")
                }
                // §61.153：扫描错误可见化——此前 B07/storage 级错误静默吞掉，
                // WebDAV/STRM 扫描 0 文件无法归因（真机一手）。前 5 条细节 WARN。
                lastProgress?.errors?.take(5)?.forEach {
                    Log.w("CM-Scan", "scan error: %s %s %s".format(it.path, it.code, it.detail))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // §61.178：取消（`scanJob?.cancel()` 重入 / 离开页面）不是扫描失败。
                // 旧实现把 CancellationException 一并按 Exception 吞成 SCAN_FAILED，
                // 通知栏出现「设置已生效：SCAN_FAILED」而日志零输出。
                Log.i("CM-Scan", "scan cancelled: ${e.javaClass.name}: ${e.message} cause=${e.cause}")
                Log.i("CM-Scan", "cancel stack=" + e.stackTrace.take(6)
                    .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                throw e
            } catch (e: Exception) {
                // §61.178（可观测性）：此前只置 lastEffect 不打日志，真机「扫描 0 首」
                // 完全无法归因（与 §61.176 点歌静默失效同型：失败与没触发同形）。
                Log.w("CM-Scan", "scan failed: ${e::class.java.name}: ${e.message}", e)
                _state.update { it.copy(lastEffect = "SCAN_FAILED") }
            } finally {
                // §61.285（U-13 ②）：重入（last-wins）时旧任务的 finally 与新一轮起跑**无序**——
                // 旧任务后跑会把新任务的 `scanning` 覆盖成 false，重扫钮的进度环刚亮就灭
                // （正是 U-13 要解决的"看不到反馈"）。只有"仍是当前扫描任务"才收掉状态。
                if (scanGeneration == generation) {
                    _state.update { it.copy(scanning = false) }
                }
            }
        }
    }
}
