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
     * For an update that adds a Plaid leg to an existing transaction (pairing, or pending to posted): what to create
     * instead if the transaction is gone (404), or, for a pairing only, if Firefly rejects the update (422), so the new
     * leg's money is never hidden behind a rejected update (see FireflyTransactionService.processFireflyTransactionUpdates).
     */
    val fallbackCreate: FireflyTransactionDto? = null,
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
