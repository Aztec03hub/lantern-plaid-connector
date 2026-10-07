package net.djvk.fireflyPlaidConnector2.sync

import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.PlaidLinksApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountTypeFilter
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLink
import net.djvk.fireflyPlaidConnector2.api.firefly.models.PlaidLinkConflictError
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.FireflyApiError
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.util.Utilities.forEachBounded
import net.djvk.fireflyPlaidConnector2.versionManagement.VersionComparison
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

typealias PlaidAccessToken = String
typealias PlaidAccountId = String

const val MINIMUM_FIREFLY_VERSION = "6.1.2"

/** A Plaid id that cannot exist, looked up at startup to prove the link table endpoint is there. */
private const val STARTUP_PROBE_ID = "lantern-startup-probe"

private const val NOT_THE_FORK = "This Firefly does not keep Plaid links (no /api/v1/plaid-links endpoint, or a write came " +
        "back without them), so it is not Lantern's Firefly fork. The connector relies on its Plaid link table to refuse " +
        "duplicate transactions and will not run without it. Point fireflyPlaidConnector2.firefly.url at the Lantern fork."

@Component
class SyncHelper(
    private val plaidAccountsConfig: AccountConfigs,

    @Value("\${fireflyPlaidConnector2.firefly.personalAccessToken}")
    private val fireflyAccessToken: String,
    private val fireflyAboutApi: AboutApi,
    private val fireflyTxApi: TransactionsApi,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyPlaidLinksApi: PlaidLinksApi,

    /** How many creates run at once, see [forEachBounded]; 1 writes one at a time. */
    @Value("\${fireflyPlaidConnector2.firefly.writeConcurrency:8}")
    val writeConcurrency: Int = 8,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun setApiCreds() {
        // Spring components are singletons by default, so this should set these credentials for any other
        //  component that also uses these components
        fireflyTxApi.setAccessToken(fireflyAccessToken)
        fireflyAccountsApi.setAccessToken(fireflyAccessToken)
        fireflyAboutApi.setAccessToken(fireflyAccessToken)
        fireflyPlaidLinksApi.setAccessToken(fireflyAccessToken)
        validateFireflyApiVersion()
        validatePlaidLinksEndpoint()
    }

    /**
     * The ids of Firefly's liability accounts (credit cards, loans, mortgages), which cannot be an end of a transfer.
     * Read once at startup; a liability account created later is picked up on the next start.
     */
    suspend fun fetchLiabilityAccountIds(): Set<String> {
        val ids = mutableSetOf<String>()
        var page = 1
        do {
            val response = fireflyAccountsApi.listAccount(page, null, AccountTypeFilter.liabilities).body()
            response.data.forEach { ids.add(it.id) }
            val pagination = response.meta.pagination
            val more = pagination != null && pagination.currentPage < pagination.totalPages
            page++
        } while (more)
        logger.debug("Firefly has {} liability accounts: {}", ids.size, ids)
        return ids
    }

    /**
     * The connector's dedupe is Firefly's Plaid link table, which only Lantern's Firefly fork has. On a stock Firefly
     * the `plaid_links` fields would be silently ignored and every retry would import the same transactions again, so
     * a missing endpoint stops the start instead of letting the connector run without the guarantee. The polled loop
     * calls it again before each iteration's first write, so a Firefly swapped for a stock one while the connector runs
     * fails the poll before anything is written, instead of once per poll after an unlinked create.
     */
    suspend fun validatePlaidLinksEndpoint() {
        val missing = IllegalStateException(NOT_THE_FORK)
        try {
            fireflyPlaidLinksApi.lookupPlaidLinks(listOf(STARTUP_PROBE_ID)).body()
        } catch (cre: ClientRequestException) {
            if (cre.response.status == HttpStatusCode.NotFound || cre.response.status == HttpStatusCode.MethodNotAllowed) throw missing
            throw cre
        } catch (e: NoTransformationFoundException) {
            // 200 with a body that is not JSON (an HTML page after a redirect, for example)
            throw missing
        } catch (e: io.ktor.serialization.ContentConvertException) {
            // 200 with JSON that is not a lookup answer (Ktor wraps Jackson's error in this)
            throw missing
        }
    }

    protected suspend fun validateFireflyApiVersion() {
        val fireflyVersion = fireflyAboutApi.getAbout().body().data.version
        if (!VersionComparison.isVersionSufficient(MINIMUM_FIREFLY_VERSION, fireflyVersion)) {
            throw RuntimeException("This version of the connector requires at least version $MINIMUM_FIREFLY_VERSION " +
                "of Firefly; version $fireflyVersion found")
        }
    }

    fun getAllPlaidAccessTokenAccountIdSets():
            Pair<Map<PlaidAccountId, FireflyAccountId>, Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>>> {
        val accountMap = plaidAccountsConfig.accounts.associate { Pair(it.plaidAccountId, it.fireflyAccountId) }
        logger.trace("Read config mapping data for ${accountMap.size} Firefly accounts")
        // Investment accounts are synced through a different Plaid endpoint, see getInvestmentAccessTokenAccountIdSets
        val accountsByAccessToken = plaidAccountsConfig.accounts
            .filter { !it.investment }
            .groupBy { it.plaidItemAccessToken }
        logger.trace("Read config mapping data for ${accountsByAccessToken.size} Plaid access tokens")

        return Pair(accountMap, sequence {
            for ((accessToken, accountConfigs) in accountsByAccessToken) {
                val accountIds = accountConfigs.map { it.plaidAccountId }
                yield(Pair(accessToken, accountIds))
            }
        })
    }

    /**
     * The configured accounts marked `investment: true`, grouped by Plaid Item. These are read with
     * /investments/transactions/get rather than /transactions/sync.
     */
    fun getInvestmentAccessTokenAccountIdSets(): Sequence<Pair<PlaidAccessToken, List<PlaidAccountId>>> {
        val investmentsByAccessToken = plaidAccountsConfig.accounts
            .filter { it.investment }
            .groupBy { it.plaidItemAccessToken }
        return sequence {
            for ((accessToken, accountConfigs) in investmentsByAccessToken) {
                yield(Pair(accessToken, accountConfigs.map { it.plaidAccountId }))
            }
        }
    }

    /**
     * Creates each of [fireflyTxs]. A 409 means Firefly already holds one of its Plaid ids (the link table refuses a
     * second row), so that transaction is "already imported" and skipped; the rest carry on.
     *
     * @return how many transactions were actually created (not skipped as already imported or as zero amount)
     */
    suspend fun optimisticInsertBatchIntoFirefly(fireflyTxs: List<FireflyTransactionDto>): Int {
        if (fireflyTxs.isNotEmpty()) {
            logger.debug("Optimistic insert of ${fireflyTxs.size} txs into Firefly")
        }
        // The creates are independent (each carries its own Plaid ids, and a pair is one create), so they run up to
        //  [writeConcurrency] at a time; the link table's 409 is what makes any overlap safe.
        val created = AtomicInteger(0)
        val timedOut = AtomicInteger(0)
        val firstTimeout = java.util.concurrent.atomic.AtomicReference<ConnectTimeoutException?>(null)
        fireflyTxs.forEachBounded(writeConcurrency) { fireflyTx ->
            val plaidIds = fireflyTx.tx.plaidLinks.orEmpty().map { it.plaidTransactionId }
            try {
                if (insertIntoFirefly(fireflyTx)) {
                    val n = created.incrementAndGet()
                    if (n % 100 == 0) {
                        logger.debug("Insert of tx index $n successful")
                    }
                }
            } catch (cre: ClientRequestException) {
                when (cre.response.status) {
                    HttpStatusCode.Conflict -> created.addAndGet(insertUnconflictedLegs(fireflyTx, plaidIds, cre))
                    HttpStatusCode.UnprocessableEntity -> {
                        val error = cre.response.body<FireflyApiError>()
                        // A transaction with no Plaid link (the batch opening balance) is deduped by Firefly's content hash
                        if (plaidIds.isEmpty() && error.message.lowercase().contains("duplicate of transaction")) {
                            logger.info("Skipped transaction that Firefly identified as a duplicate")
                        } else {
                            // Log the Plaid ids and Firefly's message only: the full error object and the transaction hold field values
                            logger.error("Firefly transaction insert rejected (${error.message}) for tx $plaidIds")
                            throw cre
                        }
                    }
                    else -> throw cre
                }
            } catch (e: ConnectTimeoutException) {
                // Keep going so one timeout doesn't block the rest, but remember it: silently skipping would lose
                //  the transaction for good once the caller commits its Plaid cursor.
                logger.error("Timeout inserting firefly tx $plaidIds; will fail this batch", e)
                firstTimeout.compareAndSet(null, e)
                timedOut.incrementAndGet()
            }
        }
        firstTimeout.get()?.let {
            throw java.io.IOException(
                "${timedOut.get()} of ${fireflyTxs.size} Firefly inserts timed out; the caller must retry this batch", it
            )
        }
        return created.get()
    }

    /**
     * Firefly answered 409: the body lists EVERY Plaid id of the request that is stored on a different transaction, and
     * nothing of the request was written. Ids that are all conflicting mean "already imported". If a transfer's two
     * legs were sent and only one conflicts, the other leg is NOT in Firefly, so it is created on its own (as a plain
     * withdrawal or deposit): dropping the whole write would let the caller commit its cursor over that money.
     *
     * @return how many transactions this created
     */
    private suspend fun insertUnconflictedLegs(
        fireflyTx: FireflyTransactionDto,
        plaidIds: List<String>,
        cre: ClientRequestException,
    ): Int {
        val conflicted = cre.response.body<PlaidLinkConflictError>().conflicts.map { it.plaidTransactionId }.toSet()
        check(conflicted.isNotEmpty()) { "Firefly answered 409 for $plaidIds without listing a conflicting Plaid id" }
        val missing = plaidIds.filter { it !in conflicted }
        if (missing.isEmpty()) {
            logger.info("Skipped transaction $plaidIds that Firefly already holds")
            return 0
        }
        val leg = if (plaidIds.size == 2 && conflicted.size == 1) TransactionConverter.survivingLeg(fireflyTx.tx, conflicted.single()) else null
        checkNotNull(leg) { "Firefly answered 409 for $plaidIds but only $conflicted conflict; cannot split the request" }
        logger.warn("Firefly already holds $conflicted of $plaidIds; creating $missing on its own")
        return optimisticInsertBatchIntoFirefly(listOf(FireflyTransactionDto(null, leg)))
    }

    /** @return false if the transaction was skipped (zero amount), true if it was sent */
    suspend fun insertIntoFirefly(fireflyTx: FireflyTransactionDto): Boolean {
        if (fireflyTx.tx.amount.toDouble() == 0.0) {
            logger.info("Skipped transaction ${fireflyTx.tx.plaidLinks?.map { it.plaidTransactionId }} with amount 0.0")
            return false
        }
        val stored = fireflyTxApi.storeTransaction(fireflyTx.toTransactionStore()).body().data
        requireLinksStored(fireflyTx.tx.plaidLinks, stored)
        return true
    }

    /**
     * A stock Firefly ignores `plaid_links` and answers 200, so a Firefly swapped for one while the connector runs would
     * store transactions that nothing dedupes. A write that sent links must come back carrying them.
     */
    private fun requireLinksStored(sent: List<PlaidLink>?, stored: TransactionRead) {
        val wanted = sent.orEmpty().map { it.plaidTransactionId }
        if (wanted.isEmpty()) return
        val held = stored.attributes.transactions.flatMap { it.plaidLinks.orEmpty() }.map { it.plaidTransactionId }.toSet()
        check(held.containsAll(wanted)) { NOT_THE_FORK }
    }

    suspend fun updateBatchInFirefly(fireflyTxs: List<FireflyTransactionDto>) {
        for (fireflyTx in fireflyTxs) {
            val stored = fireflyTxApi.updateTransaction(
                fireflyTx.id
                    ?: throw IllegalArgumentException(
                        "Can't update Firefly transaction without id (Plaid ids ${fireflyTx.tx.plaidLinks?.map { it.plaidTransactionId }})"
                    ),
                fireflyTx.toTransactionUpdate(),
            ).body().data
            requireLinksStored(fireflyTx.tx.plaidLinks, stored)
        }
    }

    suspend fun deleteBatchInFirefly(fireflyTxIds: List<FireflyTransactionId>) {
        if (fireflyTxIds.isNotEmpty()) {
            logger.debug("Delete batch of ${fireflyTxIds.size} txs in Firefly")
        }
        for (fireflyTxId in fireflyTxIds) {
            try {
                fireflyTxApi.deleteTransaction(fireflyTxId)
            } catch (cre: ClientRequestException) {
                // Already gone, for example deleted by an earlier attempt of an iteration that then failed and is
                //  being retried. The goal state is reached, and failing here would block the retry for good.
                if (cre.response.status != HttpStatusCode.NotFound) throw cre
                logger.info("Firefly transaction $fireflyTxId was already deleted")
            }
        }
    }
}