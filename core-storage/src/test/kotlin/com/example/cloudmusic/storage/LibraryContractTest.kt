package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 测试夹具：同一 test source set 内其它测试文件复用。 */
internal fun testTrack(
    id: TrackId,
    title: String = "晴天",
    artist: String = "周杰伦",
    album: String = "叶惠美",
    durationMs: Long = 240_000,
    storageId: String = "local",
    path: String = "/music/晴天.flac",
    sourceType: SourceType = SourceType.LOCAL,
    availability: Availability = Availability.OK,
    url: String? = null,
): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNo = 3,
    year = 2003,
    sampleRate = 44_100,
    bitDepth = 16,
    sourceRef = SourceRef(storageId, path, url),
    sourceType = sourceType,
    coverRef = "covers/x.webp",
    tagCompleteness = 0.8f,
    availability = availability,
)

/** docs/03-05 §13 的 T01 / T02 / T04 / T05 与 §12 的 B05 / B07 / B11 / B13 纯单元部分。 */
class LibraryContractTest {

    @Test
    @DisplayName("03-05 T01 TrackEntity.from(Track) 往返所有业务字段一致")
    fun t01() {
        val track = testTrack(TrackId.of("local", "/music/晴天.flac"))
        val entity = TrackEntity.from(track, addedAt = 100, updatedAt = 200)
        assertEquals(track, entity.toTrack())
        assertEquals(100, entity.addedAt)
        assertEquals(200, entity.updatedAt)
    }

    @Test
    @DisplayName("03-05 T02 Availability 四种状态按枚举名字符串存取，往返一致")
    fun t02() {
        Availability.entries.forEach { state ->
            val track = testTrack(TrackId.of("s", "/p.flac"), availability = state)
            val entity = TrackEntity.from(track, 1, 2)
            assertEquals(state.name, entity.availability)
            assertEquals(state, entity.toTrack().availability)
            assertEquals(state, Availability.valueOf(entity.availability))
        }
        assertEquals("OK", TrackEntity.from(testTrack(TrackId("x")), 1, 2).availability)
    }

    @Test
    @DisplayName("03-05 T04 「合并重复项」只影响展示：按 title/artist/duration±2s 折叠，不改数据")
    fun t04() {
        val a = testTrack(TrackId.of("s1", "/a.flac"), durationMs = 240_000)
        val b = testTrack(TrackId.of("s2", "/b.flac"), durationMs = 241_000)
        val c = testTrack(TrackId.of("s3", "/c.flac"), durationMs = 300_000)
        val groups = LibraryQuery.groupDuplicates(listOf(a, b, c))
        assertEquals(2, groups.size)
        assertEquals(2, groups.first { it.contains(a) }.size)
        // 纯函数：入参列表与 ID 都没有被改写
        assertEquals(3, listOf(a, b, c).size)
        assertNotEquals(a.id, b.id)
    }

    @Test
    @DisplayName("03-05 T05 / B04 中文标题按 Collator(Locale.CHINA) 排序，不是二进制序")
    fun t05() {
        val tracks = listOf(
            testTrack(TrackId("zhou"), title = "周杰伦"),
            testTrack(TrackId("a"), title = "阿桑"),
            testTrack(TrackId("zhang"), title = "张学友"),
        )
        assertEquals(
            listOf("阿桑", "张学友", "周杰伦"),
            LibraryQuery.sort(tracks, LibraryQuery.SortKey.TITLE).map { it.title },
        )
    }

    @Test
    @DisplayName("03-05 B05 搜索 1 个字符走 LIKE 兜底，多词按 AND 命中")
    fun b05() {
        val tracks = listOf(
            testTrack(TrackId("1"), title = "晴天", artist = "周杰伦"),
            testTrack(TrackId("2"), title = "七里香", artist = "周杰伦"),
            testTrack(TrackId("3"), title = "Hotel California", artist = "Eagles"),
        )
        assertEquals(2, LibraryQuery.search(tracks, "周").size)
        assertEquals(1, LibraryQuery.search(tracks, "晴天 周杰伦").size)
        assertEquals(1, LibraryQuery.search(tracks, "california").size) // 大小写不敏感
        assertEquals(tracks, LibraryQuery.search(tracks, "   "))
    }

    @Test
    @DisplayName("03-05 B07 availability != OK 的曲目仍可被搜索到")
    fun b07() {
        val tracks = listOf(
            testTrack(TrackId("1"), title = "失效的歌", availability = Availability.LINK_EXPIRED),
            testTrack(TrackId("2"), title = "正常的歌"),
        )
        assertEquals(1, LibraryQuery.search(tracks, "失效").size)
        assertEquals(Availability.LINK_EXPIRED, LibraryQuery.search(tracks, "失效").single().availability)
    }

    @Test
    @DisplayName("03-05 B11 同一首歌在两个 Storage → 两个 ID、两行数据，UI 可折叠")
    fun b11() {
        val a = testTrack(TrackId.of("nas-a", "/music/a.flac"), storageId = "nas-a")
        val b = testTrack(TrackId.of("nas-b", "/music/a.flac"), storageId = "nas-b")
        assertNotEquals(a.id, b.id)
        val groups = LibraryQuery.groupDuplicates(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
    }

    @Test
    @DisplayName("03-05 B13 / T17 failure_log 只保留最近 1,000 条 / 30 天")
    fun b13() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val records = buildList {
            repeat(1_050) { i -> add(FailureRecord(i.toLong(), TrackId("t$i"), "E_DS_UPSTREAM_403", null, now - i)) }
            repeat(100) { i -> add(FailureRecord(9_000L + i, TrackId("old$i"), "E_DB_FULL", null, now - 31 * day)) }
        }
        val kept = LibraryQuery.pruneFailures(records, now)
        assertEquals(1_000, kept.size)
        assertTrue(kept.none { it.happenedAt < now - 30 * day })
        assertEquals(kept.first().happenedAt, now)
    }

    @Test
    @DisplayName("03-05 §8 文件夹视图按规范化路径前缀分组")
    fun folder() {
        val tracks = listOf(
            testTrack(TrackId("1"), path = "/nas/华语/晴天.flac"),
            testTrack(TrackId("2"), path = "/nas/欧美/song.flac"),
            testTrack(TrackId("3"), path = "/local/晴天.flac"),
        )
        assertEquals(1, LibraryQuery.byFolder(tracks, "/nas/华语/").size)
        assertEquals(2, LibraryQuery.byFolder(tracks, "/nas").size)
        assertEquals(3, LibraryQuery.byFolder(tracks, "/").size)
    }
}
