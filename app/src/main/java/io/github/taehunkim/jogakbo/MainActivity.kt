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
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                if (text.isNotBlank()) target.value = Target.Share(Shared.Text(text))
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = sharedStreams(intent)
                val texts = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.clipData?.let { clip ->
                    (0 until clip.itemCount).mapNotNull { i ->
                        val item = clip.getItemAt(i)
                        if (item.uri == null) item.text?.toString()?.takeIf { it.isNotBlank() } else null
                    }
                }.orEmpty() else emptyList()
                if (streams.isNotEmpty() || texts.isNotEmpty()) {
                    val items = streams.map { uri ->
                        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type.orEmpty()
                        val name = runCatching {
                            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            }
                        }.getOrNull().orEmpty()
                        val audio = type.startsWith("audio/") || (type == "application/octet-stream" || type == "*/*") &&
                            name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac", "amr")
                        Shared.File(uri, audio)
                    }
                    target.value = Target.Share(if (items.size == 1 && texts.isEmpty()) {
                        val file = items.first()
                        if (file.audio) Shared.Audio(file.uri) else Shared.Document(file.uri)
                    } else Shared.Batch(items, texts))
                } else {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                    val url = NoteRepository.findUrl(text)
                    target.value = Target.Share(when {
                        url != null && text.trim().length - url.length < 200 -> Shared.Link(url)
                        text.isNotBlank() -> Shared.Text(text)
                        else -> return
                    })
                }
            }
        }
    }

    internal fun sharedStreams(intent: Intent): List<Uri> {
        val extras = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(uri)
        }
        val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        return (extras + clips).filter { it.scheme == "content" }.distinct()
    }

    companion object {
        const val ACTION_APPROVE = "io.github.taehunkim.jogakbo.action.APPROVE"
    }
}
