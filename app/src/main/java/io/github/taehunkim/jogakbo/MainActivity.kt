package io.github.taehunkim.jogakbo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.taehunkim.jogakbo.notes.NoteRepository
import io.github.taehunkim.jogakbo.sdk.Jogakbo
import io.github.taehunkim.jogakbo.ui.AppScaffold
import io.github.taehunkim.jogakbo.ui.Target
import io.github.taehunkim.jogakbo.ui.notes.Shared
import io.github.taehunkim.jogakbo.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** Where an intent asks to go; consumed once the UI navigated there. */
    private val target = MutableStateFlow<Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { AppScaffold(target.collectAsStateWithLifecycle().value) { target.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // back from OndolSaem (a model downloaded, this app allowed): check again
        val c = (application as JogakboApp).container
        lifecycleScope.launch { c.models.refresh() }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(Notifications.EXTRA_NOTE, -1).takeIf { it > 0 }?.let { target.value = Target.Note(it); return }
        when (intent.action) {
            Jogakbo.ACTION_OPEN_NOTE -> intent.getLongExtra(Jogakbo.EXTRA_NOTE_ID, -1).takeIf { it > 0 }?.let { target.value = Target.Note(it) }
            ACTION_APPROVE, Jogakbo.ACTION_APPROVE -> target.value = Target.Approvals
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                val type = intent.type.orEmpty()
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                target.value = Target.Share(
                    when {
                        stream != null && type.startsWith("audio/") -> Shared.Audio(stream)
                        NoteRepository.findUrl(text) != null && text.trim().length - NoteRepository.findUrl(text)!!.length < 200 -> Shared.Link(NoteRepository.findUrl(text)!!)
                        text.isNotBlank() -> Shared.Text(text)
                        else -> return
                    },
                )
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "io.github.taehunkim.jogakbo.action.APPROVE"
    }
}
