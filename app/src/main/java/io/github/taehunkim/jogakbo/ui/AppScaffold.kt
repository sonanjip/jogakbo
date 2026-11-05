package io.github.taehunkim.jogakbo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.ui.editor.EditTarget
import io.github.taehunkim.jogakbo.ui.editor.EditorScreen
import io.github.taehunkim.jogakbo.ui.notes.NoteDetailScreen
import io.github.taehunkim.jogakbo.ui.notes.NotesListScreen
import io.github.taehunkim.jogakbo.ui.notes.Shared
import io.github.taehunkim.jogakbo.ui.onboarding.OnboardingScreen
import io.github.taehunkim.jogakbo.ui.record.RecordScreen
import io.github.taehunkim.jogakbo.ui.settings.SettingsScreen

/** Where an intent asks the app to go. */
sealed interface Target {
    data class Note(val id: Long) : Target
    data class Share(val shared: Shared) : Target
    data object Approvals : Target
    data object Recording : Target
}

const val ROUTE_NOTES = "notes"
const val ROUTE_SETTINGS = "settings?tab={tab}"

@Composable
fun AppScaffold(target: Target?, onTargetHandled: () -> Unit) {
    val c = rememberContainer()
    val onboarded by c.settings.onboardingDone.collectAsStateWithLifecycle(null)
    when (onboarded) {
        null -> return
        false -> { OnboardingScreen { }; return }
        true -> Unit
    }
    val nav = rememberNavController()
    var shared by remember { mutableStateOf<Shared?>(null) }
    var newMemoText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target) {
        when (target) {
            is Target.Note -> nav.navigate("note/${target.id}")
            is Target.Share -> { nav.popBackStack(ROUTE_NOTES, false); shared = target.shared }
            Target.Approvals -> nav.navigate("settings?tab=2")
            Target.Recording -> nav.navigate("record")
            null -> return@LaunchedEffect
        }
        onTargetHandled()
    }
    NavHost(nav, startDestination = ROUTE_NOTES) {
        composable(ROUTE_NOTES) {
            NotesListScreen(
                onOpen = { nav.navigate("note/$it") },
                onNewMemo = { text -> newMemoText = text; nav.navigate("edit/0/NOTE") },
                onRecording = { nav.navigate("record") },
                onSettings = { nav.navigate("settings?tab=0") },
                shared = shared, onSharedHandled = { shared = null },
            )
        }
        composable("note/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
            val id = e.arguments!!.getLong("id")
            NoteDetailScreen(
                id, onBack = { nav.back() },
                onEdit = { nav.navigate("edit/$id/NOTE") }, onEditSummary = { nav.navigate("edit/$id/SUMMARY") },
            )
        }
        composable("edit/{id}/{target}", listOf(navArgument("id") { type = NavType.LongType }, navArgument("target") { type = NavType.StringType })) { e ->
            val id = e.arguments!!.getLong("id")
            val t = EditTarget.valueOf(e.arguments!!.getString("target") ?: "NOTE")
            val initial = remember { if (id == 0L) newMemoText.also { newMemoText = null } else null }
            EditorScreen(id, t, initial, onDone = { nav.back() }) { saved ->
                if (id == 0L) { nav.back(); nav.navigate("note/$saved") } else nav.back()
            }
        }
        composable("record") {
            RecordScreen(onBack = { nav.back() }) { noteId -> nav.back(); nav.navigate("note/$noteId") }
        }
        composable(ROUTE_SETTINGS, listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
            SettingsScreen(onBack = { nav.back() }, initialTab = e.arguments?.getInt("tab") ?: 0)
        }
    }
}

/** Back one screen, but never off the notes list (a double tap on Back would otherwise leave an empty screen). */
private fun androidx.navigation.NavController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
