package net.djvk.fireflyPlaidConnector2.util

import io.ktor.client.plugins.ServerResponseException
import java.io.IOException

object Utilities {
    fun getRandomAlphabeticalString(length: Int) : String {
        val allowedChars = ('A'..'Z') + ('a'..'z')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    /**
     * True if [e] or anything in its cause chain looks like a transient connectivity problem (no route, DNS failure,
     * connect/read timeout, connection reset) or a 5xx from the remote end. Client errors (4xx, such as a bad
     * credential) are not transient: retrying them forever would only hide a misconfiguration.
     */
    fun isTransientNetworkError(e: Throwable): Boolean {
        return generateSequence(e) { it.cause }.any { it is IOException || it is ServerResponseException }
    }

    /**
     * Masks a Plaid access token for logging: keeps only the last four characters, enough to tell Items apart in
     * logs but useless for calling the Plaid API.
     */
    fun redactAccessToken(token: String): String {
        return if (token.length <= 8) "access-****" else "access-****${token.takeLast(4)}"
    }
}