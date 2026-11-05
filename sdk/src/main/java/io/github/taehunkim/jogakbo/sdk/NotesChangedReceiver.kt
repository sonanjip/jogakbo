package io.github.taehunkim.jogakbo.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declared by the SDK's manifest, so every client app receives [Jogakbo.ACTION_NOTES_CHANGED] even when it is not
 * running: the process starts, `Application.onCreate` sets [handler], then the broadcast is passed on. It carries
 * no data and anyone can send it: the handler asks Jogakbo ([JogakboClient.changesSince]).
 */
class NotesChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Jogakbo.ACTION_NOTES_CHANGED) return
        handler?.invoke()
    }

    companion object {
        /** Called on the main thread. Keep it short: schedule work. */
        @Volatile var handler: (() -> Unit)? = null
    }
}
