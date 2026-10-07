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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Meta
import net.djvk.fireflyPlaidConnector2.api.firefly.models.MetaPagination
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionArray
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionUpdate
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.lib.createFireflyResponse
import net.djvk.fireflyPlaidConnector2.transactions.AccountKind
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.pairedTransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Path
import java.time.LocalDate

/**
 * A Firefly that keeps what the connector relies on: a Plaid link table that refuses a link another journal holds
 * (409), links freed when a journal is deleted, and Firefly's rule for the type of a journal between two accounts (422).
 */
private class FakeFirefly(private val kinds: Map<String, AccountKind>) : TransactionsApi() {
    val journals = linkedMapOf<String, TransactionSplit>()
    private var nextId = 1

    /** The next update fails like a crashed process (not a Firefly answer). */
    @Volatile
    var crashOnNextUpdate = false

    private fun error(status: HttpStatusCode, body: String): ClientRequestException = runBlocking {
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

    private fun conflict(ids: List<String>) = error(
        HttpStatusCode.Conflict,
        """{"message":"already linked","conflicts":[${ids.joinToString(",") { """{"plaid_transaction_id":"$it"}""" }}]}""",
    )

    private fun read(id: String, split: TransactionSplit) =
        TransactionRead("transactions", id, Transaction(transactions = listOf(split)), ObjectLink())

    private fun heldElsewhere(links: List<net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink>?, except: String?) =
        links.orEmpty().map { it.plaidTransactionId }.filter { id ->
            journals.any { (gid, s) -> gid != except && s.plaidLinks.orEmpty().any { it.plaidTransactionId == id } }
        }

    private fun checkType(split: TransactionSplit) {
        val s = split.sourceId?.toIntOrNull() ?: return
        val d = split.destinationId?.toIntOrNull() ?: return
        val want = pairedTransactionType(kinds[s.toString()] ?: AccountKind.ASSET, kinds[d.toString()] ?: AccountKind.ASSET)
        if (split.type != want) {
            throw error(HttpStatusCode.UnprocessableEntity, """{"message":"Could not find a valid destination account","errors":{}}""")
        }
    }

    private val lock = Any()

    override suspend fun storeTransaction(transactionStore: TransactionStore) = synchronized(lock) {
        transactionStore.transactions.single().let { split ->
            val clash = heldElsewhere(split.plaidLinks, null)
            if (clash.isNotEmpty()) throw conflict(clash)
            checkType(split)
            val id = (nextId++).toString()
            journals[id] = split
            createFireflyResponse(TransactionSingle(read(id, split)))
        }
    }

    override suspend fun updateTransaction(id: String, transactionUpdate: TransactionUpdate) = synchronized(lock) {
        run {
            if (crashOnNextUpdate) {
                crashOnNextUpdate = false
                throw IllegalStateException("process crashed")
            }
            val current = journals[id] ?: throw error(HttpStatusCode.NotFound, "{}")
            val u = transactionUpdate.transactions!!.single()
            val clash = heldElsewhere(u.plaidLinks, id)
            if (clash.isNotEmpty()) throw conflict(clash)
            val updated = current.copy(
                type = u.type ?: current.type,
                sourceId = u.sourceId ?: current.sourceId,
                destinationId = u.destinationId ?: current.destinationId,
                destinationName = if (u.destinationId != null) null else current.destinationName,
                sourceName = if (u.sourceId != null) null else current.sourceName,
                tags = u.tags ?: current.tags,
                plaidLinks = u.plaidLinks ?: current.plaidLinks,
            )
            checkType(updated)
            journals[id] = updated
            createFireflyResponse(TransactionSingle(read(id, updated)))
        }
    }

    override suspend fun deleteTransaction(id: String): net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse<Unit> =
        synchronized(lock) {
            journals.remove(id) ?: throw error(HttpStatusCode.NotFound, "{}")
            createFireflyResponse(Unit)
        }

    override suspend fun listTransaction(page: Int?, start: LocalDate?, end: LocalDate?, type: TransactionTypeFilter?) =
        synchronized(lock) {
            createFireflyResponse(
                TransactionArray(
                    journals.map { (id, s) -> read(id, s) },
                    Meta(MetaPagination(journals.size, journals.size, 1000, 1, 1)),
                    net.djvk.fireflyPlaidConnector2.api.firefly.models.PageLink(),
                )
            )
        }

    /** The journals as content only, so two runs that end the same compare equal whatever ids Firefly handed out. */
    fun state(): Set<String> = synchronized(lock) { journals.values.map { s ->
        "${s.type}|${s.sourceId ?: s.sourceName}|${s.destinationId ?: s.destinationName}|${s.amount}|" +
                s.plaidLinks.orEmpty().map { "${it.leg}:${it.plaidTransactionId}" }.sorted()
    }.toSet().also { check(it.size == journals.size) { "two journals with the same content" } } }
}

internal class TransferReconcilerTest {
    @Suppress("unused")
    private val warmMockUtil = net.djvk.fireflyPlaidConnector2.lib.OK_RESPONSE

