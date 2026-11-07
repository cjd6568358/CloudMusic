package com.example.cloudmusic.di.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * docs/03-05 §3 的 V0.2 Room 数据库（第十七批 / T-A1）。
 *
 * - entities 共 10 个 = **9 张物理表 + 1 张 FTS4 虚拟表**（03-05 §2 注 / 16 §6 #4）。
 * - version = [LibrarySchema.VERSION]；v1→v2 只加列与索引，见 [LibraryMigrations]。
 * - **不调用** `fallbackToDestructiveMigration()`，[RoomDatabase.Builder.addMigrations] 覆盖 1→2；
 *   缺迁移时 Room 抛 `IllegalStateException`，由启动层按 `E_DB_MIGRATION` 明确报错、不清库（T14）。
 * - `exportSchema = true`：schema JSON 由 KSP 的 `room.schemaLocation` 输出到 `core-di/schemas/`，
 *   供后续 `MigrationTestHelper` 的 instrumentation 测试消费（T13，属 T-V 验收线）。
 */
@Database(
    entities = [
        TrackRow::class,
        TrackFtsRow::class,
        PlaylistRow::class,
        PlaylistTrackRow::class,
        FavoriteRow::class,
        PlayHistoryRow::class,
        FailureLogRow::class,
        ScanSnapshotRow::class,
        QueueItemRow::class,
        PlaybackStateRow::class,
    ],
    version = LibrarySchema.VERSION,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun scanSnapshotDao(): ScanSnapshotDao
    abstract fun failureLogDao(): FailureLogDao

    companion object {
        fun build(context: Context): LibraryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                LibrarySchema.DATABASE_NAME,
            )
                .addMigrations(*LibraryMigrations.ALL)
                .addCallback(FtsRebuildCallback)
                .build()
    }
}

/**
 * B06：FTS 表与 tracks 不同步时，启动时无条件 rebuild 一次。
 * Room 的 FTS4 外部内容表自带同步触发器，这里只是幂等兜底，不改数据。
 */
private object FtsRebuildCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("INSERT INTO tracks_fts(tracks_fts) VALUES('rebuild')")
    }
}
