package com.example.cloudmusic.scanner

import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import java.util.concurrent.ConcurrentHashMap

/**
 * docs/03-04 §4 / §8 的端口抽象：Room、DataStore、Android 运行时全部隔离在适配层。
 * 生产实现由 :core-storage（03-05）提供；单测用内存 fake。
 */
interface StorageProvider {
    suspend fun active(): List<Storage>
}

interface TrackSink {
    /** 03-05 TrackRepository.upsertBatch：每 500 条一个事务 */
    suspend fun upsertBatch(tracks: List<Track>)

    /** 快照里有、本次没扫到 → 标 UNREACHABLE，禁止删除（03-04 §8 / §15 #3） */
    suspend fun markUnavailable(ids: List<TrackId>)

    /**
     * C-14（`12 §31`）：定向增量用的「当前不可用条目路径集合」。
     * 默认空集 → 既有 fake / 内存实现零回归；生产 `RoomTrackSink` 从曲库投影。
     */
    suspend fun unavailablePaths(storageId: String): Set<String> = emptySet()

    /**
     * §61.179：该数据源在曲库里**存在且可用**的路径集合。
     *
     * 用途：快照（`scan_snapshot`）与曲库（`tracks`）可能不一致——
     * 删除数据源走 `markOrphan` 只把曲目置 UNREACHABLE 而不清快照，
     * 重新加回同源时 size/mtime 全未变 ⇒ 增量判定「已扫过」全部 skip
     * ⇒ 曲目永久停在 UNREACHABLE，播放不了。曲库被清空后同理。
     *
     * 返回 `null` = 该实现不提供此能力（fake / 内存实现），调用方跳过本判据，
     * **不能**用空集表达「不提供」：空集是真实现里的「库为空」。
     */
    suspend fun knownPaths(storageId: String): Set<String>? = null

    /**
     * §61.255：曲库中**有轨道**的 storageId 集合（孤儿源 sweep 输入）。
     * 默认空集 → 既有 fake / 内存实现零回归；生产 `RoomTrackSink` 从曲库投影。
     */
    suspend fun trackedStorageIds(): Set<String> = emptySet()

    /**
     * §61.255：指定 storage 的全部轨道 id（孤儿源标记 UNREACHABLE 的对象）。
     */
    suspend fun trackIdsByStorage(storageId: String): List<com.example.cloudmusic.model.TrackId> = emptyList()
}

data class ScanSnapshot(
    val storageId: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val kind: CandidateKind,
    val scannedAt: Long,
    /**
     * §61.204（A1-③）：该文件版本（size/modified 未变的前提下）的元数据读取是否
     * 已完成。`refresh=true` 的 stat 命中跳过依赖此标记——false（上次扫描中断 /
     * WiFi 闸跳过 / 新文件）⇒ 重扫时补读，true ⇒ 跳过重读（标签内嵌于文件本体，
     * stat 未变 ⇒ 标签不可能变）。
     */
    val metadataDone: Boolean = false,
    /**
     * §61.212（B4 真机一手）：STRM/播放列表候选缓存的 directUrl——walk 阶段的
     * `expandStrm` 逐文件 `readTextOrThrow`（每 STRM 1 次网络 GET，92 文件 ≈100s
     * 真机实测）是 §61.203（只砍了 metadata 侧）之后的漏网成本大头；stat 未变 +
     * 快照已缓存 URL ⇒ 直接复用，`.strm` 内容变化由 (size,modified) 捕获后重读。
     */
    val directUrl: String? = null,
) {
    fun matches(size: Long, modified: Long): Boolean =
        this.size == size && this.modified == modified
}

interface ScanSnapshotStore {
    suspend fun load(storageId: String): Map<String, ScanSnapshot>
    suspend fun save(storageId: String, entries: List<ScanSnapshot>)

    /**
     * §61.204：metadata 块完成后按路径批量置 done（快照整轮替换发生在阶段 A 与
     * 阶段 B 之间，此处是对已完成块的增量回写）。默认空实现 = 既有 fake / 内存
     * 实现零回归（§61.145 markAvailabilityBatch 同型接口演进）。
     */
    suspend fun markMetadataDone(storageId: String, paths: Collection<String>) {}

    suspend fun clear(storageId: String)
}

class InMemoryScanSnapshotStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ScanSnapshotStore {
    private val data = ConcurrentHashMap<String, MutableMap<String, ScanSnapshot>>()

    override suspend fun load(storageId: String): Map<String, ScanSnapshot> =
        data[storageId]?.toMap() ?: emptyMap()

    override suspend fun save(storageId: String, entries: List<ScanSnapshot>) {
        // 整轮扫描写完整快照：本次没扫到的条目随替换移除，
        // 这样文件恢复出现时会被当作新增重新 upsert（03-04 §8「恢复自动置 OK」）
        data[storageId] = ConcurrentHashMap(entries.associateBy { it.path })
    }

    override suspend fun markMetadataDone(storageId: String, paths: Collection<String>) {
        val store = data[storageId] ?: return
        paths.forEach { path ->
            store[path]?.let { store[path] = it.copy(metadataDone = true) }
        }
    }

    override suspend fun clear(storageId: String) {
        data.remove(storageId)
    }
}

/** 便于单测的最小内存实现：线程安全、upsert 按 TrackId 覆盖。 */
class InMemoryTrackSink : TrackSink {
    private val tracks = ConcurrentHashMap<String, Track>()

    override suspend fun upsertBatch(tracks: List<Track>) {
        tracks.forEach { this.tracks[it.id.value] = it }
    }

    override suspend fun markUnavailable(ids: List<TrackId>) {
        ids.forEach { id -> tracks[id.value]?.let { t ->
            tracks[id.value] = t.copy(availability = com.example.cloudmusic.model.Availability.UNREACHABLE)
        } }
    }

    fun all(): List<Track> = tracks.values.toList()
    fun find(id: TrackId): Track? = tracks[id.value]
}

/**
 * §61.205（A1-⑤）：扫描节奏端口——扫描期持 wake lock 防深睡冻结 CPU +
 * 熄屏时块间让步（§61.144 冻结 / CPU-100% 异常教训；MIUI 进程级冻结无法由
 * 应用完全避免，完整判据实测仍须 USB+亮屏会话）。默认 [ScanPacerNoOp] =
 * JVM 单测与桌面场景零影响。
 */
interface ScanPacer {
    /** 扫描开始（引用计数幂等——多 Storage / 重入下 begin/end 成对出现）。 */
    fun beginScan() {}

    /** 扫描结束（引用计数归零才真正释放）。 */
    fun endScan() {}

    /** metadata 块间让步；熄屏时小幅 sleep（量级 ~50ms，实现自定）。 */
    suspend fun yieldBetweenChunks() {}
}

object ScanPacerNoOp : ScanPacer
