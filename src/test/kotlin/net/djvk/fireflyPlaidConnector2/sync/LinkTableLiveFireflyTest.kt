package net.djvk.fireflyPlaidConnector2.sync

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSingle
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionStore
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * Live checks of the Plaid link table scheme against a THROWAWAY Lantern Firefly (the fork at 5d3cb445c4 on Postgres).
 * Environment: LANTERN_LIVE_FIREFLY_URL, LANTERN_LIVE_FIREFLY_TOKEN, LANTERN_LIVE_ACCOUNT_A and _B (two asset accounts).
 * Never point this at a Firefly that holds real data. The orchestrator, service, store, converter and sync helper are
 * the real ones; only Plaid is scripted.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_LIVE_FIREFLY_URL", matches = ".+")
internal class LinkTableLiveFireflyTest {
    @TempDir
    lateinit var dir: Path

    private val url = System.getenv("LANTERN_LIVE_FIREFLY_URL")
    private val token = System.getenv("LANTERN_LIVE_FIREFLY_TOKEN")
    private val accountA = System.getenv("LANTERN_LIVE_ACCOUNT_A")
    private val accountB = System.getenv("LANTERN_LIVE_ACCOUNT_B")
    private val run = System.currentTimeMillis().toString()
    private val plaidA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val plaidB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val accountMap get() = mapOf(plaidA to accountA.toInt(), plaidB to accountB.toInt())

