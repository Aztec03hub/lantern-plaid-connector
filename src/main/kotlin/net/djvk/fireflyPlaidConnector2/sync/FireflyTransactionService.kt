package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.CancellationException
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionExternalIdIndexer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * Service for handling Firefly transaction operations.
 */
@Component
class FireflyTransactionService(
    private val fireflyTxApi: TransactionsApi,
    private val syncHelper: SyncHelper,
    
    @Value("\${fireflyPlaidConnector2.polled.existingFireflyPullWindowDays}")
    private val existingFireflyPullWindowDays: Int,

    /** "Today" is evaluated in the user's configured time zone, not the container's (which is usually UTC). */
    @Value("\${fireflyPlaidConnector2.timeZone:UTC}")
    timeZoneString: String = "UTC",

    /** Used to look up Firefly transactions that are older than the pull window; see [fetchMissingByPlaidId]. */
    private val fireflySearchApi: SearchApi? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val fireflyPageCountMax = 20
    private val zoneId = ZoneId.of(timeZoneString)

    /**
     * Fetches all Firefly transactions within the configured window.
     */
    suspend fun fetchExistingFireflyTransactions(): List<TransactionRead> {
        val existingFireflyTxs = mutableListOf<TransactionRead>()
        val today = LocalDate.now(zoneId)
        val transferWindowStart = today.minusDays(existingFireflyPullWindowDays.toLong())

        // Firefly pages are 1-based; starting at 0 fetched page 1 twice
        var fireflyTxPage = 1
        var lastPageHadMore: Boolean
        do {
            logger.debug("Fetching page $fireflyTxPage of Firefly transactions with window starting at $transferWindowStart")
            val response = fireflyTxApi.listTransaction(
                fireflyTxPage++,
                transferWindowStart,
                today,
                TransactionTypeFilter.all,
            ).body()
            val pagination = response.meta.pagination

            /**
             * Don't do any more filtering here, we will need all transactions for potentially matching
             *  up to update and delete requests.
             *
             * See [TransactionConverter.filterFireflyCandidateTransferTxs] for the filtering we do
             *  before trying to match up transfers.
             */
            val filteredTxs = response.data
            logger.debug("Fetched ${filteredTxs.size} existing Firefly single-split, non transfer transactions with window starting at $transferWindowStart")
            existingFireflyTxs.addAll(filteredTxs)
            lastPageHadMore = pagination != null && pagination.currentPage < pagination.totalPages
            // The page cap is a failsafe against an infinite loop
        } while (lastPageHadMore && fireflyTxPage <= fireflyPageCountMax)

        // Only an error if there really were more pages we refused to read (not merely when the last allowed page
        //  was also the last page)
        if (lastPageHadMore) {
            throw RuntimeException("Exceeded Firefly failsafe max page count $fireflyPageCountMax")
        }

        return existingFireflyTxs
    }

    /**
     * Plaid can update or remove a transaction long after we imported it, and a pending transaction can take days to
     * post, so the Firefly transactions it refers to may be older than the pull window. Without them the connector
     * logs "Failed to find existing Firefly transaction" and the change is never applied.
     *
     * This looks up, via Firefly's search (`external_id_is:`), the ones for [plaidTransactionIds] that aren't already
     * in [alreadyFetched]. Capped at [maxLookups] per call to bound the extra API traffic. Lookup failures are
     * logged and skipped, never fatal.
     */
    suspend fun fetchMissingByPlaidId(
        plaidTransactionIds: Collection<String>,
        alreadyFetched: List<TransactionRead>,
        maxLookups: Int = 100,
    ): List<TransactionRead> {
        val searchApi = fireflySearchApi ?: return listOf()
        val known = FireflyTransactionExternalIdIndexer(alreadyFetched)
        val missing = plaidTransactionIds.distinct().filter { known.findExistingFireflyTx(it) == null }
        if (missing.isEmpty()) return listOf()
        if (missing.size > maxLookups) {
            logger.warn("{} Plaid transactions are outside the Firefly pull window; looking up only the first {}", missing.size, maxLookups)
        }

        val found = mutableListOf<TransactionRead>()
        for (plaidId in missing.take(maxLookups)) {
            val externalId = FireflyTransactionExternalIdIndexer.getExternalId(plaidId)
            try {
                val match = searchApi.searchTransactions("external_id_is:\"$externalId\"", 1).body().data
                    .firstOrNull { read -> read.attributes.transactions.any { it.externalId == externalId } }
                if (match != null) found.add(match)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                logger.warn("Firefly search for external id {} failed: {}", externalId, e::class.simpleName)
            }
        }
        logger.debug("Found {} of {} out-of-window Firefly transactions by external id", found.size, missing.size)
        return found
    }

    /**
     * Processes transaction updates in Firefly.
     */
    suspend fun processFireflyTransactionUpdates(
        creates: List<FireflyTransactionDto>,
        updates: List<FireflyTransactionDto>,
        deletes: List<String>
    ) {
        // Insert new transactions
        syncHelper.optimisticInsertBatchIntoFirefly(creates)
        
        // Process updates
        /**
         * All updates here will either be updates of existing Firefly transactions that have been
         *  paired with incoming Plaid creates to become transfers, or updates coming in directly from Plaid.
         *
         * Split them here so we can handle them separately.
         */
        val (transferUpdates, nonTransferUpdates) = updates.partition { it.tx.type == TransactionTypeProperty.transfer }
        processFireflyTransferUpdates(transferUpdates)
        processFireflyNonTransferUpdates(nonTransferUpdates)

        // Process deletes
        syncHelper.deleteBatchInFirefly(deletes)
    }

    /**
     * Firefly's transaction update endpoint does not allow changing transaction types
     *  (i.e. deposit to transfer), so in cases where we're trying to update existing
     *  Firefly non-transfer transactions (combined with an incoming Plaid create) to become
     *  transfer transactions, we have to resolve the updates as deletes and creates.
     * I'm not crazy about this because any other reference to the existing record will be
     *  broken, but such is life (and this behavior has been around for a while at this point).
     */
    private suspend fun processFireflyTransferUpdates(updates: List<FireflyTransactionDto>) {
        for (update in updates) {
            update.id ?: throw IllegalArgumentException("Unexpected transfer update tx missing id: $update")

            /**
             * Delete first, if that fails, don't do the create.
             */
            try {
                syncHelper.deleteBatchInFirefly(listOf(update.id))
            } catch (e: Exception) {
                logger.error(
                    "Failed to execute delete as first part of updating transaction ${update.id}; " +
                            "aborting create part of update operation", e
                )
                continue
            }

            /**
             * This should not be a duplicate, so allow an exception to propagate if it is
             */
            syncHelper.pessimisticInsertBatchIntoFirefly(listOf(update))
        }
    }

    /**
     * Updates direct from Plaid will always be non-transfers (see comment a few lines down
     *  in [TransactionConverter.convertPollSync]) because we're currently not trying to handle
     *  the complexity of Plaid updates being applied to Firefly transfers (which themselves
     *  originated as two distinct Plaid transactions).
     * Because Plaid direct updates are not transfers, we can update them directly in Firefly.
     */
    private suspend fun processFireflyNonTransferUpdates(updates: List<FireflyTransactionDto>) {
        syncHelper.updateBatchInFirefly(updates)
    }
}