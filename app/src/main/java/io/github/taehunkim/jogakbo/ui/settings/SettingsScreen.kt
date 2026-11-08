package io.github.taehunkim.jogakbo.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.BuildConfig
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.data.db.AppDatabase
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.EmbedMode
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.jogakbo.data.prefs.SummaryLanguage
import io.github.taehunkim.jogakbo.index.EmbedScheduler
import io.github.taehunkim.jogakbo.index.EmbedWorker
import io.github.taehunkim.jogakbo.index.NoteIndexer
import androidx.compose.material3.LinearProgressIndicator
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.ui.common.WarningCard
import io.github.taehunkim.jogakbo.ui.common.kindLabel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tabs: Notes (summaries, embedding), Tags, Data (export, import), Connections (OndolSaem, other apps), App (language, about). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val tabs = listOf(R.string.settings_tab_notes, R.string.settings_tab_tags, R.string.settings_tab_data, R.string.settings_tab_connections, R.string.settings_tab_app)
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }, modifier = Modifier.testTag("settings_tab_$i")) }
            }
            key(tab) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        0 -> NotesTab()
                        TAB_TAGS -> TagsTab()
                        2 -> DataTab()
                        TAB_CONNECTIONS -> ConnectionsTab()
                        else -> AppTab()
                    }
                }
            }
        }
    }
}

const val TAB_TAGS = 1
const val TAB_CONNECTIONS = 3

@Composable
internal fun Section(title: Int, desc: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NotesTab() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()

    // summaries
    Section(R.string.settings_summary, R.string.settings_summary_desc)
    val savedPrompt by c.settings.systemPrompt.collectAsStateWithLifecycle("")
    var prompt by remember(savedPrompt) { mutableStateOf(savedPrompt) }
    OutlinedTextField(
        value = prompt, onValueChange = { prompt = it.take(SettingsStore.MAX_PROMPT) },
        minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("system_prompt"),
        label = { Text(stringResource(R.string.settings_system_prompt)) },
        placeholder = { Text(stringResource(R.string.settings_system_prompt_hint)) },
        supportingText = { Text("${prompt.length} / ${SettingsStore.MAX_PROMPT}") },
    )
    OutlinedButton(onClick = { scope.launch { c.settings.setSystemPrompt(prompt) } }, enabled = prompt.trim() != savedPrompt, modifier = Modifier.testTag("system_prompt_save")) {
        Text(stringResource(R.string.action_save))
    }
    Text(stringResource(R.string.settings_summary_language), style = MaterialTheme.typography.titleSmall)
    val lang by c.settings.summaryLanguage.collectAsStateWithLifecycle(SummaryLanguage.ORIGINAL)
    for ((l, label) in listOf(SummaryLanguage.ORIGINAL to R.string.summary_language_original, SummaryLanguage.APP to R.string.summary_language_app)) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = lang == l, role = Role.RadioButton) { scope.launch { c.settings.setSummaryLanguage(l) } }.testTag("summary_lang_${l.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = lang == l, onClick = null)
            Text(stringResource(label), Modifier.padding(start = 8.dp))
        }
    }
    Text(stringResource(R.string.settings_auto_summary), style = MaterialTheme.typography.titleSmall)
    for (k in NoteKind.entries) {
        val on by c.settings.autoSummary(k).collectAsStateWithLifecycle(k != NoteKind.MEMO)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(kindLabel(k)), Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { v -> scope.launch { c.settings.setAutoSummary(k, v) } }, modifier = Modifier.testTag("auto_summary_${k.wire}"))
        }
    }
    HorizontalDivider()

    // embedding
    Section(R.string.settings_embedding, R.string.settings_embedding_desc)
    val mode by c.settings.embedMode.collectAsStateWithLifecycle(EmbedMode.IMMEDIATE)
    val minutes by c.settings.scheduleMinutes.collectAsStateWithLifecycle(SettingsStore.DEFAULT_SCHEDULE_MINUTES)
    val chargingOnly by c.settings.scheduleChargingOnly.collectAsStateWithLifecycle(true)
    var pickTime by remember { mutableStateOf(false) }
    val options = listOf(
        EmbedMode.IMMEDIATE to (R.string.embed_immediate to R.string.embed_immediate_desc),
        EmbedMode.IDLE to (R.string.embed_idle to R.string.embed_idle_desc),
        EmbedMode.SCHEDULED to (R.string.embed_scheduled to R.string.embed_scheduled_desc),
        EmbedMode.MANUAL to (R.string.embed_manual to R.string.embed_manual_desc),
    )
    for ((m, labels) in options) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = mode == m, role = Role.RadioButton) { scope.launch { c.settings.setEmbedMode(m) } }.testTag("embed_${m.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == m, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(labels.first))
                Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m == EmbedMode.IDLE && mode == EmbedMode.IDLE) {
            val preset by c.settings.idlePreset.collectAsStateWithLifecycle(io.github.taehunkim.ondolsaem.sdk.IdlePreset.DEFAULT)
            Column(Modifier.padding(start = 40.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    io.github.taehunkim.ondolsaem.sdk.IdlePreset.entries.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { scope.launch { c.settings.setIdlePreset(p) } },
                            label = {
                                Text(stringResource(when (p) {
                                    io.github.taehunkim.ondolsaem.sdk.IdlePreset.FAST -> R.string.idle_preset_fast
                                    io.github.taehunkim.ondolsaem.sdk.IdlePreset.NORMAL -> R.string.idle_preset_normal
                                    io.github.taehunkim.ondolsaem.sdk.IdlePreset.RELAXED -> R.string.idle_preset_relaxed
                                }))
                            },
                            modifier = Modifier.testTag("idle_${p.name}"),
                        )
                    }
                }
                val (high, mid, low) = preset.minutesOff
                Text(stringResource(R.string.idle_preset_rule, high, mid, low), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m == EmbedMode.SCHEDULED && mode == EmbedMode.SCHEDULED) {
            Column(Modifier.padding(start = 40.dp)) {
                TextButton(onClick = { pickTime = true }) { Text(stringResource(R.string.embed_at, formatMinutes(minutes))) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(chargingOnly, { v -> scope.launch { c.settings.setScheduleChargingOnly(v) } })
                    Text(stringResource(R.string.embed_charging_only))
                }
            }
        }
    }
    val waiting by remember { c.settings.embeddingModelId.flatMapLatest { c.db.noteDao().observeNeedingEmbedding(NoteIndexer.key(it)) } }.collectAsStateWithLifecycle(0)
    val waitingSummaries by remember { c.db.noteDao().observeDeferredSummaries() }.collectAsStateWithLifecycle(0)
    Text(
        if (waitingSummaries > 0) stringResource(R.string.embed_waiting_detail, waitingSummaries, waiting) else stringResource(R.string.embed_waiting, waiting),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("embed_waiting"),
    )
    EmbedProgress()
    OutlinedButton(onClick = { c.embedScheduler.runNow() }, modifier = Modifier.testTag("embed_now")) { Text(stringResource(R.string.embed_now)) }
    if (pickTime) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { pickTime = false; scope.launch { c.settings.setScheduleMinutes(state.hour * 60 + state.minute) } }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state) },
        )
    }
}

