package com.example.cloudmusic.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §61.247：A1 Tier2 疑似重复分组（纯 JVM 零 IO）——边界矩阵。 */
class DuplicateGrouperTest {

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
    @DisplayName("§61.247 G1 · 同键同长（不同路径/来源）→ 一组 ≥2")
    fun exactDuplicateGrouped() {
        val out = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "黑色梦中"), track(2, "黑色梦中", path = "/nas/1.flac")),
        )
        assertEquals(1, out.size)
        assertEquals(2, out[0].items.size)
        assertEquals("/music/1.flac", out[0].items[0].sourceRef.path, "组内按路径排序")
    }

    @Test
    @DisplayName("§61.247 G2 · 时长容差：差 ≤1000ms 同簇；>1000ms 分簇")
    fun durationTolerance() {
        val same = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "甲", durationMs = 200_000L), track(2, "甲", durationMs = 201_000L)),
        )
        assertEquals(1, same.size, "差 1000ms = 边界内同簇")
        val diff = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "乙", durationMs = 200_000L), track(2, "乙", durationMs = 201_001L)),
        )
        assertEquals(0, diff.size, "差 1001ms = 超 tolerance 分簇（单例不输出）")
    }

    @Test
    @DisplayName("§61.247 G3 · 归一化：大小写/空白折叠命中；艺人空串参与分组")
    fun normalization() {
        val out = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "Black  Dream", artist = "dou wei"), track(2, "black dream", artist = "Dou Wei")),
        )
        assertEquals(1, out.size, "大小写+空白折叠后同键")
        val blank = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "无名曲", artist = ""), track(2, "无名曲", artist = "  ")),
        )
        assertEquals(1, blank.size, "「未知艺人」同曲互为疑似")
    }

    @Test
    @DisplayName("§61.247 G4 · 同名不同艺人不分组（保守不误报）")
    fun differentArtistNotGrouped() {
        val out = DuplicateGrouper.suspectedDuplicates(
            listOf(track(1, "梦", artist = "A"), track(2, "梦", artist = "B")),
        )
        assertEquals(0, out.size)
    }

    @Test
    @DisplayName("§61.247 G5 · 单例排除 / 空库空表 / 输出确定性")
    fun determinismAndSingletons() {
        val many = listOf(
            track(1, "乙"), track(2, "乙", path = "/n/2.flac"),
            track(3, "甲"), track(4, "甲", path = "/n/4.flac"),
        )
        val out = DuplicateGrouper.suspectedDuplicates(many)
        assertEquals(listOf("乙", "甲"), out.map { it.title }, "组按标题码点排序（确定性）")
        assertTrue(out.all { it.items.size >= 2 })
        assertEquals(0, DuplicateGrouper.suspectedDuplicates(emptyList()).size)
        assertEquals(0, DuplicateGrouper.suspectedDuplicates(listOf(track(1, "唯一"))).size)
    }

    @Test
    @DisplayName("§61.247 G6 · Tier1 消费位：同 hash 分组、null hash 不参与")
    fun groupByHash() {
        val tracks = listOf(track(1, "甲"), track(2, "甲", path = "/n/2.flac"), track(3, "丙"))
        val out = DuplicateGrouper.groupByHash(tracks) { if (it.id.value == "id-3") null else "h-${it.durationMs}" }
        assertEquals(1, out.size)
        assertEquals(2, out[0].items.size)
        val noHash = DuplicateGrouper.groupByHash(tracks) { null }
        assertEquals(0, noHash.size, "全 null（未算出）不分组")
    }
}
