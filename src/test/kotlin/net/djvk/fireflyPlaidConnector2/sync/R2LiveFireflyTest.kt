package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * Live checks for review R2 (H1-R2, H2-R2, L4-R2) against a THROWAWAY Firefly. Same environment variables and the
 * same warning as [LiveFireflyTest]: never point this at a Firefly that holds real data.
 *
 * Each test drives the connector's own code the way one poll iteration does (read Firefly, convert, write), so a
 * "retry" here is the same sequence of calls the orchestrator makes.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_LIVE_FIREFLY_URL", matches = ".+")
internal class R2LiveFireflyTest {
    private val url = System.getenv("LANTERN_LIVE_FIREFLY_URL")
    private val token = System.getenv("LANTERN_LIVE_FIREFLY_TOKEN")
    private val accountA = System.getenv("LANTERN_LIVE_ACCOUNT_A")
    private val accountB = System.getenv("LANTERN_LIVE_ACCOUNT_B")
    private val run = System.currentTimeMillis().toString()
    private val plaidA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val plaidB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val accountMap = mapOf(plaidA to accountA.toInt(), plaidB to accountB.toInt())

    private val config = ApiConfiguration().getClientConfig()
    private val txApi = TransactionsApi(url, null, config)
    private val searchApi = SearchApi(url, null, config)
    private val plaidLinksApi = PlaidLinksApi(url, null, config)
    private val accountsApi = AccountsApi(url, null, config)
    private val syncHelper = SyncHelper(AccountConfigs(emptyList()), token, AboutApi(url, null, config), txApi, accountsApi, plaidLinksApi)
    private val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", plaidLinksApi)
    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "UTC",
        transferMatchWindowDays = 3L, txStyle = TransactionStyleConfig(null),
    )

    private suspend fun creds() {
        txApi.setAccessToken(token); searchApi.setAccessToken(token); plaidLinksApi.setAccessToken(token); accountsApi.setAccessToken(token)
    }

    private fun today(): OffsetDateTime = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC)

    private fun plaid(account: String, id: String, amount: Double, name: String = "R2 $id"): PlaidTransaction =
        PlaidFixtures.getPaymentTransaction(
            accountId = account, transactionId = id, pendingTransactionId = null, amount = amount,
            date = LocalDate.now(), name = name,
        )

    /** One poll iteration's Firefly half: read, convert, write. */
    private suspend fun iteration(
        created: List<PlaidTransaction> = listOf(),
        updated: List<PlaidTransaction> = listOf(),
        deleted: List<String> = listOf(),
    ) {
        val window = service.fetchExistingFireflyTransactions()
        val referenced = updated.map { it.transactionId } + deleted + created.map { it.transactionId }
        val existing = window + service.fetchMissingByPlaidId(referenced, window)
        val result = converter.convertPollSync(accountMap, created, updated, deleted, existing)
        service.processFireflyTransactionUpdates(result.creates, result.updates, result.deletes)
    }

    /** Everything this test run has put in Firefly, by Plaid id fragment. */
    private suspend fun mine(vararg ids: String): List<TransactionRead> =
        service.fetchExistingFireflyTransactions().filter { read ->
            read.attributes.transactions.any { s -> ids.any { id -> s.externalId == "plaid-$id" || s.internalReference == "plaid-$id" } }
        }

    private fun summary(reads: List<TransactionRead>) = reads.map { r ->
        val s = r.attributes.transactions.single()
        "${s.type}:${s.externalId}:ref=${s.internalReference}:${s.amount}:${s.sourceId}->${s.destinationId}"
    }

    /** H1-R2: one bank fails on the retry, so a transfer's legs arrive in different iterations. */
    @Test
    fun aTransferWhoseLegsArriveInDifferentIterationsIsRecordedOnce() = runBlocking<Unit> {
        creds()
        val wd = plaid(plaidA, "wd$run", 50.0)
        val dep = plaid(plaidB, "dep$run", -50.0)

        iteration(created = listOf(wd, dep)) // N: both legs, one transfer inserted; a later step fails, no cursor commit
        iteration(created = listOf(wd)) // N+1: the other bank is down, only the withdrawal leg comes
        iteration(created = listOf(dep)) // N+2: the other bank recovers

        val found = mine("wd$run", "dep$run")
        println("R2LIVE H1 ${summary(found)}")
        assertThat(found).describedAs(summary(found).toString()).hasSize(1)
        val split = found.single().attributes.transactions.single()
        assertThat(split.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(split.amount.toDouble()).isEqualTo(50.0)

        syncHelper.deleteBatchInFirefly(found.map { it.id })
    }

    /** H1-R2, the conversion path: the existing leg's id must survive being converted, and be found again. */
    @Test
    fun aConvertedLegIsStillFoundByItsOldPlaidId() = runBlocking<Unit> {
        creds()
        val wd = plaid(plaidA, "cwd$run", 30.0)
        val dep = plaid(plaidB, "cdep$run", -30.0)

        iteration(created = listOf(wd)) // a lone withdrawal is imported first
        iteration(created = listOf(dep)) // then its other leg arrives and converts it in place
        // A retry of the first leg must not create a withdrawal again. Its description differs from the first
        //  import (Plaid renamed it), so Firefly's content hash can't be what saves us.
        iteration(created = listOf(plaid(plaidA, "cwd$run", 30.0, name = "R2 renamed $run")))
        iteration(updated = listOf(plaid(plaidA, "cwd$run", 31.0), plaid(plaidB, "cdep$run", -31.0)))

        val found = mine("cwd$run", "cdep$run")
        println("R2LIVE H1b ${summary(found)}")
        assertThat(found).describedAs(summary(found).toString()).hasSize(1)
        assertThat(found.single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(found.single().attributes.transactions.single().amount.toDouble()).isEqualTo(31.0)

        syncHelper.deleteBatchInFirefly(found.map { it.id })
    }

    private suspend fun insertSingle(type: TransactionTypeProperty, id: String, amount: String): TransactionRead {
        syncHelper.insertIntoFirefly(
            FireflyTransactionDto(
                null,
                TransactionSplit(
                    type, today(), amount, "R2 $id",
                    sourceId = if (type == TransactionTypeProperty.withdrawal) accountA else null,
                    sourceName = if (type == TransactionTypeProperty.deposit) "Employer $run" else null,
                    destinationId = if (type == TransactionTypeProperty.deposit) accountA else null,
                    destinationName = if (type == TransactionTypeProperty.withdrawal) "Shop $run" else null,
                    externalId = "plaid-$id", reconciled = false, order = 0, tags = listOf("user-tag"),
                )
            )
        )
        return mine(id).single()
    }

    /** H2-R2: Plaid flips the sign of a transaction. Money that went out is now money that came in. */
    @Test
    fun aSignFlipChangesTheTypeAndTheAccountsInBothDirections() = runBlocking<Unit> {
        creds()

        val w = "flipw$run"
        insertSingle(TransactionTypeProperty.withdrawal, w, "20.00")
        iteration(updated = listOf(plaid(plaidA, w, -20.0)))
        val afterW = mine(w).single().attributes.transactions.single()
        println("R2LIVE H2 withdrawal->deposit ${afterW.type} ${afterW.sourceId}/${afterW.sourceName} -> ${afterW.destinationId}/${afterW.destinationName}")
        assertThat(afterW.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(afterW.destinationId).isEqualTo(accountA)
        assertThat(afterW.sourceId).describedAs("source must not be the asset account").isNotEqualTo(accountA)
        assertThat(afterW.sourceName).isNotEqualTo("FixerA")
        assertThat(afterW.tags).contains("user-tag")

        val d = "flipd$run"
        insertSingle(TransactionTypeProperty.deposit, d, "45.00")
        iteration(updated = listOf(plaid(plaidA, d, 45.0)))
        val afterD = mine(d).single().attributes.transactions.single()
        println("R2LIVE H2 deposit->withdrawal ${afterD.type} ${afterD.sourceId}/${afterD.sourceName} -> ${afterD.destinationId}/${afterD.destinationName}")
        assertThat(afterD.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(afterD.sourceId).isEqualTo(accountA)
        assertThat(afterD.destinationId).describedAs("destination must not be the asset account").isNotEqualTo(accountA)
        assertThat(afterD.destinationName).isNotEqualTo("FixerA")

        // The same update again (a retried iteration) changes nothing
        iteration(updated = listOf(plaid(plaidA, d, 45.0)))
        assertThat(mine(d).single().attributes.transactions.single().type).isEqualTo(TransactionTypeProperty.withdrawal)

        syncHelper.deleteBatchInFirefly(listOf(mine(w).single().id, mine(d).single().id))
    }

    /**
     * L4-R2 measurement (prints, asserts only that both strategies find everything): LANTERN_LIVE_L4_COUNT
     * (default 60) transactions dated a year ago, looked up by one search per id versus one ranged, paged list.
     */
    @Test
    fun lookupCostOfOldTransactions() = runBlocking<Unit> {
        creds()
        val n = System.getenv("LANTERN_LIVE_L4_COUNT")?.toInt() ?: 60
        val oldDate = LocalDate.now().minusDays(400)
        val ids = (1..n).map { "m$run-$it" }
        val insertStart = System.nanoTime()
        for (id in ids) {
            syncHelper.insertIntoFirefly(
                FireflyTransactionDto(
                    null,
                    TransactionSplit(
                        TransactionTypeProperty.withdrawal, oldDate.atStartOfDay().atOffset(ZoneOffset.UTC), "1.00", "R2 $id",
                        sourceId = accountA, destinationId = null, destinationName = "Shop $run", externalId = "plaid-$id", reconciled = false, order = 0,
                    )
                )
            )
        }
        println("R2LIVE L4 insert $n txs: ${(System.nanoTime() - insertStart) / 1_000_000} ms")

        val s1 = System.nanoTime()
        val bySearch = service.fetchMissingByPlaidId(ids, listOf())
        val searchMs = (System.nanoTime() - s1) / 1_000_000
        println("R2LIVE L4 per-id search: ${ids.size} lookups, found ${bySearch.size}, $searchMs ms (${searchMs / ids.size} ms each)")
        assertThat(bySearch).hasSize(n)

        val s2 = System.nanoTime()
        val byRange = service.fetchFireflyTransactionsBetween(oldDate.minusDays(1), oldDate.plusDays(1), 100)
        val rangeMs = (System.nanoTime() - s2) / 1_000_000
        println("R2LIVE L4 ranged list: found ${byRange.size}, $rangeMs ms")
        assertThat(byRange.count { r -> r.attributes.transactions.any { it.externalId in ids.map { id -> "plaid-$id" } } }).isEqualTo(n)

        syncHelper.deleteBatchInFirefly(byRange.map { it.id })
    }

    /** Does Firefly find a transaction by `internal_reference_is:`? The H1-R2 fix relies on it for old transactions. */
    @Test
    fun internalReferenceIsSearchableAndHoldsAPlaidId() = runBlocking<Unit> {
        creds()
        val id = "ref$run"
        syncHelper.insertIntoFirefly(
            FireflyTransactionDto(
                null,
                TransactionSplit(
                    TransactionTypeProperty.transfer, today(), "5.00", "R2 $id", sourceId = accountA, destinationId = accountB,
                    externalId = "plaid-$id-dst", internalReference = "plaid-$id-src", reconciled = false, order = 0,
                )
            )
        )
        val hit = searchApi.searchTransactions("internal_reference_is:\"plaid-$id-src\"", 1).body().data
        println("R2LIVE ref search hits=${hit.size}")
        assertThat(hit).hasSize(1)
        assertThat(hit.single().attributes.transactions.single().internalReference).isEqualTo("plaid-$id-src")
        syncHelper.deleteBatchInFirefly(listOf(hit.single().id))
    }
}
