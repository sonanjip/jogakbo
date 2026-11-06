package io.github.taehunkim.jogakbo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY ordinal, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY ordinal, name")
    suspend fun tags(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun tagByName(name: String): TagEntity?

    @Query("SELECT COALESCE(MAX(ordinal), -1) FROM tags")
    suspend fun maxOrdinal(): Int

    @Insert suspend fun insertTag(tag: TagEntity): Long
    @Update suspend fun updateTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("UPDATE tags SET ordinal = :ordinal WHERE id = :id")
    suspend fun setOrdinal(id: Long, ordinal: Int)

    // ---- tags of notes ---------------------------------------------------------------------------------------

    @Query("SELECT nt.noteId, nt.tagId, t.name, nt.state, nt.score FROM note_tags nt JOIN tags t ON t.id = nt.tagId WHERE nt.noteId = :noteId ORDER BY t.ordinal, t.name")
    fun observeOfNote(noteId: Long): Flow<List<NoteTagName>>

    /** Tags shown on notes (the model's yes and the user's own), for the list and filters. */
    @Query("SELECT nt.noteId, nt.tagId, t.name, nt.state, nt.score FROM note_tags nt JOIN tags t ON t.id = nt.tagId WHERE nt.state IN ('AUTO_YES','MANUAL') ORDER BY t.ordinal, t.name")
    fun observeApplied(): Flow<List<NoteTagName>>

    @Query("SELECT nt.noteId, nt.tagId, t.name, nt.state, nt.score FROM note_tags nt JOIN tags t ON t.id = nt.tagId WHERE nt.noteId IN (:noteIds) ORDER BY t.ordinal, t.name")
    suspend fun ofNotes(noteIds: List<Long>): List<NoteTagName>

    @Query("SELECT * FROM note_tags WHERE noteId = :noteId")
    suspend fun rows(noteId: Long): List<NoteTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<NoteTagEntity>)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun deleteRow(noteId: Long, tagId: Long)

    /** Forget the model's answers for one tag (renamed or described differently): it is checked again. */
    @Query("DELETE FROM note_tags WHERE tagId = :tagId AND state IN ('AUTO_YES','SUGGESTED','AUTO_NO')")
    suspend fun clearAutoOfTag(tagId: Long)

    /** Forget all of the model's answers ("tag all notes again"). */
    @Query("DELETE FROM note_tags WHERE state IN ('AUTO_YES','SUGGESTED','AUTO_NO')")
    suspend fun clearAllAuto()

    /**
     * Notes the decision model should look at: finished notes changed since they were tagged, or lacking an answer
     * of [modelId] (or the user's own) for some of the [tagCount] tags.
     */
    @Query(
        """SELECT id FROM notes WHERE sourceState IN ('NONE','DONE','FAILED') AND (taggedAt IS NULL OR taggedAt < updatedAt OR
           (SELECT COUNT(*) FROM note_tags nt WHERE nt.noteId = notes.id AND (nt.state IN ('MANUAL','REJECTED') OR nt.modelId = :modelId)) < :tagCount)
           ORDER BY updatedAt DESC""",
    )
    suspend fun needingTags(modelId: String, tagCount: Int): List<Long>

    @Query("UPDATE notes SET taggedAt = :at WHERE id = :id")
    suspend fun setTagged(id: Long, at: Long)

    @Transaction
    suspend fun replaceAuto(noteId: Long, rows: List<NoteTagEntity>, at: Long) {
        upsert(rows)
        setTagged(noteId, at)
    }
}
