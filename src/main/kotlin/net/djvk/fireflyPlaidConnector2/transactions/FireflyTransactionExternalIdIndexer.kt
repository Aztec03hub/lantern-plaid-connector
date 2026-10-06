package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyExternalId
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId

/**
 * Finds existing Firefly transactions by the Plaid transaction id they were imported from.
 *
 * A single Firefly transaction carries one Plaid id in `external_id`. A transfer is built from two Plaid transactions
 * (one per bank), so it carries the second leg's id in `internal_reference` (see
 * [TransactionConverter.convertDoublePlaid]). Both are indexed, with `external_id` winning if the same value ever
 * appears in both, so that either leg, arriving in any later poll, finds the transfer instead of being imported again.
 *
 * Only transfers' `internal_reference` is read: it is a free-text field the user can edit on any transaction.
 */
class FireflyTransactionExternalIdIndexer(
    existingFireflyTxs: List<TransactionRead>,
) {
    private val fireflyTxsByExternalId: Map<FireflyExternalId, TransactionRead>

    init {
        val out = mutableMapOf<FireflyExternalId, TransactionRead>()
        for (existingFireflyTx in existingFireflyTxs) {
            for (tx in existingFireflyTx.attributes.transactions) {
                if (tx.externalId == null) continue

                out[tx.externalId] = existingFireflyTx
            }
        }
        for (existingFireflyTx in existingFireflyTxs) {
            for (tx in existingFireflyTx.attributes.transactions) {
                if (tx.type != TransactionTypeProperty.transfer) continue
                val ref = tx.internalReference?.takeIf { it.startsWith(EXTERNAL_ID_PREFIX) } ?: continue
                out.putIfAbsent(ref, existingFireflyTx)
            }
        }

        fireflyTxsByExternalId = out
    }

    /** [externalId] is the full Firefly external id (with the `plaid-` prefix). Matches `external_id` and a transfer's `internal_reference`. */
    fun findByExternalId(externalId: FireflyExternalId): TransactionRead? = fireflyTxsByExternalId[externalId]

    fun findExistingFireflyTx(
        plaidTransactionId: PlaidTransactionId,
    ): TransactionRead? {
        return fireflyTxsByExternalId[getExternalId(plaidTransactionId)]
    }

    companion object {
        const val EXTERNAL_ID_PREFIX = "plaid-"

        fun getExternalId(txId: String): PlaidTransactionId {
            return "$EXTERNAL_ID_PREFIX$txId"
        }
    }
}
