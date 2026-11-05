package io.github.taehunkim.jogakbo.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.taehunkim.jogakbo.INotes
import io.github.taehunkim.jogakbo.JogakboApp
import io.github.taehunkim.jogakbo.sdk.Jogakbo

/** The bound service other apps use (action [Jogakbo.ACTION_BIND]). Every call checks the caller first ([CallerCheck]). */
class NotesService : Service() {
    private val binder by lazy {
        val c = (application as JogakboApp).container
        val api = c.notesApi
        val check = c.callerCheck
        object : INotes.Stub() {
            override fun apiVersion(): Int = Jogakbo.API_VERSION
            override fun hello(clientJson: String): String { check.require(); return api.hello(clientJson) }
            override fun tools(): String { check.require(); return api.tools() }
            override fun runTool(name: String, argsJson: String): String = logged("runTool $name") { check.require(); api.runTool(name, argsJson) }
            override fun notes(ids: LongArray): String { check.require(); return api.notes(ids) }
            override fun readNote(id: Long, maxChars: Int): String { check.require(); return api.readNote(id, maxChars) }
            override fun changesSince(cursor: Long, limit: Int): String { check.require(); return api.changesSince(cursor, limit) }
        }
    }

    private inline fun <T> logged(what: String, block: () -> T): T = try { block() } catch (e: RuntimeException) {
        if (e !is SecurityException && e !is IllegalStateException) Log.w(TAG, "$what failed", e)
        throw e
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == Jogakbo.ACTION_BIND) binder else null

    private companion object { const val TAG = "Jogakbo.Service" }
}
