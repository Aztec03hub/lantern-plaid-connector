package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.PlaidLinkIndexer
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

    /** Used to find the Firefly transactions of Plaid ids outside the pull window; see [fetchMissingByPlaidId]. */
    private val plaidLinksApi: PlaidLinksApi,

    /** Where Firefly writes that were permanently rejected are kept; null means a rejected write fails the iteration. */
    private val deadLetters: DeadLetterStore? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val fireflyPageCountMax = 20
    private val maxRangeTransactions = 200_000
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
            // Fail on the size of the range, not on a page count, which depends on the Firefly user's page size preference
            if (pagination != null && (pagination.total ?: 0) > maxRangeTransactions) {
                throw RuntimeException("Firefly range read of ${pagination?.total} transactions exceeds the failsafe $maxRangeTransactions")
            }

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
     * post, and a transfer's second leg can arrive long after its first, so the Firefly transactions a poll refers to
     * may be older than the pull window.
     *
     * This resolves the ones for [plaidTransactionIds] that aren't already in [alreadyFetched]: one link lookup per 500
     * ids (`GET /plaid-links`), then one read per Firefly transaction found. A failed lookup propagates: swallowing it
     * would let the caller commit its Plaid cursor over a change that was never applied.
     */
    suspend fun fetchMissingByPlaidId(
        plaidTransactionIds: Collection<String>,
        alreadyFetched: List<TransactionRead>,
    ): List<TransactionRead> {
        val known = PlaidLinkIndexer(alreadyFetched)
        val missing = plaidTransactionIds.distinct().filter { known.find(it) == null }
        if (missing.isEmpty()) return listOf()

        val groupIds = linkedSetOf<String>()
        for (chunk in missing.chunked(PlaidLinksApi.MAX_IDS)) {
            plaidLinksApi.lookupPlaidLinks(chunk).body().data.mapTo(groupIds) { it.transactionGroupId }
        }
        val found = groupIds.map { fireflyTxApi.getTransaction(it).body().data }
        logger.debug("Found {} Firefly transactions for {} Plaid ids outside the window", found.size, missing.size)
        return found
    }

    /** Which of [plaidTransactionIds] Firefly already holds: link lookups only, no transaction reads. */
    suspend fun heldPlaidIds(plaidTransactionIds: Collection<String>): Set<String> =
        plaidTransactionIds.distinct().chunked(PlaidLinksApi.MAX_IDS)
            .flatMap { chunk -> plaidLinksApi.lookupPlaidLinks(chunk).body().data.map { it.plaidTransactionId } }
            .toSet()

    /**
     * Processes transaction updates in Firefly.
     *
     * Each write goes through [guarded]: a write Firefly permanently rejects for one transaction is kept in the dead
     * letter file and the rest carry on, so one bad transaction can't stall every bank. Anything else (network, 5xx,
     * authentication, rate limit) still fails the iteration, which is then retried as a whole.
     *
     * A 409 (a Plaid id Firefly already holds, see the link table) is not a rejection: it means "already imported", so
     * it is logged, dropped and never dead-lettered.
     *
     * @return how many of [creates] did not create a transaction (dead-lettered, or already imported), so the caller
     *  doesn't count them as created
     */
    suspend fun processFireflyTransactionUpdates(
        creates: List<FireflyTransactionDto>,
        updates: List<FireflyTransactionDto>,
        deletes: List<String>
    ): Int {
        var notCreated = 0
        // Insert new transactions
        for (create in creates) {
            if (!createGuarded(create)) notCreated++
        }

        // Process updates. This includes converting an existing deposit/withdrawal into a transfer, which is an in-place
        //  update with type=transfer (Firefly 6.7.7 accepts that), so every Firefly write here is safe to repeat.
        for (update in updates) {
            // An update that adds a Plaid leg to a transaction Firefly no longer has (404) creates that leg on its own
            //  instead, so its money is not hidden behind a failed update. A pairing (the update makes a transfer) does
            //  that on a 422 too: the existing transaction stays as it was and the new leg is recorded nowhere else. A
            //  pending to posted update does NOT: after a 422 the pending transaction still holds that money, and this
            //  sync's removal of the pending id was shielded, so a posted create beside it would count it twice.
            val fallback = update.fallbackCreate
            guarded(
                DeadLetter("update", update.transactionId, update.id, update.tx, update.changesType),
                instead = fallback?.let { { createGuarded(it) } },
                insteadOn = if (update.tx.type == TransactionTypeProperty.transfer) setOf(404, 422) else setOf(404),
            ) {
                syncHelper.updateBatchInFirefly(listOf(update))
            }
        }

        // Process deletes
        for (id in deletes) {
            guarded(DeadLetter("delete", id, id)) { syncHelper.deleteBatchInFirefly(listOf(id)) }
        }
        return notCreated
    }

    /** @return true if [create] made a new Firefly transaction (false: dead-lettered, or already imported) */
    private suspend fun createGuarded(create: FireflyTransactionDto): Boolean {
        var created = 0
        val kept = guarded(DeadLetter("create", letterKey(create.tx), null, create.tx, false)) {
            created = syncHelper.optimisticInsertBatchIntoFirefly(listOf(create))
        }
        return !kept && created > 0
    }

    companion object {
        /** What identifies a create in the dead letter file: its first Plaid id, so a later Plaid event for it finds it. */
        fun letterKey(split: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit): String =
            split.plaidLinks?.firstOrNull()?.plaidTransactionId ?: ""
    }

    /**
     * Retries the writes Firefly rejected in earlier polls. The ones that go through are removed from the dead letter
     * file; the ones that are still rejected stay (with Firefly's latest message) until [DeadLetterStore.maxAttempts]
     * retries have failed, after which they are abandoned: no longer retried, but still in the file and still reported.
     * A create whose Plaid id is already in Firefly answers 409 and is dropped rather than inserted again.
     */
    suspend fun retryDeadLetters() {
        val store = deadLetters ?: return
        for (letter in store.read()) {
            if (letter.abandoned) continue
            if (letter.operation == "create" && letter.split?.plaidLinks.isNullOrEmpty()) {
                // Written by a build from before the link table: sent now it would be unprotected against duplicates
                logger.error(
                    "The dead-lettered create ${letter.key} carries no Plaid link (older build); ABANDONED, enter it by hand if it is missing"
                )
                store.add(letter.copy(abandoned = true))
                continue
            }
            guarded(letter, retry = true) {
                val split = letter.split
                when (letter.operation) {
                    "create" -> syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, split!!)))
                    "update" -> syncHelper.updateBatchInFirefly(listOf(FireflyTransactionDto(letter.fireflyId, split!!, letter.changesType)))
                    "delete" -> syncHelper.deleteBatchInFirefly(listOf(letter.fireflyId!!))
                }
            }
        }
    }

    /**
     * Runs [write] for [letter]'s transaction; see [processFireflyTransactionUpdates] for what is and isn't dead-lettered.
     *
     * A 404 on an update or delete means the Firefly transaction is gone (the user deleted it, or a later removal
     * did): that write can never succeed, so the letter is dropped with a WARN instead of being retried forever.
     *
     * A 409 means Firefly already holds a Plaid id of this write (the link table refuses a second row for it): the
     * money is recorded, so the letter is dropped and nothing is retried.
     *
     * @return true if the write was kept as a dead letter
     */
    private suspend fun guarded(
        letter: DeadLetter,
        retry: Boolean = false,
        instead: (suspend () -> Unit)? = null,
        insteadOn: Set<Int> = setOf(),
        write: suspend () -> Unit,
    ): Boolean {
        val store = deadLetters
        try {
            write()
            store?.remove(letter.operation, letter.key)
            return false
        } catch (cre: ClientRequestException) {
            val status = cre.response.status.value
            if (status == 409) {
                // Nothing of the write was applied. For a create the money is recorded; for an update the Plaid id is
                //  recorded on another transaction, so the target stays as it was. The conversion already skips updates
                //  whose new id the index holds, so an update 409 is a race or an anomaly a person must look at: after a
                //  pending to posted update, the pending transaction may now be a second record of the posted money.
                if (letter.operation == "update") {
                    logger.error(
                        "Firefly refused update of ${letter.key}: a Plaid id it sends is already on another transaction " +
                                "(HTTP 409: ${runCatching { cre.response.bodyAsText() }.getOrDefault("")}); left as it was, check it by hand"
                    )
                } else {
                    logger.info("Firefly already holds a Plaid id of the ${letter.operation} of ${letter.key}; left as it was")
                }
                store?.remove(letter.operation, letter.key)
                return false
            }
            if (instead != null && status in insteadOn) {
                logger.warn("Firefly refused the ${letter.operation} of ${letter.key} (HTTP $status); creating its new Plaid leg on its own")
                store?.remove(letter.operation, letter.key)
                instead()
                return false
            }
            // 401/403 mean the Firefly credentials are wrong for every transaction; 408/429 are transient
            if (store == null || status !in 400..499 || status in setOf(401, 403, 408, 429)) throw cre
            if (status == 404 && letter.operation != "create") {
                logger.warn(
                    "Firefly transaction ${letter.fireflyId} no longer exists; dropping the ${letter.operation} of ${letter.key}"
                )
                store.remove(letter.operation, letter.key)
                return false
            }
            val message = runCatching { cre.response.bodyAsText() }.getOrDefault("")
                .let { Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(it)?.groupValues?.get(1) } ?: "HTTP $status"
            val attempts = if (retry) letter.attempts + 1 else 0
            val abandoned = attempts >= DeadLetterStore.maxAttempts
            if (abandoned) {
                logger.error(
                    "Firefly rejected ${letter.operation} of ${letter.key} $attempts times (HTTP $status: $message); " +
                            "ABANDONED: it stays in the dead letter file and is no longer retried. Fix or remove it by hand."
                )
            } else {
                logger.error(
                    "Firefly permanently rejected ${letter.operation} of ${letter.key} (HTTP $status: $message); " +
                            "kept in the dead letter file and retried every poll"
                )
            }
            store.add(letter.copy(message = "HTTP $status: $message", attempts = attempts, abandoned = abandoned))
            return true
        }
    }
}
