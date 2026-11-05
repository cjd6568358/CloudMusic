package com.example.cloudmusic.player

import com.example.cloudmusic.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/03-06 §8.2 的 Repository 契约，**签名逐字对齐**。
 * 业务队列是**唯一真相源**；Room 实现属 T-A1，本模块先给纯 JVM 内存实现。
 */
interface QueueRepository {
    fun observe(): Flow<List<QueueItem>>
    suspend fun setAll(tracks: List<Track>)              // 全量替换（下发 native）
    suspend fun insertNext(track: Track)                 // 插播
    suspend fun move(from: Int, to: Int)                 // 拖拽排序
    suspend fun remove(position: Int)
    suspend fun clear()
    suspend fun saveCursor(trackId: String, positionMs: Long)   // 每 5s + 切歌/暂停
    suspend fun restore(): PlaybackSnapshot?             // 冷启动恢复
}

/** 纯 JVM 参考实现：单一 [MutableStateFlow] 状态源，任何变更后重排 position，保证连续无空洞。 */
class InMemoryQueueRepository(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : QueueRepository {

    private val state = MutableStateFlow(emptyList<QueueItem>())
    private var mode = PlayMode.SEQUENCE
    private var snapshot: PlaybackSnapshot? = null
    private var cursorIndex = 0

    /** 供 UI / 组合层同步读取（Room 实现可用缓存快照等价提供）。 */
    fun snapshotItems(): List<QueueItem> = state.value

    fun currentMode(): PlayMode = mode

    fun setMode(value: PlayMode) { mode = value }

    override fun observe(): Flow<List<QueueItem>> = state.asStateFlow()

    override suspend fun setAll(tracks: List<Track>) {
        state.value = tracks.mapIndexed { index, track -> item(index, track) }
        cursorIndex = 0
    }

    override suspend fun insertNext(track: Track) {
        val current = state.value.toMutableList()
        val at = (cursorIndex + 1).coerceIn(0, current.size)
        current.add(at, item(at, track))
        state.value = reindex(current)
    }

    override suspend fun move(from: Int, to: Int) {
        val current = state.value
        if (current.isEmpty() || from !in current.indices || to !in current.indices || from == to) return
        val next = current.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        state.value = reindex(next)
    }

    override suspend fun remove(position: Int) {
        val current = state.value
        if (position !in current.indices) return
        val next = current.toMutableList().apply { removeAt(position) }
        state.value = reindex(next)
        if (cursorIndex >= next.size) cursorIndex = (next.size - 1).coerceAtLeast(0)
    }

    override suspend fun clear() {
        state.value = emptyList()
        cursorIndex = 0
        snapshot = null
    }

    override suspend fun saveCursor(trackId: String, positionMs: Long) {
        val index = state.value.indexOfFirst { it.trackId.value == trackId }
        if (index >= 0) cursorIndex = index
        snapshot = PlaybackSnapshot(
            currentTrackId = trackId,
            positionMs = positionMs.coerceAtLeast(0),
            playMode = mode,
            updatedAt = clock(),
        )
    }

    override suspend fun restore(): PlaybackSnapshot? = snapshot

    private fun item(position: Int, track: Track) = QueueItem(
        position = position,
        trackId = track.id,
        sourceRef = track.sourceRef,
        addedAt = clock(),
    )

    private fun reindex(items: List<QueueItem>): List<QueueItem> =
        items.mapIndexed { index, item -> item.copy(position = index) }
}
