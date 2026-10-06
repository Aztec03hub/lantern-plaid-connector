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
 * Covers the part of upstream issue #106 that is a correctness problem today: a Plaid "modified" event for a
 * transaction that the connector already turned into a Firefly transfer was converted as if it were a plain
 * withdrawal/deposit, which sent a counterparty name for a transfer.
 */
internal class PlaidUpdateOfTransferTest {
    private val converter = TransactionConverter(
        useNameForDestination = true,
        enablePrimaryCategorization = false,
        primaryCategoryPrefix = "a",
        enableDetailedCategorization = false,
        detailedCategoryPrefix = "b",
        timeZoneString = "America/New_York",
        transferMatchWindowDays = 3L,
        txStyle = TransactionStyleConfig(null),
    )

    private val existingTransfer = TransactionRead(
        "transactions", "ffTransfer",
        FireflyFixtures.getTransaction(
            type = TransactionTypeProperty.transfer,
            amount = "100.0",
            sourceId = "2",
            destinationId = "1",
            description = "User edited transfer description",
            externalId = "plaid-legId",
            currencyId = "5",
            currencyCode = "USD",
        ), ObjectLink()
    )

    @Test
    fun plaidUpdateOfATransferKeepsBothAccountsAndNeverSendsACounterpartyName() = runBlocking<Unit> {
        val modified = PlaidFixtures.getPaymentTransaction(
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            transactionId = "legId",
            pendingTransactionId = null,
            name = "Plaid renamed this",
            amount = -100.0,
        )

        val result = converter.convertPollSync(
            PlaidFixtures.getStandardAccountMapping(), listOf(), listOf(modified), listOf(), listOf(existingTransfer)
        )

        val update = result.updates.single()
        assertThat(update.id).isEqualTo("ffTransfer")
        assertThat(update.tx.sourceId).isEqualTo("2")
        assertThat(update.tx.destinationId).isEqualTo("1")
        assertThat(update.tx.sourceName).isNull()
        assertThat(update.tx.destinationName).isNull()
        assertThat(update.tx.description).isEqualTo("User edited transfer description")
        // Must not be routed to the delete-and-recreate transfer path
        assertThat(update.tx.type).isNotEqualTo(TransactionTypeProperty.transfer)
    }
}