/** What the summarize-index-tag pass is doing right now (any trigger), or what the last one did. */
@Composable
private fun EmbedProgress() {
    val context = LocalContext.current
    val infos by remember {
        WorkManager.getInstance(context).getWorkInfosFlow(
            WorkQuery.fromUniqueWorkNames(EmbedScheduler.NOW, EmbedScheduler.IDLE, EmbedScheduler.SCHEDULED, EmbedScheduler.AUTO),
        )
    }.collectAsStateWithLifecycle(emptyList())
    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val enqueuedNow = infos.any { it.state == WorkInfo.State.ENQUEUED && EmbedScheduler.NOW in it.tags }
    // the last manual pass (the periodic ones go back to ENQUEUED, the pass after a change is not the user's)
    val last = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED && EmbedScheduler.NOW in it.tags && it.outputData.keyValueMap.containsKey(EmbedWorker.OUT_EMBEDDED) }
    when {
        running != null -> {
            val p = running.progress
            val total = p.getInt(EmbedWorker.PROGRESS_TOTAL, 0)
            val done = p.getInt(EmbedWorker.PROGRESS_DONE, 0)
            val text = when (p.getString(EmbedWorker.PROGRESS_STAGE)) {
                EmbedWorker.STAGE_SUMMARY -> stringResource(R.string.embed_stage_summary, done + 1, total, p.getString(EmbedWorker.PROGRESS_NOTE).orEmpty())
                EmbedWorker.STAGE_INDEX -> if (total > 0) stringResource(R.string.embed_stage_index, done, total) else stringResource(R.string.embed_stage_index_start)
                EmbedWorker.STAGE_TAGS -> stringResource(R.string.embed_stage_tags)
                else -> stringResource(R.string.embed_enqueued)
            }
            Column(Modifier.testTag("embed_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        enqueuedNow -> Text(stringResource(R.string.embed_enqueued), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("embed_progress"))
        last != null -> {
            val o = last.outputData
            Text(
                stringResource(R.string.embed_result, o.getInt(EmbedWorker.OUT_SUMMARIZED, 0), o.getInt(EmbedWorker.OUT_EMBEDDED, 0), o.getInt(EmbedWorker.OUT_TAGGED, 0)) +
                    if (o.getBoolean(EmbedWorker.OUT_BLOCKED, false)) "\n" + stringResource(R.string.embed_result_blocked) else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("embed_result"),
            )
        }
    }
}

private fun formatMinutes(m: Int): String = java.time.LocalTime.of(m / 60, m % 60)
    .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

@Composable
private fun DataTab() {
    val c = rememberContainer()
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var includeDocuments by rememberSaveable { mutableStateOf(true) }
    var includeAudio by rememberSaveable { mutableStateOf(true) }
    var includeSummaries by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            result = runCatching { c.backup.export(uri, includeAudio, includeSummaries, includeDocuments) }
                .fold({ res.getString(R.string.export_done, it.notes, it.audio) }, { res.getString(R.string.export_failed, it.message.orEmpty()) })
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            result = runCatching { c.backup.import(uri) }
                .fold({ res.getString(R.string.import_done, it.added, it.updated, it.skipped) }, { res.getString(R.string.import_failed, it.message.orEmpty()) })
            busy = false
        }
    }

    Section(R.string.settings_export, R.string.settings_export_desc)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(includeSummaries, { includeSummaries = it }, modifier = Modifier.testTag("export_summaries")); Text(stringResource(R.string.export_summaries))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(includeDocuments, { includeDocuments = it }); Text(stringResource(R.string.export_documents))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(includeAudio, { includeAudio = it }, modifier = Modifier.testTag("export_audio")); Text(stringResource(R.string.export_audio))
    }
    OutlinedButton(onClick = { exporter.launch("jogakbo-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip") }, enabled = !busy, modifier = Modifier.testTag("export")) {
        Text(stringResource(R.string.export))
    }
    HorizontalDivider()
    Section(R.string.settings_import, R.string.settings_import_desc)
    OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.testTag("import")) {
        Text(stringResource(R.string.import_notes))
    }
    result?.let { Text(it, modifier = Modifier.testTag("data_result")) }
}

