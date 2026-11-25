package com.ccmsc.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** §61.262：首页「最近添加」排序/去重纯 JVM 判据。 */
class TrackRecentlyAddedTest {

    private fun track(id: String, added: Long) = Track(
        id = TrackId(id), title = id, artist = "", album = "", durationMs = 0,
        sourceRef = SourceRef("s", "/$id"), sourceType = SourceType.LOCAL,
        addedAt = added,
    )

    @Test
    @DisplayName("§61.262 R1 · 按 addedAt 倒序取前 N（不是曲库前 N）")
    fun ordersByAddedAt() {
        val list = listOf(track("old", 10), track("new", 30), track("mid", 20))
        assertEquals(listOf("new", "mid"), list.recentlyAdded(2).map { it.id.value })
    }

    @Test
    @DisplayName("§61.263 R3 · 同名同艺人（CUE 分轨/版本）在展示层折叠，库行不删")
    fun dedupesSameTitleArtistForDisplay() {
        val a = track("a", 30).copy(title = "很爱很爱你", artist = "刘若英")
        val older = track("b", 29).copy(title = "很爱很爱你", artist = "刘若英")
        val other = track("c", 28).copy(title = "后来", artist = "刘若英")
        assertEquals(listOf("a", "c"), listOf(older, a, other).recentlyAdded(5).map { it.id.value })
    }

    @Test
    @DisplayName("§61.262 R2 · 同 id 只出现一次 + limit<=0 空列表")
    fun dedupesAndClamps() {
        val dup = track("same", 30)
        val list = listOf(track("older", 20), dup, dup.copy(addedAt = 31))
        val result = list.recentlyAdded(5)
        assertEquals(listOf("same", "older"), result.map { it.id.value })
        assertEquals(31L, result.first().addedAt, "同 id 保留最新一条")
        assertEquals(emptyList(), list.recentlyAdded(0))
    }
}
