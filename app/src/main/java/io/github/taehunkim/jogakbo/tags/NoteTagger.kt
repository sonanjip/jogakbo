package io.github.taehunkim.jogakbo.tags

import android.util.Log
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteTagEntity
import io.github.taehunkim.jogakbo.data.db.TagDao
import io.github.taehunkim.jogakbo.data.db.TagEntity
import io.github.taehunkim.jogakbo.data.db.TagState
import io.github.taehunkim.jogakbo.ml.Decider
import io.github.taehunkim.ondolsaem.sdk.Decisions

/**
 * Tags notes with OndolSaem's decision model: for each note, one question per tag ("is this text about <tag>?"),
 * all in one call. Yes (≥ [YES]) applies the tag, unsure (≥ [SUGGEST]) offers it, anything else is a no. The user's own
 * choices ([TagState.MANUAL], [TagState.REJECTED]) are never changed. Runs after the embedding pass, so it follows the
 * embedding time in Settings.
 */
class NoteTagger(
    private val notes: NoteDao,
    private val tags: TagDao,
    private val decider: suspend () -> Decider?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Pass(val tagged: Int, val noModel: Boolean)

    /** Tags the notes that need it; stops early when [keepGoing] turns false. */
    suspend fun tagPending(onProgress: (Int, Int) -> Unit = { _, _ -> }, keepGoing: () -> Boolean = { true }): Pass {
        val all = tags.tags()
        if (all.isEmpty()) return Pass(0, false)
        val d = decider() ?: return Pass(0, true)
        val ids = tags.needingTags(d.modelId, all.size)
        var done = 0
        for (id in ids) {
            if (!keepGoing()) break
            val n = notes.get(id) ?: continue
            runCatching { tagNote(n, all, d) }.onFailure { Log.w(TAG, "note $id not tagged", it); if (it is kotlinx.coroutines.CancellationException) throw it }
            onProgress(++done, ids.size)
        }
        return Pass(done, false)
    }

    private suspend fun tagNote(n: NoteEntity, all: List<TagEntity>, d: Decider) {
        val startedAt = clock()
        val rows = tags.rows(n.id).associateBy { it.tagId }
        val changed = (n.taggedAt ?: 0) < n.updatedAt
        val todo = all.filter { t ->
            val r = rows[t.id]
            r == null || (!r.state.byUser && (changed || r.modelId != d.modelId))
        }
        val text = premise(n)
        if (todo.isEmpty() || text.isBlank()) { tags.setTagged(n.id, startedAt); return }
        val p = d.entail(text, todo.map { Decisions.hypothesis(it.name, it.description) })
        val out = todo.mapIndexed { i, t -> NoteTagEntity(n.id, t.id, stateFor(p[i]), p[i], d.modelId, startedAt) }
        tags.replaceAuto(n.id, out, startedAt)
    }

    companion object {
        private const val TAG = "Jogakbo.Tagger"
        const val YES = Decisions.YES
        const val SUGGEST = 0.40f
        private const val PREMISE_CHARS = 1500

        fun stateFor(p: Float): TagState = when {
            p >= YES -> TagState.AUTO_YES
            p >= SUGGEST -> TagState.SUGGESTED
            else -> TagState.AUTO_NO
        }

        /** Title and the summary, or the start of the original when there is no summary. */
        fun premise(n: NoteEntity): String {
            val text = n.summary?.takeIf { it.isNotBlank() } ?: n.body
            return (n.title + "\n" + text).replace(Regex("[#*_`>|\\[\\]]+"), " ").replace(Regex("\\s+"), " ").trim().take(PREMISE_CHARS)
        }
    }
}
