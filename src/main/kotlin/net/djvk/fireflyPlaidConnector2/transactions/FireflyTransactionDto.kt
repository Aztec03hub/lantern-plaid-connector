package net.djvk.fireflyPlaidConnector2.transactions

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.models.*
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * This class is used as an internal DTO because the generated Firefly API model classes are inconsistent
 *  in what data they contain which makes it hard to have consistent internal interfaces.
 */
data class FireflyTransactionDto(
    /**
     * The id of the Firefly transaction. This will only be present if this transaction has been persisted
     *  in Firefly.
     * This will be used to determine if this record should be sent to Firefly as an update or a create.
     * Not to be confused with [TransactionSplit.transactionJournalId].
     */
    val id: FireflyTransactionId?,
    val tx: TransactionSplit,
    /**
     * True when this update changes the type of the existing Firefly transaction (a sign flip turns a withdrawal into
     * a deposit and back). The type is only sent on an update when it changes, so an ordinary update can never alter it.
     */
    val changesType: Boolean = false,
    /**
     * For an update that carries a Plaid fact (a pairing, a pending to posted promotion, or a plain Plaid update): what
     * to create instead if the Firefly transaction is gone (404), so the money is never lost behind a failed update. A
     * pairing (and only a pairing, see [fallbackOnlyWhenGone]) also creates it when Firefly rejects the update for good
     * (any 4xx but 401/403/408/409/429). A dead letter keeps it too (see FireflyTransactionService.processFireflyTransactionUpdates).
     */
    val fallbackCreate: FireflyTransactionDto? = null,
    /** True when the fallback may only replace the update if the Firefly transaction is GONE (404): a transfer or plain Plaid update, where a rejected update leaves the old transaction holding the money. False keeps the pairing rule (any permanent 4xx). */
    val fallbackOnlyWhenGone: Boolean = false,
    /** The Plaid account this update is for, kept so a dead letter can re-resolve the Firefly account after the mapping changes; null when the split's links carry it. */
    val plaidAccountId: String? = null,
    /**
     * True for a create that stands in for a Plaid update Firefly has no link for: before it is sent, Firefly is searched
     * for a transaction an older import (before the link table) made with this Plaid id as its external id or internal
     * reference, and the create is skipped if there is one.
     */
    val checkLegacyReference: Boolean = false,
) {
    val transactionId: String
        get() = id ?: throw RuntimeException("Can't use a Firefly transaction without an id for sorting")

    val amount: Double
        get() = TransactionConverter.getPlaidAmount(this)

    fun toTransactionStore(): TransactionStore {
        return TransactionStore(
            listOf(tx),
            // A transaction with a Plaid link is deduped by the link table. Without the old external id, Firefly's content
            //  hash could also reject two real, identical purchases, which the connector then skipped. A transaction
            //  with no link (batch mode's opening balance) has only the hash to stop a second copy on a re-run.
            errorIfDuplicateHash = tx.plaidLinks.isNullOrEmpty(),
            applyRules = true,
            fireWebhooks = true,
            groupTitle = null,
        )
    }

    fun toTransactionUpdate(): TransactionUpdate {
        return TransactionUpdate(
            // A transfer is always sent with its type: for an existing deposit/withdrawal that is the in-place
            //  conversion to a transfer, for an existing transfer it is a no-op. A deposit/withdrawal is sent with its
            //  type only when the update says it changes (see [changesType]).
            transactions = listOf(
                tx.toTransactionSplitUpdate(includeType = changesType || tx.type == TransactionTypeProperty.transfer)
            ),
            applyRules = true,
            fireWebhooks = true,
            groupTitle = tx.description,
        )
    }
}
