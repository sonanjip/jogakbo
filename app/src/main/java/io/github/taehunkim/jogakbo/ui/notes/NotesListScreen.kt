package io.github.taehunkim.jogakbo.ui.notes

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.JobState
import io.github.taehunkim.jogakbo.data.db.NoteEntity
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.record.RecordingService
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.search.NoteFilter
import io.github.taehunkim.jogakbo.search.NoteHit
import io.github.taehunkim.jogakbo.search.NoteSearch
import io.github.taehunkim.jogakbo.ui.common.formatDate
import io.github.taehunkim.jogakbo.ui.common.kindIcon
import io.github.taehunkim.jogakbo.ui.common.kindLabel
import kotlinx.coroutines.delay
import io.github.taehunkim.jogakbo.ui.common.onMain
import kotlinx.coroutines.launch

/** What another app shared, waiting for the user to add it. */
sealed interface Shared {
    data class Link(val url: String) : Shared
    data class Text(val text: String) : Shared
    data class Audio(val uri: Uri) : Shared
    data class Document(val uri: Uri) : Shared
    data class File(val uri: Uri, val audio: Boolean, val name: String)
    data class Batch(val files: List<File>, val texts: List<String>) : Shared
}

/** The notes, newest first; search by meaning and words; the + menu adds memos, links, recordings and audio files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    onOpen: (Long) -> Unit, onNewMemo: (String?) -> Unit, onRecording: () -> Unit, onSettings: () -> Unit,
    shared: Shared? = null, onSharedHandled: () -> Unit = {},
) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val notes by c.db.noteDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val needIndex by remember {
        c.settings.embeddingModelId.flatMapLatest { c.db.noteDao().observeNeedingEmbeddingIds(io.github.taehunkim.jogakbo.index.NoteIndexer.key(it)) }.map { it.toSet() }
    }.collectAsStateWithLifecycle(emptySet())
    var query by rememberSaveable { mutableStateOf("") }
    var kinds by remember { mutableStateOf(emptySet<NoteKind>()) }
    var tagFilter by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val allTags by c.db.tagDao().observeTags().collectAsStateWithLifecycle(emptyList())
    val applied by c.db.tagDao().observeApplied().collectAsStateWithLifecycle(emptyList())
    val tagsOf = remember(applied) { applied.groupBy({ it.noteId }, { it.name }) }
    val notesWithTag = remember(applied) { applied.groupBy({ it.tagId }, { it.noteId }).mapValues { it.value.toSet() } }
    var hits by remember { mutableStateOf<List<NoteHit>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var askMissing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addLink by remember { mutableStateOf<String?>(null) }
    var addAudio by remember { mutableStateOf<Uri?>(null) }
    var addDocument by remember { mutableStateOf<Uri?>(null) }
    var addBatch by remember { mutableStateOf<Shared.Batch?>(null) }
    val autoLink by c.settings.autoSummary(NoteKind.LINK).collectAsStateWithLifecycle(true)
    val autoAudio by c.settings.autoSummary(NoteKind.AUDIO).collectAsStateWithLifecycle(true)
    val autoDocument by c.settings.autoSummary(NoteKind.DOCUMENT).collectAsStateWithLifecycle(true)
    val defaultNow = c.settings.embedMode.collectAsStateWithLifecycle(io.github.taehunkim.jogakbo.data.prefs.EmbedMode.IMMEDIATE).value == io.github.taehunkim.jogakbo.data.prefs.EmbedMode.IMMEDIATE

    LaunchedEffect(shared) {
        when (shared) {
            is Shared.Link -> addLink = shared.url
            is Shared.Audio -> addAudio = shared.uri
            is Shared.Document -> addDocument = shared.uri
            is Shared.Batch -> addBatch = shared
            is Shared.Text -> onNewMemo(shared.text)
            null -> return@LaunchedEffect
        }
        onSharedHandled()
    }
    LaunchedEffect(query, kinds, notes.size) {
        if (query.isBlank()) { hits = null; return@LaunchedEffect }
        delay(300) // typing
        hits = runCatching { c.search.search(query, NoteFilter(kinds)) }.getOrDefault(emptyList())
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) addAudio = uri }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) addDocument = uri }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { RecordingService.start(ctx); onRecording() }
    }
    fun record() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { RecordingService.start(ctx); onRecording() }
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val recording by RecordingService.current.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) stringResource(R.string.app_name) else selected.size.toString()) },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { if (io.github.taehunkim.jogakbo.Malbotdari.open(ctx, selected.take(10))) selected = emptySet() else askMissing = true }) { Text(stringResource(R.string.action_ask_chat)) }
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) } },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menu = true }, modifier = Modifier.testTag("add_menu")) { Icon(Icons.Filled.Add, stringResource(R.string.add)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_memo)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onNewMemo(null) }, modifier = Modifier.testTag("add_memo"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_link)) }, leadingIcon = { Icon(Icons.Filled.Link, null) }, onClick = { menu = false; addLink = "" }, modifier = Modifier.testTag("add_link"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_recording)) }, leadingIcon = { Icon(Icons.Filled.Mic, null) }, onClick = { menu = false; record() }, modifier = Modifier.testTag("add_recording"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_audio)) }, leadingIcon = { Icon(Icons.Filled.LibraryMusic, null) }, onClick = { menu = false; pickAudio.launch("audio/*") }, modifier = Modifier.testTag("add_audio"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_document)) }, leadingIcon = { Icon(kindIcon(NoteKind.DOCUMENT), null) }, onClick = { menu = false; pickDocument.launch(io.github.taehunkim.jogakbo.notes.Documents.MIME_TYPES) }, modifier = Modifier.testTag("add_document"))
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear)) } },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("search"),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteKind.entries.forEach { k ->
                    FilterChip(selected = k in kinds, onClick = { kinds = if (k in kinds) kinds - k else kinds + k }, label = { Text(stringResource(kindLabel(k))) }, leadingIcon = { Icon(kindIcon(k), null, Modifier.size(18.dp)) })
                }
            }
            if (allTags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("tag_filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allTags.forEach { t ->
                    FilterChip(
                        selected = t.id in tagFilter, onClick = { tagFilter = if (t.id in tagFilter) tagFilter - t.id else tagFilter + t.id },
                        label = { Text("#" + t.name) }, modifier = Modifier.testTag("tag_filter_${t.name}"),
                    )
                }
            }
            recording?.let { r ->
                TextButton(onClick = onRecording, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.recording_in_progress)) }
            }
            // any of the chosen tags
            val tagged = if (tagFilter.isEmpty()) null else tagFilter.flatMap { notesWithTag[it].orEmpty() }.toSet()
            val shown: List<Pair<NoteEntity, String?>> = (hits?.map { it.note to it.snippet }
                ?: notes.filter { kinds.isEmpty() || it.kind in kinds }.map { it to null })
                .filter { tagged == null || it.first.id in tagged }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (hits != null) R.string.search_none else R.string.notes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize().testTag("notes_list")) {
                items(shown, key = { it.first.id }) { (n, snippet) ->
                    NoteRow(n, snippet, tagsOf[n.id].orEmpty(), indexWaiting = n.id in needIndex,
                        selected = n.id in selected,
                        onLongClick = { selected = if (n.id in selected) selected - n.id else if (selected.size < 10) selected + n.id else selected },
                        onClick = { if (selected.isEmpty()) onOpen(n.id) else selected = if (n.id in selected) selected - n.id else if (selected.size < 10) selected + n.id else selected })
                    HorizontalDivider()
                }
            }
        }
    }

    if (askMissing) AlertDialog(onDismissRequest = { askMissing = false },
        text = { Text(stringResource(R.string.malbot_needed)) },
        confirmButton = { TextButton(onClick = { io.github.taehunkim.jogakbo.Malbotdari.openReleases(ctx); askMissing = false }) { Text(stringResource(R.string.malbot_get)) } },
        dismissButton = { TextButton(onClick = { askMissing = false }) { Text(stringResource(R.string.action_cancel)) } })
    addLink?.let { initial ->
        AddDialog(
            title = stringResource(R.string.add_link), initialUrl = initial, askUrl = true,
            defaultSummarize = autoLink, defaultNow = defaultNow, onDismiss = { addLink = null },
        ) { url, prompt, summarize, timing ->
            addLink = null
            scope.launch { val id = c.notes.addLink(url, prompt, summarize, timing); onMain { onOpen(id) } }
        }
    }
    addBatch?.let { batch ->
        val total = batch.files.size + batch.texts.size
        var selectedItems by remember(batch) { mutableStateOf((0 until total).toSet()) }
        AddDialog(title = stringResource(R.string.share_multiple_title, total), initialUrl = null, askUrl = false,
            defaultSummarize = autoDocument, defaultNow = defaultNow, onDismiss = { addBatch = null },
            canAdd = selectedItems.size in 1..io.github.taehunkim.jogakbo.notes.ShareCopyBudget.ITEM_LIMIT,
            extra = {
                Text(stringResource(R.string.share_limits), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    for (index in 0 until total) {
                        val label = if (index < batch.files.size) batch.files[index].name else batch.texts[index - batch.files.size].take(80)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(index in selectedItems, { checked ->
                                selectedItems = if (checked) selectedItems + index else selectedItems - index
                            }, modifier = Modifier.testTag("share_item_$index"))
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }) { _, prompt, summarize, timing ->
            val selection = selectedItems
            addBatch = null
            scope.launch {
                var failed = 0
                val budget = io.github.taehunkim.jogakbo.notes.ShareCopyBudget()
                for ((index, text) in batch.texts.withIndex()) {
                    if (index + batch.files.size !in selection) continue
                    try { c.notes.createMemo("", text, summarize, prompt, timing) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failed++; android.util.Log.w("Jogakbo", "Cannot import shared text", e) }
                }
                for ((index, file) in batch.files.withIndex()) {
                    if (index !in selection) continue
                    try {
                        if (file.audio) c.notes.addAudio(file.uri, prompt, summarize, timing, budget)
                        else c.notes.addDocument(file.uri, prompt, summarize, timing, budget)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failed++; android.util.Log.w("Jogakbo", "Cannot import shared file", e) }
                }
                if (failed > 0) android.widget.Toast.makeText(ctx, res.getString(R.string.share_failed_count, failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    addDocument?.let { uri ->
        AddDialog(title = stringResource(R.string.add_document_title), initialUrl = null, askUrl = false, defaultSummarize = autoDocument, defaultNow = defaultNow, onDismiss = { addDocument = null }) { _, prompt, summarize, timing ->
            addDocument = null
            scope.launch {
                runCatching { c.notes.addDocument(uri, prompt, summarize, timing) }
                    .onSuccess { id -> onMain { onOpen(id) } }
                    .onFailure { e ->
                        android.util.Log.w("Jogakbo", "cannot read $uri", e)
                        onMain { android.widget.Toast.makeText(ctx, res.getString(R.string.audio_cannot_read, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show() }
                    }
            }
        }
    }
    addAudio?.let { uri ->
        AddDialog(title = stringResource(R.string.add_audio), initialUrl = null, askUrl = false, defaultSummarize = autoAudio, defaultNow = defaultNow, onDismiss = { addAudio = null }) { _, prompt, summarize, timing ->
            addAudio = null
            scope.launch {
                runCatching { c.notes.addAudio(uri, prompt, summarize, timing) }
                    .onSuccess { id -> onMain { onOpen(id) } }
                    .onFailure { e ->
                        android.util.Log.w("Jogakbo", "cannot read $uri", e)
                        onMain { android.widget.Toast.makeText(ctx, res.getString(R.string.audio_cannot_read, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show() }
                    }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(n: NoteEntity, snippet: String?, tags: List<String>, indexWaiting: Boolean = false, selected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("note_${n.id}"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(kindIcon(n.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (selected) "✓ " else "") + n.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val preview = snippet ?: NoteSearch.snippet(n.summary ?: n.body)
            if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(ctx, n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status(n, indexWaiting)?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
            }
            if (tags.isNotEmpty()) Text(
                tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("note_tags_${n.id}"),
            )
            if (n.sourceState == JobState.RUNNING || n.summaryState == JobState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** A short status of the note's background work, or null when there is nothing to say. */
