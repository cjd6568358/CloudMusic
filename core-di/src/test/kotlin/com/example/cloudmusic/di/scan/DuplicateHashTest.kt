package com.example.cloudmusic.di.scan

import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.storage.InMemoryTrackRepository
import com.example.cloudmusic.di.scan.PcmDecoder
import com.example.cloudmusic.di.scan.PcmData
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

    // ------------------------------------------------------------------
    // §61.250：跨次指纹记忆累进——限数逐次推进 + 命中零 IO 复用 + 失败不记忆重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§61.250 S4 · memo 累进：三次限数跑覆盖 6 候选；命中轮零 IO；确认组随累计升级")
    fun memoProgression() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "甲", path = "/1.flac"),
            track(2, "甲", path = "/2.flac"),
            track(3, "甲", path = "/3.flac", durationMs = 200_500L),
            track(4, "甲", path = "/4.flac", durationMs = 201_000L),
            track(5, "乙", path = "/5.flac"),
            track(6, "乙", path = "/6.flac"),
        ))
        val hasher = ScriptedHasher((1..6).associate { "/$it.flac" to "h$it" })
        val memo = HashMap<String, String>()
        val session = DuplicateHashSession(repo, hasher)
        // 轮 1：前 2 个
        val r1 = session.detect(maxHashCount = 2, memo = memo)
        assertEquals(2, r1.hashedCount); assertEquals(0, r1.memoHits); assertEquals(2, r1.memoSize)
        assertEquals(0, r1.confirmed.size, "前 2 候选分属不同指纹 → 无确认")
        // 轮 2：命中 2 + 新算 2
        val r2 = session.detect(maxHashCount = 2, memo = memo)
        assertEquals(2, r2.memoHits, "命中 memo 零 IO 复用")
        assertEquals(2, r2.hashedCount)
        assertEquals(4, r2.memoSize)
        // 轮 3：全部覆盖，确认组升级（甲组 4 份同指纹？——h1~h4 各异 → 仍无确认；乙组 2 份 h5/h6 异）
        val r3 = session.detect(maxHashCount = 2, memo = memo)
        assertEquals(4, r3.memoHits)
        assertEquals(6, r3.memoSize)
        assertEquals(0, r3.confirmed.size, "指纹各异（fixture 设计）无确认组")
        assertEquals(6, r3.candidates)
        assertEquals(6, hasher.calls.size, "三轮合计恰好每个候选算一次")
    }

    @Test
    @DisplayName("§61.250 S5 · 失败不记忆：下轮自动重试并升级确认组")
    fun failureNotMemoizedRetried() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "丙", path = "/1.flac"),
            track(2, "丙", path = "/2.flac"),
        ))
        var healthy = false
        val hasher = TrackContentHasher { track ->
            if (!healthy) throw IllegalStateException("驱动未恢复")
            "same-hash"
        }
        val memo = HashMap<String, String>()
        val session = DuplicateHashSession(repo, hasher)
        val r1 = session.detect(memo = memo)
        assertEquals(2, r1.hashFailed, "轮 1 全失败")
        assertEquals(0, r1.memoSize, "失败不记忆")
        healthy = true   // 驱动恢复
        val r2 = session.detect(memo = memo)
        assertEquals(2, r2.hashedCount, "轮 2 重试成功")
        assertEquals(1, r2.confirmed.size, "同指纹 → 确认组升级")
        assertEquals(2, r2.confirmed[0].items.size)
    }

    // ------------------------------------------------------------------
    // §61.256：Tier3 声纹确认趟（fake decoder 注入）
    // ------------------------------------------------------------------

    /** 合成 PCM：同指纹因子 = 同源；不同因子 = 异源（复用 core-model 夹具思路）。 */
    private fun synthPcm(base: Double): FloatArray {
        val rate = 8000
        val n = rate * 10
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (kotlin.math.sin(2 * Math.PI * 220.0 * base * t) * 0.5 +
                kotlin.math.sin(2 * Math.PI * 440.0 * base * t) * 0.5).toFloat()
        }
    }

    @Test
    @DisplayName("§61.256 S6 · 声纹趟：同谱候选升级 fingerprintConfirmed；不可解码计 failed")
    fun fingerprintSessionUpgrades() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(
            track(1, "甲", path = "/a.mp3"),
            track(2, "甲", path = "/b.mp3"),
            track(3, "甲", path = "/c.mp3"),
        ))
        // 解码器：/a 与 /b 同谱（转码拷贝），/c 异谱，/d 不可解码
        val decoder = PcmDecoder { t, _ ->
            when (t.sourceRef.path) {
                "/a.mp3", "/b.mp3" -> PcmData(synthPcm(1.0), 8000)
                "/c.mp3" -> PcmData(synthPcm(1.5), 8000)
                else -> null
            }
        }
        val session = DuplicateScanSession(repo)
        val base = session.detect()
        assertEquals(1, base.groups.size, "Tier2 疑似组（3 份同键）")
        val fp = FingerprintSession(repo, decoder).detect(base, maxCount = 20)
        assertEquals(1, fp.fingerprintConfirmed.size, "同谱对升级声纹组")
        assertEquals(2, fp.fingerprintConfirmed[0].items.size, "/a+/b 同谱聚合")
        assertEquals(3, fp.fingerprintedCount, "三候选全部解码")
        assertEquals(0, fp.fingerprintFailed)
    }

    @Test
    @DisplayName("§61.256 S7 · 不可解码 → fingerprintFailed 如实计数（不伪造声纹）")
    fun undecodableCounted() = runTest {
        val repo = InMemoryTrackRepository()
        repo.upsertBatch(listOf(track(1, "甲", path = "/1.flac"), track(2, "甲", path = "/2.flac")))
        val base = DuplicateScanSession(repo).detect()
        val fp = FingerprintSession(repo, PcmDecoder { _, _ -> null }).detect(base)
        assertEquals(2, fp.fingerprintFailed)
        assertEquals(0, fp.fingerprintConfirmed.size)
    }
}
