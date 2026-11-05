package io.github.taehunkim.jogakbo.service

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.taehunkim.jogakbo.sdk.Jogakbo

/**
 * Tells other apps that notes changed: an explicit broadcast (no data) to every app with a receiver for
 * [Jogakbo.ACTION_NOTES_CHANGED], so it reaches apps that are not running. They ask the service what is new, which
 * checks who they are, so it does not matter who receives the hint.
 */
object NotesEvents {
    fun notifyChanged(context: Context) {
        val probe = Intent(Jogakbo.ACTION_NOTES_CHANGED)
        @Suppress("DEPRECATION") // the flags overload is API 33+
        val pkgs = context.packageManager.queryBroadcastReceivers(probe, 0)
            .map { it.activityInfo.packageName }.filter { it != context.packageName }.distinct()
        for (pkg in pkgs) {
            runCatching {
                context.sendBroadcast(Intent(Jogakbo.ACTION_NOTES_CHANGED).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }.onFailure { Log.w(TAG, "broadcast to $pkg failed", it) }
        }
    }

    private const val TAG = "Jogakbo.Events"
}
