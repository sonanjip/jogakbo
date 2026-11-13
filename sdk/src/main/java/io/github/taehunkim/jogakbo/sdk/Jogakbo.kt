package io.github.taehunkim.jogakbo.sdk

import android.content.Intent

/** Constants shared by Jogakbo and its client apps. */
object Jogakbo {
    /** Package name of the Jogakbo app. */
    const val PACKAGE = "io.github.taehunkim.jogakbo"

    /** Intent action of Jogakbo's bound service. */
    const val ACTION_BIND = "$PACKAGE.action.BIND"

    /**
     * Sent (explicitly, per package) to every app with a receiver for it after notes were added, changed or embedded.
     * It carries no data: anyone could send it, so receivers only treat it as a hint to call
     * [JogakboClient.changesSince]. The SDK's [NotesChangedReceiver] passes it on.
     */
    const val ACTION_NOTES_CHANGED = "$PACKAGE.action.NOTES_CHANGED"

    /** Opens one note in Jogakbo. Extra [EXTRA_NOTE_ID]. */
    const val ACTION_OPEN_NOTE = "$PACKAGE.action.OPEN_NOTE"
    const val EXTRA_NOTE_ID = "note_id"

    /** Opens the list of apps waiting for approval in Jogakbo. */
    const val ACTION_APPROVE = "$PACKAGE.action.APPROVE"

    /** API level of this SDK. Bump it whenever a method or JSON field is added, and note the level on it ([Api]). */
    const val API_VERSION = Api.V2

    val SDK_VERSION: String get() = BuildConfig.SDK_VERSION

    object Api {
        /** hello, tools, runTool, notes, readNote, changesSince. */
        const val V1 = 1
        /** createNote: an audio note that arrives with its transcript. */
        const val V2 = 2
    }

    /** Tool names Jogakbo offers (API 1). */
    object Tools {
        const val SEARCH_NOTES = "search_notes"
        const val READ_NOTE = "read_note"
        /** Added later as a tool only (no AIDL change): the passages of one note closest to a question. */
        const val SEARCH_IN_NOTE = "search_in_note"
    }

    /** Error codes of [JogakboCallException] (sent over Binder by [JogakboErrors]). */
    object Errors {
        /** Input rejected (unknown tool, bad JSON, ...). */
        const val BAD_INPUT = 3
        /** The client's API level is below Jogakbo's minimum: the client app must be updated. */
        const val CLIENT_TOO_OLD = 5
        /** The user has not allowed this app in Jogakbo (yet). Open [approvalIntent]. Sent inside a SecurityException. */
        const val NOT_APPROVED = 6
    }

    /** Opens [noteId] in Jogakbo (in Jogakbo's own task). */
    fun openNoteIntent(noteId: Long): Intent =
        Intent(ACTION_OPEN_NOTE).setPackage(PACKAGE).putExtra(EXTRA_NOTE_ID, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the approval list in Jogakbo, in Jogakbo's own task. */
    fun approvalIntent(): Intent = Intent(ACTION_APPROVE).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
