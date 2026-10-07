package net.djvk.fireflyPlaidConnector2.transactions

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
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
        links: List<PlaidLink>,
        type: TransactionTypeProperty = TransactionTypeProperty.withdrawal,
        tags: List<String> = listOf("my-own-tag", "plaid-primary-cat-old"),
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type,
            amount = "10.0",
            sourceId = "1",
            destinationId = if (type == TransactionTypeProperty.transfer) "2" else null,
            plaidLinks = links,
            tags = tags,
            categoryName = "Groceries (set by user)",
            currencyId = "5",
            currencyCode = "USD",
        ), ObjectLink()
    )

    private fun single(plaidId: String) = listOf(PlaidLink(plaidId, PlaidLinkLeg.single, plaidAccount))

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
        val existing = existingFirefly("ff1", single("pendingId"))

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.deletes).describedAs("the pending Firefly transaction must not be deleted").isEmpty()
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.plaidLinks).describedAs("the pending id is replaced by the posted id")
            .containsExactly(PlaidLink("postedId", PlaidLinkLeg.single, plaidAccount))
        assertThat(update.tx.externalId).describedAs("the connector no longer writes external_id").isNull()
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

    /** M2-R2: deleting and re-creating would drop the transfer's other leg and its user data. */
    @Test
    fun aPendingTransactionThatBecameATransferIsUpdatedInPlaceWhenItPosts() = runBlocking<Unit> {
        val existing = existingFirefly(
            "ff1",
            listOf(PlaidLink("pendingId", PlaidLinkLeg.source, plaidAccount), PlaidLink("otherLeg", PlaidLinkLeg.destination, "bbb")),
            type = TransactionTypeProperty.transfer,
        )

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.deletes).isEmpty()
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.tx.plaidLinks).containsExactly(
            PlaidLink("postedId", PlaidLinkLeg.source, plaidAccount), PlaidLink("otherLeg", PlaidLinkLeg.destination, "bbb"),
        )
        // both accounts of the transfer are kept, and no type is sent (it stays a transfer)
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isEqualTo("2")
        assertThat(update.toTransactionUpdate().transactions!!.single().type).isNull()
    }

    /** The pending id is the transfer's second link (the destination leg); its leg and position are kept. */
    @Test
    fun aPendingIdOnATransfersDestinationLegIsMovedToThePostedId() = runBlocking<Unit> {
        val existing = existingFirefly(
            "ff1",
            listOf(PlaidLink("otherLeg", PlaidLinkLeg.source, "bbb"), PlaidLink("pendingId", PlaidLinkLeg.destination, plaidAccount)),
            type = TransactionTypeProperty.transfer,
        )

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        assertThat(result.creates).isEmpty()
        assertThat(result.deletes).isEmpty()
        val update = result.updates.single()
        assertThat(update.tx.plaidLinks).containsExactly(
            PlaidLink("otherLeg", PlaidLinkLeg.source, "bbb"), PlaidLink("postedId", PlaidLinkLeg.destination, plaidAccount),
        )
    }

    @Test
    fun fallsBackToDeleteAndCreateWhenAmountChangedSign() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", single("pendingId"), type = TransactionTypeProperty.withdrawal)

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

        val existing = existingFirefly("ff1", single("pendingId"), tags = listOf("pending", "my-own-tag"))
        val promoted = conv.convertPollSync(accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing))
        assertThat(promoted.updates.single().tx.tags).doesNotContain("pending").contains("my-own-tag")
    }

    @Test
    fun plaidModifiedUpdatePreservesUserTags() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", single("modId"), tags = listOf("my-own-tag"))
        val modified = posted(pendingId = null).copy(transactionId = "modId")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(existing))

        assertThat(result.updates.single().tx.tags).contains("my-own-tag", "plaid-primary-cat-food-and-drink")
        assertThat(result.updates.single().tx.plaidLinks).describedAs("a modify sends no links").isNull()
    }

    @Test
    fun aPendingTransactionIsNotPromotedWhenThePostedIdIsAlreadyIndexed() = runBlocking<Unit> {
        val pending = existingFirefly("ff1", single("pendingId"))
        // held by a transfer (not a pairing candidate, so nothing else skips the posted create first)
        val alreadyPosted = existingFirefly(
            "ff2",
            listOf(PlaidLink("postedId", PlaidLinkLeg.source, plaidAccount), PlaidLink("otherLeg", PlaidLinkLeg.destination, "x")),
            type = TransactionTypeProperty.transfer,
        )

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(pending, alreadyPosted)
        )

        assertThat(result.updates).describedAs("replacing the link would be refused (409), so no promotion").isEmpty()
        assertThat(result.creates).describedAs("the posted money is already on the transfer").isEmpty()
        assertThat(result.deletes).describedAs("the pending group goes with Plaid's removal").containsExactly("ff1")
    }

    @Test
    fun aModifyOfATransactionWithSeveralSplitsIsSkippedAndReported() = runBlocking<Unit> {
        val splits = existingFirefly("ff1", single("modId")).attributes.transactions +
                FireflyFixtures.getTransaction(amount = "3.0", sourceId = "1").transactions
        val multi = TransactionRead(
            "transactions", "ff1", net.djvk.fireflyPlaidConnector2.api.firefly.models.Transaction(transactions = splits), ObjectLink()
        )
        val modified = posted(pendingId = null).copy(transactionId = "modId")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(multi))

        assertThat(result.updates).isEmpty()
        assertThat(result.transfersNeedingReview).containsExactly("ff1")
    }

    private val updateMapper = ObjectMapper().also { ApiClient.JSON_DEFAULT.invoke(it) }

    private fun splitJson(dto: FireflyTransactionDto) =
        updateMapper.readTree(updateMapper.writeValueAsString(dto.toTransactionUpdate()))["transactions"][0]

    @Test
    fun anUpdateCarriesTheTransactionJournalIdOfTheExistingSplit() = runBlocking<Unit> {
        val existing = TransactionRead(
            "transactions", "ff1",
            FireflyFixtures.getTransaction(
                amount = "10.0", sourceId = "1", currencyId = "5", currencyCode = "USD",
                transactionJournalId = "j7", plaidLinks = single("modId"),
            ), ObjectLink()
        )
        val modified = posted(pendingId = null).copy(transactionId = "modId")

        val modify = converter().convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(existing))
        assertThat(modify.updates.single().tx.transactionJournalId).isEqualTo("j7")
        assertThat(splitJson(modify.updates.single())["transaction_journal_id"].asText()).isEqualTo("j7")

        val promoted = converter().convertPollSync(
            accountMap, listOf(posted().copy(transactionId = "postedId")), listOf(), listOf("pendingId"),
            listOf(existing.copy(attributes = existing.attributes.copy(
                transactions = listOf(existing.attributes.transactions.single().copy(plaidLinks = single("pendingId")))
            )))
        )
        assertThat(promoted.updates.single().tx.transactionJournalId).isEqualTo("j7")
    }

    @Test
    fun aModifyUpdateJsonHasNoPlaidLinksKey() = runBlocking<Unit> {
        val existing = existingFirefly("ff1", single("modId"))
        val modified = posted(pendingId = null).copy(transactionId = "modId")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(modified), listOf(), listOf(existing))

        assertThat(splitJson(result.updates.single()).has("plaid_links")).isFalse()
    }

    @Test
    fun anUpdateThatSendsLinksSerializesTheFullList() = runBlocking<Unit> {
        val existing = existingFirefly(
            "ff1",
            listOf(PlaidLink("otherLeg", PlaidLinkLeg.source, "bbb"), PlaidLink("pendingId", PlaidLinkLeg.destination, plaidAccount)),
            type = TransactionTypeProperty.transfer,
        )

        val result = converter().convertPollSync(
            accountMap, listOf(posted()), listOf(), listOf("pendingId"), listOf(existing)
        )

        val links = splitJson(result.updates.single())["plaid_links"]
        assertThat(links.size()).isEqualTo(2)
        assertThat(links[0]["plaid_transaction_id"].asText()).isEqualTo("otherLeg")
        assertThat(links[0]["leg"].asText()).isEqualTo("source")
        assertThat(links[1]["plaid_transaction_id"].asText()).isEqualTo("postedId")
        assertThat(links[1]["leg"].asText()).isEqualTo("destination")
        assertThat(links[1]["plaid_account_id"].asText()).isEqualTo(plaidAccount)
    }
}
