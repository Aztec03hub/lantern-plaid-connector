package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLookupRow
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * LAN-16: syncing the same Plaid batch twice against a Firefly that keeps state records each journal once.
 *
 * The fake below plays the Lantern Firefly: it stores created journals and their `plaid_links`, answers
 * `GET /plaid-links` from that state and refuses a create whose Plaid id is already linked with a 409.
 * Each run does what a poll does: look up the batch's ids (the window read is empty, as for old transactions),
 * convert, then write.
 *
 * `sync()` below is that poll assembled by hand (lookup, convert, write). `poll()` runs the real
 * `PolledSyncOrchestrator.processTransactions` over the same fake (only the Plaid side and the account kinds are mocked),
 * so the orchestrator's choice of ids to look up is covered too, with a pending to posted resync and a batch over
 * MAX_IDS. Not covered: transfer pairs (the pairing units own those).
 */
internal class ResyncNoDuplicatesTest {
    /** Touch MockUtil first: its file-level mocks must not be created in the middle of a whenever().thenReturn(). */
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    private val firefly = FireflyMock()
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private val converter = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
        pendingTag = "pending",
    )

    // region the fake Firefly

    /** Firefly's journals: group id to the splits stored for it. */
    private val journals = linkedMapOf<String, TransactionStore>()
    private var stores = 0
    private var failOnStoreNumber = -1
    private var lostResponseOnStoreNumber = -1

    /** Switch off one defence at a time: the 409 on an already-linked id, or the link lookup (answers "nothing held"). */
    private var conflictsEnabled = true
    private var lookupEnabled = true

    private fun linkOwners(): Map<String, String> =
        journals.flatMap { (group, tx) -> tx.transactions.flatMap { it.plaidLinks.orEmpty() }.map { it.plaidTransactionId to group } }.toMap()

    private fun statusError(status: HttpStatusCode, body: String): ClientRequestException = runBlocking {
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                jackson { net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient.JSON_DEFAULT(this) }
            }
        }
        try {
            client.get("https://x.test/y"); throw AssertionError("expected an error")
        } catch (e: ClientRequestException) {
            e
        }
    }

    /** Firefly's 409 for a write that sends a Plaid id another group holds ([self] is the group being updated, if any). */
    private fun refuseIfHeld(ids: List<String>, owners: Map<String, String>, self: String?) {
        val conflicts = ids.filter { id -> owners[id].let { it != null && it != self } }
        if (!conflictsEnabled || conflicts.isEmpty()) return
        // core's body: integer journal and group ids plus the leg (docs/core-plaid-links.md)
        val rows = conflicts.joinToString(",") { id ->
            val group = owners.getValue(id)
            val link = journals.getValue(group).transactions.flatMap { s -> s.plaidLinks.orEmpty() }.first { l -> l.plaidTransactionId == id }
            """{"plaid_transaction_id":"$id","transaction_journal_id":${group.drop(1)},"transaction_group_id":${group.drop(1)},"leg":"${link.leg.value}"}"""
        }
        throw statusError(HttpStatusCode.Conflict, """{"message":"already linked","conflicts":[$rows]}""")
    }

    private fun read(group: String, store: TransactionStore) =
        TransactionRead("transactions", group, Transaction(transactions = store.transactions), ObjectLink())

    init {
        runBlocking {
            whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
                val store = it.getArgument<TransactionStore>(0)
                if (++stores == failOnStoreNumber) throw IllegalStateException("simulated crash before store #$stores")
                val owners = linkOwners()
                refuseIfHeld(store.transactions.flatMap { s -> s.plaidLinks.orEmpty().map { l -> l.plaidTransactionId } }, owners, null)
                val group = "g${journals.size + 1}"
                journals[group] = store
                // The write landed but the answer never reached the connector
                if (stores == lostResponseOnStoreNumber) throw java.io.IOException("simulated lost response for store #$stores")
                createFireflyResponse(TransactionSingle(read(group, store)))
            }
            whenever(firefly.plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer {
                // the fake mocks the API, so the real client's size rule is enforced here
                require(it.getArgument<List<String>>(0).size in 1..PlaidLinksApi.MAX_IDS) { "A link lookup takes 1 to ${PlaidLinksApi.MAX_IDS} ids" }
                val owners = if (lookupEnabled) linkOwners() else mapOf()
                val rows = it.getArgument<List<String>>(0).filter { id -> id in owners }.map { id ->
                    val group = owners.getValue(id)
                    val link = journals.getValue(group).transactions.flatMap { s -> s.plaidLinks.orEmpty() }.first { l -> l.plaidTransactionId == id }
                    PlaidLinkLookupRow(id, "j$group", group, link.leg, link.plaidAccountId)
                }
                createFireflyResponse(PlaidLinkLookupResponse(rows))
            }
            whenever(firefly.transactionsApi.updateTransaction(any(), any())).doSuspendableAnswer {
                val group = it.getArgument<String>(0)
                val update = it.getArgument<net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate>(1)
                val links = update.transactions!!.first().plaidLinks
                if (links != null) refuseIfHeld(links.map { l -> l.plaidTransactionId }, linkOwners(), group)
                val old = journals.getValue(group)
                journals[group] = old.copy(transactions = old.transactions.map { s -> if (links != null) s.copy(plaidLinks = links) else s })
                createFireflyResponse(TransactionSingle(read(group, journals.getValue(group))))
            }
            whenever(firefly.transactionsApi.deleteTransaction(any())).doSuspendableAnswer {
                journals.remove(it.getArgument<String>(0))
                createFireflyResponse(Unit)
            }
            val noPagination = mock<Meta> { on { pagination } doReturn null }
            val emptyWindow = createFireflyResponse(mock<TransactionArray> {
                on { data } doReturn listOf()
                on { meta } doReturn noPagination
            })
            whenever(firefly.transactionsApi.listTransaction(any(), any(), any(), any())).thenReturn(emptyWindow)
            whenever(firefly.transactionsApi.getTransaction(any())).doSuspendableAnswer {
                val group = it.getArgument<String>(0)
                createFireflyResponse(TransactionSingle(read(group, journals.getValue(group))))
            }
        }
    }

    // endregion

    /** The real helper: every layer between the service and the fake is production code. */
    private val syncHelper = SyncHelper(AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi, 1)

    private val service = FireflyTransactionService(firefly.transactionsApi, syncHelper, 30, "UTC", firefly.plaidLinksApi)

    @TempDir
    lateinit var dir: Path

    private fun serviceWithStore() = DeadLetterStore(dir.toString()).let {
        it to FireflyTransactionService(firefly.transactionsApi, syncHelper, 30, "UTC", firefly.plaidLinksApi, it)
    }

    private fun linked(vararg plaidIds: String) = FireflyTransactionDto(
        null,
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.withdrawal, sourceId = "1", amount = "5.0",
            plaidLinks = plaidIds.map { PlaidLink(it, PlaidLinkLeg.single, "plaidAccountA") },
        ).transactions.first(),
    )

    /** The orchestrator over the fake. Only Plaid and the account kinds are mocked; lookup, window read, conversion and writes are real. */
    private val plaidSyncService: PlaidSyncService = mock()
    private val orchestrator = PolledSyncOrchestrator(
        30, mock<SyncHelper> { onBlocking { fetchAccountKinds() } doReturn emptyMap() }, mock<CursorManager>(), plaidSyncService, service, converter,
    )

    private suspend fun poll(created: List<PlaidTransaction> = listOf(), updated: List<PlaidTransaction> = listOf(), deleted: List<String> = listOf()) {
        whenever(plaidSyncService.processPlaidTransactions(any(), any())).thenReturn(PlaidTransactionResult(created, updated, deleted))
        orchestrator.processTransactions(accountMap, sequenceOf("tok-12345678" to listOf(accountA)), mutableMapOf())
    }

    private fun plaid(id: String, amount: Double, pending: Boolean = false, pendingId: String? = null): PlaidTransaction =
        PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = id, amount = amount, pending = pending, pendingTransactionId = pendingId, name = "Plaid $id")

    private val batch = (1..4).map {
        PlaidFixtures.getPaymentTransaction(accountId = accountA, transactionId = "tx$it", amount = 10.0 * it, pendingTransactionId = null)
    }

    /** One poll over [batch]: returns how many journals this run created. */
    private suspend fun sync(): Int {
        val before = journals.size
        val found = service.fetchMissingByPlaidId(batch.map { it.transactionId }, listOf())
        val result = converter.convertPollSync(accountMap, batch, listOf(), listOf(), found)
        service.processFireflyTransactionUpdates(result.creates, result.updates, listOf())
        return journals.size - before
    }

    @Test
    fun syncingTheSameBatchTwiceLeavesExactlyOneJournalPerTransaction() = runBlocking<Unit> {
        assertThat(sync()).isEqualTo(batch.size)
        assertThat(journals).hasSize(batch.size)

        val storesBefore = stores
        val secondRun = sync()

        assertThat(stores - storesBefore).describedAs("create requests the second run sent").isEqualTo(0)
        assertThat(secondRun).describedAs("journals created by the second run").isEqualTo(0)
        assertThat(journals).describedAs("journals after the second run").hasSize(batch.size)
        assertThat(linkOwners().keys).containsExactlyInAnyOrder("tx1", "tx2", "tx3", "tx4")
    }

    /** The lookup alone: Firefly never refuses a duplicate (409 off), so only the connector's link lookup can stop one. */
    @Test
    fun theLinkLookupAlonePreventsDuplicatesOnAResync() = runBlocking<Unit> {
        conflictsEnabled = false
        sync()

        val storesBefore = stores
        sync()

        assertThat(stores - storesBefore).describedAs("create requests sent by the second run").isEqualTo(0)
        assertThat(journals).describedAs("journals after the second run").hasSize(batch.size)
    }

    /** The 409 alone: the lookup misses (Firefly lagging behind), so every create is sent and every one must be refused and dropped. */
    @Test
    fun theConflictHandlingAlonePreventsDuplicatesOnAResync() = runBlocking<Unit> {
        lookupEnabled = false
        sync()

        val storesBefore = stores
        val secondRun = sync()

        assertThat(stores - storesBefore).describedAs("creates sent (all refused with 409)").isEqualTo(batch.size)
        assertThat(secondRun).isEqualTo(0)
        assertThat(journals).describedAs("journals after the second run").hasSize(batch.size)
    }

    /** The realistic double-create: Firefly stores the journal but the answer is lost, so the poll fails and is retried. */
    @Test
    fun aLostResponseAfterAStoredJournalIsNotRecordedAgainOnTheRetry() = runBlocking<Unit> {
        lostResponseOnStoreNumber = 2
        runCatching { sync() }.exceptionOrNull().let { assertThat(it).isNotNull() }
        assertThat(journals).describedAs("journals the failed run left").hasSize(2)

        sync()

        assertThat(journals).hasSize(batch.size)
        assertThat(linkOwners().keys).containsExactlyInAnyOrder("tx1", "tx2", "tx3", "tx4")
    }

    @Test
    fun aFirstRunThatCrashesHalfWayThenAFullSecondRunStillLeavesNoDuplicates() = runBlocking<Unit> {
        failOnStoreNumber = 2 // the first journal is stored, then the run dies
        val crash = runCatching { sync() }.exceptionOrNull()
        assertThat(crash).isInstanceOf(IllegalStateException::class.java)
        assertThat(journals).describedAs("journals the crashed run left").hasSize(1)

        val secondRun = sync()

        assertThat(secondRun).describedAs("journals created by the completing run").isEqualTo(batch.size - 1)
        assertThat(journals).describedAs("journals after the second run").hasSize(batch.size)
        assertThat(linkOwners().keys).containsExactlyInAnyOrder("tx1", "tx2", "tx3", "tx4")
        assertThat(sync()).describedAs("a third run").isEqualTo(0)
        assertThat(journals).hasSize(batch.size)
    }

    // region U38: each 409 layer with a response production really returns, and the orchestrator path

    /** Replaces a mock-only test: the real helper (not a mock that throws a create 409) meets the 409 of an already-landed create. */
    @Test
    fun aCreateLetterThatAlreadyLandedIsDroppedThroughTheRealHelper() = runBlocking<Unit> {
        val (store, retrying) = serviceWithStore()
        service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf())
        store.add(DeadLetter("create", "tx1", null, linked("tx1").tx, false, "old"))
        lookupEnabled = false // so the retry's create is really sent and really refused
        val storesBefore = stores

        retrying.retryDeadLetters()

        assertThat(stores - storesBefore).describedAs("the retry's create was sent and refused").isEqualTo(1)
        assertThat(store.read()).isEmpty()
        assertThat(journals).hasSize(1)
    }

    @Test
    fun aPollCreateThatFireflyAlreadyHoldsIsAnsweredWith409AndNotCountedAsCreated() = runBlocking<Unit> {
        val (store, polling) = serviceWithStore()
        service.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf())
        lookupEnabled = false

        val notCreated = polling.processFireflyTransactionUpdates(listOf(linked("tx1")), listOf(), listOf())

        assertThat(notCreated).isEqualTo(1)
        assertThat(store.read()).isEmpty()
        assertThat(journals).hasSize(1)
    }

    /** `guarded` as the only layer: the helper has no 409 handling for an update, and Firefly answers 409 to one that sends another group's id. */
    @Test
    fun anUpdateThatFireflyAnswers409IsDroppedByGuardedWhenTheRealHelperDoesNotAbsorbIt() = runBlocking<Unit> {
        val (store, polling) = serviceWithStore()
        service.processFireflyTransactionUpdates(listOf(linked("a"), linked("b")), listOf(), listOf())
        val g1Before = journals.getValue("g1")

        polling.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("g1", linked("a", "b").tx)), listOf())

        assertThat(store.read()).isEmpty()
        assertThat(journals.getValue("g1")).isEqualTo(g1Before)
        assertThat(linkOwners()).containsEntry("b", "g2")
    }

    private fun plainBatchResyncLeavesOneJournalEach() = runBlocking<Unit> {
        poll(created = batch.map { plaid(it.transactionId, 10.0) })
        poll(created = batch.map { plaid(it.transactionId, 10.0) })
        assertThat(journals).hasSize(4)
        assertThat(linkOwners().keys).containsExactlyInAnyOrder("tx1", "tx2", "tx3", "tx4")
    }

    @Test
    fun thePolledResyncOfAPlainBatchLeavesOneJournalEach() = plainBatchResyncLeavesOneJournalEach()

    /** A plain create is not looked up by the orchestrator (only the 409 stops it), so this variant pins the 409 alone. */
    @Test
    fun thePolledResyncOfAPlainBatchIsStoppedBy409Alone() { lookupEnabled = false; plainBatchResyncLeavesOneJournalEach() }

    private fun pendingToPostedThenRepeat() = runBlocking<Unit> {
        poll(created = listOf(plaid("p1", 25.0, pending = true)))
        assertThat(journals).hasSize(1)
        val posted = plaid("t1", 25.0, pendingId = "p1")
        poll(created = listOf(posted), deleted = listOf("p1"))
        poll(created = listOf(posted), deleted = listOf("p1"))
        assertThat(journals).describedAs("journals after the resync").hasSize(1)
        assertThat(linkOwners().keys).describedAs("link owners").containsExactly("t1")
    }

    @Test
    fun aPendingToPostedResyncThroughTheOrchestratorLeavesOneJournal() = pendingToPostedThenRepeat()

    @Test
    fun aPendingToPostedResyncWithTheLookupAloneLeavesOneJournal() { conflictsEnabled = false; pendingToPostedThenRepeat() }

    /**
     * LAN-110 (found by U38): with the lookup missing the pending journal, the 409 can't help (the posted id differs from the pending one's):
     * the posted create lands as a second journal beside the still-held pending one. Enable once the connector re-checks.
     */
    @org.junit.jupiter.api.Disabled("LAN-110: pending to posted has no 409 defence; a lookup that misses leaves pending p1 and posted t1 as 2 journals")
    @Test
    fun aPendingToPostedResyncWithThe409AloneLeavesOneJournal() { lookupEnabled = false; pendingToPostedThenRepeat() }

    @Test
    fun aBatchOverTheLookupLimitIsChunkedAndNotDuplicatedOnResync() = runBlocking<Unit> {
        val ids = (1..30)
        poll(created = ids.map { plaid("p$it", 10.0 * it, pending = true) })
        val posted = ids.map { plaid("t$it", 10.0 * it, pendingId = "p$it") }
        val removed = ids.map { "p$it" }
        poll(created = posted, deleted = removed)
        poll(created = posted, deleted = removed)

        val lists = argumentCaptor<List<String>>()
        verify(firefly.plaidLinksApi, atLeast(2)).lookupPlaidLinks(lists.capture())
        assertThat(lists.allValues).allSatisfy { assertThat(it.size).isBetween(1, PlaidLinksApi.MAX_IDS) }
        assertThat(lists.allValues.maxOf { it.size }).describedAs("a full chunk was sent").isEqualTo(PlaidLinksApi.MAX_IDS)
        assertThat(journals).hasSize(30)
        assertThat(linkOwners().keys).containsExactlyInAnyOrderElementsOf(ids.map { "t$it" })
    }

    // endregion
}
