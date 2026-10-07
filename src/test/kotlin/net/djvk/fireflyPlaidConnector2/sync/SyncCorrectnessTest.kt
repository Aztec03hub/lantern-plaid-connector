package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupRow
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse as FireflyHttpResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.RemovedTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Bugs found while auditing the sync logic: duplicate/over-eager Firefly paging, a page-limit off-by-one, Plaid
 * changes to transactions older than the Firefly pull window being silently dropped, and no handling of Plaid's
 * "data changed during pagination" restart.
 */
internal class SyncCorrectnessTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    // region Firefly paging

    private val txApi: TransactionsApi = mock()

    private fun page(totalPages: Int, current: Int): FireflyHttpResponse<TransactionArray> {
        val pagination = mock<MetaPagination> {
            on { currentPage } doReturn current
            on { this.totalPages } doReturn totalPages
        }
        val meta = mock<Meta> { on { this.pagination } doReturn pagination }
        val array = mock<TransactionArray> {
            on { data } doReturn listOf()
            on { this.meta } doReturn meta
        }
        return mock { onBlocking { body() } doReturn array }
    }

    private fun serviceWithPages(totalPages: Int): FireflyTransactionService {
        // Respond for whichever page is asked for, as Firefly does
        runBlocking {
            whenever(txApi.listTransaction(any(), any(), any(), any())).doSuspendableAnswer {
                page(totalPages, it.getArgument<Int>(0))
            }
        }
        return FireflyTransactionService(txApi, mock(), 5, "UTC", mock())
    }

    @Test
    fun pagingStartsAtPageOneAndFetchesEachPageOnce() = runBlocking<Unit> {
        serviceWithPages(3).fetchExistingFireflyTransactions()

        val pages = argumentCaptor<Int>()
        verify(txApi, times(3)).listTransaction(pages.capture(), any(), any(), any())
        assertThat(pages.allValues).containsExactly(1, 2, 3)
    }

    @Test
    fun exactlyTheMaximumNumberOfPagesIsNotAnError() = runBlocking<Unit> {
        serviceWithPages(20).fetchExistingFireflyTransactions()

        verify(txApi, times(20)).listTransaction(any(), any(), any(), any())
    }

    @Test
    fun moreThanTheMaximumNumberOfPagesIsAnError() = runBlocking<Unit> {
        val service = serviceWithPages(21)

        assertThrows<RuntimeException> { runBlocking { service.fetchExistingFireflyTransactions() } }
    }

    // endregion

    // region out-of-window lookup

    private val plaidLinksApi: PlaidLinksApi = mock()

    private fun fireflyTx(id: String, plaidId: String) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(sourceId = "1", plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "acct"))),
        ObjectLink()
    )

    private fun lookup(vararg rows: PlaidLinkLookupRow) = createFireflyResponse(PlaidLinkLookupResponse(rows.toList()))

    private fun row(plaidId: String, group: String) = PlaidLinkLookupRow(plaidId, "j$group", group, PlaidLinkLeg.single, "acct")

    private val service = FireflyTransactionService(txApi, mock(), 5, "America/Chicago", plaidLinksApi)

    @Test
    fun looksUpOnlyTheTransactionsMissingFromTheWindowByPlaidLink() = runBlocking<Unit> {
        val inWindow = fireflyTx("ff1", "inWindow")
        lookup(row("old1", "ff9")).let { r -> whenever(plaidLinksApi.lookupPlaidLinks(eq(listOf("old1")))).thenReturn(r) }
        createFireflyResponse(TransactionSingle(fireflyTx("ff9", "old1"))).let { r -> whenever(txApi.getTransaction("ff9")).thenReturn(r) }

        val found = service.fetchMissingByPlaidId(listOf("inWindow", "old1", "old1"), listOf(inWindow))

        assertThat(found.map { it.id }).containsExactly("ff9")
        verify(plaidLinksApi, times(1)).lookupPlaidLinks(any()) // not for inWindow, not twice for old1
        verify(txApi, times(1)).getTransaction(any())
    }

    @Test
    fun lookupFailuresPropagateSoTheIterationIsRetried() = runBlocking<Unit> {
        whenever(plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer { throw java.io.IOException("down") }

        // M1: swallowing this let the caller commit its cursor over an update or delete that was never applied
        val e = runCatching { service.fetchMissingByPlaidId(listOf("old1"), listOf()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun aFailedTransactionReadPropagatesToo() = runBlocking<Unit> {
        lookup(row("old1", "ff9")).let { r -> whenever(plaidLinksApi.lookupPlaidLinks(any())).thenReturn(r) }
        whenever(txApi.getTransaction(any())).doSuspendableAnswer { throw java.io.IOException("down") }

        val e = runCatching { service.fetchMissingByPlaidId(listOf("old1"), listOf()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun moreThanOneHundredMissingIdsAreAllLookedUpInOneBatch() = runBlocking<Unit> {
        val empty = lookup()
        whenever(plaidLinksApi.lookupPlaidLinks(any())).thenReturn(empty)

        service.fetchMissingByPlaidId((1..250).map { "id$it" }, listOf())

        // one request per 500 ids, no per-id searches (the old scheme made two searches per id)
        val ids = argumentCaptor<List<String>>()
        verify(plaidLinksApi, times(1)).lookupPlaidLinks(ids.capture())
        assertThat(ids.firstValue).hasSize(250)
    }

    @Test
    fun aLookupThatFindsNothingReadsNoTransaction() = runBlocking<Unit> {
        lookup().let { r -> whenever(plaidLinksApi.lookupPlaidLinks(any())).thenReturn(r) }

        assertThat(service.fetchMissingByPlaidId(listOf("old1"), listOf())).isEmpty()
        verify(txApi, org.mockito.kotlin.never()).getTransaction(any())
    }

    // endregion

    // region orchestrator flow

    private val syncHelper: SyncHelper = mock()
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock {
        // 0 creates were dead-lettered (a bare mock returns null for the boxed Int)
        onBlocking { processFireflyTransactionUpdates(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()) } doReturn 0
    }
    private val converter: net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter = mock()

    private fun orchestrator() = PolledSyncOrchestrator(
        30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter
    )

    @Test
    fun aPollWithNoPlaidChangesNeverTouchesFireflyButStillCommitsTheCursor() = runBlocking<Unit> {
        val cursors = mutableMapOf("t" to "old")
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["t"] = "new"
            PlaidTransactionResult(listOf(), listOf(), listOf())
        }

        orchestrator().processTransactions(mapOf(), sequenceOf(), cursors)

        verify(fireflyTransactionService, org.mockito.kotlin.never()).fetchExistingFireflyTransactions()
        verify(fireflyTransactionService, org.mockito.kotlin.never()).processFireflyTransactionUpdates(any(), any(), any())
        assertThat(cursors["t"]).isEqualTo("new")
        verify(cursorManager).writeCursorMap(mapOf("t" to "new"))
    }

    @Test
    fun removalOfATransactionOlderThanTheWindowIsLookedUpAndPassedToTheConverter() = runBlocking<Unit> {
        val old = fireflyTx("ffOld", "oldId")
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(listOf(), listOf(), listOf("oldId")))
        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(listOf())
        whenever(fireflyTransactionService.fetchMissingByPlaidId(any(), any())).thenReturn(listOf(old))
        whenever(fireflyTransactionService.windowStart()).thenReturn(java.time.LocalDate.now().minusDays(5))
        whenever(converter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter.ConvertPollSyncResult(listOf(), listOf(), listOf("ffOld")))

        orchestrator().processTransactions(mapOf(), sequenceOf(), mutableMapOf())

        verify(converter).convertPollSync(any(), any(), any(), eq(listOf("oldId")), eq(listOf(old)))
        verify(fireflyTransactionService).processFireflyTransactionUpdates(any(), any(), eq(listOf("ffOld")))
    }

    // endregion

    // region amounts

    @Test
    fun largeAmountsAreNeverSentInScientificNotation() = runBlocking<Unit> {
        val converter = net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter(
            useNameForDestination = true,
            enablePrimaryCategorization = false,
            primaryCategoryPrefix = "a",
            enableDetailedCategorization = false,
            detailedCategoryPrefix = "b",
            timeZoneString = "America/New_York",
            transferMatchWindowDays = 3L,
            txStyle = net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig(null),
        )
        val txs = listOf(10_000_000.0, 12_345_678.9, 12.5, 100.0).mapIndexed { i, amount ->
            PlaidFixtures.getPaymentTransaction(
                accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", transactionId = "big$i",
                pendingTransactionId = null, amount = amount,
                personalFinanceCategory = null,
            )
        }

        val result = converter.convertBatchSync(txs, PlaidFixtures.getStandardAccountMapping())

        assertThat(result.map { it.tx.amount })
            .containsExactlyInAnyOrder("10000000", "12345678.9", "12.5", "100.0")
    }

    // endregion

    // region mutation during pagination

    private val plaidWrapper: PlaidApiWrapper = mock()

    private fun mutationException(): ClientRequestException = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"error_code":"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION","error_type":"TRANSACTIONS_ERROR"}""",
                    HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { expectSuccess = true }
        try {
            client.post("https://plaid.test/transactions/sync"); null
        } catch (e: ClientRequestException) {
            e
        }!!
    }

    private fun syncResponse(
        added: List<net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction>,
        cursor: String,
        hasMore: Boolean,
    ): TransactionsSyncResponse = mock {
        on { this.added } doReturn added
        on { modified } doReturn listOf()
        on { removed } doReturn listOf<RemovedTransaction>()
        on { nextCursor } doReturn cursor
        on { this.hasMore } doReturn hasMore
    }

    @Test
    fun paginationIsRestartedFromTheOriginalCursorWhenPlaidDataChangesMidway() = runBlocking<Unit> {
        val tx1 = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "tx1")
        val tx2 = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "tx2")
        var call = 0
        whenever(plaidWrapper.executeRequest<Any>(any(), any(), any())).doSuspendableAnswer {
            call++
            when (call) {
                1 -> createPlaidResponse(syncResponse(listOf(tx1), "c1", hasMore = true))
                2 -> throw mutationException() // page 2 fails: data changed
                3 -> createPlaidResponse(syncResponse(listOf(tx1, tx2), "c9", hasMore = false)) // restart
                else -> error("unexpected call $call")
            }
        }
        val service = PlaidSyncService(plaidWrapper, 100, false)
        val cursors = mutableMapOf("tok" to "start")

        val result = service.processPlaidTransactions(sequenceOf(Pair("tok", listOf("acct"))), cursors)

        // tx1 must not be counted twice, and the cursor ends at the restarted pagination's cursor
        assertThat(result.created.map { it.transactionId }).containsExactly("tx1", "tx2")
        assertThat(cursors["tok"]).isEqualTo("c9")
        assertThat(call).isEqualTo(3)
    }

    /** R1 L1: the restart must really use the cursor the sync began at, not just call Plaid again. */
    @Test
    fun theRestartedPaginationSendsTheOriginalCursorToPlaid() = runBlocking<Unit> {
        val plaid = net.djvk.fireflyPlaidConnector2.lib.PlaidMock()
        val sentCursors = mutableListOf<String?>()
        val tx1 = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "tx1")
        var call = 0
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer {
            sentCursors.add((it.getArgument<net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest>(0)).cursor)
            when (++call) {
                1 -> createPlaidResponse(syncResponse(listOf(tx1), "c1", hasMore = true))
                2 -> throw mutationException()
                3 -> createPlaidResponse(syncResponse(listOf(tx1), "c9", hasMore = false))
                else -> error("unexpected call $call")
            }
        }
        val cursors = mutableMapOf("tok" to "start")

        PlaidSyncService(plaid.wrapper, 100, false)
            .processPlaidTransactions(sequenceOf(Pair("tok", listOf("acct"))), cursors)

        // page 1 from "start", page 2 from "c1" (fails), the restart again from "start", not from "c1"
        assertThat(sentCursors).containsExactly("start", "c1", "start")
    }

    @Test
    fun paginationRestartsAreBoundedSoAPermanentlyChangingItemCannotLoopForever() = runBlocking<Unit> {
        var call = 0
        whenever(plaidWrapper.executeRequest<Any>(any(), any(), any())).doSuspendableAnswer {
            call++
            throw mutationException()
        }
        val service = PlaidSyncService(plaidWrapper, 100, false)

        assertThrows<SyncMutationDuringPaginationException> {
            runBlocking {
                service.processPlaidTransactions(sequenceOf(Pair("tok", listOf("acct"))), mutableMapOf())
            }
        }
        assertThat(call).isEqualTo(4) // the first attempt plus three restarts
    }

    // endregion
}
