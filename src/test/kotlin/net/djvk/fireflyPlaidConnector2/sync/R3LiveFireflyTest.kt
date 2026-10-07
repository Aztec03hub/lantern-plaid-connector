package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.LocalDate
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * Live checks for review R3 against a THROWAWAY Firefly (same environment variables and warning as
 * [R2LiveFireflyTest]): M1-R3 (a dead-lettered create and the Plaid events that follow it), L1-R3 and L2-R3 (transfer
 * legs), L4-R3 (an update of a Firefly transaction that is gone). The orchestrator, service, store and converter are the
 * real ones; only Plaid is scripted.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_LIVE_FIREFLY_URL", matches = ".+")
internal class R3LiveFireflyTest {
    @TempDir
    lateinit var dir: Path

    private val url = System.getenv("LANTERN_LIVE_FIREFLY_URL")
    private val token = System.getenv("LANTERN_LIVE_FIREFLY_TOKEN")
    private val accountA = System.getenv("LANTERN_LIVE_ACCOUNT_A")
    private val accountB = System.getenv("LANTERN_LIVE_ACCOUNT_B")
    private val run = System.currentTimeMillis().toString()
    private val plaidA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val plaidB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val plaidLate = "ccccccccccccccccccccccccccccccccccccc"

    private val config = ApiConfiguration().getClientConfig()
    private val txApi = TransactionsApi(url, null, config)
    private val searchApi = SearchApi(url, null, config)
    private val plaidLinksApi = PlaidLinksApi(url, null, config)
    private val accountsApi = AccountsApi(url, null, config)
    private val syncHelper = SyncHelper(AccountConfigs(emptyList()), token, AboutApi(url, null, config), txApi, accountsApi, plaidLinksApi)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "UTC",
        transferMatchWindowDays = 3L, txStyle = TransactionStyleConfig(null), pendingTag = "pending",
    )

    private suspend fun creds() {
        txApi.setAccessToken(token); searchApi.setAccessToken(token); plaidLinksApi.setAccessToken(token); accountsApi.setAccessToken(token)
    }

    private fun api(method: String, path: String, body: String? = null): String {
        val builder = HttpRequest.newBuilder(URI("$url/api/v1/$path"))
            .header("Authorization", "Bearer $token").header("Accept", "application/json").header("Content-Type", "application/json")
        val request = if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun maxAccountId(): Int = mapper.readTree(api("GET", "accounts?type=all&limit=1000"))["data"].maxOf { it["id"].asInt() }

    private fun plaid(
        account: String, id: String, amount: Double, pending: Boolean = false, pendingId: String? = null,
    ): PlaidTransaction = PlaidFixtures.getPaymentTransaction(
        accountId = account, transactionId = id, pendingTransactionId = pendingId, amount = amount, pending = pending,
        date = LocalDate.now(), name = "R3 $id",
    )

    private val plaidSyncService: PlaidSyncService = mock()
    private val cursorManager: CursorManager = mock()

    private fun orchestrator(store: DeadLetterStore): PolledSyncOrchestrator {
        val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", plaidLinksApi, store)
        return PolledSyncOrchestrator(30, syncHelper, cursorManager, plaidSyncService, service, converter, deadLetterStore = store)
    }

    /** One real poll: Plaid says [result], the orchestrator reads, converts and writes Firefly and the dead letter file. */
    private suspend fun poll(
        orchestrator: PolledSyncOrchestrator, accountMap: Map<String, Int>,
        created: List<PlaidTransaction> = listOf(), updated: List<PlaidTransaction> = listOf(), deleted: List<String> = listOf(),
    ): PollResult {
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(created, updated, deleted))
        return orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", accountMap.keys.toList())), mutableMapOf())
    }

    private suspend fun mine(vararg ids: String): List<TransactionRead> =
        FireflyTransactionService(txApi, syncHelper, 30, "UTC", plaidLinksApi).fetchExistingFireflyTransactions().filter { read ->
            read.attributes.transactions.any { s -> ids.any { id -> s.externalId == "plaid-$id" || s.internalReference == "plaid-$id" } }
        }

    private fun summary(reads: List<TransactionRead>) = reads.map { r ->
        val s = r.attributes.transactions.single()
        "${s.type}:${s.externalId}:ref=${s.internalReference}:${s.amount}:${s.sourceId}->${s.destinationId}(${s.destinationName})"
    }

    /**
     * M1-R3, the reviewer's sequence on the real Firefly. The cause of the rejection (a Firefly account that does not
     * exist yet) is fixed between polls 2 and 3. Before the fix both the pending and the posted charge went in.
     */
    @Test
    fun aDeadLetteredPendingCreateAndItsPostedVersionAreRecordedOnceAfterRecovery() = runBlocking<Unit> {
        creds()
        val missingId = maxAccountId() + 1
        val accountMap = mapOf(plaidLate to missingId)
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)

        poll(orchestrator, accountMap, created = listOf(plaid(plaidLate, "pend$run", 42.0, pending = true)))
        println("R3LIVE M1 after poll 1: letters=${store.read().map { it.key }}")
        poll(orchestrator, accountMap, created = listOf(plaid(plaidLate, "post$run", 42.0, pendingId = "pend$run")), deleted = listOf("pend$run"))
        println("R3LIVE M1 after poll 2: letters=${store.read().map { it.key }}")

        // the fix: the account the transactions point at now exists
        val created = mapper.readTree(
            api("POST", "accounts", """{"name":"R3Late$run","type":"asset","account_role":"defaultAsset","currency_code":"USD","opening_balance":"0","opening_balance_date":"2020-01-01"}""")
        )["data"]["id"].asInt()
        assertThat(created).describedAs("the new account must get the id the letters point at").isEqualTo(missingId)
        val result = poll(orchestrator, accountMap)

        val found = mine("pend$run", "post$run")
        println("R3LIVE M1 final: ${summary(found)} letters=${store.read().size} result.deadLetters=${result.deadLetters}")
        assertThat(found).describedAs(summary(found).toString()).hasSize(1)
        assertThat(found.single().attributes.transactions.single().externalId).isEqualTo("plaid-post$run")
        assertThat(store.read()).isEmpty()
        syncHelper.deleteBatchInFirefly(found.map { it.id })
    }

    /** L1-R3 (probes p1 and p2) on the real Firefly: the removal of one leg of a transfer keeps the other leg's money. */
    @Test
    fun theRemovalOfOneLegOfATransferLeavesTheOtherLegAsAPlainTransaction() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val accountMap = mapOf(plaidA to accountA.toInt(), plaidB to accountB.toInt())

        // p1: the destination leg (the external id) is removed; the outflow from A must survive
        poll(orchestrator, accountMap, created = listOf(plaid(plaidA, "wd1$run", 50.0), plaid(plaidB, "dep1$run", -50.0)))
        val transfer = mine("wd1$run", "dep1$run")
        println("R3LIVE L1 before p1: ${summary(transfer)}")
        assertThat(transfer.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)
        poll(orchestrator, accountMap, deleted = listOf("dep1$run"))
        val afterP1 = mine("wd1$run", "dep1$run")
        println("R3LIVE L1 after p1: ${summary(afterP1)}")
        val p1 = afterP1.single().attributes.transactions.single()
        assertThat(p1.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(p1.sourceId).isEqualTo(accountA)
        assertThat(p1.amount.toDouble()).isEqualTo(50.0)
        assertThat(p1.externalId).isEqualTo("plaid-wd1$run")

        // p2: the source leg (the internal reference) is removed; the inflow to B must survive
        poll(orchestrator, accountMap, created = listOf(plaid(plaidA, "wd2$run", 30.0), plaid(plaidB, "dep2$run", -30.0)))
        poll(orchestrator, accountMap, deleted = listOf("wd2$run"))
        val afterP2 = mine("wd2$run", "dep2$run")
        println("R3LIVE L1 after p2: ${summary(afterP2)}")
        val p2 = afterP2.single().attributes.transactions.single()
        assertThat(p2.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(p2.destinationId).isEqualTo(accountB)
        assertThat(p2.amount.toDouble()).isEqualTo(30.0)

        syncHelper.deleteBatchInFirefly((afterP1 + afterP2).map { it.id })
    }

    /** L2-R3 on the real Firefly: one flipped leg leaves the transfer alone (and is reported); both legs flip it once. */
    @Test
    fun aTransferFlipsOnlyWhenBothLegsAgree() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val accountMap = mapOf(plaidA to accountA.toInt(), plaidB to accountB.toInt())
        poll(orchestrator, accountMap, created = listOf(plaid(plaidA, "fw$run", 40.0), plaid(plaidB, "fd$run", -40.0)))

        // one leg flips: left alone and reported
        val one = poll(orchestrator, accountMap, updated = listOf(plaid(plaidA, "fw$run", -40.0)))
        val unchanged = mine("fw$run", "fd$run").single().attributes.transactions.single()
        println("R3LIVE L2 one leg: ${summary(mine("fw$run", "fd$run"))} review=${one.transfersNeedingReview}")
        assertThat(unchanged.sourceId).isEqualTo(accountA)
        assertThat(one.transfersNeedingReview).isEqualTo(1)
        assertThat(one.partial).isTrue()

        // an ordinary update of the untouched leg no longer flip-flops it
        poll(orchestrator, accountMap, updated = listOf(plaid(plaidB, "fd$run", -41.0)))
        assertThat(mine("fw$run", "fd$run").single().attributes.transactions.single().sourceId).isEqualTo(accountA)

        // both legs flip in one sync: swapped once, ids swapped with the accounts
        val both = poll(orchestrator, accountMap, updated = listOf(plaid(plaidA, "fw$run", -41.0), plaid(plaidB, "fd$run", 41.0)))
        val flipped = mine("fw$run", "fd$run")
        println("R3LIVE L2 both legs: ${summary(flipped)} review=${both.transfersNeedingReview}")
        val s = flipped.single().attributes.transactions.single()
        assertThat(s.sourceId).isEqualTo(accountB)
        assertThat(s.destinationId).isEqualTo(accountA)
        assertThat(s.externalId).isEqualTo("plaid-fw$run")
        assertThat(s.internalReference).isEqualTo("plaid-fd$run")
        assertThat(both.transfersNeedingReview).isEqualTo(0)

        syncHelper.deleteBatchInFirefly(flipped.map { it.id })
    }

    /** L4-R3: an update of a Firefly transaction that no longer exists is dropped, not kept forever. */
    @Test
    fun anUpdateOfAFireflyTransactionThatIsGoneIsNotKept() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", plaidLinksApi, store)
        val split = PlaidFixturesSplit.of(accountA)

        service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("99999999", split)), listOf())
        service.retryDeadLetters()

        println("R3LIVE L4 letters=${store.read()}")
        assertThat(store.read()).isEmpty()
    }
}

/** A minimal split for the L4 live check. */
private object PlaidFixturesSplit {
    fun of(account: String) = net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures.getTransaction(
        type = TransactionTypeProperty.withdrawal, sourceId = account, externalId = "plaid-r3-gone", amount = "5.0",
    ).transactions.first()
}
