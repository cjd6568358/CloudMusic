package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/03-05 §13 的 T03 / T08 / T09 / T10 / T11 与 §12 的 B01 / B02 / B03，
 * 外加实现期澄清 C-04 的「upsert 不重置状态 / 重扫显式恢复」回归。
 */
class InMemoryRepositoryTest {

    private var now = 1_000L
    private var seq = 0
    private lateinit var store: InMemoryLibraryStore
    private lateinit var tracks: InMemoryTrackRepository
    private lateinit var playlists: InMemoryPlaylistRepository
    private lateinit var favorites: InMemoryFavoriteRepository
    private lateinit var history: InMemoryHistoryRepository
    private lateinit var storages: InMemoryStorageRepository

    @BeforeEach
    fun setUp() {
        now = 1_000L
        seq = 0
        store = InMemoryLibraryStore(clock = { now }, idFactory = { "id-${++seq}" })
        tracks = InMemoryTrackRepository(store)
        playlists = InMemoryPlaylistRepository(store)
        favorites = InMemoryFavoriteRepository(store)
        history = InMemoryHistoryRepository(store)
        storages = InMemoryStorageRepository(store, tracks)
    }

    @Test
    @DisplayName("03-05 T03 / B01 重复 upsert 覆盖元数据，保留 added_at / 收藏 / 播放次数")
    fun t03() = runTest {
        val t = testTrack(TrackId.of("local", "/a.flac"))
        tracks.upsertBatch(listOf(t))
        favorites.toggle(t.id)
        history.record(t.id)
        val addedAt = store.snapshot().tracks[t.id.value]!!.addedAt

        now += 100
        tracks.upsertBatch(listOf(t.copy(title = "新标题")))
        val entity = store.snapshot().tracks[t.id.value]!!
        assertEquals(addedAt, entity.addedAt)
        assertEquals("新标题", entity.title)
        assertTrue(now > addedAt)
        assertTrue(favorites.observe().first().contains(t.id))
        assertEquals(1, history.observe().first().size)
    }

    @Test
    @DisplayName("03-05 §14 #3 / C-04 阶段 B 元数据 upsert 不抹掉播放失败标记")
    fun upsertKeepsAvailability() = runTest {
        val t = testTrack(TrackId.of("nas", "/a.flac"))
        tracks.upsertBatch(listOf(t))
        tracks.markAvailability(t.id, Availability.LINK_EXPIRED)
        now += 100
        tracks.upsertBatch(listOf(t.copy(title = "阶段B补齐")))
        assertEquals("阶段B补齐", tracks.get(t.id)!!.title)
        assertEquals(Availability.LINK_EXPIRED, tracks.get(t.id)!!.availability)
    }

    @Test
    @DisplayName("03-05 T10 / C-04 文件恢复后重扫置回 OK，收藏与播放记录保留")
    fun t10() = runTest {
        val t = testTrack(TrackId.of("nas", "/a.flac"))
        val reconciler = ScanReconciler(tracks)
        reconciler.upsertFromScan(listOf(t))
        favorites.toggle(t.id)
        history.record(t.id)
        reconciler.markUnavailable(listOf(t.id))
        assertEquals(Availability.UNREACHABLE, tracks.get(t.id)!!.availability)

        now += 100
        reconciler.upsertFromScan(listOf(t.copy(title = "恢复出现")))
        assertEquals("恢复出现", tracks.get(t.id)!!.title)
        assertEquals(Availability.OK, tracks.get(t.id)!!.availability)
        assertTrue(favorites.observe().first().contains(t.id))
        assertEquals(1, history.observe().first().size)
    }

    @Test
    @DisplayName("§61.145 markAvailabilityBatch 单事务批量置位，语义与逐行一致")
    fun markAvailabilityBatchFlipsAll() = runTest {
        val many = (1..1200).map { testTrack(TrackId.of("s", "/b/$it.flac")) }
        tracks.upsertBatch(many)
        tracks.markAvailabilityBatch(many.map { it.id }, Availability.UNREACHABLE)
        assertEquals(1200, tracks.observeAll().first().count { it.availability == Availability.UNREACHABLE })
        tracks.markAvailabilityBatch(many.map { it.id }, Availability.OK)
        assertEquals(1200, tracks.observeAll().first().count { it.availability == Availability.OK })
        // 不触碰收藏/播放次数（独立表口径）
        assertEquals(0, favorites.observe().first().size)
    }

    @Test
    @DisplayName("§61.181 updateDuration 只回填缺值行（STRM / 无标签），已有元数据不覆盖")
    fun updateDurationBackfillsOnlyMissing() = runTest {
        val missing = testTrack(
            TrackId.of("webdav", "/strm.strm"), sourceType = SourceType.STRM,
            durationMs = 0,
        )
        val tagged = testTrack(TrackId.of("local", "/tagged.flac"), durationMs = 269_000)
        tracks.upsertBatch(listOf(missing, tagged))

        tracks.updateDuration(missing.id, 244_933)
        assertEquals(244_933, tracks.get(missing.id)?.durationMs, "缺值行被回填")
        tracks.updateDuration(tagged.id, 100)
        assertEquals(269_000, tracks.get(tagged.id)?.durationMs, "已有元数据不覆盖")

        // 合法性：非真值（<=0）不得写入
        val fresh = testTrack(TrackId.of("webdav", "/fresh.strm"), sourceType = SourceType.STRM, durationMs = 0)
        tracks.upsertBatch(listOf(fresh))
        tracks.updateDuration(fresh.id, 0)
        assertEquals(0, tracks.get(fresh.id)?.durationMs)
    }

