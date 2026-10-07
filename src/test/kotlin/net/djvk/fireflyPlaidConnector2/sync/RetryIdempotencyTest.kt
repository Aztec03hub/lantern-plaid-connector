package net.djvk.fireflyPlaidConnector2.sync

import org.mockito.kotlin.doReturn
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import io.ktor.client.request.get
import io.ktor.client.engine.mock.respond
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * Review R1 fixes: a retried poll iteration (cursor not committed because something failed part way) must never
 * record the same money twice, and a failed iteration must not kill the poll loop.
 */
internal class RetryIdempotencyTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val accountB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private val converter = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
    )

    /** A Firefly transaction holding [plaidIds]: a transfer holds two (destination first, then source), anything else one. */
    private fun existing(id: String, plaidIds: List<String>, type: TransactionTypeProperty) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, amount = "50.0", sourceId = "1",
            destinationId = if (type == TransactionTypeProperty.transfer) "2" else null,
            plaidLinks = plaidIds.mapIndexed { i, plaidId ->
                PlaidLink(
                    plaidId,
                    if (type != TransactionTypeProperty.transfer) PlaidLinkLeg.single
                    else if (i == 0) PlaidLinkLeg.destination else PlaidLinkLeg.source,
                    "plaidAccount$i",
                )
            },
        ), ObjectLink()
    )

    private fun existing(id: String, plaidId: String, type: TransactionTypeProperty) = existing(id, listOf(plaidId), type)

    // region H2

    /**
     * R1 probe 3, link-table version: the converter no longer filters a create whose id Firefly holds. It sends the
     * create WITH its link, and the link table answers 409 (skipped, never dead-lettered, see SyncHelperLinkTest).
     */
    @Test
    fun aCreateWhoseIdIsAlreadyLinkedToAFireflyTransferIsSentWithItsLinkForTheDatabaseToRefuse() = runBlocking<Unit> {
        val create = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "legY", amount = -50.0)

        val result = converter.convertPollSync(
            accountMap, listOf(create), listOf(), listOf(), listOf(existing("ff1", listOf("legX", "legY"), TransactionTypeProperty.transfer))
        )

        assertThat(result.creates.single().tx.plaidLinks?.map { it.plaidTransactionId }).containsExactly("legY")
        assertThat(result.updates).isEmpty()
    }

    /** Both legs are retried together: they pair up again into a transfer carrying both links, which Firefly refuses (409). */
    @Test
    fun aRetriedPlaidPairThatBecameATransferIsSentWithBothLinksForTheDatabaseToRefuse() = runBlocking<Unit> {
        val withdrawal = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "wd", amount = 50.0)
        val deposit = PlaidFixtures.getPaymentTransaction(accountId = accountB, transactionId = "dep", amount = -50.0)

        val result = converter.convertPollSync(
            accountMap, listOf(withdrawal, deposit), listOf(), listOf(),
            listOf(existing("ff1", listOf("dep", "wd"), TransactionTypeProperty.transfer)),
        )

        assertThat(result.creates.single().tx.plaidLinks?.map { it.plaidTransactionId }).containsExactlyInAnyOrder("wd", "dep")
        assertThat(result.updates).isEmpty()
    }

    @Test
    fun aGenuinelyNewCreateIsStillInserted() = runBlocking<Unit> {
        val create = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "fresh", amount = 12.0)

        val result = converter.convertPollSync(
            accountMap, listOf(create), listOf(), listOf(), listOf(existing("ff1", "other", TransactionTypeProperty.withdrawal))
        )

        assertThat(result.creates.map { it.tx.plaidLinks?.single()?.plaidTransactionId }).containsExactly("fresh")
        assertThat(result.creates.single().tx.plaidLinks?.single()?.leg).isEqualTo(PlaidLinkLeg.single)
        assertThat(result.creates.single().tx.externalId).describedAs("the connector no longer writes external_id").isNull()
    }

    // endregion

    // region H1 / M2

    @Test
    fun convertingToATransferSendsTheTypeAndAnOrdinaryUpdateDoesNot() {
        val mapper = ObjectMapper().findAndRegisterModules()
        val transfer = FireflyTransactionDto(
            "ff1", FireflyFixtures.getTransaction(type = TransactionTypeProperty.transfer, sourceId = "1", destinationId = "2").transactions.first()
        )
        val withdrawal = FireflyTransactionDto(
            "ff2", FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = "1").transactions.first()
        )

        val transferJson = mapper.readTree(mapper.writeValueAsString(transfer.toTransactionUpdate())).get("transactions").get(0)
        val withdrawalJson = mapper.readTree(mapper.writeValueAsString(withdrawal.toTransactionUpdate())).get("transactions").get(0)

        assertThat(transferJson.get("type").asText()).isEqualTo("transfer")
        assertThat(withdrawalJson.has("type")).describedAs("a plain update must never change the type").isFalse()
    }

    @Test
    fun updatesNeverReSendReconciledSoTheUsersReconciliationSurvives() = runBlocking<Unit> {
        val update = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "u1", amount = 20.0)

        val result = converter.convertPollSync(
            accountMap, listOf(), listOf(update), listOf(), listOf(existing("ff1", "u1", TransactionTypeProperty.withdrawal))
        )

        val tx = result.updates.single().tx
        assertThat(tx.reconciled).isNull()
        assertThat(tx.order).isNull()
        val json = ObjectMapper().findAndRegisterModules().writeValueAsString(result.updates.single().toTransactionUpdate())
        assertThat(json).doesNotContain("reconciled")
    }

    // endregion

    // region H3

    private val syncHelper: SyncHelper = mock()
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()
    private val fireflyTransactionService: FireflyTransactionService = mock {
        // 0 creates were dead-lettered (a bare mock returns null for the boxed Int)
        onBlocking { processFireflyTransactionUpdates(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()) } doReturn 0
    }
    private val mockConverter: TransactionConverter = mock()

    private fun orchestrator() = PolledSyncOrchestrator(
        30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, mockConverter,
    )

    private fun stubEmptyFirefly(plaid: PlaidTransactionResult, sequence: Sequence<Pair<String, List<String>>>, cursorMap: MutableMap<String, String>) = runBlocking<Unit> {
        whenever(plaidSyncService.processPlaidTransactions(eq(sequence), eq(cursorMap))).thenReturn(plaid)
        whenever(fireflyTransactionService.fetchExistingFireflyTransactions()).thenReturn(emptyList())
        whenever(fireflyTransactionService.windowStart()).thenReturn(LocalDate.now().minusDays(5))
        whenever(fireflyTransactionService.fetchMissingByPlaidId(any(), any())).thenReturn(emptyList())
        whenever(fireflyTransactionService.fetchFireflyTransactionsBetween(any(), any(), any())).thenReturn(emptyList())
        whenever(mockConverter.convertPollSync(any(), any(), any(), any(), any()))
            .thenReturn(TransactionConverter.ConvertPollSyncResult(listOf(), listOf(), listOf()))
    }

    /**
     * An old create that could be a transfer leg is paired from ONE dated range read ending the day before the window
     * (a link lookup only finds its own record, never the other leg); an old create that cannot pair is not read for.
     */
    @Test
    fun onlyOldCreatesThatMightPairAsATransferAreRangeRead() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        val old = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "old", pendingTransactionId = null, date = LocalDate.now().minusDays(400))
        val oldPlain = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "oldPlain", pendingTransactionId = null, date = LocalDate.now().minusDays(500))
        val recent = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "recent", pendingTransactionId = null, date = LocalDate.now())
        stubEmptyFirefly(PlaidTransactionResult(listOf(old, oldPlain, recent), listOf(), listOf()), sequence, cursorMap)
        whenever(mockConverter.mightPairAsTransfer(old)).thenReturn(true)

        orchestrator().processTransactions(mapOf(accountA to 1), sequence, cursorMap)

        val start = argumentCaptor<LocalDate>()
        val end = argumentCaptor<LocalDate>()
        verify(fireflyTransactionService, times(1)).fetchFireflyTransactionsBetween(start.capture(), end.capture(), any())
        assertThat(start.firstValue).isEqualTo(LocalDate.now().minusDays(401))
        assertThat(end.firstValue).isEqualTo(LocalDate.now().minusDays(6))
        val ids = argumentCaptor<Collection<String>>()
        verify(fireflyTransactionService).fetchMissingByPlaidId(ids.capture(), any())
        assertThat(ids.firstValue).containsExactly("old")
    }

    @Test
    fun noRangeReadIsMadeWhenNoOldCreateCanPair() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        val old = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "old", pendingTransactionId = null, date = LocalDate.now().minusDays(400))
        stubEmptyFirefly(PlaidTransactionResult(listOf(old), listOf(), listOf()), sequence, cursorMap)

        orchestrator().processTransactions(mapOf(accountA to 1), sequence, cursorMap)

        verify(fireflyTransactionService, org.mockito.kotlin.never()).fetchFireflyTransactionsBetween(any(), any(), any())
    }

    @Test
    fun theLinkLookupCoversUpdatedRemovedPendingPostedAndPossibleTransferIds() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        val leg = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "leg", pendingTransactionId = null)
        val posted = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "posted", pendingTransactionId = "pend")
        val plain = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "plain", pendingTransactionId = null)
        val modified = PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "mod", pendingTransactionId = null)
        stubEmptyFirefly(PlaidTransactionResult(listOf(leg, posted, plain), listOf(modified), listOf("gone")), sequence, cursorMap)
        whenever(mockConverter.mightPairAsTransfer(leg)).thenReturn(true)

        orchestrator().processTransactions(mapOf(accountA to 1), sequence, cursorMap)

        val ids = argumentCaptor<Collection<String>>()
        verify(fireflyTransactionService).fetchMissingByPlaidId(ids.capture(), any())
        assertThat(ids.firstValue).containsExactlyInAnyOrder("mod", "gone", "pend", "posted", "leg")
    }

    /** R4 survivor: only the authorized date is before the window, and that is the date Firefly will carry. */
    @Test
    fun aCreateWhoseAuthorizedDateIsBeforeTheWindowIsReadByRangeToo() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        val create = PlaidFixtures.getPaymentTransaction(
            accountId = accountA, transactionId = "auth", pendingTransactionId = null,
            date = LocalDate.now(), authorizedDate = LocalDate.now().minusDays(20),
        )
        stubEmptyFirefly(PlaidTransactionResult(listOf(create), listOf(), listOf()), sequence, cursorMap)
        whenever(mockConverter.mightPairAsTransfer(create)).thenReturn(true)

        orchestrator().processTransactions(mapOf(accountA to 1), sequence, cursorMap)

        verify(fireflyTransactionService).fetchFireflyTransactionsBetween(eq(LocalDate.now().minusDays(21)), any(), any())
    }

    // endregion

    // region H4

    /** The #130 fix was undone because investment sync ran outside the guarded iteration. */
    @Test
    fun investmentSyncRunsInsideTheIterationAndItsFailureIsReportedAndSurvivedByTheLoop() = runBlocking<Unit> {
        val webhook: WebhookService = mock()
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(listOf(), listOf(), listOf()))
        whenever(syncHelper.getInvestmentAccessTokenAccountIdSets())
            .thenReturn(sequenceOf(Pair("brokerTok", listOf("brokerage"))))
        whenever(plaidSyncService.fetchInvestmentTransactions(any(), any(), any(), any()))
            .doSuspendableAnswer { throw java.net.ConnectException("down") }
        val orchestrator = PolledSyncOrchestrator(
            30, syncHelper, cursorManager, plaidSyncService, fireflyTransactionService, mockConverter, webhook,
            investmentConverter = mock(),
        )

        val e = runCatching { orchestrator.runIteration(mapOf(accountA to 1), sequence, cursorMap) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        // L3-R2: the bank sync worked, so its counts are reported alongside the investment failure
        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), any())
        assertThat(reported.firstValue.investmentFailures).isEqualTo(1)

        // and the poll loop's iteration swallows it instead of ending the process
        orchestrator.pollOnce(mapOf(accountA to 1), sequence, cursorMap)
    }

    // endregion

    // region M4 / L2

    @Test
    fun aFailedIterationDoesNotEndThePollLoopAndTheNextOneStillRuns() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf<String, String>()
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).doSuspendableAnswer { throw java.net.ConnectException("down") }
        val orchestrator = orchestrator()

        orchestrator.pollOnce(mapOf(accountA to 1), sequence, cursorMap)
        orchestrator.pollOnce(mapOf(accountA to 1), sequence, cursorMap)

        verify(plaidSyncService, times(2)).processPlaidTransactions(any(), any())
    }

    @Test
    fun theInMemoryCursorDoesNotAdvanceWhenTheFileWriteFails() = runBlocking<Unit> {
        val sequence = sequenceOf(Pair("tok", listOf(accountA)))
        val cursorMap = mutableMapOf("tok" to "old")
        whenever(plaidSyncService.processPlaidTransactions(eq(sequence), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as MutableMap<String, String>)["tok"] = "new"
            PlaidTransactionResult(listOf(), listOf(), listOf())
        }
        whenever(cursorManager.writeCursorMap(any())).doSuspendableAnswer { throw java.io.IOException("disk full") }

        val e = runCatching { orchestrator().processTransactions(mapOf(accountA to 1), sequence, cursorMap) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
        assertThat(cursorMap["tok"]).isEqualTo("old")
    }

    // endregion

    // region delete idempotency

    private fun clientError(status: io.ktor.http.HttpStatusCode): io.ktor.client.plugins.ClientRequestException = runBlocking {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("{}", status) }) {
            expectSuccess = true
        }
        try {
            client.get("https://firefly.test/x"); throw AssertionError("expected an error")
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            e
        }
    }

    private fun helperDeleting(error: Exception): Pair<SyncHelper, net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi> {
        val txApi: net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi = mock()
        runBlocking { whenever(txApi.deleteTransaction(any())).doSuspendableAnswer { throw error } }
        return Pair(SyncHelper(net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs(emptyList()), "t", mock(), txApi, mock(), mock()), txApi)
    }

    @Test
    fun deletingATransactionThatIsAlreadyGoneIsNotAnError() = runBlocking<Unit> {
        val (helper, txApi) = helperDeleting(clientError(io.ktor.http.HttpStatusCode.NotFound))

        helper.deleteBatchInFirefly(listOf("1", "2"))

        verify(txApi).deleteTransaction(eq("1"))
        verify(txApi).deleteTransaction(eq("2"))
    }

    @Test
    fun otherDeleteErrorsStillPropagate() = runBlocking<Unit> {
        val (helper, _) = helperDeleting(clientError(io.ktor.http.HttpStatusCode.Forbidden))

        val e = runCatching { helper.deleteBatchInFirefly(listOf("1")) }.exceptionOrNull()

        assertThat(e).isInstanceOf(io.ktor.client.plugins.ClientRequestException::class.java)
    }

    // endregion
}
