package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction
import java.nio.file.Files
import java.nio.file.Path

/**
 * Review R3 on the sync side: Plaid events reach creates waiting in the dead letter file (M1-R3), dead letters that
 * can never succeed stop being retried and an unreadable file is loud (L4-R3), startup fails without a pendingTag in
 * polled mode (L5-R3), and the survivors of the R3 mutation run (O5, D4).
 */
internal class R3SyncTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir
    lateinit var dir: Path

    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // Firefly account 1
    private val accountB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // Firefly account 2

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

    private fun plaid(
        account: String, id: String, amount: Double, pending: Boolean = false, pendingId: String? = null,
    ): PlaidTransaction = PlaidFixtures.getPaymentTransaction(
        accountId = account, transactionId = id, pendingTransactionId = pendingId, amount = amount, pending = pending,
        name = "Plaid $id",
    )

    private val txApi: TransactionsApi = mock()
    private val helper: SyncHelper = mock { onBlocking { optimisticInsertBatchIntoFirefly(any()) } doReturn 1 }
    private val plaidLinksApi: PlaidLinksApi = mock()
    private val syncHelper: SyncHelper = mock { onBlocking { fetchAccountKinds() } doReturn emptyMap() }
    private val cursorManager: CursorManager = mock()
    private val plaidSyncService: PlaidSyncService = mock()

    private fun converter(pendingTag: String = "pending") = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
        pendingTag = pendingTag,
    )

    private fun service(store: DeadLetterStore?) = FireflyTransactionService(txApi, helper, 30, "UTC", plaidLinksApi, store)

    private fun emptyLookup() = createFireflyResponse(PlaidLinkLookupResponse(listOf()))

    private fun emptyFirefly() = runBlocking<Unit> {
        val meta = mock<Meta> { on { pagination } doReturn null }
        val array = mock<TransactionArray> {
            on { data } doReturn listOf()
            on { this.meta } doReturn meta
        }
        val response = createFireflyResponse(array)
        whenever(txApi.listTransaction(any(), any(), any(), any())).thenReturn(response)
        emptyLookup().let { r -> whenever(plaidLinksApi.lookupPlaidLinks(any())).thenReturn(r) }
    }

    private fun rejectCreates() = runBlocking<Unit> {
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }
    }

    private fun create(plaidId: String, amount: String = "50.0") = FireflyTransactionDto(
        null,
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.withdrawal, sourceId = "1", amount = amount,
            plaidLinks = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, "plaidAccountA")),
        ).transactions.first()
    )

    private fun orchestrator(
        service: FireflyTransactionService,
        store: DeadLetterStore?,
        webhook: WebhookService? = null,
        converter: TransactionConverter = converter(),
    ) = PolledSyncOrchestrator(
        30, syncHelper, cursorManager, plaidSyncService, service, converter, webhook, deadLetterStore = store,
    )

    private fun plaidResult(
        created: List<PlaidTransaction> = listOf(),
        updated: List<PlaidTransaction> = listOf(),
        deleted: List<String> = listOf(),
        failed: List<ItemFailure> = listOf(),
    ) = PlaidTransactionResult(created, updated, deleted, failed)

    // region M1-R3

    /** The reviewer's probe p4 sequence: a pending create is dead-lettered, then Plaid removes it and adds the posted one. */
    @Test
    fun aDeadLetteredPendingCreateAndItsPostedVersionAreRecordedOnceAfterRecovery() = runBlocking<Unit> {
        emptyFirefly()
        val store = DeadLetterStore(dir.toString())
        var reject = true
        val inserted = mutableListOf<String?>()
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer {
            if (reject) throw statusError(HttpStatusCode.UnprocessableEntity, """{"message":"no such account"}""")
            @Suppress("UNCHECKED_CAST")
            (it.arguments[0] as List<FireflyTransactionDto>).onEach { dto -> inserted.add(dto.tx.plaidLinks?.first()?.plaidTransactionId) }.size
        }
        val orchestrator = orchestrator(service(store), store)
        val cursors = mutableMapOf<String, String>()
        val sequence = sequenceOf(Pair("tok-12345678", listOf(accountA)))

        // poll 1: the pending charge is rejected and kept
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(created = listOf(plaid(accountA, "pend", 50.0, pending = true))))
        orchestrator.processTransactions(accountMap, sequence, cursors)
        assertThat(store.read().map { it.key }).containsExactly("pend")

        // poll 2: Plaid removes the pending one and adds the posted one, which is rejected too
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(created = listOf(plaid(accountA, "posted", 50.0, pendingId = "pend")), deleted = listOf("pend")))
        orchestrator.processTransactions(accountMap, sequence, cursors)
        assertThat(store.read().map { it.key }).describedAs("only the posted create is left").containsExactly("posted")

        // the cause is fixed: the next poll's retry inserts the posted one and only that
        reject = false
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(plaidResult())
        orchestrator.processTransactions(accountMap, sequence, cursors)

        assertThat(inserted).containsExactly("posted")
        assertThat(store.read()).isEmpty()
    }

    /** The posted version alone (its pending id may be removed in a later sync) already replaces the pending letter. */
    @Test
    fun aPostedVersionDropsTheLetterOfItsPendingTransactionEvenWithoutARemovalEvent() = runBlocking<Unit> {
        emptyFirefly()
        rejectCreates()
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("create", "pend", null, create("pend").tx, false, "x"))
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(created = listOf(plaid(accountA, "posted", 50.0, pendingId = "pend"))))

        orchestrator(service(store), store).processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        assertThat(store.read().map { it.key }).containsExactly("posted")
    }

    @Test
    fun aPlaidUpdateOfADeadLetteredCreateIsRetriedWithTheNewAmount() = runBlocking<Unit> {
        emptyFirefly()
        rejectCreates()
        val store = DeadLetterStore(dir.toString())
        val letter = DeadLetter("create", "tx1", null, create("tx1", "50.0").tx, false, "x", attempts = 4, abandoned = true)
        store.add(letter)
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(updated = listOf(plaid(accountA, "tx1", 75.0))))
        val orchestrator = orchestrator(service(store), store)

        orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        val revised = store.read().single()
        assertThat(revised.split?.amount).isEqualTo("75.0")
        assertThat(revised.split?.plaidLinks?.single()?.plaidTransactionId).isEqualTo("tx1")
        assertThat(revised.attempts).describedAs("a revised letter starts over").isEqualTo(0)
        assertThat(revised.abandoned).isFalse()
    }

    @Test
    fun aPlaidRemovalOfADeadLetteredCreateLeavesNoLetter() = runBlocking<Unit> {
        emptyFirefly()
        rejectCreates()
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("create", "tx1", null, create("tx1").tx, false, "x"))
        store.add(DeadLetter("create", "other", null, create("other").tx, false, "x"))
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(plaidResult(deleted = listOf("tx1")))
        val orchestrator = orchestrator(service(store), store)

        orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        assertThat(store.read().map { it.key }).containsExactly("other")
    }

    @Test
    fun theRemovalOfOneLegOfADeadLetteredTransferKeepsTheOtherLegsMoney() = runBlocking<Unit> {
        emptyFirefly()
        rejectCreates()
        val store = DeadLetterStore(dir.toString())
        val transfer = create("dep").tx.copy(
            type = TransactionTypeProperty.transfer, sourceId = "1", destinationId = "2",
            plaidLinks = listOf(
                PlaidLink("dep", PlaidLinkLeg.destination, "plaidAccountB"),
                PlaidLink("wd", PlaidLinkLeg.source, "plaidAccountA"),
            ),
        )
        store.add(DeadLetter("create", "dep", null, transfer, false, "x"))
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(plaidResult(deleted = listOf("dep")))
        val orchestrator = orchestrator(service(store), store)

        orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        // the destination leg is gone; the source leg's outflow survives as a withdrawal from account 1
        val kept = store.read().single()
        assertThat(kept.key).isEqualTo("wd")
        assertThat(kept.split?.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(kept.split?.sourceId).isEqualTo("1")
        assertThat(kept.split?.plaidLinks).containsExactly(PlaidLink("wd", PlaidLinkLeg.single, "plaidAccountA"))
    }

    // endregion

    // region L4-R3

    @Test
    fun aRetriedUpdateOfAFireflyTransactionThatNoLongerExistsIsDroppedNotRetriedForever() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("update", "ff1", "ff1", create("u").tx, false, "old"))
        whenever(helper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service(store).retryDeadLetters()

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aFreshUpdateOfAFireflyTransactionThatNoLongerExistsIsNotKept() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        whenever(helper.updateBatchInFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.NotFound) }

        service(store).processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("ff1", create("u").tx)), listOf())

        assertThat(store.read()).isEmpty()
    }

    @Test
    fun aSuccessfulDeleteAlsoClearsTheUpdateLettersOfTheSameFireflyTransaction() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("update", "ff1", "ff1", create("u").tx, false, "old"))
        store.add(DeadLetter("update", "ff2", "ff2", create("v").tx, false, "old"))

        service(store).processFireflyTransactionUpdates(listOf(), listOf(), listOf("ff1"))

        assertThat(store.read().map { it.key }).containsExactly("ff2")
    }

    @Test
    fun aLetterFireflyKeepsRejectingIsAbandonedAfterTheCapAndReported() = runBlocking<Unit> {
        emptyFirefly()
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("update", "ff1", "ff1", create("u").tx, false, "old"))
        var calls = 0
        whenever(helper.updateBatchInFirefly(any())).doSuspendableAnswer {
            calls++
            throw statusError(HttpStatusCode.UnprocessableEntity)
        }
        val service = service(store)

        repeat(DeadLetterStore.maxAttempts) { service.retryDeadLetters() }
        val letter = store.read().single()
        assertThat(letter.abandoned).isTrue()
        assertThat(letter.attempts).isEqualTo(DeadLetterStore.maxAttempts)
        assertThat(calls).isEqualTo(DeadLetterStore.maxAttempts)

        // no longer retried
        service.retryDeadLetters()
        assertThat(calls).isEqualTo(DeadLetterStore.maxAttempts)

        // but still reported, and the poll is partial
        val webhook: WebhookService = mock()
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(plaidResult())
        orchestrator(service, store, webhook).runIteration(mapOf(), sequenceOf(), mutableMapOf())
        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), org.mockito.kotlin.isNull())
        assertThat(reported.firstValue.deadLetters).isEqualTo(1)
        assertThat(reported.firstValue.deadLettersAbandoned).isEqualTo(1)
        assertThat(reported.firstValue.partial).isTrue()
    }

    @Test
    fun anUnreadableDeadLetterFileIsMovedAsideAndReportedAndThePollStillRuns() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        Files.writeString(store.path, "[{\"operation\": 5, this is not json")
        val webhook: WebhookService = mock()
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(plaidResult())
        val orchestrator = orchestrator(service(store), store, webhook)

        orchestrator.runIteration(mapOf(), sequenceOf(), mutableMapOf())

        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), org.mockito.kotlin.isNull())
        assertThat(reported.firstValue.deadLetterFilesUnreadable).isEqualTo(1)
        assertThat(reported.firstValue.partial).isTrue()
        assertThat(Files.exists(store.path)).isFalse()
        assertThat(Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
            .anyMatch { it.startsWith("plaid_dead_letters.unreadable-") }
    }

    @Test
    fun aFileWithUnknownPropertiesIsStillRead() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        Files.writeString(store.path, "[{\"operation\":\"delete\",\"key\":\"5\",\"fireflyId\":\"5\",\"newerField\":true}]")

        assertThat(store.read().single().key).isEqualTo("5")
    }

    // endregion

    // region M1-R3 (transfer letters), N1-R3, N2-R3

    @Test
    fun aPlaidUpdateOfADeadLetteredTransferRefreshesItsAmountAndKeepsItsAccounts() = runBlocking<Unit> {
        emptyFirefly()
        rejectCreates()
        val store = DeadLetterStore(dir.toString())
        val transfer = create("dep").tx.copy(
            type = TransactionTypeProperty.transfer, sourceId = "1", destinationId = "2",
            plaidLinks = listOf(
                PlaidLink("dep", PlaidLinkLeg.destination, "plaidAccountB"),
                PlaidLink("wd", PlaidLinkLeg.source, "plaidAccountA"),
            ),
        )
        store.add(DeadLetter("create", "dep", null, transfer, false, "x"))
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(updated = listOf(plaid(accountB, "dep", -75.0))))

        orchestrator(service(store), store).processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        val revised = store.read().single().split!!
        assertThat(revised.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(revised.amount).isEqualTo("75.0")
        assertThat(revised.sourceId).isEqualTo("1")
        assertThat(revised.destinationId).isEqualTo("2")
        assertThat(revised.plaidLinks?.map { it.plaidTransactionId }).containsExactly("dep", "wd")
    }

    @Test
    fun aRangedReadOfMoreThanTheTransactionCeilingFailsWhateverThePageSize() = runBlocking<Unit> {
        val pagination = mock<net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination> {
            on { currentPage } doReturn 1
            on { totalPages } doReturn 1
            on { total } doReturn 300_000
        }
        val meta = mock<Meta> { on { this.pagination } doReturn pagination }
        val array = mock<TransactionArray> {
            on { data } doReturn listOf()
            on { this.meta } doReturn meta
        }
        val response = createFireflyResponse(array)
        whenever(txApi.listTransaction(any(), any(), any(), any())).thenReturn(response)

        val e = runCatching {
            service(null).fetchFireflyTransactionsBetween(java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2025, 1, 31), Int.MAX_VALUE)
        }.exceptionOrNull()

        assertThat(e).hasMessageContaining("exceeds the failsafe")
    }

    private fun mutationError() = statusError(
        HttpStatusCode.BadRequest, """{"error_type":"TRANSACTIONS_ERROR","error_code":"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"}"""
    )

    @Test
    fun anItemWhoseMutationRestartsAreExhaustedFailsAloneWhenItemsMayFail() = runBlocking<Unit> {
        val plaid = net.djvk.fireflyPlaidConnector2.lib.PlaidMock()
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer { throw mutationError() }
        val cursors = mutableMapOf("tok-12345678" to "start")

        val result = PlaidSyncService(plaid.wrapper, 100, true)
            .processPlaidTransactions(sequenceOf(Pair("tok-12345678", listOf("acct"))), cursors)

        assertThat(result.failedItems.single().errorCode).isEqualTo(MUTATION_DURING_PAGINATION)
        assertThat(cursors["tok-12345678"]).isEqualTo("start")
    }

    @Test
    fun exhaustedMutationRestartsStillFailTheIterationWhenItemsMayNotFail() = runBlocking<Unit> {
        val plaid = net.djvk.fireflyPlaidConnector2.lib.PlaidMock()
        whenever(plaid.api.transactionsSync(any())).doSuspendableAnswer { throw mutationError() }

        val e = runCatching {
            PlaidSyncService(plaid.wrapper, 100, false)
                .processPlaidTransactions(sequenceOf(Pair("tok-12345678", listOf("acct"))), mutableMapOf())
        }.exceptionOrNull()

        assertThat(e).isInstanceOf(SyncMutationDuringPaginationException::class.java)
    }

    // endregion

    // region L5-R3

    @Test
    fun polledModeRefusesToStartWithoutAPendingTag() {
        val e = runCatching {
            PolledSyncOrchestrator(30, syncHelper, cursorManager, plaidSyncService, mock(), converter(), pendingTag = "")
        }.exceptionOrNull()

        assertThat(e).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("pendingTag must be set")
    }

    @Test
    fun syncModeDefaultsToPolledWhenUnset() {
        val polled = PolledSyncOrchestrator::class.java
            .getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty::class.java)
        val batch = BatchSyncRunner::class.java
            .getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty::class.java)
        assertThat(polled.matchIfMissing).describedAs("polled starts when syncMode is missing").isTrue()
        assertThat(batch.matchIfMissing).describedAs("batch never starts by default").isFalse()

        val yml = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(
            javaClass.getResourceAsStream("/application.yml")!!.reader().readText()
        )
        @Suppress("UNCHECKED_CAST")
        assertThat((yml["fireflyPlaidConnector2"] as Map<String, Any>)["syncMode"]).isEqualTo("polled")
    }

    @Test
    fun anUnreadableFileOrATransferNeedingReviewMakesThePollPartial() {
        assertThat(PollResult(deadLetterFilesUnreadable = 1).partial).isTrue()
        assertThat(PollResult(transfersNeedingReview = 1).partial).isTrue()
        assertThat(PollResult(deadLettersAbandoned = 1, deadLetters = 1).partial).isTrue()
        assertThat(PollResult().partial).isFalse()
    }

    // endregion

    // region L3-R3 survivors

    /** O5: a bank fails during a poll where the others had transactions. The main path must report it. */
    @Test
    fun aFailedItemIsReportedOnAPollWhereOtherBanksHadTransactions() = runBlocking<Unit> {
        emptyFirefly()
        val webhook: WebhookService = mock()
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(
            plaidResult(
                created = listOf(plaid(accountA, "tx1", 50.0)),
                failed = listOf(ItemFailure("Chase", "access-****1234", "ITEM_LOGIN_REQUIRED")),
            )
        )
        val firefly: FireflyTransactionService = mock {
            onBlocking { processFireflyTransactionUpdates(any(), any(), any()) } doReturn 0
            onBlocking { windowStart() } doReturn java.time.LocalDate.now().minusDays(5)
            onBlocking { fetchExistingFireflyTransactions() } doReturn listOf()
            onBlocking { fetchFireflyTransactionsBetween(any(), any(), any()) } doReturn listOf()
            onBlocking { fetchMissingByPlaidId(any(), any()) } doReturn listOf()
        }
        val orchestrator = orchestrator(firefly, null, webhook)

        orchestrator.runIteration(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        val reported = argumentCaptor<PollResult>()
        verify(webhook).post(any(), any(), reported.capture(), org.mockito.kotlin.isNull())
        assertThat(reported.firstValue.plaidCreated).describedAs("this is the main path").isEqualTo(1)
        assertThat(reported.firstValue.failedItems.single().institution).isEqualTo("Chase")
        assertThat(reported.firstValue.partial).isTrue()
    }

    /** D4: a dead-lettered sign-flip update is retried WITH its type, or income is recorded as an expense. */
    @Test
    fun aRetriedSignFlipUpdateStillSendsItsType() = runBlocking<Unit> {
        val store = DeadLetterStore(dir.toString())
        store.add(DeadLetter("update", "ff1", "ff1", create("u").tx, changesType = true, message = "old"))
        val sent = argumentCaptor<List<FireflyTransactionDto>>()

        service(store).retryDeadLetters()

        verify(helper).updateBatchInFirefly(sent.capture())
        assertThat(sent.firstValue.single().changesType).isTrue()
        assertThat(sent.firstValue.single().id).isEqualTo("ff1")
        verify(helper, never()).optimisticInsertBatchIntoFirefly(any())
    }

    /** N3-R3: a create that was dead-lettered is not counted as created. */
    @Test
    fun aDeadLetteredCreateIsNotCountedAsCreated() = runBlocking<Unit> {
        emptyFirefly()
        val store = DeadLetterStore(dir.toString())
        whenever(helper.optimisticInsertBatchIntoFirefly(any())).doSuspendableAnswer { throw statusError(HttpStatusCode.UnprocessableEntity) }
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(plaidResult(created = listOf(plaid(accountA, "tx1", 50.0))))
        val orchestrator = orchestrator(service(store), store)

        val result = orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", listOf(accountA))), mutableMapOf())

        assertThat(result.fireflyCreated).isEqualTo(0)
        assertThat(result.deadLetters).isEqualTo(1)
    }

    // endregion
}
