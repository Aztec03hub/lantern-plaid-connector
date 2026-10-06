package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse as FireflyHttpResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.util.Utilities
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.NoRouteToHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers upstream issue #130 (container crashes when the network is down): the poll loop and its helpers must
 * survive connectivity loss without skipping data.
 */
internal class NetworkResilienceTest {
    // region Utilities

    @Test
    fun transientNetworkErrorDetection() {
        assertTrue(Utilities.isTransientNetworkError(NoRouteToHostException("No route to host")))
        assertTrue(Utilities.isTransientNetworkError(ConnectTimeoutException("timeout")))
        // Wrapped, as PlaidApiWrapper does after exhausting retries
        assertTrue(Utilities.isTransientNetworkError(RuntimeException("failed after retries", IOException("reset"))))
        assertFalse(Utilities.isTransientNetworkError(IllegalStateException("not network")))
        assertFalse(Utilities.isTransientNetworkError(RuntimeException("requires at least version 6.1.2")))
    }

    @Test
    fun accessTokenRedaction() {
        val token = "access-production-12345678-aaaa-bbbb-cccc-1234567890ab"
        val redacted = Utilities.redactAccessToken(token)
        assertEquals("access-****90ab", redacted)
        assertFalse(redacted.contains("production"))
        assertEquals("access-****", Utilities.redactAccessToken("short"))
    }

    // endregion

    // region PlaidApiWrapper

    private fun wrapper(maxRetries: Int) = PlaidApiWrapper(
        baseUrl = "https://plaid.test",
        maxRetries = maxRetries,
        plaidClientId = "id",
        plaidSecret = "secret",
        httpClientEngine = MockEngine { respond("{}", HttpStatusCode.OK) },
        retryBackoffMillis = 1,
    )

    @Test
    fun wrapperRetriesTransientFailureThenSucceeds() = runBlocking {
        var attempts = 0
        val result = wrapper(3).executeRequest({
            attempts++
            if (attempts < 3) throw NoRouteToHostException("No route to host") else "ok"
        }, "test call")

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun wrapperGivesUpAfterMaxRetriesKeepingTheCause() = runBlocking {
        var attempts = 0
        val e = assertThrows<RuntimeException> {
            runBlocking {
                wrapper(3).executeRequest<String>({
                    attempts++
                    throw NoRouteToHostException("No route to host")
                }, "test call")
            }
        }

        assertEquals(3, attempts)
        assertTrue(e.cause is NoRouteToHostException, "cause must be preserved so callers can classify it")
        assertTrue(Utilities.isTransientNetworkError(e))
    }

    @Test
    fun wrapperDoesNotSwallowCancellation() = runBlocking {
        var attempts = 0
        assertThrows<CancellationException> {
            runBlocking {
                wrapper(3).executeRequest<String>({
                    attempts++
                    throw CancellationException("shutting down")
                }, "test call")
            }
        }
        assertEquals(1, attempts, "a cancelled call must not be retried")
    }

    // endregion

    // region PlaidSyncService

    @Test
    fun allowItemToFailAlsoCoversNetworkFailures() = runBlocking {
        // A wrapper that throws like the real one does once its retries are exhausted
        val wrapper: PlaidApiWrapper = mock()
        whenever(wrapper.executeRequest<Any>(any(), any(), any())).doSuspendableAnswer {
            throw RuntimeException("Plaid API call failed after 3 attempts", IOException("down"))
        }
        val tolerant = PlaidSyncService(wrapper, 100, true)
        val strict = PlaidSyncService(wrapper, 100, false)

        assertNull(tolerant.executeTransactionSyncRequest("access-token", null))
        assertThrows<RuntimeException> { runBlocking { strict.executeTransactionSyncRequest("access-token", null) } }
    }

    // endregion

    // region PolledSyncOrchestrator

    private val syncHelper: SyncHelper = mock()
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock()
    private val converter: TransactionConverter = mock()

    private fun orchestrator() = PolledSyncOrchestrator(
        30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter
    )

    @Test
    fun cursorIsNotAdvancedWhenFireflyProcessingFails() = runBlocking {
        val accountMap = mapOf("account1" to 1)
        val sequence = sequenceOf(Pair("token1", listOf("account1")))
        val committed = mutableMapOf("token1" to "cursor-old")

        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        // Plaid "advances" whatever cursor map it is handed, like the real service
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["token1"] = "cursor-new"
            PlaidTransactionResult(emptyList(), emptyList(), emptyList())
        }
        whenever(converter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(TransactionConverter.ConvertPollSyncResult(emptyList(), emptyList(), emptyList()))
        whenever(fireflyTransactionService.processFireflyTransactionUpdates(any(), any(), any()))
            .doSuspendableAnswer { throw ConnectTimeoutException("Firefly unreachable") }

        assertThrows<ConnectTimeoutException> {
            runBlocking { orchestrator().processTransactions(accountMap, sequence, committed) }
        }

        assertEquals("cursor-old", committed["token1"], "cursor must not move if Firefly did not accept the data")
        verify(cursorManager, never()).writeCursorMap(any())
    }

    @Test
    fun cursorIsCommittedAfterSuccess() = runBlocking {
        val accountMap = mapOf("account1" to 1)
        val sequence = sequenceOf(Pair("token1", listOf("account1")))
        val committed = mutableMapOf("token1" to "cursor-old")

        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["token1"] = "cursor-new"
            PlaidTransactionResult(emptyList(), emptyList(), emptyList())
        }
        whenever(converter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(TransactionConverter.ConvertPollSyncResult(emptyList(), emptyList(), emptyList()))

        orchestrator().processTransactions(accountMap, sequence, committed)

        assertEquals("cursor-new", committed["token1"])
        verify(cursorManager).writeCursorMap(mapOf("token1" to "cursor-new"))
    }

    @Test
    fun retryWhileNetworkDownRetriesThenSucceeds() = runBlocking {
        var attempts = 0
        val result = orchestrator().retryWhileNetworkDown("test", 1.milliseconds) {
            attempts++
            if (attempts < 3) throw NoRouteToHostException("No route to host") else "up"
        }

        assertEquals("up", result)
        assertEquals(3, attempts)
    }

    @Test
    fun retryWhileNetworkDownDoesNotRetryNonNetworkErrors() = runBlocking {
        var attempts = 0
        assertThrows<IllegalStateException> {
            runBlocking {
                orchestrator().retryWhileNetworkDown("test", 1.milliseconds) {
                    attempts++
                    throw IllegalStateException("bad config")
                }
            }
        }
        assertEquals(1, attempts)
    }

    // endregion

    // region SyncHelper

    @Test
    fun optimisticInsertFailsTheBatchOnTimeoutInsteadOfSilentlyDroppingIt() = runBlocking {
        val firefly = FireflyMock()
        var calls = 0
        whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
            calls++
            if (calls == 1) throw ConnectTimeoutException("timeout") else mock<FireflyHttpResponse<TransactionSingle>>()
        }
        val helper = SyncHelper(
            net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs(emptyList()),
            "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi,
        )
        val dto = FireflyTransactionDto(null, FireflyFixtures.getTransaction().transactions.first())
        val dto2 = FireflyTransactionDto(null, FireflyFixtures.getTransaction(description = "second").transactions.first())

        val e = assertThrows<IOException> { runBlocking { helper.optimisticInsertBatchIntoFirefly(listOf(dto, dto2)) } }

        assertTrue(Utilities.isTransientNetworkError(e))
        assertEquals(2, calls, "the second insert must still be attempted")
        assertNotNull(e.cause)
    }

    // endregion
}
