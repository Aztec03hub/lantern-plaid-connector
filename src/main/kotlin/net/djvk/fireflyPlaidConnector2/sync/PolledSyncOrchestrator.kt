package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionExternalIdIndexer
import net.djvk.fireflyPlaidConnector2.transactions.InvestmentTransactionConverter
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.util.Utilities
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
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

    private val investmentConverter: InvestmentTransactionConverter? = null,

    /** How many days back each poll re-reads investment transactions (Plaid has no change feed for them). */
    @Value("\${fireflyPlaidConnector2.polled.investmentLookbackDays:14}")
    private val investmentLookbackDays: Long = 14,
) : Runner, DisposableBean {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val terminated = AtomicBoolean(false)
    private lateinit var mainJob: Job

    /**
     * Syncs accounts configured with `investment: true` (upstream issue #68): reads their recent investment
     * transactions from Plaid and inserts the ones Firefly doesn't have yet.
     *
     * Plaid has no cursor or change events for investment transactions, so each poll re-reads the last
     * [investmentLookbackDays] days. Transactions already in Firefly's pull window are skipped by external id; older
     * ones are rejected by Firefly's duplicate detection, which the insert tolerates. Later corrections or
     * cancellations of an already-imported investment transaction are not propagated.
     *
     * One Item failing (for example because it wasn't linked with the investments product) is logged and does not
     * stop the other Items or the regular bank sync.
     */
    suspend fun syncInvestments(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        today: java.time.LocalDate = java.time.LocalDate.now(),
    ) {
        val converter = investmentConverter ?: return
        val investmentItems = syncHelper.getInvestmentAccessTokenAccountIdSets().toList()
        if (investmentItems.isEmpty()) return

        val knownExternalIds = fireflyTransactionService.fetchExistingFireflyTransactions()
            .flatMap { it.attributes.transactions }
            .mapNotNull { it.externalId }
            .toSet()

        for ((accessToken, accountIds) in investmentItems) {
            try {
                val plaidTxs = plaidSyncService.fetchInvestmentTransactions(
                    accessToken, accountIds, today.minusDays(investmentLookbackDays), today
                )
                val creates = plaidTxs
                    .filter { FireflyTransactionExternalIdIndexer.getExternalId(it.investmentTransactionId) !in knownExternalIds }
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
            }
        }
    }

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
     */
    suspend fun processTransactions(
        accountMap: Map<PlaidAccountId, FireflyAccountId>,
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ) {
        // Fetch existing Firefly transactions
        val existingFireflyTxs = fireflyTransactionService.fetchExistingFireflyTransactions()

        // Process Plaid transactions
        val plaidTransactions = plaidSyncService.processPlaidTransactions(
            accountAccessTokenSequence,
            cursorMap
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

        // Update cursor map after successful processing
        cursorManager.writeCursorMap(cursorMap)
    }

    override fun run() {
        runBlocking {
            syncHelper.setApiCreds()

            mainJob = launch {
                // Initialize cursors
                initializeCursors()

                // Get account mappings for the polling loop
                val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
                val cursorMap = cursorManager.readCursorMap()

                /**
                 * Periodic polling loop
                 */
                do {
                    logger.debug("Polling loop start")

                    // Process transactions
                    processTransactions(accountMap, accountAccessTokenSequence, cursorMap)

                    // Accounts marked investment: true
                    syncInvestments(accountMap)

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
