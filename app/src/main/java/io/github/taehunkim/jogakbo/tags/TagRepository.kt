package io.github.taehunkim.jogakbo.tags

import io.github.taehunkim.jogakbo.data.db.NoteTagEntity
import io.github.taehunkim.jogakbo.data.db.TagDao
import io.github.taehunkim.jogakbo.data.db.TagEntity
import io.github.taehunkim.jogakbo.data.db.TagState

/** The user's tags and their choices on notes. [onTagsChanged] lets the tagger look at the notes again. */
class TagRepository(
    private val dao: TagDao,
    private val onTagsChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Ok(val id: Long) : Result
        data object Blank : Result
        data object Duplicate : Result
    }

    /** Adds the example tags once (names in the app language). */
    suspend fun seed(names: List<String>) {
        for (n in names) if (dao.tagByName(n) == null) dao.insertTag(TagEntity(name = n, ordinal = dao.maxOrdinal() + 1, createdAt = clock()))
        onTagsChanged()
    }

    suspend fun add(name: String, description: String = ""): Result {
        val n = clean(name)
        if (n.isEmpty()) return Result.Blank
        if (dao.tagByName(n) != null) return Result.Duplicate
        val id = dao.insertTag(TagEntity(name = n, description = description.trim().take(MAX_DESCRIPTION), ordinal = dao.maxOrdinal() + 1, createdAt = clock()))
        onTagsChanged()
        return Result.Ok(id)
    }

    /** Renaming or describing a tag differently makes the model check it again (the user's own choices stay). */
    suspend fun update(id: Long, name: String, description: String): Result {
        val old = dao.tag(id) ?: return Result.Blank
        val n = clean(name)
        if (n.isEmpty()) return Result.Blank
        dao.tagByName(n)?.let { if (it.id != id) return Result.Duplicate }
        val d = description.trim().take(MAX_DESCRIPTION)
        if (n == old.name && d == old.description) return Result.Ok(id)
        dao.updateTag(old.copy(name = n, description = d))
        dao.clearAutoOfTag(id)
        onTagsChanged()
        return Result.Ok(id)
    }

    suspend fun delete(id: Long) = dao.deleteTag(id)

    /** Moves a tag one place up ([delta] -1) or down (+1). */
    suspend fun move(id: Long, delta: Int) {
        val list = dao.tags().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { k, t -> if (t.ordinal != k) dao.setOrdinal(t.id, k) }
    }

    /** The user puts [tagId] on the note (also accepting a suggestion). */
    suspend fun addToNote(noteId: Long, tagId: Long) = dao.upsert(listOf(NoteTagEntity(noteId, tagId, TagState.MANUAL, at = clock())))

    /** The user takes [tagId] off the note (or declines a suggestion); the model will not put it back. */
    suspend fun removeFromNote(noteId: Long, tagId: Long) = dao.upsert(listOf(NoteTagEntity(noteId, tagId, TagState.REJECTED, at = clock())))

    /** Forgets every answer of the model, so all notes are tagged again on the next pass. */
    suspend fun retagAll() {
        dao.clearAllAuto()
        onTagsChanged()
    }

    private fun clean(name: String) = name.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    companion object {
        const val MAX_NAME = 40
        const val MAX_DESCRIPTION = 120
    }
}
