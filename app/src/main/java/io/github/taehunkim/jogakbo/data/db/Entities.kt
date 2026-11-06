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
    /** When the decision model last checked the note against the tags (schema 2). */
    val taggedAt: Long? = null,
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

/** A tag the user defined (or one of the examples). [description] says what belongs to it and helps the decision model. */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val description: String = "",
    val ordinal: Int = 0,
    val createdAt: Long,
)

/** How a tag came to a note (or did not). */
enum class TagState {
    /** The decision model said yes: the tag is shown. */
    AUTO_YES,
    /** The decision model was unsure: offered to the user, not applied. */
    SUGGESTED,
    /** The decision model said no. */
    AUTO_NO,
    /** Added by the user; the decision model never changes it. */
    MANUAL,
    /** Removed or declined by the user; the decision model never adds it again. */
    REJECTED;

    val applied: Boolean get() = this == AUTO_YES || this == MANUAL
    val byUser: Boolean get() = this == MANUAL || this == REJECTED
}

/** A note's answer for one tag; [modelId] is the decision model that answered (null for the user's own choice). */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class NoteTagEntity(
    val noteId: Long,
    val tagId: Long,
    val state: TagState,
    val score: Float? = null,
    val modelId: String? = null,
    val at: Long,
)

/** A note's tag with its name, for the UI and the SDK. */
data class NoteTagName(val noteId: Long, val tagId: Long, val name: String, val state: TagState, val score: Float?)
