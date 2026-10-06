package net.djvk.fireflyPlaidConnector2.transactions

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers upstream issue #110: when a pending transaction posts, Plaid sends a "removed" for the pending id and an
 * "added" (with pending_transaction_id) for the posted one. The connector used to delete and re-create, losing
 * everything the user had set in Firefly.
 */
internal class PendingTransactionTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val plaidAccount = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun converter(pendingTag: String = "") = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = true,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
        pendingTag = pendingTag,
    )

    private fun existingFirefly(
        id: String,
        externalId: String,
        type: TransactionTypeProperty = TransactionTypeProperty.withdrawal,
        tags: List<String> = listOf("my-own-tag", "plaid-primary-cat-old"),
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type,
            amount = "10.0",
            sourceId = "1",
            destinationId = if (type == TransactionTypeProperty.transfer) "2" else null,
            externalId = externalId,
            tags = tags,
            categoryName = "Groceries (set by user)",
            currencyId = "5",
            currencyCode = "USD",
        ), ObjectLink()
    )

    private fun posted(amount: Double = 12.5, pendingId: String? = "pendingId") = PlaidFixtures.getPaymentTransaction(
        accountId = plaidAccount,
        transactionId = "postedId",
        pendingTransactionId = pendingId,
        name = "Corner Store",
        amount = amount,
        pending = false,
        personalFinanceCategory = PersonalFinanceCategoryEnum.FOOD_AND_DRINK_GROCERIES.toPersonalFinanceCategory(),
    )

    @Test
    fun postedTransactionUpdatesPendingFireflyTransactionInPlace() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", "plaid-pendingId")

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.deletes).describedAs("the pending Firefly transaction must not be deleted").isEmpty()
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.externalId).isEqualTo("plaid-postedId")
        assertThat(update.tx.amount).isEqualTo("12.5")
        assertThat(update.tx.currencyId).isEqualTo("5")
        // User's own tag kept, stale Plaid category tag replaced
        assertThat(update.tx.tags).containsExactly("my-own-tag", "plaid-primary-cat-food-and-drink")
        // The connector never sets category, so it must stay null (and be omitted from the JSON)
        assertThat(update.tx.categoryName).isNull()
    }

    @Test
    fun updateJsonOmitsFieldsTheConnectorDoesNotOwn() {
        val update = FireflyTransactionDto(
            "ff1",
            FireflyFixtures.getTransaction(amount = "1.0", sourceId = "1", externalId = "x").transactions.first()
        ).toTransactionUpdate()

        val mapper = ObjectMapper().also { ApiClient.JSON_DEFAULT.invoke(it) }
        val json = mapper.readTree(mapper.writeValueAsString(update))
        val split = json["transactions"][0]

        assertThat(split.has("category_name")).isFalse()
        assertThat(split.has("budget_id")).isFalse()
        assertThat(split.has("notes")).isFalse()
        assertThat(split.has("amount")).isTrue()
    }

    @Test
    fun fallsBackToDeleteAndCreateWhenPendingFireflyTransactionIsNotInFirefly() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf()
        )

        assertThat(result.creates).hasSize(1)
        assertThat(result.updates).isEmpty()
        assertThat(result.deletes).isEmpty() // nothing to delete: it was never imported
    }

    @Test
    fun fallsBackToDeleteAndCreateWhenPendingFireflyTransactionBecameATransfer() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", "plaid-pendingId", type = TransactionTypeProperty.transfer)

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        // Firefly can't change a transaction's type on update, so the old behavior still applies
        assertThat(result.updates).isEmpty()
        assertThat(result.creates).hasSize(1)
        assertThat(result.deletes).containsExactly("ff1")
    }

    @Test
    fun fallsBackToDeleteAndCreateWhenAmountChangedSign() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", "plaid-pendingId", type = TransactionTypeProperty.withdrawal)

        val result = converter().convertPollSync(
            accountMap, listOf(posted(amount = -12.5)), listOf(), listOf("pendingId"), listOf(existing)
        )

        assertThat(result.updates).isEmpty()
        assertThat(result.creates).hasSize(1)
        assertThat(result.deletes).containsExactly("ff1")
    }

    @Test
    fun noPendingLinkStillCreatesNormally() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(posted(pendingId = null)), listOf(), listOf(), listOf()
        )

        assertThat(result.creates).hasSize(1)
        assertThat(result.updates).isEmpty()
    }

    @Test
    fun pendingTransactionsAreNeverMatchedAsTransfers() = runBlocking<Unit> {
        val out = PlaidFixtures.getPaymentTransaction(
            accountId = plaidAccount, transactionId = "pOut", pendingTransactionId = null,
            amount = 100.0, pending = true,
        )
        val into = PlaidFixtures.getPaymentTransaction(
            accountId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", transactionId = "pIn", pendingTransactionId = null,
            amount = -100.0, pending = true,
        )

        val pendingResult = converter().convertPollSync(accountMap, listOf(out, into), listOf(), listOf(), listOf())
        assertThat(pendingResult.creates.map { it.tx.type })
            .containsExactlyInAnyOrder(TransactionTypeProperty.withdrawal, TransactionTypeProperty.deposit)

        // The same pair, once posted, is still matched as a transfer
        val settledResult = converter().convertPollSync(
            accountMap, listOf(out.copy(pending = false), into.copy(pending = false)), listOf(), listOf(), listOf()
        )
        assertThat(settledResult.creates.map { it.tx.type }).containsExactly(TransactionTypeProperty.transfer)
    }

    @Test
    fun pendingTagIsAddedWhilePendingAndRemovedWhenPosted() = runBlocking<Unit> {
        val conv = converter(pendingTag = "pending")
        val pendingTx = posted(pendingId = null).copy(pending = true, transactionId = "pendingId")

        val created = conv.convertPollSync(accountMap, listOf(pendingTx), listOf(), listOf(), listOf())
        assertThat(created.creates.single().tx.tags).contains("pending")

        val existing = existingFirefly("ff1", "plaid-pendingId", tags = listOf("pending", "my-own-tag"))
        val promoted = conv.convertPollSync(accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing))
        assertThat(promoted.updates.single().tx.tags).doesNotContain("pending").contains("my-own-tag")
    }

    @Test
    fun plaidModifiedUpdatePreservesUserTags() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", "plaid-modId", tags = listOf("my-own-tag"))
        val modified = posted(pendingId = null).copy(transactionId = "modId")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(existing))

        assertThat(result.updates.single().tx.tags).contains("my-own-tag", "plaid-primary-cat-food-and-drink")
    }
}
