package io.github.taehunkim.jogakbo.ml

import android.content.Context
import android.util.Log
import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Span
import io.github.taehunkim.jogakbo.audio.SpeechTools
import io.github.taehunkim.jogakbo.data.prefs.SettingsStore
import io.github.taehunkim.ondolsaem.ModelInfo
import io.github.taehunkim.ondolsaem.sdk.ChatStreamEvent
import io.github.taehunkim.ondolsaem.sdk.HostCallException
import io.github.taehunkim.ondolsaem.sdk.HostLease
import io.github.taehunkim.ondolsaem.sdk.HostUnavailableException
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** A borrowed resource. Closing it returns the resource; the resource itself is NOT closed. */
class Lease<T>(val value: T, private val onClose: () -> Unit) : AutoCloseable {
    private var closed = false
    override fun close() { synchronized(this) { if (closed) return; closed = true }; onClose() }
}

/** What the OndolSaem model host offers right now. */
data class HostState(
    val status: Status,
    val models: Map<String, ModelInfo> = emptyMap(),
    val hostVersion: String? = null,
    val hostApi: Int = 0,
) {
    enum class Status {
        UNKNOWN, NOT_INSTALLED, NOT_ALLOWED, UNREACHABLE, CONNECTED,
        /** Signed with a key OndolSaem does not know: waiting for (or denied) the user's approval in OndolSaem. */
        NOT_APPROVED,
        HOST_TOO_OLD,
        CLIENT_TOO_OLD,
    }

    fun ready(category: String) = models[category]?.ready == true
    fun model(category: String): ModelInfo? = models[category]?.takeIf { it.id.isNotEmpty() }
}

/**
 * The AI models, which run in the OndolSaem app: the language model (summaries), speech recognition and the
 * embedding model (search). Hands out [Lease]s; the active model ids are mirrored into [SettingsStore].
 */
class ModelHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    val client: OndolSaemClient = OndolSaemClient(context),
) {
    private val _state = MutableStateFlow(HostState(HostState.Status.UNKNOWN))
    val state: StateFlow<HostState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    /** Test hooks: when set, used instead of the host (instrumented tests run without OndolSaem). */
    var textOverride: (suspend () -> TextModel?)? = null
    var embedderOverride: (suspend () -> Embedder?)? = null
    var speechOverride: (suspend () -> SpeechTools)? = null
    var deciderOverride: (suspend () -> Decider?)? = null

    init {
        scope.launch { client.modelChanges.collect { refresh() } }
    }

    /** Asks the host for its models and mirrors their ids into the settings. Never throws. */
    suspend fun refresh(): HostState = refreshLock.withLock {
        val next = try {
            val models = client.categories().associateBy { it.category }
            val info = client.hostInfo()
            HostState(HostState.Status.CONNECTED, models, info.appVersion, info.apiVersion)
        } catch (e: HostUnavailableException) {
            Log.i(TAG, "OndolSaem unavailable: ${e.reason} ${e.message.orEmpty()}")
            HostState(statusFor(e.reason), _state.value.models)
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            HostState(HostState.Status.UNREACHABLE, _state.value.models)
        }
        if (next.status == HostState.Status.CONNECTED) {
            val emb = next.models[Categories.EMBEDDING]?.takeIf { it.ready }?.id.orEmpty()
            if (settings.embeddingModelId.first() != emb) settings.setEmbeddingModelId(emb)
            val chat = next.models[Categories.LLM]?.takeIf { it.ready }?.id.orEmpty()
            if (settings.chatModelId.first() != chat) settings.setChatModelId(chat)
            val decision = next.models[Categories.DECISION]?.takeIf { it.ready }?.id.orEmpty()
            if (settings.decisionModelId.first() != decision) settings.setDecisionModelId(decision)
        }
        _state.value = next
        next
    }

    private suspend fun known(category: String): ModelInfo? {
        val s = _state.value
        return if (s.status == HostState.Status.CONNECTED) s.model(category) else refresh().model(category)
    }

    private suspend fun hostLease(category: String): HostLease? = try {
        client.acquire(category)
    } catch (e: HostCallException) {
        if (e.code == OndolSaem.Errors.MODEL_NOT_READY) { refresh(); null } else throw e
    } catch (e: HostUnavailableException) {
        refresh(); null
    }

    /** Null when the host or its language model is missing. Loading the model can take a while (the call waits). */
    suspend fun text(): Lease<TextModel>? {
        textOverride?.let { f -> return f()?.let { m -> Lease(m) { } } }
        val lease = hostLease(Categories.LLM) ?: return null
        val info = known(Categories.LLM)
        if (info == null || !info.ready) { lease.close(); return null }
        return Lease(RemoteTextModel(client, info)) { lease.close() }
    }

    /** Null when the host or its embedding model is missing. [background]: embedding notes (waits behind searches). */
    suspend fun embedder(background: Boolean = false): Lease<Embedder>? {
        embedderOverride?.let { f -> return f()?.let { e -> Lease(e) { } } }
        val lease = hostLease(Categories.EMBEDDING) ?: return null
        val info = known(Categories.EMBEDDING)
        if (info == null || !info.ready) { lease.close(); return null }
        val priority = if (background) OndolSaem.PRIORITY_BACKGROUND else OndolSaem.PRIORITY_INTERACTIVE
        return Lease(RemoteEmbedder(client, info, priority) { scope.launch { refresh() } }) { lease.close() }
    }

    /**
     * Null when the host is older than API 3 or its decision model is missing. Used in the background (tagging), so
     * its requests wait behind interactive ones.
     */
    suspend fun decider(): Decider? {
        deciderOverride?.let { return it() }
        val info = known(Categories.DECISION)
        if (info == null || !info.ready || !runCatching { client.supports(OndolSaem.Api.V3) }.getOrDefault(false)) return null
        return RemoteDecider(client, info.id)
    }

    /** Voice activity detection works when the host is reachable; transcription needs its speech model. */
    suspend fun speech(): Lease<SpeechTools> {
        speechOverride?.let { return Lease(it()) { } }
        val lease = hostLease(Categories.ASR)
        val can = lease != null && runCatching { client.canTranscribe() }.getOrDefault(false)
        return Lease(RemoteSpeechTools(client, can)) { lease?.close() }
    }

    companion object {
        private const val TAG = "Jogakbo.ModelHost"
        const val BACKEND = "OndolSaem"
        const val INSTALL_URL = "https://github.com/TaeHunKim/ondolsaem/releases"

        fun statusFor(reason: HostUnavailableException.Reason): HostState.Status = when (reason) {
            HostUnavailableException.Reason.NOT_INSTALLED -> HostState.Status.NOT_INSTALLED
            HostUnavailableException.Reason.NOT_ALLOWED -> HostState.Status.NOT_ALLOWED
            HostUnavailableException.Reason.NOT_APPROVED -> HostState.Status.NOT_APPROVED
            HostUnavailableException.Reason.NO_ANSWER -> HostState.Status.UNREACHABLE
            HostUnavailableException.Reason.HOST_TOO_OLD -> HostState.Status.HOST_TOO_OLD
            HostUnavailableException.Reason.CLIENT_TOO_OLD -> HostState.Status.CLIENT_TOO_OLD
        }
    }
}

