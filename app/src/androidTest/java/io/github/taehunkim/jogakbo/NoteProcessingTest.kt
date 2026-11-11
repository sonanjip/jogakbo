package io.github.taehunkim.jogakbo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.data.db.*
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.jogakbo.ml.*
import io.github.taehunkim.jogakbo.notes.*
import io.github.taehunkim.jogakbo.tags.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteProcessingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var tags: TagRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = NoteRepository(ctx, db.noteDao(), FakeJobs())
        tags = TagRepository(db.tagDao())
    }
    @After fun teardown() { scope.cancel(); db.close() }

    @Test fun onlyMissingTagQuestionsAreAskedIncludingNegativeAnswers() = runBlocking {
        tags.seed(listOf("여행", "요리"))
        val id = repo.createMemo("메모", "무관한 내용")
        val d = FakeDecider()
        val tagger = NoteTagger(db.noteDao(), db.tagDao(), { d })
        tagger.tagPending()
        val before = db.tagDao().rows(id)
        assertTrue(before.all { it.state == TagState.AUTO_NO })
        tags.add("일정")
        tagger.tagPending()
        assertEquals(before, db.tagDao().rows(id).filter { it.tagId in before.map { r -> r.tagId } })
        assertEquals(0, tagger.tagPending().tagged)
        val added = repo.createMemo("새 메모", "내용")
        assertEquals(1, tagger.tagPending().tagged)
        assertEquals(3, db.tagDao().rows(added).size)
    }

    @Test fun forcedTaggingOnlyTouchesOneNoteAndPreservesUserChoicesAndOldResultsOnFailure() = runBlocking {
        tags.seed(listOf("여행", "요리", "돈"))
        val id = repo.createMemo("여행", "내용")
        val other = repo.createMemo("다른 노트", "내용")
        var fail = false
        var available = true
        val decider = object : Decider {
            override val modelId = "controlled"
            override suspend fun entail(premise: String, hypotheses: List<String>): FloatArray {
                check(!fail) { "model failed" }
                return FloatArray(hypotheses.size) { 0.95f }
            }
        }
        val tagger = NoteTagger(db.noteDao(), db.tagDao(), { if (available) decider else null })
        tagger.tagPending()
        val all = db.tagDao().tags()
        tags.addToNote(id, all[0].id)
        tags.removeFromNote(id, all[1].id)
        val otherBefore = db.tagDao().rows(other)
        assertEquals(ProcessingOutcome.DONE, tagger.tagOne(id, force = true))
        assertEquals(TagState.MANUAL, db.tagDao().rows(id).first { it.tagId == all[0].id }.state)
        assertEquals(TagState.REJECTED, db.tagDao().rows(id).first { it.tagId == all[1].id }.state)
        assertEquals(otherBefore, db.tagDao().rows(other))
        val before = db.tagDao().rows(id)
        fail = true
        assertTrue(runCatching { tagger.tagOne(id, force = true) }.isFailure)
        assertEquals(before, db.tagDao().rows(id))
        available = false
        assertEquals(ProcessingOutcome.NO_MODEL, tagger.tagOne(id, force = true))
        assertEquals(before, db.tagDao().rows(id))
        available = true; fail = false
        tags.retagAll()
        assertEquals(2, tagger.tagPending().tagged)
    }

    @Test fun changedTagDefinitionDuringInferenceDoesNotSaveAnObsoleteAnswer() = runBlocking {
        tags.seed(listOf("여행"))
        val id = repo.createMemo("여행", "내용")
        val tag = db.tagDao().tags().single()
        val d = object : Decider {
            override val modelId = "fake"
            override suspend fun entail(premise: String, hypotheses: List<String>): FloatArray {
                tags.update(tag.id, "요리", "새 설명")
                return floatArrayOf(0.95f)
            }
        }
        val tagger = NoteTagger(db.noteDao(), db.tagDao(), { d })
        tagger.tagPending()
        assertTrue(db.tagDao().rows(id).isEmpty())
        assertEquals(listOf(id), db.tagDao().needingTags())
    }

    @Test fun indexingOneNoteWorksEvenIfItWasAlreadyIndexedAndDoesNotTagOrSummarize() = runBlocking {
        val id = repo.createMemo("선택한 노트", "내용")
        val other = repo.createMemo("다른 노트", "내용")
        val models = ModelHost(ctx, SettingsStore(ctx), scope).apply { embedderOverride = { FakeEmbedder() } }
        val indexer = NoteIndexer(db.noteDao(), models)
        assertEquals(ProcessingOutcome.DONE, indexer.embedOne(id))
        assertEquals(ProcessingOutcome.DONE, indexer.embedOne(id))
        assertNotNull(db.noteDao().get(id)!!.embeddedAt)
        assertNull(db.noteDao().get(other)!!.embeddedAt)
        assertNull(db.noteDao().get(id)!!.summary)
        assertTrue(db.tagDao().rows(id).isEmpty())
        models.embedderOverride = { null }
        assertEquals(ProcessingOutcome.NO_MODEL, indexer.embedOne(other))
        assertNull(db.noteDao().get(other)!!.embeddedAt)
        db.noteDao().delete(id)
        assertEquals(ProcessingOutcome.NOT_FOUND, indexer.embedOne(id))
    }
}
