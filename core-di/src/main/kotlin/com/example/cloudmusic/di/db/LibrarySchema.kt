package com.example.cloudmusic.di.db

import com.example.cloudmusic.model.SearchText
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

    /** §61.120（T5-2 前置）：v3 增 `tracks.replay_gain_db`（分析增益落库）。 */
    const val VERSION_V3 = 3

    /** §61.204（A1-③）：v4 增 `scan_snapshot.metadata_done`（refresh stat 命中跳过的依据）。 */
    const val VERSION_V4 = 4

    /** §61.212：v5 增 `scan_snapshot.direct_url`（STRM walk 免逐文件重读的 URL 缓存）。 */
    const val VERSION_V5 = 5

    /** §61.221（V0.2 M5 CUE）：v6 增 `tracks.cue_start_ms` / `tracks.cue_end_ms`（分轨起止）。 */
    const val VERSION_V6 = 6
    const val VERSION = VERSION_V6

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
    /**
     * `§61.296`：分词与短查询判定统一走 `SearchText`。此前用 `trim()` + `Regex("\\s+")`，
     * 中文输入法的全角空格（U+3000）不会被切成两段，FTS 收到的是一个大短语，
     * 而短语无法跨 title/artist 列匹配 ⇒ 「周杰伦　稻香」搜不到「稻香 / 周杰伦」。
     */
    fun isShortQuery(raw: String): Boolean = SearchText.normalize(raw).length < 2

    fun match(raw: String): String = SearchText.tokens(raw)
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

    /** §61.120（T5-2 前置）：分析增益列（可空 REAL，旧行全为 NULL=未分析）。 */
    const val ADD_REPLAY_GAIN =
        "ALTER TABLE tracks ADD COLUMN replay_gain_db REAL"

    /** §61.204（A1-③）：快照元数据完成标记（NOT NULL DEFAULT 0 = 旧行视为未完成，
     *  升级后首轮 refresh 全量重读一次，之后按 stat 命中跳过）。 */
    const val ADD_SNAPSHOT_METADATA_DONE =
        "ALTER TABLE scan_snapshot ADD COLUMN metadata_done INTEGER NOT NULL DEFAULT 0"

    /** §61.212：STRM directUrl 缓存（可空 TEXT，旧行 NULL = 首轮照常读文件内容）。 */
    const val ADD_SNAPSHOT_DIRECT_URL =
        "ALTER TABLE scan_snapshot ADD COLUMN direct_url TEXT"

    /** §61.221（V0.2 M5 CUE）：分轨起止列（NOT NULL DEFAULT 0 = 整轨 / 普通曲目无截断）。 */
    const val ADD_TRACK_CUE_START =
        "ALTER TABLE tracks ADD COLUMN cue_start_ms INTEGER NOT NULL DEFAULT 0"
    const val ADD_TRACK_CUE_END =
        "ALTER TABLE tracks ADD COLUMN cue_end_ms INTEGER NOT NULL DEFAULT 0"

    val V1_TO_V2 = listOf(ADD_SORT_TITLE, CREATE_SORT_INDEX)
    val V2_TO_V3 = listOf(ADD_REPLAY_GAIN)
    val V3_TO_V4 = listOf(ADD_SNAPSHOT_METADATA_DONE)
    val V4_TO_V5 = listOf(ADD_SNAPSHOT_DIRECT_URL)
    val V5_TO_V6 = listOf(ADD_TRACK_CUE_START, ADD_TRACK_CUE_END)

    fun apply(executor: SqlExecutor) {
        V1_TO_V2.forEach(executor::exec)
    }

    /** §61.120：按指定语句段执行（v2→v3 等新迁移复用同一增量纪律）。 */
    fun apply(executor: SqlExecutor, statements: List<String>) {
        statements.forEach(executor::exec)
    }

    /** T13 / T14：迁移语句必须是纯增量，任何破坏性语句都判失败。 */
    fun isNonDestructive(statements: List<String> = V1_TO_V2): Boolean =
        statements.isNotEmpty() && statements.all { sql ->
            val upper = sql.uppercase()
            (upper.startsWith("ALTER TABLE") || upper.startsWith("CREATE INDEX")) &&
                listOf("DROP", "DELETE", "TRUNCATE", "REPLACE INTO").none { it in upper }
        }
}
