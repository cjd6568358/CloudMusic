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
}

data class ScanSnapshot(
    val storageId: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val kind: CandidateKind,
    val scannedAt: Long,
) {
    fun matches(size: Long, modified: Long): Boolean =
        this.size == size && this.modified == modified
}

interface ScanSnapshotStore {
    suspend fun load(storageId: String): Map<String, ScanSnapshot>
    suspend fun save(storageId: String, entries: List<ScanSnapshot>)
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
