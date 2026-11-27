package com.ccmsc.cloudmusic.player

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.common.NetworkPlaybackGate
import com.ccmsc.cloudmusic.common.NetworkPlaybackPolicy
import com.ccmsc.cloudmusic.engine.api.EngineConfig
import com.ccmsc.cloudmusic.engine.api.EngineHandle
import com.ccmsc.cloudmusic.engine.api.EngineSource
import com.ccmsc.cloudmusic.engine.api.EngineState
import com.ccmsc.cloudmusic.engine.api.IMusicEngine
import com.ccmsc.cloudmusic.media.IMediaControl
import com.ccmsc.cloudmusic.media.PlayerControllerHandle
import com.ccmsc.cloudmusic.model.CueEnvelope
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.MediaCacheKey
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * docs/03-06 §9 状态机 + §8 队列双轨的**唯一业务编排者**。
 *
 * - UI **不得**直接调 [IMusicEngine]；只观察 [state]，动作经 [asHandle] / suspend API。
 * - 每次队列变更由 [QueueSync] **单向全量下发**；禁止反向读 native 队列回写业务态（§8 / `13 §4`）。
 * - C-05 的直接消费方：按 [Link.kind] 把 url / localPath / 回环代理 URL 填进 [EngineSource]，
 *   引擎舱经 `DataProvider` 自取，不消费 Java 字节流（`16 §3.2`）。
 * - prepare 新曲目必须先 [IMusicEngine.release] 旧 handle（§15 T06，内存实现会 check）。
 */
