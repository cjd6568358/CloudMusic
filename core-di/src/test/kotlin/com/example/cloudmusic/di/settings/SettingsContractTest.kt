package com.example.cloudmusic.di.settings

import androidx.datastore.core.CorruptionException
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.player.PlayerConfig
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.DlnaSettings
import com.example.cloudmusic.storage.Effect
import com.example.cloudmusic.storage.NetworkSettings
import com.example.cloudmusic.storage.PlaybackSettings
import com.example.cloudmusic.storage.StorageDto
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第十八批（`12 §27`，T-A2）DataStore 层的**纯 JVM 契约测试**。
 * 不实例化 Android `Context` / `DataStore`：codec、Serializer 的流读写、仓储行为与
 * 设置投影都通过端口隔离在 JVM 上覆盖；`Context` 只出现在 `SettingsStore` 的薄包装里。
 */
class SettingsContractTest {

    private class FakeSettingsAccess(initial: AppSettings = AppSettings()) : SettingsAccess {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> get() = state
        override fun current(): AppSettings = state.value
        override suspend fun snapshot(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate {
            val before = state.value
            val after = transform(before)
            state.value = after
            return SettingsUpdate.of(before, after)
        }
    }

    private class FakeTrackRepository : TrackRepository {
        val orphaned = mutableListOf<String>()
        override suspend fun markOrphan(storageId: String) {
            orphaned += storageId
        }

