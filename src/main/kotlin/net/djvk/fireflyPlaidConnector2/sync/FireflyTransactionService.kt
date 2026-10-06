package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
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

    /** First day of the pull window; Firefly transactions dated before it are not returned by [fetchExistingFireflyTransactions]. */
    fun windowStart(): LocalDate = LocalDate.now(zoneId).minusDays(existingFireflyPullWindowDays.toLong())

    /**
     * Plaid can update or remove a transaction long after we imported it, a pending transaction can take days to
     * post, and an iteration that failed part way is retried with the same Plaid creates, so the Firefly transactions
     * a poll refers to may be older than the pull window.
     *
     * This looks up, via Firefly's search (`external_id_is:`), the ones for [plaidTransactionIds] that aren't already
     * in [alreadyFetched]. Every id is looked up (a history import needs them all) and a failed lookup propagates:
     * swallowing it would let the caller commit its Plaid cursor over a change that was never applied or a create
     * that would be inserted twice.
     * ponytail: one search request per id, sequential; batch or parallelise if a 24-month history import is too slow.
     */
    suspend fun fetchMissingByPlaidId(
        plaidTransactionIds: Collection<String>,
        alreadyFetched: List<TransactionRead>,
    ): List<TransactionRead> {
        val searchApi = fireflySearchApi ?: return listOf()
        val known = FireflyTransactionExternalIdIndexer(alreadyFetched)
        val missing = plaidTransactionIds.distinct().filter { known.findExistingFireflyTx(it) == null }
        if (missing.isEmpty()) return listOf()

        val found = mutableListOf<TransactionRead>()
        for (plaidId in missing) {
            val externalId = FireflyTransactionExternalIdIndexer.getExternalId(plaidId)
            val match = searchApi.searchTransactions("external_id_is:\"$externalId\"", 1).body().data
                .firstOrNull { read -> read.attributes.transactions.any { it.externalId == externalId } }
            if (match != null) found.add(match)
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
        
        // Process updates. This includes converting an existing deposit/withdrawal into a transfer, which is an in-place
        //  update with type=transfer (Firefly 6.7.7 accepts that), so every Firefly write here is safe to repeat.
        syncHelper.updateBatchInFirefly(updates)

        // Process deletes
        syncHelper.deleteBatchInFirefly(deletes)
    }
}