class PlayerController(
    private val engine: IMusicEngine,
    private val sync: QueueSync,
    private val lookup: TrackLookup,
    private val resolver: TrackSourceResolver,
    private val media: IMediaControl? = null,
    private val config: PlayerConfig = PlayerConfig(),
    /** 03-02 §344：播放前由 03-06 检查「默认仅 WiFi 播放」；纯 JVM 端口，生产实现由 :core-di 注入。 */
    private val networkGate: NetworkPlaybackGate = NetworkPlaybackPolicy.ALLOW_ALL,
    /** §61.181（B 案）：引擎真值时长回填曲库的落库口；null = 不回填（测试 / 未接线）。 */
    private val durationWriter: TrackDurationWriter? = null,
    /** §61.182：播放游标写侧（playback_state 落库）；null = 不写（单测 / 未接线）。 */
    private val cursorWriter: CursorWriter? = null,
    /** §61.253：源级失败 → UNREACHABLE 标记（孤儿源曲库不再谎报 OK）。 */
    private val availabilityWriter: com.ccmsc.cloudmusic.player.TrackAvailabilityWriter? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** §61.335（B-7）：诊断日志出口；默认 NOOP 保证既有 JVM 测试零改动。 */
    private val logPort: com.ccmsc.cloudmusic.common.LogPort = com.ccmsc.cloudmusic.common.LogPort.NOOP,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var queue: List<QueueItem> = emptyList()
    private var shuffle: List<Int> = emptyList()
    private var handle: EngineHandle? = null

    /** §61.139：本次 prepare 是否走 ExoPlayer 原生 playlist（gapless）路径。 */
    private var queueMode = false
    /** §61.139：与 queue 下标一一对应的 EngineSource（切曲时同步网络态 / 元数据）。 */
    private var preparedSources: List<EngineSource> = emptyList()

    /**
     * §61.319（R2）：setQueue 时一次批量预取的队列曲目快照（与 queue 同长同序）。
     * 队列变更必经 setQueue（restore 亦经此口），因此 prepareAt / buildQueueSources
     * 全程零 Room 点查；缺曲库行 = null（按原语义跳过 gapless，不伪造）。
     */
    private var queueTracks: List<Track?> = emptyList()

    /** 最近一次 prepare 的曲目是否为网络音源；供 PlaybackService 同步判定避免会话假报 PLAYING。 */
    @Volatile
    private var currentNetworkSource = false

    /**
     * §61.224（V0.2 M5-③）：当前曲的 CUE 分轨包络（毫秒）。
     * - [cueStartMs] > 0：prepare 落定后定位到分轨起点（seekTo=0 的新起播；恢复位置走钳制）；
     * - [cueEndMs] > 0：节拍器读到 position ≥ 终点即交棒 next()（500ms 粒度，误差如实登记），
     *   引擎自然 ENDED 分支不受影响；
     * - seek 一律钳制进 [cueStartMs, cueEndMs) 包络（分轨语义 = 只在本轨范围内）。
     * 整轨 / 普通曲目恒 (0, 0)（§61.221 落库默认），全链零行为变化。
     */
    private var cueStartMs = 0L
    private var cueEndMs = 0L

    /**
     * §61.384（真机 2026-11-27 用户「卡死了」）：**播放页进度源**。
     *
     * `_state.positionMs` 只在 seek/暂停/切歌时写（写入会整棵树重组，§119 注释），所以播放中
     * 进度条恒为切歌那一刻的值 —— 界面看着像卡死，其实音频在走（日志 `writeSessionTransport
     * pos` 一直在涨）。这里单开一条只给进度条用的高频流：500ms 更新一次，订阅方只有播放页。
     */
    private val _displayPositionMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun displayPositionMs(): kotlinx.coroutines.flow.StateFlow<Long> = _displayPositionMs

    /**
     * §61.230（V0.2 M6）：淡入淡出——起播渐入（全场景）与分轨终点渐出（§61.224 交棒
     * 组合）。音量包络经 [IMusicEngine.setVolume]（0..1 线性曲线，50ms 步进）；
     * 切歌/终点完成后复位 1f。手动切歌不渐出（引擎 ENDED 无提前量信号，竞争协调
     * 复杂化——如实登记）；自然结束同样不渐出。
     */
    @Volatile
    private var fadeEnabled = false
    @Volatile
    private var fadeDurationMs = 2000
    private var fadeJob: kotlinx.coroutines.Job? = null

    /**
     * §61.237：自然结束渐出的 ENDED 竞争协调——armed=true 表示「fade-out 已启动，
     * next() 由 fadeOut 回调独占」；引擎自然 ENDED 事件到达时若 armed 则跳过
     * next（否则会双曲跳跃）。手动 seek 解除 armed 并复位音量（用户干预优先）。
     */
    @Volatile
    private var fadeOutArmed = false

    /**
     * §61.321（R3）：节拍器采集到的最新引擎位置（毫秒），**不写进 [PlayerState]**。
     * `PlayerState.positionMs` 是「转换点快照」（prepare / seek / pause 落值），
     * 500ms tick 只更新本字段：根 UI 用 `collectAsState` 收整份 state，若每拍都
     * `copy(positionMs=)`，整棵树（tab / 列表 / 智能歌单扫描）每 500ms 全量重组。
     * 高频消费方只有播放页，读 `PlayerStateBridge.positionMs`（独立流）。
     * 相对 seek（媒体键 / 长按）读本字段（PLAYING 期），暂停态回落 state 精确值。
     */
    @Volatile
    private var livePositionMs = 0L

    /**
     * §61.321：节拍器真值的只读快照（测试判定口）。与 [PlayerState.positionMs] 的
     * 「转换点快照」语义分离——缓冲冻结判据盯这个字段，不在 state 上。
     */
    internal val livePositionSnapshot: Long get() = livePositionMs

    /** §61.230：设置侧热更新（PlaybackComposition 的 audiofx 设置循环转发）。 */
    fun updateFade(enabled: Boolean, durationMs: Int) {
        fadeEnabled = enabled
        fadeDurationMs = durationMs.coerceIn(200, 5000)
    }

    /**
     * §61.27（G08 真机实证 · 冷启动启动竞态）：`PlaybackService.onCreate` 异步
     * `restore()` 重建队列，而 `ACTION_PLAY` / 媒体键可能在 queue 仍为空时到达 ——
     * 此时 [resume] 既无 handle、`restartCurrentIfAny()` 的 index 又不在
     * `queue.indices`，指令被静默丢弃；restore 随后按 PRD §6.1 只 prepare 不播
     * → MediaSession 假报 PLAYING、进度恒 0（K60 13:11:14 实测：需第二次
     * ACTION_PLAY 才出声）。这里记下**用户明确的播放意图**，由 restore 兑现。
     * 用 AtomicBoolean：播放指令在 Service 线程、restore 在 [scope] 协程。
     */
    private val pendingPlay = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §61.303（K1a）：手动切歌串行化 + 防抖（lx actionId+promise 链对齐；复用
     * §61.285 扫描代次先例的代次护栏思路）。switchSeq = 手动指令代次（latest-wins：
     * 连点 3 次仅末次生效）；switchMutex 保证 prepare 序贯无交错；自动连播
     * （ENDED→[advanceNext] / 交棒 / 自动跳曲）**不经过防抖**（K1a 判据）。
     */
    private val switchMutex = Mutex()
    private val switchSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private var switchJob: Job? = null

    /** §61.303（K1c）：自动跳曲延迟窗句柄——窗内人工干预立即让位（不双切）。 */
    private var autoSkipJob: Job? = null

    /** §61.303（K1f）：同一会话连续 autoSkip 失败计数（AtomicInteger：scope 多线程）。 */
    private val consecutiveSkipFails = java.util.concurrent.atomic.AtomicInteger(0)

    /** §61.303（K1d）：通知元数据发布防抖窗句柄与待发布快照（latest-wins）。 */
    private var metadataJob: Job? = null

    @Volatile
    private var pendingMetadata: Pair<Track, String?>? = null

    /** §61.304（K1b）：resolve 三级容错装饰（重试/限流退避；总超时在 [prepareAt] 兜底）。 */
    private val resolvePolicy = ResolveLinkPolicy(resolver)

    /** §61.304（K1b）：prepare 代次——竞态指纹（迟到 resolve 响应整链丢弃）。 */
    private val prepareSeq = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * §61.304（K1e）：SHUFFLE 已播历史栈（手动 prev 回溯到真实上一首；
     * 队列收缩时剔除越界项 = 已删曲目出栈）。判定入口见 [shuffleHistorySnapshot]。
     */
    private val shuffleHistory = ArrayDeque<Int>()

    /** §61.304（K1e）：历史栈只读快照（测试判定口）。 */
    internal val shuffleHistorySnapshot: List<Int> get() = shuffleHistory.toList()

    /**
     * §61.181（B 案）：当前曲是否待真值时长回填。prepareAt 按曲库 `durationMs<=0`
     * 置位（STRM / 无标签），引擎解出真值后经 [durationWriter] 写库一次（A 案
     * `syncEngineDuration` 兑现）；切曲重置，避免上一曲未兑现的旗标串到新曲。
     */
    private val pendingDurationBackfill = java.util.concurrent.atomic.AtomicBoolean(false)

    private val controllerHandle: PlayerControllerHandle = HandleAdapter()

    init {
        media?.bind(controllerHandle)
        // §61.26（G05/G07 验收）：引擎完成（ENDED）/ 出错（ERROR）事件此前无人消费——
        // 播完不自动进下一首、解码失败不自动跳过。这里统一裁决：
        // ENDED → 按播放模式进下一首（SEQUENCE 末尾置 ENDED 态收束）；
        // ERROR → 记录 lastError 并按 autoSkipOnError 跳下一首。
        // SINGLE_LOOP 的失败重试语义保留（同曲重试）。prepareAt 的异常就地吞掉，
        // 记为该曲失败，不让未捕获异常带崩本 scope。
        scope.launch {
            engine.state().collect { s ->
                runCatching {
                    when (s) {
                        // §61.237：armed 时自然 ENDED 由 fade-out 回调独占 next，
                        // 抑制本次（双曲跳跃防护）。
                        EngineState.ENDED -> {
                            if (fadeOutArmed) fadeOutArmed = false
                            else advanceNext()
                        }
                        EngineState.ERROR -> onEngineError(
                            engine.lastError()
                                ?: CloudMusicError.DsUpstream("ENGINE", "未知引擎错误"),
                        )
                        // §61.301（H0-1）：BUFFERING 透传为 [PlayerState.isBuffering]
                        // 标志（PlayerPhase 不动）；PLAYING/PAUSED 是缓冲的收敛点，
                        // 到达即清除。节拍器据标志缓冲期停发 PLAYING 会话快照。
                        EngineState.BUFFERING -> _state.update { it.copy(isBuffering = true) }
                        EngineState.PLAYING, EngineState.PAUSED ->
                            _state.update { it.copy(isBuffering = false) }
                        else -> Unit
                    }
                }
            }
        }
        // §61.139：playlist 自动切曲 → 同步业务游标（整队列播完的 ENDED 仍由上面的状态流裁决）。
        scope.launch {
            engine.queueIndex().collect { idx ->
                // collect 的 lambda 是 suspend；用 try/catch 而非 runCatching
                // （后者 block 非 suspend，不能调 applyQueueIndex）。
                try {
                    applyQueueIndex(idx)
                } catch (e: Exception) {
                    Unit
                }
            }
        }
    }

    /** 队列全量替换：重算 SHUFFLE 顺序并单向全量下发 native（§8）。 */
    suspend fun setQueue(items: List<QueueItem>, mode: PlayMode, startAt: Int = 0) {
        queueMode = false
        queue = items.toList()
        // §61.319（R2）：CUE 标志 / gapless 邻居曲目在队列变更时一次取齐（Room IN 查询），
        // 切歌热路径不再对整条队列逐项 Room 点查（7838 首 × 每次换歌 → 0）。
        queueTracks = lookup.getMany(queue.map { it.trackId })
        shuffle = if (mode == PlayMode.SHUFFLE) ShuffleOrder.generate(config.shuffleSeed, items.size) else emptyList()
        // §61.304（K1e）：队列收缩 = 已删曲目出栈（越界历史项剔除；模式切走时同清）。
        shuffleHistory.removeAll { it !in items.indices }
        if (mode != PlayMode.SHUFFLE) shuffleHistory.clear()
        val index = if (items.isEmpty()) -1 else startAt.coerceIn(0, items.size - 1)
        _state.update {
            it.copy(mode = mode, currentIndex = index, currentTrackId = items.getOrNull(index)?.trackId)
        }
        sync.push(queue, mode, index.coerceAtLeast(0))
    }

    suspend fun playAt(index: Int) = prepareAt(index, autoPlay = true, seekTo = 0L)

    suspend fun pause() {
        autoSkipJob?.cancel() // §61.303（K1c）：暂停 = 用户接管，自动跳曲让位
        val h = handle ?: return
        // §61.25（E25 真机暴露）：句柄可能已被引擎完成路径（msg=2 → releaseAllInternal）
        // 释放，引擎对陈旧句柄显式报 DsNotFound；暂停不得因此带崩协程，会话态仍回 PAUSED。
        runCatching {
            engine.pause(h)
            val pos = engine.positionMs(h)
            val dur = syncEngineDuration(h)
            livePositionMs = pos
            _displayPositionMs.value = sessionPosition(pos)
            _state.update { it.copy(phase = PlayerPhase.PAUSED, positionMs = pos, isBuffering = false) }
            // §61.239：会话快照分轨相对化（口径同 §61.226 UI 三口）。
            media?.setPlaybackState(EngineState.PAUSED, sessionPosition(pos), sessionDuration(dur))
            // §61.182：暂停点落游标（恢复链写侧：切歌 / 暂停 / 每 5s 三口之一）。
            _state.value.currentTrackId?.let { persistCursor(it, pos) }
        }.onFailure {
            _state.update { it.copy(phase = PlayerPhase.PAUSED) }
            media?.setPlaybackState(EngineState.PAUSED, 0L, 0L)
        }
    }

    suspend fun resume() {
        // 03-02 §344：用户点播 / 焦点恢复 / pendingPlay 兑现都必须过闸门；
        // prepareAt 的 autoPlay 分支只覆盖 playAt / 自动进曲。
        if (blockedByNetworkPolicy()) return
        // §61.182（真机一手，本批判据③复验时发现）：B2 播完后 handle **不释放**
        // 且 ExoPlayer 在 STATE_ENDED 下 play() 只置 playWhenReady、不重播也不翻转
        // isPlaying → 旧路径无声空转、MediaSession/UI 假报 PLAYING（§61.25 修的是
        // 「句柄已释放」分支，本条补「句柄还在但引擎已完」同族缺口）。标准行为不变：
        // 从头重播当前曲（重建句柄走完整 prepare，playAt 内部再过网络闸门）。
        // §61.252（P0 修复）：ERROR 相位的播放指令 = 重建当前曲（与 ENDED 同语义）。
        // 此前 ERROR 相位 resume() 直落 engine.play(陈旧句柄)——Media3 在 STATE_IDLE
        // （错误后）不重prepare，用户点播放是死按钮（真机一手：冷启动时服务器不可达
        // → restore 失败落 ERROR → 点播放永远无声）。典型场景：车库里网络未恢复/
        // 直链过期后回网，点播放应当重试而不是永远沉默。
        if (_state.value.phase == PlayerPhase.ENDED || _state.value.phase == PlayerPhase.ERROR) {
            restartCurrentIfAny()
            return
        }
        // §61.25（E24/E25 真机暴露）：播放完成后句柄已随 msg=2 释放（引擎槽回 IDLE），
        // 此时再按播放不应空转导致 MediaSession 假报 PLAYING——标准行为是
        // 从头重播当前曲（重建引擎句柄走完整 prepare）。
        val h = handle ?: run {
            // §61.27：队列尚未恢复（冷启动竞态）时不能丢掉这一次播放指令。
            if (queue.isEmpty()) {
                pendingPlay.set(true)
                return
            }
            restartCurrentIfAny()
            return
        }
        runCatching { engine.play(h) }.onFailure {
            // 引擎侧已释放（完成后 / 出错后）→ 陈旧句柄，从头重播当前曲。
            restartCurrentIfAny()
            return
        }
        _state.update { it.copy(phase = PlayerPhase.PLAYING, isBuffering = false) }
        val dur = syncEngineDuration(h)
        startPositionTicker()
        // §61.239：会话快照分轨相对化。
        media?.setPlaybackState(EngineState.PLAYING, sessionPosition(engine.positionMs(h)), sessionDuration(dur))
    }

    /** §61.114：位置节拍器句柄。 */
    private var positionTicker: Job? = null

    /**
     * §61.181（A 案）：引擎真值时长同步——>0 才写 [PlayerState.durationMs]
     * （0 = 引擎未解出 / 陈旧句柄异常，UI 回退曲库元数据）；同时兑现 B 案：
     * 曲库缺时长（prepareAt 置位旗标）且已解出真值 → 经 [durationWriter] 回填一次。
     * 自身不抛（引擎异常 / 落库失败都吞掉），可在节拍器里安全逐 tick 调用。
     *
     * @return 归一后的引擎时长（>0 真值；0 未知），调用方决定是否兜底曲库元数据。
     */
    private suspend fun syncEngineDuration(h: EngineHandle): Long {
        val dur = runCatching { engine.durationMs(h) }.getOrNull()?.takeIf { it > 0 } ?: 0L
        if (dur > 0) {
            _state.update { it.copy(durationMs = dur) }
            if (pendingDurationBackfill.compareAndSet(true, false)) {
                _state.value.currentTrackId?.let { id ->
                    runCatching { durationWriter?.write(id, dur) }
                }
            }
        }
        return dur
    }

    /**
     * §61.114（真机一手：播放中进度时间冻结在 00:00）——B2 引擎没有逐帧回调，
     * [positionMs] 此前只在暂停 / seek / 恢复时更新一次，PLAYING 期间 UI 进度
     * 与时间显示不走。PLAYING 期每 500ms 轮询引擎位置；相位离开 PLAYING 自行
     * 终止。MediaSession 通知栏位置刷新仍只在转换点（挂账，非本批）。
     * §61.181：每 tick 同步引擎真值时长（B2 在 READY 异步解出，prepare 返回时
     * 尚未知；节拍器是「解出后进 state + 触发回填」的兜底采集点）。
     * §61.182：每 5s（10 tick）落播放游标（写侧复活：saveCursor 此前生产链零写方）。
     */
    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            // §61.321：起播瞬间以转换点快照对齐——首个 500ms 窗口内相对 seek
            // 不得读到上一曲的残留位置。
            livePositionMs = _state.value.positionMs
            var tick = 0
            while (isActive && _state.value.phase == PlayerPhase.PLAYING) {
                handle?.let { h ->
                    runCatching { engine.positionMs(h) }.getOrNull()?.let { pos ->
                        // §61.309（K-02 真机一手）：media3 的 currentPosition 在 BUFFERING
                        // 期间按播放时钟墙钟外推（实测 450→486→843→1347ms 持续走）——
                        // 缓冲期冻结 App 内进度显示（卡住不涨是通用播放器语义），
                        // READY 恢复后下一拍自动续走。cue 交棒与 fade 判据仍用真值 pos。
                        val buffering = _state.value.isBuffering
                        if (!buffering) {
                            // §61.321（R3）：只更新高频字段，**不**改 PlayerState ——
                            // 根 UI 收整份 state，每拍 copy 一次 = 每 500ms 全树重组。
                            livePositionMs = pos
                        }
                        // §61.224 + §61.230：分轨终点交棒——position ≥ cueEndMs 时
                        // 先渐出再进下一曲（fade 未启用时立即交棒）。引擎自然 ENDED
                        // 分支不受影响（无竞争：fade-out 完成 → next() 单一入口）。
                        // §61.309（K-02 真机一手）：BUFFERING 期 media3 位置按墙钟**无界
                        // 外推**（实测 450→486→843→1347 越涨越快），可越过 cueEndMs →
                        // 误触发终点交棒（「还在加载却跳下一首」= 用户体感快进）——
                        // 缓冲期位置非真值，交棒与渐出判据一并冻结。
                        if (!buffering && cueEndMs > 0 && pos >= cueEndMs) {
                            val hh = h
                            fadeOutThen(hh) { scope.launch { advanceNext() } }
                            return@launch
                        }
                        val dur = syncEngineDuration(h)
                        // §61.115：每 2s（4 tick）同步一次 MediaSession 位置——
                        // 此前只在转换点写会话，通知栏进度与 dumpsys 监控通道
                        // 均冻结在起播值（G08 长稳 20 轮 position=0 的一手后果）。
                        // §61.237：自然结束渐出——接近引擎真值终点（dur - fade 窗口）时
                        // 启动渐出并 armed（ENDED 事件被抑制，fade 完成回调独占 next）。
                        if (fadeEnabled && fadeDurationMs > 0 && !fadeOutArmed &&
                            dur > fadeDurationMs && pos >= dur - fadeDurationMs
                        ) {
                            fadeOutArmed = true
                            val hh = h
                            fadeOutThen(hh) {
                                fadeOutArmed = false
                                scope.launch { advanceNext() }
                            }
                            return@launch
                        }
                        // §61.384：每 500ms 把分轨相对位置推给进度条（独立流，不碰 _state）。
                        _displayPositionMs.value = sessionPosition(pos)
                        if (tick % 4 == 0 && !_state.value.isBuffering &&
                            runCatching { engine.state().first() }.getOrNull() != EngineState.BUFFERING
                        ) {
                            // §61.239：会话快照分轨相对化（§61.115 每 2s 同步链口径统一）。
                            // §61.301（H0-1）：缓冲期停发 PLAYING 快照——BUFFERING 态
                            // 由 PlaybackService 引擎直采链写 STATE_BUFFERING+speed=0f
                            // （系统会话停走），此处再按 2s 链重锚 PLAYING 会让外推
                            // 时钟反复回弹（跳变观感）。
                            media?.setPlaybackState(EngineState.PLAYING, sessionPosition(pos), sessionDuration(dur))
                        }
                        // §61.182：每 5s（10 tick）落游标（与 pause / 切歌共同保
                        // playback_state 常在，冷启动恢复才有输入）。
                        if (tick % 10 == 0) {
                            _state.value.currentTrackId?.let { persistCursor(it, pos) }
                        }
                    }
                }
                tick++
                delay(500)
            }
        }
    }

    /**
     * §61.182：游标落库（best-effort，失败吞掉不抛——与 [syncEngineDuration] 同型；
     * 落库失败只损失一次恢复点，不得带崩播放协程）。
     */
    private suspend fun persistCursor(trackId: TrackId, positionMs: Long) {
        val w = cursorWriter ?: return
        runCatching { w.write(trackId.value, positionMs) }
    }

    /** §61.93（音效必须支持）：effectId>0 启用引擎基础音效（audiofx 档位），0 = 释放。 */
    fun attachEffect(effectId: Int) {
        val h = handle ?: return
        runCatching { engine.attachAudioEffect(h, effectId) }
    }

    /** §61.118（T5-1）：分频段均衡器电平（millibel）；未挂载 audiofx 时引擎侧忽略。 */
    fun setEqualizerBands(levelsMillibel: List<Int>) {
        val h = handle ?: return
        runCatching { engine.setEqualizerBands(h, levelsMillibel) }
    }

    /** §61.25：当前队列游标有效则从头重播（resume 空转兜底）。 */
    private suspend fun restartCurrentIfAny() {
        val index = _state.value.currentIndex
        if (index in queue.indices) playAt(index)
    }

    /** §15 T09：钳制到 [0, duration]；未知时长（-1）不钳制上界。
     *  §61.224：分轨曲目钳制进 [cueStartMs, cueEndMs) 包络——包络外的目标回到本轨起点。 */
    suspend fun seek(ms: Long) {
        val h = handle ?: return
        runCatching {
            val duration = engine.durationMs(h)
            val upper = if (duration > 0) duration else Long.MAX_VALUE
            val clamped = clampToCue(ms).coerceIn(0L, upper)
            // §61.237：用户 seek 干预优先——解除 armed、取消渐出协程、复位音量。
            if (fadeOutArmed) {
                fadeOutArmed = false
                fadeJob?.cancel()
                engine.setVolume(h, 1f)
            }
            engine.seek(h, clamped)
            livePositionMs = clamped
            _displayPositionMs.value = sessionPosition(clamped)
            _state.update { it.copy(positionMs = clamped) }
        }   // 陈旧句柄（完成后）静默忽略：无可 seek 的活动流。
    }

    /** §61.224：分轨包络钳制。包络前 → 起点；包络后（含终点）→ 回本轨起点重新播；
     *  整轨（包络全 0）原样返回，零行为变化。 */
    private fun clampToCue(ms: Long): Long = when {
        cueStartMs <= 0 && cueEndMs <= 0 -> ms
        ms < cueStartMs -> cueStartMs
        cueEndMs > 0 && ms >= cueEndMs -> cueStartMs
        else -> ms
    }

    /**
     * §61.239：会话快照分轨相对化两口——口径 = [CueEnvelope]（core-model 唯一真值，
     * 与 §61.226 UI 三口同源）。整轨恒原样，零行为变化。
     */
    private fun sessionPosition(posMs: Long): Long =
        CueEnvelope.displayPosition(posMs, cueStartMs)

    private fun sessionDuration(durMs: Long): Long =
        CueEnvelope.displayDuration(durMs, cueStartMs, cueEndMs)

    /** §61.230：fade-in——起播音量 0 → 1 线性 ramp（50ms 步进，与 focus duck 的
     *  竞争边缘场景如实登记）。未启用直接跳过（volume 保持 1f）。 */
    private fun startFadeIn(h: EngineHandle) {
        if (!fadeEnabled || fadeDurationMs <= 0) return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            engine.setVolume(h, 0f)
            val steps = (fadeDurationMs / 50).coerceAtLeast(1)
            for (i in 1..steps) {
                kotlinx.coroutines.delay(50)
                engine.setVolume(h, fadeCurve(i.toFloat() / steps))
            }
            engine.setVolume(h, 1f)
        }
    }

    /** §61.230：fade-out 到静音后复位音量并执行 [onDone]（交棒下一曲）。 */
    private fun fadeOutThen(h: EngineHandle, onDone: () -> Unit) {
        if (!fadeEnabled || fadeDurationMs <= 0) {
            onDone()
            return
        }
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = (fadeDurationMs / 50).coerceAtLeast(1)
            for (i in 1..steps) {
                kotlinx.coroutines.delay(50)
                engine.setVolume(h, fadeCurve(1f - i.toFloat() / steps))
            }
            engine.setVolume(h, 1f)
            onDone()
        }
    }

    /** §61.230：音量包络纯函数——线性曲线（x∈[0,1] → 音量），单测直测。 */
    internal fun fadeCurve(x: Float): Float = x.coerceIn(0f, 1f)

    /**
     * §61.303（K1a）：手动下一曲——500ms 防抖 latest-wins + [switchMutex] 序贯执行，
     * 目标在执行时按当前游标重算（连点 3 次仅末次生效）。自动连播走 [advanceNext]。
     */
    suspend fun next() {
        val my = switchSeq.incrementAndGet()
        yieldAutoSkipToUser()
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(KernelTuning.MANUAL_SWITCH_DEBOUNCE_MS)
            if (switchSeq.get() == my) switchMutex.withLock { nextInternal(manual = true) }
        }
    }

    /** §61.303（K1a）：手动上一曲——同 [next] 防抖口径。 */
    suspend fun prev() {
        val my = switchSeq.incrementAndGet()
        yieldAutoSkipToUser()
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(KernelTuning.MANUAL_SWITCH_DEBOUNCE_MS)
            if (switchSeq.get() == my) switchMutex.withLock { prevInternal() }
        }
    }

    /**
     * §61.303（K1a）：手动切歌防抖入口——500ms 窗 latest-wins（连点 3 次仅末次生效），
     * 执行经 [switchMutex] 序贯无交错 prepare。UI/媒体键的手动点播走这里；
     * 内部自动路径（restore / restartCurrentIfAny / 自动连播）走 [playAt] 直执行，
     * **不受防抖抑制**（§61.299 K1a 判据）。
     */
    suspend fun playAtDebounced(index: Int) {
        val my = switchSeq.incrementAndGet()
        yieldAutoSkipToUser()
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(KernelTuning.MANUAL_SWITCH_DEBOUNCE_MS)
            if (switchSeq.get() == my) {
                switchMutex.withLock { prepareAt(index, autoPlay = true, seekTo = 0L) }
            }
        }
    }

    /** §61.303（K1a）：自动连播唯一入口——ENDED/交棒/自动跳曲，立即执行不过防抖。 */
    private suspend fun advanceNext() {
        switchMutex.withLock { nextInternal(manual = false) }
    }

    private suspend fun nextInternal(manual: Boolean = false) {
        val s = _state.value
        // §61.304（K1e）：SHUFFLE 已播历史栈——每次前进前压栈（手动/自动都回溯得起来）。
        if (s.mode == PlayMode.SHUFFLE && s.currentIndex in queue.indices) {
            shuffleHistory.addLast(s.currentIndex)
        }
        // §61.304（K1e）：手动切歌强制列表循环语义前进（lx 口径）——SINGLE_LOOP 手动
        // next 不原地循环而是按 LIST_LOOP 前进；自动 ENDED 仍原地循环（§8.2 语义不变）。
        val effectiveMode = if (manual && s.mode == PlayMode.SINGLE_LOOP) PlayMode.LIST_LOOP else s.mode
        val target = PlayModeOrdering.nextIndex(s.currentIndex, queue.size, effectiveMode, shuffle)
        if (target == null) {
            _state.update { it.copy(phase = PlayerPhase.ENDED) }
            return
        }
        // §61.139：队列模式用原生 playlist 跳播，不 release / 重建 player（保持 gapless）。
        val h = handle
        if (queueMode && h != null) {
            engine.seekToQueueIndex(h, target)
            applyQueueIndex(target)
            // 单曲路径由 playAt 置 PREPARING→PLAYING 并清 lastError；队列跳播
            // 不重建 handle，这里显式同步，保证 ERROR 自动跳曲后相位一致。
            _state.update { it.copy(phase = PlayerPhase.PLAYING, lastError = null) }
            return
        }
        playAt(target)
    }

    private suspend fun prevInternal() {
        val s = _state.value
        // §61.304（K1e）：手动 prev 优先历史栈回溯（SHUFFLE 回退到真实上一首）；
        // 栈空 / 全为当前曲时回退线性 prevIndex（§8.2 语义保持）。
        val target: Int = if (s.mode == PlayMode.SHUFFLE) {
            var fromHistory: Int? = null
            while (shuffleHistory.isNotEmpty()) {
                val last = shuffleHistory.removeLast()
                if (last in queue.indices && last != s.currentIndex) {
                    fromHistory = last
                    break
                }
            }
            fromHistory ?: PlayModeOrdering.prevIndex(s.currentIndex, queue.size) ?: return
        } else {
            PlayModeOrdering.prevIndex(s.currentIndex, queue.size) ?: return
        }
        val h = handle
        if (queueMode && h != null) {
            engine.seekToQueueIndex(h, target)
            applyQueueIndex(target)
            _state.update { it.copy(phase = PlayerPhase.PLAYING, lastError = null) }
            return
        }
        playAt(target)
    }

    /** §61.303（K1c）：人工干预让位——取消待执行的自动跳曲并清连续失败计数。 */
    private fun yieldAutoSkipToUser() {
        autoSkipJob?.cancel()
        autoSkipJob = null
        consecutiveSkipFails.set(0)
    }

    /**
     * §61.303（K1c+K1f）：自动跳曲 5s 延迟窗 + 连续失败护栏（lx delayNextTimeout 对齐）。
     * 窗内人工干预（next/prev/playAtDebounced/pause）立即让位不双切；同一会话连续
     * ≥[KernelTuning.CONSECUTIVE_SKIP_FAIL_LIMIT] 首 autoSkip 失败 → 停止自动跳曲，
     * 人话化提示置 lastError（V0.4-02 口径），停在半途待用户裁决；成功起播清零。
     */
    private fun scheduleAutoSkip() {
        val fails = consecutiveSkipFails.incrementAndGet()
        if (fails >= KernelTuning.CONSECUTIVE_SKIP_FAIL_LIMIT) {
            autoSkipJob?.cancel()
            _state.update {
                it.copy(
                    lastError = CloudMusicError.SkipGuard(
                        "连续 $fails 首播放失败，已暂停自动跳曲——点播放重试或切换音源",
                    ),
                )
            }
            return
        }
        autoSkipJob?.cancel()
        autoSkipJob = scope.launch {
            delay(KernelTuning.AUTO_SKIP_DELAY_MS)
            advanceNext()
        }
    }

    /**
     * §61.303（K1d）：通知元数据发布防抖（lx debounceUpdateMetaTools 对齐）——
     * 500ms 窗 latest-wins：快速切歌只发布最后一曲元数据（治通知栏歌名错乱）。
     * duration 同源链（§61.185/186/239）原样透传不回退。
     */
    private fun publishMetadataDebounced(track: Track, coverRef: String?) {
        pendingMetadata = track to coverRef
        metadataJob?.cancel()
        metadataJob = scope.launch {
            delay(KernelTuning.METADATA_DEBOUNCE_MS)
            pendingMetadata?.let { (t, c) -> media?.publishMetadata(t, c) }
            pendingMetadata = null
        }
    }

    /** §9：记录失败 → 延迟自动跳下一首（可关，§61.303 K1c 5s 窗）→ UI 展示具体原因。 */
    suspend fun onEngineError(error: CloudMusicError) {
        _state.update { it.copy(phase = PlayerPhase.ERROR, lastError = error) }
        if (config.autoSkipOnError) scheduleAutoSkip()
    }

    /** §9：切 DLNA → 本机内核静音 + 暂停，手机转遥控器；回落恢复。 */
    fun onOutputChanged(dlna: Boolean) {
        if (dlna) {
            handle?.let { engine.setVolume(it, 0f) }
            _state.update { it.copy(phase = PlayerPhase.PAUSED, remoteOutput = true) }
            media?.setEnabled(false)
        } else {
            handle?.let { engine.setVolume(it, 1f) }
            _state.update { it.copy(remoteOutput = false) }
            media?.setEnabled(true)
        }
    }

    /**
     * §8.2 恢复顺序 / §15 T11：Room.restore() → 重建队列 → push → prepare → seek →
     * **保持 PAUSED**，等用户点播放（PRD §6.1 不自动开播）。
     */
    suspend fun restore(snapshot: PlaybackSnapshot, items: List<QueueItem>) {
        val index = items.indexOfFirst { it.trackId.value == snapshot.currentTrackId }
            .let { if (it < 0) 0 else it }
        setQueue(items, snapshot.playMode, index)
        if (items.isEmpty()) {
            _state.update { it.copy(phase = PlayerPhase.IDLE, positionMs = snapshot.positionMs) }
            return
        }
        prepareAt(index, autoPlay = false, seekTo = snapshot.positionMs)
        // §61.252（P0 修复）：prepareAt 的源级失败（fail→ERROR）此前被本行无条件
        // 覆盖成 PAUSED —— 恢复失败从来都被静默显示为「暂停」，用户无从知晓。
        // ERROR 态保持原样（顶栏显示错误，PRD §6.1 不自动开播语义不变）。
        if (_state.value.phase != PlayerPhase.ERROR) {
            _state.update { it.copy(phase = PlayerPhase.PAUSED, positionMs = snapshot.positionMs) }
        }
        // §61.27：兑现冷启动期间到达的播放意图。PRD §6.1「不自动开播」约束的是
        // 无用户指令的 restore；只有 ACTION_PLAY / 媒体键才会置位 pendingPlay。
        // prepareAt 已建立 handle，resume() 经门面实现下发 play（B2 = Media3EngineAdapter，
        // `12 §61.65~61.66`；`prepareGate` 是 A 路线 EngineAdapter 的内部闸门，非契约概念）。
        if (pendingPlay.compareAndSet(true, false)) resume()
    }

    /** 供 `IMediaControl.bind` 使用的非 suspend 适配（03-07 §3）。 */
    fun asHandle(): PlayerControllerHandle = controllerHandle

    /** 03-02 §344：当前曲目是否网络音源（未 prepare 时按本地处理，不误拦）。 */
    fun currentSourceIsNetwork(): Boolean = currentNetworkSource

    /** @return true = 已落 ERROR 并中止本次播放动作。 */
    private fun blockedByNetworkPolicy(): Boolean {
        val reason = networkGate.rejectReason(currentNetworkSource) ?: return false
        fail(CloudMusicError.DsUpstream(NetworkPlaybackPolicy.ERROR_CODE, reason))
        return true
    }

    private suspend fun prepareAt(index: Int, autoPlay: Boolean, seekTo: Long) {
        if (queue.isEmpty() || index !in queue.indices) {
            _state.update { it.copy(phase = PlayerPhase.ENDED) }
            return
        }
        val item = queue[index]
        _state.update {
            it.copy(
                phase = PlayerPhase.PREPARING, currentIndex = index,
                currentTrackId = item.trackId, positionMs = seekTo, lastError = null,
                // §61.181：切曲即清零，上一曲真值不残留（验收判据②）；新曲真值由
                // prepare 返回值 / 节拍器重新写入。
                durationMs = 0L,
            )
        }
        val track = lookup.get(item.trackId)
        if (track == null) {
            failAndMaybeSkip(CloudMusicError.DsNotFound(item.trackId.value), autoPlay)
            return
        }
        // §61.224：分轨包络随曲更新（整轨恒 0，零行为变化）。
        cueStartMs = track.cueStartMs
        cueEndMs = track.cueEndMs
        // §61.181（B 案）：曲库缺时长（STRM / 无标签）→ 等引擎解出真值后回填一次。
        pendingDurationBackfill.set(track.durationMs <= 0)
        // §61.304（K1b）：resolve 三级容错（重试 1 次/限流退避）+ 100s 总超时 +
        // prepare 代次竞态指纹——迟到响应整链丢弃，不覆盖新曲。
        val myPrepare = prepareSeq.incrementAndGet()
        val link = try {
            kotlinx.coroutines.withTimeoutOrNull(KernelTuning.LINK_TOTAL_TIMEOUT_MS) {
                resolvePolicy.resolve(track)
            } ?: throw CloudMusicError.LinkTimeout(
                "音源解析超时（${KernelTuning.LINK_TOTAL_TIMEOUT_MS / 1000}s），请检查音源可达性",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (prepareSeq.get() != myPrepare) {
                logPort.debug("CM-Player", "prepareAt: 迟到响应整链丢弃 gen=$myPrepare track=${item.trackId.value}")
                return
            }
            // §61.252（P0 修复）：源级失败（驱动缺失/条目不可读）与引擎错误同语义 ——
            // autoPlay 且 autoSkipOnError 时跳下一曲。此前静默停在坏轨（跳链死亡），
            // 用户看到「整条队列播不了」（真机一手：孤儿 SFTP 轨卡死恢复链）。
            failAndMaybeSkip(
                if (e is CloudMusicError) e else CloudMusicError.DsUpstream("RESOLVE", e.message ?: "解析失败"),
                autoPlay,
            )
            return
        }
        if (prepareSeq.get() != myPrepare) {
            logPort.debug("CM-Player", "prepareAt: 迟到 resolve 丢弃 gen=$myPrepare track=${item.trackId.value}")
            return
        }
        currentNetworkSource = link.kind != Link.Kind.LOCAL
        // 03-02 §344 / 01 §6.1：播放前检查「默认仅 WiFi 播放」。
        // restore(autoPlay=false) 只 prepare + seek 不播，不得被闸门拦
        // （PRD §6.1 不自动开播；03-11 §6 B05 飞行模式下本地/曲库完全可用）。
        // 被拒时落 ERROR + 明确原因，不 prepare、不释放现有 handle，也不自动跳曲
        // （这是用户策略拒绝而非音源故障，避免把整条队列逐首标失败）。
        if (autoPlay && blockedByNetworkPolicy()) return
        val source = try {
            engineSourceOf(track, link)
        } catch (e: IllegalArgumentException) {
            failAndMaybeSkip(CloudMusicError.DsInvalidConfig(e.message ?: "音源形态非法"), autoPlay)
            return
        }

        // §61.139（施工单步骤 2）：队列 ≥2、顺序播放、立即起播、整队列均为本地音源
        // 时走 B2 ExoPlayer 原生 playlist → 同格式相邻曲目无缝衔接。其余一律保持
        // 单曲 prepare 路径（零回归）：SHUFFLE / SINGLE_LOOP / LIST_LOOP 的业务顺序
        // 由 PlayModeOrdering 决定，线性 playlist 无法表达；restore（autoPlay=false /
        // seekTo>0）须保持 PAUSED + 位置恢复；含网络音源须逐曲过「默认仅 WiFi」闸门，
        // 不得被队列绕过。
        // §61.224：队列含 CUE 分轨时禁用 gapless——同一音频文件的 N 个分轨在原生
        // playlist 里是同一 source，位置无法按轨截断，整队退回单曲 prepare 路径。
        // §61.319（R2）：标志来自 setQueue 的批量预取，切歌热路径零 Room 点查。
        val hasCueTracks = queueTracks.any { it?.let { t -> t.cueStartMs > 0 || t.cueEndMs > 0 } == true }
        val gaplessEligible = autoPlay && seekTo == 0L && !hasCueTracks && queue.size >= 2 &&
            _state.value.mode == PlayMode.SEQUENCE &&
            source.url == null && source.localPath != null
        val queuedSources = if (gaplessEligible) buildQueueSources(index, source) else null

        handle?.let { engine.release(it) }
        val engineConfig = EngineConfig(
            autoSkipOnError = config.autoSkipOnError,
            // §61.120（T5-2 前置）：曲库分析增益兜底（容器标签缺失时生效）。
            trackGainDb = track.replayGainDb,
        )
        logPort.debug("CM-Player", "prepareAt: index=$index track=${track.title} src=${source.url ?: source.localPath} gapless=${queuedSources != null}")
        if (queuedSources != null) {
            handle = engine.prepareQueue(queuedSources, index, engineConfig)
            queueMode = true
            preparedSources = queuedSources
        } else {
            handle = engine.prepare(source, engineConfig)
            queueMode = false
            preparedSources = emptyList()
        }
        val h = requireNotNull(handle)
        // §61.224：分轨起始定位——新起播（seekTo=0）落到 cueStartMs；恢复位置钳制进
        // 包络（包络外回本轨起点）。整轨恒 0 零变化。
        val effectiveSeek = if (seekTo > 0) clampToCue(seekTo) else cueStartMs
        if (effectiveSeek > 0) engine.seek(h, effectiveSeek)
        // 冷启动恢复只 prepare + seek，引擎必须停在 PAUSED（§8.2 / PRD §6.1 不自动开播）。
        if (autoPlay) {
            if (fadeEnabled && fadeDurationMs > 0) engine.setVolume(h, 0f)   // §61.230 fade-in 起点
            engine.play(h)
            if (fadeEnabled && fadeDurationMs > 0) startFadeIn(h)
        } else engine.pause(h)
        // §61.181（A 案）：prepare 返回即读引擎真值时长（已解出则进 state 并兑现回填；
        // B2 真机上 STRM 直链此刻多半还未 READY，0 = 未知，由节拍器解出后续采）。
        val engineDur = syncEngineDuration(h)
        _state.update {
            it.copy(
                phase = if (autoPlay) PlayerPhase.PLAYING else PlayerPhase.PAUSED,
                // §61.224：显示位置用包络修正后的定位（分轨起点 / 钳制值），非原始 seekTo。
                positionMs = effectiveSeek,
                // §61.301：换曲清缓冲标志，防上一曲残留。
                isBuffering = false,
            )
        }
        consecutiveSkipFails.set(0) // §61.303（K1f）：成功起播清连续失败计数
        // §61.182：切歌即落游标（恢复链写侧三口之一；restore 的 PAUSED 路径同样落，
        // 重启后 Room 才有 playback_state 可读）。
        persistCursor(item.trackId, effectiveSeek)
        if (autoPlay) startPositionTicker()
        // §61.185：媒体会话元数据时长同源真值——publishMetadata 直接用曲库值，STRM 恒 0
        // ⇒ 通知栏进度条比例错（播放页/PlaybackState 已由 §61.181 修，本处补最后一环）。
        // 引擎未解出（0）时回退曲库值；零契约变更（Track 副本，门面签名不动）。
        // §61.239：分轨曲目元数据时长用包络长（与快照/播放页口径统一）。
        val resolvedDur = if (engineDur > 0) engineDur else track.durationMs
        publishMetadataDebounced(
            track.copy(durationMs = CueEnvelope.displayDuration(resolvedDur, cueStartMs, cueEndMs)),
            track.coverRef,
        )
        // §61.309（K-02 真机一手，确定性口径）：**网络源 autoPlay 不发乐观 PLAYING**——
        // 网络起播必经 BUFFERING（直写链已发 STATE_BUFFERING+speed=0f），乐观发布在
        // 真机上 43ms 内覆盖它 → 系统会话从锚按 1f 墙钟外推整个缓冲窗（Avrcp 逐条
        // 证据）。play() 走主线程异步、prepareAt 在 Default 继续，「读状态再决定」存在
        // 结构性竞态（首轮 engineNow=PAUSED 门禁被穿透的一手教训）→ 直接按音源类型
        // 静态判定：网络源不发（BUFFERING 直写 + READY 回正 + 节拍器接管），本地源
        // 秒 READY 照发零变化。恢复（autoPlay=false）照发 PAUSED。
        if (!autoPlay || !currentNetworkSource) {
            media?.setPlaybackState(
                if (autoPlay) EngineState.PLAYING else EngineState.PAUSED,
                // §61.239：分轨新起播显示位置 = effectiveSeek - cueStart（整轨原样）。
                sessionPosition(effectiveSeek),
                // §61.181：引擎真值优先，未解出（0）时兜底曲库元数据（本地有标签曲目瞬时正确）。
                // §61.239：分轨显示时长 = 包络长。
                sessionDuration(resolvedDur),
            )
        }
    }

    /**
     * §61.139：把整条业务队列解析为与 queue 下标一一对应的 EngineSource。
     * 起播曲沿用已解析结果；任一后续曲目解析失败或为网络音源即返回 null
     * → 退回单曲路径（不把整队列标失败，也不伪造音源）。
     */
    private suspend fun buildQueueSources(
        index: Int,
        first: EngineSource,
    ): List<EngineSource>? = try {
        // 显式 for 循环：lookup / resolver 是 suspend，不能放进非 suspend 的 mapIndexed lambda。
        val out = ArrayList<EngineSource>(queue.size)
        for (i in queue.indices) {
            if (i == index) {
                out.add(first)
                continue
            }
            // §61.319（R2）：从 setQueue 的批量快照取，不再逐项 Room 点查。
            val t = queueTracks.getOrNull(i) ?: throw IllegalStateException("曲目不存在")
            val s = engineSourceOf(t, resolver.resolve(t))
            // 网络 / 代理音源必须逐曲过闸门，队列模式不接管。
            if (s.url != null) throw IllegalStateException("队列含网络音源")
            out.add(s)
        }
        out
    } catch (e: Exception) {
        null
    }

    /** §61.139：playlist 自动切曲 / 手动跳播后同步业务游标与媒体会话元数据。 */
    private suspend fun applyQueueIndex(index: Int) {
        if (!queueMode || index !in queue.indices) return
        if (_state.value.currentIndex == index) return
        val item = queue[index]
        _state.update {
            it.copy(
                currentIndex = index, currentTrackId = item.trackId,
                positionMs = 0L,
                // §61.181：队列内切曲同样清零，真值由节拍器随 READY 采集（队列模式
                // 仅本地音源，曲库元数据兜底恒可靠）。
                durationMs = 0L,
            )
        }
        currentNetworkSource = preparedSources.getOrNull(index)?.url != null
        val track = lookup.get(item.trackId) ?: return
        publishMetadataDebounced(track, track.coverRef)
        media?.setPlaybackState(EngineState.PLAYING, 0L, track.durationMs)
    }

    /** C-05：四态 → EngineSource 的唯一映射（16 §3.2 决策表）。 */
    private fun engineSourceOf(track: Track, link: Link): EngineSource {
        val loopbackOrDirect = link.url.takeIf { it.isNotBlank() }
        val url = when (link.kind) {
            Link.Kind.URL -> link.url
            Link.Kind.PROXIED, Link.Kind.TEXT -> requireNotNull(loopbackOrDirect) {
                "PROXIED/TEXT 本机播放必须由组合层 proxy.publish 提供回环 URL（16 §3.2）"
            }
            Link.Kind.LOCAL -> null
        }
        val localPath = if (link.kind == Link.Kind.LOCAL) link.localPath else null
        return EngineSource(
            trackId = track.id.value,
            mimeHint = mimeHintOf(link),
            expectedLength = track.durationMs,
            url = url,
            localPath = localPath,
            // §61.318（R1）：物理锚由 Track.sourceRef 派生；CUE 分轨共享父音频 path，
            // 因此同一整轨的 N 个分轨共用一个 cache key（切轨秒级命中）。
            cacheAnchor = MediaCacheKey.of(track),
        )
    }

    /** 12 §61.45 / F1：MIME 推断统一委托 AudioFormats，本处不再维护 when 分支。 */
    private fun mimeHintOf(link: Link): String =
        com.ccmsc.cloudmusic.model.AudioFormats.mimeOfName(link.localPath ?: link.url)

    private fun fail(error: CloudMusicError) {
        logPort.debug("CM-Player", "fail: $error")
        _state.update { it.copy(phase = PlayerPhase.ERROR, lastError = error) }
    }

    /** §61.252：fail + 源级自动跳曲（autoPlay 且 autoSkipOnError；restore 不跳——PRD §6.1）。 */
    private suspend fun failAndMaybeSkip(error: CloudMusicError, autoPlay: Boolean) {
        fail(error)
        // §61.253：驱动缺失/条目不存在 = 源不可达 → 曲库如实标记（不再谎报 OK）。
        if (error is CloudMusicError.DsUnreachable || error is CloudMusicError.DsNotFound) {
            _state.value.currentTrackId?.let { id ->
                runCatching { availabilityWriter?.mark(id, false) }
            }
        }
        if (autoPlay && config.autoSkipOnError) scheduleAutoSkip()
    }

    private inner class HandleAdapter : PlayerControllerHandle {
        override fun play() { scope.launch { resume() } }
        override fun pause() { scope.launch { pause() } }
        override fun next() { scope.launch { next() } }
        override fun prev() { scope.launch { prev() } }
        override fun seekRelative(deltaMs: Long) {
            scope.launch {
                // §61.321（R3）：PLAYING 期 state.positionMs 不再随拍刷新 —— 相对 seek
                // 以节拍器采集的真值 [livePositionMs] 为基准；非 PLAYING 期 state 的
                // 转换点值本身就是准的（pause / seek 都会写）。
                val base = if (_state.value.phase == PlayerPhase.PLAYING) {
                    livePositionMs
                } else {
                    _state.value.positionMs
                }
                seek(base + deltaMs)
            }
        }

        override fun pauseFromFocus() { scope.launch { pause() } }
        override fun resumeFromFocus() { scope.launch { resume() } }
        override fun duck(volume: Float) { handle?.let { engine.setVolume(it, volume) } }
        override fun unduck() { handle?.let { engine.setVolume(it, 1f) } }
    }
}
