package net.djvk.fireflyPlaidConnector2.api

import io.ktor.client.engine.mock.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * LAN-41 r1 M1 and M2: what the ktor 3 clients put on the wire, and what the retry/batch code does when a request
 * times out. The expected values were measured on the 922890f build (ktor 2.3.13) with this same file.
 */
internal class WireAndTimeoutTest {
    private val ok = """{"data":{"version":"6.1.2","api_version":"1.0","php_version":"8","os":"Linux","driver":"mysql"}}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // region M2: request headers on the wire

    private fun plaidWrapper(engine: MockEngine) = PlaidApiWrapper(
        baseUrl = "https://plaid.test", maxRetries = 1, plaidClientId = "cid", plaidSecret = "sec",
        httpClientEngine = engine,
    )

    @Test
    fun plaidPostWireHeaders() = runBlocking<Unit> {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val plaid = plaidWrapper(MockEngine { request ->
            seen = request
            respond(ByteReadChannel("""{"accounts":[],"item":{},"request_id":"r"}"""), HttpStatusCode.OK, jsonHeaders)
        })
        plaid.executeRequest({ it.accountsBalanceGet(AccountsBalanceGetRequest(accessToken = "tok")) }, "wire")
        val r = seen!!
        assertEquals(HttpMethod.Post, r.method)
        assertEquals(EXPECTED_JSON_CONTENT_TYPE, r.body.contentType.toString())
        assertEquals(listOf("application/json"), r.headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("UTF-8"), r.headers.getAll(HttpHeaders.AcceptCharset))
        assertEquals(listOf("cid"), r.headers.getAll("PLAID-CLIENT-ID"))
        assertEquals(listOf("sec"), r.headers.getAll("PLAID-SECRET"))
        assertNull(r.headers[HttpHeaders.Authorization], "Plaid uses key headers, not Authorization")
    }

    @Test
    fun fireflyPostWireHeaders() = runBlocking<Unit> {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val api = TransactionsApi("https://firefly.test", MockEngine { request ->
            seen = request
            respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
        })
        api.setAccessToken("secret-token")
        val store = TransactionStore(FireflyFixtures.getTransaction().transactions)
        try { api.storeTransaction(store) } catch (_: Exception) { /* the empty {} body does not parse; only the request matters */ }
        val r = seen!!
        assertEquals(HttpMethod.Post, r.method)
        assertEquals(EXPECTED_JSON_CONTENT_TYPE, r.body.contentType.toString())
        assertEquals(listOf("application/json"), r.headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("UTF-8"), r.headers.getAll(HttpHeaders.AcceptCharset), "exactly one Accept-Charset")
        assertEquals(listOf("Bearer secret-token"), r.headers.getAll(HttpHeaders.Authorization))
        // The body must be sent as UTF-8 whatever the declared charset is
        assertContains(r.body.toByteArray().toString(Charsets.UTF_8), "\"transactions\"")
    }

    @Test
    fun fireflyGetWireHeaders() = runBlocking<Unit> {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val api = AboutApi("https://firefly.test", MockEngine { request ->
            seen = request
            respond(ByteReadChannel(ok), HttpStatusCode.OK, jsonHeaders)
        })
        api.setAccessToken("secret-token")
        api.getAbout()
        val r = seen!!
        assertEquals(HttpMethod.Get, r.method)
        assertEquals(listOf("application/json"), r.headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("UTF-8"), r.headers.getAll(HttpHeaders.AcceptCharset))
        assertEquals(listOf("Bearer secret-token"), r.headers.getAll(HttpHeaders.Authorization))
    }

    @Test
    fun redactedErrorMessageHasNoCredentials() = runBlocking<Unit> {
        // L2: a logged 429 must not carry the Authorization value or the access token
        val plaid = PlaidApiWrapper(
            baseUrl = "https://plaid.test", maxRetries = 1, plaidClientId = "cid", plaidSecret = "sec",
            httpClientEngine = MockEngine {
                respond(ByteReadChannel("""{"error_code":"RATE_LIMIT"}"""), HttpStatusCode.BadRequest, jsonHeaders)
            },
            httpClientConfig = { it.expectSuccess = true },
        )
        val e = assertThrows<io.ktor.client.plugins.ClientRequestException> {
            runBlocking { plaid.executeRequest({ it.accountsBalanceGet(AccountsBalanceGetRequest(accessToken = "tok-SECRET")) }, "x") }
        }
        assertFalse(e.message.contains("tok-SECRET"))
        assertFalse(e.message.contains("Authorization", ignoreCase = true))
    }

    // endregion

    // region M1: timeouts

    /** Same shape as ApiConfiguration.getClientConfig (expectSuccess + HttpTimeout) with a short timeout. */
    private val shortTimeoutConfig: (io.ktor.client.HttpClientConfig<*>) -> Unit = {
        it.expectSuccess = true
        it.install(HttpTimeout) { requestTimeoutMillis = 50 }
    }

    private suspend fun MockRequestHandleScope.fail(kind: String, request: io.ktor.client.request.HttpRequestData): io.ktor.client.request.HttpResponseData =
        when (kind) {
            "connect" -> throw ConnectTimeoutException("connect timed out", null)
            "socket" -> throw SocketTimeoutException("socket timed out", null)
            else -> { delay(2000); respond("{}", HttpStatusCode.OK, jsonHeaders) } // "request": HttpTimeout fires first
        }

    private fun plaidTimeoutRun(kind: String): Pair<Int, Throwable> {
        var calls = 0
        val engine = MockEngine { request -> calls++
            fail(kind, request) }
        val plaid = PlaidApiWrapper(
            baseUrl = "https://plaid.test", maxRetries = 2, plaidClientId = "c", plaidSecret = "s",
            httpClientEngine = engine, httpClientConfig = shortTimeoutConfig, retryBackoffMillis = 1,
        )
        val e = assertThrows<Throwable> {
            runBlocking { plaid.executeRequest({ it.accountsBalanceGet(AccountsBalanceGetRequest(accessToken = "t")) }, "t") }
        }
        return calls to e
    }

    @Test
    fun plaidRetriesEveryTimeoutKindAndKeepsTheCause() {
        for ((kind, type) in listOf(
            "connect" to ConnectTimeoutException::class, "socket" to SocketTimeoutException::class,
            "request" to HttpRequestTimeoutException::class,
        )) {
            val (calls, e) = plaidTimeoutRun(kind)
            assertEquals(2, calls, "$kind timeout: both attempts made (retried)")
            assertTrue(e is RuntimeException && e !is java.util.concurrent.CancellationException, "$kind: wrapped, got $e")
            assertTrue(type.isInstance(e.cause), "$kind: cause kept, got ${e.cause}")
            assertTrue(e.cause is IOException, "$kind: still an IOException so isTransientNetworkError holds")
        }
    }

    private fun helperWith(engine: MockEngine): SyncHelper {
        val firefly = FireflyMock()
        val tx = TransactionsApi("https://firefly.test", engine, shortTimeoutConfig)
        tx.setAccessToken("t")
        return SyncHelper(AccountConfigs(emptyList()), "token", firefly.aboutApi, tx, firefly.accountsApi, firefly.plaidLinksApi)
    }

    private fun batch(kind: String): Pair<Int, Throwable> {
        var calls = 0
        val engine = MockEngine { request -> calls++
            fail(kind, request) }
        val dtos = listOf(
            net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto(null, FireflyFixtures.getTransaction().transactions.first()),
            net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto(null, FireflyFixtures.getTransaction(description = "second").transactions.first()),
        )
        val e = assertThrows<Throwable> { runBlocking { helperWith(engine).optimisticInsertBatchIntoFirefly(dtos) } }
        return calls to e
    }

    @Test
    fun fireflyConnectTimeoutKeepsGoingThenFailsTheBatchWithIOException() {
        val (calls, e) = batch("connect")
        assertEquals(2, calls, "second insert still attempted")
        assertTrue(e is IOException && e.cause is ConnectTimeoutException, "got $e / ${e.cause}")
        assertContains(e.message!!, "2 of 2 Firefly inserts timed out")
    }

    @Test
    fun fireflySocketAndRequestTimeoutsAbortTheBatchRawAsBefore() {
        // ktor 2.3.13 behaviour (measured, same on 3.6.0): only ConnectTimeoutException is caught in the insert loop;
        //  the others leave the batch unwrapped (both inserts run concurrently), so the caller does not commit its cursor.
        for ((kind, type) in listOf("socket" to SocketTimeoutException::class, "request" to HttpRequestTimeoutException::class)) {
            val (calls, e) = batch(kind)
            assertTrue(type.isInstance(e), "$kind: got $e")
            assertTrue(e is IOException)
            assertEquals(2, calls, "$kind: both inserts were attempted")
        }
    }

    // endregion

    companion object {
        /** Measured on 922890f (ktor 2.3.13) and on ktor 3.6.0: identical, no charset parameter. */
        const val EXPECTED_JSON_CONTENT_TYPE = "application/json"
    }
}
