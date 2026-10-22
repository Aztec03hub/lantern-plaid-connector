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
 * U46: the import never pairs. Look-alike transfer legs come in as two singles, in a poll and in a batch; the settled
 * pair pass (PairPassTest.theSettledPassPairsTheTwoSinglesAPollImported) pairs them afterwards.
 */
internal class NoInRunPairingTest {
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
        txStyle = TransactionStyleConfig(null),
    )

    private fun leg(account: String, id: String, amount: Double) = PlaidFixtures.getPaymentTransaction(
        accountId = account, transactionId = id, pendingTransactionId = null, amount = amount, name = "Plaid $id",
        personalFinanceCategory = (if (amount > 0) PersonalFinanceCategoryEnum.TRANSFER_OUT_ACCOUNT_TRANSFER
        else PersonalFinanceCategoryEnum.TRANSFER_IN_ACCOUNT_TRANSFER).toPersonalFinanceCategory(),
    )

    private fun assertTwoSingles(creates: List<FireflyTransactionDto>) {
        assertThat(creates).hasSize(2)
        assertThat(creates.map { it.tx.type }).containsExactlyInAnyOrder(TransactionTypeProperty.withdrawal, TransactionTypeProperty.deposit)
        creates.forEach { c ->
            assertThat(c.tx.plaidLinks).singleElement().extracting { it.leg }.isEqualTo(PlaidLinkLeg.single)
        }
        assertThat(creates.flatMap { it.tx.plaidLinks.orEmpty() }.map { it.plaidTransactionId }).containsExactlyInAnyOrder("wd", "dep")
    }

    @Test
    fun aPollOfTwoLookAlikeTransferLegsImportsTwoSingles() = runBlocking<Unit> {
        val result = converter().convertPollSync(
            accountMap, listOf(leg(accountA, "wd", 250.0), leg(accountB, "dep", -250.0)), listOf(), listOf(), listOf()
        )
        assertTwoSingles(result.creates)
        assertThat(result.updates).isEmpty()
    }

    @Test
    fun aBatchOfTwoLookAlikeTransferLegsImportsTwoSingles() = runBlocking<Unit> {
        val creates = converter().convertBatchSync(listOf(leg(accountA, "wd", 250.0), leg(accountB, "dep", -250.0)), accountMap)
        assertTwoSingles(creates)
    }

    @Test
    fun aSecondLegArrivingInALaterPollIsImportedAsASingleAndTheExistingLegIsLeftAlone() = runBlocking<Unit> {
        val single = FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.withdrawal, amount = "50.0", sourceId = "1", destinationId = null,
            plaidLinks = listOf(PlaidLink("wd", PlaidLinkLeg.single, accountA)), tags = listOf(),
            description = "User words", currencyId = "5", currencyCode = "USD", destinationName = "Shop",
        )
        val existing = TransactionRead("transactions", "ff1", single, ObjectLink())
        val result = converter().convertPollSync(accountMap, listOf(leg(accountB, "dep", -50.0)), listOf(), listOf(), listOf(existing))
        assertThat(result.updates).isEmpty()
        assertThat(result.creates).singleElement().satisfies({
            assertThat(it.tx.type).isEqualTo(TransactionTypeProperty.deposit)
            assertThat(it.tx.plaidLinks).singleElement().extracting { l -> l.leg }.isEqualTo(PlaidLinkLeg.single)
            assertThat(it.fallbackCreate).isNull()
        })
    }
}
