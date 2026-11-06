package io.github.taehunkim.jogakbo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = (ApplicationProvider.getApplicationContext<JogakboApp>()).container

    @Before fun onboarded() {
        runBlocking { c.settings.setOnboardingDone(true) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("add_menu")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun writeMemoWithToolbarThenOpenSettings() {
        val title = "Toolbar test ${System.currentTimeMillis()}"
        rule.onNodeWithTag("add_menu").performClick()
        rule.onNodeWithTag("add_memo").performClick()
        rule.onNodeWithTag("title").performTextInput(title)
        rule.onNodeWithTag("editor_text").performTextInput("milk")
        rule.onNodeWithTag("md_checkbox").performScrollTo().performClick()
        rule.onNodeWithTag("editor_tab_preview").performClick()
        rule.onNodeWithText("☐", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("editor_tab_write").performClick()
        rule.onNodeWithTag("save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("note_title") and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        val note = runBlocking { c.db.noteDao().all().last { it.title == title } }
        assertTrue(note.body, note.body == "- [ ] milk")
        rule.onNodeWithTag("summary_card").assertIsDisplayed()
        // delete it again
        rule.onNodeWithTag("note_menu").performClick()
        rule.onNodeWithTag("delete_note").performClick()
        rule.onNodeWithTag("delete_confirm").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("add_menu")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("system_prompt").assertIsDisplayed()
        rule.onNodeWithTag("settings_tab_1").performClick()
        rule.onNodeWithTag("tag_add").assertIsDisplayed()
        rule.onNodeWithTag("settings_tab_2").performClick()
        rule.onNodeWithTag("export").assertIsDisplayed()
        rule.onNodeWithTag("settings_tab_3").performClick()
        rule.onNodeWithTag("conn_ondolsaem").assertIsDisplayed()
    }

    @Test fun addLinkDialog() {
        rule.onNodeWithTag("add_menu").performClick()
        rule.onNodeWithTag("add_link").performClick()
        rule.onNodeWithTag("add_dialog").assertIsDisplayed()
        rule.onNodeWithTag("add_url").performTextReplacement("https://example.com")
        rule.onNodeWithTag("add_prompt").assertIsDisplayed()
    }
}
