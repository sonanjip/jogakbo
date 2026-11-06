package io.github.taehunkim.jogakbo.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.taehunkim.jogakbo.data.db.NoteKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** When notes are embedded for search (same choices as Nunsori's automatic indexing, minus the file trigger). */
enum class EmbedMode { IMMEDIATE, SCHEDULED, IDLE, MANUAL }

/** The language summaries are written in. */
enum class SummaryLanguage { ORIGINAL, APP }

class SettingsStore(private val context: Context) {
    private val kEmbedMode = stringPreferencesKey("embed_mode")
    private val kScheduleMinutes = intPreferencesKey("schedule_minutes")
    private val kChargingOnly = booleanPreferencesKey("schedule_charging_only")
    private val kIdlePreset = androidx.datastore.preferences.core.stringPreferencesKey("idle_preset")
    private val kSystemPrompt = stringPreferencesKey("summary_system_prompt")
    private val kLanguage = stringPreferencesKey("summary_language")
    private val kOnboarding = booleanPreferencesKey("onboarding_done")
    private val kApprovals = stringPreferencesKey("api_approvals")
    private val kEmbeddingModel = stringPreferencesKey("embedding_model_id")
    private val kChatModel = stringPreferencesKey("chat_model_id")
    private val kDecisionModel = stringPreferencesKey("decision_model_id")
    private val kAutoTag = booleanPreferencesKey("auto_tag")
    private val kTagsSeeded = booleanPreferencesKey("tags_seeded")
    private fun kAuto(kind: NoteKind) = booleanPreferencesKey("auto_summary_${kind.wire}")

    val embedMode: Flow<EmbedMode> = context.dataStore.data.map { p -> p[kEmbedMode]?.let { runCatching { EmbedMode.valueOf(it) }.getOrNull() } ?: EmbedMode.IMMEDIATE }
    suspend fun setEmbedMode(m: EmbedMode) { context.dataStore.edit { it[kEmbedMode] = m.name } }

    /** Minutes after midnight for [EmbedMode.SCHEDULED]. */
    val scheduleMinutes: Flow<Int> = context.dataStore.data.map { it[kScheduleMinutes] ?: DEFAULT_SCHEDULE_MINUTES }
    suspend fun setScheduleMinutes(m: Int) { context.dataStore.edit { it[kScheduleMinutes] = m.coerceIn(0, 24 * 60 - 1) } }

    val scheduleChargingOnly: Flow<Boolean> = context.dataStore.data.map { it[kChargingOnly] ?: true }
    suspend fun setScheduleChargingOnly(on: Boolean) { context.dataStore.edit { it[kChargingOnly] = on } }
    /** How long the phone must be unused (by battery level) before "When idle" embedding starts. */
    val idlePreset: Flow<io.github.taehunkim.ondolsaem.sdk.IdlePreset> =
        context.dataStore.data.map { io.github.taehunkim.ondolsaem.sdk.IdlePreset.of(it[kIdlePreset]) }
    suspend fun setIdlePreset(p: io.github.taehunkim.ondolsaem.sdk.IdlePreset) { context.dataStore.edit { it[kIdlePreset] = p.name } }

    /** The user's instructions for every summary (added to the built-in prompt). */
    val systemPrompt: Flow<String> = context.dataStore.data.map { it[kSystemPrompt] ?: "" }
    suspend fun setSystemPrompt(s: String) { context.dataStore.edit { it[kSystemPrompt] = s.trim().take(MAX_PROMPT) } }

    val summaryLanguage: Flow<SummaryLanguage> = context.dataStore.data.map { p -> p[kLanguage]?.let { runCatching { SummaryLanguage.valueOf(it) }.getOrNull() } ?: SummaryLanguage.ORIGINAL }
    suspend fun setSummaryLanguage(l: SummaryLanguage) { context.dataStore.edit { it[kLanguage] = l.name } }

    /** Whether a new note of [kind] is summarized on its own (memos: no; links, videos and recordings: yes). */
    fun autoSummary(kind: NoteKind): Flow<Boolean> = context.dataStore.data.map { it[kAuto(kind)] ?: (kind != NoteKind.MEMO) }
    suspend fun setAutoSummary(kind: NoteKind, on: Boolean) { context.dataStore.edit { it[kAuto(kind)] = on } }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[kOnboarding] ?: false }
    suspend fun setOnboardingDone(done: Boolean) { context.dataStore.edit { it[kOnboarding] = done } }

    /** Other apps' access to the notes (service.Approvals), as JSON. */
    val apiApprovalsJson: Flow<String> = context.dataStore.data.map { it[kApprovals] ?: "" }
    suspend fun setApiApprovalsJson(s: String) { context.dataStore.edit { it[kApprovals] = s } }

    /** OndolSaem's active models, mirrored by ModelHost.refresh (the embedding model is part of the embedding key). */
    val embeddingModelId: Flow<String> = context.dataStore.data.map { it[kEmbeddingModel] ?: "" }
    suspend fun setEmbeddingModelId(id: String) { context.dataStore.edit { it[kEmbeddingModel] = id } }
    val chatModelId: Flow<String> = context.dataStore.data.map { it[kChatModel] ?: "" }
    suspend fun setChatModelId(id: String) { context.dataStore.edit { it[kChatModel] = id } }
    val decisionModelId: Flow<String> = context.dataStore.data.map { it[kDecisionModel] ?: "" }
    suspend fun setDecisionModelId(id: String) { context.dataStore.edit { it[kDecisionModel] = id } }

    /** Tag notes with the decision model, at the embedding time. */
    val autoTag: Flow<Boolean> = context.dataStore.data.map { it[kAutoTag] ?: true }
    suspend fun setAutoTag(on: Boolean) { context.dataStore.edit { it[kAutoTag] = on } }

    /** The example tags were added once (deleting them does not bring them back). */
    val tagsSeeded: Flow<Boolean> = context.dataStore.data.map { it[kTagsSeeded] ?: false }
    suspend fun setTagsSeeded() { context.dataStore.edit { it[kTagsSeeded] = true } }

    companion object {
        const val DEFAULT_SCHEDULE_MINUTES = 180
        const val MAX_PROMPT = 2000
    }
}