    @TempDir
    lateinit var dir: Path

    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private fun plaidAccount(letter: Char) = letter.toString().repeat(37)

    private fun plaidTx(id: String, account: Char, amount: Double, name: String = "N$id") =
        PlaidFixtures.getPaymentTransaction(
            accountId = plaidAccount(account), transactionId = id, pendingTransactionId = null, amount = amount, name = name,
            categoryId = null, category = null,
        ).copy(personalFinanceCategory = null)

    /** Four own accounts a(1) b(2) c(3) d(4), four transfers between them and one ordinary purchase. */
    private val allTxs: List<PlaidTransaction> = listOf(
        plaidTx("t1a", 'a', 100.0), plaidTx("t1b", 'b', -100.0), // a -> b
        plaidTx("t2c", 'c', 250.0), plaidTx("t2a", 'a', -250.0), // c -> a
        plaidTx("t3b", 'b', 75.0), plaidTx("t3d", 'd', -75.0),   // b -> d
        plaidTx("t4d", 'd', 40.0), plaidTx("t4c", 'c', -40.0),   // d -> c
        plaidTx("s1", 'a', 12.34, name = "Coffee"),
    )

    private inner class Setup(kinds: Map<String, AccountKind> = mapOf()) {
        val firefly = FakeFirefly(kinds)
        val store = DeadLetterStore(dir.toString())
        val converter = TransactionConverter(
            useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
            enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/New_York",
            transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null),
        ).also { it.accountKinds = kinds }
        val helper = SyncHelper(
            AccountConfigs(('a'..'d').mapIndexed { i, c -> AccountConfig(i + 1, "token", plaidAccount(c)) }),
            "t", FireflyMock().aboutApi, firefly, FireflyMock().accountsApi, mock<PlaidLinksApi>(), 8,
        )
        val service = FireflyTransactionService(firefly, helper, 30, "UTC", mock<PlaidLinksApi>(), store)
        val reconciler = TransferReconciler(helper, service, converter, store, "America/New_York", 3)

