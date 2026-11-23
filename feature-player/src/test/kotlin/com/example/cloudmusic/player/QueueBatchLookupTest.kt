package com.example.cloudmusic.player

import com.example.cloudmusic.engine.api.InMemoryMusicEngine
import com.example.cloudmusic.media.InMemoryMediaControl
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §61.319（R2）· 队列批量预取判据（纯 JVM，无 Room / 无网络）。
 *
 * 用「计数 lookup」把 P0-2 的规模性根因钉死：整库队列（本例 5000 首）在
 * setQueue 时**一次** getMany 取齐 cue 标志，prepareAt（含 gapless 邻居解析）
 * 只保留「当前曲目 1 次点查」，不再对整条队列逐项 lookup.get。
 * 生产 RoomTrackLookup 覆写 getMany → `IN` 查询（见 core-di），本测试只钉端口行为。
 */
class QueueBatchLookupTest {

    /** 计数型 lookup：get = 点查（应只在 prepareAt 当前曲目出现），getMany = 批量。 */
    private class CountingLookup(tracks: List<Track>) : TrackLookup {
        private val map = tracks.associateBy { it.id }
        var pointGets = 0
            private set
        var batchCalls = 0
            private set
        var batchIds = 0
            private set

        override suspend fun get(id: TrackId): Track? {
            pointGets++
            return map[id]
        }

        override suspend fun getMany(ids: List<TrackId>): List<Track?> {
            batchCalls++
            batchIds += ids.size
            return ids.map { map[it] }
        }
    }

    private fun bigQueue(size: Int): List<Track> = (1..size).map { i ->
        Track(
            id = TrackId("q-$i"),
            title = "曲 $i",
            artist = "歌手",
            album = "专辑",
            durationMs = 180_000L,
            sourceRef = SourceRef("storage", "/music/$i.flac"),
            sourceType = SourceType.LOCAL,
        )
    }

    private fun fixture(tracks: List<Track>, lookup: CountingLookup) = Fixture(tracks, lookup)

    private class Fixture(tracks: List<Track>, val lookup: CountingLookup) {
        val engine = InMemoryMusicEngine()
        val controller = PlayerController(
            engine = engine,
            sync = object : QueueSync {
                override suspend fun push(items: List<QueueItem>, mode: PlayMode, startIndex: Int) = Unit
            },
            lookup = lookup,
            resolver = TrackSourceResolver { t ->
                Link(localPath = "/local/${t.id.value}.flac", localSize = t.durationMs)
            },
            media = InMemoryMediaControl(),
            scope = TestScope(),
        )
        val items = tracks.mapIndexed { i, t ->
            QueueItem(i, t.id, t.sourceRef, i.toLong())
        }
    }

    @Test
    fun `大队列 setQueue 后 prepareAt 不逐项点查`() = runTest {
        val tracks = bigQueue(5_000)
        val lookup = CountingLookup(tracks)
        val f = fixture(tracks, lookup)
        f.controller.setQueue(f.items, PlayMode.SEQUENCE)

        assertEquals(1, lookup.batchCalls, "setQueue 必须一次批量取齐")
        assertEquals(5_000, lookup.batchIds)
        assertEquals(0, lookup.pointGets, "setQueue 不得逐项点查")

        f.controller.playAt(0)
        assertEquals(1, lookup.pointGets, "prepareAt 仅当前曲目 1 次点查，不是整队 N 次")
        assertEquals(1, lookup.batchCalls, "prepareAt 不得再发起整队批量")
        assertNotNull(f.engine.snapshot().source)
    }

    @Test
    fun `批量预取的队列无 CUE 标志时 gapless 邻居不逐项点查`() = runTest {
        val tracks = bigQueue(2_000)
        val lookup = CountingLookup(tracks)
        val f = fixture(tracks, lookup)
        f.controller.setQueue(f.items, PlayMode.SEQUENCE)
        f.controller.playAt(1) // 非队首：buildQueueSources 必须解析邻居

        assertEquals(1, lookup.batchCalls)
        assertEquals(2_000, lookup.batchIds)
        assertEquals(1, lookup.pointGets, "buildQueueSources 逐项 lookup 风暴已移除")
        assertNotNull(f.engine.snapshot().source, "gapless 邻居解析后仍正常起播")
    }

    @Test
    fun `setQueue 预取缺项时按原语义降级不崩`() = runTest {
        val tracks = bigQueue(2).map { it.copy() }
        // lookup 只有第一首，第二首缺失 → setQueue 与 prepareAt 均按原语义处理
        val lookup = CountingLookup(listOf(tracks.first()))
        val f = fixture(tracks, lookup)
        f.controller.setQueue(f.items, PlayMode.SEQUENCE)
        assertEquals(2, lookup.batchIds)
        f.controller.playAt(0) // 队首存在，仍能起播（缺项为 null，不伪造）
        assertNotNull(f.engine.snapshot().source)
    }
}
