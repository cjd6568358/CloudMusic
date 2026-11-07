package com.example.cloudmusic.di.settings

import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.storage.StorageDto
import com.example.cloudmusic.storage.StorageRepository
import com.example.cloudmusic.storage.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 03-05 §2 / §4 的 `StorageRepository` **DataStore 实现**（第十八批 / T-A2）。
 *
 * 替换第十七批保留的 `InMemoryStorageRepository`：Storage（含 `additionRaw` 明文凭据）
 * 现在真正落盘到 DataStore，重启不再丢失；Room 只存曲目，不存凭据（§14 #2）。
 * 删除 Storage 时按 §2 / §9 显式调 [TrackRepository.markOrphan]，不依赖物理外键。
 *
 * 依赖 [SettingsAccess] 端口而非 Android `Context`，故增删查改与 markOrphan 委托可在
 * JVM 单测里用 fake 覆盖（与 `:core-di/db` 的 Room Repository 同型）。
 */
class DataStoreStorageRepository(
    private val access: SettingsAccess,
    private val tracks: TrackRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : StorageRepository {

    override fun observe(): Flow<List<Storage>> =
        access.settings.map { settings ->
            settings.storages.map { it.toModel() }.sortedBy { it.modified }
        }

    /** 生成稳定 UUID；`mountPath` 沿用内存实现的 `/<driverType>` 约定（03-01 §3.3）。 */
    override suspend fun add(type: String, additionJson: String): Storage {
        val dto = StorageDto(
            id = idFactory(),
            mountPath = "/$type",
            driverType = type,
            additionRaw = additionJson,
            modified = clock(),
        )
        access.update { it.copy(storages = it.storages + dto) }
        return dto.toModel()
    }

    override suspend fun update(s: Storage) {
        access.update { settings ->
            settings.copy(
                storages = settings.storages.map { dto -> if (dto.id == s.id) s.toDto() else dto },
            )
        }
    }

    override suspend fun remove(id: String) {
        access.update { it.copy(storages = it.storages.filterNot { dto -> dto.id == id }) }
        tracks.markOrphan(id)
    }

    override suspend fun setError(id: String, msg: String) {
        access.update { settings ->
            settings.copy(
                storages = settings.storages.map { dto ->
                    if (dto.id == id) dto.copy(errorMsg = msg, modified = clock()) else dto
                },
            )
        }
    }
}