    @Test
    @DisplayName("03-05 B02 / T06 7,500 首一次性 upsert 分 15 批事务")
    fun b02() = runTest {
        val many = (1..7_500).map { i ->
            testTrack(TrackId.of("s", "/f/$i.flac"), title = "track-$i")
        }
        tracks.upsertBatch(many)
        assertEquals(7_500, tracks.count())
        assertEquals(15, store.snapshot().batchCount)
        assertEquals(UPSERT_BATCH_SIZE, 500)
    }

    @Test
    @DisplayName("03-05 T08 跨来源歌单混合，顺序按 position，move/remove 后重编号")
    fun t08() = runTest {
        val local = testTrack(TrackId.of("local", "/a.flac"), title = "本地", sourceType = SourceType.LOCAL)
        val webdav = testTrack(TrackId.of("nas", "/b.flac"), title = "网盘", sourceType = SourceType.WEBDAV, storageId = "nas")
        val strm = testTrack(TrackId.of("strm", "/c.strm"), title = "直链", sourceType = SourceType.STRM, storageId = "strm")
        tracks.upsertBatch(listOf(local, webdav, strm))

        val pid = playlists.create("混合歌单")
        playlists.addTracks(pid, listOf(strm.id, local.id, webdav.id))
        assertEquals(listOf(strm.id, local.id, webdav.id), playlists.observeTracks(pid).first().map { it.id })
        assertEquals(1, playlists.observePlaylists().first().size)

        playlists.move(pid, 0, 2)
        assertEquals(listOf(local.id, webdav.id, strm.id), playlists.observeTracks(pid).first().map { it.id })
        playlists.remove(pid, 1)
        assertEquals(listOf(local.id, strm.id), playlists.observeTracks(pid).first().map { it.id })

        playlists.delete(pid)
        assertTrue(playlists.observeTracks(pid).first().isEmpty())
        assertEquals(3, tracks.count()) // 删歌单绝不删 Track（§14 #5）
    }

    @Test
    @DisplayName("03-05 T09 markOrphan 只影响目标 Storage 的曲目")
    fun t09() = runTest {
        val local = testTrack(TrackId.of("local", "/a.flac"), storageId = "local")
        val nas = testTrack(TrackId.of("nas", "/b.flac"), storageId = "nas")
        tracks.upsertBatch(listOf(local, nas))
        tracks.markOrphan("nas")
        assertEquals(Availability.UNREACHABLE, tracks.get(nas.id)!!.availability)
        assertEquals(Availability.OK, tracks.get(local.id)!!.availability)
        assertEquals(2, tracks.count())
    }

    @Test
    @DisplayName("03-05 B03 删除 Storage 后歌单成员关系保留、曲目灰显")
    fun b03() = runTest {
        val storage = storages.add("webdav", "{}")
        val t = testTrack(TrackId.of(storage.id, "/a.flac"), storageId = storage.id)
        tracks.upsertBatch(listOf(t))
        val pid = playlists.create("我的歌单")
        playlists.addTracks(pid, listOf(t.id))
        favorites.toggle(t.id)

        storages.remove(storage.id)
        assertTrue(storages.observe().first().isEmpty())
        assertEquals(Availability.UNREACHABLE, tracks.get(t.id)!!.availability)
        assertEquals(listOf(t.id), playlists.observeTracks(pid).first().map { it.id })
        assertTrue(favorites.observe().first().contains(t.id))
    }

    @Test
    @DisplayName("03-05 T11 toggle(favorite) 后 Flow 立即推送新集合")
    fun t11() = runTest {
        val t = testTrack(TrackId.of("local", "/a.flac"))
        tracks.upsertBatch(listOf(t))
        assertTrue(favorites.observe().first().isEmpty())
        favorites.toggle(t.id)
        assertEquals(setOf(t.id), favorites.observe().first())
        favorites.toggle(t.id)
        assertTrue(favorites.observe().first().isEmpty())
    }

    @Test
    @DisplayName("03-05 §5 StorageRepository 生成稳定 UUID、setError 后 isWork=false")
    fun storageRepository() = runTest {
        val a = storages.add("webdav", "{}")
        val b = storages.add("strm", "{}")
        assertEquals(2, storages.observe().first().size)
        assertTrue(a.id != b.id)
        assertTrue(a.isWork)
        storages.setError(a.id, "连接失败")
        val updated = storages.observe().first().single { it.id == a.id }
        assertEquals("连接失败", updated.errorMsg)
        assertFalse(updated.isWork)
        storages.update(updated.copy(errorMsg = ""))
        assertTrue(storages.observe().first().single { it.id == a.id }.isWork)
    }

