package com.example.cloudmusic.di.db

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.scanner.CandidateKind
import com.example.cloudmusic.scanner.ScanSnapshot
import java.text.Collator
import java.util.Comparator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第十七批（`12 §26`，T-A1）Room 层的**纯 JVM 契约测试**。
 *
 * 与 `ProductionAdaptersTest` 同型：不实例化 `Context` / `RoomDatabase` / Android 框架类，
 * 只断言可在 JVM 上判定的部分（schema 清单、Migration SQL、转义、排序键、模型映射、
 * upsert 保留语义、事务分批）。DAO 的 SQL 编译期校验由 KSP（`./gradlew test`）负责，
 * `MigrationTestHelper` 的 instrumentation 测试属 T-V 验收线，见 `12 §26.2`。
 */
class LibraryRoomContractTest {

    private fun track(
        id: String = "t1",
        title: String = "晴天",
        url: String? = null,
    ): Track = Track(
        id = TrackId(id),
        title = title,
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000L,
        trackNo = 3,
        year = 2003,
        sampleRate = 44_100,
        bitDepth = 16,
        sourceRef = SourceRef("nas-1", "音乐/周杰伦/晴天.flac", url),
        sourceType = SourceType.WEBDAV,
        coverRef = "/covers/1.jpg",
        tagCompleteness = 0.8f,
    )

    private class RecordingSql : SqlExecutor {
        val statements = mutableListOf<String>()
        override fun exec(sql: String) {
            statements.add(sql)
        }
    }

    // ---- 03-05 §2 / §3：schema ------------------------------------------------

