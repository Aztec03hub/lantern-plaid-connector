package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.plaid.models.PersonalFinanceCategory
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionCounterparty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.CounterpartyType
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.PlaidFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Imports give every spelling of one payee one Firefly account name. */
internal class ConverterNamesTest {
    private val converter = TransactionConverter(
        useNameForDestination = true, enablePrimaryCategorization = false, primaryCategoryPrefix = "p-",
        enableDetailedCategorization = false, detailedCategoryPrefix = "d-", timeZoneString = "America/Chicago",
        transferMatchWindowDays = 3, txStyle = TransactionStyleConfig(null),
    )

    private fun tx(name: String, merchant: String? = null, counterparty: String? = null) = PlaidFixtures.getTransaction(
        name = name, merchantName = merchant, paymentChannel = Transaction.PaymentChannel.other,
        personalFinanceCategory = PersonalFinanceCategory("INCOME", "INCOME_WAGES"),
    ).copy(
        counterparties = counterparty?.let { listOf(TransactionCounterparty(it, CounterpartyType.merchant, null, null, null, "HIGH")) },
    )

    @Test
    fun theThreeRealPaycheckStringsMapToOneName() {
        val names = listOf(
            "EVOLV CONSULTING",
            "Evolv Consulting Type: Payroll ID: XX2465 CO: Evolv Consulting",
            "AC EVOLV CONSULTING PAYROLL 111000023909729PPD 4260302465",
        ).map { converter.getSourceOrDestinationName(tx(it), true) }
        assertThat(names.toSet()).containsExactly("Evolv Consulting")
    }

    @Test
    fun twoTransfersToTheSameBankMapToOneName() {
        val names = listOf("AC DCU XXXX0373W", "AC DCU XXXX1179W").map { converter.getSourceOrDestinationName(tx(it), false) }
        assertThat(names.toSet()).hasSize(1)
    }

    @Test
    fun plaidsCounterpartyNameBeatsTheBankText() {
        assertThat(converter.getSourceOrDestinationName(tx("SQ *COFFEE 1234 CHICAGO IL", merchant = "Other", counterparty = "Blue Bottle"), false))
            .isEqualTo("Blue Bottle")
    }
}
