package io.github.taehunkim.jogakbo.summary

import io.github.taehunkim.jogakbo.data.db.NoteKind
import io.github.taehunkim.jogakbo.data.prefs.SummaryLanguage
import io.github.taehunkim.jogakbo.ml.TextModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {
    private class FakeModel(override val contextTokens: Int, val tooLongAbove: Int = Int.MAX_VALUE) : TextModel {
        override val modelId = "fake"
        val calls = ArrayList<Pair<String, String>>()
        override suspend fun generate(system: String, user: String, onToken: (String) -> Unit): String {
            if (Tokens.estimate(system + user) > tooLongAbove) throw IllegalStateException("input exceeds the maximum")
            calls += system to user
            return if (system.contains("take notes on part")) "- note ${calls.size}" else "```markdown\nSummary of ${user.length} chars\n```"
        }
    }

    private fun req(text: String, prompt: String? = null) =
        SummaryRequest(NoteKind.LINK, "Title", text, notePrompt = prompt, globalPrompt = "Be brief.", language = SummaryLanguage.APP, appLanguage = "Korean")

    @Test fun shortTextIsOneCall() = runTest {
        val m = FakeModel(4096)
        val out = Summarizer(m).summarize(req("Hello world. ".repeat(50), "Focus on X"))
        assertEquals(1, m.calls.size)
        assertTrue(out.startsWith("Summary of")) // code fence removed
        val (system, user) = m.calls[0]
        assertTrue(user.startsWith("Instructions for this summary:\nFocus on X"))
        assertTrue(system.contains("Be brief.") && system.indexOf("Be brief.") < system.indexOf("Write in Korean"))
        assertTrue(system.contains("Markdown"))
    }

    @Test fun longTextIsSummarizedInParts() = runTest {
        val m = FakeModel(2048)
        val text = (1..400).joinToString("\n\n") { "Paragraph $it has some words in it, quite a few of them really." }
        Summarizer(m).summarize(req(text, "Focus on X"))
        val parts = m.calls.count { it.first.contains("take notes on part") }
        assertTrue("parts: $parts", parts >= 3)
        // every call fits the window
        m.calls.forEach { (s, u) -> assertTrue(Tokens.estimate(s + u) < 2048) }
        // the note's instruction goes to the final summary only
        assertFalse(m.calls.filter { it.first.contains("take notes on part") }.any { it.second.contains("Focus on X") })
        val last = m.calls.last()
        assertTrue(last.first.contains("notes on its parts") && last.second.contains("Focus on X"))
    }

    @Test fun retriesSmallerWhenTheModelSaysTooLong() = runTest {
        val m = FakeModel(4096, tooLongAbove = 1500)
        val text = (1..120).joinToString("\n\n") { "Sentence number $it is here with words." }
        val out = Summarizer(m).summarize(req(text))
        assertTrue(out.isNotBlank())
    }

    @Test fun originalLanguageLine() {
        val s = PromptBuilder.system(req("x").copy(language = SummaryLanguage.ORIGINAL))
        assertTrue(s.endsWith("Write in the same language as the text (if it mixes languages, use the main one)."))
    }

    @Test fun koreanCountsAsMoreTokens() {
        assertTrue(Tokens.estimate("안녕하세요 반갑습니다") > Tokens.estimate("hello there"))
        val parts = Tokens.split("가".repeat(1000), 300)
        assertTrue(parts.all { Tokens.estimate(it) <= 300 })
        assertEquals(1000, parts.sumOf { it.length })
    }
}
