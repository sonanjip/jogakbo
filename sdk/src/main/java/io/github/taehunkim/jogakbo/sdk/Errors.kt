package io.github.taehunkim.jogakbo.sdk

import java.io.IOException

/** Base class of SDK failures. */
open class JogakboException(message: String?, cause: Throwable? = null) : IOException(message, cause)

/** Jogakbo is not installed, refused the connection, did not answer, or the versions do not fit. */
class JogakboUnavailableException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    JogakboException(message ?: reason.name, cause) {
    enum class Reason {
        NOT_INSTALLED,
        /** Refused for another reason than approval (e.g. a Jogakbo without this service). */
        NOT_ALLOWED,
        /** The user has not allowed this app in Jogakbo yet (the request is waiting there) or denied it. Show [Jogakbo.approvalIntent]. */
        NOT_APPROVED,
        NO_ANSWER,
        /** Jogakbo's API level is lower than this call needs: update Jogakbo. */
        HOST_TOO_OLD,
        /** Jogakbo no longer serves this client's API level: update the client app. */
        CLIENT_TOO_OLD,
    }
}

/** Jogakbo answered with an error (see [Jogakbo.Errors]). */
class JogakboCallException(val code: Int, message: String?) : JogakboException(message)

/** Binder only carries a few exception types, so errors travel as `IllegalStateException("jogakbo:<code>:<message>")`. */
object JogakboErrors {
    private const val PREFIX = "jogakbo:"

    /** Service side: the exception to throw from a Binder method. */
    fun encode(code: Int, message: String?): IllegalStateException = IllegalStateException("$PREFIX$code:${message ?: ""}")

    /** Client side: null when [e] is not a Jogakbo error. */
    fun decode(e: IllegalStateException): JogakboCallException? {
        val m = e.message ?: return null
        if (!m.startsWith(PREFIX)) return null
        val rest = m.removePrefix(PREFIX)
        val code = rest.substringBefore(':').toIntOrNull() ?: return null
        return JogakboCallException(code, rest.substringAfter(':').ifEmpty { null })
    }

    /** Client side: why a call was refused. */
    fun refusal(e: SecurityException): JogakboUnavailableException {
        val err = e.message?.let { decode(IllegalStateException(it)) }
        return if (err?.code == Jogakbo.Errors.NOT_APPROVED) JogakboUnavailableException(JogakboUnavailableException.Reason.NOT_APPROVED, err.message, e)
        else JogakboUnavailableException(JogakboUnavailableException.Reason.NOT_ALLOWED, e.message, e)
    }
}
