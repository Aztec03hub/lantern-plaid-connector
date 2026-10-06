package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import net.djvk.fireflyPlaidConnector2.api.plaid.models.RemovedTransaction
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidMock
import net.djvk.fireflyPlaidConnector2.lib.createPlaidResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * Review R2 on the sync side: a failing bank is visible (H3-R2), a half-fetched Item is discarded (M1-R2), one rejected
 * Firefly write no longer stalls every bank (M3-R2), banks and investments are independent (L3-R2), and the survivors
 * of the R2 mutation run.
 */
internal class R2SyncTest {
    @TempDir
    lateinit var dir: Path

    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun statusError(status: HttpStatusCode, body: String = "{}"): ClientRequestException = runBlocking {
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
        }
        try {
            client.get("https://x.test/y"); throw AssertionError("expected an error")
        } catch (e: ClientRequestException) {
            e
        }
    }

    private fun plaidError(code: String) =
        statusError(HttpStatusCode.BadRequest, """{"error_type":"ITEM_ERROR","error_code":"$code"}""")

    private fun page(txs: List<net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction>, cursor: String, hasMore: Boolean): TransactionsSyncResponse =
        mock {
            on { added } doReturn txs
            on { modified } doReturn listOf()
            on { removed } doReturn listOf<RemovedTransaction>()
            on { nextCursor } doReturn cursor
            on { this.hasMore } doReturn hasMore
        }

    private fun config(token: String, name: String?) = AccountConfig(1, token, "acct", institutionName = name)

    // region H3-R2 + M1-R2: PlaidSyncService

    @Test
    fun aFailingItemIsReportedWithItsInstitutionAndPlaidErrorCodeAndItsCursorDoesNotMove() = runBlocking<Unit> {
        val plaid = PlaidMock()
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer { throw plaidError("ITEM_LOGIN_REQUIRED") }
        val service = PlaidSyncService(plaid.wrapper, 100, true, AccountConfigs(listOf(config("access-token-chase1", "Chase"))))
        val cursors = mutableMapOf("access-token-chase1" to "start")

        val result = service.processPlaidTransactions(sequenceOf(Pair("access-token-chase1", listOf("acct"))), cursors)

        val failed = result.failedItems.single()
        assertThat(failed.institution).isEqualTo("Chase")
        assertThat(failed.errorCode).isEqualTo("ITEM_LOGIN_REQUIRED")
        assertThat(failed.accessTokenRedacted).isEqualTo("access-****ase1")
        assertThat(cursors["access-token-chase1"]).isEqualTo("start")
    }

    @Test
    fun aNetworkFailureIsReportedByExceptionClassNotMessage() = runBlocking<Unit> {
        val plaid = PlaidMock()
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer { throw java.net.ConnectException("secret-host.internal refused") }
        val service = PlaidSyncService(plaid.wrapper, 100, true)

        val result = service.processPlaidTransactions(sequenceOf(Pair("tok-12345678", listOf("acct"))), mutableMapOf())

        assertThat(result.failedItems.single().errorCode).isEqualTo("ConnectException")
        assertThat(result.failedItems.single().institution).isEqualTo("unnamed Item")
        assertThat(result.failedItems.toString()).doesNotContain("secret-host")
    }

    /** M1-R2: page 1 arrived, page 2 failed. Nothing from the half-fetched Item is kept and its cursor is back at the start. */
    @Test
    fun anItemThatFailsOnALaterPageIsDiscardedAndItsCursorGoesBackToTheStart() = runBlocking<Unit> {
        val plaid = PlaidMock()
        val tx1 = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "tx1")
        var call = 0
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer {
            when (++call) {
                1 -> createPlaidResponse(page(listOf(tx1), "c1", hasMore = true))
                else -> throw plaidError("INSTITUTION_DOWN")
            }
        }
        val service = PlaidSyncService(plaid.wrapper, 100, true)
        val cursors = mutableMapOf("tok-12345678" to "start")

        val result = service.processPlaidTransactions(sequenceOf(Pair("tok-12345678", listOf("acct"))), cursors)

        assertThat(result.created).isEmpty()
        assertThat(cursors["tok-12345678"]).isEqualTo("start")
        assertThat(result.failedItems.single().errorCode).isEqualTo("INSTITUTION_DOWN")
    }

    @Test
    fun anItemWithNoCursorThatFailsOnALaterPageEndsWithNoCursor() = runBlocking<Unit> {
        val plaid = PlaidMock()
        var call = 0
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer {
            if (++call == 1) createPlaidResponse(page(listOf(), "c1", hasMore = true)) else throw plaidError("INSTITUTION_DOWN")
        }
        val cursors = mutableMapOf<String, String>()

        PlaidSyncService(plaid.wrapper, 100, true)
            .processPlaidTransactions(sequenceOf(Pair("tok-12345678", listOf("acct"))), cursors)

        assertThat(cursors).doesNotContainKey("tok-12345678")
    }

    @Test
    fun aHealthyItemIsStillFetchedWhenAnotherOneFails() = runBlocking<Unit> {
        val plaid = PlaidMock()
        val tx = PlaidFixtures.getPaymentTransaction(accountId = "acct", transactionId = "good")
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer {
            val request = it.getArgument<net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest>(0)
            if (request.accessToken == "tok-bad-1234") throw plaidError("ITEM_LOGIN_REQUIRED")
            createPlaidResponse(page(listOf(tx), "next", hasMore = false))
        }
        val cursors = mutableMapOf<String, String>()

        val result = PlaidSyncService(plaid.wrapper, 100, true).processPlaidTransactions(
            sequenceOf(Pair("tok-bad-1234", listOf("acct")), Pair("tok-good-5678", listOf("acct"))), cursors
        )

        assertThat(result.created.map { it.transactionId }).containsExactly("good")
        assertThat(cursors).containsOnlyKeys("tok-good-5678")
        assertThat(result.failedItems).hasSize(1)
    }

    // endregion

    // region H3-R2: the status file, the callback and the orchestrator

    @Test
    fun theStatusFileKeepsTheLastSuccessOfAFailingItemAndHoldsNoSecret() = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val t1 = Instant.parse("2026-10-01T00:00:00Z")
        val t2 = Instant.parse("2026-10-02T00:00:00Z")
        val ref = store.ref("access-secret-token-abcd", "Chase")

        store.record(t1, listOf(ref), listOf())
        val lastSuccess = store.record(t2, listOf(), listOf(ref to "ITEM_LOGIN_REQUIRED"))

        assertThat(lastSuccess[ref.id]).isEqualTo(t1.toString())
        val status = store.read().getValue(ref.id)
        assertThat(status.lastSuccessfulSync).isEqualTo(t1.toString())
        assertThat(status.lastFailure).isEqualTo(t2.toString())
        assertThat(status.lastErrorCode).isEqualTo("ITEM_LOGIN_REQUIRED")
        assertThat(status.institution).isEqualTo("Chase")
        assertThat(java.nio.file.Files.readString(store.path)).doesNotContain("secret-token")

        // it syncs again: the error is cleared
        store.record(Instant.parse("2026-10-03T00:00:00Z"), listOf(ref), listOf())
        assertThat(store.read().getValue(ref.id).lastErrorCode).isNull()
    }

    @Test
    fun anItemThatNeverSucceededHasNoLastSuccess() = runBlocking<Unit> {
        val store = ItemStatusStore(dir.toString())
        val ref = store.ref("tok-12345678", "Bank")

        val lastSuccess = store.record(Instant.now(), listOf(), listOf(ref to "X"))

        assertThat(lastSuccess).containsEntry(ref.id, null)
    }

    private val syncHelper: SyncHelper = mock()
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock()
    private val converter: TransactionConverter = mock()

    @Test
    fun aPollWithAFailedItemReportsItAndStampsTheOthersInTheStatusFile() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok-bad-1234", listOf(accountA)), Pair("tok-good-5678", listOf(accountA)))
        val cursorMap = mutableMapOf("tok-good-5678" to "old")
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(
            PlaidTransactionResult(listOf(), listOf(), listOf(), listOf(ItemFailure("Chase", "access-****1234", "ITEM_LOGIN_REQUIRED")))
        )
        whenever(plaidSyncService.describeInstitution(any())).thenReturn("Other Bank")
        val store = ItemStatusStore(dir.toString())
        val webhook: WebhookService = mock()
        val orchestrator = PolledSyncOrchestrator(
            30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter, webhook,
            itemStatusStore = store,
        )

        orchestrator.runIteration(mapOf(accountA to 1), sequence, cursorMap)

        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), org.mockito.kotlin.isNull())
        val failed = reported.firstValue.failedItems.single()
        assertThat(failed.institution).isEqualTo("Chase")
        assertThat(failed.errorCode).isEqualTo("ITEM_LOGIN_REQUIRED")
        assertThat(failed.lastSuccessfulSync).describedAs("never synced before").isNull()
        assertThat(reported.firstValue.partial).isTrue()
        val statuses = store.read().values
        assertThat(statuses.map { it.institution }).containsExactlyInAnyOrder("Chase", "Other Bank")
        assertThat(statuses.single { it.institution == "Other Bank" }.lastSuccessfulSync).isNotNull()
        assertThat(statuses.single { it.institution == "Chase" }.lastSuccessfulSync).isNull()
    }

    @Test
    fun theCallbackStatusIsPartialWhenABankFailedAndSuccessOtherwise() = runBlocking<Unit> {
        val captured = mutableListOf<String>()
        val engine = MockEngine { request ->
            captured.add(String(request.body.toByteArray()))
            respond("OK", HttpStatusCode.OK)
        }
        val service = WebhookService("https://monitor.example.com/hook", null, engine)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val failedItem = FailedItemReport("Chase", "access-****1234", "ITEM_LOGIN_REQUIRED", "2026-09-30T00:00:00Z")

        service.post(start, start, PollResult(failedItems = listOf(failedItem)))
        service.post(start, start, PollResult())
        service.post(start, start, PollResult(deadLetters = 2))

        val mapper = ObjectMapper()
        assertThat(captured.map { mapper.readTree(it)["status"].asText() }).containsExactly("partial", "success", "partial")
        val item = mapper.readTree(captured[0])["results"]["failedItems"][0]
        assertThat(item["institution"].asText()).isEqualTo("Chase")
        assertThat(item["errorCode"].asText()).isEqualTo("ITEM_LOGIN_REQUIRED")
        assertThat(item["lastSuccessfulSync"].asText()).isEqualTo("2026-09-30T00:00:00Z")
        assertThat(captured[0]).doesNotContain("access-token")
    }

    // endregion

    // region L3-R2

    @Test
    fun investmentsStillSyncWhenTheBankSyncFailsAndBothAreReported() = runBlocking<Unit> {
        val webhook: WebhookService = mock()
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer { throw java.io.IOException("bank down") }
        whenever(syncHelper.getInvestmentAccessTokenAccountIdSets()).thenReturn(sequenceOf(Pair("brokerTok", listOf("brokerage"))))
        whenever(plaidSyncService.fetchInvestmentTransactions(any(), any(), any(), any())).thenReturn(listOf())
        val orchestrator = PolledSyncOrchestrator(
            30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter, webhook,
            investmentConverter = mock(),
        )

        val e = runCatching { orchestrator.runIteration(mapOf(accountA to 1), sequenceOf(), mutableMapOf()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
        verify(plaidSyncService).fetchInvestmentTransactions(any(), any(), any(), any())
        verify(webhook, times(1)).post(any(), any(), org.mockito.kotlin.isNull(), any<java.io.IOException>())
    }

    // endregion

    // region M3-R2: dead letters

    private val txApi: TransactionsApi = mock()
    private val helper: SyncHelper = mock()
    private val searchApi: SearchApi = mock()

    /** Built before any stubbing: creating a mock inside thenReturn() breaks Mockito. */
    private fun searchResult(vararg txs: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead) =
        net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse(
            mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray> { on { data } doReturn txs.toList() }
        )

    private fun service(store: DeadLetterStore?) = FireflyTransactionService(txApi, helper, 30, "UTC", searchApi, store)

    private fun create(externalId: String) = FireflyTransactionDto(
        null, FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = "1", externalId = externalId)
            .transactions.first()
    )

    @Test
    fun oneRejectedWriteIsKeptAndTheOthersStillGoThrough() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        val bad = create("plaid-bad")
        val good = create("plaid-good")
        whenever(helper.optimisticInsertBatchIntoFirefly(eq(listOf(bad)))).doSuspendableAnswer {
            throw statusError(HttpStatusCode.UnprocessableEntity, """{"message":"The amount is invalid"}""")
        }

        service(store).processFireflyTransactionUpdates(listOf(bad, good), listOf(), listOf())

        verify(helper).optimisticInsertBatchIntoFirefly(eq(listOf(good)))
        val letters = store.read()
        assertThat(letters.single().key).isEqualTo("plaid-bad")
        assertThat(letters.single().operation).isEqualTo("create")
        assertThat(letters.single().message).contains("422").contains("The amount is invalid")
        assertThat(letters.single().split?.externalId).isEqualTo("plaid-bad")
    }

    @Test
    fun aRejectedUpdateAndDeleteAreKeptToo() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        val update = FireflyTransactionDto("ff1", create("plaid-u").tx)
        whenever(helper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }
        whenever(helper.deleteBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.BadRequest) }

        service(store).processFireflyTransactionUpdates(listOf(), listOf(update), listOf("77"))

        assertThat(store.read().map { it.operation to it.key }).containsExactlyInAnyOrder("update" to "ff1", "delete" to "77")
    }

    @Test
    fun serverErrorsAuthenticationAndRateLimitsStillFailTheIteration() = runBlocking<Unit> {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.RequestTimeout)) {
            val store = DeadLetterStore(dir.resolve(status.value.toString()).toString())
            whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(status) }

            val e = runCatching { service(store).processFireflyTransactionUpdates(listOf(create("plaid-x")), listOf(), listOf()) }.exceptionOrNull()

            assertThat(e).describedAs("$status").isInstanceOf(ClientRequestException::class.java)
            assertThat(store.read()).describedAs("$status").isEmpty()
        }
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw java.io.IOException("down") }
        val e = runCatching { service(DeadLetterStore(dir.toString())).processFireflyTransactionUpdates(listOf(create("plaid-x")), listOf(), listOf()) }
            .exceptionOrNull()
        assertThat(e).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun withoutADeadLetterStoreARejectedWriteStillFailsTheIteration() = runBlocking<Unit> {
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }

        val e = runCatching { service(null).processFireflyTransactionUpdates(listOf(create("plaid-x")), listOf(), listOf()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(ClientRequestException::class.java)
    }

    @Test
    fun aKeptWriteIsRetriedAndRemovedOnceFireflyAcceptsIt() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        val bad = create("plaid-bad")
        var reject = true
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer {
            if (reject) throw statusError(HttpStatusCode.UnprocessableEntity) else Unit
        }
        val nothing = searchResult()
        whenever(searchApi.searchTransactions(any(), any())).thenReturn(nothing)
        val service = service(store)
        service.processFireflyTransactionUpdates(listOf(bad), listOf(), listOf())
        assertThat(store.read()).hasSize(1)

        service.retryDeadLetters() // still rejected: stays
        assertThat(store.read()).hasSize(1)

        reject = false
        service.retryDeadLetters()
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aKeptCreateThatFireflyAlreadyHasIsDroppedNotInsertedAgain() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("create", "plaid-there", null, create("plaid-there").tx, false, "x"))
        val there = net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead(
            "transactions", "ff9", FireflyFixtures.getTransaction(externalId = "plaid-there"), net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink()
        )
        val found = searchResult(there)
        whenever(searchApi.searchTransactions(any(), any())).thenReturn(found)

        service(store).retryDeadLetters()

        verify(helper, never()).optimisticInsertBatchIntoFirefly(any())
        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aNewerSuccessfulWriteForTheSameTransactionClearsItsOldDeadLetter() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        val update = FireflyTransactionDto("ff1", create("plaid-u").tx)
        store.add(DeadLetter("update", "ff1", "ff1", update.tx, false, "old"))

        service(store).processFireflyTransactionUpdates(listOf(), listOf(update), listOf())

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun theDeadLetterFileIsReadableOnlyByItsOwner() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())

        store.add(DeadLetter("delete", "5", "5"))

        val perms = java.nio.file.Files.getPosixFilePermissions(store.path)
        assertThat(perms).containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        )
    }

    @Test
    fun theOrchestratorReportsTheDeadLetterCountAndRetriesThemFirst() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("delete", "5", "5"))
        val webhook: WebhookService = mock()
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(listOf(), listOf(), listOf()))
        val orchestrator = PolledSyncOrchestrator(
            30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, converter, webhook,
            deadLetterStore = store,
        )

        orchestrator.runIteration(mapOf(), sequenceOf(), mutableMapOf())

        verify(fireflyTransactionService).retryDeadLetters()
        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), org.mockito.kotlin.isNull())
        assertThat(reported.firstValue.deadLetters).isEqualTo(1)
        assertThat(reported.firstValue.partial).isTrue()
    }

    // endregion

    // region R11 survivor, L4-R2, N1-R2

    @Test
    fun theWindowStartIsTheConfiguredNumberOfDaysBeforeToday() {
        val service = FireflyTransactionService(txApi, helper, 30, "America/Chicago", searchApi)

        assertThat(service.windowStart()).isEqualTo(LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusDays(30))
    }

    @Test
    fun theRangedReadSendsItsDatesAndTheCallerCapOnPages() = runBlocking<Unit> {
        val pagination = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination> {
            on { currentPage } doReturn 1
            on { totalPages } doReturn 3
        }
        val meta = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta> { on { this.pagination } doReturn pagination }
        val array = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray> {
            on { data } doReturn listOf()
            on { this.meta } doReturn meta
        }
        val response = net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse(array)
        whenever(txApi.listTransaction(any(), any(), any(), any())).thenReturn(response)
        val start = LocalDate.of(2025, 1, 1)
        val end = LocalDate.of(2025, 1, 31)

        // the mock always says "page 1 of 3", so a cap of 2 pages is exceeded: an error, not a silently truncated read
        val e = runCatching { service(null).fetchFireflyTransactionsBetween(start, end, 2) }.exceptionOrNull()

        assertThat(e).hasMessageContaining("max page count 2")
        verify(txApi, times(2)).listTransaction(any(), eq(start), eq(end), any())
    }

    // endregion
}
