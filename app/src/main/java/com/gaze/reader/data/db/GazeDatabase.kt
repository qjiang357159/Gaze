package com.gaze.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ComicEntity::class,
        TagEntity::class,
        ComicTagEntity::class,
        FolderEntity::class,
        ReadingSessionEntity::class,
        ShelfCoverEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GazeDatabase : RoomDatabase() {

    abstract fun comicDao(): ComicDao
    abstract fun tagDao(): TagDao
    abstract fun folderDao(): FolderDao
    abstract fun statsDao(): StatsDao
    abstract fun shelfCoverDao(): ShelfCoverDao

    companion object {
        @Volatile
        private var instance: GazeDatabase? = null

        /**
         * v1 -> v2（方案第二十五章 P2）。
         *
         * 两件事：
         * 1. `comics` 补 4 个扩展元数据列 —— **全部可空、无默认值**。
         *    带默认值的 `ADD COLUMN` 会和 Room 编译期生成的 schema 对不上，
         *    运行时直接抛 `IllegalStateException`，这是本项目踩过的坑。
         * 2. 新建 `reading_sessions` 表。
         *
         * 老数据一条不丢：现有漫画只是这几个新字段为 NULL。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `comics` ADD COLUMN `series` TEXT")
                db.execSQL("ALTER TABLE `comics` ADD COLUMN `volume` INTEGER")
                db.execSQL("ALTER TABLE `comics` ADD COLUMN `note` TEXT")
                db.execSQL("ALTER TABLE `comics` ADD COLUMN `rating` INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `comicId` INTEGER NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `startPage` INTEGER NOT NULL,
                        `endPage` INTEGER NOT NULL,
                        `pagesRead` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_comicId` " +
                        "ON `reading_sessions` (`comicId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_startedAt` " +
                        "ON `reading_sessions` (`startedAt`)"
                )
            }
        }

        /**
         * v2 -> v3。
         *
         * 只加一张 `shelf_covers`：书架文件夹的自定义封面覆盖记录。
         * 不加列、不动老表 —— 这是本轮改动里唯一需要的结构变更。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shelf_covers` (
                        `key` TEXT NOT NULL,
                        `comicId` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): GazeDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): GazeDatabase =
            Room.databaseBuilder(context, GazeDatabase::class.java, "gaze.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // 不用 destructive fallback：宁可启动报错，也不能悄悄清掉用户的阅读进度与标签。
                .build()
    }
}
