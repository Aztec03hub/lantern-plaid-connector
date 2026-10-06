package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WebhookServiceTest {
    private val mapper = ObjectMapper()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-01-01T00:00:02.500Z")

    private val sampleResult = PollResult(
        existingFireflyTransactionsRead = 12,
        plaidCreated = 3,
        plaidUpdated = 2,
        plaidDeleted = 1,
        fireflyCreated = 3,
        fireflyUpdated = 2,
        fireflyDeleted = 1,
    )

    private class Captured {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
    }

    private fun engine(captured: Captured, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { request ->
        captured.requests.add(request)
        captured.bodies.add(String(request.body.toByteArray()))
        respond("OK", status)
    }

    @Test
    fun postsNonEmptyResultsWithBearerToken() = runBlocking<Unit> {
        val captured = Captured()
        val service = WebhookService("https://monitor.example.com/hook", "secret-token", engine(captured))

        service.post(start, end, sampleResult)

        assertEquals(1, captured.requests.size)
        val request = captured.requests.single()
        assertEquals("https://monitor.example.com/hook", request.url.toString())
        assertEquals("Bearer secret-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("application/json"))

        val json: JsonNode = mapper.readTree(captured.bodies.single())
        assertEquals("success", json["status"].asText())
        assertEquals("2026-01-01T00:00:00Z", json["startedAt"].asText())
        assertEquals(2.5, json["durationSeconds"].asDouble())
        assertEquals(3, json["results"]["plaidCreated"].asInt())
        assertEquals(12, json["results"]["existingFireflyTransactionsRead"].asInt())
        assertTrue(json["errorType"].isNull)
        // No transaction content must ever appear in the payload
        assertTrue(!captured.bodies.single().contains("description"))
    }

    @Test
    fun omitsAuthorizationHeaderWhenNoTokenConfigured() = runBlocking<Unit> {
        val captured = Captured()
        val service = WebhookService("https://monitor.example.com/hook", "  ", engine(captured))

        service.post(start, end, sampleResult)

        assertEquals(1, captured.requests.size)
        assertNull(captured.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun reportsFailureWithErrorTypeOnlyAndNoMessage() = runBlocking<Unit> {
        val captured = Captured()
        val service = WebhookService("https://monitor.example.com/hook", null, engine(captured))

        service.post(start, end, null, IllegalStateException("contains a private description"))

        val json = mapper.readTree(captured.bodies.single())
        assertEquals("error", json["status"].asText())
        assertEquals("IllegalStateException", json["errorType"].asText())
        assertTrue(json["results"].isNull)
        assertTrue(!captured.bodies.single().contains("private description"))
    }

    @Test
    fun serverErrorDoesNotThrow() = runBlocking<Unit> {
        val captured = Captured()
        val service = WebhookService(
            "https://monitor.example.com/hook", null, engine(captured, HttpStatusCode.InternalServerError)
        )

        service.post(start, end, sampleResult)

        assertEquals(1, captured.requests.size)
    }

    @Test
    fun networkFailureDoesNotThrowAndNextPostStillWorks() = runBlocking<Unit> {
        var calls = 0
        val failingThenOk = MockEngine {
            calls++
            if (calls == 1) throw IOException("connection refused") else respond("OK", HttpStatusCode.OK)
        }
        val service = WebhookService("https://monitor.example.com/hook", null, failingThenOk)

        service.post(start, end, sampleResult) // fails, must not throw
        service.post(start, end, sampleResult) // client must still be usable

        assertEquals(2, calls)
    }

    private val contextRunner = ApplicationContextRunner().withUserConfiguration(WebhookService::class.java)

    @Test
    fun beanIsAbsentWhenCallbackUrlUnsetOrBlank() {
        contextRunner.run { ctx -> assertThat(ctx).doesNotHaveBean(WebhookService::class.java) }
        contextRunner.withPropertyValues("fireflyPlaidConnector2.polled.resultCallbackUrl=")
            .run { ctx -> assertThat(ctx).doesNotHaveBean(WebhookService::class.java) }
    }

    @Test
    fun beanIsPresentWhenCallbackUrlSetWithOptionalTokenAbsent() {
        contextRunner.withPropertyValues("fireflyPlaidConnector2.polled.resultCallbackUrl=https://monitor.example.com/x")
            .run { ctx -> assertThat(ctx).hasSingleBean(WebhookService::class.java) }
    }

    @Test
    fun contextFailsFastOnMalformedUrl() {
        contextRunner.withPropertyValues("fireflyPlaidConnector2.polled.resultCallbackUrl=ftp://nope")
            .run { ctx -> assertThat(ctx).hasFailed() }
    }

    @Test
    fun rejectsMalformedUrls() {
        assertThrows<IllegalArgumentException> { WebhookService("not a url", null, engine(Captured())) }
        assertThrows<IllegalArgumentException> { WebhookService("ftp://example.com/x", null, engine(Captured())) }
        assertThrows<IllegalArgumentException> { WebhookService("https:///path-only", null, engine(Captured())) }
    }
}
