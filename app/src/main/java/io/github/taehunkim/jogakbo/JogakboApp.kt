package io.github.taehunkim.jogakbo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import io.github.taehunkim.jogakbo.data.db.ALL_MIGRATIONS
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import io.github.taehunkim.jogakbo.data.prefs.EmbedMode
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.fetch.UrlConnectionHttp
import io.github.taehunkim.jogakbo.index.EmbedScheduler
import io.github.taehunkim.jogakbo.index.NoteIndexer
import io.github.taehunkim.jogakbo.io.Backup
import io.github.taehunkim.jogakbo.jobs.SourceJob
import io.github.taehunkim.jogakbo.jobs.SummaryJob
import io.github.taehunkim.jogakbo.jobs.WorkJobs
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.notes.NoteJobs
import io.github.taehunkim.jogakbo.notes.NoteRepository
import io.github.taehunkim.jogakbo.record.RecordingService
import io.github.taehunkim.jogakbo.search.NoteSearch
import io.github.taehunkim.jogakbo.service.Approvals
import io.github.taehunkim.jogakbo.service.CallerCheck
import io.github.taehunkim.jogakbo.service.NotesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(val context: Context) {
    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { Room.databaseBuilder(context, AppDatabase::class.java, "jogakbo.db").addMigrations(*ALL_MIGRATIONS).build() }
    val settings = SettingsStore(context)
    /** Summaries, speech recognition and embeddings (OndolSaem). */
    val models = ModelHost(context, settings, appScope)
    val http = UrlConnectionHttp()
    val embedScheduler = EmbedScheduler(context, settings, appScope)
    val jobs: NoteJobs = WorkJobs(context) { embedScheduler.onNoteChanged() }
    val notes by lazy { NoteRepository(context, db.noteDao(), jobs) }
    val summaryJob by lazy { SummaryJob(context, db.noteDao(), models, settings, jobs) }
    val sourceJob by lazy { SourceJob(context, db.noteDao(), http, models, settings, jobs, { notes.audioDir }) }
    val indexer by lazy { NoteIndexer(db.noteDao(), models) }
    val tagger by lazy { io.github.taehunkim.jogakbo.tags.NoteTagger(db.noteDao(), db.tagDao(), { models.decider() }) }
    val tags by lazy { io.github.taehunkim.jogakbo.tags.TagRepository(db.tagDao(), onTagsChanged = { embedScheduler.onNoteChanged() }) }
    val search by lazy { NoteSearch(db.noteDao()) { q -> models.embedder()?.use { it.value.modelId to it.value.embedQuery(q) } } }
    val backup by lazy { Backup(context, db.noteDao(), { notes.audioDir }, onImported = { embedScheduler.onNoteChanged() }, tags = db.tagDao()) }
    val approvals by lazy { Approvals({ settings.apiApprovalsJson.first() }, settings::setApiApprovalsJson, appScope) }
    val callerCheck by lazy {
        CallerCheck(context, approvals).apply { onRequest = { pkg -> Notifications.approvalRequest(context, appLabel(pkg)) } }
    }
    val notesApi by lazy { NotesApi(db.noteDao(), search, BuildConfig.VERSION_NAME, tags = db.tagDao()) }

    /** The name another app shows in the launcher (its package name if it has none). */
    fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

class JogakboApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        container.embedScheduler.start()
        // exact screen-off times for "When idle" while the app runs (OndolSaem SDK's IdleState)
        io.github.taehunkim.ondolsaem.sdk.IdleState(this).watchScreen()
        container.appScope.launch {
            if (!container.settings.tagsSeeded.first()) {
                container.tags.seed(resources.getStringArray(R.array.tag_examples).toList())
                container.settings.setTagsSeeded()
            }
        }
        container.appScope.launch {
            container.models.refresh()
            // a recording the app was killed during: finish it from its raw audio
            val dao = container.db.noteDao()
            for (id in dao.pendingSources()) {
                val n = dao.get(id) ?: continue
                if (RecordingService.current.value?.noteId == id) continue
                if (n.sourceMeta?.contains("\"recording\":true") == true) container.jobs.fetchSource(id, n.kind)
            }
        }
        // the embedding model changed in OndolSaem: notes are embedded again
        container.appScope.launch {
            container.settings.embeddingModelId.distinctUntilChanged().drop(1).collect {
                if (it.isNotEmpty() && container.settings.embedMode.first() == EmbedMode.IMMEDIATE) container.embedScheduler.runNow(5)
            }
        }
        // a new decision model answers again
        container.appScope.launch {
            container.settings.decisionModelId.distinctUntilChanged().drop(1).collect {
                if (it.isNotEmpty() && container.settings.embedMode.first() == EmbedMode.IMMEDIATE) container.embedScheduler.runNow(5)
            }
        }
        // OndolSaem announces model changes by broadcast (also when this app is not running): re-check.
        io.github.taehunkim.ondolsaem.sdk.ModelChangedReceiver.handler = { container.appScope.launch { container.models.refresh() } }
    }
}

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current.applicationContext
    return remember { (ctx as JogakboApp).container }
}
