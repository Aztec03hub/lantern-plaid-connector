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
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever

/**
 * LAN-16: syncing the same Plaid batch twice against a Firefly that keeps state records each journal once.
 *
 * The fake below plays the Lantern Firefly: it stores created journals and their `plaid_links`, answers
 * `GET /plaid-links` from that state and refuses a create whose Plaid id is already linked with a 409.
 * Each run does what a poll does: look up the batch's ids (the window read is empty, as for old transactions),
 * convert, then write.
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
    )

    // region the fake Firefly

    /** Firefly's journals: group id to the splits stored for it. */
    private val journals = linkedMapOf<String, TransactionStore>()
    private var stores = 0
    private var failOnStoreNumber = -1

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

    private fun read(group: String, store: TransactionStore) =
        TransactionRead("transactions", group, Transaction(transactions = store.transactions), ObjectLink())

    init {
        runBlocking {
            whenever(firefly.transactionsApi.storeTransaction(any())).doSuspendableAnswer {
                val store = it.getArgument<TransactionStore>(0)
                if (++stores == failOnStoreNumber) throw IllegalStateException("simulated crash before store #$stores")
                val owners = linkOwners()
                val conflicts = store.transactions.flatMap { s -> s.plaidLinks.orEmpty().map { l -> l.plaidTransactionId } }.filter { id -> id in owners }
                if (conflicts.isNotEmpty()) {
                    throw statusError(
                        HttpStatusCode.Conflict,
                        """{"message":"already linked","conflicts":[${conflicts.joinToString(",") { id -> """{"plaid_transaction_id":"$id"}""" }}]}""",
                    )
                }
                val group = "g${journals.size + 1}"
                journals[group] = store
                createFireflyResponse(TransactionSingle(read(group, store)))
            }
            whenever(firefly.plaidLinksApi.lookupPlaidLinks(any())).doSuspendableAnswer {
                val owners = linkOwners()
                val rows = it.getArgument<List<String>>(0).filter { id -> id in owners }.map { id ->
                    val group = owners.getValue(id)
                    val link = journals.getValue(group).transactions.flatMap { s -> s.plaidLinks.orEmpty() }.first { l -> l.plaidTransactionId == id }
                    PlaidLinkLookupRow(id, "j$group", group, link.leg, link.plaidAccountId)
                }
                createFireflyResponse(PlaidLinkLookupResponse(rows))
            }
            whenever(firefly.transactionsApi.getTransaction(any())).doSuspendableAnswer {
                val group = it.getArgument<String>(0)
                createFireflyResponse(TransactionSingle(read(group, journals.getValue(group))))
            }
        }
    }

    // endregion

    private val service = FireflyTransactionService(
        firefly.transactionsApi,
        SyncHelper(AccountConfigs(emptyList()), "token", firefly.aboutApi, firefly.transactionsApi, firefly.accountsApi, firefly.plaidLinksApi, 1),
        30, "UTC", firefly.plaidLinksApi,
    )

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

        val secondRun = sync()

        assertThat(secondRun).describedAs("journals created by the second run").isEqualTo(0)
        assertThat(journals).describedAs("journals after the second run").hasSize(batch.size)
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
}