@Composable
private fun ConnectionsTab() {
    Section(R.string.settings_ondolsaem, R.string.settings_ondolsaem_desc)
    OndolSaemCard()
    HorizontalDivider()
    Section(R.string.settings_apps, R.string.settings_apps_desc)
    ConnectedApps()
}

@Composable
private fun AppTab() {
    Section(R.string.settings_permissions, R.string.settings_permissions_desc)
    PermissionCards()
    HorizontalDivider()
    Section(R.string.settings_language, null)
    LanguagePicker()
    HorizontalDivider()
    About()
}

/** Notifications (progress of background work) and the microphone (recordings), one card per missing permission. */
@Composable
fun PermissionCards() {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var mic by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled(); mic = granted(Manifest.permission.RECORD_AUDIO) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled(); mic = granted(Manifest.permission.RECORD_AUDIO) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!notif) WarningCard(stringResource(R.string.perm_notifications), actionLabel = stringResource(R.string.perm_allow)) {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        if (!mic) WarningCard(stringResource(R.string.perm_microphone), actionLabel = stringResource(R.string.perm_allow)) { ask.launch(Manifest.permission.RECORD_AUDIO) }
        if (notif && mic) Text(stringResource(R.string.perm_all_set), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    val options = listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ko" to R.string.lang_ko)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (tag, label) ->
            FilterChip(selected = current == tag, onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun About() {
    val ctx = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    Text(stringResource(R.string.app_name_full), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, AppDatabase.VERSION))
    TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.about_licenses)) }
    if (showLicenses) {
        val text = remember { runCatching { ctx.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.action_close)) } },
            title = { Text(stringResource(R.string.about_licenses)) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        )
    }
}
