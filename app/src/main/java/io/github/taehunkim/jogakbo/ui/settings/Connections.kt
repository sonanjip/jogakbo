package io.github.taehunkim.jogakbo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.jogakbo.R
import io.github.taehunkim.jogakbo.ml.HostState
import io.github.taehunkim.jogakbo.ml.ModelHost
import io.github.taehunkim.jogakbo.rememberContainer
import io.github.taehunkim.jogakbo.service.Approval
import io.github.taehunkim.jogakbo.ui.common.openUrl
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import kotlinx.coroutines.launch

/** OndolSaem runs the models: shows whether it is reachable and which of the three models this app uses are ready. */
@Composable
fun OndolSaemCard() {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.models.state.collectAsStateWithLifecycle()
    val connected = s.status == HostState.Status.CONNECTED
    Card(Modifier.fillMaxWidth().testTag("conn_ondolsaem")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.conn_ondolsaem_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.conn_ondolsaem_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = when (s.status) {
                HostState.Status.CONNECTED -> stringResource(R.string.conn_connected, s.hostVersion.orEmpty())
                HostState.Status.NOT_INSTALLED -> stringResource(R.string.conn_not_installed)
                HostState.Status.NOT_APPROVED -> stringResource(R.string.conn_not_approved)
                HostState.Status.HOST_TOO_OLD, HostState.Status.CLIENT_TOO_OLD, HostState.Status.NOT_ALLOWED -> stringResource(R.string.conn_too_old)
                HostState.Status.UNKNOWN -> stringResource(R.string.conn_checking)
                HostState.Status.UNREACHABLE -> stringResource(R.string.conn_unreachable)
            }
            Text(status, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("conn_ondolsaem_status"))
            if (connected) {
                for ((cat, label) in listOf(OndolSaem.Categories.LLM to R.string.model_llm, OndolSaem.Categories.ASR to R.string.model_asr, OndolSaem.Categories.EMBEDDING to R.string.model_embedding, OndolSaem.Categories.DECISION to R.string.model_decision)) {
                    val m = s.model(cat)
                    val ready = s.ready(cat)
                    Text(
                        stringResource(label) + ": " + (if (m != null && ready) m.displayName else stringResource(R.string.model_missing)),
                        style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.status) {
                    HostState.Status.NOT_INSTALLED -> OutlinedButton(onClick = { openUrl(ctx, ModelHost.INSTALL_URL) }) { Text(stringResource(R.string.conn_get)) }
                    HostState.Status.NOT_APPROVED -> OutlinedButton(onClick = { runCatching { ctx.startActivity(OndolSaem.approvalIntent()) } }) { Text(stringResource(R.string.conn_open_ondolsaem)) }
                    else -> OutlinedButton(onClick = { c.models.client.launchIntent()?.let { ctx.startActivity(it) } }) { Text(stringResource(R.string.conn_open_ondolsaem)) }
                }
                TextButton(onClick = { scope.launch { c.models.refresh() } }) { Text(stringResource(R.string.conn_check)) }
            }
        }
    }
}

/** Apps not signed with this app's key that asked for the notes: allow, deny or forget each. */
@Composable
fun ConnectedApps() {
    val c = rememberContainer()
    val all by c.approvals.all.collectAsStateWithLifecycle()
    // load the saved decisions the first time this is shown
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.approvals.get("") } }
    if (all.isEmpty()) { Text(stringResource(R.string.apps_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("connected_apps")) {
        all.values.sortedBy { it.pkg }.forEach { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.appLabel(a.pkg), style = MaterialTheme.typography.titleSmall)
                    Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(when (a.state) { Approval.State.PENDING -> R.string.apps_pending; Approval.State.ALLOWED -> R.string.apps_allowed; Approval.State.DENIED -> R.string.apps_denied }))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.state != Approval.State.ALLOWED) OutlinedButton(onClick = { c.approvals.decide(a.pkg, true) }) { Text(stringResource(R.string.apps_allow)) }
                        if (a.state != Approval.State.DENIED) OutlinedButton(onClick = { c.approvals.decide(a.pkg, false) }) { Text(stringResource(R.string.apps_deny)) }
                        TextButton(onClick = { c.approvals.forget(a.pkg) }) { Text(stringResource(R.string.apps_forget)) }
                    }
                }
            }
        }
    }
}
