package com.example.cloudmusic.di.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.example.cloudmusic.storage.AppSettings
import com.example.cloudmusic.storage.SettingsValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 03-05 §4 / T-A2 的 DataStore 序列化器（自定义 `Serializer<AppSettings>`，D-49）。
 *
 * 文件名沿用 `.pb` 后缀，但内容是 [AppSettingsCodec] 的 JSON —— 与 Proto DataStore 共用
 * 同一套 `DataStore<T>` / `Serializer<T>` API，后续如要换成真正的 protobuf，只需替换本
 * Serializer，**调用方、Repository、投影全部零改动**。
 *
 * 损坏数据抛 [CorruptionException]（不静默清库，03-05 §11 / B10）；空文件按全默认设置处理。
 */
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val raw = input.readBytes().decodeToString()
        if (raw.isBlank()) return defaultValue
        return try {
            AppSettingsCodec.decode(raw)
        } catch (cause: Exception) {
            throw CorruptionException("AppSettings 损坏，拒绝静默清库（03-05 §11 / B10）", cause)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(AppSettingsCodec.encode(t).toByteArray(Charsets.UTF_8))
    }
}

/** 全项目唯一的设置 DataStore，路径由 Android 的 `filesDir` 解析，不含任何个人绝对路径。 */
private val Context.appSettingsDataStore: DataStore<AppSettings> by dataStore(
    fileName = "cloudmusic_settings.pb",
    serializer = AppSettingsSerializer,
)

/**
 * C-14（`12 §31`）：把「写入成功」这一事实广播给非 UI 的运行时消费者。
 *
 * `SettingsStore.update()` 的返回值只到调用方（设置页属 T-A6），而 `PlaybackService`
 * 需要独立消费 [SettingsUpdate.effects] 才能执行 Driver 重建 / 全量重扫 / 缓存失效。
 * 用 `tryEmit` + 缓冲，绝不因无订阅者阻塞写入（B14 飞行模式保存不受影响）。
 */
@Singleton
class SettingsEffectsBus @Inject constructor() {
    private val _updates = MutableSharedFlow<SettingsUpdate>(extraBufferCapacity = 8)
    val updates: SharedFlow<SettingsUpdate> = _updates.asSharedFlow()

    fun emit(update: SettingsUpdate) {
        _updates.tryEmit(update)
    }
}

/**
 * 设置单一入口。所有写入都经过 [SettingsValidator] 与 [SettingsUpdate] 的 08 §7 裁决，
 * 设置页 / Driver 重建 / 缓存失效的调用方只需消费 [SettingsUpdate.effects]。
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val effectsBus: SettingsEffectsBus,
) : SettingsAccess {

    private val dataStore: DataStore<AppSettings> = context.appSettingsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val settings: Flow<AppSettings> get() = dataStore.data

    /** Eagerly 共享，保证 `current()` 在设置流到达后立即可用。 */
    private val state: StateFlow<AppSettings> =
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override fun current(): AppSettings = state.value

    override suspend fun snapshot(): AppSettings = dataStore.data.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate {
        var before = AppSettings()
        var after = AppSettings()
        dataStore.updateData { current ->
            val next = transform(current)
            val problems = SettingsValidator.validate(next)
            require(problems.isEmpty()) {
                "非法设置被拒绝写入：${problems.joinToString("; ")}"
            }
            // updateData 在并发冲突时可能重试；只保留最终成功提交的那一对快照。
            before = current
            after = next
            next
        }
        val update = SettingsUpdate.of(before, after)
        effectsBus.emit(update)   // C-14：运行时执行者的唯一入口
        return update
    }
}
