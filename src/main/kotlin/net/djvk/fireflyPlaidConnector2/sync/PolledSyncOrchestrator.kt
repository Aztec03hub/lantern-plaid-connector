package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
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
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "polled")
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
) : Runner, DisposableBean {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val terminated = AtomicBoolean(false)
    private lateinit var mainJob: Job

    /**
     * Initializes cursors for access tokens that don't have one yet.
     */
    suspend fun initializeCursors() {
        // Read cursor map from storage
        val cursorMap = cursorManager.readCursorMap()

        // Get account mappings
        val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()

        // Initialize cursors for access tokens that don't have one yet
        plaidSyncService.initializeCursors(accountAccessTokenSequence, cursorMap)
        cursorManager.writeCursorMap(cursorMap)
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
        // Fetch existing Firefly transactions
        val existingFireflyTxs = fireflyTransactionService.fetchExistingFireflyTransactions()

        // Advance a working copy of the cursors. The real map is only updated after Firefly has accepted the
        //  changes, so a failure part way through (network down, Firefly error) never skips Plaid data: the next
        //  iteration re-reads from the last committed cursors.
        val workingCursors = cursorMap.toMutableMap()
        val plaidTransactions = plaidSyncService.processPlaidTransactions(
            accountAccessTokenSequence,
            workingCursors
        )

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
        fireflyTransactionService.processFireflyTransactionUpdates(
            convertResult.creates,
            convertResult.updates,
            convertResult.deletes
        )

        // Commit the cursors only after successful processing
        cursorMap.putAll(workingCursors)
        cursorManager.writeCursorMap(cursorMap)

        return PollResult(
            existingFireflyTransactionsRead = existingFireflyTxs.size,
            plaidCreated = plaidTransactions.created.size,
            plaidUpdated = plaidTransactions.updated.size,
            plaidDeleted = plaidTransactions.deleted.size,
            fireflyCreated = convertResult.creates.size,
            fireflyUpdated = convertResult.updates.size,
            fireflyDeleted = convertResult.deletes.size,
        )
    }

    /**
     * Runs one poll iteration and reports its outcome to the optional result callback. A failure is reported to the
     * callback and then rethrown, so the callback is notified before the exception leaves the loop.
     */
    suspend fun runIteration(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ) {
        val iterationStart = Instant.now()
        val pollResult = try {
            processTransactions(accountMap, accountAccessTokenSequence, cursorMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            webhookService?.post(iterationStart, Instant.now(), null, e)
            throw e
        }
        webhookService?.post(iterationStart, Instant.now(), pollResult)
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

    override fun run() {
        runBlocking {
            mainJob = launch {
                val (accountMap, accountAccessTokenSequence, cursorMap) = retryWhileNetworkDown("startup") {
                    syncHelper.setApiCreds()

                    // Initialize cursors
                    initializeCursors()

                    // Get account mappings for the polling loop
                    val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
                    Triple(accountMap, accountAccessTokenSequence, cursorManager.readCursorMap())
                }

                var consecutiveFailures = 0

                /**
                 * Periodic polling loop
                 */
                do {
                    logger.debug("Polling loop start")

                    // Process transactions (and report the outcome to the optional result callback). A failed
                    //  iteration (network down, Plaid or Firefly error) must not kill the loop: cursors are only
                    //  committed on success, so the next iteration retries the same data.
                    try {
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
