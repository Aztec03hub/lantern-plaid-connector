package net.djvk.fireflyPlaidConnector2.transactions

import io.ktor.http.*
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.constants.Direction
import net.djvk.fireflyPlaidConnector2.transactions.PersonalFinanceCategoryEnum.Primary.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.time.*
import java.util.*
import kotlin.math.abs
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

typealias PlaidAccountId = String
typealias FireflyAccountId = Int

/**
 * These are the only Firefly transaction types that are eligible to be converted into transfers.
 * All other transaction types will not be considered for conversion.
 */
val fireflyTxTypesEligibleForConversion = hashSetOf(
    TransactionTypeProperty.deposit,
    TransactionTypeProperty.withdrawal,
)

@Component
class TransactionConverter(
    @Value("\${fireflyPlaidConnector2.useNameForDestination:true}")
    private val useNameForDestination: Boolean,
    @Value("\${fireflyPlaidConnector2.timeZone}")
    private val timeZoneString: String,
    @Value("\${fireflyPlaidConnector2.transferMatchWindowDays}")
    private val transferMatchWindowDays: Long,

    @Value("\${fireflyPlaidConnector2.categorization.primary.enable:false}")
    private val enablePrimaryCategorization: Boolean,
    @Value("\${fireflyPlaidConnector2.categorization.primary.prefix:plaid-primary-cat-}")
    private val primaryCategoryPrefix: String,

    @Value("\${fireflyPlaidConnector2.categorization.detailed.enable:false}")
    private val enableDetailedCategorization: Boolean,
    @Value("\${fireflyPlaidConnector2.categorization.detailed.prefix:plaid-detailed-cat-}")
    private val detailedCategoryPrefix: String,

    private val txStyle: TransactionStyleConfig,

    /**
     * When true, single (non-transfer) transactions are created without a counterparty name, so Firefly leaves the
     * other side as its default "(no name)" account instead of creating one account per merchant. Useful when
     * Firefly rules or webhooks assign the counterparty afterwards. Transfers are unaffected because both of their
     * accounts are real, configured accounts.
     */
    @Value("\${fireflyPlaidConnector2.disableAccountAssignment:false}")
    private val disableAccountAssignment: Boolean = false,

    /**
     * Optional tag added to every transaction CREATED during a sync run, like the Firefly data importer's
     * "Data Import on ..." tag. Text inside {braces} is a java.time.format.DateTimeFormatter pattern evaluated in
     * [timeZoneString] at the start of the run, e.g. "Plaid import {yyyy-MM-dd @ HH:mm}". Blank disables it.
     * Updates to existing transactions are not re-tagged.
     */
    @Value("\${fireflyPlaidConnector2.importTag:}")
    private val importTagTemplate: String = "",

    /**
     * Optional tag added to transactions while Plaid reports them as pending, and removed when they post.
     * Blank (the default) disables it.
     */
    @Value("\${fireflyPlaidConnector2.pendingTag:}")
    private val pendingTag: String = "",
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val timeZone = TimeZone.getTimeZone(timeZoneString)
    private val zoneId = timeZone.toZoneId()

    init {
        // Fail fast on a bad pattern instead of on the first sync
        currentImportTag()
    }

    /**
     * The import tag for a run starting now, or null if none is configured.
     */
    fun currentImportTag(): String? = renderImportTag(importTagTemplate, ZonedDateTime.now(zoneId))
    private val transferMatcher = TransferMatcher(timeZoneString, transferMatchWindowDays)

    companion object {
        private val importTagPlaceholder = Regex("\\{([^{}]*)\\}")

        /**
         * Replaces each {pattern} in [template] with [now] formatted using that DateTimeFormatter pattern.
         * @return the rendered tag, or null if [template] is blank
         * @throws IllegalArgumentException if a pattern is not a valid DateTimeFormatter pattern
         */
        fun renderImportTag(template: String, now: ZonedDateTime): String? {
            if (template.isBlank()) return null
            return importTagPlaceholder.replace(template.trim()) { match ->
                try {
                    java.time.format.DateTimeFormatter.ofPattern(match.groupValues[1]).format(now)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "fireflyPlaidConnector2.importTag has an invalid date pattern '${match.groupValues[1]}': ${e.message}"
                    )
                }
            }
        }

        fun convertScreamingSnakeCaseToKebabCase(input: String): String {
            return input
                .replace("_", "-")
                .lowercase()
        }

        fun getOffsetDateTimeForDate(zoneId: ZoneId, date: LocalDate): OffsetDateTime {
            val instant = date.atStartOfDay(zoneId).toInstant()
            val offset = zoneId.rules.getOffset(instant)
            return date.atTime(OffsetTime.of(0, 0, 0, 0, offset))
        }

        /**
         * Basically the inverse of [getFireflyTransactionDtoType]
         */
        fun getPlaidAmount(tx: FireflyTransactionDto): Double {
            return when (tx.tx.type) {
                TransactionTypeProperty.withdrawal -> tx.tx.amount.toDouble()
                TransactionTypeProperty.deposit -> -tx.tx.amount.toDouble()
                else -> throw IllegalArgumentException("Can't get Plaid amount for a Firefly transaction of type ${tx.tx.type}")
            }
        }

        fun getTransactionDirection(tx: PlaidTransaction): Direction {
            return if (tx.amount > 0) {
                Direction.OUT
            } else {
                Direction.IN
            }
        }

        private fun requirePlaidTransaction(tx: PlaidFireflyTransaction?): PlaidTransaction {
            return tx?.plaidTransaction ?: throw RuntimeException("Transaction missing required Plaid information")
        }
    }

    fun getTxAuthorizedTimestamp(tx: PlaidTransaction): OffsetDateTime? {
        if (tx.authorizedDatetime != null) {
            return tx.authorizedDatetime
        } else if (tx.authorizedDate == null) {
            return null
        }
        return getOffsetDateTimeForDate(zoneId, tx.authorizedDate)
    }

    fun getTxPostedTimestamp(tx: PlaidTransaction): OffsetDateTime {
        return tx.datetime
            ?: getOffsetDateTimeForDate(zoneId, tx.date)
    }

    fun getTxDescription(tx: PlaidTransaction): String {
        // Note about the "name" field from Plaid's API docs:
        // This is a legacy field that is not actively maintained. Use merchant_name instead for the merchant name.
        // Source: https://plaid.com/docs/api/products/transactions/#transactionssync
        //
        // Observations about the "name" field. It seems to be used differently by different institutions. It's
        // sometimes exactly the same as merchantName, sometimes it's exactly the same as originalDescription, and
        // yet other times it's something completely different from either.
        val merchantName = tx.merchantName ?: tx.name

        // The originalDescription should always be populated because we're calling Plaid
        // with include_original_description set to true
        val defaultDesc = merchantName + if (tx.originalDescription == null) "" else ": ${tx.originalDescription}"

        if (txStyle.descriptionExpression == null || txStyle.descriptionExpression.trim().isEmpty()) {
            return defaultDesc
        }

        return try {
            val parser = SpelExpressionParser()
            val context = StandardEvaluationContext(FormattingContext(tx))

            // Provide #defaultDescription with the default description
            context.setVariable("merchantAndDescription", defaultDesc)

            // Provide shorthand for tx.merchantName ?: tx.name using #merchantNameWithFallback
            context.setVariable("merchantNameWithFallback", merchantName)

            val value = parser.parseExpression(txStyle.descriptionExpression.trim()).getValue(context, String::class.java)

            value ?: run {
                logger.error("Custom description SpEL expression {} returned null. Falling-back to default.", txStyle.descriptionExpression)
                defaultDesc
            }
        } catch (e: RuntimeException) {
            logger.error("Failed to parse custom description SpEL expression {}. Falling-back to default.", txStyle.descriptionExpression, e)
            defaultDesc
        }
    }

    fun getSourceOrDestinationName(
        tx: PlaidTransaction,
        isSource: Boolean,
    ): String {
        return tx.merchantName
            ?: if (useNameForDestination) {
                tx.name.take(255)
            } else {
                if (tx.personalFinanceCategory == null) {
                    return "Unknown"
                }
                val cat = PersonalFinanceCategoryEnum.from(tx.personalFinanceCategory)
                getUnknownSourceOrDestinationName(cat, isSource)
            }
    }

    /**
     * Gets the name to use for an external account in cases where we don't have any better info
     * @param isSource True if source, false if destination
     */
    fun getUnknownSourceOrDestinationName(
        pfc: PersonalFinanceCategoryEnum,
        isSource: Boolean,
    ): String {
        val typeString = when (pfc.primary) {
            INCOME, LOAN_DISBURSEMENTS -> "Income"
            TRANSFER_IN, TRANSFER_OUT -> "Transfer"
            LOAN_PAYMENTS, BANK_FEES, ENTERTAINMENT, FOOD_AND_DRINK, GENERAL_MERCHANDISE, HOME_IMPROVEMENT,
            MEDICAL, PERSONAL_CARE, GENERAL_SERVICES, GOVERNMENT_AND_NON_PROFIT, TRANSPORTATION, TRAVEL,
            RENT_AND_UTILITIES, OTHER -> "Payment"
        }
        val sourceString = if (isSource) {
            "Source"
        } else {
            "Recipient"
        }
        return "Unknown $typeString $sourceString"
    }

    /**
     * Convert a batch of Plaid transactions to Firefly transactions in batch syncing mode, as opposed to [convertPollSync]
     *
     * A batch should include the widest date range possible and should include transactions from
     *  all available Plaid accounts to enable matching of transfer transactions.
     */
    suspend fun convertBatchSync(
        txs: List<PlaidTransaction>,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
    ): List<FireflyTransactionDto> {
        logger.debug("Batch sync converting Plaid transactions to Firefly transactions")
        val importTag = currentImportTag()
        return transferMatcher.match(PlaidFireflyTransaction.normalizeByTransactionId(txs, listOf(), accountMap)).map {
            when (it) {
                is PlaidFireflyTransaction.Transfer -> {
                    convertDoublePlaid(
                        requirePlaidTransaction(it.withdrawal),
                        requirePlaidTransaction(it.deposit),
                        accountMap,
                        importTag,
                    )
                }
                else -> {
                    convertSingle(requirePlaidTransaction(it), accountMap, importTag)
                }
            }
        }
    }

    data class ConvertPollSyncResult(
        val creates: List<FireflyTransactionDto>,
        val updates: List<FireflyTransactionDto>,
        val deletes: List<FireflyTransactionId>,
    )

    /**
     * Convert a batch of Plaid change events and existing Firefly transactions to Firefly creates, updates, and
     *  deletes in poll syncing mode, as opposed to [convertBatchSync]
     *
     * [existingFireflyTxs] should include transactions from fireflyPlaidConnector2.transferMatchWindowDays ago for
     *  transfer matching purposes.
     */
    suspend fun convertPollSync(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        plaidCreatedTxs: List<PlaidTransaction>,
        plaidUpdatedTxs: List<PlaidTransaction>,
        plaidDeletedTxs: List<PlaidTransactionId>,
        existingFireflyTxs: List<TransactionRead>,
    ): ConvertPollSyncResult {
        logger.trace("Starting ${::convertPollSync.name}")
        val transferCandidateExistingFireflyTxs = filterFireflyCandidateTransferTxs(existingFireflyTxs)

        val creates = mutableListOf<FireflyTransactionDto>()
        val updates = mutableListOf<FireflyTransactionDto>()
        val deletes = mutableListOf<FireflyTransactionId>()
        val importTag = currentImportTag()
        val indexer = FireflyTransactionExternalIdIndexer(existingFireflyTxs)

        /**
         * Firefly ids of pending transactions that we're updating in place into their posted version, so that the
         *  Plaid "removed" event for the pending id doesn't also delete them.
         */
        val promotedFireflyIds = mutableSetOf<FireflyTransactionId>()

        /**
         * Don't pass in [plaidUpdatedTxs] here because we're not going to try to update transfers for now
         *  because it's more complexity than I want to deal with, and I haven't seen any Plaid updates in the wild yet
         *
         * Pending transactions are never matched as transfers: they are provisional, and keeping them as plain
         *  deposits/withdrawals is what allows them to be updated in place when they post (Firefly can't change a
         *  transaction's type on update).
         */
        val (pendingCreates, settledCreates) = plaidCreatedTxs.partition { it.pending }
        val wrappedCreates = transferMatcher.match(
            PlaidFireflyTransaction.normalizeByTransactionId(settledCreates, transferCandidateExistingFireflyTxs, accountMap)
        ) + PlaidFireflyTransaction.normalizeByTransactionId(pendingCreates, transferCandidateExistingFireflyTxs, accountMap)
            // Existing Firefly transactions are only needed here to recognize pending creates we already imported
            .filter { it !is PlaidFireflyTransaction.FireflyTransaction }
        logger.debug(
            "{} call to transferMatcher returned {} transactions",
            ::convertPollSync.name,
            wrappedCreates.size,
        )

        /**
         * Handle singles, which are transactions that didn't have any transfer pair matches
         */
        for (create in wrappedCreates) {
            val convertedSingle = when (create) {
                is PlaidFireflyTransaction.PlaidTransaction -> {
                    // A posted transaction that replaces a pending one we already imported updates that Firefly
                    //  transaction in place (keeping its id, categories, budgets, notes and user tags) rather than
                    //  deleting and re-creating it.
                    val promotion = convertPendingPromotion(create.plaidTransaction, indexer, accountMap)
                    if (promotion != null) {
                        promotedFireflyIds.add(promotion.transactionId)
                        updates.add(promotion)
                        continue
                    }
                    convertSingle(create.plaidTransaction, accountMap, importTag)
                }

                // In both of these cases a Firefly transaction already exists. We don't need to do anything to it.
                // If we have an associated Plaid transaction, log a message. Otherwise, silently ignore it.
                is PlaidFireflyTransaction.FireflyTransaction -> continue
                is PlaidFireflyTransaction.MatchedTransaction -> {
                    logger.debug(
                        "Ignoring Plaid transaction id {} because it already has a corresponding Firefly transaction {}",
                        create.plaidTransaction.transactionId,
                        create.fireflyTransaction.id,
                    )
                    continue
                }

                is PlaidFireflyTransaction.Transfer -> {
                    if (create.withdrawal.fireflyTransaction != null && create.deposit.fireflyTransaction != null) {
                        logger.debug("TransferMatcher found multiple existing Firefly transactions that appear to "
                                + "be a transfer. Converting multiple existing Firefly transactions to a transfer is "
                                + "not supported. Skipping: {}", create)
                        continue
                    }

                    val fireflyComponent = create.fireflyTransaction
                    if (fireflyComponent != null) {
                        val converted = convertDoubleFirefly(
                            requirePlaidTransaction(create),
                            fireflyComponent,
                            accountMap,
                            importTag,
                        )
                        // Converted in place, so keep the user's tags and reconciliation on the existing transaction
                        val target = existingFireflyTxs.firstOrNull { it.id == converted.id }
                        if (target == null) converted
                        else FireflyTransactionDto(converted.id, preserveUserFields(converted.tx, target, keepCounterparty = false))
                    } else {
                        convertDoublePlaid(
                            requirePlaidTransaction(create.deposit),
                            requirePlaidTransaction(create.withdrawal),
                            accountMap,
                            importTag,
                        )
                    }
                }
            }

            if (convertedSingle.id == null) {
                // A create whose external id is already in Firefly (whatever its type) was inserted by an earlier,
                //  partly failed iteration, possibly as one leg of a transfer. Skip it so a retry never records the
                //  same money twice.
                val externalId = convertedSingle.tx.externalId
                if (externalId != null && indexer.findByExternalId(externalId) != null) {
                    logger.info("Skipping create of {}: Firefly already has a transaction with that external id", externalId)
                    continue
                }
                creates.add(convertedSingle)
            } else {
                updates.add(convertedSingle)
            }
        }

        /**
         * Handle Plaid updates
         */
        for (plaidUpdate in plaidUpdatedTxs) {
            val target = indexer.findExistingFireflyTx(plaidUpdate.transactionId)
            if (target == null) {
                logger.error("Failed to find existing Firefly transaction to update for Plaid id ${plaidUpdate.transactionId}")
                continue
            }

            val convertedUpdate = convertSingle(plaidUpdate, accountMap)
            val existingSplit = target.attributes.transactions.singleOrNull()
            val update = if (existingSplit?.type == TransactionTypeProperty.transfer) {
                // This Plaid transaction is one leg of a transfer we created. Firefly can't turn a transfer back
                //  into a withdrawal/deposit, and the counterparty on a transfer is a real account, so keep both
                //  account ids exactly as they are and only refresh what Plaid owns (amount, date, description,
                //  tags).
                convertedUpdate.tx.copy(
                    sourceId = existingSplit.sourceId,
                    sourceName = null,
                    destinationId = existingSplit.destinationId,
                    destinationName = null,
                    description = existingSplit.description,
                )
            } else {
                convertedUpdate.tx
            }
            updates.add(FireflyTransactionDto(target.id, preserveUserFields(update, target)))
        }
        /**
         * Handle Plaid deletes
         */
        for (plaidDeleteId in plaidDeletedTxs) {
            val target = indexer.findExistingFireflyTx(plaidDeleteId)
            if (target == null) {
                logger.error("Failed to find existing Firefly transaction to delete for Plaid id $plaidDeleteId")
                continue
            }
            if (promotedFireflyIds.contains(target.id)) {
                logger.debug("Not deleting Firefly transaction {}: it was updated in place from pending to posted", target.id)
                continue
            }

            deletes.add(target.id)
        }

        return ConvertPollSyncResult(
            creates = creates,
            updates = updates,
            deletes = deletes,
        )
    }

    /**
     * If [posted] is the posted version of a pending transaction that we already imported into Firefly (found via
     * Plaid's pending_transaction_id), builds an in-place update of that Firefly transaction. The external id moves to
     * the posted Plaid id so later Plaid updates and deletes find it.
     *
     * Returns null when the transaction should instead go through the normal delete-and-create path: it isn't linked
     * to a pending one, the pending one isn't in Firefly (outside the pull window, or never imported), or the
     * Firefly transaction can't be updated in place (it's split, or its type differs from the posted one, as when it
     * has since become a transfer or the amount changed sign; Firefly can't change a transaction's type on update).
     */
    private suspend fun convertPendingPromotion(
        posted: PlaidTransaction,
        indexer: FireflyTransactionExternalIdIndexer,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
    ): FireflyTransactionDto? {
        val pendingId = posted.pendingTransactionId ?: return null
        val target = indexer.findExistingFireflyTx(pendingId) ?: return null
        val existingSplit = target.attributes.transactions.singleOrNull() ?: return null
        if (existingSplit.type != getFireflyTransactionDtoType(posted, false)) {
            logger.debug(
                "Not updating pending Firefly transaction {} in place: type {} differs from posted type",
                target.id, existingSplit.type,
            )
            return null
        }
        val converted = convertSingle(posted, accountMap)
        return FireflyTransactionDto(target.id, preserveUserFields(converted.tx, target))
    }

    /**
     * Prepares a converted transaction to be sent as an update to the existing Firefly transaction [target]:
     * - Plaid-derived tags (category and pending tags) are refreshed, but tags the user added in Firefly are kept.
     *   Sending just the Plaid tags would remove the user's.
     * - The existing currency is carried over, which Firefly requires on updates.
     * - Reconciled flag, description and counterparty name are not sent (see the comments in the body), unless
     *   [keepCounterparty] is false: a conversion to a transfer needs both accounts spelled out.
     * Fields the connector never sets (category, budget, notes, ...) are null here and are omitted from the update
     *  JSON (see TransactionSplitUpdate), so the user's values for them are untouched.
     */
    private fun preserveUserFields(
        update: TransactionSplit,
        target: TransactionRead,
        keepCounterparty: Boolean = true,
    ): TransactionSplit {
        val existingSplit = target.attributes.transactions.firstOrNull() ?: return update
        return update.copy(
            tags = mergeTags(existingSplit.tags, update.tags),
            currencyId = existingSplit.currencyId ?: update.currencyId,
            currencyCode = existingSplit.currencyCode ?: update.currencyCode,
            // The connector sets reconciled=false on create; sending it again would un-reconcile what the user
            //  reconciled. null omits it from the update JSON.
            reconciled = null,
            order = null,
            // Description and counterparty are the user's to edit once imported (Plaid's are only a first guess), so
            //  an update keeps the existing ones. The account on our side stays Plaid-owned.
            description = if (keepCounterparty) existingSplit.description else update.description,
            sourceName = if (keepCounterparty) null else update.sourceName,
            destinationName = if (keepCounterparty) null else update.destinationName,
        )
    }

    /**
     * Existing tags minus the ones this connector manages (category prefixes, pending tag), plus [plaidTags].
     */
    private fun mergeTags(existing: List<String>?, plaidTags: List<String>?): List<String> {
        val managedPrefixes = listOf(primaryCategoryPrefix, detailedCategoryPrefix).filter { it.isNotBlank() }
        val userTags = (existing ?: listOf()).filter { tag ->
            tag != pendingTag && managedPrefixes.none { tag.startsWith(it) }
        }
        return (userTags + (plaidTags ?: listOf())).distinct()
    }

    fun filterFireflyCandidateTransferTxs(
        input: List<TransactionRead>,
    ): List<FireflyTransactionDto> {
        return input
            /**
             * Filter out split transactions; we're not going to bother trying to match those up as transfers
             */
            .filter { it.attributes.transactions.size == 1 }
            /**
             * Filter out transactions of ineligible types
             */
            .filter { fireflyTxTypesEligibleForConversion.contains(
                it.attributes.transactions.first().type
            ) }
            .map { FireflyTransactionDto(it.id, it.attributes.transactions.first()) }

    }

    /**
     * @param importTag tag to add for this run; null for Plaid updates, which must not be re-tagged
     */
    protected suspend fun convertSingle(
        tx: PlaidTransaction,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        importTag: String? = null,
    ): FireflyTransactionDto {
        logger.trace("Starting ${::convertSingle.name}")
        val fireflyAccountId = accountMap[tx.accountId]?.toString()
            ?: throw RuntimeException("Failed to find Firefly account mapping for Plaid account ${tx.accountId}")

        val sourceId: String?
        val sourceName: String?
        val destinationId: String?
        val destinationName: String?
        if (getTransactionDirection(tx) == Direction.IN) {
            destinationId = fireflyAccountId
            destinationName = null

            sourceId = null
            sourceName = if (disableAccountAssignment) null else getSourceOrDestinationName(tx, true)
        } else {
            sourceId = fireflyAccountId
            sourceName = null

            destinationId = null
            destinationName = if (disableAccountAssignment) null else getSourceOrDestinationName(tx, false)
        }
        return convert(
            tx = tx,
            isPair = false,
            sourceId = sourceId,
            sourceName = sourceName,
            destinationId = destinationId,
            destinationName = destinationName,
            importTag = importTag,
        )
    }

    protected suspend fun convertDoublePlaid(
        a: PlaidTransaction,
        b: PlaidTransaction,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        importTag: String? = null,
    ): FireflyTransactionDto {
        val (sourceTx, destinationTx) = if (a.amount < 0.0) Pair(b, a) else Pair(a, b)
        return convert(
            // The destination transaction tends to have the most relevant categorization information
            tx = destinationTx,
            isPair = true,
            sourceId = accountMap[sourceTx.accountId]?.toString()
                ?: throw RuntimeException("Failed to find Firefly account mapping for Plaid account ${sourceTx.accountId}"),
            destinationId = accountMap[destinationTx.accountId]?.toString()
                ?: throw RuntimeException("Failed to find Firefly account mapping for Plaid account ${destinationTx.accountId}"),
            importTag = importTag,
        )
    }

    protected suspend fun convertDoubleFirefly(
        plaidTx: PlaidTransaction,
        fireflyTx: FireflyTransactionDto,
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        importTag: String? = null,
    ): FireflyTransactionDto {
        val plaidTxFireflyAccountId = accountMap[plaidTx.accountId]
            ?: throw RuntimeException("Failed to find Firefly account mapping for Plaid account ${plaidTx.accountId}")

        /**
         * With transfers, the two component transactions are the inverse of each other.
         * Since we know the transaction amounts have opposite signs, we know, in Firefly terms, one is a deposit
         *  and one is a withdrawal.
         *
         * For Plaid transactions, we can only be confident in the accuracy of the account the transaction is
         *  "on," the rest is just based on the name field, which isn't super accurate.
         * For Firefly transactions, it depends;
         * - Withdrawal: positive Plaid value. The Firefly tx would have the source account right, and the
         *  destination account would be garbage.
         * - Deposit: negative Plaid value. The Firefly tx would have the destination account right, and the
         *  source account would be garbage.
         */
        val sourceId: String?
        val sourceName: String?
        val destinationId: String?
        val destinationName: String?
        when (fireflyTx.tx.type) {
            TransactionTypeProperty.withdrawal -> {
                sourceName = fireflyTx.tx.sourceName
                sourceId = fireflyTx.tx.sourceId

                destinationName = null
                destinationId = plaidTxFireflyAccountId.toString()
            }

            TransactionTypeProperty.deposit -> {
                sourceName = null
                sourceId = plaidTxFireflyAccountId.toString()

                destinationName = fireflyTx.tx.destinationName
                destinationId = fireflyTx.tx.destinationId
            }

            else -> throw IllegalArgumentException(
                "Unable to convert an existing Firefly transaction of type " +
                        "${fireflyTx.tx.type} to a transfer."
            )
        }

        return convert(
            tx = plaidTx,
            isPair = true,
            sourceId = sourceId,
            sourceName = sourceName,
            destinationId = destinationId,
            destinationName = destinationName,
            fireflyTx = fireflyTx,
            importTag = importTag,
        )
    }

    /**
     * @param fireflyTx Only included in cases where we're converting a Plaid/Firefly tx pair into a transfer.
     */
    protected suspend fun convert(
        tx: PlaidTransaction,
        isPair: Boolean,
        sourceId: String? = null,
        sourceName: String? = null,
        destinationId: String? = null,
        destinationName: String? = null,
        fireflyTx: FireflyTransactionDto? = null,
        importTag: String? = null,
    ): FireflyTransactionDto {
        val postedTime = getTxPostedTimestamp(tx)
        val authorizedTime = getTxAuthorizedTimestamp(tx)
        val externalUrl = if (tx.website != null) {
            // Plaid does not provide the protocol in the string. Firefly requires a protocol.
            URLBuilder(
                protocol = URLProtocol.HTTPS,
                host = tx.website,
            ).buildString()
        } else {
            null
        }
        val split = TransactionSplit(
            getFireflyTransactionDtoType(tx, isPair),
            // Plaid's guidance on using authorized date vs posted date:
            // The authorized_date, when available, is generally preferable to use over the date field for posted
            // transactions, as it will generally represent the date the user actually made the transaction.
            // Source: https://plaid.com/docs/api/products/transactions/#transactionssync
            authorizedTime ?: postedTime,
            /**
             * Always positive per https://github.com/firefly-iii/firefly-iii/issues/2476
             * "Direction" of transactions handled in [getFireflyTransactionDtoType]
             */
            // toPlainString: Double.toString switches to scientific notation ("1.0E7") from ten million up, which
            //  Firefly rejects
            java.math.BigDecimal.valueOf(abs(tx.amount)).toPlainString(),
            fireflyTx?.tx?.description ?: getTxDescription(tx),
            processDate = postedTime,
            sourceId = sourceId,
            sourceName = sourceName,
            destinationId = destinationId,
            destinationName = destinationName,
            tags = getFireflyCategoryTags(tx) + listOfNotNull(importTag),
            latitude = tx.location.lat,
            longitude = tx.location.lon,
            externalUrl = externalUrl,
            externalId = FireflyTransactionExternalIdIndexer.getExternalId(tx.transactionId),
            order = 0,
            reconciled = false,
            // These are all explicitly required, but only for updates
            currencyId = fireflyTx?.tx?.currencyId,
            currencyCode = fireflyTx?.tx?.currencyCode,
        )
        return FireflyTransactionDto(
            fireflyTx?.transactionId,
            split,
        )
    }

    /**
     * This is our primary mechanism for enabling the use of Firefly's budgets and categories.
     * The intent is to use this functionality to tag Firefly transactions with Plaid category data,
     *  then use those tags with Firefly's rule engine to apply categories and budgets as desired.
     */
    protected suspend fun getFireflyCategoryTags(tx: PlaidTransaction): List<String> {
        val tagz = mutableListOf<String>()
        if (tx.pending && pendingTag.isNotBlank()) {
            tagz.add(pendingTag)
        }
        if (tx.personalFinanceCategory == null) {
            return tagz
        }
        if (enablePrimaryCategorization) {
            val primaryCat = tx.personalFinanceCategory.primary
            tagz.add(primaryCategoryPrefix + convertScreamingSnakeCaseToKebabCase(primaryCat))
        }
        if (enableDetailedCategorization) {
            val detailedCat = PersonalFinanceCategoryEnum.from(tx.personalFinanceCategory).detailed.name
            tagz.add(detailedCategoryPrefix + convertScreamingSnakeCaseToKebabCase(detailedCat))
        }
        return tagz
    }

    /**
     * [Firefly transaction types](https://docs.firefly-iii.org/firefly-iii/support/transaction_types/)
     * See [getPlaidAmount] for sort of the inverse of this.
     *
     * @param isPair True if [t] is part of a pair of offsetting Plaid transactions, false otherwise.
     */
    suspend fun getFireflyTransactionDtoType(t: PlaidTransaction, isPair: Boolean): TransactionTypeProperty {

        /**
         * Per Firefly docs:
         * Transfers are internal transactions that don't influence your bottom line.
         * A transfer is created only between existing asset accounts.
         * Select an asset account for both the source and destination from the free-form fields.
         * Transfers can be linked to piggy banks, to automatically add or remove money from the piggy bank you select.
         */
        if (isPair) {
            return TransactionTypeProperty.transfer
        }

        /**
         * Per Firefly docs:
         * Withdrawals represent money that you spent that you can't get back easily unless the receiving party sends it to you.
         * Deposits represent money that you received from others.
         *
         * Per Plaid docs on `amount`:
         * Positive values when money moves out of the account; negative values when money moves in. For example,
         *  debit card purchases are positive; credit card payments, direct deposits, and refunds are negative.
         */
        return if (t.amount > 0) {
            TransactionTypeProperty.withdrawal
        } else {
            return TransactionTypeProperty.deposit
        }
    }
}

/**
 * Container data class for the values passed into the SpEL evaluator context. Although there is currently only one
 * value here, wrapping that value will make it much easier in the future if we ever want to make other objects
 * available during SpEL evaluation.
 */
data class FormattingContext(val transaction: PlaidTransaction)
