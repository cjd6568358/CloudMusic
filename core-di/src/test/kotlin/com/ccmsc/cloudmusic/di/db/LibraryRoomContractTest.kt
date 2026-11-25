package com.ccmsc.cloudmusic.di.db

import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.scanner.CandidateKind
import com.ccmsc.cloudmusic.scanner.ScanSnapshot
import java.text.Collator
import java.util.Comparator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
        addedAt: Long = 0L,
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
        addedAt = addedAt,
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
        // §61.120（T5-2 前置）：v3 增 tracks.replay_gain_db（分析增益落库）。
        assertEquals(3, LibrarySchema.VERSION_V3)
        // §61.204（A1-③）：v4 增 scan_snapshot.metadata_done（refresh stat 命中跳过依据）。
        assertEquals(4, LibrarySchema.VERSION_V4)
        // §61.212：v5 增 scan_snapshot.direct_url（STRM walk 免逐文件重读）。
        assertEquals(5, LibrarySchema.VERSION_V5)
        // §61.221：v6 增 tracks.cue_start_ms / cue_end_ms（CUE 分轨起止）。
        assertEquals(6, LibrarySchema.VERSION_V6)
        assertEquals(LibrarySchema.VERSION_V6, LibrarySchema.VERSION)
        assertEquals("cloudmusic.db", LibrarySchema.DATABASE_NAME)
        assertEquals(1, LibraryMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, LibraryMigrations.MIGRATION_1_2.endVersion)
        assertEquals(2, LibraryMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, LibraryMigrations.MIGRATION_2_3.endVersion)
        assertEquals(3, LibraryMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, LibraryMigrations.MIGRATION_3_4.endVersion)
        assertEquals(4, LibraryMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, LibraryMigrations.MIGRATION_4_5.endVersion)
        assertEquals(5, LibraryMigrations.MIGRATION_5_6.startVersion)
        assertEquals(6, LibraryMigrations.MIGRATION_5_6.endVersion)
        assertEquals(5, LibraryMigrations.ALL.size)
    }

    @Test
    fun `T-A1-02b v3 to v4 migration is additive with metadata_done default`() {
        val sql = MigrationSql.ADD_SNAPSHOT_METADATA_DONE
        assertTrue(sql.startsWith("ALTER TABLE scan_snapshot ADD COLUMN metadata_done"))
        assertTrue("NOT NULL" in sql.uppercase())
        assertTrue("DEFAULT 0" in sql)
        assertTrue(MigrationSql.isNonDestructive(MigrationSql.V3_TO_V4))
        val recorder = RecordingSql()
        MigrationSql.apply(recorder, MigrationSql.V3_TO_V4)
        assertEquals(listOf(MigrationSql.ADD_SNAPSHOT_METADATA_DONE), recorder.statements)
    }

    @Test
    fun `T-A1-02c v4 to v5 migration adds nullable direct_url`() {
        val sql = MigrationSql.ADD_SNAPSHOT_DIRECT_URL
        assertTrue(sql.startsWith("ALTER TABLE scan_snapshot ADD COLUMN direct_url"))
        assertTrue(MigrationSql.isNonDestructive(MigrationSql.V4_TO_V5))
        val recorder = RecordingSql()
        MigrationSql.apply(recorder, MigrationSql.V4_TO_V5)
        assertEquals(listOf(MigrationSql.ADD_SNAPSHOT_DIRECT_URL), recorder.statements)
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
    fun `T-A1-06b full-width space splits FTS tokens`() {
        // 全角空格不切分时，FTS 收到的是一个跨列短语 ⇒ 两个词都在库里却零命中。
        assertEquals("\"晴天\" \"周杰伦\"", FtsQuery.match("晴天\u3000周杰伦"))
        assertEquals("\"晴天\" \"周杰伦\"", FtsQuery.match("\u3000晴天 \u3000周杰伦\u3000"))
        assertEquals("\"Hotel\" \"California\"", FtsQuery.match("Hotel\u00A0California"))
        // 尾随全角空格不得把 1 字查询抬成 2 字（否则会走 FTS 而丢掉 LIKE 兜底）
        assertTrue(FtsQuery.isShortQuery("周\u3000"))
        assertFalse(FtsQuery.isShortQuery("周\u3000杰"))
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
        val source = track(url = "https://user:p%40ss@host/a?x=中文", addedAt = 1_000L)
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
    // ---- §61.173：存量乱码标题修复（GBK 被当 Latin-1） ----------------------

    @Test
    fun `T-A1-15 plan repairs GBK-as-Latin-1 text on every field`() {
        val row = TrackTextRow(
            id = "t1",
            title = "²»ÊÇÒòÎª¼ÅÄ¯²ÅÏëÄã-t.r.y",
            artist = "ÁªÏµÈË:ÁõÃ÷¡î15895559019",
            album = "ËÕÖÝÌìµØÈË¿¨ºÅÍø",
            sortTitle = "",
        )
        val plan = LegacyTextRepairRunner.plan(row)!!
        assertEquals("不是因为寂寞才想你-t.r.y", plan.title)
        assertEquals("联系人:刘明☆15895559019", plan.artist)
        assertEquals("苏州天地人卡号网", plan.album)
        assertEquals("t1", plan.id)
    }

    @Test
    fun `T-A1-16 plan leaves already-correct text untouched`() {
        assertNull(LegacyTextRepairRunner.plan(TrackTextRow("t1", "晴天", "周杰伦", "叶惠美", "")))
        assertNull(LegacyTextRepairRunner.plan(TrackTextRow("t2", "一辈子的孤单", "刘若英", "涩女郎", "")))
        // 真 Latin-1 文本（Café）在 GB18030 下解码失败 ⇒ 必须原样保留，不能被误伤。
        assertNull(LegacyTextRepairRunner.plan(TrackTextRow("t3", "Café del Mar", "Various", "Nice", "")))
    }

    @Test
    fun `T-A1-17 TrackRow_from repairs text at the single ingest point`() {
        val dirty = Track(
            id = TrackId("t9"),
            title = "Ò»±²×ÓµÄ¹Âµ¥",
            artist = "ÁõÈôÓ¢",
            album = "É¬Å®ÀÉµçÊÓ¾çÔ\u00ADÉù´ø",
            durationMs = 1L,
            sourceRef = SourceRef("strm-1", "/刘若英 - 一辈子的孤单.strm"),
            sourceType = SourceType.STRM,
            coverRef = null,
            tagCompleteness = 0.2f,
        )
        val row = TrackRow.from(dirty, null, 1L)
        assertEquals("一辈子的孤单", row.title)
        assertEquals("刘若英", row.artist)
        assertEquals("涩女郎电视剧原声带", row.album)
        assertEquals(SortKeys.of("一辈子的孤单"), row.sortTitle)
    }
}
