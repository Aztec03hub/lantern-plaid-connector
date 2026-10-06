package net.djvk.fireflyPlaidConnector2.transactions

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ObjectLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Review R3 on the converter: the removal of one leg of a two-leg transfer (L1-R3, reviewer probes p1 and p2) and the
 * roles of the two ids that make it possible.
 */
internal class R3ConverterTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // Firefly account 1
    private val accountB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // Firefly account 2

    private fun converter() = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
    )

    private fun existing(
        id: String,
        externalId: String,
        type: TransactionTypeProperty,
        internalReference: String? = null,
        amount: String = "50.0",
    ) = TransactionRead(
        "transactions", id,
        FireflyFixtures.getTransaction(
            type = type, amount = amount,
            sourceId = if (type == TransactionTypeProperty.deposit) null else "1",
            destinationId = if (type == TransactionTypeProperty.withdrawal) null else "2",
            externalId = externalId, internalReference = internalReference,
            description = "User words", currencyId = "5", currencyCode = "USD",
            sourceName = if (type == TransactionTypeProperty.deposit) "Employer" else null,
            destinationName = if (type == TransactionTypeProperty.withdrawal) "Shop" else null,
        ), ObjectLink()
    )

    private fun plaid(account: String, id: String, amount: Double) =
        PlaidFixtures.getPaymentTransaction(accountId = account, transactionId = id, pendingTransactionId = null, amount = amount, name = "Plaid $id")

    private val transfer get() = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

    /** p1: removing the destination leg used to delete the whole transfer, and the source leg's outflow with it. */
    @Test
    fun theRemovalOfTheDestinationLegTurnsTheTransferIntoAWithdrawalForTheSourceLeg() = runBlocking<Unit> {
        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("dep"), listOf(transfer))

        assertThat(result.deletes).isEmpty()
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff1")
        assertThat(update.changesType).isTrue()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isNull()
        assertThat(update.tx.destinationName).isEqualTo("Unknown Transfer Recipient")
        assertThat(update.tx.externalId).isEqualTo("plaid-wd")
        assertThat(update.tx.amount).isEqualTo("50.0")
        assertThat(update.tx.description).isEqualTo("User words")
    }

    /** p1 continued: the leg is re-added under a new id. Money stays right: one withdrawal for the survivor, one deposit. */
    @Test
    fun aRemovedAndReAddedLegDoesNotLoseOrDoubleMoney() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep2", -50.0)), listOf(), listOf("dep"), listOf(transfer)
        )

        assertThat(result.deletes).isEmpty()
        assertThat(result.creates.single().tx.externalId).isEqualTo("plaid-dep2")
        assertThat(result.creates.single().tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(result.updates.single().tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
    }

    /** Both legs removed in one sync: one delete, not two and not a survivor. */
    @Test
    fun theRemovalOfBothLegsDeletesTheTransferOnce() = runBlocking<Unit> {
        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("wd", "dep"), listOf(transfer))

        assertThat(result.deletes).containsExactly("ff1")
        assertThat(result.updates).isEmpty()
    }

    /** The surviving leg's new amount, said earlier in the same sync, is not reverted by the removal's update. */
    @Test
    fun aLegUpdatedInTheSameSyncKeepsItsNewAmountWhenTheOtherLegIsRemoved() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(), listOf(plaid(accountB, "dep", -60.0)), listOf("wd"), listOf(transfer)
        )

        val update = result.updates.single()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.tx.amount).isEqualTo("60.0")
    }

    /** An older-build transfer knows one leg only: nothing says where the other money is, so the old behaviour stays. */
    @Test
    fun aTransferWithOnlyOneKnownLegIsStillDeletedWhenThatLegIsRemoved() = runBlocking<Unit> {
        val legacy = existing("ff1", "plaid-dep", TransactionTypeProperty.transfer)

        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("dep"), listOf(legacy))

        assertThat(result.deletes).containsExactly("ff1")
    }

    /**
     * The roles hold for a conversion too: an existing DEPOSIT (account 2) paired with a Plaid withdrawal leg (account 1)
     * has the deposit's id as `external_id` (destination leg) and the Plaid leg's as `internal_reference`.
     */
    @Test
    fun convertingAnExistingDepositKeepsTheDestinationLegAsTheExternalId() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountA, "wd", 50.0)), listOf(), listOf(),
            listOf(existing("ff2", "plaid-dep", TransactionTypeProperty.deposit))
        )

        val update = result.updates.single()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isEqualTo("2")
        assertThat(update.tx.externalId).isEqualTo("plaid-dep")
        assertThat(update.tx.internalReference).isEqualTo("plaid-wd")
    }

    /** ...and the removal that follows keeps the right account: the deposit leg survives on account 2. */
    @Test
    fun afterAConversionOfADepositTheRemovalOfTheNewLegLeavesTheDepositOnItsAccount() = runBlocking<Unit> {
        val converted = existing("ff2", "plaid-dep", TransactionTypeProperty.transfer, internalReference = "plaid-wd")

        val result = converter().convertPollSync(accountMap, listOf(), listOf(), listOf("wd"), listOf(converted))

        val update = result.updates.single()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.tx.destinationId).isEqualTo("2")
    }

    /** A withdrawal converted by a Plaid deposit leg: the Plaid leg is the destination leg, so it is the external id. */
    @Test
    fun convertingAnExistingWithdrawalKeepsThePlaidLegAsTheExternalId() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(),
            listOf(existing("ff1", "plaid-wd", TransactionTypeProperty.withdrawal))
        )

        val update = result.updates.single()
        assertThat(update.tx.externalId).isEqualTo("plaid-dep")
        assertThat(update.tx.internalReference).isEqualTo("plaid-wd")
    }
}
