package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers upstream issue #69: some institutions only deliver new transactions to /transactions/sync after
 * /transactions/refresh is called, so the poll loop can optionally request a refresh on an interval.
 */
internal class TransactionsRefreshTest {
    private val requests = mutableListOf<Pair<String, String>>()

    private fun plaidServiceReturning(status: HttpStatusCode, body: String): PlaidSyncService {
        val engine = MockEngine { request ->
            requests.add(Pair(request.url.encodedPath, String(request.body.toByteArray())))
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val wrapper = PlaidApiWrapper(
            baseUrl = "https://plaid.test",
            maxRetries = 1,
            plaidClientId = "client",
            plaidSecret = "secret",
            httpClientEngine = engine,
            httpClientConfig = ApiConfiguration().getClientConfig(),
        )
        return PlaidSyncService(wrapper, 100, false)
    }

    @Test
    fun refreshPostsToTheRefreshEndpointWithTheAccessToken() = runBlocking<Unit> {
        val service = plaidServiceReturning(HttpStatusCode.OK, """{"request_id":"req1"}""")

        val ok = service.refreshTransactions("access-sandbox-token-1")

        assertTrue(ok)
        assertEquals(1, requests.size)
        assertEquals("/transactions/refresh", requests.single().first)
        assertTrue(requests.single().second.contains("\"access_token\" : \"access-sandbox-token-1\""))
    }

    @Test
    fun refreshFailureReturnsFalseAndDoesNotThrow() = runBlocking<Unit> {
        val service = plaidServiceReturning(
            HttpStatusCode.BadRequest,
            """{"error_type":"INVALID_REQUEST","error_code":"PRODUCT_NOT_ENABLED","error_message":"x","request_id":"r"}"""
        )

        assertFalse(service.refreshTransactions("access-sandbox-token-1"))
    }

    private val plaidSyncService: PlaidSyncService = mock()

    private fun orchestrator(refreshIntervalMinutes: Long) = PolledSyncOrchestrator(
        30, mock<SyncHelper>(), mock<CursorManager>(), plaidSyncService, mock<FireflyTransactionService>(),
        mock<TransactionConverter>(), refreshIntervalMinutes = refreshIntervalMinutes,
    )

    private val items = sequenceOf(Pair("token-a", listOf("acct1")), Pair("token-b", listOf("acct2")))
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun disabledByDefaultNeverRefreshes() = runBlocking<Unit> {
        orchestrator(0).refreshItemsDue(items, t0)

        verify(plaidSyncService, never()).refreshTransactions(any())
    }

    @Test
    fun refreshesEachItemOnFirstPollThenOnlyWhenTheIntervalHasElapsed() = runBlocking<Unit> {
        val orchestrator = orchestrator(60)

        orchestrator.refreshItemsDue(items, t0)
        verify(plaidSyncService).refreshTransactions(eq("token-a"))
        verify(plaidSyncService).refreshTransactions(eq("token-b"))

        // 30 minutes later: not due
        orchestrator.refreshItemsDue(items, t0.plusSeconds(30 * 60))
        verify(plaidSyncService, times(1)).refreshTransactions(eq("token-a"))

        // 61 minutes later: due again
        orchestrator.refreshItemsDue(items, t0.plusSeconds(61 * 60))
        verify(plaidSyncService, times(2)).refreshTransactions(eq("token-a"))
        verify(plaidSyncService, times(2)).refreshTransactions(eq("token-b"))
    }
}
