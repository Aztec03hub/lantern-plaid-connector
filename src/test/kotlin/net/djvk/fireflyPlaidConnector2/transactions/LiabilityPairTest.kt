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
        txStyle = TransactionStyleConfig(null),
    ).also { c -> c.accountKinds = liabilities.associateWith { AccountKind.LIABILITY } }

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

    private suspend fun batchTypes(liabilities: Set<String>, a: Double, b: Double) =
        converter(liabilities).convertBatchSync(
            listOf(plaid(accountA, "wd", a), plaid(accountB, "dep", b)), accountMap
        ).map { it.tx.type }

    /** U46: whatever the account kinds, the import makes two singles; the pair type (liability rules) is the pair pass's job. */
    @Test
    fun legsOfAnAssetAndALiabilityImportAsTwoSinglesWhateverTheKinds() = runBlocking<Unit> {
        for (liabilities in listOf(setOf("2"), setOf("1"), setOf(), setOf("1", "2"))) {
            assertThat(batchTypes(liabilities, 50.0, -50.0)).describedAs("liabilities $liabilities")
                .containsExactlyInAnyOrder(TransactionTypeProperty.withdrawal, TransactionTypeProperty.deposit)
        }
    }

    /** U46: the import no longer types a pair, but the pair pass still does; one cell per kind combination (Firefly 6.7.7 rules). */
    @Test
    fun pairedTransactionTypeFollowsFirefliesTableForEveryKindCombination() {
        val a = AccountKind.ASSET
        val l = AccountKind.LIABILITY
        assertThat(pairedTransactionType(a, a)).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(pairedTransactionType(a, l)).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(pairedTransactionType(l, a)).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(pairedTransactionType(l, l)).isEqualTo(TransactionTypeProperty.transfer)
    }

    /** U46 (was: a manual withdrawal paired with a liability leg at import): the manual single is left alone, the leg is a single. */
    @Test
    fun aManualWithdrawalIsLeftAloneWhenALiabilityLegArrives() = runBlocking<Unit> {
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
        assertThat(result.updates).isEmpty()
        assertThat(result.creates.single().tx.type).isEqualTo(TransactionTypeProperty.deposit)
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

    /** Both banks flip the direction of a payment pair: the withdrawal becomes a deposit, the type is sent, legs swap. */
    @Test
    fun aSwappedWithdrawalPairBecomesADepositAndSendsTheType() = runBlocking<Unit> {
        val result = converter(setOf("2")).convertPollSync(
            accountMap, listOf(), listOf(plaid(accountA, "wd", -50.0), plaid(accountB, "dep", 50.0)), listOf(), listOf(paidLiability)
        )
        // one update per leg, both computed from the same stored state, so identical and idempotent
        val update = result.updates.first()
        assertThat(update.tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(update.changesType).isTrue()
        assertThat(update.toTransactionUpdate().transactions!!.single().type).isNotNull
        assertThat(update.tx.sourceId).isEqualTo("2")
        assertThat(update.tx.destinationId).isEqualTo("1")
        assertThat(update.tx.plaidLinks).containsExactlyInAnyOrder(
            srcLink.copy(leg = PlaidLinkLeg.destination), dstLink.copy(leg = PlaidLinkLeg.source))
    }

    /** A paired withdrawal or deposit is never a single: each is skipped as a candidate however the Plaid ids are looked up. */
    @Test
    fun pairedDepositsAndWithdrawalsAreNeverCandidatesForAnotherPairing() {
        val depositPair = TransactionRead(
            "transactions", "ff2",
            FireflyFixtures.getTransaction(
                type = TransactionTypeProperty.deposit, amount = "50.0", sourceId = "2", destinationId = "1",
                plaidLinks = listOf(srcLink.copy(plaidAccountId = accountB), dstLink.copy(plaidAccountId = accountA)),
            ), ObjectLink()
        )
        assertThat(converter(setOf("2")).filterFireflyCandidateTransferTxs(listOf(paidLiability, depositPair))).isEmpty()
    }
}