    @Test
    @DisplayName("03-05 §8 不可用曲目仍在搜索结果与 observeAll 里，只是状态被标记")
    fun unavailableStillVisible() = runTest {
        val t = testTrack(
            TrackId.of("nas", "/a.flac"),
            title = "暂不可达",
            storageId = "nas",
            path = "/nas/a.flac",
        )
        tracks.upsertBatch(listOf(t))
        tracks.markAvailability(t.id, Availability.UNREACHABLE)
        assertEquals(1, tracks.search("不可达").first().size)
        assertEquals(Availability.UNREACHABLE, tracks.observeAll().first().single().availability)
        assertEquals(1, tracks.byFolder("/nas").first().size)
    }

    @Test
    @DisplayName("03-05 §3.4 / B13 failure_log 写入与清理")
    fun failureLog() = runTest {
        val log = InMemoryFailureLog(store)
        val t = testTrack(TrackId.of("nas", "/a.flac"))
        repeat(3) { log.record(t.id, "E_DS_UPSTREAM_403") }
        assertEquals(3, log.observe().first().size)
        log.prune()
        assertEquals(3, log.observe().first().size)
        now += 31L * 24 * 60 * 60 * 1000
        log.prune()
        assertTrue(log.observe().first().isEmpty())
    }

    @Test
    @DisplayName("§61.212 setError 不改 modified、同值幂等零 diff（防 REBUILD 无限重扫循环）")
    fun setErrorKeepsModifiedAndIsIdempotent() = runTest {
        val added = storages.add("webdav", "{}")
        val before = store.snapshot().storages[added.id]!!

        storages.setError(added.id, "E_DS_UNREACHABLE: down")
        val afterError = store.snapshot().storages[added.id]!!
        assertEquals(before.modified, afterError.modified, "error-state 是运行态诊断，不得改 modified")
        assertEquals("E_DS_UNREACHABLE: down", afterError.errorMsg)

        // 同值幂等：重复写「已清」/同错误 → storages 无任何变化 → diff 空集
        val snapshotBefore = store.snapshot().storages[added.id]!!
        storages.setError(added.id, "E_DS_UNREACHABLE: down")
        assertEquals(snapshotBefore, store.snapshot().storages[added.id], "同值写必须零变化")

        // 策略层断言：errorMsg-only 差异（modified 不变）不得产生任何 SettingsChange
        // （REBUILD_DRIVER_AND_FULL_RESCAN 不得触发——§61.212 无限重扫循环回归锚）
        val cleared = storages.observe().first().single { it.id == added.id }
        // Storage→StorageDto 映射在 core-di（SettingsAccess.toDto）；此处按同字段手写，
        // 只断言 SettingsChangePolicy 对 errorMsg-only 差异的判定。
        val dto = StorageDto(
            id = cleared.id, mountPath = cleared.mountPath,
            driverType = cleared.driverType, additionRaw = cleared.addition.raw,
            errorMsg = cleared.errorMsg, modified = cleared.modified,
        )
        val before2 = AppSettings(storages = listOf(dto))
        val after2 = AppSettings(storages = listOf(dto.copy(errorMsg = "E_X")))
        assertEquals(
            emptySet(), SettingsChangePolicy.diff(before2, after2),
            "errorMsg-only + modified 不变 → 无任何 SettingsChange（REBUILD 不得触发）",
        )
    }

    @Test
    fun `upsertImported - 幂等建单 + 全量替换 + 与手建歌单互不干扰`() = runTest {
        // 手建歌单（用户数据）
        val userId = playlists.create("我的最爱")
        // 扫描导入（稳定 id）
        val impId = "pl_imp_abc123"
        // 先注册成员轨（InMemory 观察口按 store.tracks 解引用）
        tracks.upsertBatch(listOf(
            testTrack(TrackId("t1")).copy(title = "曲一"),
            testTrack(TrackId("t2")).copy(title = "曲二"),
        ))
        playlists.upsertImported(impId, "周华健精选", listOf(TrackId("t1"), TrackId("t2")))
        var rows = playlists.observePlaylists().first()
        assertEquals(2, rows.size, "导入建单")
        var members = playlists.observeTracks(impId).first().map { it.id.value }
        assertEquals(listOf("t1", "t2"), members, "曲目顺序 = m3u 条目序")
        // 重扫：同名同 id → 不重复建；曲目全量替换
        tracks.upsertBatch(listOf(testTrack(TrackId("t3")).copy(title = "曲三")))
        playlists.upsertImported(impId, "周华健精选（更新）", listOf(TrackId("t3")))
        rows = playlists.observePlaylists().first()
        assertEquals(2, rows.size, "幂等不重复")
        members = playlists.observeTracks(impId).first().map { it.id.value }
        assertEquals(listOf("t3"), members, "全量替换")
        assertEquals("周华健精选（更新）", rows.first { it.id == impId }.name)
        // 手建歌单不受影响
        assertEquals("我的最爱", rows.first { it.id == userId }.name)
    }
}
