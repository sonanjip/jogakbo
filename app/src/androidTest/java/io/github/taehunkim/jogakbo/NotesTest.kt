package io.github.taehunkim.jogakbo

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.jogakbo.audio.Pcm
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteDao
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.fetch.HttpClient
import io.github.taehunkim.jogakbo.fetch.HttpResponse
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.jogakbo.io.Backup
import io.github.taehunkim.jogakbo.jobs.SourceJob
import io.github.taehunkim.jogakbo.jobs.SummaryJob
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.notes.NoteRepository
import io.github.taehunkim.jogakbo.record.FileSource
import io.github.taehunkim.jogakbo.record.Recorder
import io.github.taehunkim.jogakbo.search.NoteSearch
import io.github.taehunkim.jogakbo.service.NotesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NotesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao
    private lateinit var jobs: FakeJobs
    private lateinit var repo: NoteRepository
    private lateinit var models: ModelHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { SettingsStore(ctx) }
    private val text = FakeText()
    private val embedder = FakeEmbedder()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.noteDao()
        jobs = FakeJobs()
        // the settings file outlives tests: automatic summaries start now unless a test says otherwise
        runBlocking { settings.setEmbedMode(io.github.taehunkim.jogakbo.data.prefs.EmbedMode.IMMEDIATE) }
        repo = NoteRepository(ctx, dao, jobs)
        models = ModelHost(ctx, settings, scope).apply {
            textOverride = { text }; embedderOverride = { embedder }; speechOverride = { FakeSpeech() }
        }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun memoSummaryAndEdits() = runBlocking {
        val id = repo.createMemo("", "# Trip plan\n\nWe fly to Jeju on Friday and return on Sunday. Book the hotel near the beach.")
        val n = dao.get(id)!!
        assertEquals("Trip plan", n.title)
        assertEquals(1, jobs.changes)
        repo.requestSummary(id, "List the to-dos")
        assertEquals(listOf(id), jobs.summaries)
        assertEquals(JobState.QUEUED, dao.get(id)!!.summaryState)
        assertTrue(SummaryJob(ctx, dao, models, settings, jobs).run(id))
        val s = dao.get(id)!!
        assertEquals("- the summary", s.summary)
        assertEquals(JobState.DONE, s.summaryState)
        assertEquals("fake-llm", s.summaryModel)
        assertTrue(text.calls.last().second.startsWith("Instructions for this summary:\nList the to-dos"))
        repo.updateSummary(id, "- my own summary")
        assertTrue(dao.get(id)!!.summaryEdited)
        // too short to summarize
        val short = repo.createMemo("x", "hi")
        SummaryJob(ctx, dao, models, settings, jobs).run(short)
        assertEquals(JobState.FAILED, dao.get(short)!!.summaryState)
        repo.delete(id)
        assertNull(dao.get(id))
    }

    @Test fun linkIsFetchedThenSummarized() = runBlocking {
        val html = "<html><head><title>Tea guide</title></head><body><article><h1>Tea guide</h1>" +
            "<p>Green tea is brewed at about eighty degrees for two minutes, longer makes it bitter.</p>" +
            "<p>Black tea likes boiling water and four minutes. Add milk if you like, the British way.</p>" +
            "<p>Oolong sits between them and can be brewed several times from the same leaves.</p></article></body></html>"
        val http = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(url, 200, "text/html; charset=utf-8", html)
            override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(url, 404, null, "")
        }
        val id = repo.addLink("example.com/tea", prompt = "Make a table")
        assertEquals(listOf(id), jobs.sources)
        assertEquals("https://example.com/tea", dao.get(id)!!.sourceUrl)
        val out = SourceJob(ctx, dao, http, models, settings, jobs, { repo.audioDir }).run(id)
        assertEquals(SourceJob.Outcome.DONE, out)
        val n = dao.get(id)!!
        assertEquals("Tea guide", n.title)
        assertTrue(n.body, n.body.contains("Green tea is brewed"))
        assertEquals(JobState.DONE, n.sourceState)
        assertEquals(listOf(id), jobs.summaries) // a prompt was given: summarize
        val y = repo.addLink("https://youtu.be/dQw4w9WgXcQ", summarize = false)
        assertEquals(NoteKind.YOUTUBE, dao.get(y)!!.kind)
    }

    @Test fun audioFileIsTranscribed() = runBlocking {
        val wav = File(ctx.cacheDir, "tone.wav").apply { writeBytes(Pcm.toWav(tone(21_000))) }
        val id = repo.addAudio(Uri.fromFile(wav))
        val n = dao.get(id)!!
        assertEquals(NoteKind.AUDIO, n.kind)
        assertTrue(File(repo.audioDir, n.audioFile!!).exists())
        assertEquals(SourceJob.Outcome.DONE, SourceJob(ctx, dao, NoNet, models, settings, jobs, { repo.audioDir }).run(id))
        val done = dao.get(id)!!
        assertEquals(JobState.DONE, done.sourceState)
        assertEquals(10, dao.cues(id).size)
        assertTrue(done.body.startsWith("**0:00** line at 0"))
        assertTrue((done.durationMs ?: 0) in 20_500..21_500)
        assertTrue(SummaryJob.input(done, dao.cues(id).map { io.github.taehunkim.jogakbo.audio.Cue(it.startMs, it.endMs, it.text) }).startsWith("line at 0 line at 2"))
        assertEquals(listOf(id), jobs.summaries) // audio is summarized by default
        repo.deleteAudio(id)
        assertNull(dao.get(id)!!.audioFile)
    }

    @Test fun embeddingStopsWhenAskedAndTheRestWaits() = runBlocking {
        repeat(3) { repo.createMemo("Memo $it", "Text number $it about something.") }
        val indexer = NoteIndexer(dao, models)
        var asked = 0
        // an idle pass stops when the phone is used again: here after the first note
        val pass = indexer.embedPending(keepGoing = { asked++ < 1 })
        assertEquals(1, pass.embedded)
        assertEquals(2, pass.left)
        assertEquals(2, dao.needingEmbedding(NoteIndexer.key(embedder.modelId)).size)
        assertEquals(2, indexer.embedPending().embedded)
    }

    @Test fun embeddingSearchAndApi() = runBlocking {
        val a = repo.createMemo("Kimchi recipe", "Napa cabbage, chili powder, garlic and fish sauce. Ferment for two days.")
        val b = repo.createMemo("Bike repair", "Fix a flat tire: remove the wheel, find the hole, patch the tube.")
        repo.createMemo("Meeting", "Quarterly budget review with the finance team on Monday.")
        val indexer = NoteIndexer(dao, models)
        val pass = indexer.embedPending()
        assertEquals(3, pass.embedded)
        assertEquals(0, dao.needingEmbedding(NoteIndexer.key(embedder.modelId)).size)
        assertEquals(0, indexer.embedPending().embedded) // nothing changed
        repo.updateMemo(b, "Bike repair", "Fix a flat tire and oil the chain.")
        assertEquals(listOf(b), dao.needingEmbedding(NoteIndexer.key(embedder.modelId)))
        indexer.embedPending()

        val search = NoteSearch(dao) { q -> embedder.modelId to embedder.embedQuery(q) }
        assertEquals(a, search.search("cabbage chili garlic").first().note.id)
        assertEquals(b, search.search("chain").first().note.id) // a word
        val api = NotesApi(dao, search, "0.1.0", tags = db.tagDao())
        val tools = JSONArray(api.tools())
        assertEquals(setOf("search_notes", "read_note", "search_in_note"), (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet())
        val found = JSONArray(api.runTool("search_notes", """{"query":"flat tire","kinds":"memo"}"""))
        assertEquals(b, found.getJSONObject(0).getLong("note_id"))
        val read = JSONObject(api.runTool("read_note", """{"note_id":$a}"""))
        assertTrue(read.getString("text").startsWith("# Kimchi recipe"))
        assertEquals("No matching notes.", api.runTool("search_notes", """{"query":"kimchi","kinds":"youtube"}"""))
        // tags: shown on results and usable as a filter
        val tags = io.github.taehunkim.jogakbo.tags.TagRepository(db.tagDao())
        val car = (tags.add("자동차") as io.github.taehunkim.jogakbo.tags.TagRepository.Result.Ok).id
        tags.addToNote(a, car)
        val byTag = JSONArray(api.runTool("search_notes", """{"tags":"#자동차"}"""))
        assertEquals(1, byTag.length()); assertEquals(a, byTag.getJSONObject(0).getLong("note_id"))
        assertEquals("자동차", byTag.getJSONObject(0).getJSONArray("tags").getString(0))
        assertEquals("자동차", JSONObject(api.runTool("read_note", """{"note_id":$a}""")).getJSONArray("tags").getString(0))
        assertEquals("No matching notes.", api.runTool("search_notes", """{"query":"flat tire","tags":"요리"}"""))
        val start = JSONObject(api.changesSince(0, 10)).getLong("cursor")
        Thread.sleep(5)
        repo.updateMemo(a, "Kimchi recipe", "More garlic.")
        val ch = JSONObject(api.changesSince(start, 10))
        assertEquals(a, ch.getJSONArray("ids").getLong(0))
        assertEquals(1, JSONArray(api.notes(longArrayOf(a, 999))).length())
    }

    @Test fun inNoteSearchIsScopedAndHasAnUnindexedFallback() = runBlocking {
        val id = repo.createMemo("Report", "## Page 1\n\nThe harbour inspection is scheduled for March.\n\n## Page 2\n\nThe launch code is cobalt.")
        repo.createMemo("Other", "The launch code is orange.")
        val search = NoteSearch(dao) { q -> embedder.modelId to embedder.embedQuery(q) }
        val api = NotesApi(dao, search, "test")
        val args = JSONObject().put("note_id", id).put("query", "cobalt").toString()
        val fallback = JSONObject(api.runTool("search_in_note", args))
        assertTrue(fallback.toString().contains("cobalt"))
        assertFalse(fallback.toString().contains("orange"))
        NoteIndexer(dao, models).embedPending()
        assertTrue(JSONObject(api.runTool("search_in_note", args)).toString().contains("cobalt"))
    }

    @Test fun documentOriginalSurvivesBackupIncludingTextFiles() = runBlocking {
        val file = File(repo.docDir, "original-test.txt").apply { writeText("Original document text, kept with the note.") }
        val id = repo.createMemo("Document", "Extracted text")
        dao.update(dao.get(id)!!.copy(kind = NoteKind.DOCUMENT, sourceMeta = JSONObject().put("docFile", file.name).put("docName", "report.txt").toString()))
        val zip = ByteArrayOutputStream()
        Backup(ctx, dao, { repo.audioDir }).export(zip, false, true)
        val other = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            Backup(ctx, other.noteDao(), { repo.audioDir }).import(ByteArrayInputStream(zip.toByteArray()), "backup.zip")
            val n = other.noteDao().all().single()
            assertEquals(NoteKind.DOCUMENT, n.kind)
            assertEquals(file.readText(), File(repo.docDir, io.github.taehunkim.jogakbo.notes.Documents.fileName(n)!!).readText())
        } finally { other.close(); file.delete() }
    }

    @Test fun exportThenImport() = runBlocking {
        val m = repo.createMemo("Ideas", "- one\n- **two**")
        repo.updateSummary(m, "Two ideas.")
        val wav = File(ctx.cacheDir, "tone2.wav").apply { writeBytes(Pcm.toWav(tone(3_000))) }
        val au = repo.addAudio(Uri.fromFile(wav))
        SourceJob(ctx, dao, NoNet, models, settings, jobs, { repo.audioDir }).run(au)
        val backup = Backup(ctx, dao, { repo.audioDir })
        val zip = ByteArrayOutputStream()
        val ex = backup.export(zip, includeAudio = true, includeSummaries = true)
        assertEquals(2, ex.notes); assertEquals(1, ex.audio)
        val before = dao.all().associateBy { it.uuid }
        val audioName = dao.get(au)!!.audioFile!!
        // a fresh database: everything comes back
        val db2 = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            File(repo.audioDir, audioName).delete()
            val b2 = Backup(ctx, db2.noteDao(), { repo.audioDir })
            val r = b2.import(ByteArrayInputStream(zip.toByteArray()), "backup.zip")
            assertEquals(2, r.added)
            for (n in db2.noteDao().all()) {
                val o = before.getValue(n.uuid)
                assertEquals(o.title, n.title); assertEquals(o.body, n.body); assertEquals(o.summary, n.summary)
                assertEquals(o.summaryEdited, n.summaryEdited); assertEquals(o.kind, n.kind); assertEquals(o.createdAt, n.createdAt)
            }
            val audioNote = db2.noteDao().all().first { it.kind == NoteKind.AUDIO }
            assertTrue(File(repo.audioDir, audioNote.audioFile!!).exists())
            assertEquals(dao.cues(au).map { it.text }, db2.noteDao().cues(audioNote.id).map { it.text })
            // again: nothing newer
            assertEquals(2, b2.import(ByteArrayInputStream(zip.toByteArray()), "backup.zip").skipped)
            // a plain Markdown file
            val p = b2.import(ByteArrayInputStream("# Groceries\n\n- [ ] milk".toByteArray()), "list.md")
            assertEquals(1, p.added)
            assertNotNull(db2.noteDao().all().firstOrNull { it.title == "Groceries" && it.body == "- [ ] milk" })
        } finally { db2.close() }
    }

    @Test fun tagsAreDecidedKeptWhenTheUserChoseAndCheckedAgainAfterEdits() = runBlocking {
        val tagDao = db.tagDao()
        var changed = 0
        val tags = io.github.taehunkim.jogakbo.tags.TagRepository(tagDao, onTagsChanged = { changed++ })
        tags.seed(listOf("여행", "요리", "돈"))
        assertTrue(tags.add(" #요리 ") is io.github.taehunkim.jogakbo.tags.TagRepository.Result.Duplicate)
        val decider = FakeDecider()
        var available = true
        val tagger = io.github.taehunkim.jogakbo.tags.NoteTagger(dao, tagDao, { if (available) decider else null })
        val trip = repo.createMemo("제주 여행", "3박 4일 일정")
        val misc = repo.createMemo("메모", "maybe something")
        assertEquals(2, tagger.tagPending().tagged)
        assertEquals("one call per note", 2, decider.calls)
        fun names(id: Long, st: io.github.taehunkim.jogakbo.data.db.TagState) = runBlocking { tagDao.rows(id) }.filter { it.state == st }.map { r -> runBlocking { tagDao.tag(r.tagId)!!.name } }.toSet()
        assertEquals(setOf("여행"), names(trip, io.github.taehunkim.jogakbo.data.db.TagState.AUTO_YES))
        assertEquals(setOf("여행", "요리", "돈"), names(misc, io.github.taehunkim.jogakbo.data.db.TagState.SUGGESTED))
        assertEquals("nothing left", 0, tagger.tagPending().tagged)

        // the user's choices stay through edits and other models
        val food = tagDao.tagByName("요리")!!.id
        val travel = tagDao.tagByName("여행")!!.id
        tags.addToNote(trip, food); tags.removeFromNote(trip, travel)
        repo.updateMemo(trip, "제주 여행", "3박 4일 일정, 흑돼지 맛집")
        assertEquals(1, tagger.tagPending().tagged)
        assertEquals(setOf("요리"), names(trip, io.github.taehunkim.jogakbo.data.db.TagState.MANUAL))
        assertEquals(setOf("여행"), names(trip, io.github.taehunkim.jogakbo.data.db.TagState.REJECTED))
        assertEquals(setOf("돈"), names(trip, io.github.taehunkim.jogakbo.data.db.TagState.AUTO_NO))

        // a new tag is checked on every note; editing a tag checks it again; a new model answers again
        tags.add("일정")
        assertEquals(2, tagger.tagPending().tagged)
        assertTrue("일정" in names(trip, io.github.taehunkim.jogakbo.data.db.TagState.AUTO_YES))
        tags.update(tagDao.tagByName("일정")!!.id, "맛집", "")
        tagger.tagPending()
        assertTrue("맛집" in names(trip, io.github.taehunkim.jogakbo.data.db.TagState.AUTO_YES))
        val other = io.github.taehunkim.jogakbo.tags.NoteTagger(dao, tagDao, { FakeDecider("other-nli") })
        assertEquals(2, other.tagPending().tagged)
        // without a model nothing happens
        available = false
        tags.retagAll()
        assertTrue(tagger.tagPending().noModel)
        assertTrue(changed >= 4)
    }

    @Test fun tagsTravelWithTheBackup() = runBlocking {
        val tagDao = db.tagDao()
        val tags = io.github.taehunkim.jogakbo.tags.TagRepository(tagDao)
        tags.seed(listOf("여행", "요리"))
        tags.add("돈", "지출, 영수증")
        val trip = repo.createMemo("제주 여행", "일정")
        tags.addToNote(trip, tagDao.tagByName("여행")!!.id)
        tags.removeFromNote(trip, tagDao.tagByName("요리")!!.id)
        val zip = ByteArrayOutputStream()
        Backup(ctx, dao, { repo.audioDir }, tags = tagDao).export(zip, includeAudio = false, includeSummaries = true)
        val db2 = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val t2 = db2.tagDao()
            io.github.taehunkim.jogakbo.tags.TagRepository(t2).add("요리", "레시피") // already here: keeps its own description
            Backup(ctx, db2.noteDao(), { repo.audioDir }, tags = t2).import(ByteArrayInputStream(zip.toByteArray()), "b.zip")
            assertEquals(setOf("여행", "요리", "돈"), t2.tags().map { it.name }.toSet())
            assertEquals("지출, 영수증", t2.tagByName("돈")!!.description)
            assertEquals("레시피", t2.tagByName("요리")!!.description)
            val n = db2.noteDao().all().single()
            val rows = t2.ofNotes(listOf(n.id)).associate { it.name to it.state }
            assertEquals(io.github.taehunkim.jogakbo.data.db.TagState.MANUAL, rows["여행"])
            assertEquals(io.github.taehunkim.jogakbo.data.db.TagState.REJECTED, rows["요리"])
            // a Markdown file from another app with tags creates them
            Backup(ctx, db2.noteDao(), { repo.audioDir }, tags = t2).import(ByteArrayInputStream("---\ntitle: x\ntags:\n  - 독서\n  - \"#여행\"\n---\nbody".toByteArray()), "x.md")
            val x = db2.noteDao().all().first { it.title == "x" }
            assertEquals(setOf("독서", "여행"), t2.ofNotes(listOf(x.id)).map { it.name }.toSet())
        } finally { db2.close() }
    }

    @Test fun recordingTranscribesWhileRecording() = runBlocking {
        val dir = File(ctx.cacheDir, "rec-test").apply { deleteRecursively(); mkdirs() }
        val src = File(dir, "src.pcm").apply { writeBytes(tone(12_000)) }
        val heard = ArrayList<io.github.taehunkim.jogakbo.audio.Cue>()
        val r = Recorder(FileSource(src, speed = 3.0), File(dir, "rec.pcm"), File(dir, "rec.m4a"), FakeSpeech(), scope, { synchronized(heard) { heard += it } }, passEveryMs = 300)
        r.start()
        var during = 0
        while (!r.ended) { kotlinx.coroutines.delay(100); during = synchronized(heard) { heard.size } }
        val len = r.stop()
        assertTrue("lines during recording: $during", during >= 2)
        assertTrue(len in 11_500..12_100)
        // each stretch of audio is given out once, in order (the fake names lines by their offset in the piece it heard)
        val starts = heard.map { it.startMs }
        assertEquals(starts.sorted().distinct(), starts)
        assertTrue("lines: ${heard.size}", heard.size >= 5)
        val mp = MediaPlayer().apply { setDataSource(File(dir, "rec.m4a").path); prepare() }
        assertTrue("m4a length ${mp.duration}", mp.duration in 11_000..12_500)
        mp.release()
        assertFalse(File(dir, "rec.pcm.tail").exists())
        dir.deleteRecursively()
        Unit
    }

    private object NoNet : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) = throw java.io.IOException("offline")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = throw java.io.IOException("offline")
    }

    /** A 440 Hz tone, 16 kHz mono 16-bit. */
    private fun tone(ms: Int): ByteArray {
        val n = ms * 16
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort((sin(2 * PI * 440 * i / 16000) * 8000).toInt().toShort())
        return b.array()
    }
}
