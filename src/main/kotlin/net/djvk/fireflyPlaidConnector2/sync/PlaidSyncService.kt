package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import net.djvk.fireflyPlaidConnector2.util.Utilities.redactAccessToken
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import net.djvk.fireflyPlaidConnector2.api.plaid.models.Transaction as PlaidTransaction

/**
 * Service for handling Plaid transaction synchronization.
 */
@Component
class PlaidSyncService(
    private val plaidApiWrapper: PlaidApiWrapper,

    @Value("\${fireflyPlaidConnector2.plaid.batchSize}")
    private val plaidBatchSize: Int,

    @Value("\${fireflyPlaidConnector2.polled.allowItemToFail:false}")
    private val allowItemToFail: Boolean,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Creates a transaction sync request for Plaid API.
     */
    fun getTransactionSyncRequest(
        accessToken: PlaidAccessToken,
        cursor: PlaidSyncCursor?,
        batchSize: Int = plaidBatchSize
    ): TransactionsSyncRequest {
        return TransactionsSyncRequest(
            accessToken,
            null,
            null,
            cursor,
            batchSize,
            TransactionsSyncRequestOptions(
                includeOriginalDescription = true,
                includePersonalFinanceCategory = true,
            )
        )
    }

    /**
     * Executes a transaction sync request to Plaid API.
     * Returns null if the request fails and allowItemToFail is true.
     */
    suspend fun executeTransactionSyncRequest(
        accessToken: PlaidAccessToken,
        cursor: PlaidSyncCursor?,
        batchSize: Int = plaidBatchSize
    ): TransactionsSyncResponse? {
        val request = getTransactionSyncRequest(accessToken, cursor, batchSize)
        try {
            return plaidApiWrapper.executeRequest(
                { plaidApi -> plaidApi.transactionsSync(request) },
                "transaction sync request"
            ).body()
        } catch (ce: CancellationException) {
            throw ce
        } catch (cre: ClientRequestException) {
            // Not a failure of the Item: Plaid wants the pagination restarted (see processPlaidTransactions)
            if (runCatching { cre.response.bodyAsText() }.getOrDefault("").contains(MUTATION_DURING_PAGINATION)) {
                throw SyncMutationDuringPaginationException(cre)
            }
            return handleSyncFailure(accessToken, cre)
        } catch (e: Exception) {
            return handleSyncFailure(accessToken, e)
        }
    }

    /**
     * Network failures surface as RuntimeException (after the wrapper's retries) as well as ClientRequestException;
     * allowItemToFail applies to all of them.
     */
    private fun handleSyncFailure(accessToken: PlaidAccessToken, e: Exception): TransactionsSyncResponse? {
        logger.error("Error requesting Plaid transactions for ${redactAccessToken(accessToken)}: ${e::class.simpleName}")
        if (allowItemToFail) {
            logger.warn("Querying transactions for access token ${redactAccessToken(accessToken)} failed, allowing failure and continuing on to the next access token")
            return null
        }
        throw e
    }

    /**
     * Processes Plaid transactions for a set of access tokens and account IDs.
     * Returns lists of created, updated, and deleted transactions.
     */
    suspend fun processPlaidTransactions(
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ): PlaidTransactionResult {
        val plaidCreatedTxs = mutableListOf<PlaidTransaction>()
        val plaidUpdatedTxs = mutableListOf<PlaidTransaction>()
        val plaidDeletedTxs = mutableListOf<PlaidTransactionId>()

        accessTokenLoop@ for ((accessToken, accountIds) in accountAccessTokenSequence) {
            logger.debug(
                "Querying Plaid transaction sync endpoint for access token ${redactAccessToken(accessToken)} " +
                        " and account ids ${accountIds.joinToString("; ")}"
            )
            val accountIdSet = accountIds.toSet()

            /**
             * If the Item's data changes while we're paging through it, Plaid rejects the next page with
             *  TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION and requires the whole pagination to restart from the
             *  cursor it started at (https://plaid.com/docs/api/products/transactions/#transactionssync). Anything
             *  fetched in the failed attempt is discarded so it isn't counted twice.
             */
            val startCursor = cursorMap[accessToken]
            var restarts = 0
            while (true) {
                val created = mutableListOf<PlaidTransaction>()
                val updated = mutableListOf<PlaidTransaction>()
                val deleted = mutableListOf<PlaidTransactionId>()
                var abandonItem = false
                try {
                    // Plaid transaction batch loop
                    var hasMore: Boolean
                    do {
                        // Iterate through batches of Plaid transactions
                        // In sync mode we fetch and retain all Plaid transactions that have changed since the last poll.
                        val response = executeTransactionSyncRequest(
                            accessToken,
                            cursorMap[accessToken],
                            plaidBatchSize
                        )
                        if (response == null) {
                            // allowItemToFail: keep what earlier batches returned, move on to the next Item
                            abandonItem = true
                            break
                        }

                        cursorMap[accessToken] = response.nextCursor
                        logger.debug(
                            "Received batch of sync updates for access token ${redactAccessToken(accessToken)}: " +
                                    "${response.added.size} created; ${response.modified.size} updated; " +
                                    "${response.removed.size} deleted; next cursor ${response.nextCursor}"
                        )

                        // The transaction sync endpoint doesn't take accountId as a parameter, so do that filtering here
                        created.addAll(response.added.filter { accountIdSet.contains(it.accountId) })
                        updated.addAll(response.modified.filter { accountIdSet.contains(it.accountId) })
                        deleted.addAll(response.removed.mapNotNull { it.transactionId })

                        // Keep going until we get all the transactions
                        hasMore = response.hasMore
                    } while (hasMore)
                } catch (e: SyncMutationDuringPaginationException) {
                    if (++restarts > maxMutationRestarts) throw e
                    logger.warn(
                        "Plaid data for ${redactAccessToken(accessToken)} changed during pagination; " +
                                "restarting from the cursor this sync began at (restart $restarts of $maxMutationRestarts)"
                    )
                    if (startCursor == null) cursorMap.remove(accessToken) else cursorMap[accessToken] = startCursor
                    continue
                }
                plaidCreatedTxs.addAll(created)
                plaidUpdatedTxs.addAll(updated)
                plaidDeletedTxs.addAll(deleted)
                if (abandonItem) continue@accessTokenLoop
                break
            }
        }

        return PlaidTransactionResult(
            plaidCreatedTxs,
            plaidUpdatedTxs,
            plaidDeletedTxs
        )
    }

    /**
     * Initializes cursors for access tokens that don't have one yet.
     */
    suspend fun initializeCursors(
        accountAccessTokenSequence: Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>,
        cursorMap: MutableMap<PlaidAccessToken, PlaidSyncCursor>
    ) {
        logger.debug("Beginning Plaid sync endpoint cursor initialization")
        cursorCatchupLoop@ for ((accessToken, _) in accountAccessTokenSequence) {
            // If we already have a cursor for this access token, then move on
            if (cursorMap.contains(accessToken)) {
                logger.debug("Cursor map contains ${redactAccessToken(accessToken)}, skipping initialization for it")
                continue
            }

            // For access tokens that we don't have cursors for, iterate through historical data and ignore it
            // to get current cursors
            do {
                val response =
                    executeTransactionSyncRequest(accessToken, cursorMap[accessToken], plaidBatchSize)
                        ?: continue@cursorCatchupLoop
                logger.debug(
                    "Received initial batch of sync updates for access token ${redactAccessToken(accessToken)}. " +
                            "Updating cursor map to next cursor: ${response.nextCursor}"
                )
                if (response.nextCursor.isNotBlank()) {
                    cursorMap[accessToken] = response.nextCursor
                }
            } while (response.hasMore)
        }
    }
}

/** Plaid error_code returned when an Item's data changed between pages of a /transactions/sync pagination. */
const val MUTATION_DURING_PAGINATION = "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"

/** How many times to restart one Item's pagination because its data kept changing before giving up. */
private const val maxMutationRestarts = 3

/**
 * Plaid asked for the current /transactions/sync pagination to be restarted from its starting cursor.
 */
class SyncMutationDuringPaginationException(cause: Throwable) :
    RuntimeException("Plaid transactions changed during pagination ($MUTATION_DURING_PAGINATION)", cause)

/**
 * Data class to hold the result of processing Plaid transactions.
 */
data class PlaidTransactionResult(
    val created: List<PlaidTransaction>,
    val updated: List<PlaidTransaction>,
    val deleted: List<PlaidTransactionId>
)