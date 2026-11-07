package io.github.taehunkim.jogakbo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.jobs.SummaryJob
import io.github.taehunkim.jogakbo.ml.Lease
import io.github.taehunkim.jogakbo.ml.TextModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A summary that waits for its pass must survive a language model that is out of reach at that moment: it goes back to
 * waiting (the next idle, scheduled or manual pass tries again) instead of failing for good; the manual pass also
 * retries summaries that failed earlier.
 */
@RunWith(AndroidJUnit4::class)
class SummaryRetryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val c get() = (ctx.applicationContext as JogakboApp).container
    private lateinit var db: AppDatabase
    private var model: TextModel? = null
    private val body = "We fly to Jeju on Friday and return on Sunday. Book the hotel near the beach, rent a car, and check the weather."

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        c.models.textOverride = { model }
    }

    @After fun tearDown() { c.models.textOverride = null; db.close() }

    private fun job() = SummaryJob(ctx, db.noteDao(), c.models, c.settings, FakeJobs())

    private suspend fun note(state: JobState): Long = db.noteDao().insert(
        NoteEntity(uuid = java.util.UUID.randomUUID().toString(), kind = NoteKind.MEMO, title = "Trip", body = body, summaryState = state, createdAt = 1, updatedAt = 1),
    )

    @Test fun missingModelKeepsAWaitingSummaryWaiting() = runBlocking {
        val id = note(JobState.DEFERRED)
        model = null
        assertFalse(job().run(id, keepWaiting = true))
        assertEquals(JobState.DEFERRED, db.noteDao().get(id)!!.summaryState)
        assertTrue(db.noteDao().deferredSummaries().contains(id))
    }

    @Test fun withoutKeepWaitingItStillFailsAsBefore() = runBlocking {
        val id = note(JobState.QUEUED)
        model = null
        assertTrue(job().run(id))
        assertEquals(JobState.FAILED, db.noteDao().get(id)!!.summaryState)
    }

    @Test fun theNextPassSummarizesIt() = runBlocking {
        val id = note(JobState.DEFERRED)
        model = null
        job().run(id, keepWaiting = true)
        model = FakeText()
        assertTrue(job().run(id, keepWaiting = true))
        val n = db.noteDao().get(id)!!
        assertEquals(JobState.DONE, n.summaryState)
        assertEquals("- the summary", n.summary)
    }

    @Test fun failedSummariesAreListedForTheManualPass() = runBlocking {
        val failed = note(JobState.FAILED)
        val waiting = note(JobState.DEFERRED)
        assertEquals(listOf(failed), db.noteDao().failedSummaries())
        assertEquals(listOf(waiting), db.noteDao().deferredSummaries())
    }
}
