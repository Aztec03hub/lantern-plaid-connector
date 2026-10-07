package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.pairing.PairPass
import net.djvk.fireflyPlaidConnector2.pairing.PairSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.InvestmentTransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.util.Utilities
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

typealias IntervalMinutes = Int
typealias PlaidSyncCursor = String

/**
 * Orchestrates the polled sync process.
 *
 * Handles the "polled" sync mode, which periodically polls for new transactions and processes them.
 * This class coordinates the different components involved in the sync process.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "polled", matchIfMissing = true)
@Component
class PolledSyncOrchestrator(
    @Value("\${fireflyPlaidConnector2.polled.syncFrequencyMinutes}")
    private val syncFrequencyMinutes: IntervalMinutes,

    private val syncHelper: SyncHelper,
    private val cursorManager: CursorManager,
    private val plaidSyncService: PlaidSyncService,
    private val fireflyTransactionService: FireflyTransactionService,
    private val converter: TransactionConverter,

    /** Null unless `fireflyPlaidConnector2.polled.resultCallbackUrl` is configured. */
    private val webhookService: WebhookService? = null,

    /**
     * If greater than zero, ask Plaid to refresh each Item (/transactions/refresh) at most this often, before the
     * poll that follows. 0 (the default) disables it. See [refreshItemsDue].
     */
    @Value("\${fireflyPlaidConnector2.polled.refreshIntervalMinutes:0}")
    private val refreshIntervalMinutes: Long = 0,

    /**
     * By default the first run for an Item skips its existing history and only picks up what changes afterwards (see
     * [initializeCursors]). When true, the first sync of a new Item imports everything Plaid has for it (up to the
     * history it was linked with, typically 24 months), which is what you want when adding a bank to a new Firefly.
     */
    @Value("\${fireflyPlaidConnector2.polled.importHistoryOnFirstSync:false}")
    private val importHistoryOnFirstSync: Boolean = false,

    private val investmentConverter: InvestmentTransactionConverter? = null,

    /** How many days back each poll re-reads investment transactions (Plaid has no change feed for them). */
    @Value("\${fireflyPlaidConnector2.polled.investmentLookbackDays:14}")
    private val investmentLookbackDays: Long = 14,

    /** Each Item's last successful sync, for Lantern to read; null (tests) means it isn't kept. */
    private val itemStatusStore: ItemStatusStore? = null,

    /** Counts the writes Firefly permanently rejected, for the result callback; null (tests) means none are kept. */
    private val deadLetterStore: DeadLetterStore? = null,

    /**
     * Polled mode needs `pendingTag`: without it a pending Firefly transaction stays a transfer candidate, and a
     * pending transaction the bank then cancels can stay a transfer. An unset value used to degrade silently, so
     * startup fails instead. (The default here is only for hand-built instances in tests; Spring passes the property.)
     */
    @Value("\${fireflyPlaidConnector2.pendingTag:}")
    pendingTag: String = "pending",

    /** Pairs what is left unpaired in Firefly after each poll's writes (when pair.afterRun is on); null (tests) skips it. */
    private val pairPass: PairPass? = null,
    private val pairSettings: PairSettings? = null,
) : Runner, DisposableBean {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        check(pendingTag.isNotBlank()) {
            "fireflyPlaidConnector2.pendingTag must be set in polled mode (for example \"pending\"): without it a " +
                    "cancelled pending transaction can be left behind as a transfer. Set it and restart."
        }
        if (webhookService == null) {
            logger.warn(
                "fireflyPlaidConnector2.polled.resultCallbackUrl is not set: a poll that needs attention " +
                        "(status \"partial\") will only show in the log"
            )
        }
    }

    private val terminated = AtomicBoolean(false)
    private lateinit var mainJob: Job

    /** When each Item (by access token) was last asked to refresh; in memory only, so every restart refreshes once. */
    private val lastRefreshAt = mutableMapOf<PlaidAccessToken, Instant>()

    /**
     * Requests a Plaid refresh for every Item that hasn't been asked within [refreshIntervalMinutes]. The attempt
     * time is recorded even if Plaid rejects the request, so a failing or unsupported Item is retried only on the
     * interval rather than every poll (Plaid bills successful refreshes).
     */
    suspend fun refreshItemsDue(
        accessTokens: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        now: Instant = Instant.now(),
    ) {
        if (refreshIntervalMinutes <= 0) return
        for ((accessToken, _) in accessTokens) {
            val last = lastRefreshAt[accessToken]
            if (last != null && java.time.Duration.between(last, now).toMinutes() < refreshIntervalMinutes) continue
            lastRefreshAt[accessToken] = now
            plaidSyncService.refreshTransactions(accessToken)
        }
    }

    /**
     * Syncs accounts configured with `investment: true` (upstream issue #68): reads their recent investment
     * transactions from Plaid and inserts the ones Firefly doesn't have yet.
     *
     * Plaid has no cursor or change events for investment transactions, so each poll re-reads the last
     * [investmentLookbackDays] days. The ones Firefly already holds (a link lookup) are skipped; a 409 on the insert is
     * tolerated. Later corrections or cancellations of an already-imported investment transaction are not propagated.
     *
     * One Item failing (for example because it wasn't linked with the investments product) does not stop the other
     * Items; once all have been tried, an exception reports the failed ones.
     */
    suspend fun syncInvestments(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        today: java.time.LocalDate = java.time.LocalDate.now(),
    ) {
        val converter = investmentConverter ?: return
        val investmentItems = syncHelper.getInvestmentAccessTokenAccountIdSets().toList()
        if (investmentItems.isEmpty()) return

        val failedItems = mutableListOf<String>()

        for ((accessToken, accountIds) in investmentItems) {
            try {
                val plaidTxs = plaidSyncService.fetchInvestmentTransactions(
                    accessToken, accountIds, today.minusDays(investmentLookbackDays), today
                )
                if (plaidTxs.isEmpty()) continue
                val held = fireflyTransactionService.heldPlaidIds(plaidTxs.map { it.investmentTransactionId })
                val creates = plaidTxs
                    .filter { it.investmentTransactionId !in held }
                    .mapNotNull { tx ->
                        val fireflyAccountId = accountMap[tx.accountId]
                        if (fireflyAccountId == null) {
                            logger.warn("Investment transaction for an unconfigured Plaid account; skipping")
                            null
                        } else {
                            converter.convert(tx, fireflyAccountId)
                        }
                    }
                logger.debug(
                    "Investments for ${Utilities.redactAccessToken(accessToken)}: ${plaidTxs.size} read, " +
                            "${creates.size} new"
                )
                syncHelper.optimisticInsertBatchIntoFirefly(creates)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Investment sync failed for ${Utilities.redactAccessToken(accessToken)}: ${e::class.simpleName}"
                )
                failedItems.add(Utilities.redactAccessToken(accessToken))
            }
        }
        // Every Item gets its turn, but a failure is not silent: it fails the iteration, so it is reported through the
        //  result callback and logged by the poll loop (an outage longer than the lookback loses transactions).
        if (failedItems.isNotEmpty()) {
            throw InvestmentSyncException(failedItems)
        }
    }

    /**
     * Initializes cursors for access tokens that don't have one yet.
     */
    suspend fun initializeCursors() {
        if (importHistoryOnFirstSync) {
            // Leave new Items without a cursor: their first poll then starts from the beginning of Plaid's history
            logger.info("importHistoryOnFirstSync is enabled; not fast-forwarding cursors for new Items")
            return
        }

        // Read cursor map from storage
        val cursorMap = cursorManager.readCursorMap()

        // Get account mappings
        val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()

        // Initialize cursors for access tokens that don't have one yet
        plaidSyncService.initializeCursors(accountAccessTokenSequence, cursorMap)
        cursorManager.writeCursorMap(cursorMap)
    }

    /** Writes the cursors to disk first and only then advances the in-memory map, so a failed write leaves both stale together. */
    private suspend fun commitCursors(cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>, workingCursors: Map<PlaidAccessToken, PlaidSyncCursor>) {
        cursorManager.writeCursorMap(cursorMap + workingCursors)
        cursorMap.putAll(workingCursors)
    }

    /**
     * Stamps each Item's outcome in the item status file and builds the callback's failure report.
     *
     * @param accessTokens every Item polled
     * @param failed the Items that failed (a subset of [accessTokens]); a failed Item's cursor did not move
     */
    private suspend fun recordItemOutcomes(
        accessTokens: List<PlaidAccessToken>,
        failed: List<ItemFailure>,
    ): List<FailedItemReport> {
        val store = itemStatusStore
            ?: return failed.map { FailedItemReport(it.institution, it.accessTokenRedacted, it.errorCode) }
        val failedRedacted = failed.map { it.accessTokenRedacted }.toSet()
        val succeeded = accessTokens
            .filter { Utilities.redactAccessToken(it) !in failedRedacted }
            .map { store.ref(it, plaidSyncService.describeInstitution(it)) }
        val failedRefs = failed.mapNotNull { f ->
            accessTokens.firstOrNull { Utilities.redactAccessToken(it) == f.accessTokenRedacted }
                ?.let { store.ref(it, f.institution) to f.errorCode }
        }
        val lastSuccess = store.record(Instant.now(), succeeded, failedRefs)
        return failed.map { f ->
            val ref = failedRefs.firstOrNull { it.first.redactedToken == f.accessTokenRedacted }?.first
            FailedItemReport(f.institution, f.accessTokenRedacted, f.errorCode, ref?.let { lastSuccess[it.id] })
        }
    }

    /**
     * Processes transactions by fetching from Plaid, converting, and updating Firefly.
     *
     * @return counts of what this iteration did, for the optional result callback
     */
    suspend fun processTransactions(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ): PollResult {
        // Firefly still keeps Plaid links (it may have been swapped for a stock one since startup); checked before the
        //  first write, because a stock Firefly would store every create of this poll, and of every poll after, unlinked
        syncHelper.validatePlaidLinksEndpoint()
        // A liability account added to Firefly since startup is typed correctly from this poll on
        converter.accountKinds = syncHelper.fetchAccountKinds()

        // Writes Firefly rejected in earlier polls are retried first, so a newer write for the same transaction
        //  (below) always lands after the stale one
        fireflyTransactionService.retryDeadLetters()

        // Advance a working copy of the cursors. The real map is only updated after Firefly has accepted the
        //  changes, so a failure part way through (network down, Firefly error) never skips Plaid data: the next
        //  iteration re-reads from the last committed cursors.
        val workingCursors = cursorMap.toMutableMap()
        val plaidTransactions = plaidSyncService.processPlaidTransactions(
            accountAccessTokenSequence,
            workingCursors
        )
        val accessTokens = accountAccessTokenSequence.map { it.first }.toList()

        // Most polls find nothing new. Don't page through Firefly every interval just to do nothing with it.
        if (plaidTransactions.created.isEmpty() && plaidTransactions.updated.isEmpty() &&
            plaidTransactions.deleted.isEmpty()
        ) {
            logger.debug("No Plaid changes; skipping Firefly")
            commitCursors(cursorMap, workingCursors)
            return PollResult(
                failedItems = recordItemOutcomes(accessTokens, plaidTransactions.failedItems),
            ).withDeadLetters()
        }

        // Fetch existing Firefly transactions in the pull window: the pool a new transfer leg is paired from
        val windowFireflyTxs = fireflyTransactionService.fetchExistingFireflyTransactions()
        //  Settled creates dated before the window that could be transfer legs are paired from a dated range read too
        //  (a link lookup only finds a create's OWN record, never the other leg): a second bank imported with its whole
        //  history would otherwise record every old transfer as an unpaired withdrawal plus deposit. Dedupe is not its
        //  job any more, the link table does that.
        val windowStart = fireflyTransactionService.windowStart()
        val oldPairableDates = plaidTransactions.created
            .filter { converter.mightPairAsTransfer(it) }
            .map { minOf(it.date, it.authorizedDate ?: it.date) }
            .filter { it < windowStart }
        val oldFireflyTxs = if (oldPairableDates.isEmpty()) listOf() else
            fireflyTransactionService.fetchFireflyTransactionsBetween(
                oldPairableDates.min().minusDays(1), windowStart.minusDays(1), maxHistoryPages,
            )
        // Plaid's updates, removals, pending-to-posted links and possible transfer legs may refer to Firefly transactions
        //  the reads above missed; a link lookup finds them. A posted create that replaces a pending one is looked up
        //  under its own id too: if Firefly already holds it, the pending one is not promoted onto it. A create that is
        //  none of these needs no lookup: if Firefly already holds it, the write answers 409.
        val referencedPlaidIds = plaidTransactions.updated.map { it.transactionId } +
                plaidTransactions.deleted +
                plaidTransactions.created.mapNotNull { it.pendingTransactionId } +
                plaidTransactions.created.filter { it.pendingTransactionId != null }.map { it.transactionId } +
                plaidTransactions.created.filter { converter.mightPairAsTransfer(it) }.map { it.transactionId }
        val knownFireflyTxs = windowFireflyTxs + oldFireflyTxs
        val existingFireflyTxs = knownFireflyTxs +
                fireflyTransactionService.fetchMissingByPlaidId(referencedPlaidIds, knownFireflyTxs)

        // Convert Plaid transactions to Firefly format
        logger.trace("Converting Plaid transactions to Firefly transactions")
        val convertResult = converter.convertPollSync(
            accountMap,
            plaidTransactions.created,
            plaidTransactions.updated,
            plaidTransactions.deleted,
            existingFireflyTxs,
        )
        logger.debug(
            "Conversion result: ${convertResult.creates.size} creates; " +
                    "${convertResult.updates.size} updates; " +
                    "${convertResult.deletes.size} deletes;"
        )

        // Process transaction updates in Firefly
        val deadLetteredCreates = fireflyTransactionService.processFireflyTransactionUpdates(
            convertResult.creates,
            convertResult.updates,
            convertResult.deletes
        )

        // What this poll could not pair in memory (a leg imported by another run) is paired from Firefly's state. A
        //  failure is logged and retried by the next poll: it must not hold back the cursors of imported data.
        try {
            if (pairPass != null && pairSettings?.afterRun == true) {
                val today = java.time.LocalDate.now()
                pairPass.run(today.minusDays(pairSettings.lookbackDays), today, pairSettings.dryRun)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Transfer pairing failed; it is retried by the next poll", e)
        }

        // A create waiting in the dead letter file is invisible to the conversion above (it only looks in Firefly), so
        //  Plaid's later word on that transaction is applied to the letter itself. After the writes, so a failed
        //  iteration replays cleanly; before the cursors commit, because Plaid will not send these events again.
        reviseDeadLetteredCreates(accountMap, plaidTransactions)

        // Commit the cursors only after successful processing
        commitCursors(cursorMap, workingCursors)

        return PollResult(
            existingFireflyTransactionsRead = existingFireflyTxs.size,
            plaidCreated = plaidTransactions.created.size,
            plaidUpdated = plaidTransactions.updated.size,
            plaidDeleted = plaidTransactions.deleted.size,
            fireflyCreated = convertResult.creates.size - deadLetteredCreates,
            fireflyUpdated = convertResult.updates.size,
            fireflyDeleted = convertResult.deletes.size,
            failedItems = recordItemOutcomes(accessTokens, plaidTransactions.failedItems),
            transfersNeedingReview = convertResult.transfersNeedingReview.size,
        ).withDeadLetters()
    }

    /** Fills in what the dead letter file holds right now (retried, abandoned, unreadable). */
    private suspend fun PollResult.withDeadLetters(): PollResult {
        val store = deadLetterStore ?: return this
        val letters = store.read()
        return copy(
            deadLetters = letters.size,
            deadLettersAbandoned = letters.count { it.abandoned },
            deadLetterFilesUnreadable = store.takeUnreadableCount(),
        )
    }

    /**
     * Applies this poll's Plaid events to the creates that Firefly rejected and that wait in the dead letter file:
     * - a posted transaction that replaces a pending one drops the pending one's letter (the posted create takes over);
     * - a modified transaction replaces the letter's content with its new version (the retry carries the new amount);
     * - a removed transaction drops its letter (nothing was ever created); one leg of a transfer that was removed
     *   leaves the other leg's money as a plain create instead.
     * Any letter that changes is retried from scratch (attempts reset).
     */
    private suspend fun reviseDeadLetteredCreates(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        plaid: PlaidTransactionResult,
    ) {
        val store = deadLetterStore ?: return
        if (plaid.created.isEmpty() && plaid.updated.isEmpty() && plaid.deleted.isEmpty()) return
        val creates = store.read().filter { it.operation == "create" && it.split != null }
        if (creates.isEmpty()) return
        fun letterFor(plaidId: String) = creates.firstOrNull { letter ->
            letter.split?.plaidLinks.orEmpty().any { it.plaidTransactionId == plaidId }
        }

        for (posted in plaid.created) {
            val pendingId = posted.pendingTransactionId ?: continue
            val letter = letterFor(pendingId) ?: continue
            logger.info("Dropping the dead-lettered create of pending transaction ${letter.key}: its posted version replaces it")
            store.remove("create", letter.key)
        }
        for (modified in plaid.updated) {
            val letter = letterFor(modified.transactionId) ?: continue
            logger.info("Applying Plaid's update of ${modified.transactionId} to its dead-lettered create ${letter.key}")
            store.add(letter.copy(split = converter.reviseUnsentCreate(letter.split!!, modified, accountMap), attempts = 0, abandoned = false))
        }
        for (removedId in plaid.deleted) {
            val letter = store.read().firstOrNull { l ->
                l.operation == "create" && l.split?.plaidLinks.orEmpty().any { it.plaidTransactionId == removedId }
            } ?: continue
            val survivor = converter.survivingLeg(letter.split!!, removedId)
            if (survivor == null) {
                logger.info("Dropping the dead-lettered create ${letter.key}: Plaid removed ${removedId}")
                store.remove("create", letter.key)
            } else {
                logger.info("Plaid removed one leg of the dead-lettered transfer ${letter.key}; keeping the other leg's money")
                store.remove("create", letter.key)
                store.add(letter.copy(key = FireflyTransactionService.letterKey(survivor), split = survivor, attempts = 0, abandoned = false))
            }
        }
    }

    /**
     * Runs one poll iteration and reports its outcome to the optional result callback. A failure is reported to the
     * callback and then rethrown, so the callback is notified before the exception leaves the loop.
     *
     * Banks and investments are independent: each is tried even if the other fails, so a bank outage doesn't starve
     * investment syncing (which only looks back [investmentLookbackDays]) and a failing investment Item doesn't hide
     * a bank sync that worked. One callback carries both outcomes, then the first failure is rethrown.
     */
    suspend fun runIteration(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ) {
        val iterationStart = Instant.now()
        var bankResult: PollResult? = null
        var bankFailure: Exception? = null
        try {
            bankResult = processTransactions(accountMap, accountAccessTokenSequence, cursorMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bankFailure = e
        }
        // Accounts marked investment: true. Its failure is reported to the callback and, like any other, caught by the
        //  poll loop (see [pollOnce]) instead of ending the process.
        var investmentFailure: Exception? = null
        try {
            syncInvestments(accountMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            investmentFailure = e
        }

        val failure = bankFailure ?: investmentFailure
        if (bankFailure != null && investmentFailure != null) bankFailure.addSuppressed(investmentFailure)
        val reported = bankResult?.let {
            if (investmentFailure == null) it
            else it.copy(investmentFailures = (investmentFailure as? InvestmentSyncException)?.failedItems ?: 1)
        }
        webhookService?.post(iterationStart, Instant.now(), reported, failure)
        if (failure != null) throw failure
    }

    /**
     * Runs [block], and if it fails because the network (or a remote 5xx) is unavailable, logs a warning and tries
     * again after [retryDelay], for as long as it keeps failing that way. Any other failure propagates.
     * Used for startup, where a host that boots before its network is up should wait, not crash.
     */
    suspend fun <T> retryWhileNetworkDown(
        what: String,
        retryDelay: Duration = syncFrequencyMinutes.minutes,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Utilities.isTransientNetworkError(e)) throw e
                logger.warn("Network unavailable during $what (${e::class.simpleName}); retrying in $retryDelay")
                delay(retryDelay)
            }
        }
    }

    private var consecutiveFailures = 0

    /**
     * One iteration of the polling loop. A failed iteration (network down, Plaid or Firefly error) must not kill the
     * loop: cursors are only committed on success, so the next iteration retries the same data. Never throws, except
     * for cancellation.
     */
    suspend fun pollOnce(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>,
    ) {
        logger.debug("Polling loop start")
        try {
            // Optionally nudge Plaid to check for new data; it arrives on a later poll
            refreshItemsDue(accountAccessTokenSequence)

            runIteration(accountMap, accountAccessTokenSequence, cursorMap)
            if (consecutiveFailures > 0) {
                logger.info("Poll recovered after $consecutiveFailures failed iteration(s)")
            }
            consecutiveFailures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            logger.error(
                "Poll iteration failed ($consecutiveFailures consecutive); " +
                        "retrying in $syncFrequencyMinutes minutes", e
            )
        }
    }

    override fun run() {
        runBlocking {
            mainJob = launch {
                val (accountMap, accountAccessTokenSequence, cursorMap) = retryWhileNetworkDown("startup") {
                    syncHelper.setApiCreds()
                    converter.accountKinds = syncHelper.fetchAccountKinds()

                    // Initialize cursors
                    initializeCursors()

                    // Get account mappings for the polling loop
                    val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
                    Triple(accountMap, accountAccessTokenSequence, cursorManager.readCursorMap())
                }

                /**
                 * Periodic polling loop
                 */
                do {
                    pollOnce(accountMap, accountAccessTokenSequence, cursorMap)

                    // Trigger GC to try to reduce heap size
                    logger.trace("Calling System.gc()")
                    System.gc()

                    // Sleep until next poll
                    logger.info("Sleeping $syncFrequencyMinutes")
                    delay(syncFrequencyMinutes.minutes)
                } while (!terminated.get())
            }
        }
    }

    override fun destroy() {
        logger.info("Shutting down ${this::class}")
        terminated.set(true)
        mainJob.cancel()
    }
}

/** Investment sync failed for the Items in [failedItemNames] (redacted tokens), after every Item had its turn. */
class InvestmentSyncException(val failedItemNames: List<String>) :
    IllegalStateException("Investment sync failed for ${failedItemNames.size} Item(s): $failedItemNames") {
    val failedItems: Int get() = failedItemNames.size
}

/** No page cap for the history range read: it is bounded by the transaction ceiling in [FireflyTransactionService] instead. */
private const val maxHistoryPages = Int.MAX_VALUE
