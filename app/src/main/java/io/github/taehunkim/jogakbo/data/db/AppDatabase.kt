package io.github.taehunkim.jogakbo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, CueEntity::class, ChunkEntity::class, TagEntity::class, NoteTagEntity::class],
    version = AppDatabase.VERSION, exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao

    companion object {
        const val VERSION = 2
    }
}

/** 1 → 2 (0.2.0): tags, the tags of each note, and when a note was last tagged. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `taggedAt` INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `description` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_tags` (`noteId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, `state` TEXT NOT NULL, `score` REAL, `modelId` TEXT, `at` INTEGER NOT NULL, PRIMARY KEY(`noteId`, `tagId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tags_tagId` ON `note_tags` (`tagId`)")
    }
}

/** Every schema change adds a Migration here (no destructive fallback: notes are the user's own data). */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
