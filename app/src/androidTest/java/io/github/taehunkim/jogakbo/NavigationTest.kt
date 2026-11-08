package io.github.taehunkim.jogakbo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
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
        rule.onNodeWithTag("storage_originals").assertIsDisplayed()
        rule.onNodeWithTag("export_documents").assertIsDisplayed()
        rule.onNodeWithTag("settings_tab_3").performClick()
        rule.onNodeWithTag("conn_ondolsaem").assertIsDisplayed()
    }

    @Test fun sharedStreamsAreDeduplicatedAndRejectFileUris() {
        val a = android.net.Uri.parse("content://example/one.png")
        val b = android.net.Uri.parse("content://example/two.pdf")
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, arrayListOf(a, b))
            clipData = android.content.ClipData.newUri(rule.activity.contentResolver, "one", a).apply {
                addItem(android.content.ClipData.Item(android.net.Uri.parse("file:///unsafe")))
            }
        }
        org.junit.Assert.assertEquals(listOf(a, b), rule.activity.sharedStreams(intent))
    }

    @Test fun multipleSharedTextsCreateSeparateNotes() {
        val first = "share-one-${System.currentTimeMillis()}"
        val second = "$first-second"
        rule.activityRule.scenario.onActivity { activity ->
            val clips = android.content.ClipData.newPlainText("first", first).apply {
                addItem(android.content.ClipData.Item(second))
            }
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain").setClipData(clips).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("add_confirm")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("add_confirm").performClick()
        rule.waitUntil(10000) { runBlocking { c.db.noteDao().all().count { it.body == first || it.body == second } == 2 } }
        runBlocking { c.db.noteDao().all().filter { it.body == first || it.body == second }.forEach { c.notes.delete(it.id) } }
    }

    @Test fun sharedItemsCanBeExcludedBeforeImport() {
        val prefix = "exclude-${System.currentTimeMillis()}"
        rule.activityRule.scenario.onActivity { activity ->
            val clip = android.content.ClipData.newPlainText("first", "$prefix-first").apply {
                addItem(android.content.ClipData.Item("$prefix-second"))
            }
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain").setClipData(clip).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("share_item_0")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("share_item_0").performClick()
        rule.onNodeWithTag("add_confirm").performClick()
        rule.waitUntil(10000) { runBlocking { c.db.noteDao().all().any { it.body == "$prefix-second" } } }
        runBlocking {
            val imported = c.db.noteDao().all().filter { it.body.startsWith(prefix) }
            org.junit.Assert.assertEquals(listOf("$prefix-second"), imported.map { it.body })
            imported.forEach { c.notes.delete(it.id) }
        }
    }

    @Test fun shareRequiresAtMostTenSelectedItems() {
        rule.activityRule.scenario.onActivity { activity ->
            val clip = android.content.ClipData.newPlainText("one", "one").apply {
                repeat(10) { addItem(android.content.ClipData.Item("item $it")) }
            }
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java).setAction(android.content.Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain").setClipData(clip).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("share_item_0")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("add_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("share_item_0").performClick()
        rule.onNodeWithTag("add_confirm").assertIsEnabled()
    }

    @Test fun processTextOpensMemoEditor() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java).setAction(android.content.Intent.ACTION_PROCESS_TEXT).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "Selected passage").addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("editor_text")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("editor_text").assertIsDisplayed()
    }

    @Test fun addLinkDialog() {
        rule.onNodeWithTag("add_menu").performClick()
        rule.onNodeWithTag("add_link").performClick()
        rule.onNodeWithTag("add_dialog").assertIsDisplayed()
        rule.onNodeWithTag("add_url").performTextReplacement("https://example.com")
        rule.onNodeWithTag("add_prompt").assertIsDisplayed()
    }
}
