package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.common.PathNormalizer
import com.ccmsc.cloudmusic.datasource.Driver
import com.ccmsc.cloudmusic.datasource.DriverRegistry
import com.ccmsc.cloudmusic.datasource.readTextOrThrow
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.playlist.PlaylistSource
import com.ccmsc.cloudmusic.playlist.expandPlaylist
import com.ccmsc.cloudmusic.playlist.playlistDirectUrl
import com.ccmsc.cloudmusic.playlist.playlistRejection
import com.ccmsc.cloudmusic.strm.StrmParseResult
import com.ccmsc.cloudmusic.strm.parseStrm
import com.ccmsc.cloudmusic.strm.strmTitleHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * docs/03-04 §4 / §5 / §10 · 扫描入口。
 *
 * 阶段 A：列目录 + 内联展开 + 文件名兜底骨架 → 批量 upsert（不等网络元数据）
 * 阶段 B：低优先级补元数据，逐条 upsert
 * 所有 Android / Room 依赖经 [StorageProvider] / [TrackSink] / [ScanSnapshotStore] 注入。
 */
class ScanCoordinator(
    private val storageProvider: StorageProvider,
    private val driverRegistry: DriverRegistry,
    private val trackSink: TrackSink,
    private val snapshotStore: ScanSnapshotStore,
    private val tagReader: TagReader = EmptyTagReader,
    private val rules: ScanRules = ScanRules(),
    /**
     * §61.116（M6）：每扫描一次读取一次的最短时长下限（ms）——
     * `AppSettings.scan.minDurationMs` 此前只持久化、从未接线（死旋钮）。
     * null = 用 [ScanRules.minDurationMs] 默认 10s（C-09 settingsSource 同型）。
     */
    private val minDurationMsSource: (() -> Long)? = null,
    /** §61.117（M2-2）：每扫描读取的忽略目录集合——AppSettings.scan.ignoreDirs 接线。 */
    private val ignoreDirsSource: (() -> Set<String>)? = null,
    /** §61.254：m3u 展开条目 → 歌单页命名实体（null = 不导入，测试/默认零影响）。 */
    private val playlistImportSink: com.ccmsc.cloudmusic.scanner.PlaylistImportSink? = null,
    private val trackFactory: TrackFactory = TrackFactory(),
    private val rootPathFor: (Storage) -> String = { "" },
    /** §61.205（A1-⑤）：扫描节奏（wake lock + 熄屏块间让步）；默认 NoOp 零影响。 */
    private val pacer: ScanPacer = ScanPacerNoOp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 文档签名：返回进度 Flow。 */
    fun scan(request: ScanRequest): Flow<ScanProgress> = callbackFlow {
        val key = request.storageIds.sorted().joinToString(",").ifEmpty { "*" }
        val job = scope.launch {
            try {
                run(request) { trySend(it) }
            } finally {
                close()
            }
        }
        jobs[key] = job
        awaitClose {
            job.cancel()
            jobs.remove(key)
        }
    }

    fun cancel(storageId: String? = null) {
        if (storageId == null) {
            jobs.values.forEach { it.cancel() }
        } else {
            jobs.entries.filter { it.key == "*" || storageId in it.key.split(',') }
                .forEach { it.value.cancel() }
        }
    }

    /**
     * §61.117：每目录遍历读取的忽略目录集合（设置投影优先，缺省 ScanRules 默认）；
     * 统一小写比对——目录名大小写在 Windows 侧存储常见不一致。
     */
    private fun effectiveIgnoreDirs(): Set<String> =
        (ignoreDirsSource?.invoke() ?: rules.ignoreDirs).map { it.lowercase() }.toSet()

    /** 可测的同步入口：取消按 E_SCAN_CANCELLED 正常结束，返回已完成部分的报告。 */
    suspend fun run(
        request: ScanRequest,
        emit: (ScanProgress) -> Unit = {},
    ): ScanReport {
        val state = ScanState()
        pacer.beginScan()
        try {
            return try {
                val storages = storageProvider.active()
                    .filter { request.storageIds.isEmpty() || it.id in request.storageIds }
                val semaphore = Semaphore(request.storageConcurrency.coerceAtLeast(1))
                // §61.323（B-2）：metadata 预算提到 run() 级**全源共享**。
                // 原实现在 runMetadata 内部各自 new Semaphore(8) ⇒ 4 源并存时
                // 上限是 4×8=32 个 tag 读 + WebP 编解码同时抢核（架构审计 P1-7）。
                // 全局限流后单源吞吐可能略降，但总 CPU 竞争收敛、总时长更可控。
                val metadataBudget = Semaphore(request.metadataConcurrency.coerceAtLeast(1))
                coroutineScope {
                    storages.map { storage ->
                        async {
                            semaphore.withPermit {
                                currentCoroutineContext().ensureActive()
                                scanStorage(storage, request, state, emit, metadataBudget)
                            }
                        }
                    }.awaitAll()
                }
                // §61.255：孤儿源 sweep——曲库里存在、但已不在 active 源清单的轨道
                // 标 UNREACHABLE（DataStore 手术/历史残留；PRD §4 灰显不删除）。
                // 口径：按**全量 active**（非定向过滤后）判定，定向重扫不误标其它源。
                runCatching {
                    // §61.345（G-4）：按 configured()（配置全集）判定孤儿，而非 active()
                    // （已剔除 init 失败者）——后者会把「SAF 授权过期」误判成「源被删除」，
                    // 令本地音源名下轨道整片变灰。
                    val allActive = storageProvider.configured().map { it.id }.toSet()
                    val orphans = trackSink.trackedStorageIds().filter { it !in allActive }
                    for (sid in orphans) {
                        val ids = trackSink.trackIdsByStorage(sid)
                        if (ids.isNotEmpty()) {
                            trackSink.markUnavailable(ids)
                            state.error("", "E_SCAN_ORPHAN_SOURCE", "数据源已移除，${ids.size} 条轨道标记暂不可用: $sid")
                        }
                    }
                }
                state.report(cancelled = false, stageAFinished = true)
            } catch (e: CancellationException) {
                emit(state.progress("", request, cancelled = true))
                state.report(cancelled = true, stageAFinished = state.stageA.get() > 0)
            }
        } finally {
            pacer.endScan()
        }
    }

    // ------------------------------------------------------------------
    private suspend fun scanStorage(
        storage: Storage,
        request: ScanRequest,
        state: ScanState,
        emit: (ScanProgress) -> Unit,
        /** §61.323（B-2）：run() 级**全源共享**的 metadata 并发预算（原为每源各一份）。 */
        metadataBudget: Semaphore,
    ) {
        val driver = driverRegistry.get(storage.id)
        if (driver == null) {
            state.error("", "E_SCAN_STORAGE_UNREACHABLE", "驱动未注册: ${storage.id}")
            return
        }
        // §61.178（真机一手）：扫描卡在「正在扫描曲库 0/0」无法归因——
        // 分步打点，定位到底停在快照读取还是目录走查。:scanner 是纯 JVM 模块，
        // 无 android.util.Log；Android 上 System.out 会进 logcat（tag=System.out）。
        fun trace(msg: String) {
            println("CM-Scan [${storage.id}] $msg")
        }
        trace("scanStorage 入口 driver=${driver.javaClass.simpleName}")
        val previous = snapshotStore.load(storage.id)
        trace("快照已载入 previous=${previous.size}")
        val candidates = mutableListOf<Candidate>()
        // §61.254：walk 收集的 m3u 导入引用（expandPlaylistInline 填充；metadata 后统一导入）。
        val playlistImports = mutableListOf<com.ccmsc.cloudmusic.scanner.PlaylistImport>()
        try {
            trace("walk 开始 path='${storage.mountPath}' root='${rootPathFor(storage)}'")
            walk(
                driver, storage, Obj(name = "", isDir = true, path = ""), request, state, candidates,
                0, previous, playlistImports,
            )
            trace("walk 完成 candidates=${candidates.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace("walk 抛异常 ${e::class.java.name}: ${e.message}")
            state.error("", "E_SCAN_STORAGE_UNREACHABLE", e.message ?: "目录走查失败")
            // §61.153：walk 异常可见化——错误此前在首个 emit 前发生，runScan 的
            // lastProgress.errors 恒空（WebDAV/STRM 0 文件无法归因的真机一手）。
            emit(state.progress(storage.id, request))
            return   // B07：单个 Storage 失败不影响其它 Storage
        }

        // C-14（`12 §31`）：定向增量只重扫当前 Availability != OK 的条目。
        // 没有失败项时整轮跳过并保留快照——否则 seen 为空会把全部历史条目标成 UNREACHABLE。
        val targetPaths = if (request.onlyUnavailable) {
            runCatching { trackSink.unavailablePaths(storage.id) }.getOrDefault(emptySet())
        } else null
        val scoped = when {
            targetPaths == null -> candidates
            targetPaths.isEmpty() -> {
                state.addSkipped(candidates.size)
                return
            }
            else -> candidates.filter { it.obj.path in targetPaths }
        }

        // §61.221：seen 按快照键（分轨 = 锚点 idPath，其余 = obj.path）——cue 移除后
        // 其分轨锚点不再产生 → missing 语义自动把分轨标 UNREACHABLE（禁止删除口径不变）。
        val seen = scoped.map { it.effectiveIdPath() }.toSet()
        // §61.179：快照与曲库不一致时强制重扫。删除数据源只 markOrphan（置
        // UNREACHABLE）而不清快照，重新加回同源时 size/mtime 全未变 ⇒ 旧判据
        // 认为「已扫过」全部 skip ⇒ 曲目永久 UNREACHABLE（播放不了的静默成因之一）。
        // §61.204：knownPaths 改为恒计算（refresh 也取）——stat 命中跳过要求
        // 「曲库行存在且可用」，否则 C-04 恢复 / 重加源场景会被 statHit 跳过而
        // 无法置回 OK（判据开销 = 一次全表投影，量级 ~百 ms）。
        val knownPaths = runCatching { trackSink.knownPaths(storage.id) }.getOrNull()
        // §61.204（A1-③）：refresh=true 的 stat 命中跳过——(path,size,mtime) 未变 +
        // 元数据已完成（metadataDone）+ 曲库行可用 ⇒ 阶段 A 重 upsert 与阶段 B 重读
        // 全跳。标签内嵌于文件本体，stat 未变 ⇒ 标签不可能变，重读是纯浪费
        // （§61.160 一手：本地 7514 文件 refresh 重扫 ~87s 的大头）。
        // refresh 语义从「全部重读」收敛为「重新走查 + 只重处理有变 / 元数据未完成 /
        // 行不可用者」；普通增量扫描语义不变（T11 口径保持）。
        // §61.221：stat 匹配取 statSize/statModified（分轨 = cue 文件 stat；cue 变化即使
        // 音频 stat 未变也触发重展开）；快照键查 effectiveIdPath（分轨 = 锚点）。
        val statHit = if (request.refresh) scoped.filter { c ->
            val old = previous[c.effectiveIdPath()]
            old != null && old.matches(c.statSize(), c.statModified()) && old.metadataDone &&
                (knownPaths == null || c.obj.path in knownPaths)
        }.map { it.effectiveIdPath() }.toSet() else emptySet()
        val changed = scoped.filter { c ->
            val old = previous[c.effectiveIdPath()]
            // 定向增量必须强制重处理，否则文件未变时失败项永远不会被重新 upsert 置回 OK。
            c.effectiveIdPath() !in statHit && (
                request.refresh || request.onlyUnavailable || old == null ||
                    !old.matches(c.statSize(), c.statModified()) ||
                    (knownPaths != null && c.obj.path !in knownPaths)
                )
        }
        state.addSkipped(scoped.size - changed.size)
        val minDurationMs = minDurationMsSource?.invoke() ?: rules.minDurationMs
        val accepted = changed.filter { candidate ->
            val dur = candidate.hint?.durationMs
            !(dur != null && dur > 0 && dur < minDurationMs)
        }
        state.addScanned(scoped.size)

        // 阶段 A：批量 upsert 骨架（禁止逐条写库）
        println("CM-Scan [${storage.id}] 阶段A 准备 upsert: accepted=${accepted.size} changed=${changed.size} skipped=${scoped.size - changed.size}")
        val tracks = accepted.map { trackFactory.build(it) }
        tracks.chunked(request.batchSize.coerceAtLeast(1)).forEach { trackSink.upsertBatch(it) }
        println("CM-Scan [${storage.id}] 阶段A 完成 upsert=${tracks.size}")
        state.addUpserted(tracks.size)
        state.stageA.incrementAndGet()
        emit(state.progress(storage.id, request, stageAFinished = true))

        // 快照有、本次没扫到 → 标 UNREACHABLE，禁止删除（§8 / §15 #3）。
        // C-14：定向增量只处理子集，绝不能据 seen 推断其它条目缺失。
        val missing = if (request.onlyUnavailable) emptyList()
        else previous.keys.filter { it !in seen }.map { TrackId.of(storage.id, it) }
        if (missing.isNotEmpty()) {
            trackSink.markUnavailable(missing)
            state.addUnavailable(missing.size)
        }
        val now = System.currentTimeMillis()
        snapshotStore.save(
            storage.id,
            candidates.map {
                // §61.204：stat 未变 → 继承上一轮 metadataDone（refresh 重扫可跳过重读）；
                // 有变 / 新出现 → 重置 false，待本轮 metadata 块完成回写。
                // §61.212：STRM/播放列表候选缓存 directUrl（walk 复用，免逐文件重读）。
                // §61.221：快照键 = effectiveIdPath（分轨 = 锚点）、stat 取 statObj（分轨 = cue 文件）——
                // cue 未变时分轨继承 metadataDone，refresh 免重展开；既有候选两处均与旧口径相同。
                val old = previous[it.effectiveIdPath()]
                val statUnchanged = old != null && old.matches(it.statSize(), it.statModified())
                ScanSnapshot(
                    storage.id, it.effectiveIdPath(), it.statSize(), it.statModified(), it.kind, now,
                    metadataDone = statUnchanged && old.metadataDone,
                    directUrl = it.directUrl ?: old?.takeIf { o -> statUnchanged }?.directUrl,
                )
            },
        )
        // 文件恢复出现：accepted 里的 upsert 已把 availability 置回 OK

        if (request.includeMetadata && (!request.onlyWifiMetadata || request.wifiAvailable)) {
            runMetadata(storage.id, accepted, request, state, emit, metadataBudget)
        }
        // §61.254：歌单导入——accepted 交集过滤（规则剔除的条目不进歌单），
        // TrackId 由组合层 sink 按 storageId+path 派生（TrackId.of 同一真值源）。
        val sink = playlistImportSink
        if (sink != null && playlistImports.isNotEmpty()) {
            val acceptedPaths = accepted.map { it.effectiveIdPath() }.toSet()
            playlistImports.forEach { imp ->
                runCatching {
                    sink.import(
                        imp.id, imp.name,
                        imp.idPaths.filter { it in acceptedPaths }
                            .map { com.ccmsc.cloudmusic.model.TrackId.of(storage.id, it) },
                    )
                }.onFailure {
                    state.error("", "E_SCAN_IMPORT", "歌单导入失败: ${imp.name}")
                }
            }
        }
    }

    private suspend fun walk(
        driver: Driver,
        storage: Storage,
        dir: Obj,
        request: ScanRequest,
        state: ScanState,
        out: MutableList<Candidate>,
        depth: Int,
        previous: Map<String, ScanSnapshot> = emptyMap(),
        imports: MutableList<com.ccmsc.cloudmusic.scanner.PlaylistImport> = mutableListOf(),
    ) {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_WALK_DEPTH) {
            state.error("", "E_DS_NOT_SUPPORT", "目录层级过深: ${dir.path}")
            return
        }
        val items = driver.list(dir, request.refresh)
        // §61.178：目录级打点——网络源卡在首个 list() 时无法与「未开始」区分。
        println("CM-Scan [${storage.id}] list('${dir.path}') -> ${items.size} 项")
        if (rules.isNoMedia(items.map { it.name })) return   // B11：整个子树跳过

        val childDirs = mutableListOf<Obj>()
        // §61.221：cue 的 FILE 引用按 basename 匹配同目录音频项——分派前先建索引，
        // 不改变 AUDIO 候选的产生时机与顺序（整轨候选照常，分轨候选在 cue 处追加）。
        val audioByName = items.asSequence()
            .filter { !it.isDir && rules.classify(it.name, false) == FileClass.AUDIO }
            .associateBy { it.name.lowercase() }
        for (item in items) {
            currentCoroutineContext().ensureActive()
            when (rules.classify(item.name, item.isDir)) {
                FileClass.DIRECTORY -> if (item.name.lowercase() !in effectiveIgnoreDirs()) childDirs += item
                FileClass.AUDIO -> out += audioCandidate(storage, item, dir)
                // 03-04 §5 内联展开：直接调用共享解析函数，不实例化 StrmDriver / PlaylistDriver
                FileClass.STRM -> expandStrm(driver, storage, item, dir, state, out, previous)
                FileClass.PLAYLIST -> expandPlaylistInline(driver, storage, item, dir, state, out, imports)
                FileClass.CUE -> expandCue(driver, storage, item, dir, state, out, audioByName)
                FileClass.PASS_THROUGH, FileClass.IGNORED -> Unit
            }
        }
        for (child in childDirs) {
            walk(driver, storage, child, request, state, out, depth + 1, previous, imports)
        }
    }

    private fun audioCandidate(storage: Storage, item: Obj, dir: Obj) = Candidate(
        storage = storage,
        obj = item,
        kind = CandidateKind.AUDIO,
        parentDirName = parentName(if (dir.path.isBlank()) item.path else dir.path),
    )

    private suspend fun expandStrm(
        driver: Driver,
        storage: Storage,
        item: Obj,
        dir: Obj,
        state: ScanState,
        out: MutableList<Candidate>,
        previous: Map<String, ScanSnapshot> = emptyMap(),
    ) {
        // §61.212（B4 真机一手）：walk 阶段的逐文件 readTextOrThrow（每 STRM 1 次
        // 网络 GET，92 文件 ≈100s）是 §61.203（只砍 metadata 侧）后的漏网成本大头。
        // stat 未变 + 快照已缓存 directUrl ⇒ 直接复用；.strm 内容变化由
        // (size,modified) 捕获 → 变了才重读。URL 失效由播放期断链标记兜底。
        val cached = previous[item.path]
        if (cached != null && cached.kind == CandidateKind.STRM &&
            cached.matches(item.size, item.modified) && !cached.directUrl.isNullOrBlank()
        ) {
            out += Candidate(
                storage = storage,
                obj = item,
                kind = CandidateKind.STRM,
                parentDirName = parentName(dir.path.ifBlank { item.path }),
                hint = strmTitleHint(item.name)?.let { TrackHint(title = it.second, artist = it.first) },
                directUrl = cached.directUrl,
            )
            return
        }
        val text = runCatching { driver.readTextOrThrow(item) }.getOrElse {
            state.error("", "E_SCAN_ENCODING", "strm 读取失败: ${item.path}")
            return
        }
        when (val result = parseStrm(text)) {
            is StrmParseResult.Url -> out += Candidate(
                storage = storage,
                obj = item,
                kind = CandidateKind.STRM,
                parentDirName = parentName(dir.path.ifBlank { item.path }),
                hint = strmTitleHint(item.name)?.let { TrackHint(title = it.second, artist = it.first) },
                directUrl = result.url,
            )
            is StrmParseResult.Relative -> {
                // §61.227（V0.2 M9）：相对引用展开为同源音频候选——解析相对 .strm 所在
                // 目录（`./` `../` 由 PathNormalizer 消解；`/` 开头为驱动内绝对路径），
                // 目标命中（非目录）→ kind=AUDIO（obj=目标，播放直接走本驱动 link）；
                // miss → E_SCAN 可见化，不静默产生空曲库。
                val baseDir = dir.path.ifBlank { item.path.substringBeforeLast('/', "") }
                val resolved = runCatching { resolveRelativeStrm(baseDir, result.path) }.getOrNull()
                val target = resolved?.let { runCatching { driver.get(it) }.getOrNull() }
                if (resolved == null || target == null || target.isDir) {
                    state.error(item.path, "E_SCAN", "strm 相对引用目标不存在: ${item.path}")
                } else {
                    out += Candidate(
                        storage = storage,
                        obj = target,
                        kind = CandidateKind.AUDIO,
                        parentDirName = parentName(dir.path.ifBlank { item.path }),
                    )
                }
            }
            is StrmParseResult.Unsupported -> state.error(
                item.path, "E_LINK_BAD_SCHEME", "不支持的 strm scheme: ${result.scheme} @ ${item.path}",
            )
            StrmParseResult.Empty -> state.error(
                item.path, "E_LINK_BAD_SCHEME", "strm 内容为空: ${item.path}",
            )
        }
    }

    /**
     * §61.221：CUE sheet 展开——一个 cue → N 个分轨候选（kind=CUE，obj = FILE 关联的音频项）。
     * - FILE 按 basename 匹配同目录音频项（cue 规范：FILE 为相对 cue 所在目录的文件名）；
     * - 分轨起点 = INDEX 01；终点 = 同 FILE 内下一轨起点；同 FILE 末轨 / 独立 FILE = 0（无界）；
     * - cue 文本自身的 (size, modified) 作为分轨候选的 stat 源（[Candidate.statObj]）——
     *   cue 变化即使音频 stat 未变也触发重展开；cue 未变时分轨候选走 statHit 免重 upsert；
     * - 阶段 A 骨架即带 cue 元数据（[Candidate.cueRaw]），阶段 B 跳过 tag 读取（runMetadata）；
     * - 整轨候选不抑制（与分轨并存入库，重复条目取舍留后续批如实登记）。
     */
    /** §61.227：.strm 相对引用解析——`/` 开头为驱动内绝对路径，其余相对 [baseDir]
     *  拼接后由 PathNormalizer 消解 `./` / `../`（越界抛 DsNotSupport，由调用方降级）。 */
    private fun resolveRelativeStrm(baseDir: String, rel: String): String {
        val p = rel.trim()
        val joined = if (p.startsWith("/")) p else if (baseDir.isBlank()) p else "$baseDir/$p"
        return PathNormalizer.normalize(joined)
    }

    private suspend fun expandCue(
        driver: Driver,
        storage: Storage,
        item: Obj,
        dir: Obj,
        state: ScanState,
        out: MutableList<Candidate>,
        audioByName: Map<String, Obj>,
    ) {
        val text = runCatching { driver.readTextOrThrow(item) }.getOrElse {
            state.error(item.path, "E_SCAN_ENCODING", "cue 读取失败: ${item.path}")
            return
        }
        val sheet = CueParser.parse(text)
        sheet.errors.forEach { state.error(item.path, "E_SCAN", "cue 解析: $it @ ${item.name}") }
        val audioTracks = sheet.tracks.filter { it.audio }
        if (audioTracks.isEmpty()) {
            state.error(item.path, "E_SCAN", "cue 无有效音频轨: ${item.path}")
            return
        }
        val cueStem = item.name.substringBeforeLast('.').ifBlank { item.name }
        val parent = parentName(dir.path.ifBlank { item.path })
        // (轨, 关联音频) 匹配：FILE 先全名（大小写不敏感）再 basename（cue 常写相对子路径）
        val matched = mutableListOf<Triple<CueTrack, Obj, Long>>()
        for (t in audioTracks) {
            val file = t.file
            if (file == null) {
                state.error(item.path, "E_SCAN", "cue 轨 ${t.no} 无 FILE 上下文 @ ${item.name}")
                continue
            }
            val audio = audioByName[file.lowercase()]
                ?: audioByName[file.substringAfterLast('/').lowercase()]
            if (audio == null) {
                state.error(item.path, "E_SCAN", "cue 引用的音频文件不在同目录: ${t.file} @ ${item.path}")
                continue
            }
            matched += Triple(t, audio, t.startMs)
        }
        for (i in matched.indices) {
            val (t, audio, start) = matched[i]
            // 终点 = 同音频文件内下一轨起点；跨音频 / 末轨 = 0（无界，播到本文件自然结束）
            val end = matched.getOrNull(i + 1)
                ?.takeIf { (_, nextAudio, _) -> nextAudio.path == audio.path }
                ?.third ?: 0L
            val dur = if (end > start) end - start else null
            val artist = t.performer ?: sheet.albumPerformer
            out += Candidate(
                storage = storage,
                obj = audio,
                kind = CandidateKind.CUE,
                parentDirName = parent,
                hint = TrackHint(title = t.title, artist = artist, durationMs = dur),
                idPathOverride = "${audio.path}#$cueStem#${t.no}",
                statObj = item,
                cueRaw = RawTags(
                    title = t.title,
                    artist = artist,
                    album = sheet.albumTitle,
                    trackNo = t.no,
                    durationMs = dur,
                    completeness = listOfNotNull(t.title, artist, sheet.albumTitle).size / 3f,
                ),
                cueStartMs = start,
                cueEndMs = end,
            )
        }
    }

    private suspend fun expandPlaylistInline(
        driver: Driver,
        storage: Storage,
        item: Obj,
        dir: Obj,
        state: ScanState,
        out: MutableList<Candidate>,
        imports: MutableList<com.ccmsc.cloudmusic.scanner.PlaylistImport>,
    ) {
        val source = object : PlaylistSource {
            override suspend fun readText(path: String): String? = runCatching {
                driver.readTextOrThrow(Obj(name = path.substringAfterLast('/'), path = path))
            }.getOrNull()

            override suspend fun find(path: String): Obj? = driver.get(path)
        }
        val expanded = runCatching {
            expandPlaylist(item, source, rootPathFor(storage))
        }.getOrElse {
            state.error("", "E_SCAN_ENCODING", "播放列表展开失败: ${item.path}")
            return
        }
        val added = mutableListOf<Candidate>()
        expanded.forEach { obj ->
            obj.playlistRejection()?.let { reason ->
                state.error("", "E_DS_NOT_SUPPORT", "$reason @ ${obj.path}")
                return@forEach
            }
            val candidate = obj.playlistDirectUrl()?.let { url ->
                Candidate(
                    storage = storage, obj = obj, kind = CandidateKind.PLAYLIST,
                    parentDirName = parentName(dir.path.ifBlank { obj.path }),
                    directUrl = url,
                )
            } ?: Candidate(
                storage = storage, obj = obj, kind = CandidateKind.PLAYLIST,
                parentDirName = parentName(dir.path.ifBlank { obj.path }),
            )
            added += candidate
            out += candidate
        }
        // §61.254：注册为歌单页命名实体——id = 稳定派生（storageId+m3u 路径，重扫幂等）；
        // name = m3u 文件名去扩展名；曲目序 = m3u 条目序（坏条目已被上方剔除）。
        imports += com.ccmsc.cloudmusic.scanner.PlaylistImport(
            id = "pl_imp_" + com.ccmsc.cloudmusic.model.TrackId
                .of("playlist-import", "${storage.id}|${item.path}").value,
            name = item.name.substringBeforeLast('.'),
            idPaths = added.map { it.effectiveIdPath() },
        )
    }

    private suspend fun runMetadata(
        storageId: String,
        candidates: List<Candidate>,
        request: ScanRequest,
        state: ScanState,
        emit: (ScanProgress) -> Unit,
        /** §61.323（B-2）：全源共享预算；由 [run] 创建并下传，本函数**不再自建**。 */
        semaphore: Semaphore,
    ) {
        // §61.323（B-2）：此处原有 `val semaphore = Semaphore(request.metadataConcurrency)`，
        // 每个源一份 ⇒ 多源并存时并发按源数倍增（4 源 = 32）。现改为 run() 级共享。
        // §61.144：metadata 写库按 batchSize 聚合——原实现逐文件 `upsertBatch(listOf(单行))`
        // + 逐文件进度 emit，7,500 规模 = 7,514 个独立 Room 事务 + 7,514 次全量重组，
        // 是 T-V #9 冷扫判据 FAIL 的直接卡点（阶段 A 本体 ~50-60s，metadata 阶段分钟级）。
        // 现按块并行读标签（信号量限流），块尾一次 upsertBatch + emit；
        // 取消/异常时仅当前块的增量丢失（阶段 A 骨架已在库，重扫即恢复，§8 快照语义）。
        coroutineScope {
            candidates.chunked(request.batchSize.coerceAtLeast(1)).forEach { chunk ->
                val tracks = chunk.map { candidate ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            // §61.221：分轨元数据已在候选（cue 文本，阶段 A 骨架即带全），
                            // 跳过 tag 读取——整轨文件 tag 是专辑级信息，读了反而覆盖轨级元数据。
                            val raw = candidate.cueRaw ?: tagReader.read(candidate)
                            if (raw == RawTags.EMPTY) null   // B01：降级，保留阶段 A 骨架
                            else trackFactory.build(candidate, raw)
                        }
                    }
                }.awaitAll().filterNotNull()
                if (tracks.isNotEmpty()) trackSink.upsertBatch(tracks)
                // §61.204：本块 tag 读取完成（含 B01 降级的稳定结果）→ 快照回写 done；
                // 取消发生在块内则整块不记，下轮 refresh 自动补读（§61.144 语义延续）。
                // §61.221：键 = effectiveIdPath（分轨 = 锚点）。
                snapshotStore.markMetadataDone(storageId, chunk.map { it.effectiveIdPath() })
                state.addMetadata(tracks.size)
                emit(state.progress(storageId, request, phase = ScanPhase.METADATA))
                // §61.205：块间让步——熄屏时小幅 sleep，防 CPU 长满占用触发 MIUI 异常。
                pacer.yieldBetweenChunks()
            }
        }
    }

    private fun parentName(path: String): String {
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        return parent.substringAfterLast('/').ifBlank { "未知" }
    }

    private class ScanState {
        val scanned = AtomicInteger(0)
        val upserted = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val unavailable = AtomicInteger(0)
        val metadata = AtomicInteger(0)
        val stageA = AtomicInteger(0)
        val errors: MutableList<ScanError> = Collections.synchronizedList(mutableListOf())

        fun addScanned(n: Int) { scanned.addAndGet(n) }
        fun addUpserted(n: Int) { upserted.addAndGet(n) }
        fun addSkipped(n: Int) { skipped.addAndGet(n) }
        fun addUnavailable(n: Int) { unavailable.addAndGet(n) }
        fun addMetadata(n: Int) { metadata.addAndGet(n) }
        fun error(storageId: String, code: String, detail: String) {
            errors += ScanError(storageId, code, detail)
        }

        fun progress(
            storageId: String,
            request: ScanRequest,
            phase: ScanPhase = request.phase,
            cancelled: Boolean = false,
            stageAFinished: Boolean = false,
        ) = ScanProgress(
            storageId = storageId,
            phase = phase,
            scanned = scanned.get(),
            total = (scanned.get() + skipped.get()).coerceAtLeast(1),
            currentPath = "",
            errors = errors.toList(),
            cancelled = cancelled,
            stageAFinished = stageAFinished,
        )

        fun report(cancelled: Boolean, stageAFinished: Boolean) = ScanReport(
            scanned = scanned.get(),
            upserted = upserted.get(),
            skipped = skipped.get(),
            unavailable = unavailable.get(),
            errors = errors.toList(),
            cancelled = cancelled,
            stageAFinished = stageAFinished,
        )
    }

    companion object {
        const val MAX_WALK_DEPTH = 64
    }
}
