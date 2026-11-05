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
        val previous = snapshotStore.load(storage.id)
        val candidates = mutableListOf<Candidate>()
        try {
            walk(driver, storage, Obj(name = "", isDir = true, path = ""), request, state, candidates, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error("", "E_SCAN_STORAGE_UNREACHABLE", e.message ?: "目录走查失败")
            return   // B07：单个 Storage 失败不影响其它 Storage
        }

        val seen = candidates.map { it.obj.path }.toSet()
        val changed = candidates.filter { c ->
            val old = previous[c.obj.path]
            request.refresh || old == null || !old.matches(c.obj.size, c.obj.modified)
        }
        state.addSkipped(candidates.size - changed.size)
        val accepted = changed.filter { !rules.tooShort(it.hint?.durationMs) }
        state.addScanned(candidates.size)

        // 阶段 A：批量 upsert 骨架（禁止逐条写库）
        val tracks = accepted.map { trackFactory.build(it) }
        tracks.chunked(request.batchSize.coerceAtLeast(1)).forEach { trackSink.upsertBatch(it) }
        state.addUpserted(tracks.size)
        state.stageA.incrementAndGet()
        emit(state.progress(storage.id, request, stageAFinished = true))

        // 快照有、本次没扫到 → 标 UNREACHABLE，禁止删除（§8 / §15 #3）
        val missing = previous.keys.filter { it !in seen }.map { TrackId.of(storage.id, it) }
        if (missing.isNotEmpty()) {
            trackSink.markUnavailable(missing)
            state.addUnavailable(missing.size)
        }
        val now = System.currentTimeMillis()
        snapshotStore.save(
            storage.id,
            candidates.map {
                ScanSnapshot(storage.id, it.obj.path, it.obj.size, it.obj.modified, it.kind, now)
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
        if (rules.isNoMedia(items.map { it.name })) return   // B11：整个子树跳过

        val childDirs = mutableListOf<Obj>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            when (rules.classify(item.name, item.isDir)) {
                FileClass.DIRECTORY -> if (!rules.isIgnoredDir(item.name)) childDirs += item
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
        coroutineScope {
            candidates.map { candidate ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        val raw = tagReader.read(candidate)
                        if (raw == RawTags.EMPTY) return@withPermit   // B01：降级，保留阶段 A 骨架
                        trackSink.upsertBatch(listOf(trackFactory.build(candidate, raw)))
                        state.addMetadata(1)
                        emit(state.progress(storageId, request, phase = ScanPhase.METADATA))
                    }
                }
            }.awaitAll()
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
