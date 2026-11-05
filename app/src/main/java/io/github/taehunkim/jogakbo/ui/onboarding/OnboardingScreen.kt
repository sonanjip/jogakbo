package io.github.taehunkim.jogakbo.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.ui.settings.OndolSaemCard
import io.github.taehunkim.jogakbo.ui.settings.PermissionCards
import kotlinx.coroutines.launch

/** First start: what the app does, OndolSaem (the models), and the permissions. Embedding timing has a default. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("onboarding"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name_full), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.onboarding_models), style = MaterialTheme.typography.bodyMedium)
        OndolSaemCard()
        Text(stringResource(R.string.onboarding_permissions), style = MaterialTheme.typography.bodyMedium)
        PermissionCards()
        Text(stringResource(R.string.onboarding_embedding), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { scope.launch { c.settings.setOnboardingDone(true); onDone() } }, modifier = Modifier.fillMaxWidth().testTag("onboarding_start")) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}
