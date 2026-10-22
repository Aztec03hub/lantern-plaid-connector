package net.djvk.fireflyPlaidConnector2.transactions

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkLeg
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction.PaymentChannel
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FireflyFixtures
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

internal class SpecialLabelTest {
    private class Probe : TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
        txStyle = TransactionStyleConfig(null), pairConfigFile = "",
    ) {
        suspend fun create(t: net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction) =
            convert(t, false, sourceId = "1", plaidLinks = listOf(PlaidLink(t.transactionId, PlaidLinkLeg.single, t.accountId))).tx

        suspend fun plainUpdate(t: net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction) =
            convertSingle(t, mapOf(t.accountId to 1)).tx

        suspend fun update(t: net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction) =
            convert(t, false, sourceId = "1", fireflyTx = FireflyTransactionDto("9", FireflyFixtures.getTransaction(categoryName = "Hand set").transactions.first())).tx
    }

    private fun tx(text: String) = PlaidFixtures.getTransaction(
        name = text, originalDescription = text, paymentChannel = PaymentChannel.other,
        personalFinanceCategory = net.djvk.fireflyPlaidConnector2.api.plaid.models.PersonalFinanceCategory("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_OTHER_GENERAL_MERCHANDISE"),
        amount = 50.0, date = LocalDate.of(2026, 10, 7), accountId = "plaid1",
    )

    @Test
    fun aCarryOverCreateGetsTheLabelAsCategory() {
        val s = runBlocking { Probe().create(tx("LAST STATEMENT BAL FROM ACCT ENDING 1234")) }
        assertThat(s.categoryName).isEqualTo("Statement carry-over")
        assertThat(s.description).contains("LAST STATEMENT BAL FROM ACCT ENDING 1234")
    }

    @Test
    fun anOrdinaryExpenseGetsNoCategory() {
        assertThat(runBlocking { Probe().create(tx("COFFEE SHOP")) }.categoryName).isNull()
    }

    @Test
    fun anUpdateOfAnExistingJournalDoesNotSendACategory() {
        assertThat(runBlocking { Probe().update(tx("BALANCE CONSOLIDATION")) }.categoryName).isNull()
    }

    @Test
    fun aPlainUpdateThroughConvertSingleSendsNoCategory() {
        assertThat(runBlocking { Probe().plainUpdate(tx("BALANCE CONSOLIDATION")) }.categoryName).isNull()
    }
}