private class RemoteTextModel(private val client: OndolSaemClient, info: ModelInfo) : TextModel {
    override val modelId: String = info.id
    override val contextTokens: Int = info.contextTokens.takeIf { it > 0 } ?: 4096

    override suspend fun generate(system: String, user: String, onToken: (String) -> Unit): String {
        val out = StringBuilder()
        // a fresh conversation key each time: every summary call stands alone (no cached history to reuse)
        client.chat("jogakbo-${UUID.randomUUID()}", system, emptyList(), user, priority = OndolSaem.PRIORITY_BACKGROUND)
            .collect { ev -> if (ev is ChatStreamEvent.Token) { out.append(ev.text); onToken(ev.text) } }
        return out.toString()
    }
}

/** Thrown when the host started answering with another embedding model than the one this proxy stands for. */
class EmbeddingModelChangedException(expected: String, actual: String) :
    IllegalStateException("embedding model changed in OndolSaem: $expected -> $actual")

private class RemoteEmbedder(
    private val client: OndolSaemClient, info: ModelInfo, private val priority: Int, private val onMismatch: () -> Unit,
) : Embedder {
    override val modelId: String = info.id

    private fun check(r: io.github.taehunkim.ondolsaem.EmbeddingResult): FloatArray {
        if (r.modelId != modelId) { onMismatch(); throw EmbeddingModelChangedException(modelId, r.modelId) }
        return r.vector
    }

    override suspend fun embedDocument(text: String, title: String?) = check(client.embedDocument(text, title, priority))
    override suspend fun embedQuery(text: String) = check(client.embedQuery(text, OndolSaem.PRIORITY_INTERACTIVE))
}

private class RemoteSpeechTools(private val client: OndolSaemClient, override val canTranscribe: Boolean) : SpeechTools {
    override suspend fun speechSpans(pcm: File): List<Span> = client.speechSpans(pcm).map { Span(it.first, it.last) }

    override suspend fun transcribe(pcm: File, fromMs: Long, onCue: suspend (Cue) -> Unit) {
        if (!canTranscribe) return
        client.transcribe(pcm, fromMs) { s, e, t -> onCue(Cue(s, e, t)) }
    }
}

/** OndolSaem's decision model: P(yes) per statement for a text. */
interface Decider {
    val modelId: String
    suspend fun entail(premise: String, hypotheses: List<String>): FloatArray
}

private class RemoteDecider(private val client: OndolSaemClient, override val modelId: String) : Decider {
    override suspend fun entail(premise: String, hypotheses: List<String>) =
        client.entail(premise, hypotheses, OndolSaem.PRIORITY_BACKGROUND)
}
