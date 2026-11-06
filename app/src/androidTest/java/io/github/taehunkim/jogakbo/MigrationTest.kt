package io.github.taehunkim.jogakbo

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taehunkim.jogakbo.data.db.ALL_MIGRATIONS
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun from1KeepsNotesAndAddsTags() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO notes (id, uuid, kind, title, body, summaryEdited, summaryState, sourceState, createdAt, updatedAt) VALUES (1, 'u1', 'MEMO', 'Trip', 'Jeju', 0, 'NONE', 'NONE', 1, 2)")
        }
        helper.runMigrationsAndValidate(DB, AppDatabase.VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT title, taggedAt FROM notes").use { c -> c.moveToFirst(); assertEquals("Trip", c.getString(0)); assertEquals(true, c.isNull(1)) }
            db.execSQL("INSERT INTO tags (name, description, ordinal, createdAt) VALUES ('Travel', '', 0, 1)")
            db.execSQL("INSERT INTO note_tags (noteId, tagId, state, at) VALUES (1, 1, 'MANUAL', 3)")
            db.query("SELECT COUNT(*) FROM tags WHERE name = 'travel'").use { c -> c.moveToFirst(); assertEquals("names ignore case", 1, c.getInt(0)) }
        }
    }

    private companion object { const val DB = "migration-test" }
}
