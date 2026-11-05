package io.github.taehunkim.jogakbo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [NoteEntity::class, CueEntity::class, ChunkEntity::class], version = AppDatabase.VERSION, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        const val VERSION = 1
    }
}

/** Every schema change adds a Migration here (no destructive fallback: notes are the user's own data). */
val ALL_MIGRATIONS: Array<Migration> = arrayOf()
