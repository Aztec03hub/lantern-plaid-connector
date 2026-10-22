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
import net.djvk.fireflyPlaidConnector2.transactions.isPaired
import net.djvk.fireflyPlaidConnector2.util.Utilities.forEachBounded
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

    suspend fun fetchFireflyTransactionsBetween(start: LocalDate, end: LocalDate, maxPages: Int): List<TransactionRead> =
        readRange(start, end, maxPages, strict = false)

    /**
     * Like [fetchFireflyTransactionsBetween], but fails when Firefly's total changes between pages or the groups read do
     * not add up to it (a write by another process shifted the pages), instead of returning a list with a journal
     * missed or doubled. For a read that sums journals, such as an opening balance.
     */
    suspend fun fetchFireflyTransactionsStrictly(start: LocalDate, end: LocalDate, maxPages: Int): List<TransactionRead> =
        readRange(start, end, maxPages, strict = true)

    private suspend fun readRange(start: LocalDate, end: LocalDate, maxPages: Int, strict: Boolean): List<TransactionRead> {
        val existingFireflyTxs = mutableListOf<TransactionRead>()
        val today = end
        val transferWindowStart = start

        // Firefly pages are 1-based; starting at 0 fetched page 1 twice
        var fireflyTxPage = 1
        var firstTotal: Int? = null
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
            if (strict && pagination?.total != null) {
                firstTotal = firstTotal ?: pagination.total
                check(pagination.total == firstTotal) { FIREFLY_CHANGED }
            }
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

        if (strict && firstTotal != null) {
            check(existingFireflyTxs.map { it.id }.distinct().size == firstTotal && existingFireflyTxs.size == firstTotal) { FIREFLY_CHANGED }
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
     * This resolves the ones for [plaidTransactionIds] that aren't already in [alreadyFetched]: one link lookup per
     * [PlaidLinksApi.MAX_IDS] ids (`GET /plaid-links`), then one read per Firefly transaction found. A failed lookup propagates: swallowing it
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
        // Insert new transactions, a few at a time (see SyncHelper.writeConcurrency). Updates and deletes follow
        //  sequentially: an update may target what a create just made, and its fallback is itself a create.
        val notCreated = java.util.concurrent.atomic.AtomicInteger(0)
        creates.forEachBounded(syncHelper.writeConcurrency) { create ->
            if (!createGuarded(create)) notCreated.incrementAndGet()
        }

        // Process updates. This includes converting an existing deposit/withdrawal into a transfer, which is an in-place
        //  update with type=transfer (Firefly 6.7.7 accepts that), so every Firefly write here is safe to repeat.
        for (update in updates) {
            // An update that carries a Plaid fact (a pairing, a promotion, a plain Plaid update) creates that fact on its
            //  own instead when the Firefly transaction is gone (404), so its money is not hidden behind a failed update.
            //  A pairing (the update makes a transfer) does that on ANY permanent rejection: the existing transaction
            //  stays as it was and the new leg is recorded nowhere else, and as a group-keyed update letter the new leg
            //  would be invisible to Plaid's later events. Every other update (a transfer promotion, a pending to
            //  posted update, a plain update) does NOT: after a 422 the old transaction still holds that money, and a
            //  create beside it would count it twice. Those keep the fallback in their dead letter instead, so a retry
            //  that finds the transaction gone (404) still creates it.
            val fallback = update.fallbackCreate
            guarded(
                DeadLetter(
                    "update", update.transactionId, update.id, update.tx, update.changesType,
                    fallback = fallback?.tx, plaidAccountId = update.plaidAccountId,
                ),
                instead = fallback?.let { { createGuarded(it) } },
                insteadOn = if (update.tx.isPaired() && !update.fallbackOnlyWhenGone) permanent4xx else setOf(404),
            ) {
                syncHelper.updateBatchInFirefly(listOf(update))
            }
        }

        // Process deletes
        for (id in deletes) {
            guarded(DeadLetter("delete", id, id)) { syncHelper.deleteBatchInFirefly(listOf(id)) }
        }
        return notCreated.get()
    }

    /** @return true if [create] made a new Firefly transaction (false: dead-lettered, or already imported) */
    private suspend fun createGuarded(create: FireflyTransactionDto): Boolean {
        if (create.checkLegacyReference) {
            val ids = create.tx.plaidLinks.orEmpty().map { it.plaidTransactionId }
            // A letter of any kind already holding one of these ids (a create, or a promotion update whose split carries
            //  the posted id) gets Plaid's change from the letter itself; a create here would sit beside the pending twin
            val waiting = deadLetters?.read().orEmpty().any { l ->
                listOfNotNull(l.split, l.fallback).any { s -> s.plaidLinks.orEmpty().any { it.plaidTransactionId in ids } }
            }
            if (waiting) {
                logger.info("Plaid modified $ids, which waits in the dead letter file; the letter takes the change")
                return false
            }
            val legacy = try {
                ids.firstOrNull { syncHelper.heldByLegacyReference(it) }
            } catch (cre: ClientRequestException) {
                val status = cre.response.status.value
                val store = deadLetters
                if (store == null || status !in permanent4xx) throw cre
                // A search Firefly rejects for good would stall every poll of this Item. Keep the create as an ABANDONED
                //  letter (reported in the poll result, never auto-sent: it could duplicate an older import) and go on.
                logger.error("Legacy search for Plaid id(s) $ids failed (HTTP $status); create kept as an abandoned dead letter: check it by hand")
                store.add(DeadLetter("create", letterKey(create.tx), null, create.tx, false, "legacy search failed: HTTP $status", abandoned = true, legacyUnchecked = true))
                return false
            }
            if (legacy != null) {
                logger.warn(
                    "Firefly already holds a transaction made for Plaid id $legacy before the link table; not creating a second one " +
                            "(Plaid's version dated ${create.tx.date}, amount ${create.tx.amount}, is not applied to it)"
                )
                return false
            }
        }
        var created = 0
        val kept = guarded(DeadLetter("create", letterKey(create.tx), null, create.tx, false)) {
            created = syncHelper.optimisticInsertBatchIntoFirefly(listOf(create))
        }
        return !kept && created > 0
    }

    companion object {
        /** 401/403 mean the Firefly credentials are wrong for every transaction; 408/429 are transient. */
        private const val FIREFLY_CHANGED = "Firefly changed while it was being read (its transaction count moved between pages, " +
                "or the groups read do not add up to it); nothing was decided, run again"

        private val notPermanent4xx = setOf(401, 403, 408, 429)

        /** The 4xx answers that reject one write for good (409 is handled before these, as "already recorded"). */
        private val permanent4xx = (400..499).toSet() - notPermanent4xx - 409

        /** What identifies a create in the dead letter file: its first Plaid id, so a later Plaid event for it finds it. */
        fun letterKey(split: net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit): String =
            split.plaidLinks?.firstOrNull()?.plaidTransactionId ?: ""
    }

    /**
     * Retries the writes Firefly rejected in earlier polls. The ones that go through are removed from the dead letter
     * file; the ones that are still rejected stay (with Firefly's latest message) until [DeadLetterStore.maxAttempts]
     * retries have failed, after which they are abandoned: no longer retried, but still in the file and still reported.
     * A create whose Plaid id is already in Firefly answers 409 and is dropped rather than inserted again.
     *
     * The Firefly account ids inside a letter are re-resolved from [accountMap] first, so fixing the account mapping
     * fixes the letters waiting on it. An update letter that carries a fallback and finds its Firefly transaction gone
     * (404) creates the fallback and only then leaves the file; one without a fallback is dropped.
     */
    suspend fun retryDeadLetters(accountMap: Map<String, Int> = mapOf()) {
        val store = deadLetters ?: return
        for (stored in store.read()) {
            var letter = stored
            if (letter.abandoned && letter.legacyUnchecked) {
                // Kept because the legacy search was rejected: ask again. Answered "none" re-arms it (it is then sent once,
                //  like any create); answered "held" drops it; still rejected leaves it abandoned for a person.
                val ids = letter.split?.plaidLinks.orEmpty().map { it.plaidTransactionId }
                if (ids.isEmpty()) continue
                val held = try {
                    ids.firstOrNull { syncHelper.heldByLegacyReference(it) }
                } catch (cre: ClientRequestException) {
                    continue
                }
                if (held != null) {
                    logger.warn("Dead-lettered create ${letter.key}: Firefly already holds Plaid id $held from before the link table; dropped")
                    store.remove(letter.operation, letter.key)
                    continue
                }
                letter = letter.copy(abandoned = false, legacyUnchecked = false, attempts = 0)
                store.add(letter)
            }
            if (letter.abandoned) continue
            if (letter.operation == "create" && letter.split?.plaidLinks.isNullOrEmpty()) {
                // Written by a build from before the link table: sent now it would be unprotected against duplicates
                logger.error(
                    "The dead-lettered create ${letter.key} carries no Plaid link (older build); ABANDONED, enter it by hand if it is missing"
                )
                store.add(letter.copy(abandoned = true))
                continue
            }
            val current = letter.copy(
                split = letter.split?.withCurrentAccounts(letter.plaidAccountId, accountMap),
                fallback = letter.fallback?.withCurrentAccounts(letter.plaidAccountId, accountMap),
            )
            if (current != letter) {
                logger.info("Re-resolved the Firefly accounts of dead letter ${letter.key} from the current mapping")
            }
            guarded(
                current, retry = true,
                instead = current.fallback?.let { fb -> { createGuarded(FireflyTransactionDto(null, fb)) } },
                insteadOn = setOf(404),
            ) {
                val split = current.split
                when (current.operation) {
                    "create" -> syncHelper.optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, split!!)))
                    "update" -> syncHelper.updateBatchInFirefly(listOf(FireflyTransactionDto(current.fireflyId, split!!, current.changesType)))
                    "delete" -> syncHelper.deleteBatchInFirefly(listOf(current.fireflyId!!))
                }
            }
        }
    }

    /**
     * Runs [write] for [letter]'s transaction; see [processFireflyTransactionUpdates] for what is and isn't dead-lettered.
     *
     * A 404 on an update or delete means the Firefly transaction is gone (the user deleted it, or a later removal
     * did): that write can never succeed. With an [instead] (the fallback create of an update) that is run first and
     * the letter leaves the file only after it succeeded, so a failing fallback keeps the letter. Without one the
     * letter is dropped with a WARN instead of being retried forever.
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
                instead()
                store?.remove(letter.operation, letter.key)
                return false
            }
            if (store == null || status !in permanent4xx) throw cre
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
