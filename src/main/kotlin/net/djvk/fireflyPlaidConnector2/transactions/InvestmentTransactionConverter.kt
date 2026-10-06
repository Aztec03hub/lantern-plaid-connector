package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.math.abs

/**
 * Converts Plaid investment transactions (upstream issue #68) into Firefly transactions on the account's cash
 * ledger.
 *
 * Firefly has no concept of securities or holdings, so this records only the cash effect of each investment
 * transaction. Plaid's `amount` follows the same sign convention as bank transactions: positive when cash leaves the
 * account (a purchase of stock, a fee), negative when cash arrives (a sale, a dividend). The security, quantity and
 * price go in the Firefly notes so nothing is lost.
 */
@Component
class InvestmentTransactionConverter(
    @Value("\${fireflyPlaidConnector2.timeZone}")
    timeZoneString: String,
) {
    private val zoneId: ZoneId = ZoneId.of(timeZoneString)

    /**
     * @return null for a zero-amount transaction (for example a stock split or an adjustment), which Firefly can't
     *  store and which carries no cash movement
     */
    fun convert(tx: InvestmentTransaction, fireflyAccountId: Int, importTag: String? = null): FireflyTransactionDto? {
        if (tx.amount == 0.0) return null

        val isCashOut = tx.amount > 0
        val label = "Investment ${tx.type.value}"
        val tags = listOfNotNull(
            "plaid-investment-${tx.type.value}",
            "plaid-investment-${tx.subtype.value.replace(' ', '-')}",
            importTag,
        ).distinct()

        val split = TransactionSplit(
            if (isCashOut) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
            TransactionConverter.getOffsetDateTimeForDate(zoneId, tx.date),
            BigDecimal.valueOf(abs(tx.amount)).toPlainString(),
            tx.name,
            sourceId = if (isCashOut) fireflyAccountId.toString() else null,
            sourceName = if (isCashOut) null else label,
            destinationId = if (isCashOut) null else fireflyAccountId.toString(),
            destinationName = if (isCashOut) label else null,
            currencyCode = tx.isoCurrencyCode,
            notes = describe(tx),
            tags = tags,
            externalId = FireflyTransactionExternalIdIndexer.getExternalId(tx.investmentTransactionId),
            order = 0,
            reconciled = false,
        )
        return FireflyTransactionDto(null, split)
    }

    private fun describe(tx: InvestmentTransaction): String {
        val parts = mutableListOf("${tx.type.value} (${tx.subtype.value})")
        if (tx.quantity != 0.0) {
            parts.add("quantity ${BigDecimal.valueOf(tx.quantity).toPlainString()} @ ${BigDecimal.valueOf(tx.price).toPlainString()}")
        }
        tx.fees?.takeIf { it != 0.0 }?.let { parts.add("fees ${BigDecimal.valueOf(it).toPlainString()}") }
        tx.securityId?.let { parts.add("security $it") }
        return parts.joinToString("; ")
    }
}
