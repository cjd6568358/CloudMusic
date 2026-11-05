package com.example.cloudmusic.datasource

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Storage
import java.util.concurrent.ConcurrentHashMap

/**
 * Driver 注册表（docs/03-01 §4.1）。
 *
 * 生产侧唯一注册点是 :core-di 的 DataSourceModule（Hilt Multibindings，03-12 §5），
 * 本对象只提供运行时存储与查找；03-02 / 03-03 只提供构造，不另建 @Module。
 * 单个 Driver 实例不保证线程安全，多个协程同时 list 由 03-04 ScanCoordinator 用 Semaphore(4) 限流。
 */
object DriverRegistry {
    private val factories = ConcurrentHashMap<String, DriverFactory>()
    private val instances = ConcurrentHashMap<String, Driver>()

    /** 重复注册同一 type 抛 IllegalStateException（T16）。 */
    fun register(type: String, factory: DriverFactory) {
        check(factories.putIfAbsent(type, factory) == null) { "数据源类型已注册: $type" }
    }

    fun types(): Set<String> = factories.keys.toSet()

    fun create(type: String, storage: Storage, addition: Addition): Driver =
        factories[type]?.invoke(storage, addition)
            ?: throw CloudMusicError.DsUnknownDriver(type)

    fun get(storageId: String): Driver? = instances[storageId]

    fun put(storageId: String, d: Driver) {
        instances[storageId] = d
    }

    /** 移除并 drop（drop 幂等，B02）。 */
    fun remove(storageId: String) {
        instances.remove(storageId)?.drop()
    }

    /**
     * 仅用于契约测试复位（T16 需要在干净注册表上验证 4 个 key）。
     * 生产代码不得调用；:core-di 在 Application 启动时完成一次性注册。
     */
    fun reset() {
        instances.values.forEach { runCatching { it.drop() } }
        instances.clear()
        factories.clear()
    }
}
