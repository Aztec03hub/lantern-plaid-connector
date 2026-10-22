package net.djvk.fireflyPlaidConnector2.api.plaid

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.djvk.fireflyPlaidConnector2.api.plaid.apis.PlaidApi
import net.djvk.fireflyPlaidConnector2.api.plaid.infrastructure.ApiClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

typealias PlaidTransactionId = String

const val clientIdHeader = "PLAID-CLIENT-ID"
const val secretHeader = "PLAID-SECRET"

/**
 * A wrapper for Plaid API calls that provides additional services:
 *  - rate limiting
 *  - retry logic
 *  - error handling
 */
@Component
class PlaidApiWrapper(
    @Value("\${fireflyPlaidConnector2.plaid.url}")
    private val baseUrl: String,
    @Value("\${fireflyPlaidConnector2.plaid.maxRetries:3}")
    private val maxRetries: Int,
    @Value("\${fireflyPlaidConnector2.plaid.clientId}")
    private val plaidClientId: String,
    @Value("\${fireflyPlaidConnector2.plaid.secret}")
    private val plaidSecret: String,
    httpClientEngine: HttpClientEngine? = null,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)? = null,
    /** Base wait between retries of a failed call; attempt N waits N times this. */
    @Value("\${fireflyPlaidConnector2.plaid.retryBackoffMillis:2000}")
    private val retryBackoffMillis: Long = 2000,
    /** Longest wait after one 429 (also the wait when Plaid sends no Retry-After); a 429 uses up a retry like any other failure. */
    @Value("\${fireflyPlaidConnector2.plaid.rateLimitWaitMillis:60000}")
    private val rateLimitWaitMillis: Long = 60_000,
) {
    private val plaidApi = PlaidApi(baseUrl, httpClientEngine, { config ->
        // ktor 3 stopped sending Accept-Charset by default; ktor 2 sent "UTF-8". Keep the wire format unchanged.
        config.defaultRequest { headers.set(HttpHeaders.AcceptCharset, "UTF-8") }
        httpClientConfig?.invoke(config)
    }) {
        ApiClient.JSON_DEFAULT.invoke(this)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        // Plaid answers 400 UNKNOWN_FIELDS to a field an endpoint does not know, even when it is null (the generated
        //  models carry fields of other products, such as payment_details on /accounts/balance/get). Send only what is set.
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
    }
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        // The Plaid client id and secret travel in headers of every request, and access tokens in the body, so a
        //  plain-http URL (typo or bad config) would send them in the clear. Loopback is allowed for local mocks.
        require(isSecureOrLoopback(baseUrl)) {
            "fireflyPlaidConnector2.plaid.url must use https (plain http is only allowed for localhost)"
        }
        plaidApi.setApiKey(plaidClientId, clientIdHeader)
        plaidApi.setApiKey(plaidSecret, secretHeader)
    }

    /**
     * Executes a request to the Plaid API
     *
     * @param logString String to include in log messages
     * @param boundRateLimit LAN-54, loan interest only: a 429 uses up the retry budget and then throws. false (poll, batch, repair-dates)
     *   keeps the original behaviour: wait [rateLimitWaitMillis] and retry with the same budget, however long it takes.
     */
    suspend fun <T> executeRequest(
        request: suspend (PlaidApi) -> T,
        logString: String,
        remainingRetries: Int = maxRetries,
        boundRateLimit: Boolean = false,
    ): T {
        if (remainingRetries <= 0) {
            throw RuntimeException("Plaid API call $logString was not attempted: maxRetries is $maxRetries, it must be at least 1")
        }
        try {
            return request(plaidApi)
        } catch (cre: ClientRequestException) {
            if (cre.response.status == HttpStatusCode.TooManyRequests) {
                if (!boundRateLimit) {
                    logger.error("429 rate limiting error encountered while calling $logString. Waiting for ${rateLimitWaitMillis / 1000} seconds.", cre)
                    delay(rateLimitWaitMillis)
                    return executeRequest(request, logString, remainingRetries, false)
                }
                // LAN-54: the budget shrinks on every 429, so a persistent one ends after maxRetries attempts and the caller sees the exception
                logger.error("429 rate limiting error encountered while calling $logString; ${remainingRetries - 1} retries remaining.", cre)
                if (remainingRetries <= 1) throw cre
                val retryAfter = cre.response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.let { it.coerceIn(0, 3600) * 1000 }
                delay(minOf(retryAfter ?: rateLimitWaitMillis, rateLimitWaitMillis))
                return executeRequest(request, logString, remainingRetries - 1, true)
            }
            val transientCode = transientPlaidError(cre)
            if (transientCode != null) {
                // LAN-50: an institution that does not answer (a 400, not a 5xx) is retried like a network failure
                logger.error("Transient Plaid error $transientCode while calling $logString; ${remainingRetries - 1} retries remaining.")
                if (remainingRetries - 1 <= 0) throw cre
                delay(retryBackoffMillis * (maxRetries - remainingRetries + 1))
                return executeRequest(request, logString, remainingRetries - 1, boundRateLimit)
            }
            throw cre
        } catch (e: CancellationException) {
            // Never swallow cancellation (app shutdown); retrying here would keep a cancelled job alive
            throw e
        } catch (e: Exception) {
            logger.error("Error encountered $logString; ${remainingRetries - 1} retries remaining.", e)
            if (remainingRetries - 1 <= 0) {
                throw RuntimeException("Plaid API call $logString failed after $maxRetries attempts", e)
            }
            // Back off so a brief network outage isn't burned through by instant retries
            delay(retryBackoffMillis * (maxRetries - remainingRetries + 1))
            return executeRequest(request, logString, remainingRetries - 1, boundRateLimit)
        }
    }

    /**
     * Plaid's `error_code` when [cre] is a transient institution or rate-limit error (error_type RATE_LIMIT or a code in
     * [TRANSIENT_INSTITUTION_CODES]), else null. Never returns the message, which can hold user data.
     */
    suspend fun transientPlaidError(cre: ClientRequestException): String? {
        val (type, code) = plaidErrorTypeAndCode(cre)
        return code?.takeIf { type == "RATE_LIMIT" || it in TRANSIENT_INSTITUTION_CODES }
    }

    /** Plaid's `error_type` and `error_code` from [cre]'s body (null when absent). Never the message, which can hold user data. */
    suspend fun plaidErrorTypeAndCode(cre: ClientRequestException): Pair<String?, String?> {
        val body = try { cre.response.bodyAsText() } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
        val type = Regex("\"error_type\"\\s*:\\s*\"([A-Z_]+)\"").find(body)?.groupValues?.get(1)
        val code = Regex("\"error_code\"\\s*:\\s*\"([A-Z_0-9]+)\"").find(body)?.groupValues?.get(1)
        return type to code
    }

    companion object {
        /** Plaid's documented INSTITUTION_ERROR codes that recover on their own. INSTITUTION_NOT_SUPPORTED, INSTITUTION_NO_LONGER_SUPPORTED, INSTITUTION_REGISTRATION_REQUIRED and the like are permanent and are not retried. */
        private val TRANSIENT_INSTITUTION_CODES = setOf("INSTITUTION_DOWN", "INSTITUTION_NOT_RESPONDING", "INSTITUTION_NOT_AVAILABLE")

        /**
         * True if [url] is https, or http to a loopback host (for local mock servers).
         */
        fun isSecureOrLoopback(url: String): Boolean {
            val uri = try {
                java.net.URI(url.trim())
            } catch (e: Exception) {
                return false
            }
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
                else -> false
            }
        }
    }
}
