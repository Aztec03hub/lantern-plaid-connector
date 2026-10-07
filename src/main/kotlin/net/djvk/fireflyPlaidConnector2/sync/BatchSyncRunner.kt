package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.plugins.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.*
import net.djvk.fireflyPlaidConnector2.constants.IntervalSeconds
import net.djvk.fireflyPlaidConnector2.constants.TimestampSeconds
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.util.Utilities.redactAccessToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/**
 * Batch sync runner.
 *
 * Handles the "batch" sync mode, which syncs a large batch of transactions at once, then exits.
 */
@ConditionalOnProperty(name = ["fireflyPlaidConnector2.syncMode"], havingValue = "batch")
@Component
class BatchSyncRunner(
    @Value("\${fireflyPlaidConnector2.batch.maxSyncDays}")
    private val syncDays: Int,
    @Value("\${fireflyPlaidConnector2.batch.setInitialBalance:false}")
    private val setInitialBalance: Boolean,
    @Value("\${fireflyPlaidConnector2.batch.balanceMinLastUpdatedDatetimeSeconds:}")
    private val balanceMinLastUpdatedDatetimeSeconds: IntervalSeconds? = null,
    @Value("\${fireflyPlaidConnector2.plaid.batchSize}")
    private val plaidBatchSize: Int,

    private val plaidApiWrapper: PlaidApiWrapper,
    private val syncHelper: SyncHelper,
    private val fireflyAccountsApi: AccountsApi,

    private val converter: TransactionConverter,

    /** Pairs what is left unpaired in Firefly after the run; null (tests) skips it. */
    private val reconciler: TransferReconciler? = null,
    ) : Runner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run() {
        val allPlaidTxs = mutableMapOf<PlaidAccessToken, MutableList<Transaction>>()

        val startDate = LocalDate.now().minusDays(syncDays.toLong())
        val endDate = LocalDate.now()

        runBlocking {
            syncHelper.setApiCreds()
            converter.accountKinds = syncHelper.fetchAccountKinds()
            val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
            // Each Plaid Item is fetched on its own, all of them at once (a few Items, each a run of sequential pages)
            val fetched = coroutineScope {
                accountAccessTokenSequence.toList()
                    .map { (accessToken, accountIds) -> async { accessToken to fetchTransactions(accessToken, accountIds, startDate, endDate) } }
                    .awaitAll()
            }
            // Joined in the order of the configured Items, so the conversion sees the same list as a serial fetch would give
            for ((accessToken, txs) in fetched) {
                allPlaidTxs.getOrPut(accessToken) { mutableListOf() }.addAll(txs)
            }

            // Map Plaid transactions to Firefly transactions
            val fireflyTxs = converter.convertBatchSync(allPlaidTxs.values.flatten(), accountMap)

            // Insert into Firefly
            syncHelper.optimisticInsertBatchIntoFirefly(fireflyTxs)

            // Whatever this run could not pair in memory (a leg imported by an earlier or parallel run) is paired
            //  from what Firefly holds now
            reconciler?.let { it.reconcile(startDate.minusDays(it.transferMatchWindowDays), endDate) }

            // Set initial balance transaction if configured
            if (setInitialBalance) {
                setInitialBalances(allPlaidTxs, syncHelper, startDate)
            }
        }
    }

    /** All of one Plaid Item's transactions between [startDate] and [endDate], page after page. */
    private suspend fun fetchTransactions(
        accessToken: PlaidAccessToken,
        accountIds: List<PlaidAccountId>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<Transaction> {
        val result = mutableListOf<Transaction>()
        logger.debug("Fetching Plaid data for access token ${redactAccessToken(accessToken)} and account ids ${accountIds.joinToString()}")
        var offset = 0
        do {
            /**
             * Iterate through batches of Plaid transactions
             *
             * We're storing all this data in memory so we can try to match up offsetting transfers before inserting
             *  into Firefly.
             * Note that the heap size may need to be increased if you're handling a ton of transactions.
             */
            /**
             * Iterate through batches of Plaid transactions
             *
             * We're storing all this data in memory so we can try to match up offsetting transfers before inserting
             *  into Firefly.
             * We don't use fireflyPlaidConnector2.transferMatchWindowDays here because if we did we'd have to
             *  do some complex rolling window shenanigans that I have no interest in implementing, and it's
             *  easy to run batch mode once on a high-spec machine.
             * Note that the heap size may need to be increased if you're handling a ton of transactions.
             */
            val request = TransactionsGetRequest(
                accessToken,
                startDate,
                endDate,
                null,
                TransactionsGetRequestOptions(
                    accountIds,
                    plaidBatchSize,
                    offset,
                    includeOriginalDescription = true,
                    includePersonalFinanceCategoryBeta = false,
                    includePersonalFinanceCategory = true,
                )
            )
            val plaidTxs: List<Transaction>
            try {
                plaidTxs = plaidApiWrapper.executeRequest(
                    { plaidApi -> plaidApi.transactionsGet(request) },
                    "transaction get request"
                ).body().transactions
                logger.debug("\tReceived a batch of ${plaidTxs.size} Plaid transactions")
            } catch (cre: ClientRequestException) {
                // The request object holds the access token, so don't log it
                logger.error(
                    "Error requesting Plaid transactions for access token ${redactAccessToken(accessToken)} " +
                            "at offset $offset"
                )
                throw cre
            }
            result.addAll(plaidTxs)

            /**
             * No dupe lookup here: every create carries its Plaid links, and Firefly's link table refuses
             *  (409) one that is already imported, which the insert treats as "already imported".
             */

            offset += plaidTxs.size

            // Keep going until we get all the transactions
        } while (plaidTxs.size == plaidBatchSize)
        logger.debug("Done fetching Plaid data for access token ${redactAccessToken(accessToken)} and account ids ${accountIds.joinToString()}")
        return result
    }


    suspend fun setInitialBalances(
        allPlaidTxs: Map<PlaidAccessToken, List<Transaction>>,
        syncHelper: SyncHelper,
        startDate: LocalDate,
    ) {
        logger.info("Attempting to set initial balances")
        val (accountMap, accountAccessTokenSequence) = syncHelper.getAllPlaidAccessTokenAccountIdSets()
        // Iterate over all Plaid items/access tokens we have configured
        for ((accessToken, accountIds) in accountAccessTokenSequence) {
            val plaidTxs = allPlaidTxs[accessToken] ?: continue
            // Request balance data for this item/access token
            logger.debug("Requesting balances for access token ${redactAccessToken(accessToken)} and account ids ${accountIds.joinToString()}")
            // Calculate min last updated, if required
            val minLastUpdated = balanceMinLastUpdatedDatetimeSeconds?.let {
                logger.debug("Setting min_last_updated_datetime to $balanceMinLastUpdatedDatetimeSeconds seconds ago")
                OffsetDateTime.now().minusSeconds(it)
            }
            val balances: AccountsGetResponse
            try {
                balances = plaidApiWrapper.executeRequest(
                    { plaidApi ->
                        plaidApi.accountsBalanceGet(
                            AccountsBalanceGetRequest(
                                accessToken, null, null, AccountsBalanceGetRequestOptions(accountIds, minLastUpdated)
                            )
                        )
                    },
                    "transaction get request"
                ).body()
            } catch (e: Exception) {
                logger.error(
                    "Failed to fetch balances for access token ${redactAccessToken(accessToken)} and account ids ${accountIds.joinToString()}",
                    e
                )
                continue
            }

            // Group transactions and balance data by Plaid account id
            val plaidTxsByAccountId = plaidTxs.groupBy { it.accountId }
            val balancesByAccountId = balances.accounts.associate { Pair(it.accountId, it.balances.current) }

            // Iterate over account ids
            for ((accountId, currentBalance) in balancesByAccountId) {
                val fireflyAccountId = accountMap[accountId]
                if (fireflyAccountId == null) {
                    logger.warn("Failed to find Firefly account id for Plaid account $accountId")
                    continue
                }
                if (currentBalance == null) {
                    logger.warn("No current balance data received for Plaid account $accountId")
                    continue
                }
                val fireflyAccount: AccountRead
                try {
                    fireflyAccount = fireflyAccountsApi.getAccount(fireflyAccountId.toString(), null).body().data
                } catch (e: Exception) {
                    logger.error("Error fetching Firefly account $fireflyAccountId", e)
                    continue
                }
                val isCreditCard = fireflyAccount.attributes.accountRole?.value == "ccAsset"

                val txs = plaidTxsByAccountId[accountId] ?: listOf()
                val total = txs.fold(0.0) { acc, tx -> acc + tx.amount }

                /**
                 * Plaid returns positive balances regardless, even though they're functionally negative for
                 *  credit card accounts.
                 */
                val initialBalance = if (isCreditCard) {
                    total - currentBalance
                } else {
                    total + currentBalance
                }

                val earliestTimestamp = txs.fold(OffsetDateTime.now()) { acc, tx ->
                    val ts = converter.getTxPostedTimestamp(tx)
                    if (ts < acc) {
                        ts
                    } else {
                        acc
                    }
                }
                logger.debug("Inserting initial balance $initialBalance for Firefly account id $fireflyAccountId")
                syncHelper.optimisticInsertBatchIntoFirefly(
                    listOf(
                        FireflyTransactionDto(
                            null, TransactionSplit(
                                /**
                                 * Would like this to be [TransactionTypeProperty.openingBalance], but the Firefly API doesn't
                                 *  let us insert with that value.
                                 *
                                 */
                                type = if (initialBalance < 0) TransactionTypeProperty.withdrawal else TransactionTypeProperty.deposit,
                                date = earliestTimestamp.minusHours(1),
                                // Sums of doubles carry binary noise (0.30000000000000004), so round to cents, and
                                //  avoid scientific notation, which Firefly rejects
                                amount = java.math.BigDecimal.valueOf(initialBalance.absoluteValue)
                                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                                description = "Plaid Connector Initial Balance",
                                sourceName = "Initial Balance",
                                sourceId = if (initialBalance < 0) fireflyAccountId.toString() else null,
                                destinationId = if (initialBalance < 0) null else fireflyAccountId.toString(),
                                order = 0,
                                reconciled = false,
                            )
                        )
                    )
                )
            }
        }
    }
}
