package io.github.taehunkim.jogakbo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.notes.NoteRepository
import io.github.taehunkim.jogakbo.notes.Timing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The summary of a new note starts now or waits, by the Settings default and the note's own choice. */
@RunWith(AndroidJUnit4::class)
class TimingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val jobs = FakeJobs()
    private val body = "We fly to Jeju on Friday and return on Sunday. Book the hotel near the beach, rent a car."

    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build() }
    @After fun tearDown() { db.close() }

    private fun repo(defaultNow: Boolean) = NoteRepository(ctx, db.noteDao(), jobs, defaultNow = { defaultNow })

    @Test fun defaultNowStartsTheSummary() = runBlocking {
        val id = repo(true).createMemo("", body, summarize = true)
        assertEquals(listOf(id), jobs.summaries)
        assertEquals(JobState.QUEUED, db.noteDao().get(id)!!.summaryState)
        assertNull(Timing.override(db.noteDao().get(id)!!))
    }

    @Test fun defaultLaterKeepsTheSummaryWaiting() = runBlocking {
        val before = jobs.summaries.size
        val id = repo(false).createMemo("", body, summarize = true, prompt = "List the to-dos")
        assertEquals(before, jobs.summaries.size)
        val n = db.noteDao().get(id)!!
        assertEquals(JobState.DEFERRED, n.summaryState)
        assertEquals("List the to-dos", n.summaryPrompt)
        assertTrue(db.noteDao().deferredSummaries().contains(id))
    }

    @Test fun theNoteCanChooseNowAgainstALaterDefault() = runBlocking {
        val id = repo(false).createMemo("", body, summarize = true, timing = true)
        assertTrue(jobs.summaries.contains(id))
        assertEquals(true, Timing.override(db.noteDao().get(id)!!))
        assertTrue(Timing.isNow(db.noteDao().get(id)!!, false))
    }

    @Test fun theNoteCanChooseLaterAgainstANowDefault() = runBlocking {
        val id = repo(true).createMemo("", body, summarize = true, timing = false)
        assertEquals(JobState.DEFERRED, db.noteDao().get(id)!!.summaryState)
        assertEquals(false, Timing.override(db.noteDao().get(id)!!))
    }

    @Test fun theUsersOwnSummaryRequestIsNeverDeferred() = runBlocking {
        val r = repo(false)
        val id = r.createMemo("", body, summarize = true)
        r.requestSummary(id)
        assertEquals(JobState.QUEUED, db.noteDao().get(id)!!.summaryState)
        assertTrue(jobs.summaries.contains(id))
    }

    @Test fun overrideKeepsOtherSourceDetails() {
        val meta = Timing.withOverride("""{"summarize":true}""", false)!!
        assertEquals(false, Timing.override(meta))
        assertTrue(meta.contains("summarize"))
        assertNull(Timing.withOverride(null, null))
        assertNull(Timing.override(Timing.withOverride(meta, null)))
    }
}
