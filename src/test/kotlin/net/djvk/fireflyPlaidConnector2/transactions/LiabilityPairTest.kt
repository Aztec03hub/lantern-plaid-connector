package net.djvk.fireflyPlaidConnector2.transactions

import kotlinx.coroutines.runBlocking
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
 * Firefly 6.7.7 rejects a transfer between an asset and a liability account (422 "Could not find a valid destination
 * account"). A pair with a liability end is a withdrawal (payment) or deposit (cash advance, disbursement) instead.
 */
internal class LiabilityPairTest {
    private val accountMap = PlaidFixtures.getStandardAccountMapping()
    private val accountA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // Firefly account 1
    private val accountB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // Firefly account 2

    private fun converter(liabilities: Set<String>) = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "plaid-primary-cat-",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "plaid-detailed-cat-",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
    ).also { it.liabilityAccountIds = liabilities }

    private fun plaid(account: String, id: String, amount: Double) =
        PlaidFixtures.getPaymentTransaction(accountId = account, transactionId = id, pendingTransactionId = null, amount = amount, name = "Plaid $id")

    private val srcLink = PlaidLink("wd", PlaidLinkLeg.source, accountA)
    private val dstLink = PlaidLink("dep", PlaidLinkLeg.destination, accountB)

    /** A withdrawal from account 1 into account 2 (a liability), made from two Plaid legs. */
    private val paidLiability = TransactionRead(
        "transactions", "ff1",
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.withdrawal, amount = "50.0", sourceId = "1", destinationId = "2",
            plaidLinks = listOf(srcLink, dstLink), description = "Card payment", currencyId = "5", currencyCode = "USD",
        ), ObjectLink()
    )

    private suspend fun batchType(liabilities: Set<String>, a: Double, b: Double) =
        converter(liabilities).convertBatchSync(
            listOf(plaid(accountA, "wd", a), plaid(accountB, "dep", b)), accountMap
        ).single().tx.type

    @Test
    fun aPaymentFromAnAssetToALiabilityIsAWithdrawal() = runBlocking<Unit> {
        assertThat(batchType(setOf("2"), 50.0, -50.0)).isEqualTo(TransactionTypeProperty.withdrawal)
    }

    @Test
    fun moneyOutOfALiabilityIntoAnAssetIsADeposit() = runBlocking<Unit> {
        assertThat(batchType(setOf("1"), 50.0, -50.0)).isEqualTo(TransactionTypeProperty.deposit)
    }

    @Test
    fun twoAssetsAndTwoLiabilitiesStayATransfer() = runBlocking<Unit> {
        assertThat(batchType(setOf(), 50.0, -50.0)).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(batchType(setOf("1", "2"), 50.0, -50.0)).isEqualTo(TransactionTypeProperty.transfer)
    }

    @Test
    fun theLegsAndLinksOfALiabilityPairAreKept() = runBlocking<Unit> {
        val tx = converter(setOf("2")).convertBatchSync(
            listOf(plaid(accountA, "wd", 50.0), plaid(accountB, "dep", -50.0)), accountMap
        ).single().tx
        assertThat(tx.sourceId).isEqualTo("1")
        assertThat(tx.destinationId).isEqualTo("2")
        assertThat(tx.plaidLinks).containsExactly(srcLink, dstLink)
    }

    @Test
    fun aManualWithdrawalPairedWithALiabilityLegStaysAWithdrawalAndKeepsBothLegs() = runBlocking<Unit> {
        val manual = TransactionRead(
            "transactions", "ff9",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.withdrawal, amount = "50.0", sourceId = "1", destinationName = "Card",
                plaidLinks = listOf(PlaidLink("wd", PlaidLinkLeg.single, accountA)), description = "x",
                currencyId = "5", currencyCode = "USD",
            ), ObjectLink()
        )
        val result = converter(setOf("2")).convertPollSync(
            accountMap, listOf(plaid(accountB, "dep", -50.0)), listOf(), listOf(), listOf(manual)
        )
        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ff9")
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(update.changesType).isFalse()
        assertThat(update.tx.destinationId).isEqualTo("2")
        assertThat(update.tx.plaidLinks!!.map { it.leg }).containsExactlyInAnyOrder(PlaidLinkLeg.source, PlaidLinkLeg.destination)
        assertThat(update.fallbackCreate).isNotNull
    }

    @Test
    fun aPairedWithdrawalIsNotACandidateForAnotherPairing() {
        assertThat(converter(setOf("2")).filterFireflyCandidateTransferTxs(listOf(paidLiability))).isEmpty()
    }

    /** Without the fix the liability leg's sign flip is read as a direction change of a plain deposit. */
    @Test
    fun aPlaidUpdateOfALegOfALiabilityPairKeepsTheTypeAndBothAccounts() = runBlocking<Unit> {
        val result = converter(setOf("2")).convertPollSync(
            accountMap, listOf(), listOf(plaid(accountB, "dep", -60.0)), listOf(), listOf(paidLiability)
        )
        val update = result.updates.single()
        assertThat(update.changesType).isFalse()
        assertThat(update.tx.amount).isEqualTo("60.0")
        assertThat(update.tx.sourceId).isEqualTo("1")
        assertThat(update.tx.destinationId).isEqualTo("2")
        assertThat(update.tx.description).isEqualTo("Card payment")
    }

    @Test
    fun removingOneLegOfALiabilityPairLeavesTheOtherLegsMoney() = runBlocking<Unit> {
        val result = converter(setOf("2")).convertPollSync(accountMap, listOf(), listOf(), listOf("dep"), listOf(paidLiability))
        assertThat(result.deletes).isEmpty()
        val update = result.updates.single()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(update.tx.plaidLinks).containsExactly(srcLink.copy(leg = PlaidLinkLeg.single))
    }
}
