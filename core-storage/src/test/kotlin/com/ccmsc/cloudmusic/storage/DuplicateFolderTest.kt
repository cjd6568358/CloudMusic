package com.ccmsc.cloudmusic.storage

import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.208（A4）：同 URL 双源折叠（[DuplicateFolder]，mergeDuplicates 死旋钮的消费方）。
 * 口径：UI 展示层折叠、不改库不删行（§15 #3 资产纪律）；胜者 = storageId 字典序最小。
 */
class DuplicateFolderTest {

    private fun track(id: String, storageId: String, path: String, url: String?) = Track(
        id = TrackId(id),
        title = "t$id",
        artist = "a",
        album = "al",
        durationMs = 1_000L,
        sourceRef = SourceRef(storageId = storageId, path = path, url = url),
        sourceType = SourceType.STRM,
    )

    @Test
    @DisplayName("开关关闭 → 原样透传（死旋钮接线的幂等出口）")
    fun disabledPassthrough() {
        val list = listOf(track("1", "b-strm", "/x.strm", "https://nas/a.flac"))
        assertTrue(DuplicateFolder.fold(list, enabled = false) === list)
    }

    @Test
    @DisplayName("同 URL 双源 → 只留 storageId 字典序最小的胜者")
    fun sameUrlFoldsToLowestStorageId() {
        val list = listOf(
            track("1", "webdav-strm-probe", "/w/a.strm", "https://nas/a.flac"),
            track("2", "strm-strm-probe", "/s/a.strm", "https://nas/a.flac"),
        )
        val folded = DuplicateFolder.fold(list, enabled = true)
        assertEquals(listOf("2"), folded.map { it.id.value }, "strm-strm-probe < webdav-strm-probe")
    }

    @Test
    @DisplayName("胜者与输入顺序无关（确定性，重排不抖动）")
    fun winnerIsOrderIndependent() {
        val a = track("1", "aaa", "/a.strm", "https://nas/a.flac")
        val b = track("2", "zzz", "/z.strm", "https://nas/a.flac")
        assertEquals(
            DuplicateFolder.fold(listOf(b, a), enabled = true).map { it.id.value },
            DuplicateFolder.fold(listOf(a, b), enabled = true).map { it.id.value },
        )
    }

    @Test
    @DisplayName("无 URL（本地文件）恒保留；不同 URL 互不折叠；原始顺序保持")
    fun nullUrlAndDistinctUrlsKept() {
        val list = listOf(
            track("1", "local", "/music/a.mp3", null),
            track("2", "s1", "/s/a.strm", "https://nas/a.flac"),
            track("3", "s2", "/s2/a.strm", "https://nas/a.flac"),
            track("4", "s1", "/s/b.strm", "https://nas/b.flac"),
        )
        val folded = DuplicateFolder.fold(list, enabled = true)
        assertEquals(listOf("1", "2", "4"), folded.map { it.id.value }, "3 折叠进 2，其余保留且顺序不变")
    }
}
