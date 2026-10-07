package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead

/**
 * Finds already-imported Firefly transactions by the Plaid transaction id on their link (Firefly's
 * `plaid_transaction_links` table, read back as `plaid_links` on every split). A transfer between two linked banks holds
 * both ids, so either leg, arriving in any later poll, finds it.
 */
class PlaidLinkIndexer(existingFireflyTxs: List<TransactionRead>) {
    private val byPlaidId: Map<String, TransactionRead> = buildMap {
        for (read in existingFireflyTxs) {
            for (link in linksOf(read)) putIfAbsent(link.plaidTransactionId, read)
        }
    }

    /** The Firefly transaction that holds [plaidTransactionId] (the raw Plaid id), or null. */
    fun find(plaidTransactionId: String): TransactionRead? = byPlaidId[plaidTransactionId]

    companion object {
        fun linksOf(read: TransactionRead): List<PlaidLink> =
            read.attributes.transactions.flatMap { it.plaidLinks.orEmpty() }
    }
}
