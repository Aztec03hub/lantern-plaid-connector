package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
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

/**
 * Runs the connector's real Firefly code against a THROWAWAY Firefly instance. Skipped unless the environment names
 * one, so the normal build never touches a real Firefly:
 *
 *   LANTERN_LIVE_FIREFLY_URL=http://127.0.0.1:8111 LANTERN_LIVE_FIREFLY_TOKEN=... \
 *   LANTERN_LIVE_ACCOUNT_A=<asset account id> LANTERN_LIVE_ACCOUNT_B=<another asset account id> ./gradlew test --tests '*LiveFireflyTest*'
 *
 * Never point this at a Firefly that holds real data: it creates and deletes transactions.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_LIVE_FIREFLY_URL", matches = ".+")
internal class LiveFireflyTest {
    private val url = System.getenv("LANTERN_LIVE_FIREFLY_URL")
    private val token = System.getenv("LANTERN_LIVE_FIREFLY_TOKEN")
    private val accountA = System.getenv("LANTERN_LIVE_ACCOUNT_A")
    private val accountB = System.getenv("LANTERN_LIVE_ACCOUNT_B")
    private val run = System.currentTimeMillis().toString()

    private val config = ApiConfiguration().getClientConfig()
    private val txApi = TransactionsApi(url, null, config)
    private val searchApi = SearchApi(url, null, config)
    private val syncHelper = SyncHelper(
        AccountConfigs(emptyList()), token, AboutApi(url, null, config), txApi, AccountsApi(url, null, config), searchApi
    )
    private val service = FireflyTransactionService(txApi, syncHelper, 30, "UTC", searchApi)

    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "UTC",
        transferMatchWindowDays = 3L, txStyle = TransactionStyleConfig(null),
    )

    private suspend fun creds() {
        txApi.setAccessToken(token); searchApi.setAccessToken(token)
    }

    private fun today(): OffsetDateTime = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC)

    private suspend fun find(plaidId: String): TransactionRead =
        service.fetchMissingByPlaidId(listOf(plaidId), listOf()).single()

    @Test
    fun transferConversionIsInPlaceAndRetrySafe() = runBlocking<Unit> {
        creds()
        val xId = "x$run"
        val yId = "y$run"
        syncHelper.insertIntoFirefly(
            FireflyTransactionDto(
                null,
                TransactionSplit(
                    TransactionTypeProperty.deposit, today(), "50.00", "X leg $run",
                    sourceId = null, sourceName = "Unknown Source", destinationId = accountA, externalId = "plaid-$xId",
                    tags = listOf("user-tag"), reconciled = false, order = 0,
                )
            )
        )
        val existing = find(xId)

        // The real Firefly JSON, parsed by the real generated models, goes through the real converter: a Plaid
        //  create for the other leg turns the existing deposit into a transfer.
        val yLeg = PlaidFixtures.getPaymentTransaction(
            accountId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", transactionId = yId, pendingTransactionId = null,
            amount = 50.0, date = LocalDate.now(),
        )
        val accountMap = mapOf(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" to accountA.toInt(), "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" to accountB.toInt()
        )
        val result = converter.convertPollSync(accountMap, listOf(yLeg), listOf(), listOf(), listOf(existing))
        assertThat(result.updates).hasSize(1)
        assertThat(result.creates).isEmpty()

        // H1: apply it twice, as a retried iteration would. Neither may fail, and there is still exactly one.
        service.processFireflyTransactionUpdates(result.creates, result.updates, result.deletes)
        service.processFireflyTransactionUpdates(result.creates, result.updates, result.deletes)

        val converted = find(yId)
        val split = converted.attributes.transactions.single()
        assertThat(converted.id).isEqualTo(existing.id)
        assertThat(split.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(split.sourceId).isEqualTo(accountB)
        assertThat(split.destinationId).isEqualTo(accountA)
        assertThat(split.tags).contains("user-tag")
        // R2 H1 + R3 L1: both Plaid ids are kept, so a lookup by either finds this same transfer. The destination
        //  leg's id (the old deposit, on account A) is the external id and the source leg's (Y, on B) the internal
        //  reference, which is what lets the removal of one leg leave the other on the right account.
        assertThat(split.externalId).isEqualTo("plaid-$xId")
        assertThat(split.internalReference).isEqualTo("plaid-$yId")
        assertThat(service.fetchMissingByPlaidId(listOf(xId), listOf()).map { it.id }).containsExactly(converted.id)

        // H2 + H3: the retried Plaid create for Y is recognised from what Firefly really returns
        val retry = converter.convertPollSync(accountMap, listOf(yLeg), listOf(), listOf(), listOf(converted))
        assertThat(retry.creates).isEmpty()
        assertThat(retry.updates).isEmpty()

        // Delete twice: the second hits a 404 and must not fail
        syncHelper.deleteBatchInFirefly(listOf(converted.id))
        syncHelper.deleteBatchInFirefly(listOf(converted.id))
        assertThat(service.fetchMissingByPlaidId(listOf(yId), listOf())).isEmpty()
    }

    @Test
    fun aPlaidUpdateKeepsReconciledAndTheUsersDescription() = runBlocking<Unit> {
        creds()
        val id = "r$run"
        syncHelper.insertIntoFirefly(
            FireflyTransactionDto(
                null,
                TransactionSplit(
                    TransactionTypeProperty.withdrawal, today(), "20.00", "Plaid description",
                    sourceId = accountA, destinationId = null, destinationName = "Merchant", externalId = "plaid-$id", reconciled = false, order = 0,
                )
            )
        )
        val created = find(id)
        // The user reconciles it and rewords it in Firefly
        txApi.updateTransaction(
            created.id,
            created.attributes.transactions.single().copy(description = "My own words", reconciled = true).let {
                FireflyTransactionDto(created.id, it).toTransactionUpdate()
            }
        )
        val userEdited = find(id)
        assertThat(userEdited.attributes.transactions.single().reconciled).isTrue()

        // Plaid then reports the transaction as modified (new amount)
        val modified = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", transactionId = id, pendingTransactionId = null,
            name = "Plaid description changed", amount = 21.0, date = LocalDate.now(),
        )
        val accountMap = mapOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" to accountA.toInt())
        val result = converter.convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(userEdited))
        service.processFireflyTransactionUpdates(result.creates, result.updates, result.deletes)

        val after = find(id).attributes.transactions.single()
        assertThat(after.amount.toDouble()).isEqualTo(21.0)
        assertThat(after.reconciled).describedAs("M2: still reconciled").isTrue()
        assertThat(after.description).describedAs("M2: user's description kept").isEqualTo("My own words")

        syncHelper.deleteBatchInFirefly(listOf(created.id))
    }
}
