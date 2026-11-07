package io.github.taehunkim.jogakbo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The assistant app Malbotdari: hand it notes to ask about (it reads them through this app's SDK service). */
object Malbotdari {
    const val PACKAGE = "io.github.taehunkim.malbotdari"
    /** Malbotdari's MainActivity: a new chat with [EXTRA_NOTE_IDS] attached (Nunsori's files use `item_ids`). */
    const val ACTION_NEW_CHAT = "$PACKAGE.action.NEW_CHAT"
    const val EXTRA_NOTE_IDS = "note_ids"
    const val RELEASES_URL = "https://github.com/sonanjip/malbotdari/releases"

    fun isInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** A new chat with [noteIds] attached; false when Malbotdari is not installed. */
    fun open(ctx: Context, noteIds: List<Long>): Boolean {
        val intent = Intent(ACTION_NEW_CHAT).setPackage(PACKAGE).putExtra(EXTRA_NOTE_IDS, noteIds.toLongArray())
        return try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: ActivityNotFoundException) { false }
    }

    /** Where to get it. */
    fun openReleases(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