        /** One import run: the Plaid transactions of some accounts, converted and written, then the pairing pass. */
        suspend fun run(vararg accounts: Char) {
            val txs = allTxs.filter { it.accountId in accounts.map(::plaidAccount) }
            helper.optimisticInsertBatchIntoFirefly(converter.convertBatchSync(txs, accountMap))
            reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1))
        }
    }

    private fun combinedState(kinds: Map<String, AccountKind> = mapOf()) = runBlocking {
        Setup(kinds).also { it.run('a', 'b', 'c', 'd') }.firefly.state()
    }

    @Test
    fun theCombinedRunMakesOneJournalWithTwoLinksPerTransfer() {
        val state = combinedState()
        assertThat(state.filter { it.startsWith("transfer") }).hasSize(4)
        assertThat(state).contains("transfer|1|2|100.0|[destination:t1b, source:t1a]")
        assertThat(state.filter { "s1" in it }).hasSize(1)
        assertThat(state).hasSize(5)
    }

    @Test
    fun perBankRunsInEitherOrderGiveTheCombinedState() = runBlocking<Unit> {
        val expected = combinedState()
        for (order in listOf(listOf("ab", "cd"), listOf("cd", "ab"), listOf("a", "b", "c", "d"), listOf("d", "c", "b", "a"))) {
            val setup = Setup()
            order.forEach { setup.run(*it.toCharArray()) }
            assertThat(setup.firefly.state()).describedAs("order $order").isEqualTo(expected)
        }
    }

    @Test
    fun twoConcurrentRunsGiveTheCombinedState() = runBlocking<Unit> {
        val expected = combinedState()
        val setup = Setup()
        listOf(async(Dispatchers.Default) { setup.run('a', 'b') }, async(Dispatchers.Default) { setup.run('c', 'd') }).awaitAll()
        // a leg the other run had not written yet when this one paired is picked up by one more pass
        setup.reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1))
        assertThat(setup.firefly.state()).isEqualTo(expected)
    }

    @Test
    fun withALiabilityTheSameHoldsAndFireflysTypeRuleIsMet() = runBlocking<Unit> {
        // account 2 is a card: a -> b is a payment (withdrawal), b -> d a cash advance (deposit); the fake answers 422 otherwise
        val kinds = mapOf("2" to AccountKind.LIABILITY)
        val expected = combinedState(kinds)
        assertThat(expected).contains("withdrawal|1|2|100.0|[destination:t1b, source:t1a]")
        assertThat(expected).contains("deposit|2|4|75.0|[destination:t3d, source:t3b]")
        val setup = Setup(kinds)
        listOf("a", "b", "c", "d").forEach { setup.run(*it.toCharArray()) }
        assertThat(setup.firefly.state()).isEqualTo(expected)
    }

    @Test
    fun aCrashBetweenTheDeleteAndTheUpdateIsHealedByTheNextPass() = runBlocking<Unit> {
        val expected = combinedState()
        val setup = Setup()
        setup.run('a', 'b')
        setup.run('c', 'd')   // no crash: everything but the cross-bank transfers is paired
        // start over with the cross-bank legs only unpaired, and crash on the first merge's update
        val crashing = Setup()
        crashing.helper.optimisticInsertBatchIntoFirefly(crashing.converter.convertBatchSync(allTxs.filter { it.accountId in "ab".map(::plaidAccount) }, accountMap))
        crashing.helper.optimisticInsertBatchIntoFirefly(crashing.converter.convertBatchSync(allTxs.filter { it.accountId in "cd".map(::plaidAccount) }, accountMap))
        crashing.firefly.crashOnNextUpdate = true
        val failure = runCatching { crashing.reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)

        // the deleted leg is gone from Firefly and waits as a dead letter that re-creates it
        assertThat(crashing.store.read().map { it.operation }).containsExactly("create")
        val heldIds = crashing.firefly.journals.values.flatMap { it.plaidLinks.orEmpty() }.map { it.plaidTransactionId }
        assertThat(heldIds).doesNotContain(crashing.store.read().single().key)

        // the next pass (a poll, or syncMode=pair) re-creates it and pairs it again
        crashing.reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1))
        assertThat(crashing.store.read()).isEmpty()
        assertThat(crashing.firefly.state()).isEqualTo(expected)
        assertThat(setup.firefly.state()).isEqualTo(expected)
    }

    @Test
    fun ambiguousCandidatesAreLeftAlone() = runBlocking<Unit> {
        // two 100.00 payments out of a and c in the same days, one 100.00 in on b: which one it answers is unknowable
        val txs = listOf(plaidTx("x1", 'a', 100.0), plaidTx("x2", 'c', 100.0), plaidTx("x3", 'b', -100.0))
        val setup = Setup()
        for (tx in txs) {
            setup.helper.optimisticInsertBatchIntoFirefly(setup.converter.convertBatchSync(listOf(tx), accountMap))
        }
        val before = setup.firefly.state()
        val merged = setup.reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1))

        assertThat(merged).isZero()
        assertThat(setup.firefly.state()).isEqualTo(before)
        assertThat(before).hasSize(3)
        assertThat(before.all { it.startsWith("withdrawal") || it.startsWith("deposit") }).isTrue()
    }

    @Test
    fun aSecondPassOverPairedDataChangesNothing() = runBlocking<Unit> {
        val setup = Setup()
        setup.run('a', 'b', 'c', 'd')
        val before = setup.firefly.state()
        assertThat(setup.reconciler.reconcile(LocalDate.now().minusDays(30), LocalDate.now().plusDays(1))).isZero()
        assertThat(setup.firefly.state()).isEqualTo(before)
    }

    @Test
    fun theUsersEditsOnTheKeptJournalSurvive() = runBlocking<Unit> {
        val setup = Setup()
        setup.run('a')
        setup.run('b')
        // the pass paired a1 and b1 already; check the kept journal still has the withdrawal's own description and tags
        val paired = setup.firefly.journals.values.single { it.type == TransactionTypeProperty.transfer }
        assertThat(paired.description).isEqualTo("Nt1a")
        assertThat(paired.plaidLinks!!.map { it.leg }).containsExactlyInAnyOrder(PlaidLinkLeg.source, PlaidLinkLeg.destination)
    }
}
