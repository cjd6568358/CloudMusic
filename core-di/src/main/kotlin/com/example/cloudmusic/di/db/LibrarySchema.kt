package com.example.cloudmusic.di.db

import java.text.Collator
import java.util.Locale

/**
 * docs/03-05 §3 / §8 / §10 的 Room 契约常量（**纯 JVM，零 Android 依赖**）。
 *
 * 第十七批（`12 §26`，T-A1）：把这些常量与纯函数独立出来，使「9 张物理表 + FTS4 +
 * Migration 的非破坏性」可以在 AGP 的 JVM 单测里断言，而不必实例化 Android 运行时。
 * Room 实体 / DAO / Database 在 [Entities.kt] / [Daos.kt] / [LibraryDatabase.kt]。
 */
object LibrarySchema {
    const val DATABASE_NAME = "cloudmusic.db"

    /** 03-05 §3：版本 1 一次性建好全部表（含 FTS4 虚拟表）。 */
    const val VERSION_V1 = 1

    /** 03-05 §8 / B04 / T05：中文 Collator 排序键，按 §10「新增列一律 ALTER ... DEFAULT」扩展。 */
    const val VERSION_V2 = 2
    const val VERSION = VERSION_V2

    const val FTS_TABLE = "tracks_fts"

    /** 9 张物理表（8 类数据；歌单拆两张），03-05 §2 注 / 16 §6 #4 为权威口径。 */
    val PHYSICAL_TABLES = listOf(
        "tracks",
        "playlists",
        "playlist_tracks",
        "favorites",
        "play_history",
        "failure_log",
        "scan_snapshot",
        "queue_items",
        "playback_state",
    )

    val VIRTUAL_TABLES = listOf(FTS_TABLE)

    /** 03-05 §3 / 03-04 §8 / 03-06 §8.1 的索引清单；v2 追加中文排序键索引。 */
    val INDEXES = listOf(
        "idx_tracks_title",
        "idx_tracks_artist",
        "idx_tracks_album",
        "idx_tracks_storage",
        "idx_tracks_avail",
        "idx_tracks_sort_title",
        "idx_pt_track",
        "idx_failure_time",
        "idx_queue_track",
    )
}

/** 03-05 §8：FTS4 MATCH 短语转义，避免用户输入里的引号破坏 MATCH 语法。 */
object FtsQuery {
    fun isShortQuery(raw: String): Boolean = raw.trim().length < 2

    fun match(raw: String): String = raw.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { token -> "\"${token.replace("\"", "\"\"")}\"" }
}

/** LIKE 兜底（B05）的通配符转义，配合 DAO 里的 `ESCAPE '\'`。 */
object LikePattern {
    fun escape(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '\\', '%', '_' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }
}

/**
 * 03-05 §8 / B04 / T05：SQLite 的 BINARY collation 不懂中文拼音，故存一份
 * `Collator(Locale.CHINA)` 的 collation key（无符号 hex，字典序 == 字节序）。
 * Repository 层仍用 `LibraryQuery.sort` 做展示排序，该列供后续 SQL 端排序使用。
 */
object SortKeys {
    fun of(text: String): String {
        val bytes = Collator.getInstance(Locale.CHINA).getCollationKey(text).toByteArray()
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** 让 Migration SQL 在纯 JVM 单测里可执行，无需 `SupportSQLiteDatabase`。 */
interface SqlExecutor {
    fun exec(sql: String)
}

/**
 * 03-05 §10 的 v1→v2 迁移：只新增列 / 索引，**不得 DROP / DELETE / 重建**。
 * Room 的 `Migration` 对象在 [Migrations.kt] 里通过同一个 [apply] 适配，保证
 * 单测断言的 SQL 与真机执行的 SQL 是同一份真相源。
 */
object MigrationSql {
    const val ADD_SORT_TITLE =
        "ALTER TABLE tracks ADD COLUMN sort_title TEXT NOT NULL DEFAULT ''"
    const val CREATE_SORT_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_tracks_sort_title ON tracks(sort_title)"

    val V1_TO_V2 = listOf(ADD_SORT_TITLE, CREATE_SORT_INDEX)

    fun apply(executor: SqlExecutor) {
        V1_TO_V2.forEach(executor::exec)
    }

    /** T13 / T14：迁移语句必须是纯增量，任何破坏性语句都判失败。 */
    fun isNonDestructive(statements: List<String> = V1_TO_V2): Boolean =
        statements.isNotEmpty() && statements.all { sql ->
            val upper = sql.uppercase()
            (upper.startsWith("ALTER TABLE") || upper.startsWith("CREATE INDEX")) &&
                listOf("DROP", "DELETE", "TRUNCATE", "REPLACE INTO").none { it in upper }
        }
}
