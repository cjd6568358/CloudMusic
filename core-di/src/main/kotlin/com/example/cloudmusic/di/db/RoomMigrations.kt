package com.example.cloudmusic.di.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 03-05 §10 / T13 / T14 / §14 #1。
 *
 * v1 = 初始 9 张物理表 + FTS4；v2 = 为 §8 中文排序补 `tracks.sort_title` 与索引。
 * SQL 语句唯一的真相源是 [MigrationSql]，Room 的 [Migration] 只是把
 * [SupportSQLiteDatabase] 适配成 [SqlExecutor]；因此 JVM 单测断言的 SQL
 * 与真机执行的 SQL 是同一份，不会出现「测试测 A、生产跑 B」。
 *
 * **禁止** `fallbackToDestructiveMigration()`：曲库是用户资产（03-05 §14 #1 / B10 / E_DB_MIGRATION）。
 */
object LibraryMigrations {

    val MIGRATION_1_2: Migration =
        object : Migration(LibrarySchema.VERSION_V1, LibrarySchema.VERSION_V2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.apply(object : SqlExecutor {
                    override fun exec(sql: String) {
                        db.execSQL(sql)
                    }
                })
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