        override fun observeAll(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun search(query: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byAlbum(album: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byArtist(artist: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun byFolder(prefix: String): Flow<List<Track>> = MutableStateFlow(emptyList())
        override suspend fun get(id: TrackId): Track? = null
        override suspend fun upsertBatch(tracks: List<Track>) = Unit
        override suspend fun markAvailability(id: TrackId, a: Availability) = Unit
        override suspend fun count(): Int = 0
    }

    private fun credentialJson(): String =
        """{"user":"a:b@中文","pass":"p\"\\/ss","nested":{"k":"v"}}"""

    private fun populated(): AppSettings = AppSettings(
        storages = listOf(
            StorageDto(
                id = "nas-1",
                mountPath = "/nas",
                driverType = "webdav",
                additionRaw = credentialJson(),
                remark = "备注:带冒号",
                disabled = true,
                errorMsg = "E_DS_STORAGE_MISSING",
                modified = 1_700_000_000_000L,
            ),
        ),
        theme = "LIGHT",
        network = NetworkSettings(wifiOnly = false, forceWebdavProxy = true),
        dlna = DlnaSettings(manualDmrHistory = listOf("dmr-1", "dmr-2"), preferDirectLink = false),
        playback = PlaybackSettings(autoSkipOnError = false, restoreLastQueue = false),
    )

    // ---- 03-05 §4 / 08 §2 / B08：JSON codec ---------------------------------

    @Test
    fun `T-A2-01 default settings survive a JSON round-trip`() {
        val original = AppSettings()
        assertEquals(original, AppSettingsCodec.decode(AppSettingsCodec.encode(original)))
    }

    @Test
    fun `T-A2-02 all groups and credential special characters round-trip`() {
        val original = populated()
        val encoded = AppSettingsCodec.encode(original)
        assertEquals(original, AppSettingsCodec.decode(encoded))
        assertTrue(encoded.contains("additionRaw"))
        assertTrue(encoded.contains("中文"), "凭据里的中文必须随 DataStore 明文保留")
    }

    @Test
    fun `T-A2-03 missing fields fall back to catalog defaults`() {
        val decoded = AppSettingsCodec.decode(
            """{"theme":"LIGHT","network":{"forceWebdavProxy":true}}""",
        )
        assertEquals("LIGHT", decoded.theme)
        assertTrue(decoded.network.forceWebdavProxy)
        assertEquals(10_000L, decoded.scan.minDurationMs)
        assertEquals(AppSettings().scan, decoded.scan)
        assertEquals(AppSettings().playback, decoded.playback)
        assertTrue(decoded.storages.isEmpty())
        assertEquals(AppSettings(), AppSettingsCodec.decode("   "))
        assertEquals("{}", AppSettingsCodec.decode("""{"storages":[{"id":"x"}]}""")
            .storages.single().additionRaw)
    }

    @Test
    fun `T-A2-04 corrupted JSON falls back to defaults without clearing user data silently`() {
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault("{not json"))
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault(""))
        assertFailsWith<Exception> { AppSettingsCodec.decode("{not json") }
    }

    // ---- DataStore Serializer（03-05 §11 / B10）------------------------------

    @Test
    fun `T-A2-05 serializer writes and reads back through streams`() = runBlocking {
        assertEquals(AppSettings(), AppSettingsSerializer.defaultValue)
        val original = populated()
        val bytes = ByteArrayOutputStream().also {
            AppSettingsSerializer.writeTo(original, it)
        }.toByteArray()
        assertEquals(original, AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes)))
        assertEquals(
            AppSettings(),
            AppSettingsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun `T-A2-06 serializer reports corruption instead of silently wiping settings`() {
        // 必须用语句体：表达式体 `= runBlocking { assertFailsWith{...} }` 的返回类型不是 Unit，
        // JUnit Jupiter 会静默跳过该测试方法（第十八批实测发现，见 12 §27.3）。
        runBlocking {
            assertFailsWith<CorruptionException> {
                AppSettingsSerializer.readFrom(ByteArrayInputStream("{broken".toByteArray()))
            }
        }
    }

    // ---- 03-05 §2 / §4：StorageDto ↔ Storage 与仓储行为 ---------------------

    @Test
    fun `T-A2-07 storage dto and domain model round-trip`() {
        val dto = StorageDto("s1", "/nas", "webdav", credentialJson(), "备注", true, "err", 9L)
        assertEquals(dto, dto.toModel().toDto())
        assertEquals(credentialJson(), dto.toModel().addition.raw)
    }

    @Test
    fun `T-A2-08 DataStore storage repository CRUD and markOrphan delegation`() = runBlocking {
        val access = FakeSettingsAccess()
        val tracks = FakeTrackRepository()
        var seq = 0
        val repo = DataStoreStorageRepository(
            access = access,
            tracks = tracks,
            clock = { 100L + seq },
            idFactory = { "storage-${++seq}" },
        )

        val added = repo.add("webdav", credentialJson())
        assertEquals("storage-1", added.id)
        assertEquals("/webdav", added.mountPath)
        assertEquals(credentialJson(), added.addition.raw)
        assertEquals(listOf(added.id), repo.observe().first().map { it.id })
        // 凭据只在 DataStore，未写入 Room：markOrphan 只在删除时发生
        assertTrue(tracks.orphaned.isEmpty())

        val renamed = added.copy(remark = "新备注")
        repo.update(renamed)
        assertEquals("新备注", repo.observe().first().single().remark)

        repo.setError(added.id, "E_DS_STORAGE_MISSING")
        assertEquals("E_DS_STORAGE_MISSING", repo.observe().first().single().errorMsg)

        repo.remove(added.id)
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(listOf(added.id), tracks.orphaned)
    }

    // ---- 08 §6 / §7：单一来源投影与生效规则 ----------------------------------

    @Test
    fun `T-A2-09 runtime projections come from the single persistent source`() {
        val settings = populated()
        val proxy = SettingsProjection.proxy(settings)
        assertTrue(proxy.forceWebdavProxy)
        assertFalse(proxy.preferDirectLink)
        val config: PlayerConfig = SettingsProjection.playerConfig(settings)
        assertFalse(config.autoSkipOnError)
        assertFalse(config.restoreLastQueue)
        assertTrue(SettingsProjection.proxy(AppSettings()).preferDirectLink)
    }

    @Test
    fun `T-A2-10 settings update exposes 08 section 7 effects`() {
        val before = AppSettings()
        assertEquals(setOf(Effect.SAVE_ONLY), SettingsUpdate.of(before, before).effects)

        val proxyChanged = before.copy(network = before.network.copy(forceWebdavProxy = true))
        val proxyEffects = SettingsUpdate.of(before, proxyChanged).effects
        assertTrue(Effect.INVALIDATE_DIRECT_URL_CACHE in proxyEffects)
        assertTrue(Effect.RESOLVE_NEXT_TRACK in proxyEffects)

        val withStorage = before.copy(
            storages = listOf(StorageDto("s1", "/nas", "webdav", "{}")),
        )
        val credentialsChanged = withStorage.copy(
            storages = listOf(withStorage.storages.single().copy(additionRaw = credentialJson())),
        )
        assertTrue(
            Effect.REBUILD_DRIVER_AND_FULL_RESCAN in
                SettingsUpdate.of(withStorage, credentialsChanged).effects,
        )
    }
}
