package io.github.taehunkim.jogakbo

import io.github.taehunkim.jogakbo.audio.Cue
import io.github.taehunkim.jogakbo.audio.Span
import io.github.taehunkim.jogakbo.audio.SpeechTools
import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.ml.Embedder
import io.github.taehunkim.jogakbo.ml.TextModel
import io.github.taehunkim.jogakbo.notes.NoteJobs
import java.io.File
import kotlin.math.sqrt

/** Records what would run in the background. */
class FakeJobs : NoteJobs {
    val sources = ArrayList<Long>()
    val summaries = ArrayList<Long>()
    var changes = 0
    override fun fetchSource(noteId: Long, kind: NoteKind) { sources += noteId }
    override fun summarize(noteId: Long) { summaries += noteId }
    override fun index(noteId: Long) {}
    override fun tag(noteId: Long) {}
    override fun cancel(noteId: Long) {}
    override fun noteChanged() { changes++ }
}

/** Bag of words hashed into 64 dimensions: texts sharing words are close. */
class FakeEmbedder(override val modelId: String = "fake-emb") : Embedder {
    var calls = 0
    private fun vec(t: String): FloatArray {
        val v = FloatArray(64)
        Regex("[\\p{L}\\p{N}]+").findAll(t.lowercase()).forEach { v[(it.value.hashCode() and 0x7fffffff) % 64] += 1f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return FloatArray(64) { v[it] / n }
    }
    override suspend fun embedDocument(text: String, title: String?) = vec(text).also { calls++ }
    override suspend fun embedQuery(text: String) = vec(text)
}

class FakeText(override val contextTokens: Int = 4096, private val answer: (String, String) -> String = { _, _ -> "- the summary" }) : TextModel {
    override val modelId = "fake-llm"
    val calls = ArrayList<Pair<String, String>>()
    override suspend fun generate(system: String, user: String, onToken: (String) -> Unit): String { calls += system to user; return answer(system, user) }
}

/** Hears one line per 2 s of the file. */
class FakeSpeech(override val canTranscribe: Boolean = true) : SpeechTools {
    override suspend fun speechSpans(pcm: File) = emptyList<Span>()
    override suspend fun transcribe(pcm: File, fromMs: Long, onCue: suspend (Cue) -> Unit) {
        val len = pcm.length() / 32
        var t = fromMs
        while (t + 1500 <= len) { onCue(Cue(t, t + 1500, "line at ${t / 1000}")); t += 2000 }
    }
}

/** Decision model: yes when the text contains the topic word of the statement, "maybe" when it contains "maybe". */
class FakeDecider(override val modelId: String = "fake-nli") : io.github.taehunkim.jogakbo.ml.Decider {
    var calls = 0
    override suspend fun entail(premise: String, hypotheses: List<String>): FloatArray {
        calls++
        return FloatArray(hypotheses.size) { i ->
            val topic = hypotheses[i].substringAfter("이 글은 ").substringBefore("에 관한").substringAfter("This text is about ").removeSuffix(".").substringBefore("(").substringBefore(" (").trim()
            when { premise.contains(topic, ignoreCase = true) -> 0.95f; premise.contains("maybe") -> 0.5f; else -> 0.05f }
        }
    }
}
