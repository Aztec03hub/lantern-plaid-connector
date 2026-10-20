package net.djvk.fireflyPlaidConnector2.api.plaid

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** LAN-54 M2: a 429 uses up the retry budget, honours Retry-After only up to the cap, and then the exception propagates. */
internal class PlaidApiWrapperRateLimitTest {
    private var calls = 0

    private fun wrapper(maxRetries: Int = 3, cap: Long = 0, answer: (Int) -> Pair<HttpStatusCode, String>): PlaidApiWrapper {
        val engine = MockEngine {
            val (status, body) = answer(++calls)
            respond(body, status, io.ktor.http.Headers.build { append(HttpHeaders.ContentType, "application/json"); append(HttpHeaders.RetryAfter, "3600") })
        }
        return PlaidApiWrapper("https://plaid.test", maxRetries, "id", "secret", engine, ApiConfiguration().getClientConfig(), 0, cap)
    }

    private fun call(w: PlaidApiWrapper) = runBlocking { w.executeRequest({ it.accountsBalanceGet(AccountsBalanceGetRequest("tok", null, null, null)) }, "balance get request") }

    private val ok = HttpStatusCode.OK to """{"accounts":[],"item":{"available_products":[],"billed_products":[],"consent_expiration_time":null,"error":null,"institution_id":"i","item_id":"z","products":[],"update_type":"background","webhook":""},"request_id":"r"}"""
    private val limited = HttpStatusCode.TooManyRequests to "{}"

    @Test
    fun aPersistent429EndsAfterMaxRetriesAttemptsAndPropagates() {
        assertThrows<ClientRequestException> { call(wrapper { limited }) }
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun a429ThenSuccessStillWorks() {
        call(wrapper { if (it == 1) limited else ok })
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun retryAfterIsCappedSoTheWaitIsBounded() {
        val t0 = System.nanoTime()
        assertThrows<ClientRequestException> { call(wrapper(cap = 20) { limited }) }  // Retry-After says 3600 s each time
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun zeroMaxRetriesFailsWithAClearMessage() {
        val e = assertThrows<RuntimeException> { call(wrapper(maxRetries = 0) { ok }) }
        assertThat(e.message).contains("maxRetries is 0")
        assertThat(calls).isEqualTo(0)
    }
}