    private val config = ApiConfiguration().getClientConfig()
    private val txApi = TransactionsApi(url, null, config)
    private val linksApi = PlaidLinksApi(url, null, config)
    private val accountsApi = AccountsApi(url, null, config)
    private val syncHelper = SyncHelper(AccountConfigs(emptyList()), token, AboutApi(url, null, config), txApi, accountsApi, linksApi)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "UTC",
        transferMatchWindowDays = 3L, txStyle = TransactionStyleConfig(null), pendingTag = "pending",
    )

    /** A TransactionsApi whose Nth store (1-based) fails like a dropped connection, after the first N-1 went through. */
    private inner class CrashingTxApi(private val crashOn: Int) : TransactionsApi(url, null, config) {
        val stores = AtomicInteger()
        override suspend fun storeTransaction(transactionStore: TransactionStore): HttpResponse<TransactionSingle> {
            if (stores.incrementAndGet() == crashOn) throw java.io.IOException("connection reset (simulated)")
            return super.storeTransaction(transactionStore)
        }
    }

    private suspend fun creds() {
        syncHelper.setApiCreds()
    }

    private fun api(method: String, path: String, body: String? = null): String {
        val builder = HttpRequest.newBuilder(URI("$url/api/v1/$path"))
            .header("Authorization", "Bearer $token").header("Accept", "application/json").header("Content-Type", "application/json")
        val request = if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun plaid(
        account: String, id: String, amount: Double, pending: Boolean = false, pendingId: String? = null,
        date: LocalDate = LocalDate.now(),
    ): PlaidTransaction = PlaidFixtures.getPaymentTransaction(
        accountId = account, transactionId = id, pendingTransactionId = pendingId, amount = amount, pending = pending,
        date = date, name = "LT $id",
    )

    private val plaidSyncService: PlaidSyncService = mock()
    private val cursorManager: CursorManager = mock()

    private fun orchestrator(
        store: DeadLetterStore, tx: TransactionsApi = txApi, helper: SyncHelper = syncHelper,
    ): PolledSyncOrchestrator {
        val service = FireflyTransactionService(tx, helper, 30, "UTC", linksApi, store)
        return PolledSyncOrchestrator(30, helper, cursorManager, plaidSyncService, service, converter, deadLetterStore = store)
    }

    private suspend fun poll(
        orchestrator: PolledSyncOrchestrator,
        created: List<PlaidTransaction> = listOf(), updated: List<PlaidTransaction> = listOf(), deleted: List<String> = listOf(),
    ): PollResult {
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(created, updated, deleted))
        return orchestrator.processTransactions(accountMap, sequenceOf(Pair("tok-12345678", accountMap.keys.toList())), mutableMapOf())
    }

    /** The Firefly transactions that hold any of [ids]: found the way the connector finds them (link lookup, group read). */
    private suspend fun mine(vararg ids: String): List<TransactionRead> {
        val groups = linksApi.lookupPlaidLinks(ids.toList()).body().data.map { it.transactionGroupId }.distinct()
        return groups.map { txApi.getTransaction(it).body().data }
    }

    private suspend fun held(vararg ids: String): Set<String> = linksApi.lookupPlaidLinks(ids.toList()).body().data.map { it.plaidTransactionId }.toSet()

    private fun linksOf(read: TransactionRead) = read.attributes.transactions.single().plaidLinks.orEmpty()

    private fun summary(reads: List<TransactionRead>) = reads.map { r ->
        val s = r.attributes.transactions.single()
        "${s.type}:${s.amount}:${s.sourceId}->${s.destinationId}:links=${s.plaidLinks?.map { "${it.plaidTransactionId}/${it.leg}" }}:ext=${s.externalId}"
    }

    private suspend fun cleanup(vararg reads: List<TransactionRead>) =
        syncHelper.deleteBatchInFirefly(reads.flatMap { it }.map { it.id }.distinct())

    /** A create, then the same create again in a later poll: one transaction, then one more only if the cursor never moved. */
    @Test
    fun aCreateRepeatedByPlaidIsRecordedOnce() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val tx = plaid(plaidA, "once$run", 12.5)

        val first = poll(orchestrator, created = listOf(tx))
        val second = poll(orchestrator, created = listOf(tx))

        val found = mine("once$run")
        println("LINKLIVE repeat: ${summary(found)} first.created=${first.fireflyCreated} second.created=${second.fireflyCreated}")
        assertThat(found).hasSize(1)
        assertThat(linksOf(found.single())).containsExactly(PlaidLink("once$run", PlaidLinkLeg.single, plaidA))
        assertThat(first.fireflyCreated).isEqualTo(1)
        assertThat(second.fireflyCreated).isEqualTo(0)
        assertThat(store.read()).isEmpty()
        cleanup(found)
    }

    /** The same, for a create older than the pull window: nothing finds it but the database's refusal, a 409. */
    @Test
    fun aRetriedCreateOutsideThePullWindowIsRefusedByTheDatabaseAndNotDeadLettered() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val old = plaid(plaidA, "old$run", 33.0, date = LocalDate.now().minusDays(120))

        poll(orchestrator, created = listOf(old))
        val retry = poll(orchestrator, created = listOf(old))

        val found = mine("old$run")
        println("LINKLIVE old retry: ${summary(found)} created=${retry.fireflyCreated} letters=${store.read().size}")
        assertThat(found).hasSize(1)
        assertThat(retry.fireflyCreated).isEqualTo(0)
        assertThat(retry.deadLetters).isEqualTo(0)
        assertThat(store.read()).isEmpty()
        cleanup(found)
    }

    /** The crash case: the second of two creates dies on the wire; the next poll re-sends both. Each lands exactly once. */
    @Test
    fun aPollThatCrashesBetweenTwoCreatesRecordsEachExactlyOnceOnRetry() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val crashing = CrashingTxApi(crashOn = 2).also { it.setAccessToken(token) }
        val crashHelper = SyncHelper(AccountConfigs(emptyList()), token, AboutApi(url, null, config), crashing, accountsApi, linksApi)
        val creates = listOf(plaid(plaidA, "c1$run", 11.0), plaid(plaidA, "c2$run", 12.0))

        assertThatThrownBy { runBlocking { poll(orchestrator(store, crashing, crashHelper), created = creates) } }
            .isInstanceOf(java.io.IOException::class.java)
        println("LINKLIVE crash: after the crash ${summary(mine("c1$run", "c2$run"))}")
        assertThat(held("c1$run", "c2$run")).containsExactly("c1$run")

        val retry = poll(orchestrator(store), created = creates)
        val found = mine("c1$run", "c2$run")
        println("LINKLIVE crash: after the retry ${summary(found)} created=${retry.fireflyCreated}")
        assertThat(found).hasSize(2)
        assertThat(held("c1$run", "c2$run")).containsExactlyInAnyOrder("c1$run", "c2$run")
        assertThat(retry.fireflyCreated).isEqualTo(1)
        assertThat(store.read()).isEmpty()
        cleanup(found)
    }

    /** Two banks, one transfer, whichever leg arrives first and whether or not they arrive in the same sync. */
    @Test
    fun aTransferBetweenTwoBanksIsOneTransactionWithTwoLinksInAnyArrivalOrder() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val done = mutableListOf<List<TransactionRead>>()

        // same sync
        poll(orchestrator, created = listOf(plaid(plaidA, "s-wd$run", 50.0), plaid(plaidB, "s-dep$run", -50.0)))
        val same = mine("s-wd$run", "s-dep$run")
        println("LINKLIVE transfer same sync: ${summary(same)}")
        assertThat(same).hasSize(1)
        assertThat(same.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(linksOf(same.single())).containsExactlyInAnyOrder(
            PlaidLink("s-wd$run", PlaidLinkLeg.source, plaidA), PlaidLink("s-dep$run", PlaidLinkLeg.destination, plaidB),
        )
        done.add(same)

        // the withdrawal first, the deposit in a later sync
        poll(orchestrator, created = listOf(plaid(plaidA, "w-wd$run", 61.0)))
        assertThat(mine("w-wd$run").single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.withdrawal)
        poll(orchestrator, created = listOf(plaid(plaidB, "w-dep$run", -61.0)))
        val wFirst = mine("w-wd$run", "w-dep$run")
        println("LINKLIVE transfer withdrawal first: ${summary(wFirst)}")
        assertThat(wFirst).hasSize(1)
        assertThat(wFirst.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(linksOf(wFirst.single())).containsExactlyInAnyOrder(
            PlaidLink("w-wd$run", PlaidLinkLeg.source, plaidA), PlaidLink("w-dep$run", PlaidLinkLeg.destination, plaidB),
        )
        done.add(wFirst)

        // the deposit first, the withdrawal in a later sync
        poll(orchestrator, created = listOf(plaid(plaidB, "d-dep$run", -72.0)))
        poll(orchestrator, created = listOf(plaid(plaidA, "d-wd$run", 72.0)))
        val dFirst = mine("d-wd$run", "d-dep$run")
        println("LINKLIVE transfer deposit first: ${summary(dFirst)}")
        assertThat(dFirst).hasSize(1)
        assertThat(dFirst.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(linksOf(dFirst.single())).containsExactlyInAnyOrder(
            PlaidLink("d-wd$run", PlaidLinkLeg.source, plaidA), PlaidLink("d-dep$run", PlaidLinkLeg.destination, plaidB),
        )
        done.add(dFirst)

        // a retry of either late leg changes nothing
        poll(orchestrator, created = listOf(plaid(plaidA, "d-wd$run", 72.0)))
        assertThat(mine("d-wd$run", "d-dep$run")).hasSize(1)
        assertThat(store.read()).isEmpty()
        done.forEach { cleanup(it) }
    }

    /** A transfer POST where one leg is already in Firefly: nothing is written, and the connector creates the other leg alone. */
    @Test
    fun aTransferWhoseOneLegIsAlreadyInFireflyCreatesTheOtherLegOnItsOwn() = runBlocking<Unit> {
        creds()
        val single = FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.withdrawal, sourceId = accountA, amount = "20", description = "LT partial $run",
            plaidLinks = listOf(PlaidLink("pa$run", PlaidLinkLeg.single, plaidA)),
        ).transactions.first()
        syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, single)))

        val transfer = FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.transfer, sourceId = accountA, destinationId = accountB, amount = "20",
            description = "LT partial pair $run",
            plaidLinks = listOf(PlaidLink("pa$run", PlaidLinkLeg.source, plaidA), PlaidLink("pb$run", PlaidLinkLeg.destination, plaidB)),
        ).transactions.first()
        val created = syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, transfer)))

        val found = mine("pa$run", "pb$run")
        println("LINKLIVE partial 409: created=$created ${summary(found)}")
        assertThat(created).isEqualTo(1)
        assertThat(found).hasSize(2)
        assertThat(found.map { it.attributes.transactions.single().type }).containsExactlyInAnyOrder(
            TransactionTypeProperty.withdrawal, TransactionTypeProperty.deposit,
        )
        cleanup(found)
    }

    /** Pending, then posted: one Firefly transaction, its link moved to the posted id, the pending id freed. */
    @Test
    fun aPendingTransactionBecomesItsPostedVersionInPlace() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)

        poll(orchestrator, created = listOf(plaid(plaidA, "pend$run", 42.0, pending = true)))
        val pending = mine("pend$run").single()
        poll(orchestrator, created = listOf(plaid(plaidA, "post$run", 42.5, pendingId = "pend$run")), deleted = listOf("pend$run"))

        val found = mine("pend$run", "post$run")
        println("LINKLIVE pending: ${summary(found)} was group ${pending.id}")
        assertThat(found).hasSize(1)
        assertThat(found.single().id).describedAs("updated in place").isEqualTo(pending.id)
        assertThat(held("pend$run", "post$run")).containsExactly("post$run")
        assertThat(found.single().attributes.transactions.single().amount.toDouble()).isEqualTo(42.5)
        assertThat(store.read()).isEmpty()
        cleanup(found)
    }

    /** A modify keeps the link; a removal deletes the transaction and frees the id for a later re-import. */
    @Test
    fun aModifyKeepsTheLinkAndARemovalFreesTheId() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)

        poll(orchestrator, created = listOf(plaid(plaidA, "m$run", 10.0)))
        poll(orchestrator, updated = listOf(plaid(plaidA, "m$run", 99.0)))
        val modified = mine("m$run").single()
        assertThat(modified.attributes.transactions.single().amount.toDouble()).isEqualTo(99.0)
        assertThat(linksOf(modified)).containsExactly(PlaidLink("m$run", PlaidLinkLeg.single, plaidA))

        poll(orchestrator, deleted = listOf("m$run"))
        assertThat(held("m$run")).isEmpty()
        poll(orchestrator, deleted = listOf("m$run"))
        poll(orchestrator, created = listOf(plaid(plaidA, "m$run", 10.0)))
        val again = mine("m$run")
        println("LINKLIVE remove: re-imported ${summary(again)}")
        assertThat(again).hasSize(1)
        cleanup(again)
    }

    /** The other bank's money is never deleted: removing one leg leaves the other as a plain transaction with a single link. */
    @Test
    fun theRemovalOfOneLegOfATransferLeavesTheOtherLegAsASingle() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)

        poll(orchestrator, created = listOf(plaid(plaidA, "r1wd$run", 50.0), plaid(plaidB, "r1dep$run", -50.0)))
        poll(orchestrator, deleted = listOf("r1dep$run"))
        val afterDestination = mine("r1wd$run", "r1dep$run")
        println("LINKLIVE leg removed (destination): ${summary(afterDestination)}")
        val p1 = afterDestination.single().attributes.transactions.single()
        assertThat(p1.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(p1.sourceId).isEqualTo(accountA)
        assertThat(p1.plaidLinks).containsExactly(PlaidLink("r1wd$run", PlaidLinkLeg.single, plaidA))
        assertThat(held("r1dep$run")).isEmpty()

        poll(orchestrator, created = listOf(plaid(plaidA, "r2wd$run", 30.0), plaid(plaidB, "r2dep$run", -30.0)))
        poll(orchestrator, deleted = listOf("r2wd$run"))
        val afterSource = mine("r2wd$run", "r2dep$run")
        println("LINKLIVE leg removed (source): ${summary(afterSource)}")
        val p2 = afterSource.single().attributes.transactions.single()
        assertThat(p2.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(p2.destinationId).isEqualTo(accountB)
        assertThat(p2.plaidLinks).containsExactly(PlaidLink("r2dep$run", PlaidLinkLeg.single, plaidB))

        // both legs removed in one sync: gone
        poll(orchestrator, created = listOf(plaid(plaidA, "r3wd$run", 20.0), plaid(plaidB, "r3dep$run", -20.0)))
        poll(orchestrator, deleted = listOf("r3wd$run", "r3dep$run"))
        assertThat(held("r3wd$run", "r3dep$run")).isEmpty()
        cleanup(afterDestination, afterSource)
    }

    /** A Plaid leg paired with a transaction the user entered by hand: removing the Plaid leg must not delete the user's side. */
    @Test
    fun aTransferPairedWithAManualTransactionKeepsTheManualSideWhenThePlaidLegIsRemoved() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val manual = mapper.readTree(
            api(
                "POST", "transactions",
                """{"apply_rules":false,"transactions":[{"type":"withdrawal","date":"${LocalDate.now()}","amount":"25","description":"LT manual $run","source_id":"$accountA","destination_name":"Somewhere $run"}]}"""
            )
        )["data"]["id"].asText()

        poll(orchestrator, created = listOf(plaid(plaidB, "mn-dep$run", -25.0)))
        val paired = mine("mn-dep$run")
        println("LINKLIVE manual pair: ${summary(paired)}")
        assertThat(paired.single().id).isEqualTo(manual)
        assertThat(paired.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)

        poll(orchestrator, deleted = listOf("mn-dep$run"))
        val after = txApi.getTransaction(manual).body().data
        println("LINKLIVE manual pair after removal: ${summary(listOf(after))}")
        val s = after.attributes.transactions.single()
        assertThat(s.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(s.sourceId).isEqualTo(accountA)
        assertThat(s.plaidLinks).isEmpty()
        assertThat(held("mn-dep$run")).isEmpty()
        cleanup(listOf(after))
    }

    /** A sign flip changes the type and both accounts, keeps the link, and repeating it changes nothing. */
    @Test
    fun aSignFlipChangesTheTypeAndTheAccountsInBothDirectionsAndKeepsTheLink() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)

        poll(orchestrator, created = listOf(plaid(plaidA, "flipw$run", 20.0)))
        poll(orchestrator, updated = listOf(plaid(plaidA, "flipw$run", -20.0)))
        val w = mine("flipw$run").single().attributes.transactions.single()
        println("LINKLIVE flip w->d: ${w.type} ${w.sourceId}/${w.sourceName} -> ${w.destinationId}")
        assertThat(w.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(w.destinationId).isEqualTo(accountA)
        assertThat(w.sourceId).isNotEqualTo(accountA)
        assertThat(w.plaidLinks).containsExactly(PlaidLink("flipw$run", PlaidLinkLeg.single, plaidA))

        poll(orchestrator, created = listOf(plaid(plaidA, "flipd$run", -45.0)))
        poll(orchestrator, updated = listOf(plaid(plaidA, "flipd$run", 45.0)))
        poll(orchestrator, updated = listOf(plaid(plaidA, "flipd$run", 45.0)))
        val d = mine("flipd$run").single().attributes.transactions.single()
        assertThat(d.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(d.sourceId).isEqualTo(accountA)
        assertThat(d.destinationId).isNotEqualTo(accountA)
        cleanup(mine("flipw$run"), mine("flipd$run"))
    }

    /** What the user edited in Firefly (description, reconciled flag, own tags, category) survives a Plaid update. */
    @Test
    fun aPlaidUpdateKeepsTheUsersEdits() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        poll(orchestrator, created = listOf(plaid(plaidA, "edit$run", 15.0)))
        val group = mine("edit$run").single().id
        api("PUT", "transactions/$group", """{"apply_rules":false,"transactions":[{"transaction_journal_id":"${mine("edit$run").single().attributes.transactions.single().transactionJournalId}","description":"my words","category_name":"Mine $run","tags":["user-tag"],"reconciled":true}]}""")

        poll(orchestrator, updated = listOf(plaid(plaidA, "edit$run", 16.0)))

        val s = mine("edit$run").single().attributes.transactions.single()
        println("LINKLIVE edits kept: ${s.description} ${s.categoryName} ${s.tags} reconciled=${s.reconciled} amount=${s.amount}")
        assertThat(s.amount.toDouble()).isEqualTo(16.0)
        assertThat(s.description).isEqualTo("my words")
        assertThat(s.categoryName).isEqualTo("Mine $run")
        assertThat(s.tags).contains("user-tag")
        assertThat(s.reconciled).isTrue()
        assertThat(s.plaidLinks).containsExactly(PlaidLink("edit$run", PlaidLinkLeg.single, plaidA))
        cleanup(mine("edit$run"))
    }

    /** A write Firefly really rejects (422) is still dead-lettered; a 409 never is. */
    @Test
    fun aRejectedWriteIsDeadLetteredByItsPlaidId() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        val unmapped = mapOf("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" to 99999)
        whenever(plaidSyncService.processPlaidTransactions(any(), any()))
            .thenReturn(PlaidTransactionResult(listOf(plaid("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "dl$run", 5.0)), listOf(), listOf()))
        val result = orchestrator.processTransactions(unmapped, sequenceOf(Pair("tok-12345678", unmapped.keys.toList())), mutableMapOf())

        println("LINKLIVE dead letter: ${store.read().map { it.key + ":" + it.message.take(60) }}")
        assertThat(store.read().map { it.key }).containsExactly("dl$run")
        assertThat(result.deadLetters).isEqualTo(1)
        assertThat(held("dl$run")).isEmpty()
    }

    /** Both legs of a transfer flip direction in one sync: accounts swap once and so do the links' legs. */
    @Test
    fun aTransferFlipsItsLegsWhenBothBanksAgree() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val orchestrator = orchestrator(store)
        poll(orchestrator, created = listOf(plaid(plaidA, "fw$run", 40.0), plaid(plaidB, "fd$run", -40.0)))

        val one = poll(orchestrator, updated = listOf(plaid(plaidA, "fw$run", -40.0)))
        val unchanged = mine("fw$run", "fd$run").single().attributes.transactions.single()
        assertThat(unchanged.sourceId).isEqualTo(accountA)
        assertThat(one.transfersNeedingReview).isEqualTo(1)

        val both = poll(orchestrator, updated = listOf(plaid(plaidA, "fw$run", -41.0), plaid(plaidB, "fd$run", 41.0)))
        val flipped = mine("fw$run", "fd$run")
        println("LINKLIVE flip: ${summary(flipped)}")
        val s = flipped.single().attributes.transactions.single()
        assertThat(s.sourceId).isEqualTo(accountB)
        assertThat(s.destinationId).isEqualTo(accountA)
        assertThat(s.plaidLinks).containsExactlyInAnyOrder(
            PlaidLink("fw$run", PlaidLinkLeg.destination, plaidA), PlaidLink("fd$run", PlaidLinkLeg.source, plaidB),
        )
        assertThat(both.transfersNeedingReview).isEqualTo(0)
        cleanup(flipped)
    }

    /** An update of a Firefly transaction that no longer exists is dropped, not kept forever. */
    @Test
    fun anUpdateOfAFireflyTransactionThatIsGoneIsNotKept() = runBlocking<Unit> {
        creds()
        val store = DeadLetterStore(dir.toString())
        val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", linksApi, store)
        val split = FireflyFixtures.getTransaction(type = TransactionTypeProperty.withdrawal, sourceId = accountA, amount = "5.0").transactions.first()

        service.processFireflyTransactionUpdates(listOf(), listOf(FireflyTransactionDto("99999999", split)), listOf())
        service.retryDeadLetters()

        assertThat(store.read()).isEmpty()
    }

    /** 500 ids in one lookup is a 414 from Firefly's server; the connector chunks smaller, and a bigger call is refused locally. */
    @Test
    fun aLookupOfMoreThanFiveHundredIdsIsChunked() = runBlocking<Unit> {
        creds()
        val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", linksApi)
        val ids = (1..1203).map { "nope$run$it" }
        assertThat(service.heldPlaidIds(ids)).isEmpty()
        assertThatThrownBy { runBlocking { linksApi.lookupPlaidLinks(ids) } }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(linksApi.lookupPlaidLinks(ids.take(PlaidLinksApi.MAX_IDS)).body().data).isEmpty()
    }

    /** The stock Firefly (no link table) must stop the connector at startup. Needs LANTERN_LIVE_STOCK_FIREFLY_URL and _TOKEN. */
    @Test
    @EnabledIfEnvironmentVariable(named = "LANTERN_LIVE_STOCK_FIREFLY_URL", matches = ".+")
    fun aStockFireflyStopsTheConnectorAtStartup() = runBlocking<Unit> {
        val stockUrl = System.getenv("LANTERN_LIVE_STOCK_FIREFLY_URL")
        val stockToken = System.getenv("LANTERN_LIVE_STOCK_FIREFLY_TOKEN")
        val helper = SyncHelper(
            AccountConfigs(emptyList()), stockToken, AboutApi(stockUrl, null, config), TransactionsApi(stockUrl, null, config),
            AccountsApi(stockUrl, null, config), PlaidLinksApi(stockUrl, null, config),
        )
        assertThatThrownBy { runBlocking { helper.setApiCreds() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("plaid-links")
        println("LINKLIVE stock firefly: refused at startup")
        // the fork itself passes the same check
        syncHelper.setApiCreds()
    }
}
