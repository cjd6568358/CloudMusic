package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.readTextOrThrow
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.playlist.PlaylistSource
import com.example.cloudmusic.playlist.expandPlaylist
import com.example.cloudmusic.playlist.playlistDirectUrl
import com.example.cloudmusic.playlist.playlistRejection
import com.example.cloudmusic.strm.StrmParseResult
import com.example.cloudmusic.strm.parseStrm
import com.example.cloudmusic.strm.strmTitleHint
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
    private val trackFactory: TrackFactory = TrackFactory(),
    private val rootPathFor: (Storage) -> String = { "" },
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
        return try {
            val storages = storageProvider.active()
                .filter { request.storageIds.isEmpty() || it.id in request.storageIds }
            val semaphore = Semaphore(request.storageConcurrency.coerceAtLeast(1))
            coroutineScope {
                storages.map { storage ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            scanStorage(storage, request, state, emit)
                        }
                    }
                }.awaitAll()
            }
            state.report(cancelled = false, stageAFinished = true)
        } catch (e: CancellationException) {
            emit(state.progress("", request, cancelled = true))
            state.report(cancelled = true, stageAFinished = state.stageA.get() > 0)
        }
    }

    // ------------------------------------------------------------------
    private suspend fun scanStorage(
        storage: Storage,
        request: ScanRequest,
        state: ScanState,
        emit: (ScanProgress) -> Unit,
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
        try {
            trace("walk 开始 path='${storage.mountPath}' root='${rootPathFor(storage)}'")
            walk(driver, storage, Obj(name = "", isDir = true, path = ""), request, state, candidates, 0)
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

        val seen = scoped.map { it.obj.path }.toSet()
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
        val statHit = if (request.refresh) scoped.filter { c ->
            val old = previous[c.obj.path]
            old != null && old.matches(c.obj.size, c.obj.modified) && old.metadataDone &&
                (knownPaths == null || c.obj.path in knownPaths)
        }.map { it.obj.path }.toSet() else emptySet()
        val changed = scoped.filter { c ->
            val old = previous[c.obj.path]
            // 定向增量必须强制重处理，否则文件未变时失败项永远不会被重新 upsert 置回 OK。
            c.obj.path !in statHit && (
                request.refresh || request.onlyUnavailable || old == null ||
                    !old.matches(c.obj.size, c.obj.modified) ||
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
                val old = previous[it.obj.path]
                val statUnchanged = old != null && old.matches(it.obj.size, it.obj.modified)
                ScanSnapshot(
                    storage.id, it.obj.path, it.obj.size, it.obj.modified, it.kind, now,
                    metadataDone = statUnchanged && old.metadataDone,
                )
            },
        )
        // 文件恢复出现：accepted 里的 upsert 已把 availability 置回 OK

        if (request.includeMetadata && (!request.onlyWifiMetadata || request.wifiAvailable)) {
            runMetadata(storage.id, accepted, request, state, emit)
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
        for (item in items) {
            currentCoroutineContext().ensureActive()
            when (rules.classify(item.name, item.isDir)) {
                FileClass.DIRECTORY -> if (item.name.lowercase() !in effectiveIgnoreDirs()) childDirs += item
                FileClass.AUDIO -> out += audioCandidate(storage, item, dir)
                // 03-04 §5 内联展开：直接调用共享解析函数，不实例化 StrmDriver / PlaylistDriver
                FileClass.STRM -> expandStrm(driver, storage, item, dir, state, out)
                FileClass.PLAYLIST -> expandPlaylistInline(driver, storage, item, dir, state, out)
                FileClass.PASS_THROUGH, FileClass.IGNORED -> Unit
            }
        }
        for (child in childDirs) {
            walk(driver, storage, child, request, state, out, depth + 1)
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
    ) {
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
            is StrmParseResult.Unsupported -> state.error(
                storage.id, "E_LINK_BAD_SCHEME", "不支持的 strm scheme: ${result.scheme} @ ${item.path}",
            )
            StrmParseResult.Empty -> state.error(
                storage.id, "E_LINK_BAD_SCHEME", "strm 内容为空: ${item.path}",
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
        expanded.forEach { obj ->
            obj.playlistRejection()?.let { reason ->
                state.error("", "E_DS_NOT_SUPPORT", "$reason @ ${obj.path}")
                return@forEach
            }
            obj.playlistDirectUrl()?.let { url ->
                out += Candidate(
                    storage = storage, obj = obj, kind = CandidateKind.PLAYLIST,
                    parentDirName = parentName(dir.path.ifBlank { obj.path }),
                    directUrl = url,
                )
                return@forEach
            }
            out += Candidate(
                storage = storage, obj = obj, kind = CandidateKind.PLAYLIST,
                parentDirName = parentName(dir.path.ifBlank { obj.path }),
            )
        }
    }

    private suspend fun runMetadata(
        storageId: String,
        candidates: List<Candidate>,
        request: ScanRequest,
        state: ScanState,
        emit: (ScanProgress) -> Unit,
    ) {
        val semaphore = Semaphore(request.metadataConcurrency.coerceAtLeast(1))
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
                            val raw = tagReader.read(candidate)
                            if (raw == RawTags.EMPTY) null   // B01：降级，保留阶段 A 骨架
                            else trackFactory.build(candidate, raw)
                        }
                    }
                }.awaitAll().filterNotNull()
                if (tracks.isNotEmpty()) trackSink.upsertBatch(tracks)
                // §61.204：本块 tag 读取完成（含 B01 降级的稳定结果）→ 快照回写 done；
                // 取消发生在块内则整块不记，下轮 refresh 自动补读（§61.144 语义延续）。
                snapshotStore.markMetadataDone(storageId, chunk.map { it.obj.path })
                state.addMetadata(tracks.size)
                emit(state.progress(storageId, request, phase = ScanPhase.METADATA))
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