fun status(n: NoteEntity, indexWaiting: Boolean = false): Int? = when {
    n.sourceState == JobState.QUEUED || n.sourceState == JobState.RUNNING ->
        when (n.kind) { NoteKind.AUDIO -> R.string.state_transcribing; NoteKind.DOCUMENT -> R.string.state_reading_document; else -> R.string.state_fetching }
    n.sourceState == JobState.FAILED -> R.string.state_source_failed
    n.summaryState == JobState.QUEUED || n.summaryState == JobState.RUNNING -> R.string.state_summarizing
    n.summaryState == JobState.FAILED -> R.string.state_summary_failed
    n.summaryState == JobState.DEFERRED -> R.string.state_summary_waiting
    indexWaiting -> R.string.state_index_waiting
    else -> null
}

/**
 * Adding a link or an audio file: the address, whether to summarize (with an optional instruction), and whether to
 * summarize, index and tag right away ([defaultNow] from Settings; the callback gets null when the choice is the default).
 */
@Composable
fun AddDialog(
    title: String, initialUrl: String?, askUrl: Boolean, defaultSummarize: Boolean, defaultNow: Boolean, onDismiss: () -> Unit,
    canAdd: Boolean = true, extra: @Composable () -> Unit = {},
    onAdd: (url: String, prompt: String?, summarize: Boolean, timing: Boolean?) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var prompt by remember { mutableStateOf("") }
    var summarize by remember { mutableStateOf(defaultSummarize) }
    var now by remember(defaultNow) { mutableStateOf(defaultNow) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("add_dialog"),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extra()
                if (askUrl) OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.add_link_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_url"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(summarize, { summarize = it }, modifier = Modifier.testTag("add_summarize"))
                    Text(stringResource(R.string.add_summarize))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(now, { now = it }, modifier = Modifier.testTag("add_now"))
                    Text(stringResource(R.string.timing_now))
                }
                if (summarize) OutlinedTextField(
                    prompt, { prompt = it }, label = { Text(stringResource(R.string.summary_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.summary_prompt_hint)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("add_prompt"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url.trim(), prompt.trim().ifEmpty { null }, summarize, now.takeIf { it != defaultNow }) }, enabled = canAdd && (!askUrl || url.isNotBlank()), modifier = Modifier.testTag("add_confirm")) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
