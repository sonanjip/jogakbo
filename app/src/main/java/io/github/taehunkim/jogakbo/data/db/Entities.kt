package io.github.taehunkim.jogakbo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a note was made from. */
enum class NoteKind { MEMO, LINK, YOUTUBE, AUDIO;
    /** The name used in exports and in the SDK. */
    val wire: String get() = name.lowercase()
    companion object { fun fromWire(s: String?): NoteKind? = entries.firstOrNull { it.wire == s?.lowercase() } }
}

/** Progress of a background job on a note (making its original, or its summary). */
enum class JobState { NONE, QUEUED, RUNNING, DONE, FAILED }

/**
 * One note. [body] is the original and [summary] the summary, both Markdown. Notes made from a link or audio get
 * their original from a background job ([sourceState]); the summary is made by another job ([summaryState]).
 */
@Entity(tableName = "notes", indices = [Index(value = ["uuid"], unique = true), Index("updatedAt")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable across export and import. */
    val uuid: String,
    val kind: NoteKind,
    val title: String,
    val body: String = "",
    val summary: String? = null,
    /** The user edited the summary: summarizing again asks first. */
    val summaryEdited: Boolean = false,
    /** The user's instruction for this note's summary (on top of the global one). */
    val summaryPrompt: String? = null,
    val summaryState: JobState = JobState.NONE,
    val summaryError: String? = null,
    /** OndolSaem model that made the summary. */
    val summaryModel: String? = null,
    val sourceUrl: String? = null,
    /** JSON with details of the source (site, channel, length, fetch time). */
    val sourceMeta: String? = null,
    /** NONE for memos; QUEUED/RUNNING while fetching, transcribing or recording; FAILED keeps [sourceError]. */
    val sourceState: JobState = JobState.NONE,
    val sourceError: String? = null,
    /** File name under files/audio (recordings and imported audio). */
    val audioFile: String? = null,
    val durationMs: Long? = null,
    val createdAt: Long,
    /** Changes whenever the text (title, original, summary) changes. */
    val updatedAt: Long,
    /** When the chunks were last made, and with what ([embedKey] = model + chunking version). */
    val embeddedAt: Long? = null,
    val embedKey: String? = null,
)

/** One line of a transcript or subtitle track, for playback and seeking. */
@Entity(
    tableName = "cues",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")],
)
data class CueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A piece of a note's text with its embedding (int8, see ml.Quant). */
@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("modelId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val ordinal: Int,
    val text: String,
    val title: String?,
    val modelId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
) {
    override fun equals(other: Any?) = other is ChunkEntity && other.id == id
    override fun hashCode() = id.hashCode()
}

/** What search needs of a chunk. */
data class ChunkVector(val id: Long, val noteId: Long, val embedding: ByteArray)
