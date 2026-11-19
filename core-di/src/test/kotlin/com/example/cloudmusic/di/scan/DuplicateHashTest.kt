package com.example.cloudmusic.di.scan

import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.storage.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.248：A1 Tier1 内容哈希确认趟——分块流式哈希 + 会话限数/跳过/确认分组。 */
class DuplicateHashTest {

    // ---- ContentHashing 纯逻辑 ------------------------------------------------

    @Test
    @DisplayName("§61.248 H1 · hashChunks：分块聚合指纹 = 整体 SHA-256；EOF 停止")
    fun hashChunksMatchesWhole() = runTest {
        val content = ByteArray(700_000) { (it % 251).toByte() }   // 跨 3 块（256KB）
        val expected = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        val out = ContentHashing.hashChunks(readChunk = { offset, length ->
            if (offset >= content.size) null
            else content.copyOfRange(offset.toInt(), minOf(offset + length, content.size.toLong()).toInt())
        })
        assertEquals(expected, out, "分块流式 digest 与整体一致")
    }

    @Test
    @DisplayName("§61.248 H2 · 首读即空 → null（不伪造空指纹）")
    fun emptyContentNull() = runTest {
        assertNull(ContentHashing.hashChunks(readChunk = { _, _ -> ByteArray(0) }))
        assertNull(ContentHashing.hashChunks(readChunk = { _, _ -> null }))
    }

    // ---- DuplicateHashSession 会话 --------------------------------------------

    private fun track(id: Int, title: String, path: String, durationMs: Long = 200_000L) = Track(
        id = TrackId("id-$id"), title = title, artist = "窦唯", album = "黑梦",
        durationMs = durationMs, sourceRef = SourceRef("s", path), sourceType = SourceType.LOCAL,
    )

    /** 脚本化哈希器：按路径返回指纹或 null（不可读）。 */
    private class ScriptedHasher(val map: Map<String, String?>) : TrackContentHasher {
        val calls = mutableListOf<String>()
        override suspend fun hash(track: Track): String? {
            calls += track.sourceRef.path
            return map[track.sourceRef.path]
        }
    }

    @Test
    @DisplayName("§61.248 S1 · 确认趟：同指纹升级精确确认组；异指纹留在疑似层")
    fun confirmedGrouping() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "黑色梦中", path = "/a/1.flac"),
            track(2, "黑色梦中", path = "/b/2.flac"),
            track(3, "黑色梦中", path = "/c/3.flac", durationMs = 201_000L),
        ))
        // 1 与 2 同指纹（真重复）；3 不同指纹（同名单曲另一版）
        val hasher = ScriptedHasher(mapOf(
            "/a/1.flac" to "h1", "/b/2.flac" to "h1", "/c/3.flac" to "h3",
        ))
        val report = DuplicateHashSession(repo, hasher).detect()
        assertEquals(1, report.confirmed.size, "同指纹组升级确认")
        assertEquals(2, report.confirmed[0].items.size)
        assertEquals(1, report.groups.size, "疑似层保留全量（确认是其子集视图）")
        assertEquals(3, report.candidates, "疑似组候选文件去重计数")
        assertEquals(3, report.hashedCount)
        assertEquals(0, report.hashFailed)
    }

    @Test
    @DisplayName("§61.248 S2 · 限数：候选超 maxHashCount 只算前 N；不可读计 hashFailed")
    fun limitAndSkip() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "甲", path = "/1.flac"),
            track(2, "甲", path = "/2.flac"),
            track(3, "甲", path = "/3.flac", durationMs = 200_500L),
            track(4, "甲", path = "/4.flac", durationMs = 201_000L),
        ))
        val hasher = ScriptedHasher(mapOf("/1.flac" to "h1", "/2.flac" to null))
        val report = DuplicateHashSession(repo, hasher).detect(maxHashCount = 2)
        assertEquals(listOf("/1.flac", "/2.flac"), hasher.calls, "只尝试前 2 个候选")
        assertEquals(4, report.candidates)
        assertEquals(1, report.hashedCount, "仅可读 1 个")
        assertEquals(1, report.hashFailed, "null = 不可读如实计数")
        assertEquals(0, report.confirmed.size, "同指纹不足 2 → 无确认组")
    }

    @Test
    @DisplayName("§61.248 S3 · 哈希器抛异常 = hashFailed（runCatching 兜底不炸会话）")
    fun hasherThrowIsSkipped() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(track(1, "甲", path = "/1.flac"), track(2, "甲", path = "/2.flac")))
        val hasher = TrackContentHasher { throw IllegalStateException("boom") }
        val report = DuplicateHashSession(repo, hasher).detect()
        assertEquals(2, report.hashFailed)
        assertEquals(0, report.hashedCount)
        assertEquals(0, report.confirmed.size)
    }
}
