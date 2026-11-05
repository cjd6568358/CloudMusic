package com.example.cloudmusic.player

/**
 * docs/03-06 §8 双轨：业务队列变更后**单向全量下发**到 native 队列。
 * 生产实现调 `PlaylistJniBridge` 快照接口；本模块只保留纯 JVM 记录型参考实现。
 */
interface QueueSync {
    suspend fun push(items: List<QueueItem>, mode: PlayMode, startAt: Int)
}

/** 纯 JVM 参考实现：记录每次下发，供单测断言「全量、单向、顺序一致」。 */
class RecordingQueueSync : QueueSync {
    data class Push(val items: List<QueueItem>, val mode: PlayMode, val startAt: Int)

    private val _pushes = mutableListOf<Push>()
    val pushes: List<Push> get() = _pushes.toList()

    override suspend fun push(items: List<QueueItem>, mode: PlayMode, startAt: Int) {
        _pushes.add(Push(items.toList(), mode, startAt))
    }

    fun clear() = _pushes.clear()
}

/**
 * `13 §4` 实证补强：竞品 `libnmplaylist.so` 自带 SQLite，**不是只读镜像**。
 * 为维持「业务队列（Room）唯一真相源」，舱内必须守住的隔离约束：
 * ① `nativeInit` 只传隔离沙箱路径；② 禁止 `nativeRestoreFromDb` 等反向读回写业务态；
 * ③ 每次实例初始化先全量 `push` 覆盖残留；④ 业务 Room 与 native 库物理隔离、都不得存明文凭据。
 *
 * 这些是**编译期 / 自检期守护常量**，与 `tools/check_source_contracts.py` 的静态断言联动。
 */
object NativeQueueSandbox {
    /** 允许的沙箱目录名（相对应用私有目录，不含任何机器绝对路径）。 */
    val ALLOWED_DIR_HINTS = setOf("native_playlist", "cache", "noBackupFilesDir")

    /** 禁止从 native 队列反向读取业务态的入口。 */
    val FORBIDDEN_NATIVE_CALLS = setOf(
        "nativeRestoreFromDb", "nativeListRecentPlaylists", "nativeGetSnapshot",
    )

    /** 冷启动恢复的唯一合法业务入口（§8.2 恢复顺序）。 */
    const val RESTORE_ENTRY = "QueueRepository.restore() -> QueueSync.push()"

    fun isSandboxPath(path: String): Boolean =
        ALLOWED_DIR_HINTS.any { path.contains(it, ignoreCase = true) } &&
                !path.contains("datastore", ignoreCase = true) &&
                !path.contains("credentials", ignoreCase = true)
}
