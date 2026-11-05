package io.github.taehunkim.jogakbo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY createdAt")
    suspend fun all(): List<NoteEntity>

    @Insert suspend fun insert(note: NoteEntity): Long
    @Update suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    // ---- background jobs -------------------------------------------------------------------------------------

    @Query("UPDATE notes SET summaryState = :state, summaryError = :error WHERE id = :id")
    suspend fun setSummaryState(id: Long, state: JobState, error: String? = null)

    @Query("UPDATE notes SET summary = :summary, summaryState = 'DONE', summaryError = NULL, summaryModel = :model, summaryEdited = 0, updatedAt = :now WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, model: String?, now: Long)

    @Query("UPDATE notes SET sourceState = :state, sourceError = :error WHERE id = :id")
    suspend fun setSourceState(id: Long, state: JobState, error: String? = null)

    /** Notes whose job was interrupted (process killed): picked up again at start. */
    @Query("SELECT id FROM notes WHERE summaryState IN ('QUEUED','RUNNING')")
    suspend fun pendingSummaries(): List<Long>

    @Query("SELECT id FROM notes WHERE sourceState IN ('QUEUED','RUNNING')")
    suspend fun pendingSources(): List<Long>

    // ---- cues ------------------------------------------------------------------------------------------------

    @Query("SELECT * FROM cues WHERE noteId = :noteId ORDER BY ordinal")
    fun observeCues(noteId: Long): Flow<List<CueEntity>>

    @Query("SELECT * FROM cues WHERE noteId = :noteId ORDER BY ordinal")
    suspend fun cues(noteId: Long): List<CueEntity>

    @Insert suspend fun insertCues(cues: List<CueEntity>)

    @Query("DELETE FROM cues WHERE noteId = :noteId")
    suspend fun deleteCues(noteId: Long)

    @Query("SELECT COALESCE(MAX(ordinal), -1) FROM cues WHERE noteId = :noteId")
    suspend fun lastCueOrdinal(noteId: Long): Int

    @Transaction
    suspend fun replaceCues(noteId: Long, cues: List<CueEntity>) {
        deleteCues(noteId)
        insertCues(cues.mapIndexed { i, c -> c.copy(id = 0, noteId = noteId, ordinal = i) })
    }

    // ---- embeddings ------------------------------------------------------------------------------------------

    /** Notes whose chunks are missing, older than the text, or made with another model or chunking. */
    @Query("SELECT id FROM notes WHERE sourceState IN ('NONE','DONE','FAILED') AND (embedKey IS NULL OR embedKey != :key OR embeddedAt IS NULL OR embeddedAt < updatedAt) ORDER BY updatedAt DESC")
    suspend fun needingEmbedding(key: String): List<Long>

    @Query("SELECT COUNT(*) FROM notes WHERE sourceState IN ('NONE','DONE','FAILED') AND (embedKey IS NULL OR embedKey != :key OR embeddedAt IS NULL OR embeddedAt < updatedAt)")
    fun observeNeedingEmbedding(key: String): Flow<Int>

    @Query("DELETE FROM chunks WHERE noteId = :noteId")
    suspend fun deleteChunks(noteId: Long)

    @Insert suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("UPDATE notes SET embeddedAt = :at, embedKey = :key WHERE id = :id")
    suspend fun setEmbedded(id: Long, at: Long, key: String)

    @Transaction
    suspend fun replaceChunks(noteId: Long, chunks: List<ChunkEntity>, at: Long, key: String) {
        deleteChunks(noteId)
        insertChunks(chunks)
        setEmbedded(noteId, at, key)
    }

    @Query("SELECT id, noteId, embedding FROM chunks WHERE modelId = :modelId")
    suspend fun vectors(modelId: String): List<ChunkVector>

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun chunks(ids: List<Long>): List<ChunkEntity>

    /** Changes whenever chunks are added or removed (search caches the vectors by it). */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(id), 0) FROM chunks")
    suspend fun chunkStamp(): String

    // ---- keyword search and changes ------------------------------------------------------------------------

    @Query("SELECT * FROM notes WHERE title LIKE :pattern ESCAPE '\\' OR body LIKE :pattern ESCAPE '\\' OR summary LIKE :pattern ESCAPE '\\' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun keywordCandidates(pattern: String, limit: Int): List<NoteEntity>

    /** For other apps: notes added or changed after [cursor] (an updatedAt time). */
    @Query("SELECT id, updatedAt FROM notes WHERE updatedAt > :cursor ORDER BY updatedAt LIMIT :limit")
    suspend fun changedAfter(cursor: Long, limit: Int): List<NoteChange>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM notes")
    suspend fun lastUpdate(): Long
}

data class NoteChange(val id: Long, val updatedAt: Long)
