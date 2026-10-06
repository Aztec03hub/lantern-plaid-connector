package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.util.Utilities.redactAccessToken
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidTransactionId
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentTransaction
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentsTransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.InvestmentsTransactionsGetRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsRefreshRequest
import net.djvk.fireflyPlaidConnector2.util.Utilities
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncRequestOptions
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsSyncResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
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

    /** Only used to put an institution name on a failure report (see [ItemFailure]). */
    private val accountConfigs: AccountConfigs = AccountConfigs(listOf()),
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
        batchSize: Int = plaidBatchSize,
        /** Called with the failure when [allowItemToFail] swallows it, so the caller can report the Item. */
        onFailure: (Exception) -> Unit = {},
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
            return handleSyncFailure(accessToken, cre, onFailure)
        } catch (e: Exception) {
            return handleSyncFailure(accessToken, e, onFailure)
        }
    }

    /**
     * Network failures surface as RuntimeException (after the wrapper's retries) as well as ClientRequestException;
     * allowItemToFail applies to all of them.
     */
    private suspend fun handleSyncFailure(
        accessToken: PlaidAccessToken,
        e: Exception,
        onFailure: (Exception) -> Unit,
    ): TransactionsSyncResponse? {
        val errorCode = plaidErrorCode(e)
        logger.error(
            "Error requesting Plaid transactions for ${describeItem(accessToken)}: $errorCode"
        )
        if (allowItemToFail) {
            logger.warn("Querying transactions for ${describeItem(accessToken)} failed ($errorCode), allowing failure and continuing on to the next access token")
            onFailure(e)
            return null
        }
        throw e
    }

    /** The institution name configured for the Item, or "unnamed Item" (the redacted token tells Items apart). */
    fun describeInstitution(accessToken: PlaidAccessToken): String =
        accountConfigs.accounts.firstOrNull { it.plaidItemAccessToken == accessToken && !it.institutionName.isNullOrBlank() }
            ?.institutionName ?: "unnamed Item"

    private fun describeItem(accessToken: PlaidAccessToken): String =
        "${describeInstitution(accessToken)} (${redactAccessToken(accessToken)})"

    /**
     * Plaid's `error_code` from a failed call (for example ITEM_LOGIN_REQUIRED), or the exception class when there is
     * none (a network failure). Never the message, which can contain user data.
     */
    suspend fun plaidErrorCode(e: Exception): String {
        if (e is ClientRequestException) {
            val body = runCatching { e.response.bodyAsText() }.getOrDefault("")
            Regex("\"error_code\"\\s*:\\s*\"([A-Z_0-9]+)\"").find(body)?.let { return it.groupValues[1] }
            return "HTTP_${e.response.status.value}"
        }
        return e::class.simpleName ?: "UnknownError"
    }

    /**
     * Asks Plaid to check the institution for new transactions now (/transactions/refresh), instead of waiting for
     * Plaid's own schedule, which for some institutions is infrequent (upstream issue #69).
     *
     * The call is asynchronous on Plaid's side: new data shows up on a later /transactions/sync, so the next poll
     * picks it up. Plaid bills this endpoint per successful call.
     *
     * Never throws (except cancellation): a refresh is best effort and must not stop the sync. For example it fails
     * with a 4xx if the Item was not set up with the Transactions Refresh capability.
     *
     * @return true if Plaid accepted the request
     */
    suspend fun refreshTransactions(accessToken: PlaidAccessToken): Boolean {
        return try {
            plaidApiWrapper.executeRequest(
                { plaidApi -> plaidApi.transactionsRefresh(TransactionsRefreshRequest(accessToken)) },
                "transactions refresh request"
            )
            logger.info("Requested transactions refresh for access token ${Utilities.redactAccessToken(accessToken)}")
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.warn(
                "Transactions refresh failed for access token ${Utilities.redactAccessToken(accessToken)}: " +
                        "${e::class.simpleName}; continuing with the normal sync"
            )
            false
        }
    }

    /**
     * Reads investment transactions (upstream issue #68) for [accountIds] of one Item between [startDate] and
     * [endDate], following Plaid's offset pagination. Unlike /transactions/sync this has no cursor and no
     * modified/removed events, so callers re-read a recent window and rely on de-duplication.
     *
     * Throws on failure, including when the Item was not linked with the investments product.
     */
    suspend fun fetchInvestmentTransactions(
        accessToken: PlaidAccessToken,
        accountIds: List<PlaidAccountId>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<InvestmentTransaction> {
        val all = mutableListOf<InvestmentTransaction>()
        do {
            val request = InvestmentsTransactionsGetRequest(
                accessToken, startDate, endDate,
                options = InvestmentsTransactionsGetRequestOptions(accountIds, plaidBatchSize, all.size),
            )
            val response = plaidApiWrapper.executeRequest(
                { plaidApi -> plaidApi.investmentsTransactionsGet(request) },
                "investment transactions request"
            ).body()
            all.addAll(response.investmentTransactions)
            // Stop on an empty page too, so a total that never converges can't loop forever
        } while (response.investmentTransactions.isNotEmpty() && all.size < response.totalInvestmentTransactions)
        return all
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
        val failedItems = mutableListOf<ItemFailure>()

        accessTokenLoop@ for ((accessToken, accountIds) in accountAccessTokenSequence) {
            logger.debug(
                "Querying Plaid transaction sync endpoint for access token ${redactAccessToken(accessToken)} " +
                        " and account ids ${accountIds.joinToString("; ")}"
            )
            val accountIdSet = accountIds.toSet()

            /**
             * Plaid's contract for /transactions/sync is to keep the cursor an Item's pagination started from until
             *  the last page (has_more = false) has arrived, and only then store the final one
             *  (https://plaid.com/docs/transactions/ , https://plaid.com/docs/api/products/transactions/#transactionssync).
             *  Two consequences here:
             *  - If the Item's data changes while we're paging through it, Plaid rejects the next page with
             *    TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION and requires the whole pagination to restart from the
             *    cursor it started at. Anything fetched in the failed attempt is discarded so it isn't counted twice.
             *  - If an Item fails on any page (allowItemToFail), everything fetched for it in this call is discarded and
             *    its cursor goes back to where it started, so nothing is committed from the middle of a pagination.
             */
            val startCursor = cursorMap[accessToken]
            fun restoreStartCursor() {
                if (startCursor == null) cursorMap.remove(accessToken) else cursorMap[accessToken] = startCursor
            }
            var restarts = 0
            while (true) {
                val created = mutableListOf<PlaidTransaction>()
                val updated = mutableListOf<PlaidTransaction>()
                val deleted = mutableListOf<PlaidTransactionId>()
                var failure: Exception? = null
                try {
                    // Plaid transaction batch loop
                    var hasMore: Boolean
                    do {
                        // Iterate through batches of Plaid transactions
                        // In sync mode we fetch and retain all Plaid transactions that have changed since the last poll.
                        val response = executeTransactionSyncRequest(
                            accessToken,
                            cursorMap[accessToken],
                            plaidBatchSize,
                            onFailure = { failure = it },
                        )
                        if (response == null) {
                            // allowItemToFail: drop this Item for this poll, move on to the next one
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
                    restoreStartCursor()
                    continue
                }
                val failed = failure
                if (failed != null) {
                    restoreStartCursor()
                    failedItems.add(ItemFailure(describeInstitution(accessToken), redactAccessToken(accessToken), plaidErrorCode(failed)))
                    continue@accessTokenLoop
                }
                plaidCreatedTxs.addAll(created)
                plaidUpdatedTxs.addAll(updated)
                plaidDeletedTxs.addAll(deleted)
                break
            }
        }

        return PlaidTransactionResult(
            plaidCreatedTxs,
            plaidUpdatedTxs,
            plaidDeletedTxs,
            failedItems,
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
    val deleted: List<PlaidTransactionId>,
    /** Items that failed and were skipped for this poll ([PlaidSyncService]'s allowItemToFail); their cursors did not move. */
    val failedItems: List<ItemFailure> = listOf(),
)

/**
 * An Item (one bank login) that failed this poll. Holds no secrets: [accessTokenRedacted] is the masked token,
 * [errorCode] is Plaid's `error_code` (for example ITEM_LOGIN_REQUIRED) or the exception class for a network failure.
 */
data class ItemFailure(val institution: String, val accessTokenRedacted: String, val errorCode: String)