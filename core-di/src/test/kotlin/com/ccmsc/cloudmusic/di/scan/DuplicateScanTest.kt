package com.ccmsc.cloudmusic.di.scan

import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.storage.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** §61.247：A1 Tier2 重复曲目检测会话——读曲库 → 分组 → 报告（零 IO）。 */
class DuplicateScanTest {

    private fun track(
        id: Int,
        title: String,
        artist: String = "窦唯",
        durationMs: Long = 200_000L,
        path: String = "/music/$id.flac",
    ) = Track(
        id = TrackId("id-$id"), title = title, artist = artist, album = "黑梦",
        durationMs = durationMs, sourceRef = SourceRef("s", path), sourceType = SourceType.LOCAL,
    )

    @Test
    @DisplayName("§61.247 · 会话：疑似重复分组 + scanned 计数；无重复空组")
    fun sessionFlow() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "黑色梦中"),
            track(2, "黑色梦中", durationMs = 200_500L, path = "/nas/2.flac"),
            track(3, "独立曲"),
        ))
        val report = DuplicateScanSession(repo).detect()
        assertEquals(3, report.scanned)
        assertEquals(1, report.groups.size)
        assertEquals(2, report.groups[0].items.size)
        assertEquals("黑色梦中", report.groups[0].title)
    }

    @Test
    @DisplayName("§61.247 · 会话：空库 / 无重复 → 空组表")
    fun emptyAndNoDup() = runTest {
        assertEquals(0, DuplicateScanSession(InMemoryTrackRepository()).detect().groups.size)
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(track(1, "甲"), track(2, "乙")))
        val report = DuplicateScanSession(repo).detect()
        assertEquals(2, report.scanned)
        assertEquals(0, report.groups.size)
    }

    @Test
    @DisplayName("§61.247 · 会话读口与 UI 消费一致（observeAll().first() 快照语义）")
    fun snapshotSemantics() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(track(1, "甲"), track(2, "甲", path = "/n/2.flac")))
        val session = DuplicateScanSession(repo)
        val first = session.detect()
        assertEquals(1, first.groups.size)
        // 库变化后再次检测反映新快照
        repo.upsertBatch(listOf(track(3, "甲", path = "/n/3.flac")))
        val second = session.detect()
        assertEquals(3, second.groups[0].items.size)
        assertEquals(3, repo.observeAll().first().size)
    }
}
