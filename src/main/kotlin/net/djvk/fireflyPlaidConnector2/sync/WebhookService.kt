package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Counts describing what one polled sync iteration did. Deliberately contains no transaction content (descriptions,
 * merchants, amounts) so that the callback payload never carries financial PII.
 */
data class PollResult(
    val existingFireflyTransactionsRead: Int = 0,
    val plaidCreated: Int = 0,
    val plaidUpdated: Int = 0,
    val plaidDeleted: Int = 0,
    val fireflyCreated: Int = 0,
    val fireflyUpdated: Int = 0,
    val fireflyDeleted: Int = 0,
)

/**
 * The JSON body POSTed to the result callback after every polled sync iteration.
 *
 * [status] is "success" or "error". On "error", [errorType] holds the simple class name of the exception that ended
 * the iteration (never its message, which can contain user data) and the details are in the connector's logs.
 */
data class WebhookPayload(
    val status: String,
    val startedAt: String,
    val completedAt: String,
    val durationSeconds: Double,
    val connectorVersion: String,
    val javaVersion: String,
    val results: PollResult?,
    val errorType: String?,
)

/**
 * Reports a summary of each polled sync iteration to an operator-configured HTTP endpoint, so an external monitor can
 * alert when the connector stops syncing.
 *
 * This bean only exists when `fireflyPlaidConnector2.polled.resultCallbackUrl` is set to a non-blank value, so
 * [PolledSyncOrchestrator] receives null otherwise and only needs a null check.
 *
 * Failures to deliver the callback are logged and swallowed; they never affect the sync loop or later iterations.
 *
 * @param resultCallbackUrl absolute http(s) URL to POST to. Validated at startup; a malformed value stops the app
 *  with a clear message rather than failing silently on every iteration.
 * @param resultCallbackBearerToken optional; when present it is sent as `Authorization: Bearer <token>`. It is never
 *  logged.
 * @param httpClientEngine the engine shared with the rest of the project; a CIO engine is created when absent
 *  (as in tests that do not load the API configuration).
 * @param buildProperties build metadata, used only to report the connector version.
 */
@Component
@ConditionalOnExpression("!'\${fireflyPlaidConnector2.polled.resultCallbackUrl:}'.isBlank()")
class WebhookService(
    @Value("\${fireflyPlaidConnector2.polled.resultCallbackUrl}")
    resultCallbackUrl: String,

    @Value("\${fireflyPlaidConnector2.polled.resultCallbackBearerToken:#{null}}")
    private val resultCallbackBearerToken: String? = null,

    httpClientEngine: HttpClientEngine? = null,
    buildProperties: BuildProperties? = null,
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val callbackUri: URI = validateCallbackUrl(resultCallbackUrl.trim())

    /** Safe to log: scheme, host and path only, so a secret in a query string or userinfo is never printed. */
    private val loggableTarget =
        "${callbackUri.scheme}://${callbackUri.host}${if (callbackUri.port != -1) ":${callbackUri.port}" else ""}" +
                (callbackUri.path ?: "")

    private val connectorVersion = buildProperties?.version ?: "unknown"
    private val bearerToken = resultCallbackBearerToken?.takeIf { it.isNotBlank() }

    private val client = HttpClient(httpClientEngine ?: CIO.create()) {
        // Never throw on non-2xx; post() reports the status itself
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
        install(ContentNegotiation) {
            jackson()
        }
    }

    init {
        if (callbackUri.scheme == "http" && bearerToken != null) {
            logger.warn(
                "The result callback $loggableTarget uses plain http while a bearer token is configured; " +
                        "the token will be sent unencrypted. Use https."
            )
        }
        logger.info("Result callback enabled for $loggableTarget")
    }

    /**
     * Sends one iteration's summary to the callback. Never throws (except coroutine cancellation).
     *
     * @param startedAt when the iteration began
     * @param completedAt when the iteration ended
     * @param result what the iteration did, or null if it failed before producing a result
     * @param failure the exception that ended the iteration, or null on success
     */
    suspend fun post(startedAt: Instant, completedAt: Instant, result: PollResult?, failure: Throwable? = null) {
        val payload = WebhookPayload(
            status = if (failure == null) "success" else "error",
            startedAt = startedAt.toString(),
            completedAt = completedAt.toString(),
            durationSeconds = Duration.between(startedAt, completedAt).toMillis() / 1000.0,
            connectorVersion = connectorVersion,
            javaVersion = System.getProperty("java.version") ?: "unknown",
            results = result,
            errorType = failure?.let { it::class.simpleName },
        )

        try {
            val response = client.post(callbackUri.toString()) {
                bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (response.status.isSuccess()) {
                logger.debug("Result callback to {} returned {}", loggableTarget, response.status)
            } else {
                logger.warn("Result callback to {} returned {}", loggableTarget, response.status)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Log the class only; exception messages from HTTP clients can embed the full URL
            logger.warn("Failed to post result callback to {}: {}", loggableTarget, e::class.simpleName)
        }
    }

    override fun destroy() {
        client.close()
    }

    companion object {
        /**
         * @throws IllegalArgumentException if [url] is not an absolute http or https URL with a host
         */
        fun validateCallbackUrl(url: String): URI {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("fireflyPlaidConnector2.polled.resultCallbackUrl is not a valid URL")
            }
            require(uri.scheme == "http" || uri.scheme == "https") {
                "fireflyPlaidConnector2.polled.resultCallbackUrl must start with http:// or https://"
            }
            require(!uri.host.isNullOrBlank()) {
                "fireflyPlaidConnector2.polled.resultCallbackUrl must include a host"
            }
            return uri
        }
    }
}
