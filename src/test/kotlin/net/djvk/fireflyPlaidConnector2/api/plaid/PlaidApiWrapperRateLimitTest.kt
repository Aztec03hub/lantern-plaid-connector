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

    private fun wrapper(maxRetries: Int = 3, cap: Long = 0, retryAfter: String = "3600", answer: (Int) -> Pair<HttpStatusCode, String>): PlaidApiWrapper {
        val engine = MockEngine {
            val (status, body) = answer(++calls)
            respond(body, status, io.ktor.http.Headers.build { append(HttpHeaders.ContentType, "application/json"); append(HttpHeaders.RetryAfter, retryAfter) })
        }
        return PlaidApiWrapper("https://plaid.test", maxRetries, "id", "secret", engine, ApiConfiguration().getClientConfig(), 0, cap)
    }

    private fun call(w: PlaidApiWrapper, bound: Boolean = true) = runBlocking { w.executeRequest({ it.accountsBalanceGet(AccountsBalanceGetRequest("tok", null, null, null)) }, "balance get request", boundRateLimit = bound) }

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
    fun aHugeRetryAfterStillWaitsTheCapAndDoesNotOverflowToNoWait() {
        val t0 = System.nanoTime()
        assertThrows<ClientRequestException> { call(wrapper(cap = 300, retryAfter = "9223372036854776") { limited }) }
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(550)  // 2 waits of the 300 ms cap
    }

    @Test
    fun theDefaultPathKeepsWaitingOutA429TrainLongerThanMaxRetries() {
        call(wrapper(cap = 0) { if (it <= 7) limited else ok }, bound = false)  // maxRetries is 3
        assertThat(calls).isEqualTo(8)
    }

    @Test
    fun aShortRetryAfterIsHonouredBelowTheCap() {
        val t0 = System.nanoTime()
        call(wrapper(cap = 5000, retryAfter = "1") { if (it == 1) limited else ok })
        assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(900, 3000)
    }

    @Test
    fun withNoRetryAfterTheCapIsWaited() {
        val t0 = System.nanoTime()
        call(wrapper(cap = 300, retryAfter = "x") { if (it == 1) limited else ok })
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(280)
    }

    @Test
    fun zeroMaxRetriesFailsWithAClearMessage() {
        val e = assertThrows<RuntimeException> { call(wrapper(maxRetries = 0) { ok }) }
        assertThat(e.message).contains("maxRetries is 0")
        assertThat(calls).isEqualTo(0)
    }

    private val limited400 = HttpStatusCode.BadRequest to """{"error_type":"RATE_LIMIT","error_code":"ACCOUNTS_LIMIT"}"""

    @Test
    fun a400RateLimitWaitsLikeA429AndIsBounded() {
        val t0 = System.nanoTime()
        assertThrows<ClientRequestException> { call(wrapper(cap = 40) { limited400 }, bound = false) }  // backoff is 0, so only the cap can make it wait
        assertThat(calls).isEqualTo(3)
        assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(70, 5_000)
    }

    @Test
    fun a400WithoutAnErrorTypeIsThrownAtOnce() {
        assertThrows<ClientRequestException> { call(wrapper(cap = 40) { HttpStatusCode.BadRequest to """{"error_code":"RATE_LIMIT"}""" }) }
        assertThat(calls).isEqualTo(1)
    }
}
