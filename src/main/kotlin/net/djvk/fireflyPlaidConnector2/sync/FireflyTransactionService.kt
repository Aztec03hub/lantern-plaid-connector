package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
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
    private val fireflySearchApi: SearchApi,

    /** Where Firefly writes that were permanently rejected are kept; null means a rejected write fails the iteration. */
    private val deadLetters: DeadLetterStore? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val fireflyPageCountMax = 20
    private val zoneId = ZoneId.of(timeZoneString)

    /**
     * Fetches all Firefly transactions within the configured window, or between [start] and [end] (inclusive) when
     * given. [maxPages] is a failsafe: exceeding it is an error rather than a silently truncated list.
     */
    suspend fun fetchExistingFireflyTransactions(): List<TransactionRead> =
        fetchFireflyTransactionsBetween(windowStart(), LocalDate.now(zoneId), fireflyPageCountMax)

    suspend fun fetchFireflyTransactionsBetween(
        start: LocalDate,
        end: LocalDate,
        maxPages: Int,
    ): List<TransactionRead> {
        val existingFireflyTxs = mutableListOf<TransactionRead>()
        val today = end
        val transferWindowStart = start

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
        } while (lastPageHadMore && fireflyTxPage <= maxPages)

        // Only an error if there really were more pages we refused to read (not merely when the last allowed page
        //  was also the last page)
        if (lastPageHadMore) {
            throw RuntimeException("Exceeded Firefly failsafe max page count $maxPages")
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
        val searchApi = fireflySearchApi
        val known = FireflyTransactionExternalIdIndexer(alreadyFetched)
        val missing = plaidTransactionIds.distinct().filter { known.findExistingFireflyTx(it) == null }
        if (missing.isEmpty()) return listOf()

        val found = mutableListOf<TransactionRead>()
        for (plaidId in missing) {
            val externalId = FireflyTransactionExternalIdIndexer.getExternalId(plaidId)
            // A transfer built from two Plaid transactions has one leg's id as its external id and the other's as
            //  its internal reference, so a miss on the first is followed by the second.
            val match = searchApi.searchTransactions("external_id_is:\"$externalId\"", 1).body().data
                .firstOrNull { read -> read.attributes.transactions.any { it.externalId == externalId } }
                ?: searchApi.searchTransactions("internal_reference_is:\"$externalId\"", 1).body().data
                    .firstOrNull { read -> read.attributes.transactions.any { it.internalReference == externalId } }
            if (match != null) found.add(match)
        }
        logger.debug("Found {} of {} out-of-window Firefly transactions by external id", found.size, missing.size)
        return found
    }

    /**
     * Processes transaction updates in Firefly.
     *
     * Each write goes through [guarded]: a write Firefly permanently rejects for one transaction is kept in the dead
     * letter file and the rest carry on, so one bad transaction can't stall every bank. Anything else (network, 5xx,
     * authentication, rate limit) still fails the iteration, which is then retried as a whole.
     */
    suspend fun processFireflyTransactionUpdates(
        creates: List<FireflyTransactionDto>,
        updates: List<FireflyTransactionDto>,
        deletes: List<String>
    ) {
        // Insert new transactions
        for (create in creates) {
            guarded(DeadLetter("create", create.tx.externalId ?: "", null, create.tx, false)) {
                syncHelper.optimisticInsertBatchIntoFirefly(listOf(create))
            }
        }

        // Process updates. This includes converting an existing deposit/withdrawal into a transfer, which is an in-place
        //  update with type=transfer (Firefly 6.7.7 accepts that), so every Firefly write here is safe to repeat.
        for (update in updates) {
            guarded(DeadLetter("update", update.transactionId, update.id, update.tx, update.changesType)) {
                syncHelper.updateBatchInFirefly(listOf(update))
            }
        }

        // Process deletes
        for (id in deletes) {
            guarded(DeadLetter("delete", id, id)) { syncHelper.deleteBatchInFirefly(listOf(id)) }
        }
    }

    /**
     * Retries the writes Firefly rejected in earlier polls. The ones that go through are removed from the dead letter
     * file; the ones that are still rejected stay (with Firefly's latest message). A create whose external id is
     * already in Firefly is dropped rather than inserted again.
     */
    suspend fun retryDeadLetters() {
        val store = deadLetters ?: return
        for (letter in store.read()) {
            if (letter.operation == "create" && letter.key.startsWith(FireflyTransactionExternalIdIndexer.EXTERNAL_ID_PREFIX) &&
                fetchMissingByPlaidId(listOf(letter.key.removePrefix(FireflyTransactionExternalIdIndexer.EXTERNAL_ID_PREFIX)), listOf()).isNotEmpty()
            ) {
                store.remove(letter.operation, letter.key)
                continue
            }
            guarded(letter) {
                val split = letter.split
                when (letter.operation) {
                    "create" -> syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, split!!)))
                    "update" -> syncHelper.updateBatchInFirefly(listOf(FireflyTransactionDto(letter.fireflyId, split!!, letter.changesType)))
                    "delete" -> syncHelper.deleteBatchInFirefly(listOf(letter.fireflyId!!))
                }
            }
        }
    }

    /** Runs [write] for [letter]'s transaction; see [processFireflyTransactionUpdates] for what is and isn't dead-lettered. */
    private suspend fun guarded(letter: DeadLetter, write: suspend () -> Unit) {
        val store = deadLetters
        try {
            write()
            store?.remove(letter.operation, letter.key)
        } catch (cre: ClientRequestException) {
            val status = cre.response.status.value
            // 401/403 mean the Firefly credentials are wrong for every transaction; 408/429 are transient
            if (store == null || status !in 400..499 || status in setOf(401, 403, 408, 429)) throw cre
            val message = runCatching { cre.response.bodyAsText() }.getOrDefault("")
                .let { Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(it)?.groupValues?.get(1) } ?: "HTTP $status"
            logger.error(
                "Firefly permanently rejected ${letter.operation} of ${letter.key} (HTTP $status: $message); " +
                        "kept in the dead letter file and retried every poll"
            )
            store.add(letter.copy(message = "HTTP $status: $message"))
        }
    }
}
