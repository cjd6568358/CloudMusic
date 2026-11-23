package com.example.cloudmusic.storage

import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §61.329（A-6）：CUE 分轨封面继承。
 *
 * 两个层次：
 * 1. [CoverInheritance.plan] 纯判定——分轨 id 与整轨不同、`(storageId, sourcePath)` 相同；
 * 2. [InMemoryTrackRepository.inheritCueSplitCovers] 端到端——写入、回填、幂等、不覆盖。
 */
class CoverInheritanceTest {

    private fun row(
        id: String,
        path: String = "/music/专辑.flac",
        storageId: String = "local",
        cover: String? = null,
    ) = CoverInheritance.Row(id, storageId, path, cover)

    @Test
    @DisplayName("整轨有封面 → 同路径分轨全部继承（分轨 id 是锚点哈希，与整轨不同）")
    fun splitsInheritFromWholeTrack() {
        val plan = CoverInheritance.plan(
            listOf(
                row("whole", cover = "/covers/a.webp"),
                row("split-1"),
                row("split-2"),
                row("split-3"),
            ),
        )
        assertEquals(3, plan.size)
        assertEquals("/covers/a.webp", plan["split-1"])
        assertEquals("/covers/a.webp", plan["split-3"])
        assertTrue("whole" !in plan, "有封面的行不该出现在计划里（否则会自我覆盖）")
    }

    @Test
    @DisplayName("分轨自己已有封面（刮削过）→ 不覆盖，也不当 donor 传染错封面")
    fun keepsExistingCover() {
        val plan = CoverInheritance.plan(
            listOf(
                row("whole", cover = "/covers/a.webp"),
                row("split-1", cover = "/covers/scraped.webp"),
            ),
        )
        assertTrue(plan.isEmpty(), "两行都有封面，无可继承项：$plan")
    }

    @Test
    @DisplayName("同路径但跨 storage → 不互相继承（不同源可能挂不同图）")
    fun doesNotCrossStorage() {
        val plan = CoverInheritance.plan(
            listOf(
                row("whole", storageId = "nas", cover = "/covers/nas.webp"),
                row("split", storageId = "local"),
            ),
        )
        assertTrue(plan.isEmpty(), "跨源串味：$plan")
    }

    @Test
    @DisplayName("空串按缺失处理（历史行可能是 '' 而不是 NULL）→ 被回填，但不当 donor")
    fun blankIsMissing() {
        val plan = CoverInheritance.plan(
            listOf(
                row("whole", cover = "/covers/a.webp"),
                row("blank-cover", cover = ""),
                // 唯一候选 donor 自己也是空白 → 不能把 '' 当封面传给同路径的 victim
                row("blank-donor", cover = "   ", path = "/music/另一张.flac"),
                row("victim", path = "/music/另一张.flac"),
            ),
        )
        assertEquals("/covers/a.webp", plan["blank-cover"])
        assertTrue("blank-donor" !in plan)
        assertTrue("victim" !in plan, "空白 donor 不许当封面来源：$plan")
    }

    // ---- 端到端（内存实现，与 Room 实现同一份判定） ----

    private fun track(id: TrackId, path: String, cover: String?) = Track(
        id = id,
        title = "曲",
        artist = "艺人",
        album = "专辑",
        durationMs = 240_000,
        sourceRef = SourceRef("local", path, null),
        sourceType = SourceType.LOCAL,
        coverRef = cover,
    )

    @Test
    @DisplayName("端到端：整轨 + 3 分轨 → 回填 3 行；再跑一次返回 0（幂等）")
    fun endToEndFillsAndIsIdempotent() = runTest {
        val repo = InMemoryTrackRepository()
        val audio = "/music/专辑.flac"
        val whole = TrackId.of("local", audio)
        val splits = (1..3).map { TrackId.of("local", "$audio#CUE#${it}") }
        repo.upsertBatch(
            listOf(track(whole, audio, "/covers/a.webp")) +
                splits.map { track(it, audio, null) },
        )

        assertEquals(3, repo.inheritCueSplitCovers())
        splits.forEach { assertEquals("/covers/a.webp", repo.get(it)?.coverRef) }
        assertEquals(0, repo.inheritCueSplitCovers(), "第二轮无缺口，必须返回 0")
    }

    @Test
    @DisplayName("端到端：父音频自己也没有封面 → 不虚构，分轨仍为 null")
    fun noDonorNoFill() = runTest {
        val repo = InMemoryTrackRepository()
        val audio = "/music/无封面.flac"
        repo.upsertBatch(
            listOf(
                track(TrackId.of("local", audio), audio, null),
                track(TrackId.of("local", "$audio#CUE#1"), audio, null),
            ),
        )
        assertEquals(0, repo.inheritCueSplitCovers())
        assertNull(repo.get(TrackId.of("local", "$audio#CUE#1"))?.coverRef)
    }

    @Test
    @DisplayName("端到端：普通曲目（路径唯一、无封面）不被波及")
    fun leavesNormalTracksAlone() = runTest {
        val repo = InMemoryTrackRepository()
        val covered = track(TrackId.of("local", "/a.flac"), "/a.flac", "/covers/a.webp")
        val bare = track(TrackId.of("local", "/b.flac"), "/b.flac", null)
        repo.upsertBatch(listOf(covered, bare))
        assertEquals(0, repo.inheritCueSplitCovers())
        assertNull(repo.get(bare.id)?.coverRef)
    }
}