    @Test
    fun `T-A1-01 schema has 9 physical tables plus FTS4`() {
        assertEquals(9, LibrarySchema.PHYSICAL_TABLES.size)
        assertEquals(9, LibrarySchema.PHYSICAL_TABLES.toSet().size)
        assertTrue("tracks" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("playlists" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("playlist_tracks" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("favorites" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("play_history" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("failure_log" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("scan_snapshot" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("queue_items" in LibrarySchema.PHYSICAL_TABLES)
        assertTrue("playback_state" in LibrarySchema.PHYSICAL_TABLES)
        assertEquals(listOf("tracks_fts"), LibrarySchema.VIRTUAL_TABLES)
        assertEquals("tracks_fts", LibrarySchema.FTS_TABLE)
        assertTrue("idx_tracks_sort_title" in LibrarySchema.INDEXES)
        assertTrue("idx_pt_track" in LibrarySchema.INDEXES)
        assertTrue("idx_failure_time" in LibrarySchema.INDEXES)
        assertTrue("idx_queue_track" in LibrarySchema.INDEXES)
    }

    @Test
    fun `T-A1-02 database version and migration range`() {
        assertEquals(1, LibrarySchema.VERSION_V1)
        assertEquals(2, LibrarySchema.VERSION_V2)
        assertEquals(LibrarySchema.VERSION_V2, LibrarySchema.VERSION)
        assertEquals("cloudmusic.db", LibrarySchema.DATABASE_NAME)
        assertEquals(1, LibraryMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, LibraryMigrations.MIGRATION_1_2.endVersion)
        assertEquals(1, LibraryMigrations.ALL.size)
    }

    // ---- 03-05 §10 / T13 / T14 / B10：Migration ------------------------------

    @Test
    fun `T-A1-03 migration SQL is executed through the shared executor`() {
        val recorder = RecordingSql()
        MigrationSql.apply(recorder)
        assertEquals(MigrationSql.V1_TO_V2, recorder.statements)
        assertEquals(2, recorder.statements.size)
        assertEquals(MigrationSql.ADD_SORT_TITLE, recorder.statements[0])
        assertEquals(MigrationSql.CREATE_SORT_INDEX, recorder.statements[1])
    }

    @Test
    fun `T-A1-04 migration is strictly additive and keeps old rows`() {
        assertTrue(MigrationSql.isNonDestructive())
        val add = MigrationSql.ADD_SORT_TITLE
        val addUpper = add.uppercase()
        assertTrue(add.startsWith("ALTER TABLE tracks ADD COLUMN sort_title"))
        assertTrue("NOT NULL" in addUpper)
        assertTrue("DEFAULT ''" in add)
        assertTrue("CREATE INDEX IF NOT EXISTS" in MigrationSql.CREATE_SORT_INDEX.uppercase())
        assertFalse(MigrationSql.isNonDestructive(listOf("DROP TABLE tracks")))
        assertFalse(MigrationSql.isNonDestructive(emptyList()))
    }

    // ---- 03-05 §8：FTS / LIKE / 中文排序 -------------------------------------

    @Test
    fun `T-A1-05 FTS MATCH phrase is escaped and tokenized`() {
        assertEquals("\"晴天\" \"周杰伦\"", FtsQuery.match("晴天 周杰伦"))
        assertEquals("\"a\"\"b\"", FtsQuery.match("a\"b"))
        assertEquals("", FtsQuery.match("   "))
    }

    @Test
    fun `T-A1-06 short queries fall back to LIKE`() {
        assertTrue(FtsQuery.isShortQuery("周"))
        assertTrue(FtsQuery.isShortQuery(" "))
        assertFalse(FtsQuery.isShortQuery("周杰"))
        assertFalse(FtsQuery.isShortQuery("ab"))
    }

    @Test
    fun `T-A1-07 LIKE wildcards in user input are escaped`() {
        assertEquals("100\\% \\_ \\\\", LikePattern.escape("100% _ \\"))
        assertEquals("晴天", LikePattern.escape("晴天"))
    }

    @Test
    fun `T-A1-08 sort keys follow the Chinese collator`() {
        val collator = Collator.getInstance(Locale.CHINA)
        val words = listOf("周杰伦", "阿杜", "陈奕迅", "Beyond")
        val expected = words.sortedWith(Comparator { a, b -> collator.compare(a, b) })
        val actual = words.sortedBy { SortKeys.of(it) }
        assertEquals(expected, actual)
        assertEquals(SortKeys.of("晴天"), SortKeys.of("晴天"))
    }

    // ---- 03-05 §6 / §14 #3 / C-04：upsert 语义 -------------------------------

    @Test
    fun `T-A1-09 upsert preserves availability and addedAt`() {
        val original = track()
        val existing = TrackRow.from(original, null, 100L).copy(
            availability = Availability.LINK_EXPIRED.name,
            addedAt = 42L,
        )
        val merged = TrackRow.from(original, existing, 999L)
        assertEquals(Availability.LINK_EXPIRED.name, merged.availability)
        assertEquals(42L, merged.addedAt)
        assertEquals(999L, merged.updatedAt)
        assertTrue(merged.sortTitle.isNotEmpty())
    }

    @Test
    fun `T-A1-10 new row uses the track state and round-trips all fields`() {
        val source = track(url = "https://user:p%40ss@host/a?x=中文")
        val row = TrackRow.from(source, null, 1_000L)
        assertEquals(Availability.OK.name, row.availability)
        assertEquals(SourceType.WEBDAV.name, row.sourceType)
        assertEquals(1_000L, row.addedAt)
        assertEquals(source, row.toTrack())
        assertEquals(source, row.toEntity().toTrack())
    }

    @Test
    fun `T-A1-11 upsert is chunked into 500-row transactions`() {
        assertEquals(0, UpsertPlanner.chunks(emptyList()).size)
        val batch = List(7_500) { track("t$it") }
        val chunks = UpsertPlanner.chunks(batch)
        assertEquals(15, chunks.size)
        assertTrue(chunks.all { it.size == 500 })
        assertEquals(1, UpsertPlanner.chunks(batch.take(1)).size)
        assertFailsWith<IllegalArgumentException> { UpsertPlanner.chunks(batch, 0) }
    }

    // ---- 03-06 §8.1 / 03-04 §8：跨模块模型映射 -------------------------------

    @Test
    fun `T-A1-12 SourceRef JSON round-trip keeps special characters and null`() {
        val ref = SourceRef("nas:1", "音乐/周杰伦/晴天 1.flac", "https://u:p@host/p?x=中文")
        assertEquals(ref, ref.toJson().toSourceRef())
        val withoutUrl = SourceRef("local", "/a/b.flac")
        assertEquals(withoutUrl, withoutUrl.toJson().toSourceRef())
    }

    @Test
    fun `T-A1-13 scan snapshot row round-trip keeps enum by name`() {
        val snapshot = ScanSnapshot("nas-1", "/a/b.flac", 1_024L, 2_048L, CandidateKind.STRM, 9_999L)
        val row = ScanSnapshotRow.from(snapshot)
        assertEquals("STRM", row.kind)
        assertEquals(snapshot, row.toModel())
    }

    @Test
    fun `T-A1-14 queue and playback state rows map back to models`() {
        val ref = SourceRef("nas-1", "/a/b.flac")
        val item = QueueItemRow("default", 2, "t1", ref.toJson(), 7L).toModel()
        assertEquals(2, item.position)
        assertEquals(TrackId("t1"), item.trackId)
        assertEquals(ref, item.sourceRef)
        assertEquals("default", item.queueId)

        val state = PlaybackStateRow(1, "t1", 12_345L, "SHUFFLE", 8L).toModel()
        assertEquals("t1", state.currentTrackId)
        assertEquals(12_345L, state.positionMs)
        assertEquals("SHUFFLE", state.playMode.name)
        assertEquals(8L, state.updatedAt)
    }
}
