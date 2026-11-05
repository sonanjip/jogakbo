package io.github.taehunkim.jogakbo.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import io.github.taehunkim.jogakbo.INotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A tool Jogakbo offers to a chat model; [parameters] is a JSON schema object. */
data class JogakboTool(val name: String, val description: String, val parameters: String)

/** One note as [JogakboClient.notes] reports it. kind: memo, link, youtube, audio. */
data class NoteInfo(val noteId: Long, val title: String, val kind: String, val created: String?, val updated: String?, val url: String?)

/** Notes added or changed since a cursor, and the cursor to pass next time. */
data class NoteChanges(val ids: List<Long>, val cursor: Long)

/** Versions Jogakbo reported in hello. */
data class JogakboInfo(val apiVersion: Int, val minClientApi: Int, val appVersion: String)

/**
 * Connection to Jogakbo's notes. Binds on first use, rebinds after Jogakbo's process died (calls are retried once,
 * and unbinds after [idleUnbindMs] without calls. All calls are suspend functions on
 * [Dispatchers.IO]; they throw [JogakboUnavailableException] or [JogakboCallException].
 */
class JogakboClient(
    context: Context,
    private val idleUnbindMs: Long = 60_000,
    private val connectTimeoutMs: Long = 15_000,
    private val clientApi: Int = Jogakbo.API_VERSION,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var service: INotes? = null
    @Volatile private var info: JogakboInfo? = null
    private var connection: ServiceConnection? = null
    private val active = AtomicInteger(0)
    private var unbindJob: Job? = null

    fun isInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(Jogakbo.PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Opens Jogakbo (null when it is not installed). */
    fun launchIntent(): Intent? = app.packageManager.getLaunchIntentForPackage(Jogakbo.PACKAGE)

    suspend fun info(): JogakboInfo = call { info ?: error("not connected") }

    suspend fun tools(): List<JogakboTool> = call { s ->
        val arr = JSONArray(s.tools())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JogakboTool(o.getString("name"), o.optString("description"), o.optJSONObject("parameters")?.toString() ?: "{}")
        }
    }

    /** The tool's text result for the model. */
    suspend fun runTool(name: String, argsJson: String): String = call { it.runTool(name, argsJson) }

    suspend fun notes(ids: List<Long>): List<NoteInfo> = call { s ->
        val arr = JSONArray(s.notes(ids.toLongArray()))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoteInfo(
                o.getLong("note_id"), o.optString("title"), o.optString("kind"), o.optString("created").ifEmpty { null },
                o.optString("updated").ifEmpty { null }, o.optString("url").ifEmpty { null },
            )
        }
    }

    /** The note as Markdown (title, summary, original), "" when it does not exist. */
    suspend fun readNote(id: Long, maxChars: Int): String = call { it.readNote(id, maxChars) }

    /** [cursor] 0 starts from now. */
    suspend fun changesSince(cursor: Long, limit: Int = 200): NoteChanges = call { s ->
        val o = JSONObject(s.changesSince(cursor, limit))
        val arr = o.optJSONArray("ids") ?: JSONArray()
        NoteChanges((0 until arr.length()).map { arr.getLong(it) }, o.optLong("cursor", cursor))
    }

    /** Unbinds now. Later calls bind again. */
    fun disconnect() { scope.launch { lock.withLock { disconnectLocked() } } }

    // ---- connection --------------------------------------------------------------------------------------------

    private fun begin() {
        active.incrementAndGet()
        synchronized(this) { unbindJob?.cancel(); unbindJob = null }
    }

    private fun end() {
        if (active.decrementAndGet() > 0) return
        synchronized(this) {
            unbindJob?.cancel()
            unbindJob = scope.launch {
                delay(idleUnbindMs)
                lock.withLock { if (active.get() == 0) disconnectLocked() }
            }
        }
    }

    private suspend fun <T> call(retry: Boolean = true, minApi: Int = Jogakbo.Api.V1, block: (INotes) -> T): T = withContext(Dispatchers.IO) {
        begin()
        try {
            var attempt = 0
            while (true) {
                val s = connect()
                val api = info?.apiVersion ?: 0
                if (api < minApi) throw JogakboUnavailableException(JogakboUnavailableException.Reason.HOST_TOO_OLD, "needs Jogakbo API $minApi, it has $api")
                try {
                    return@withContext block(s)
                } catch (e: DeadObjectException) {
                    lock.withLock { if (service === s) disconnectLocked() }
                    if (!retry || attempt++ > 0) throw JogakboUnavailableException(JogakboUnavailableException.Reason.NO_ANSWER, "Jogakbo died", e)
                    Log.w(TAG, "Jogakbo died, reconnecting")
                } catch (e: IllegalStateException) {
                    throw JogakboErrors.decode(e) ?: e
                } catch (e: SecurityException) {
                    throw JogakboErrors.refusal(e)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { end() }
    }

    private suspend fun connect(): INotes = lock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        disconnectLocked()
        if (!isInstalled()) throw JogakboUnavailableException(JogakboUnavailableException.Reason.NOT_INSTALLED)
        val s = withTimeoutOrNull(connectTimeoutMs) { bind() }
            ?: run { disconnectLocked(); throw JogakboUnavailableException(JogakboUnavailableException.Reason.NO_ANSWER, "bind timeout") }
        try {
            val o = JSONObject(s.hello(JSONObject().put("api", clientApi).put("sdk", Jogakbo.SDK_VERSION).toString()))
            info = JogakboInfo(o.optInt("api"), o.optInt("minClientApi"), o.optString("appVersion"))
        } catch (e: IllegalStateException) {
            disconnectLocked()
            val err = JogakboErrors.decode(e) ?: throw e
            if (err.code == Jogakbo.Errors.CLIENT_TOO_OLD) throw JogakboUnavailableException(JogakboUnavailableException.Reason.CLIENT_TOO_OLD, err.message)
            throw err
        } catch (e: SecurityException) {
            disconnectLocked(); throw JogakboErrors.refusal(e)
        } catch (e: DeadObjectException) {
            disconnectLocked(); throw JogakboUnavailableException(JogakboUnavailableException.Reason.NO_ANSWER, "Jogakbo died", e)
        }
        service = s
        s
    }

    private suspend fun bind(): INotes = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                if (cont.isActive) cont.resume(INotes.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
            override fun onBindingDied(name: ComponentName?) {
                service = null
                if (cont.isActive) cont.resumeWithException(JogakboUnavailableException(JogakboUnavailableException.Reason.NO_ANSWER, "binding died"))
                else {
                    val self = this
                    scope.launch { lock.withLock { if (connection === self) disconnectLocked() } }
                }
            }
            override fun onNullBinding(name: ComponentName?) {
                if (cont.isActive) cont.resumeWithException(JogakboUnavailableException(JogakboUnavailableException.Reason.NOT_ALLOWED, "null binding"))
            }
        }
        connection = conn
        val intent = Intent(Jogakbo.ACTION_BIND).setPackage(Jogakbo.PACKAGE)
        val ok = try { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (_: SecurityException) { false }
        if (!ok) {
            runCatching { app.unbindService(conn) }
            connection = null
            if (cont.isActive) cont.resumeWithException(JogakboUnavailableException(JogakboUnavailableException.Reason.NOT_ALLOWED, "bindService failed (Jogakbo too old?)"))
        }
    }

    private fun disconnectLocked() {
        service = null
        info = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }

    companion object {
        private const val TAG = "Jogakbo.Client"
    }
}
